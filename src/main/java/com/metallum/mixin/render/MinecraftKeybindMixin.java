package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxOptionsScreen;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback keybind: opens the MetalFX options screen when the user
 * presses {@code F8}. This is a polled approach (no registered
 * {@code KeyMapping}) so it works without Fabric API and shows up
 * nowhere in the controls menu — the trade-off for being a "hidden"
 * fallback. The key is documented in the MetalFX button's tooltip
 * (translation key {@code metallum.fx.button.open.tooltip}).
 *
 * <p>F8 was chosen because vanilla Minecraft does not bind it by
 * default on either macOS or iOS. Pressing it while a non-MetalFX
 * screen is open (or no screen is open) opens the MetalFX options
 * directly. Pressing it while the MetalFX options is already open
 * does nothing (avoids recursive parent chains).
 *
 * <p>The mixin only fires on Metal backends; on OpenGL/Vulkan the
 * F8 key is left untouched for other mods / vanilla debug overlays.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftKeybindMixin {
    private boolean metallum$f8WasDown = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private void metallum$pollF8Keybind(CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        Window window = self.getWindow();
        boolean f8Down = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_F8);
        if (!f8Down || this.metallum$f8WasDown) {
            this.metallum$f8WasDown = f8Down;
            return;
        }
        this.metallum$f8WasDown = true;

        // Only handle on Metal backends; without Metal there is no MetalFX.
        if (!metallum$isMetalBackend()) {
            return;
        }

        Screen current = self.gui.screen();
        // Don't open recursively if the user is already on a MetalFX screen
        // (either the warning dialog or the options screen itself).
        if (current instanceof MetalFxOptionsScreen
                || current instanceof MetalFxWarningScreen) {
            return;
        }

        // Reload persisted config so the freshly-opened screen reflects any
        // out-of-band edits (e.g. config file tweaks on iOS via Files app).
        MetalFxConfig.reload();
        // Route through the warning screen helper so the user sees the
        // compatibility warning the first time, identical to the button path.
        MetalFxWarningScreen.openIfNotAcknowledged(current);
    }

    private static boolean metallum$isMetalBackend() {
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            if (device == null) {
                return false;
            }
            return "Metal".equals(device.getDeviceInfo().backendName());
        } catch (Throwable t) {
            return false;
        }
    }
}
