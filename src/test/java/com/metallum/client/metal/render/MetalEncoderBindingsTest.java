package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the encoder-split fix: cached draw state belongs to one
 * encoder instance, and replacing the encoder must invalidate every flag so
 * the next draw re-pushes pipeline, scissor, vertex buffers and descriptors.
 *
 * <p>Encoder identity is exactly the {@code MTLRenderCommandEncoder} object
 * identity used by production; stand-in {@link Object}s are enough to prove
 * the contract because {@link MetalEncoderBindings} never inspects them.
 */
final class MetalEncoderBindingsTest {

    /** Clears every flag the way a completed {@code bindDrawState} does. */
    private static void clearAll(final MetalEncoderBindings bindings) {
        bindings.clearPipelineDirty();
        bindings.clearVertexBuffersDirty();
        bindings.clearScissorDirty();
        bindings.clearDescriptorMask();
        assertFalse(bindings.needsDrawState(), "precondition: a clean bound state reports no work");
    }

    /** ⑤ A different encoder instance drops everything that was cached. */
    @Test
    void newEncoderInvalidatesCachedState() {
        MetalEncoderBindings bindings = new MetalEncoderBindings();
        Object firstEncoder = new Object();

        assertTrue(bindings.bindEncoder(firstEncoder), "the first bind invalidates the initial state");
        clearAll(bindings);

        assertFalse(bindings.bindEncoder(firstEncoder), "the same encoder keeps the cached state");
        assertFalse(bindings.needsDrawState(), "re-binding the same encoder must not re-push state");

        Object replacementEncoder = new Object();
        assertTrue(bindings.bindEncoder(replacementEncoder), "a replaced encoder invalidates the cache");
        assertTrue(bindings.isPipelineDirty(), "the new encoder has no pipeline bound");
        assertTrue(bindings.isVertexBuffersDirty(), "the new encoder has no vertex buffers bound");
        assertTrue(bindings.isScissorDirty(), "the new encoder has no scissor rect");
        assertEquals(
                MetalEncoderBindings.ALL_DESCRIPTORS_DIRTY,
                bindings.dirtyDescriptorMask(),
                "the new encoder has no descriptor bindings"
        );
        assertTrue(bindings.needsDrawState(), "the next draw must re-push the full state");
    }

    /** Going back to an earlier encoder is still a replacement, not a restore. */
    @Test
    void encoderToggleIsAlwaysInvalidated() {
        MetalEncoderBindings bindings = new MetalEncoderBindings();
        Object encoderA = new Object();
        Object encoderB = new Object();

        bindings.bindEncoder(encoderA);
        clearAll(bindings);
        bindings.bindEncoder(encoderB);
        clearAll(bindings);

        assertTrue(bindings.bindEncoder(encoderA), "returning to an older encoder is still a different encoder");
        assertTrue(bindings.needsDrawState(), "an older encoder's bindings are long gone");
    }

    /** The fence-update path can invalidate without waiting for a new bind. */
    @Test
    void explicitInvalidateRestoresEveryDirtyFlag() {
        MetalEncoderBindings bindings = new MetalEncoderBindings();
        Object encoder = new Object();
        bindings.bindEncoder(encoder);
        clearAll(bindings);

        bindings.invalidate();

        assertTrue(bindings.isPipelineDirty());
        assertTrue(bindings.isVertexBuffersDirty());
        assertTrue(bindings.isScissorDirty());
        assertEquals(MetalEncoderBindings.ALL_DESCRIPTORS_DIRTY, bindings.dirtyDescriptorMask());
        assertTrue(bindings.needsDrawState());
    }

    /** Individual dirty marks still round-trip through the cache. */
    @Test
    void individualMarksAndClearsRoundTrip() {
        MetalEncoderBindings bindings = new MetalEncoderBindings();
        bindings.bindEncoder(new Object());
        clearAll(bindings);

        bindings.markPipelineDirty();
        assertTrue(bindings.needsDrawState());
        bindings.clearPipelineDirty();

        bindings.markScissorDirty();
        assertTrue(bindings.isScissorDirty());
        bindings.clearScissorDirty();

        bindings.markVertexBuffersDirty();
        assertTrue(bindings.isVertexBuffersDirty());
        bindings.clearVertexBuffersDirty();

        bindings.markDescriptorDirty(3);
        assertEquals(1L << 3, bindings.dirtyDescriptorMask());
        bindings.markDescriptors(1L << 7);
        assertEquals((1L << 3) | (1L << 7), bindings.dirtyDescriptorMask());
        bindings.clearDescriptorMask();
        assertFalse(bindings.needsDrawState());
    }
}
