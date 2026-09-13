package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

/**
 * Render-thread slot for the active Iris Metal world pipeline's vanilla pass
 * redirector. Keeps core backend classes free of Iris/Minecraft linkage and
 * empty for offline Metal tests.
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalDescriptorRedirect {
    public interface Redirector {
        @Nullable RenderPassDescriptor redirect(RenderPassDescriptor descriptor);
    }

    private static volatile @Nullable Redirector active;

    private IrisMetalDescriptorRedirect() {
    }

    public static void set(@Nullable final Redirector redirector) {
        active = redirector;
    }

    public static @Nullable RenderPassDescriptor redirect(final RenderPassDescriptor descriptor) {
        Redirector redirector = active;
        return redirector == null ? null : redirector.redirect(descriptor);
    }
}
