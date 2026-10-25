package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;
import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private static final Pattern BLOCK_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENTS = Pattern.compile("(?m)//[^\\n]*");
    private final MemorySegment metalDeviceHandle;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaView;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final MetalGpuBuffer genericVertexAttributeBuffer;
    private final DeviceInfo deviceInfo;
    private final MetalFxPipeline metalFxPipeline;
    public final MTLCommandQueue commandQueue;
    private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, IntermediaryShaderModule> shaderCache = new HashMap<>();
    private final Map<MslFunctionKey, MemorySegment> functionCache = new HashMap<>();
    private static final int MAX_POOLED_BUFFER_BUCKETS = 32;
    private static final int MAX_POOLED_BUFFERS_PER_SIZE = 8;
    /**
     * Hard cap on the total bytes retained across every pooled bucket. iOS
     * jetsams the app when its resident memory crosses ~3 GB; Sodium's
     * {@code GlBufferArena} routinely requests multi-MB vertex/index
     * buffers, so an unbounded pool could silently accumulate hundreds of
     * megabytes of "free" MTLBuffers that the OS still bills against the
     * process. Draining the pool proactively once it crosses this threshold
     * lets the OS reclaim that memory between frames instead of waiting
     * for a {@code close()} that may not come for hours.
     */
    private static final long MAX_POOLED_BYTES_TOTAL = 256L * 1024L * 1024L;
    /**
     * Buffers larger than this are never retained in the pool. They are
     * almost always one-shot chunk geometry arenas whose allocation
     * pattern (grow, free, grow again at a different size) makes pooling
     * them actively harmful: each kept entry fragments the address space
     * and the OS still charges it to the process. Releasing them eagerly
     * lets the driver return the pages immediately.
     */
    private static final long POOL_RETAIN_MAX_BUFFER_BYTES = 4L * 1024L * 1024L;
    private long pooledBytesTotal = 0L;
    private final Map<Long, Deque<MemorySegment>> bufferPool = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Deque<MemorySegment>> eldest) {
            if (size() <= MAX_POOLED_BUFFER_BUCKETS) {
                return false;
            }
            for (MemorySegment handle : eldest.getValue()) {
                MetalNativeBridge.metallum_release_object(handle);
            }
            // Keep the byte budget honest: the evicted bucket's buffers are
            // released here, not through the destroy queue.
            pooledBytesTotal -= eldest.getKey() >>> 12;
            if (pooledBytesTotal < 0L) {
                pooledBytesTotal = 0L;
            }
            return true;
        }
    };
    private static final int MAX_TEXEL_VIEWS = 128;
    private final Map<TexelTexelViewKey, MemorySegment> texelViewCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<TexelTexelViewKey, MemorySegment> eldest) {
            if (size() <= MAX_TEXEL_VIEWS) {
                return false;
            }
            MetalNativeBridge.metallum_release_object(eldest.getValue());
            return true;
        }
    };
    private ShaderSource activeShaderSource;

    MetalDevice(
            final ShaderSource defaultShaderSource,
            final GpuDebugOptions debugOptions,
            final MemorySegment metalDeviceHandle,
            final MemorySegment metalLayer,
            final String deviceName,
            final MemorySegment cocoaView
    ) {
        this.activeShaderSource = defaultShaderSource;
        this.debugOptions = debugOptions;
        this.metalDeviceHandle = metalDeviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        MetalNativeBridge.metallum_set_debug_labels_enabled(this.useLabels());
        this.commandQueue = MTLCommandQueue.create(metalDeviceHandle);
        MetalNativeBridge.metallum_init_pipelines(metalDeviceHandle);
        this.commandEncoder = new MetalCommandEncoder(this);
        // Publish device limits before anything allocates: MetalGpuBuffer
        // validates its size against maxBufferAllocationSize(), and the
        // generic vertex attribute buffer below is the first allocation.
        this.deviceInfo = buildDeviceInfo(deviceName);
        this.genericVertexAttributeBuffer = (MetalGpuBuffer) this.createBuffer(
                () -> "OpenGL generic vertex attribute defaults",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                genericVertexAttributeDefaults()
        );
        // Publish this device as the process-wide active Metal device so that
        // the public MetalCrossShaderCompiler shaderpack entry points (called
        // from the Iris intercept mixins in com.metallum.mixin.iris) can reach
        // it without a GpuDevice.backend mixin accessor. Cleared in close().
        MetalDeviceRegistry.setActiveDevice(this);
        // Query MetalFX device capabilities and load persisted user settings
        // (all defaults are OFF). Both are no-ops on devices without MetalFX;
        // the pipeline gracefully degrades to the legacy present path.
        MetalFxConfig.reload();
        MetalFxConfig.get().queryDeviceCapabilities(this.metalDeviceHandle);
        this.metalFxPipeline = new MetalFxPipeline(this.metalDeviceHandle);
        warnIfRenderScaleOverlapsMetalFx();
    }

    /**
     * One startup warning when both internal-resolution mechanisms are armed:
     * MetalFX spatial upscaling shrinks the size {@code Window} reports, and
     * {@code -Dmetallum.iris.renderScale} would shrink the main target again
     * (0.77 * 0.75 = 0.58 → visibly mushy). The MetalFX path wins; the render
     * scale is skipped at runtime (see {@code MetalFxCompat}), this line only
     * tells the user why their switch appears inert.
     */
    private static void warnIfRenderScaleOverlapsMetalFx() {
        if (MetalDebugSwitches.RENDER_SCALE == 1.0f) {
            return;
        }
        MetalFxConfig fx = MetalFxConfig.get();
        if (!fx.isSpatialUpscalingActive()) {
            return;
        }
        Metallum.LOGGER.warn(
                "[metallum-iris] both MetalFX spatial upscaling (mode={}, scale={}) and "
                        + "-Dmetallum.iris.renderScale={} are enabled; MetalFX owns the internal "
                        + "resolution and the render scale is ignored for this session",
                fx.spatialMode(), fx.spatialMode().renderScale, MetalDebugSwitches.RENDER_SCALE
        );
    }

    /**
     * @return the per-device MetalFX pipeline used by the present path to
     * optionally upscale / interpolate frames. Returns a non-null instance
     * even when MetalFX is disabled — the caller checks
     * {@link com.metallum.client.metal.fx.MetalFxConfig#isSpatialUpscalingActive()}
     * / {@link com.metallum.client.metal.fx.MetalFxConfig#isFrameInterpolationActive()}
     * before encoding.
     */
    public MetalFxPipeline metalFxPipeline() {
        return this.metalFxPipeline;
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle) {
        return new MetalSurface(this, this.metalLayer, windowHandle);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final Supplier<String> label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return this.createTexture(this.resolveDebugLabel(label), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture) {
        return this.createTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        return new MetalGpuBuffer(this, usage, size);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        if (data == null || data.remaining() <= 0) {
            throw new IllegalArgumentException("Cannot create buffer from empty ByteBuffer");
        }
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
        // A caller-supplied source belongs to this pipeline only. Persisting it
        // as the lazy fallback would make a later vanilla pipeline resolve
        // through an unrelated Iris-owned source chain.
        return this.compiledPipelines.computeIfAbsent(pipeline, p -> MetalCrossShaderCompiler.compile(this, p, effectiveSource));
    }

    @Override
    public void clearPipelineCache() {
        this.waitForSubmittedGpuWork();
        this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.compiledPipelines.clear();
        this.shaderCache.values().forEach(IntermediaryShaderModule::close);
        this.shaderCache.clear();
        for (MemorySegment function : this.functionCache.values()) {
            if (!MetalNativeBridge.isNullHandle(function)) {
                MetalNativeBridge.metallum_release_object(function);
            }
        }
        this.functionCache.clear();
        for (MemorySegment view : this.texelViewCache.values()) {
            if (!MetalNativeBridge.isNullHandle(view)) {
                MetalNativeBridge.metallum_release_object(view);
            }
        }
        this.texelViewCache.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.genericVertexAttributeBuffer.close();
        this.commandEncoder.close();
        this.clearPipelineCache();
        this.drainBufferPool();
        MetalDeviceRegistry.clearActiveDevice(this);
        try {
            this.metalFxPipeline.close();
        } catch (Throwable ignored) {
        }
        try {
            MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
        } catch (Throwable ignored) {
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    public MemorySegment metalDeviceHandle() {
        return this.metalDeviceHandle;
    }

    MetalGpuBuffer genericVertexAttributeBuffer() {
        return this.genericVertexAttributeBuffer;
    }

    static ByteBuffer genericVertexAttributeDefaults() {
        ByteBuffer defaults = ByteBuffer.allocateDirect(
                MetalCrossShaderCompiler.GENERIC_VERTEX_DEFAULT_VALUES_SIZE
        );
        MetalCrossShaderCompiler.writeGenericVertexDefaultValues(defaults);
        return defaults;
    }

    long maxBufferAllocationSize() {
        // Defensive: a buffer can be created while the device is still being
        // constructed (the constructor publishes deviceInfo before its own
        // first allocation, but future callers may not). "Unknown" is reported
        // as 0 = unlimited so a construction-order slip degrades into skipping
        // the size pre-check instead of taking the whole backend down.
        final DeviceInfo info = this.deviceInfo;
        return info == null ? 0L : info.limits().maxMemoryAllocationSize();
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final MemorySegment handle) {
        this.commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(handle));
    }

    MemorySegment tryAcquirePooledBuffer(final long size, final long resourceOptions) {
        long key = composePoolKey(size, resourceOptions);
        Deque<MemorySegment> bucket = bufferPool.get(key);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        return MemorySegment.NULL;
    }

    void queueBufferRelease(final MemorySegment handle, final long size, final long resourceOptions) {
        this.commandEncoder.queueForDestroy(() -> {
            // Never pool very large buffers (chunk geometry arenas): their
            // allocation pattern is grow-and-discard, and keeping them in
            // the pool just inflates resident memory on iOS where the OS
            // jetsams the process at ~3 GB. Release eagerly.
            if (size > POOL_RETAIN_MAX_BUFFER_BYTES) {
                MetalNativeBridge.metallum_release_object(handle);
                return;
            }
            long key = composePoolKey(size, resourceOptions);
            Deque<MemorySegment> bucket = bufferPool.computeIfAbsent(key, k -> new ArrayDeque<>());
            if (bucket.size() < MAX_POOLED_BUFFERS_PER_SIZE
                    && pooledBytesTotal + size <= MAX_POOLED_BYTES_TOTAL) {
                bucket.push(handle);
                pooledBytesTotal += size;
            } else {
                MetalNativeBridge.metallum_release_object(handle);
            }
        });
    }

    /** Returns a cached Metal texture view over a texel buffer, creating one on first use. The returned
     *  segment remains owned by this cache and must not be released by callers. */
    MemorySegment getOrCreateTexelView(
            final MetalGpuBuffer texelBuffer,
            final long pixelFormat,
            final long offset,
            final long texelCount,
            final long height,
            final long bytesPerRow
    ) {
        TexelTexelViewKey key = new TexelTexelViewKey(texelBuffer.nativeHandle().address(), pixelFormat, offset, bytesPerRow, texelCount, height);
        MemorySegment cached = texelViewCache.get(key);
        if (cached != null && !MetalNativeBridge.isNullHandle(cached)) {
            return cached;
        }
        MemorySegment view = MetalNativeBridge.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(), pixelFormat, offset, texelCount, height, bytesPerRow);
        if (!MetalNativeBridge.isNullHandle(view)) {
            texelViewCache.put(key, view);
            Stats.recordTexelViewAllocation();
        }
        return view;
    }

    static long composePoolKey(final long size, final long resourceOptions) {
        return (size << 12) | (resourceOptions & 0xFFFL);
    }

    private void drainBufferPool() {
        for (Deque<MemorySegment> bucket : bufferPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        bufferPool.clear();
        pooledBytesTotal = 0L;
    }

    /**
     * Immediately releases every pooled {@code MTLBuffer}. Called by
     * {@link MetalGpuBuffer} when {@code metallum_create_buffer} returns
     * null — i.e. when the Metal driver refused the allocation, almost
     * always because the process is approaching its resident-memory cap
     * (iOS jetsam) or hit the per-buffer size limit. Draining the pool
     * gives the driver an instant chunk of free address space to satisfy
     * the retry, instead of waiting for the next submit's destroy-queue
     * rotation to free the same buffers.
     *
     * <p>Must only be called from the render thread (the same thread that
     * owns {@link MetalCommandEncoder}). Safe to call repeatedly — drains
     * are idempotent.
     */
    void emergencyDrainBufferPool() {
        drainBufferPool();
    }

    MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
        MetalCompiledRenderPipeline irisPipeline = IrisMetalTerrainBridge.compiledPipeline(this, pipeline);
        if (irisPipeline != null) {
            return irisPipeline;
        }
        return this.compiledPipelines.computeIfAbsent(pipeline, p -> MetalCrossShaderCompiler.compile(this, p, this.activeShaderSource));
    }

    IntermediaryShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
        ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
        return this.shaderCache.computeIfAbsent(key, k -> {
            String source = shaderSource.get(k.id(), k.type());
            if (source == null) {
                return IntermediaryShaderModule.INVALID;
            }
            String sourceWithDefines = prepareShaderSource(source, k.defines());
            try (GlslCompiler glslCompiler = new GlslCompiler()) {
                return glslCompiler.createIntermediary(k.id().toDebugFileName(), sourceWithDefines, k.type());
            } catch (ShaderCompileException e) {
                throw new IllegalStateException("Failed to compile shader " + k.id(), e);
            }
        });
    }

    private static String prepareShaderSource(final String source, final ShaderDefines defines) {
        String stripped = BLOCK_COMMENTS.matcher(source).replaceAll("");
        stripped = LINE_COMMENTS.matcher(stripped).replaceAll("").stripLeading();
        return GlslPreprocessor.injectDefines(stripped, defines);
    }

    MemorySegment getOrCompileFunction(final String msl, final String entryPoint) {
        return this.functionCache.computeIfAbsent(
                new MslFunctionKey(msl, entryPoint),
                key -> MetalNativeBridge.metallum_create_shader_function(this.metalDeviceHandle, key.msl(), key.entryPoint())
        );
    }

    private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> underlyingExtensions = Set.of("CAMetalLayer", "MTLDevice");
        String osVersion = System.getProperty("os.version", "").trim();
        String platformName = MetalNativeBridge.isIOS() ? "iOS" : "macOS";
        String driverDescription = platformName + " " + osVersion;
        long maxMemoryAllocationSize = MetalNativeBridge.MTLDevice_maxMemoryAllocationSize(metalDeviceHandle);
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(
                        16,
                        256,
                        16384,
                        maxMemoryAllocationSize,
                        0,
                        ColorTargetState.MAX_COLOR_TARGETS
                ),
                new DeviceFeatures(false, false, true, true, true, false, true),
                underlyingExtensions,
                new HintsAndWorkarounds(false, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }

    private record TexelTexelViewKey(long bufferAddress, long pixelFormat, long offset, long bytesPerRow, long texelCount, long height) {
    }
}
