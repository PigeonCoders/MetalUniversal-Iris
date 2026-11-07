package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

/**
 * Per-encoder cache of the draw state a render pass has pushed to the open
 * Metal render encoder.
 *
 * <p>Metal encoders do not inherit state: a freshly created
 * {@code MTLRenderCommandEncoder} has no pipeline, no vertex buffers, no
 * scissor rect and no argument-table bindings. The dirty flags in
 * {@link MetalRenderPass} exist to skip re-pushing unchanged state, so they are
 * only valid for the encoder instance that actually received that state. When
 * the encoder is torn down and re-created mid-pass — which happens whenever an
 * interleaved clear/blit/compute encoding ends it, or when the attachment
 * action masks change and {@code renderCommandEncoder} opens a new one — the
 * new encoder starts empty while the flags would still claim "clean". The next
 * draw would then run with no pipeline / stale scissor / missing descriptors.
 *
 * <p>{@link #bindEncoder} tracks the encoder identity and invalidates every
 * cached flag when a different encoder shows up, so the next draw re-pushes
 * the full state. Kept native-free and free of Metal types so the identity
 * contract is unit testable with arbitrary stand-in encoder objects.
 */
@Environment(EnvType.CLIENT)
final class MetalEncoderBindings {
    /**
     * Every descriptor bit set. A safe over-approximation of a pipeline's
     * resource mask: pushes ignore bits that do not belong to a resource, and
     * no pipeline uses 64 or more descriptor slots.
     */
    static final long ALL_DESCRIPTORS_DIRTY = -1L;

    /** Last encoder that received draw state, compared by identity only. */
    @Nullable
    private Object encoder;
    private boolean pipelineDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean scissorDirty = true;
    private long dirtyDescriptorMask = ALL_DESCRIPTORS_DIRTY;

    /**
     * Records the encoder the next draw will be encoded against.
     *
     * <p>Identity comparison, not {@code equals}: two Metal encoders are equal
     * only when they are the same native encoder. First bind also returns
     * {@code true} (the flags start dirty), so callers never have to special
     * case the initial encoder.
     *
     * @return {@code true} when the encoder changed and every cached flag was
     *         invalidated, {@code false} when the previous state still applies
     */
    boolean bindEncoder(final @Nullable Object encoder) {
        if (this.encoder == encoder) {
            return false;
        }
        this.encoder = encoder;
        invalidate();
        return true;
    }

    /**
     * Drops every cached flag: the next {@code bindDrawState} re-pushes the
     * pipeline, scissor, vertex buffers and all descriptors onto whatever
     * encoder is current. Called when an encoder is ended
     * ({@link MetalCommandEncoder#endEncoder()}) or replaced mid-pass.
     */
    void invalidate() {
        this.pipelineDirty = true;
        this.vertexBuffersDirty = true;
        this.scissorDirty = true;
        this.dirtyDescriptorMask = ALL_DESCRIPTORS_DIRTY;
    }

    /** Whether the next draw needs the encoder to be (re)bound at all. */
    boolean needsDrawState() {
        return pipelineDirty || vertexBuffersDirty || scissorDirty || dirtyDescriptorMask != 0L;
    }

    boolean isPipelineDirty() {
        return pipelineDirty;
    }

    void markPipelineDirty() {
        this.pipelineDirty = true;
    }

    void clearPipelineDirty() {
        this.pipelineDirty = false;
    }

    boolean isVertexBuffersDirty() {
        return vertexBuffersDirty;
    }

    void markVertexBuffersDirty() {
        this.vertexBuffersDirty = true;
    }

    void clearVertexBuffersDirty() {
        this.vertexBuffersDirty = false;
    }

    boolean isScissorDirty() {
        return scissorDirty;
    }

    void markScissorDirty() {
        this.scissorDirty = true;
    }

    void clearScissorDirty() {
        this.scissorDirty = false;
    }

    long dirtyDescriptorMask() {
        return dirtyDescriptorMask;
    }

    /** Marks one descriptor slot (by {@code descriptorMaskIndex}) for re-push. */
    void markDescriptorDirty(final int descriptorMaskIndex) {
        dirtyDescriptorMask |= 1L << descriptorMaskIndex;
    }

    /** Marks every descriptor bit in {@code mask} for re-push. */
    void markDescriptors(final long mask) {
        dirtyDescriptorMask |= mask;
    }

    void clearDescriptorMask() {
        dirtyDescriptorMask = 0L;
    }
}
