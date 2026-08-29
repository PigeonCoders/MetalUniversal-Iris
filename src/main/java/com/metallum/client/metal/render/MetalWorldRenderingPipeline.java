package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.BitSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backend-owned Iris world-pipeline generation.
 *
 * <p>Iris remains the source of truth for pack parsing and dimension program
 * selection. This object mirrors the CPU-visible world semantics without
 * constructing {@code IrisRenderingPipeline}'s OpenGL programs, framebuffers,
 * samplers, or images. GPU program/resource ownership is connected in later
 * focused commits before the Iris factory is redirected here.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalWorldRenderingPipeline extends VanillaRenderingPipeline {
    private static final AtomicInteger GENERATIONS = new AtomicInteger();

    private final int generation;
    private final ProgramSet programSet;
    private final ShaderPack pack;
    private final PackDirectives directives;
    private final OptionalInt forcedShadowRenderDistanceChunks;
    private final IrisMetalFrameState frameState = new IrisMetalFrameState();
    private final IrisMetalUniformValues uniformValues;
    private final IrisMetalWorldPrograms programs;
    private final IrisMetalExecutionGraph executionGraph;
    private final IrisMetalRuntimeReceipts receipts;
    private IrisMetalCompiledPrograms compiledPrograms;
    private IrisMetalWorldResources resources;
    private @Nullable IrisMetalCenterDepthSampler centerDepthSampler;
    private MetalDevice centerDepthDevice;
    private boolean initializedBlockIds;
    private int receiptWidth = -1;
    private int receiptHeight = -1;

    public MetalWorldRenderingPipeline(final ProgramSet programSet) {
        this.generation = GENERATIONS.incrementAndGet();
        this.programSet = Objects.requireNonNull(programSet, "programSet");
        this.programs = new IrisMetalWorldPrograms(this.generation, this.programSet);
        this.executionGraph = new IrisMetalExecutionGraph(
                this.generation,
                this.programSet,
                this.programs,
                IrisMetalRenderTargetFormats.from(this.programSet.getPackDirectives()).length
        );
        this.receipts = IrisMetalRuntimeReceipts.open(this.generation);
        this.pack = programSet.getPack();
        this.directives = programSet.getPackDirectives();
        this.forcedShadowRenderDistanceChunks = forcedShadowDistance(
                this.directives.getShadowDirectives()
        );
        CustomUniforms customUniforms = this.pack.customUniforms.build(holder ->
                CommonUniforms.addNonDynamicUniforms(
                        holder,
                        this.pack.getIdMap(),
                        this.directives,
                        this.frameState.updateNotifier()
                )
        );
        this.uniformValues = new IrisMetalUniformValues(
                this.directives.getSunPathRotation(),
                customUniforms,
                this.frameState.updateNotifier(),
                () -> this.frameState.phase().ordinal()
        );
        this.executionGraph.attachUniformValues(this.uniformValues);
        publishWorldSettings();
        IrisMetalPackLifecycle.onSemanticPipelineActivated();
    }

    private static OptionalInt forcedShadowDistance(final PackShadowDirectives shadow) {
        if (!shadow.isDistanceRenderMulExplicit()) {
            return OptionalInt.empty();
        }
        if (shadow.getDistanceRenderMul() < 0.0F) {
            return OptionalInt.of(-1);
        }
        return OptionalInt.of((int) Math.ceil(
                shadow.getDistance() * shadow.getDistanceRenderMul() / 16.0F
        ));
    }

    private void publishWorldSettings() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
        settings.setEntityIds(this.pack.getIdMap().getEntityIdMap());
        settings.setItemIds(this.pack.getIdMap().getItemIdMap());
        settings.setAmbientOcclusionLevel(this.directives.getAmbientOcclusionLevel());
        settings.setDisableDirectionalShading(!this.directives.isOldLighting());
        settings.setUseSeparateAo(this.directives.shouldUseSeparateAo());
        settings.setBreaksAnisotropy(this.directives.breaksAnisotropy());
        settings.setVoxelizeLightBlocks(this.directives.shouldVoxelizeLightBlocks());
        settings.setSeparateEntityDraws(this.directives.shouldUseSeparateEntityDraws());
    }

    ProgramSet programSet() {
        return this.programSet;
    }

    int generation() {
        return this.generation;
    }

    IrisMetalWorldPrograms programs() {
        return this.programs;
    }

    IrisMetalCompiledPrograms compiledPrograms() {
        if (this.compiledPrograms == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " has not prepared compiled programs"
            );
        }
        return this.compiledPrograms;
    }

    IrisMetalWorldResources resources() {
        if (this.resources == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " has not prepared GPU resources"
            );
        }
        return this.resources;
    }

    /** Returns the generation-owned pack uniform block for a terrain shader key. */
    GpuBufferSlice uniformSlice(final ShaderKey key) {
        GpuBufferSlice slice = this.uniformValues.slice(key);
        if (slice == null) {
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation
                            + " has no prepared uniform block for " + key
            );
        }
        return slice;
    }

    boolean shouldOverrideCoreShaders(final boolean writesMainTarget) {
        return this.frameState.shouldOverrideShaders(writesMainTarget);
    }

    BitSet shadowReadSnapshot() {
        return this.executionGraph.shadowReadSnapshot();
    }

    @Override
    public void beginLevelRendering() {
        if (!this.initializedBlockIds) {
            // IrisRenderingPipeline publishes these maps on the first world
            // frame; IrisExclusiveUniforms.getCurrentSelectedBlockId reads
            // them from WorldRenderingSettings during the uniform-graph update.
            WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
            settings.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                    this.pack.getIdMap().getBlockProperties(),
                    this.pack.getIdMap().getTagEntries()
            ));
            settings.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                    this.pack.getIdMap().getBlockRenderTypeMap()
            ));
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.levelExtractor.allChanged();
            }
            this.initializedBlockIds = true;
        }
        this.receipts.recordEvent("frame.begin");
        prepareResources();
        prepareTerrainUniforms();
        Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
        this.executionGraph.beginFrame(
                this.resources(), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)
        );
        this.frameState.beginWorldRendering();
        this.receipts.recordEvent("setup");
        this.executionGraph.executeSetup(this.resources());
        this.receipts.recordEvent("begin");
        this.executionGraph.executeBegin(this.resources());
    }

    private void prepareTerrainUniforms() {
        for (ShaderKey key : new ShaderKey[]{
                ShaderKey.SODIUM_TERRAIN_SOLID,
                ShaderKey.SODIUM_TERRAIN_CUTOUT,
                ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
                ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
        }) {
            this.programs.sodium(key.getProgram(), key.getAlphaTest()).ifPresent(
                    linked -> this.uniformValues.register(key, "sodium_" + key.getName(), linked)
            );
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal terrain uniforms have no active Metal device");
        }
        this.uniformValues.prewarm(device);
        this.uniformValues.updateFrame();
    }

    private void prepareResources() {
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal world pipeline has no active Metal device");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            throw new IllegalStateException("Iris Metal world pipeline has no game renderer");
        }
        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (mainTarget.width <= 0 || mainTarget.height <= 0) {
            throw new IllegalStateException(
                    "Iris Metal main target has invalid extent "
                            + mainTarget.width + "x" + mainTarget.height
            );
        }
        if (this.receiptWidth < 0) {
            this.receipts.recordEvent("generation.allocate");
        } else if (this.receiptWidth != mainTarget.width || this.receiptHeight != mainTarget.height) {
            this.receipts.recordEvent("resize");
        }
        this.receiptWidth = mainTarget.width;
        this.receiptHeight = mainTarget.height;
        if (this.compiledPrograms == null) {
            this.compiledPrograms = new IrisMetalCompiledPrograms(
                    device,
                    this.generation,
                    this.programs,
                    IrisMetalRenderTargetFormats.from(this.directives)
            );
        } else if (!this.compiledPrograms.isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal compiled generation crossed Metal device ownership");
        }
        if (this.resources == null) {
            this.resources = new IrisMetalWorldResources(
                    device,
                    this.generation,
                    this.programSet,
                    mainTarget.width,
                    mainTarget.height
            );
        } else {
            if (!this.resources.isOwnedBy(device)) {
                throw new IllegalStateException("Iris Metal generation crossed Metal device ownership");
            }
            this.resources.resize(mainTarget.width, mainTarget.height);
        }
        if (this.centerDepthSampler == null) {
            ShaderSource fallback = (identifier, type) -> {
                throw new IllegalStateException(
                        "Unexpected fallback shader lookup while creating Iris center-depth sampler: "
                                + identifier + " / " + type
                );
            };
            this.centerDepthSampler = new IrisMetalCenterDepthSampler(
                    device,
                    this.generation,
                    Math.max(0.001F, this.directives.getCenterDepthHalfLife()),
                    fallback
            );
            this.centerDepthDevice = device;
            this.executionGraph.setCenterDepthSampler(this.centerDepthSampler);
        } else if (this.centerDepthDevice != device) {
            throw new IllegalStateException("Iris center-depth sampler crossed Metal device ownership");
        }
        this.executionGraph.prepare(
                device,
                this.resources,
                this.uniformValues,
                mainTarget.getColorTexture().getFormat()
        );
    }

    @Override
    public void beginTranslucents() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        if (depth == null) {
            throw new IllegalStateException("Iris translucent boundary has no main depth texture");
        }
        this.receipts.recordEvent("depthtex1.capture");
        this.executionGraph.captureNoTranslucentsDepth(this.resources(), depth);
        this.receipts.recordEvent("deferred");
        this.executionGraph.executeDeferred(this.resources());
    }

    @Override
    public void beginHand() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        GpuTextureView depthView = target.getDepthTextureView();
        if (depth == null || depthView == null) {
            throw new IllegalStateException("Iris hand boundary has no main depth texture view");
        }
        this.receipts.recordEvent("center-depth.sample");
        this.executionGraph.sampleCenterDepth(depthView, 1.0F / 60.0F);
        this.receipts.recordEvent("depthtex2.capture");
        this.executionGraph.captureNoHandDepth(this.resources(), depth);
    }

    @Override
    public void renderShadows(
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        if (this.directives.isPrepareBeforeShadow()) {
            this.receipts.recordEvent("prepare");
            this.executionGraph.executePrepare(this.resources());
        }
        this.receipts.recordEvent("shadow.render.begin");
        super.renderShadows(levelRenderer, camera, cameraRenderState);
        this.receipts.recordEvent("shadow.render.end");
        if (!this.directives.isPrepareBeforeShadow()) {
            this.receipts.recordEvent("prepare");
            this.executionGraph.executePrepare(this.resources());
        }
        this.receipts.recordEvent("shadow.composite");
        this.executionGraph.executeShadowComposite(this.resources());
    }

    @Override
    public void finalizeLevelRendering() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depth = target.getDepthTexture();
        GpuTextureView colorView = target.getColorTextureView();
        if (depth == null || colorView == null) {
            throw new IllegalStateException("Iris final boundary has no main target textures");
        }
        this.receipts.recordEvent("depthtex0.capture");
        this.executionGraph.captureFinalDepth(this.resources(), depth);
        this.receipts.recordEvent("composite");
        this.executionGraph.executeComposite(this.resources());
        this.receipts.recordEvent("final");
        this.executionGraph.executeFinal(this.resources(), colorView);
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris final readback has no active Metal device");
        }
        this.receipts.captureFinalTarget(
                device,
                device.createCommandEncoder(),
                colorView
        );
        this.frameState.endWorldRendering();
    }

    @Override
    public void destroy() {
        IrisMetalPackLifecycle.onSemanticPipelineDestroyed();
        this.frameState.endWorldRendering();
        this.receipts.recordEvent("generation.destroy");
        if (this.compiledPrograms != null) {
            this.compiledPrograms.close();
            this.compiledPrograms = null;
        }
        this.executionGraph.close();
        if (this.centerDepthSampler != null) {
            this.centerDepthSampler.close();
            this.centerDepthSampler = null;
        }
        this.centerDepthDevice = null;
        this.programs.close();
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.uniformValues.close();
        this.receipts.close();
        super.destroy();
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return this.directives.getTextureMap();
    }

    @Override
    public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
        return this.forcedShadowRenderDistanceChunks;
    }

    @Override
    public WorldRenderingPhase getPhase() {
        return this.frameState.phase();
    }

    @Override
    public void setPhase(final WorldRenderingPhase phase) {
        this.frameState.setPhase(phase);
    }

    @Override
    public void setOverridePhase(final WorldRenderingPhase phase) {
        this.frameState.setOverridePhase(phase);
    }

    @Override
    public FrameUpdateNotifier getFrameUpdateNotifier() {
        return this.frameState.updateNotifier();
    }

    @Override
    public void setIsMainBound(final boolean mainBound) {
        this.frameState.setMainBound(mainBound);
    }

    @Override
    public void onBeginClear() {
        this.frameState.setPhase(WorldRenderingPhase.SKY);
    }

    @Override
    public float getSunPathRotation() {
        return this.directives.getSunPathRotation();
    }

    @Override
    public boolean shouldRenderUnderwaterOverlay() {
        return this.directives.underwaterOverlay();
    }

    @Override
    public boolean shouldRenderVignette() {
        return this.directives.vignette();
    }

    @Override
    public boolean shouldRenderSun() {
        return this.directives.shouldRenderSun();
    }

    @Override
    public boolean shouldRenderWeather() {
        return this.directives.shouldRenderWeather();
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return this.directives.shouldRenderWeatherParticles();
    }

    @Override
    public boolean shouldRenderMoon() {
        return this.directives.shouldRenderMoon();
    }

    @Override
    public boolean shouldRenderStars() {
        return this.directives.shouldRenderStars();
    }

    @Override
    public boolean shouldRenderSkyDisc() {
        return this.directives.shouldRenderSkyDisc();
    }

    @Override
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return this.directives.rainDepth();
    }

    @Override
    public ParticleRenderingSettings getParticleRenderingSettings() {
        return this.directives.getParticleRenderingSettings();
    }

    @Override
    public boolean allowConcurrentCompute() {
        return this.directives.getConcurrentCompute();
    }

    @Override
    public boolean hasFeature(final FeatureFlags feature) {
        return this.pack.hasFeature(feature);
    }

    @Override
    public boolean shouldDisableDirectionalShading() {
        return this.programSet != null && !this.programSet.getPackDirectives().isOldLighting();
    }

    @Override
    public boolean shouldDisableFrustumCulling() {
        return !this.directives.shouldUseFrustumCulling();
    }

    @Override
    public boolean shouldDisableOcclusionCulling() {
        return !this.directives.shouldUseOcclusionCulling();
    }

    @Override
    public CloudSetting getCloudSetting() {
        return this.directives.getCloudSetting();
    }

    @Override
    public boolean supportsEndFlash() {
        return this.directives.supportsEndFlash();
    }
}
