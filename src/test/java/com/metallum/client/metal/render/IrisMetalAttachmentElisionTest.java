package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.blending.BlendMode;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the P1 attachment elision analysis. No Metal device, no
 * Iris program set and no render target is touched: the analysis only sees the
 * ordered pass description the execution graph builds at plan time.
 */
final class IrisMetalAttachmentElisionTest {
    private static final int TARGETS = 4;

    private static IrisMetalAttachmentElision.Pass pass(
            final String name,
            final int[] writes,
            final int[] samples,
            final boolean fullCoverage
    ) {
        BitSet sampled = new BitSet(TARGETS);
        for (int target : samples) {
            sampled.set(target);
        }
        return new IrisMetalAttachmentElision.Pass(
                name, writes, sampled, fullCoverage,
                new boolean[writes.length], new boolean[writes.length]
        );
    }

    private static IrisMetalAttachmentElision.Pass pass(
            final String name,
            final int[] writes,
            final int[] samples
    ) {
        return pass(name, writes, samples, true);
    }

    private static IrisMetalAttachmentElision.Masks[] analyze(
            final List<IrisMetalAttachmentElision.Pass> passes,
            final BitSet persistent,
            final boolean deadStores,
            final boolean loadElision
    ) {
        return IrisMetalAttachmentElision.analyze(
                passes, persistent, TARGETS, deadStores, loadElision
        );
    }

    private static IrisMetalAttachmentElision.Masks[] analyze(
            final List<IrisMetalAttachmentElision.Pass> passes
    ) {
        return analyze(passes, new BitSet(), true, true);
    }

    private static int[] load(
            final IrisMetalAttachmentElision.Masks[] masks,
            final int index
    ) {
        return masks[index] == null ? null : masks[index].loadDontCare();
    }

    private static int[] store(
            final IrisMetalAttachmentElision.Masks[] masks,
            final int index
    ) {
        return masks[index] == null ? null : masks[index].storeDontCare();
    }

    @Test
    void overwrittenStoreDropsItsStoreAndTheNextWritersLoad() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("second", new int[]{0}, new int[]{})
        ));

        assertNotNull(masks[0]);
        assertArrayEquals(new int[]{1}, store(masks, 0),
                "the first writer's store is dead");
        assertArrayEquals(new int[]{1}, load(masks, 0),
                "the first writer fully overwrites its attachment too");
        assertArrayEquals(new int[]{1}, load(masks, 1),
                "the overwriting writer must not load the undefined contents");
        assertNull(store(masks, 1),
                "the last write of the target in the tail is always stored");
    }

    @Test
    void readBetweenWritesKeepsTheStore() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("reader", new int[]{1}, new int[]{0}),
                pass("second", new int[]{0}, new int[]{})
        ));

        assertNull(store(masks, 0),
                "an intervening sampler read makes the first store live");
        // Target B is independent: the later writer replaces every pixel of
        // its attachment without reading it, so its own load stays dead. No
        // pass can observe the old contents between that writer's load and
        // its full-screen write.
        assertArrayEquals(new int[]{1}, load(masks, 2));
        assertNull(store(masks, 2));
    }

    @Test
    void lastWriteOfTheTargetAlwaysStores() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("only", new int[]{0}, new int[]{})
        ));

        assertNotNull(masks[0]);
        assertNull(store(masks, 0),
                "executeFinal canonicalizes the last write into the history texture");
        assertArrayEquals(new int[]{1}, load(masks, 0));
    }

    @Test
    void persistentTargetNeverDropsItsStore() {
        BitSet persistent = new BitSet(TARGETS);
        persistent.set(0);
        IrisMetalAttachmentElision.Masks[] masks = analyze(
                List.of(
                        pass("first", new int[]{0}, new int[]{}),
                        pass("second", new int[]{0}, new int[]{})
                ),
                persistent, true, true
        );

        assertNull(store(masks, 0),
                "colortex0Clear=false keeps the value for the next frame");
        assertArrayEquals(new int[]{1}, load(masks, 0),
                "load elision does not depend on cross-frame persistence");
    }

    @Test
    void worldDrawBarrierKeepsEveryEarlierStore() {
        // A compute pass in the tail may bind any colortex as sampler/image:
        // the graph models it as an all-target read barrier. It stands in for
        // the world-draw boundary between the two writers here.
        IrisMetalAttachmentElision.Pass world = IrisMetalAttachmentElision.computeBarrier(
                "world", TARGETS
        );
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("beforeWorld", new int[]{0}, new int[]{}),
                world, // not full coverage, reads every target
                pass("afterWorld", new int[]{0}, new int[]{})
        ));

        assertNull(store(masks, 0),
                "a read between the writes keeps the earlier store");
        assertNull(masks[1], "the barrier has no attachments");
        assertArrayEquals(new int[]{1}, load(masks, 2),
                "the later writer still replaces its attachment completely");
    }

    @Test
    void feedbackPassKeepsItsStoreAndLoad() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("feedback", new int[]{0}, new int[]{0})
        ));

        assertNull(store(masks, 0),
                "the second pass samples the target it writes");
        assertNull(store(masks, 1));
        assertNull(load(masks, 1),
                "a pass that samples its own write cannot drop the load");
    }

    @Test
    void partialCoverageNeverElides() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("partial", new int[]{0}, new int[]{}, false)
        ));

        assertNull(store(masks, 0),
                "a partial overwrite cannot prove the store dead");
        assertNull(load(masks, 1),
                "a partial pass cannot drop its load");
    }

    @Test
    void blendReadingDestinationKeepsTheLoad() {
        IrisMetalAttachmentElision.Pass blending = new IrisMetalAttachmentElision.Pass(
                "blend",
                new int[]{0},
                new BitSet(TARGETS),
                true,
                new boolean[]{false},
                new boolean[]{true}
        );
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(blending));

        assertNull(load(masks, 0),
                "a destination-reading blend consumes the previous pixels");
    }

    @Test
    void clearColorKeepsTheLoad() {
        IrisMetalAttachmentElision.Pass clearing = new IrisMetalAttachmentElision.Pass(
                "clear",
                new int[]{0},
                new BitSet(TARGETS),
                true,
                new boolean[]{true},
                new boolean[]{false}
        );
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(clearing));

        assertNull(load(masks, 0), "a cleared slot never loads");
    }

    @Test
    void finalMainTargetDropsOnlyItsLoad() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("final", new int[]{IrisMetalAttachmentElision.MAIN_TARGET}, new int[]{0})
        ));

        assertNotNull(masks[0]);
        assertArrayEquals(new int[]{1}, load(masks, 0));
        assertNull(store(masks, 0), "the present image is always stored");
    }

    @Test
    void mainTargetStoreIsNeverElidedEvenWithALaterWriter() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("finalA", new int[]{IrisMetalAttachmentElision.MAIN_TARGET}, new int[]{}),
                pass("finalB", new int[]{IrisMetalAttachmentElision.MAIN_TARGET}, new int[]{})
        ));

        assertNull(store(masks, 0));
        assertNull(store(masks, 1));
        assertArrayEquals(new int[]{1}, load(masks, 1));
    }

    @Test
    void switchesOffProduceNoMasks() {
        List<IrisMetalAttachmentElision.Pass> tail = List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("second", new int[]{0}, new int[]{})
        );

        IrisMetalAttachmentElision.Masks[] bothOff = analyze(tail, new BitSet(), false, false);
        assertAllEmpty(bothOff);

        IrisMetalAttachmentElision.Masks[] deadStoresOnLoadOff =
                analyze(tail, new BitSet(), true, false);
        assertAllEmpty(deadStoresOnLoadOff);
        assertNull(deadStoresOnLoadOff[0],
                "dead-store elimination needs the paired load elision");

        IrisMetalAttachmentElision.Masks[] deadStoresOff = analyze(tail, new BitSet(), false, true);
        assertNull(store(deadStoresOff, 0));
        assertArrayEquals(new int[]{1}, load(deadStoresOff, 1),
                "load elision is independent of the dead-store switch");
    }

    @Test
    void emptyTailProducesNoMasks() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of());
        assertEquals(0, masks.length);
    }

    @Test
    void masksAreDefensiveCopies() {
        IrisMetalAttachmentElision.Masks[] masks = analyze(List.of(
                pass("first", new int[]{0}, new int[]{}),
                pass("second", new int[]{0}, new int[]{})
        ));
        int[] firstStore = store(masks, 0);
        firstStore[0] = 0;

        assertArrayEquals(new int[]{1}, store(masks, 0),
                "outer mutation must not reach the stored mask");
    }

    @Test
    void passSlotArraysMustMatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IrisMetalAttachmentElision.Pass(
                        "bad",
                        new int[]{0, 1},
                        new BitSet(TARGETS),
                        true,
                        new boolean[]{false},
                        new boolean[]{false, false}
                )
        );
    }

    @Test
    void anyEnabledBlendKeepsTheLoad() {
        // Standard alpha blending multiplies the existing pixel by
        // (1 - srcAlpha): the destination is read, so the load must stay.
        assertTrue(IrisMetalAttachmentElision.blendReadsDestination(
                new BlendMode(0x0302, 0x0303, 0x0001, 0x0303) // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
        ));
        // Additive blending multiplies the destination by ONE: also a read.
        assertTrue(IrisMetalAttachmentElision.blendReadsDestination(
                new BlendMode(0x0302, 0x0001, 0x0302, 0x0001) // SRC_ALPHA, ONE
        ));
        assertTrue(IrisMetalAttachmentElision.blendReadsDestination(
                new BlendMode(0x0302, 0x0306, 0x0302, 0x0306) // SRC_ALPHA, DST_COLOR
        ));
        assertTrue(IrisMetalAttachmentElision.blendReadsDestination(
                new BlendMode(0x0302, 0x0000, 0x0302, 0x0000) // destination factor ZERO
        ));
        assertFalse(IrisMetalAttachmentElision.blendReadsDestination(null));
    }

    private static void assertAllEmpty(final IrisMetalAttachmentElision.Masks[] masks) {
        for (IrisMetalAttachmentElision.Masks mask : masks) {
            assertNull(mask);
        }
    }
}
