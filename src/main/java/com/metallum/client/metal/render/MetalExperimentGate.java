package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Temporary bisection switch; enabled by default on iOS devices only. */
@Environment(EnvType.CLIENT)
public final class MetalExperimentGate {
    private MetalExperimentGate() {
    }

    public static boolean enabled(final String property) {
        if (!Boolean.parseBoolean(System.getProperty(property, "true"))) {
            return false;
        }
        try {
            return MetalNativeBridge.isIOS();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
