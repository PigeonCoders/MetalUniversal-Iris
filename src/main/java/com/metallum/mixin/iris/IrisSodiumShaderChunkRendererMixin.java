package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalTerrainBridge;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the ShaderKey selected for the current Sodium terrain pass. */
@Mixin(ShaderChunkRenderer.class)
public abstract class IrisSodiumShaderChunkRendererMixin {
    @Inject(method = "begin", at = @At("RETURN"), remap = false)
    private void metallum$beginIrisTerrain(
            final TerrainRenderPass pass,
            final FogParameters fog,
            final GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        IrisMetalTerrainBridge.begin(pass);
    }

    @Inject(method = "end", at = @At("RETURN"), remap = false)
    private void metallum$endIrisTerrain(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        IrisMetalTerrainBridge.end(pass);
    }
}
