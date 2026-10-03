package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalDebugSwitches;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code metallum.iris.debug.noVanillaSky} probe: cancels the engine's sky
 * disc and sun/moon/stars pass so the on-device artifact can be bisected
 * against the port's own sky handling. Inactive by default; the Metal debug
 * switches are read at injection time, so a vanilla session (or one where the
 * switch is off) is completely unaffected.
 */
@Mixin(SkyRenderer.class)
abstract class NoVanillaSkyMixin {
    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void metallum$skipSkyDisc(final int color, final CallbackInfo ci) {
        if (MetalDebugSwitches.NO_VANILLA_SKY) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void metallum$skipSunMoonAndStars(
            final PoseStack poseStack,
            final float f,
            final float g,
            final float h,
            final MoonPhase moonPhase,
            final float i,
            final float j,
            final CallbackInfo ci
    ) {
        if (MetalDebugSwitches.NO_VANILLA_SKY) {
            ci.cancel();
        }
    }
}
