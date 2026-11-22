package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for {@code -Dmetallum.perf.perPassGpu}: the sample-index
 * ledger that pairs an encoder's start/end timestamps, its truncation cap, the
 * per-pass label format and the per-pass aggregation the window report prints.
 *
 * <p>No Metal device and no native symbol is involved — the ledger only hands
 * out indices, the labels stay on this side, and the aggregation is plain
 * arithmetic, so all of it runs on the Linux test host.
 */
final class MetalGpuPassTimingTest {

    private static MetalPerformanceProbe.GpuSampleLedger ledger() {
        MetalPerformanceProbe.GpuSampleLedger ledger = new MetalPerformanceProbe.GpuSampleLedger();
        ledger.reset();
        return ledger;
    }

    // ---------------------------------------------------------------------
    // Sample-index accounting
    // ---------------------------------------------------------------------

    /** Pair {@code k} must always live at indices {@code 2k} / {@code 2k+1}. */
    @Test
    void ledgerPairsStartAndEndIndices() {
        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();

        assertEquals(0, ledger.start("terrain"), "first start index is 0");
        assertEquals(1, ledger.end(), "first end index follows its start");
        assertEquals(2, ledger.start("composite"));
        assertEquals(3, ledger.end());

        assertEquals(4, ledger.sampleCount(), "two pairs = four samples");
        assertEquals(2, ledger.pairCount());
        assertEquals("terrain", ledger.labelAt(0));
        assertEquals("composite", ledger.labelAt(1));
        assertNull(ledger.labelAt(2), "no label at an unpaired index");
        assertFalse(ledger.truncated());
    }

    /** An end without an open start must not consume an index. */
    @Test
    void endWithoutStartIsIgnored() {
        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();

        assertEquals(-1, ledger.end());
        assertEquals(0, ledger.sampleCount());
        assertEquals(0, ledger.pairCount());
        assertNull(ledger.labelAt(0));

        assertEquals(0, ledger.start("shadowcomp"));
        assertEquals(1, ledger.end());
        assertEquals(-1, ledger.end(), "a second end of the same pair is ignored");
        assertEquals(2, ledger.sampleCount());
        assertEquals(1, ledger.pairCount());
    }

    /** A start while another pair is open is refused (one encoder at a time). */
    @Test
    void nestedStartIsRefused() {
        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();

        assertEquals(0, ledger.start("a"));
        assertEquals(-1, ledger.start("b"));
        assertEquals(1, ledger.end());
        assertEquals(1, ledger.pairCount());
        assertEquals("a", ledger.labelAt(0));
    }

    /** The cap is a hard stop that leaves every accepted pair complete. */
    @Test
    void ledgerStopsAtTheSampleCap() {        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();
        int pairs = MetalPerformanceProbe.GpuSampleLedger.MAX_SAMPLES / 2;

        for (int pair = 0; pair < pairs; pair++) {
            assertEquals(pair * 2, ledger.start("pass" + pair));
            assertEquals(pair * 2 + 1, ledger.end());
        }
        assertFalse(ledger.truncated(), "a buffer that exactly fills the cap is not truncated");
        assertEquals(MetalPerformanceProbe.GpuSampleLedger.MAX_SAMPLES, ledger.sampleCount());

        assertEquals(-1, ledger.start("overflow"), "the cap refuses a 129th pair");
        assertTrue(ledger.truncated());
        assertEquals(-1, ledger.end(), "the refused start leaves nothing open");
        assertEquals(pairs, ledger.pairCount(), "every accepted pair stayed complete");
        assertEquals("pass127", ledger.labelAt(pairs - 1));

        ledger.reset();
        assertEquals(0, ledger.sampleCount());
        assertEquals(0, ledger.pairCount());
        assertFalse(ledger.truncated());
        assertEquals(0, ledger.start("fresh"), "a reset command buffer samples from index 0 again");
    }

    /** Stage-boundary mode reserves both indices up front, from the descriptor. */
    @Test
    void reservePairReservesBothIndicesAtOnce() {
        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();

        assertEquals(0, ledger.reservePair("terrain"));
        assertEquals(2, ledger.reservePair("composite"));
        assertEquals(4, ledger.sampleCount(), "two reserved pairs = four samples");
        assertEquals(2, ledger.pairCount());
        assertEquals("terrain", ledger.labelAt(0));
        assertEquals("composite", ledger.labelAt(1));
        assertEquals(-1, ledger.end(), "a reserved pair leaves nothing open");
        assertFalse(ledger.truncated());

        // Modes never mix inside one command buffer: an open start() blocks a
        // reservation and vice versa.
        MetalPerformanceProbe.GpuSampleLedger mixed = ledger();
        assertEquals(0, mixed.start("a"));
        assertEquals(-1, mixed.reservePair("b"));
        assertEquals(1, mixed.end());
    }

    /** The same cap applies to the stage-boundary reservations. */
    @Test
    void reservePairStopsAtTheSampleCap() {
        MetalPerformanceProbe.GpuSampleLedger ledger = ledger();
        int pairs = MetalPerformanceProbe.GpuSampleLedger.MAX_SAMPLES / 2;

        for (int pair = 0; pair < pairs; pair++) {
            assertEquals(pair * 2, ledger.reservePair("pass" + pair));
        }
        assertFalse(ledger.truncated());
        assertEquals(-1, ledger.reservePair("overflow"));
        assertTrue(ledger.truncated());
        assertEquals(MetalPerformanceProbe.GpuSampleLedger.MAX_SAMPLES, ledger.sampleCount());
        assertEquals(pairs, ledger.pairCount(), "every reserved pair stayed complete");
    }

    /** A stage-boundary device drives both encoder kinds from one bit. */
    @Test
    void stageBoundaryEnablesBothKinds() {
        int both = MetalPerformanceProbe.GPU_KIND_RENDER | MetalPerformanceProbe.GPU_KIND_COMPUTE;

        assertEquals(both,
                MetalPerformanceProbe.gpuSupportedKinds(MetalPerformanceProbe.GPU_MODE_STAGE_BOUNDARY),
                "Apple silicon reports only the stage-boundary bit and still samples both kinds");
        assertEquals(both, MetalPerformanceProbe.gpuSupportedKinds(
                        MetalPerformanceProbe.GPU_MODE_STAGE_BOUNDARY
                                | MetalPerformanceProbe.GPU_KIND_RENDER));

        assertEquals(MetalPerformanceProbe.GPU_KIND_RENDER,
                MetalPerformanceProbe.gpuSupportedKinds(MetalPerformanceProbe.GPU_KIND_RENDER),
                "immediate-mode fallback: draw boundary only");
        assertEquals(MetalPerformanceProbe.GPU_KIND_COMPUTE,
                MetalPerformanceProbe.gpuSupportedKinds(MetalPerformanceProbe.GPU_KIND_COMPUTE));
        assertEquals(both, MetalPerformanceProbe.gpuSupportedKinds(
                MetalPerformanceProbe.GPU_KIND_RENDER | MetalPerformanceProbe.GPU_KIND_COMPUTE));
        assertEquals(0, MetalPerformanceProbe.gpuSupportedKinds(0));
    }

    /** The three native sampling points map to the three documented bits. */
    @Test
    void availabilityMaskBitsAreStable() {
        assertEquals(1, MetalPerformanceProbe.GPU_KIND_RENDER);
        assertEquals(2, MetalPerformanceProbe.GPU_KIND_COMPUTE);
        assertEquals(4, MetalPerformanceProbe.GPU_MODE_STAGE_BOUNDARY);
    }

    // ---------------------------------------------------------------------
    // Label format
    // ---------------------------------------------------------------------

    @Test
    void labelsAreSingleTokensAndDefaultToUnnamed() {
        assertEquals("unnamed", MetalPerformanceProbe.gpuPassLabel(null));
        assertEquals("unnamed", MetalPerformanceProbe.gpuPassLabel(""));
        assertEquals("composite_final", MetalPerformanceProbe.gpuPassLabel("composite final"));
        assertEquals("a_b_c", MetalPerformanceProbe.gpuPassLabel("a\tb\nc"));
        assertEquals("terrain_solid", MetalPerformanceProbe.gpuPassLabel("terrain_solid"));
    }

    @Test
    void longLabelsAreTruncatedDeterministically() {
        String exact = "x".repeat(MetalPerformanceProbe.GPU_LABEL_LIMIT);
        assertEquals(exact, MetalPerformanceProbe.gpuPassLabel(exact), "a label at the limit is kept verbatim");

        String longer = "y".repeat(MetalPerformanceProbe.GPU_LABEL_LIMIT + 20);
        String truncated = MetalPerformanceProbe.gpuPassLabel(longer);
        assertEquals(MetalPerformanceProbe.GPU_LABEL_LIMIT, truncated.length());
        assertEquals("y".repeat(MetalPerformanceProbe.GPU_LABEL_LIMIT), truncated);

        // Whitespace inside the retained prefix is still sanitized, and
        // whitespace past the cut cannot leak into the label.
        String spaced = "p".repeat(MetalPerformanceProbe.GPU_LABEL_LIMIT - 1) + " tail";
        String sanitized = MetalPerformanceProbe.gpuPassLabel(spaced);
        assertEquals(MetalPerformanceProbe.GPU_LABEL_LIMIT, sanitized.length());
        assertFalse(sanitized.contains(" "), "the probe line must stay one token per field");
        assertFalse(sanitized.contains("tail"), "characters past the limit are cut");
    }

    // ---------------------------------------------------------------------
    // Per-pass aggregation
    // ---------------------------------------------------------------------

    @Test
    void aggregationKeepsCountSumAndMax() {
        HashMap<String, long[]> tally = new HashMap<>();

        MetalPerformanceProbe.foldGpuPass(tally, "composite", 1_000_000L);
        MetalPerformanceProbe.foldGpuPass(tally, "composite", 3_000_000L);
        long[] slot = MetalPerformanceProbe.foldGpuPass(tally, "composite", 2_000_000L);

        assertEquals(1, tally.size(), "one label is one bucket");
        assertEquals(3L, slot[MetalPerformanceProbe.GPU_PASS_COUNT]);
        assertEquals(6_000_000L, slot[MetalPerformanceProbe.GPU_PASS_SUM]);
        assertEquals(3_000_000L, slot[MetalPerformanceProbe.GPU_PASS_MAX]);

        assertEquals("perf gpu pass=composite ms=2.00 n=3 max=3.00",
                MetalPerformanceProbe.gpuPassLine("composite", 3L, 6_000_000L, 3_000_000L));
    }

    /** A spilled label is folded into {@code (other)} instead of growing the map. */
    @Test
    void labelOverflowFoldsIntoOther() {
        HashMap<String, long[]> tally = new HashMap<>();
        for (int index = 0; index < MetalPerformanceProbe.GPU_PASS_LIMIT; index++) {
            MetalPerformanceProbe.foldGpuPass(tally, "pass" + index, 1_000_000L);
        }
        assertEquals(MetalPerformanceProbe.GPU_PASS_LIMIT, tally.size());

        MetalPerformanceProbe.foldGpuPass(tally, "pass_overflow", 5_000_000L);
        assertEquals(MetalPerformanceProbe.GPU_PASS_LIMIT + 1, tally.size(),
                "the overflow bucket is admitted on top of the limit");
        long[] other = tally.get("(other)");
        assertEquals(1L, other[MetalPerformanceProbe.GPU_PASS_COUNT]);
        assertEquals(5_000_000L, other[MetalPerformanceProbe.GPU_PASS_SUM]);

        MetalPerformanceProbe.foldGpuPass(tally, "pass_overflow2", 1_000_000L);
        assertEquals(2L, other[MetalPerformanceProbe.GPU_PASS_COUNT],
                "later overflow labels join the same bucket instead of adding keys");
        assertEquals(MetalPerformanceProbe.GPU_PASS_LIMIT + 1, tally.size());
    }

    /** The printed order is the one that answers "where does the frame go". */
    @Test
    void rankingPutsHeaviestTotalTimeFirst() {
        HashMap<String, long[]> tally = new HashMap<>();
        MetalPerformanceProbe.foldGpuPass(tally, "composite", 1_000_000L);
        MetalPerformanceProbe.foldGpuPass(tally, "composite", 1_000_000L);
        MetalPerformanceProbe.foldGpuPass(tally, "composite", 1_000_000L);
        MetalPerformanceProbe.foldGpuPass(tally, "fog", 2_000_000L);
        MetalPerformanceProbe.foldGpuPass(tally, "hand", 500_000L);

        List<Map.Entry<String, long[]>> ranked = MetalPerformanceProbe.rankGpuPasses(tally);
        assertEquals("composite", ranked.get(0).getKey(), "3ms total beats 2ms once");
        assertEquals("fog", ranked.get(1).getKey());
        assertEquals("hand", ranked.get(2).getKey());
    }

    @Test
    void negativeSamplesCannotUnderflowTheTally() {
        HashMap<String, long[]> tally = new HashMap<>();
        MetalPerformanceProbe.foldGpuPass(tally, "glitched", -4_000_000L);
        long[] slot = tally.get("glitched");
        assertEquals(1L, slot[MetalPerformanceProbe.GPU_PASS_COUNT], "the occurrence still counts");
        assertEquals(0L, slot[MetalPerformanceProbe.GPU_PASS_SUM]);
        assertEquals(0L, slot[MetalPerformanceProbe.GPU_PASS_MAX]);
    }

    // ---------------------------------------------------------------------
    // Degradation tokens
    // ---------------------------------------------------------------------

    @Test
    void unavailableReasonsMatchTheNativeCodes() {
        assertEquals("noSymbol", MetalPerformanceProbe.gpuUnavailableReason(MetalPerformanceProbe.GPU_NO_SYMBOL));
        assertEquals("counterSet", MetalPerformanceProbe.gpuUnavailableReason(-1));
        assertEquals("samplingPoint", MetalPerformanceProbe.gpuUnavailableReason(-2));
        assertEquals("unsupported", MetalPerformanceProbe.gpuUnavailableReason(0));
        assertEquals("unsupported", MetalPerformanceProbe.gpuUnavailableReason(3));
    }
}
