package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.BlendMode;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Pure attachment load/store elision for the Iris post-processing tail (P1).
 *
 * <p>Two decisions are computed for one ordered pass tail. The tail is
 * supplied by the caller and must contain only passes that run after the last
 * world-drawing boundary (the execution graph feeds the composite stage plus
 * final): world draws may sample any colortex, so a store issued before them
 * can never be proven dead.</p>
 *
 * <h2>Dead store (target A)</h2>
 * <p>A pass's color store is dead when the <em>next</em> pass that writes the
 * same logical target runs before any pass reads it, that next pass fully
 * overwrites its attachment, does not sample the target itself, and that next
 * pass's own load is elidable. The store becomes {@code .dontCare} and the
 * next pass's load becomes {@code .dontCare} in the same step, which is what
 * keeps the pair correct: a store without the matching load would expose
 * undefined contents. The last write of a target in the tail always stores,
 * because {@code executeFinal} canonicalizes the read side into the main
 * texture for the next frame's history readers.</p>
 *
 * <h2>Load elision (target B)</h2>
 * <p>A full-coverage graph pass that overwrites a color attachment without
 * sampling it, without supplying a clear color and without a destination-
 * reading blend never consumes the attachment's previous contents, so its
 * load is dead. No cross-frame persistence check is needed here: the pass
 * replaces every pixel regardless of what was there before.</p>
 *
 * <p>Every rule fails closed. Anything the caller cannot describe (an
 * unknown target slot, a non-full-coverage pass, a compute pass) keeps the
 * baseline {@code .load}/{@code .store} behavior.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalAttachmentElision {
    /**
     * Attachment slot that resolves to the engine's main (present) target
     * instead of a colortex target. Its load may be elided (the pass
     * overwrites the swapchain image) but its store never is: it is the frame
     * output.
     */
    static final int MAIN_TARGET = -1;

    private static final int[] NO_TARGETS = new int[0];
    private static final boolean[] NO_SLOTS = new boolean[0];

    /**
     * One graph pass in execution order.
     *
     * @param colorTargets   attachment slot to logical colortex target; a
     *                       {@link #MAIN_TARGET} slot is the present target,
     *                       other negative values are unknown and never touched
     * @param sampledTargets logical colortex targets the pass reads: declared
     *                       sampler names (including Iris's legacy aliases),
     *                       storage-image declarations and mipmapped buffers
     * @param fullCoverage   the pass draws the full-screen quad over a viewport
     *                       and render area covering the whole attachment
     * @param clearColors    per slot: the pass supplies a clear color (its load
     *                       is a clear, never a load)
     * @param blendReadsDst  per slot: the pack declares a blend whose
     *                       destination factor reads the target pixel
     */
    record Pass(
            String name,
            int[] colorTargets,
            BitSet sampledTargets,
            boolean fullCoverage,
            boolean[] clearColors,
            boolean[] blendReadsDst
    ) {
        Pass {
            Objects.requireNonNull(name, "name");
            colorTargets = colorTargets.clone();
            sampledTargets = (BitSet) sampledTargets.clone();
            clearColors = clearColors.clone();
            blendReadsDst = blendReadsDst.clone();
            if (clearColors.length != colorTargets.length
                    || blendReadsDst.length != colorTargets.length) {
                throw new IllegalArgumentException("Pass slot arrays must match the color target count");
            }
        }

        @Override
        public int[] colorTargets() {
            return colorTargets.clone();
        }

        @Override
        public BitSet sampledTargets() {
            return (BitSet) sampledTargets.clone();
        }

        @Override
        public boolean[] clearColors() {
            return clearColors.clone();
        }

        @Override
        public boolean[] blendReadsDst() {
            return blendReadsDst.clone();
        }

        /** Whether this pass reads the given logical target. */
        boolean samples(final int target) {
            return target >= 0 && sampledTargets.get(target);
        }
    }

    /** Per-pass masks; either array is {@code null} when no slot changed. */
    record Masks(int @Nullable [] loadDontCare, int @Nullable [] storeDontCare) {
        static final Masks NONE = new Masks(null, null);

        Masks {
            if (loadDontCare != null) {
                loadDontCare = loadDontCare.clone();
            }
            if (storeDontCare != null) {
                storeDontCare = storeDontCare.clone();
            }
        }

        @Override
        public int @Nullable [] loadDontCare() {
            return loadDontCare == null ? null : loadDontCare.clone();
        }

        @Override
        public int @Nullable [] storeDontCare() {
            return storeDontCare == null ? null : storeDontCare.clone();
        }
    }

    private IrisMetalAttachmentElision() {
    }

    /**
     * Computes the per-pass masks for one ordered tail.
     *
     * @param passes             the tail in execution order (composite raster
     *                           passes, any composite-stage compute passes as
     *                           read barriers, final computes, final)
     * @param persistentTargets  logical targets declared {@code Clear=false};
     *                           their stores are always kept
     * @param targetCount        logical colortex target count, for range checks
     * @param deadStoresEnabled  {@code -Dmetallum.iris.deadStores}
     * @param loadElisionEnabled {@code -Dmetallum.iris.loadElision}
     * @return one entry per pass, {@code null} when neither mask is set
     */
    static @Nullable Masks[] analyze(
            final List<Pass> passes,
            final BitSet persistentTargets,
            final int targetCount,
            final boolean deadStoresEnabled,
            final boolean loadElisionEnabled
    ) {
        Objects.requireNonNull(passes, "passes");
        Objects.requireNonNull(persistentTargets, "persistentTargets");
        if (targetCount <= 0) {
            throw new IllegalArgumentException("Target count must be positive");
        }
        int count = passes.size();
        @Nullable Masks[] result = new Masks[count];
        if (count == 0) {
            return result;
        }

        int[][] loadMasks = new int[count][];
        int[][] storeMasks = new int[count][];

        if (loadElisionEnabled) {
            for (int index = 0; index < count; index++) {
                Pass pass = passes.get(index);
                if (!pass.fullCoverage()) {
                    continue;
                }
                int[] targets = pass.colorTargets();
                int[] mask = null;
                for (int slot = 0; slot < targets.length; slot++) {
                    int target = targets[slot];
                    if (target < MAIN_TARGET) {
                        // Unknown slot: never elide its load.
                        continue;
                    }
                    if (target != MAIN_TARGET && target >= targetCount) {
                        continue;
                    }
                    if (pass.clearColors()[slot] || pass.blendReadsDst()[slot]) {
                        continue;
                    }
                    if (target != MAIN_TARGET && pass.samples(target)) {
                        // Feedback: the pass consumes the target it writes.
                        continue;
                    }
                    if (mask == null) {
                        mask = new int[targets.length];
                    }
                    mask[slot] = 1;
                }
                loadMasks[index] = mask;
            }
        }

        if (deadStoresEnabled && loadElisionEnabled) {
            // The store/load pair is only correct together: eliding the store
            // exposes undefined contents to the next writer's load, so a
            // disabled load elision also disables dead-store elimination.
            for (int index = 0; index < count; index++) {
                Pass writer = passes.get(index);
                int[] targets = writer.colorTargets();
                int[] mask = null;
                for (int slot = 0; slot < targets.length; slot++) {
                    int target = targets[slot];
                    if (target < 0 || target >= targetCount) {
                        // Main target (present output) and unknown slots always store.
                        continue;
                    }
                    if (persistentTargets.get(target)) {
                        // colortexNClear=false: the next frame reads this value.
                        continue;
                    }
                    int next = nextWriter(passes, index, target);
                    if (next < 0) {
                        // Last write of this target in the tail: keep it.
                        continue;
                    }
                    Pass nextPass = passes.get(next);
                    int nextSlot = slotWriting(nextPass, target);
                    if (nextSlot < 0 || !nextPass.fullCoverage() || nextPass.samples(target)) {
                        continue;
                    }
                    int[] nextLoad = loadMasks[next];
                    if (nextLoad == null || nextLoad[nextSlot] == 0) {
                        // The next write cannot drop its own load, so it would
                        // read the undefined contents this store elision left.
                        continue;
                    }
                    if (mask == null) {
                        mask = new int[targets.length];
                    }
                    mask[slot] = 1;
                }
                storeMasks[index] = mask;
            }
        }

        for (int index = 0; index < count; index++) {
            if (loadMasks[index] != null || storeMasks[index] != null) {
                result[index] = new Masks(loadMasks[index], storeMasks[index]);
            }
        }
        return result;
    }

    /**
     * Index of the first pass after {@code from} that writes {@code target},
     * or {@code -1} when the tail ends or an intervening reader makes the
     * store live first.
     */
    private static int nextWriter(final List<Pass> passes, final int from, final int target) {
        for (int index = from + 1; index < passes.size(); index++) {
            Pass pass = passes.get(index);
            if (pass.samples(target)) {
                return -1;
            }
            if (slotWriting(pass, target) >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** Attachment slot of {@code pass} that writes {@code target}, or {@code -1}. */
    private static int slotWriting(final Pass pass, final int target) {
        int[] targets = pass.colorTargets();
        for (int slot = 0; slot < targets.length; slot++) {
            if (targets[slot] == target) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Whether a pack blend override consumes the destination pixel.
     *
     * <p>Conservative on purpose: <em>any</em> enabled blend reads the
     * destination, because the result is {@code src*srcFactor op dst*dstFactor}
     * and even the ubiquitous {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} (let
     * alone additive {@code SRC_ALPHA / ONE}) multiplies the existing pixel.
     * Only a destination factor of {@code ZERO} with {@code ADD} would leave it
     * unread, and Iris's {@link BlendMode} carries no equation, so a MIN/MAX
     * pack blend could still consume it. Blending a full-screen pass is rare,
     * so treating every override as destination-reading costs almost nothing
     * and cannot corrupt the image.</p>
     */
    static boolean blendReadsDestination(final @Nullable BlendMode blendMode) {
        return blendMode != null;
    }

    /** A compute pass in the tail: it may read any target as a sampler or image. */
    static Pass computeBarrier(final String name, final int targetCount) {
        BitSet all = new BitSet(targetCount);
        all.set(0, targetCount);
        return new Pass(name, NO_TARGETS, all, false, NO_SLOTS, NO_SLOTS);
    }

    /** True when {@code mask} is {@code null} or every entry is zero. */
    static boolean isAllZero(final int @Nullable [] mask) {
        if (mask == null) {
            return true;
        }
        for (int value : mask) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
