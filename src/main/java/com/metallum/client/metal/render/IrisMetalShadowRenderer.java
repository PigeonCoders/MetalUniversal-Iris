package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ShadowCullState;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * M6.1: the first real shadow caster pass — terrain only. Mirrors upstream
 * {@code ShadowRenderer.renderShadows} for the terrain slice:
 *
 * <ol>
 * <li>flips Iris's process-wide {@link ShadowRenderer#ACTIVE} flag so the
 *     Sodium integration shipped inside the Iris jar swaps to its shadow
 *     render lists, and this port's terrain bridge maps Sodium draws to the
 *     {@code SHADOW_SODIUM_TERRAIN_*} keys;</li>
 * <li>installs the pack shadow matrices (sun-relative view, zero-to-one ortho)
 *     on Sodium's {@code ChunkRenderMatrices};</li>
 * <li>runs {@code setupTerrain} with the shadow culling frustum, then draws
 *     the opaque group, copies shadowtex0 to shadowtex1, and optionally draws
 *     the translucent group.</li>
 * </ol>
 *
 * <p>Entities are M6.2 and deliberately not rendered here;
 * {@code IrisMetalWorldBridge} keeps its existing shadow-time null behavior.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowRenderer {
    private final MetalWorldRenderingPipeline pipeline;
    private @Nullable String reportedCulling;
    private boolean warnedSafeZone;

    IrisMetalShadowRenderer(final MetalWorldRenderingPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Renders the shadow caster pass. Returns {@code true} only when the pass
     * actually ran; any gate miss returns {@code false} so the caller can fall
     * back to the pre-M6.1 placeholder clear.
     */
    boolean render(
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        if (!MetalDebugSwitches.SHADOW_PASS) {
            this.pipeline.receipts().recordEvent("shadow.pass.disabled");
            return false;
        }
        IrisMetalShadowTargets shadows = this.pipeline.resources().shadowTargets();
        if (shadows == null) {
            this.pipeline.receipts().recordEvent("shadow.pass.no-targets");
            return false;
        }
        PackShadowDirectives directives = this.pipeline.shadowDirectives();
        if (!directives.shouldRenderTerrain()) {
            this.pipeline.receipts().recordEvent("shadow.pass.no-terrain");
            return false;
        }
        if (IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) == 0) {
            this.pipeline.receipts().recordEvent("shadow.pass.zero-distance");
            return false;
        }
        if (!(levelRenderer instanceof LevelRendererExtension extension)) {
            this.pipeline.receipts().recordEvent("shadow.scope.unavailable");
            return false;
        }
        SodiumWorldRenderer sodiumWorldRenderer = extension.sodium$getWorldRenderer();
        ShadowRenderListAccess scope = sodiumWorldRenderer instanceof ShadowRenderListAccess access
                ? access
                : null;
        if (sodiumWorldRenderer == null || scope == null) {
            this.pipeline.receipts().recordEvent("shadow.scope.unavailable");
            return false;
        }
        IrisMetalUniformValues.ShadowMatrixSet matrixSet =
                this.pipeline.uniformValues().currentShadowMatrices();
        if (!matrixSet.packValid()) {
            // No real shadow matrices (no directives / perspective pack / A-B
            // switch). Rendering the camera matrices into the shadow map would
            // only produce garbage; fall back to the cleared placeholder.
            this.pipeline.receipts().recordEvent("shadow.pass.fallback-matrices");
            return false;
        }

        Frustum frustum = createFrustum(directives, matrixSet);
        Vec3 cameraPos = camera.position();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        frustum.prepare(cameraX, cameraY, cameraZ);
        boolean spectator = camera.entity() != null && camera.entity().isSpectator();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        ChunkRenderMatrices shadowMatrices = new ChunkRenderMatrices(
                new Matrix4f(matrixSet.zeroToOneProjection()),
                new Matrix4f(matrixSet.modelView())
        );
        ChunkRenderMatrices savedMatrices = extension.sodium$getMatrices();

        pipeline.setPhase(WorldRenderingPhase.NONE);
        ShadowRenderer.ACTIVE = true;
        this.pipeline.receipts().recordEvent("shadow.pass.begin");
        try {
            scope.iris$beginShadowRenderListScope();
            extension.sodium$setMatrices(shadowMatrices);
            // Cancelled by Iris's own MixinSodiumWorldRenderer while ACTIVE is
            // set; kept for parity with upstream's call order.
            sodiumWorldRenderer.scheduleTerrainUpdate();
            sodiumWorldRenderer.setupTerrain(
                    camera,
                    ((ViewportProvider) frustum).sodium$createViewport(),
                    ((FogStorage) Minecraft.getInstance().gameRenderer).sodium$getFogParameters(),
                    spectator,
                    false,
                    ((FrustumAccessor) frustum).sodium$getMatrix()
            );
            pipeline.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
            sodiumWorldRenderer.drawChunkLayer(
                    ChunkSectionLayerGroup.OPAQUE, shadowMatrices, cameraX, cameraY, cameraZ, sampler
            );
            pipeline.setPhase(WorldRenderingPhase.NONE);
            this.pipeline.captureShadowNoTranslucents();
            if (directives.shouldRenderTranslucent()) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
                sodiumWorldRenderer.drawChunkLayer(
                        ChunkSectionLayerGroup.TRANSLUCENT, shadowMatrices, cameraX, cameraY, cameraZ, sampler
                );
                pipeline.setPhase(WorldRenderingPhase.NONE);
            }
            recordTerrainStats(directives.shouldRenderTranslucent());
        } finally {
            pipeline.setPhase(WorldRenderingPhase.NONE);
            extension.sodium$setMatrices(savedMatrices);
            scope.iris$endShadowRenderListScope();
            ShadowRenderer.ACTIVE = false;
            this.pipeline.receipts().recordEvent("shadow.pass.end");
        }
        return true;
    }

    private void recordTerrainStats(final boolean translucent) {
        String stats;
        try {
            var extractor = Minecraft.getInstance().levelExtractor;
            stats = extractor == null ? "<none>" : extractor.sectionStatistics();
        } catch (Throwable ignored) {
            stats = "<unavailable>";
        }
        this.pipeline.receipts().recordEvent(
                "shadow.terrain stats=" + stats + " translucent=" + translucent
        );
    }

    /**
     * Culling frustum selection: pack {@code shadow.culling} (DEFAULT/ADVANCED
     * use the advanced frustum, DISTANCE the box frustum) with SAFE_ZONE
     * downgraded to advanced for v1, overridable via
     * {@code -Dmetallum.iris.debug.shadowCulling=advanced|box|none}.
     */
    private Frustum createFrustum(
            final PackShadowDirectives directives,
            final IrisMetalUniformValues.ShadowMatrixSet matrixSet
    ) {
        String forced = MetalDebugSwitches.SHADOW_CULLING;
        if ("none".equalsIgnoreCase(forced)) {
            reportCulling("none (forced)");
            return new NonCullingFrustum();
        }
        if ("advanced".equalsIgnoreCase(forced)) {
            reportCulling("advanced (forced)");
            return advancedFrustum(directives, matrixSet);
        }
        if ("box".equalsIgnoreCase(forced)) {
            return boxFrustum(directives, "box (forced)");
        }
        ShadowCullState state = directives.getCullingState();
        if (state == ShadowCullState.SAFE_ZONE) {
            if (!this.warnedSafeZone) {
                this.warnedSafeZone = true;
                Metallum.LOGGER.warn(
                        "[metallum-iris] shadow.culling=SAFE_ZONE is not implemented yet;"
                                + " falling back to advanced culling"
                );
            }
            reportCulling("advanced (safe-zone degraded)");
            return advancedFrustum(directives, matrixSet);
        }
        if (state == ShadowCullState.DISTANCE) {
            return boxFrustum(directives, "box (pack)");
        }
        reportCulling("advanced (pack)");
        return advancedFrustum(directives, matrixSet);
    }

    private Frustum boxFrustum(final PackShadowDirectives directives, final String description) {
        double distance = shadowDistance(directives);
        double renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        if (distance <= 0.0 || distance > renderDistance) {
            // Upstream disables culling entirely when the pack's distance
            // exceeds the normal render distance.
            reportCulling(description + " -> none (distance)");
            return new NonCullingFrustum();
        }
        reportCulling(description);
        return new BoxCullingFrustum(new BoxCuller(distance));
    }

    private Frustum advancedFrustum(
            final PackShadowDirectives directives,
            final IrisMetalUniformValues.ShadowMatrixSet matrixSet
    ) {
        double distance = shadowDistance(directives);
        double renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        BoxCuller boxCuller = distance > 0.0 && distance < renderDistance
                ? new BoxCuller(distance)
                : null;
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        Matrix4f projView = MetalIrisDepthConvention.packProjection(state.getGbufferProjection())
                .mul(state.getGbufferModelView(), new Matrix4f());
        Vector4f lightWorld = new CelestialUniforms(this.pipeline.getSunPathRotation())
                .getShadowLightPositionInWorldSpace();
        Vector3f light = new Vector3f(lightWorld.x, lightWorld.y, lightWorld.z).normalize();
        return new AdvancedShadowCullingFrustum(projView, matrixSet.packProjection(), light, boxCuller);
    }

    private static double shadowDistance(final PackShadowDirectives directives) {
        float multiplier = directives.getDistanceRenderMul();
        if (multiplier < 0.0F) {
            return IrisVideoSettings.shadowDistance * 16.0;
        }
        return directives.getDistance() * multiplier;
    }

    private void reportCulling(final String description) {
        if (!description.equals(this.reportedCulling)) {
            this.reportedCulling = description;
            this.pipeline.receipts().recordEvent("shadow.culling=" + description);
        }
    }
}
