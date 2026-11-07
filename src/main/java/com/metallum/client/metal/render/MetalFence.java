package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Metal-side fence: a {@code submitIndex} stamped at {@link #createFence()}
 * time and resolved against the encoder's submit ring on demand.
 *
 * <p>{@code GpuFence#awaitCompletion(long)} takes <b>nanoseconds</b>
 * ({@code 0} = poll without waiting). The engine's
 * {@code StagedVertexBuffer$GpuBufferPool} polls every frame with
 * {@code awaitCompletion(0)} to decide whether staged vertex buffers can be
 * recycled, so the value must reach the backend unmodified: a millisecond
 * truncation would turn a sub-millisecond wait into a poll, and treating
 * "current submit" as an error makes that poll throw.
 *
 * <p>Once an await resolves to "completed" (or {@link #close()} is called),
 * the fence is final and later awaits return {@code true} without another
 * backend call, matching the engine reference backend's {@code GlFence}
 * {@code closedOrCompleted} field.
 */
@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closedOrCompleted;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closedOrCompleted = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        if (this.closedOrCompleted) {
            return true;
        }
        this.closedOrCompleted = this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutNS);
        return this.closedOrCompleted;
    }
}
