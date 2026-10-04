package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shadows.ShadowRenderer;
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
import net.minecraft.world.level.dimension.DimensionType;
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
    private @Nullable HorizonRenderer horizonRenderer;
    private @Nullable IrisMetalShadowRenderer shadowRenderer;
    private MetalDevice centerDepthDevice;
    private int receiptWidth = -1;
    private int receiptHeight = -1;
    private boolean blockIdsInitialized;

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
        this.executionGraph.attachReceipts(this.receipts);
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
                () -> this.frameState.phase().ordinal(),
                this.directives.getShadowDirectives()
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

    IrisMetalUniformValues uniformValues() {
        return this.uniformValues;
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

    IrisMetalRuntimeReceipts receipts() {
        return this.receipts;
    }

    PackShadowDirectives shadowDirectives() {
        return this.directives.getShadowDirectives();
    }

    /** Copies shadowtex0 into shadowtex1 at the opaque/translucent caster boundary. */
    void captureShadowNoTranslucents() {
        this.executionGraph.captureShadowNoTranslucents(this.resources());
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
        this.receipts.recordEvent("frame.begin");
        IrisMetalWorldBridge.beginFrame();
        initializeBlockIds();
        prepareResources();
        prepareWorldUniforms();
        Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
        this.executionGraph.beginFrame(
                this.resources(), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)
        );
        // Upstream order: clear shadowtex0 -> top-level shadow.csh dispatch at
        // the shadow-map extent -> clear shadowcolor on both sides.
        this.executionGraph.clearShadowDepth(this.resources());
        this.executionGraph.executeShadowComputes(this.resources());
        this.executionGraph.clearShadowColors(this.resources());
        this.frameState.beginWorldRendering();
        this.receipts.recordEvent("setup");
        this.executionGraph.executeSetup(this.resources());
        this.receipts.recordEvent("begin");
        this.executionGraph.executeBegin(this.resources());
    }

    /**
     * One-time per-generation mirror of {@code IrisRenderingPipeline}'s block-id
     * initialization. The Metal pipeline does not construct the GL pipeline, so
     * without this {@code WorldRenderingSettings.getBlockStateIds()} stays null
     * and {@code IrisExclusiveUniforms.getCurrentSelectedBlockId()} NPEs as soon
     * as the player looks at a block.
     */
    private void initializeBlockIds() {
        if (this.blockIdsInitialized) {
            return;
        }
        WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                this.pack.getIdMap().getBlockProperties(),
                this.pack.getIdMap().getTagEntries()
        ));
        WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                this.pack.getIdMap().getBlockRenderTypeMap()
        ));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.levelExtractor != null) {
            minecraft.levelExtractor.allChanged();
        }
        this.blockIdsInitialized = true;
    }

    private void prepareWorldUniforms() {
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
        // Pack uniform blocks for every world key the world bridge can
        // install (M1 + M2 + M3), so uniformSlice(key) succeeds at draw time.
        for (ShaderKey key : IrisMetalWorldBridge.WORLD_OVERRIDE_KEYS) {
            IrisMetalWorldBridge.ProgramRequest request = IrisMetalWorldBridge.shaderKeyToProgramRequest(key);
            this.programs.vanilla(
                    request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
            ).ifPresent(linked -> this.uniformValues.register(
                    key, "vanilla_" + key.getName(), linked
            ));
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
                    IrisMetalRenderTargetFormats.from(this.directives),
                    IrisMetalShadowTargets.colorFormats(this.programSet)
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
        this.receipts.recordEvent("depthtex1.capture");
        if (usesVanillaSceneDepth()) {
            // Vanilla depth is the authoritative scene depth: taken-over draws
            // test/write it, so depthtex0 and depthtex1 are derived from it
            // (terrain + entities, before translucent water).
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTexture depth = target.getDepthTexture();
            if (depth == null) {
                throw new IllegalStateException("Iris translucent boundary has no main depth texture");
            }
            this.executionGraph.captureNoTranslucentsDepth(this.resources(), depth);
        } else {
            // Legacy Iris-depth path: the vanilla depth was already folded into
            // depthtex0 by the world bridge's first rewrite of this frame, so
            // the translucent boundary only needs mainDepth -> noTranslucents.
            this.executionGraph.captureNoTranslucentsDepthOnly(this.resources());
        }
        this.receipts.recordEvent("deferred");
        if (!MetalDebugSwitches.SKIP_DEFERRED) {
            this.executionGraph.executeDeferred(this.resources());
        }
    }

    @Override
    public void beginHand() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView depthView = target.getDepthTextureView();
        if (depthView == null) {
            throw new IllegalStateException("Iris hand boundary has no main depth texture view");
        }
        this.receipts.recordEvent("center-depth.sample");
        this.executionGraph.sampleCenterDepth(depthView, 1.0F / 60.0F);
        this.receipts.recordEvent("depthtex2.capture");
        if (usesVanillaSceneDepth()) {
            // depthtex2 = current scene (terrain + entities + water) without
            // hand; hand and particles still draw into the vanilla depth, so
            // vanilla is the authoritative source.
            this.executionGraph.captureNoHandDepth(this.resources(), depthView.texture());
        } else {
            // Legacy Iris-depth path: depthtex2 comes from the Iris mainDepth.
            this.executionGraph.captureNoHandDepthOnly(this.resources());
        }
    }

    /**
     * Whether taken-over world draws used the vanilla scene depth attachment
     * (default) instead of the Iris depthtex0 texture. Mirrors the choice in
     * {@link IrisMetalWorldBridge#rewriteWorldDescriptor}; captures must be
     * sourced accordingly.
     */
    private static boolean usesVanillaSceneDepth() {
        return MetalDebugSwitches.WORLD_PASS && MetalDebugSwitches.WORLD_PASS_DEPTH_VANILLA;
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
        boolean castersRendered = false;
        if (!MetalDebugSwitches.NO_SHADOWS) {
            IrisMetalShadowRenderer renderer = this.shadowRenderer;
            if (renderer == null) {
                renderer = new IrisMetalShadowRenderer(this);
                this.shadowRenderer = renderer;
            }
            castersRendered = renderer.render(levelRenderer, camera, cameraRenderState);
        }
        this.receipts.recordEvent("shadow.render.end");
        if (!this.directives.isPrepareBeforeShadow()) {
            this.receipts.recordEvent("prepare");
            this.executionGraph.executePrepare(this.resources());
        }
        this.receipts.recordEvent("shadow.composite");
        this.executionGraph.executeShadowComposite(this.resources(), castersRendered);
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
        // M1: captureFinalDepth still folds the vanilla depth back into
        // depthtex0 because hand/particles/weather draw into the vanilla depth
        // until M2/M3 wire them up; their depth must not be lost.
        this.executionGraph.captureFinalDepth(this.resources(), depth);
        this.receipts.recordEvent("composite");
        if (!MetalDebugSwitches.SKIP_POST) {
            this.executionGraph.executeComposite(this.resources());
        }
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
        // Defensive: a driver that threw mid-pass must not leave Iris's
        // process-wide shadow flag set for the next generation.
        ShadowRenderer.ACTIVE = false;
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
        if (this.horizonRenderer != null) {
            this.horizonRenderer.destroy();
            this.horizonRenderer = null;
        }
        if (this.shadowRenderer != null) {
            this.shadowRenderer.close();
            this.shadowRenderer = null;
        }
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

        // Upstream parity: IrisRenderingPipeline.onBeginClear() (L1286-1303 in
        // the pinned 20e226b tree) draws HorizonRenderer's inverted cone before
        // sky rendering. 26.2's vanilla SkyRenderer only covers the upper
        // hemisphere (sky disc at y=+16), so without this pass the lower half
        // of the sky stays clear color. The cone is drawn through the vanilla
        // SKY pipeline; HorizonRendererMixin arms the world override for it.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (!this.shouldRenderSkyDisc()) {
            return;
        }
        DimensionType dimensionType = minecraft.level.dimensionType();
        if (dimensionType.skybox() != DimensionType.Skybox.OVERWORLD && !dimensionType.hasSkyLight()) {
            return;
        }
        if (this.horizonRenderer == null) {
            this.horizonRenderer = new HorizonRenderer();
        }
        Vector3d fogColor3 = CapturedRenderingState.INSTANCE.getFogColor();
        // NB: The alpha value must be 1.0 here, or shader packs get pink
        // reflections and similar bugs (upstream comment on the same value).
        Vector4f fogColor = new Vector4f((float) fogColor3.x, (float) fogColor3.y, (float) fogColor3.z, 1.0F);
        this.horizonRenderer.renderHorizon(
                CapturedRenderingState.INSTANCE.getGbufferModelView(),
                CapturedRenderingState.INSTANCE.getGbufferProjection(),
                fogColor
        );
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
        return !MetalDebugSwitches.NO_VANILLA_SKY && this.directives.shouldRenderSun();
    }

    @Override
    public boolean shouldRenderWeather() {
        return !MetalDebugSwitches.NO_VANILLA_SKY && this.directives.shouldRenderWeather();
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return !MetalDebugSwitches.NO_VANILLA_SKY && this.directives.shouldRenderWeatherParticles();
    }

    @Override
    public boolean shouldRenderMoon() {
        return !MetalDebugSwitches.NO_VANILLA_SKY && this.directives.shouldRenderMoon();
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
        if (MetalDebugSwitches.NO_VANILLA_SKY || MetalDebugSwitches.NO_VANILLA_CLOUDS) {
            return CloudSetting.OFF;
        }
        return this.directives.getCloudSetting();
    }

    @Override
    public boolean supportsEndFlash() {
        return this.directives.supportsEndFlash();
    }
}
