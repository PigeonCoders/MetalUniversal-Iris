package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the submit/fence contract, ported from the upstream
 * {@code Fix empty submit} + fence-semantics commits.
 *
 * <p>No Metal device is involved: the encoder under test is the native-free
 * bookkeeping instance, and every path exercised here resolves before any
 * native call (empty submit advances the cursor, "current submit" polling
 * returns {@code false}, a rolled-out-of-ring index completes immediately).
 * The blocking-wait path is only asserted to <em>throw</em> for the current
 * submit, which also happens before any native access.
 */
final class MetalSubmitFenceSemanticsTest {

    private static MetalCommandEncoder nativeFreeEncoder() {
        return new MetalCommandEncoder();
    }

    /** ① Empty submits still advance the cursor the engine stamps fences with. */
    @Test
    void emptySubmitAdvancesSubmitIndex() {
        MetalCommandEncoder encoder = nativeFreeEncoder();
        long start = encoder.currentSubmitIndex();

        encoder.submit();
        assertEquals(start + 1L, encoder.currentSubmitIndex(), "one empty submit must advance the cursor");

        encoder.submit();
        assertEquals(start + 2L, encoder.currentSubmitIndex(), "every empty submit must advance the cursor");
    }

    /** ① / ② A fence taken before an empty submit becomes a completed past index. */
    @Test
    void fenceTakenBeforeEmptySubmitResolvesAfterIt() {
        MetalCommandEncoder encoder = nativeFreeEncoder();
        MetalFence fence = new MetalFence(encoder, encoder.currentSubmitIndex());

        // Poll before the submit: index == current submit -> not completed.
        assertFalse(fence.awaitCompletion(0L), "a not-yet-submitted fence must poll as incomplete");

        encoder.submit();

        // The index is now in the past and has no in-flight record, so the
        // empty submission must read as completed instead of throwing.
        assertTrue(fence.awaitCompletion(0L), "an empty submit must complete its fence");
    }

    /** ② Polling the current submit returns false; it is not a usage error. */
    @Test
    void pollOnCurrentSubmitReturnsFalse() {
        MetalCommandEncoder encoder = nativeFreeEncoder();

        assertFalse(
                encoder.awaitSubmitCompletion(encoder.currentSubmitIndex(), 0L),
                "poll on the current submit must report incomplete"
        );

        MetalFence fence = new MetalFence(encoder, encoder.currentSubmitIndex());
        assertFalse(fence.awaitCompletion(0L), "fence poll on the current submit must report incomplete");
        assertFalse(fence.awaitCompletion(0L), "a failed poll must stay pollable");
    }

    /** ③ Only a blocking wait on the current submit is a usage error. */
    @Test
    void blockingWaitOnCurrentSubmitStillThrows() {
        MetalCommandEncoder encoder = nativeFreeEncoder();
        long current = encoder.currentSubmitIndex();

        assertThrows(
                IllegalStateException.class,
                () -> encoder.awaitSubmitCompletion(current, 1L),
                "a nonzero timeout on the current submit is a real usage error"
        );
        assertThrows(
                IllegalStateException.class,
                () -> new MetalFence(encoder, current).awaitCompletion(1L),
                "1 ns must stay a wait (old /1_000_000 truncation turned it into a poll), so the fence must throw"
        );
    }

    /** ④ An index older than the ring holds is necessarily completed. */
    @Test
    void indexRolledOutOfRingIsCompleted() {
        MetalCommandEncoder encoder = nativeFreeEncoder();
        long current = encoder.currentSubmitIndex();
        int ring = MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT;

        assertFalse(encoder.submitIndexRolledOutOfRing(current - ring), "the oldest still-resident index is in the ring");
        assertTrue(encoder.submitIndexRolledOutOfRing(current - ring - 1L), "one past the ring is rolled out");

        assertTrue(
                encoder.awaitSubmitCompletion(current - ring - 1L, 0L),
                "polling a rolled-out index must report completed"
        );
        assertTrue(
                encoder.awaitSubmitCompletion(current - ring - 1L, 1_000_000L),
                "waiting on a rolled-out index must complete immediately"
        );
    }

    /** Timeout conversion keeps a nonzero wait from degrading into a poll. */
    @Test
    void timeoutNanosConvertUpToMillis() {
        assertEquals(1L, MetalCommandEncoder.millisFromNanos(1L), "1 ns must round up to 1 ms");
        assertEquals(1L, MetalCommandEncoder.millisFromNanos(999_999L), "sub-millisecond must round up to 1 ms");
        assertEquals(1L, MetalCommandEncoder.millisFromNanos(1_000_000L), "exact millisecond");
        assertEquals(2L, MetalCommandEncoder.millisFromNanos(1_000_001L), "fractional millisecond rounds up");
        assertEquals(5_000L, MetalCommandEncoder.millisFromNanos(5_000_000_000L), "submit timeout is 5 s");
        assertEquals(Long.MAX_VALUE, MetalCommandEncoder.millisFromNanos(Long.MAX_VALUE), "infinite wait stays infinite");
    }

    /** A closed fence is final and reports completion without touching the ring. */
    @Test
    void closedFenceReportsCompleted() {
        MetalCommandEncoder encoder = nativeFreeEncoder();
        MetalFence fence = new MetalFence(encoder, encoder.currentSubmitIndex());
        fence.close();

        assertTrue(fence.awaitCompletion(0L), "close() makes the fence final");
    }
}
