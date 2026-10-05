package com.metallum.client.metal.render;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native-free two-frame ping-pong simulation over the execution graph's
 * planned passes. Mirrors {@code beginFrame}'s history canonicalization,
 * {@code executeStage}'s read-snapshot / opposite-side write / flip-state
 * advance, {@code executeFinal}'s FINAL-stage rule (reads with its snapshot,
 * writes no colortex) and the end-of-frame canonicalization. Test-only.
 *
 * <p>The world bridge's own writes before the graph passes (gbuffer/translucent
 * draws landing on the current read side) are declared by the caller through
 * {@link WorldWrite} so the simulation stays pack-agnostic.
 */
final class FrameFlowSimulator {
    /** A world-bridge write landing on the target's current read side before a pass. */
    record WorldWrite(String beforePass, int target, String label) {
    }

    private final Map<String, String> reads = new HashMap<>();
    private final Map<String, String> writes = new HashMap<>();
    private final Map<Integer, BitSet> endStates = new HashMap<>();

    private FrameFlowSimulator() {
    }

    /** Runs the two frames and returns the queryable observations. */
    static FrameFlowSimulator simulate(
            final List<IrisMetalExecutionGraph.PlannedPass> passes,
            final int targetCount,
            final List<WorldWrite> worldWrites
    ) {
        FrameFlowSimulator flow = new FrameFlowSimulator();
        String[][] side = new String[targetCount][2];
        BitSet flipped = new BitSet();
        for (int frame = 1; frame <= 2; frame++) {
            // beginFrame: the previous frame's read side becomes main, then the
            // per-frame flip state resets.
            for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                side[t][0] = side[t][1];
            }
            flipped.clear();
            if (frame == 1) {
                for (int t = 0; t < targetCount; t++) {
                    side[t][0] = "clear";
                    side[t][1] = "clear";
                }
            }

            for (IrisMetalExecutionGraph.PlannedPass pass : passes) {
                int[] buffers = pass.drawBuffers();
                BitSet readsFromAlt = pass.readsFromAlt();
                for (WorldWrite worldWrite : worldWrites) {
                    if (worldWrite.beforePass().equals(pass.name())) {
                        int target = worldWrite.target();
                        side[target][flipped.get(target) ? 1 : 0] =
                                "f" + frame + ":" + worldWrite.label();
                    }
                }
                // Every pass samples through its snapshot, including FINAL.
                for (int t = 0; t < targetCount; t++) {
                    flow.reads.put(key(frame, pass.name(), t), side[t][readsFromAlt.get(t) ? 1 : 0]);
                }
                if (!pass.stage().equals("FINAL")) {
                    for (int t : buffers) {
                        int writeSide = readsFromAlt.get(t) ? 0 : 1;
                        String marker = "f" + frame + ":" + pass.name();
                        side[t][writeSide] = marker;
                        flow.writes.put(key(frame, pass.name(), t), marker);
                    }
                }
                flipped.clear();
                flipped.or(pass.stateAfter());
            }

            // executeFinal: canonicalize every flipped target's read side into main.
            for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                side[t][0] = side[t][1];
            }
            flow.endStates.put(frame, (BitSet) flipped.clone());
        }
        return flow;
    }

    /** Side label read by {@code pass} in {@code frame} for {@code target}, or null. */
    String read(final int frame, final String pass, final int target) {
        return reads.get(key(frame, pass, target));
    }

    /** Marker written by {@code pass} in {@code frame} for {@code target}, or null. */
    String write(final int frame, final String pass, final int target) {
        return writes.get(key(frame, pass, target));
    }

    /** Copy of the flip state after the frame's end-of-frame canonicalization. */
    BitSet endState(final int frame) {
        BitSet state = endStates.get(frame);
        return state == null ? new BitSet() : (BitSet) state.clone();
    }

    private static String key(final int frame, final String pass, final int target) {
        return frame + "|" + pass + "|" + target;
    }
}
