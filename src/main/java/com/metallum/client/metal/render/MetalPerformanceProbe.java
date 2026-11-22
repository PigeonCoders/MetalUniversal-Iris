package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in performance measurement for the Metal world pipeline
 * ({@code -Dmetallum.perf}). Every hook is gated by the {@link #ENABLED}
 * constant so a release run only pays a predictable branch: no maps, no
 * strings, no boxing on the hot path. Statistics are folded into the existing
 * {@link MetalProbeReport} channel once every {@value #REPORT_INTERVAL}
 * frames instead of touching the probe file per frame.
 *
 * <p>The counters live in this class rather than in each renderer so the
 * probe stays a single, generation-independent window: a shaderpack or
 * dimension change resets the window naturally when the report fires.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalPerformanceProbe {
    /** Master switch: {@code -Dmetallum.perf}. */
    static final boolean ENABLED = MetalDebugSwitches.PERF || MetalDebugSwitches.PER_PASS_GPU;

    static final int REPORT_INTERVAL = 120;
    private static final int SAMPLE_CAPACITY = 128;
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /** Level frames whose render passes are dumped by {@code perf order}. */
    static final int ORDER_FRAMES = 3;
    /** Pass labels dumped per frame before the line is truncated with {@code …}. */
    static final int ORDER_ENTRY_LIMIT = 80;
    private static final String ORDER_OVERFLOW = "…";
    private static final String ORDER_SEPARATOR = " | ";

    /** CPU stages timed around the world-pipeline boundaries. */
    enum Stage {
        PREPARE_RESOURCES("prepare"),
        PREPARE_UNIFORMS("uniforms"),
        SHADOWS("shadows"),
        TRANSLUCENTS("translucents"),
        HAND("hand"),
        FINALIZE("finalize");

        final String label;

        Stage(final String label) {
            this.label = label;
        }
    }

    private static final Stage[] STAGES = Stage.values();

    /**
     * Why {@link #renderCommandEncoder} did or did not hand back the open
     * encoder. Ordered so the {@code perf encoders reuse=…} line can be built
     * straight from the ordinal array; the names are the probe tokens.
     */
    enum EncoderDecision {
        /** Same attachment handles, no pending clear: the open encoder is reused. */
        REUSE("reuse"),
        /** No encoder open at all (frame start, after {@code endEncoder}). */
        NO_OPEN("noOpen"),
        /** The open encoder is a blit/compute encoder, not a render encoder. */
        KIND("kind"),
        /** Both are render encoders but the color slot count differs. */
        COLOR_COUNT("colorCount"),
        /** Same color slot count but at least one slot's texture handle differs. */
        SLOT_HANDLE("slotHandle"),
        /** Color set identical, depth attachment handle differs. */
        DEPTH("depth"),
        /** Attachments identical, but this pass would clear: merging would drop the clear. */
        CLEAR("clear"),
        /**
         * Defensive bucket. Also attributes a pass that could only not reuse the
         * open encoder because its P1 attachment actions differ.
         */
        OTHER("other");

        final String token;

        EncoderDecision(final String token) {
            this.token = token;
        }
    }

    private static final EncoderDecision[] ENCODER_DECISIONS = EncoderDecision.values();
    // Exists only for an enabled run; guarded like every other probe buffer.
    private static final long[] ENCODER_DECISION_COUNTS = ENABLED
            ? new long[ENCODER_DECISIONS.length]
            : null;
    // Sample storage exists only for an enabled run; all accessors are behind
    // the ENABLED guard, so a release process allocates none of it.
    private static final long[][] STAGE_SAMPLES = ENABLED
            ? new long[STAGES.length][SAMPLE_CAPACITY]
            : null;
    private static final int[] STAGE_NEXT = new int[STAGES.length];
    private static final int[] STAGE_COUNT = new int[STAGES.length];
    private static final long[] STAGE_MIN = new long[STAGES.length];
    private static final long[] STAGE_SUM = new long[STAGES.length];
    private static final long[] SCRATCH = ENABLED ? new long[SAMPLE_CAPACITY] : null;

    private static final long[] FRAME_SAMPLES = ENABLED ? new long[SAMPLE_CAPACITY] : null;
    private static int frameNext;
    private static int frameCount;
    private static long frameSum;
    private static final double[] GPU_SAMPLES = ENABLED ? new double[SAMPLE_CAPACITY] : null;
    private static int gpuNext;
    private static int gpuCount;
    private static double gpuSum;

    /** Distinct pass labels the per-pass GPU tally tracks in one window. */
    static final int GPU_PASS_LIMIT = 256;
    /**
     * Probe label budget: a pass label longer than this is truncated so a
     * shaderpack-injected suffix (or a pack name with a version string) cannot
     * push a {@code perf gpu pass=} line past a terminal width. 48 leaves room
     * for the statistics that follow on the same line.
     */
    static final int GPU_LABEL_LIMIT = 48;
    /** Per-pass GPU timing is only collected with its own switch on. */
    static final boolean GPU_PASSES = MetalDebugSwitches.PER_PASS_GPU;
    /** Availability code the bridge returns when the dylib has no counter symbols. */
    static final int GPU_NO_SYMBOL = Integer.MIN_VALUE;
    /**
     * Sampling-point mask bit for render encoders at the draw boundary
     * ({@code MTLCommonCounterSamplingPointDraw}): only immediate-mode GPUs
     * support it, so it is the fallback path, not the primary one.
     */
    static final int GPU_KIND_RENDER = 1;
    /** Sampling-point mask bit for compute encoders at the dispatch boundary. */
    static final int GPU_KIND_COMPUTE = 2;
    /**
     * Sampling-point mask bit for stage-boundary sampling
     * ({@code MTLCommonCounterSamplingPointStage}). Apple silicon's only
     * supported sampling point: it is fed through a pass descriptor's
     * {@code sampleBufferAttachments[0]}, which yields the whole encoder's
     * start/end timestamps in one shot.
     */
    static final int GPU_MODE_STAGE_BOUNDARY = 4;
    /** Distinct labels printed per window; heavier totals first. */
    private static final int GPU_PASS_TOP = 16;
    static final int GPU_PASS_COUNT = 0;
    static final int GPU_PASS_SUM = 1;
    static final int GPU_PASS_MAX = 2;
    static final int GPU_PASS_FIELDS = 3;
    /** label &rarr; {occurrences, summed nanos, max nanos}. Only allocated on demand. */
    private static final HashMap<String, long[]> GPU_PASS_TALLY =
            ENABLED && GPU_PASSES ? new HashMap<>() : null;
    /** Command buffers in the window that hit the per-command-buffer sample cap. */
    private static long gpuTruncatedBuffers;

    private static long drawCalls;
    private static long renderPassesCreated;
    private static long renderPassesSubmitted;
    private static long encoderSwitches;
    private static long textureCopyBytes;
    private static long uniformUploadBytes;
    /** Native encoders this window actually opened, by kind. */
    private static long realRenderEncoders;
    private static long clearRenderEncoders;
    private static long blitEncoders;
    /** Attachment bytes counted per native encoder instead of per render pass. */
    private static long encoderLoadBytes;
    private static long encoderStoreBytes;
    /** Attachment bytes this window did not load/store because of the P1 masks. */
    private static long elidedLoadBytes;
    private static long elidedStoreBytes;

    /** Aggregated attachment bytes of one render pass creation (pure math, unit-testable). */
    record PassTraffic(
            long loadBytes,
            long storeBytes,
            int colorAttachments,
            boolean depthAttachment,
            boolean fullCoverage
    ) {
    }

    /**
     * Attachment bytes of one native render encoder's lifetime (pure math,
     * unit-testable). A reused encoder (same attachment handles, no clear)
     * contributes nothing additional, which is exactly the difference between
     * this and the per-pass {@link PassTraffic} model.
     */
    record EncoderTraffic(long loadBytes, long storeBytes) {
    }

    // Per-pass-name aggregate: load, store, colors, depth flag, occurrences,
    // partial-coverage occurrences (in that order). Only allocated on demand.
    private static final int TRAFFIC_LOAD = 0;
    private static final int TRAFFIC_STORE = 1;
    private static final int TRAFFIC_COLORS = 2;
    private static final int TRAFFIC_DEPTH = 3;
    private static final int TRAFFIC_PASSES = 4;
    private static final int TRAFFIC_PARTIAL = 5;
    private static final int TRAFFIC_FIELDS = 6;
    /** Caps distinct pass names so a label that embeds a frame counter cannot grow the map. */
    private static final int TRAFFIC_NAME_LIMIT = 256;
    private static final int TOP_PASSES = 8;
    private static final String TRAFFIC_OTHER = "(other)";
    private static final String COPY_REASON_OTHER = "other";

    private static final HashMap<String, long[]> PASS_TRAFFIC = ENABLED ? new HashMap<>() : null;
    private static final HashMap<String, long[]> COPY_TRAFFIC = ENABLED ? new HashMap<>() : null;
    private static long trafficLoadBytes;
    private static long trafficStoreBytes;
    private static long clearsQueued;
    private static long clearsMaterialized;
    private static long clearsDiscarded;
    private static long clearOnlyPasses;

    private static long frameStartNanos;
    private static long totalFrames;
    private static int windowFrames;
    private static boolean switchLineRecorded;

    /** Pass labels of the frame currently being dumped ({@code orderFrame > 0}). */
    private static final String[] ORDER_LABELS = ENABLED ? new String[ORDER_ENTRY_LIMIT] : null;
    private static int orderCount;
    private static boolean orderOverflow;
    /** Level frames seen since the probe started; the static counter that stops the dump. */
    private static int levelFrame;
    /** Frame number whose pass order is being captured, 0 = not capturing. */
    private static int orderFrame;

    private MetalPerformanceProbe() {
    }

    /** Marks the first CPU timestamp of a level frame; inert when disabled. */
    static void beginFrame() {
        if (!ENABLED) {
            return;
        }
        frameStartNanos = System.nanoTime();
        if (!switchLineRecorded) {
            switchLineRecorded = true;
            MetalProbeReport.record("perf switches perf=on");
        }
        levelFrame++;
        if (levelFrame <= ORDER_FRAMES) {
            // Sample the first frames only: the world has just been loaded and
            // the framegraph is at its steady-state pass list.
            orderFrame = levelFrame;
            orderCount = 0;
            orderOverflow = false;
        }
    }

    /** Records the frame CPU time and emits the 120-frame window summary. */
    static void endFrame() {
        if (!ENABLED) {
            return;
        }
        flushOrderLine();
        if (frameStartNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - frameStartNanos;
        frameStartNanos = 0L;
        totalFrames++;
        windowFrames++;
        pushSample(FRAME_SAMPLES, frameNext, elapsed);
        frameSum += elapsed;
        if (frameCount < SAMPLE_CAPACITY) {
            frameCount++;
        }
        frameNext = (frameNext + 1) % SAMPLE_CAPACITY;
        if (windowFrames >= REPORT_INTERVAL) {
            report();
        }
    }

    /** Returns a start timestamp for {@link #endStage(Stage, long)}; 0 when off. */
    static long beginStage() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Folds one completed CPU stage into the current window. */
    static void endStage(final Stage stage, final long startNanos) {
        if (!ENABLED || startNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        int index = stage.ordinal();
        pushSample(STAGE_SAMPLES[index], STAGE_NEXT[index], elapsed);
        STAGE_SUM[index] += elapsed;
        STAGE_MIN[index] = STAGE_COUNT[index] == 0
                ? elapsed
                : Math.min(STAGE_MIN[index], elapsed);
        if (STAGE_COUNT[index] < SAMPLE_CAPACITY) {
            STAGE_COUNT[index]++;
        }
        STAGE_NEXT[index] = (STAGE_NEXT[index] + 1) % SAMPLE_CAPACITY;
    }

    static void recordDraw(final int count) {
        if (ENABLED) {
            drawCalls += count;
        }
    }

    static void recordRenderPassCreated() {
        if (ENABLED) {
            renderPassesCreated++;
        }
    }

    static void recordRenderPassSubmitted() {
        if (ENABLED) {
            renderPassesSubmitted++;
        }
    }

    /**
     * True only while one of the first {@link #ORDER_FRAMES} level frames is
     * being sampled. Callers use it to skip building the label and the
     * flip-side token at all once the dump is done.
     */
    static boolean capturingOrder() {
        return ENABLED && orderFrame > 0;
    }

    /**
     * Appends one render pass to the current frame's order dump. {@code label}
     * is the descriptor's own label (never a new concatenation); {@code side}
     * is the probe-only {@code main}/{@code alt}/{@code mixed} flip
     * attribution, or {@code null} when the attachment is not an Iris
     * ping-pong target.
     */
    static void recordRenderPassOrder(final String label, final String side) {
        if (!ENABLED || orderFrame == 0) {
            return;
        }
        int next = appendOrderEntry(ORDER_LABELS, orderCount, orderEntry(label, side));
        if (next == orderCount) {
            orderOverflow = true;
        }
        orderCount = next;
    }

    /** One dump entry: the existing label plus the probe-only flip-side suffix (pure). */
    static String orderEntry(final String label, final String side) {
        String base = label == null || label.isEmpty() ? "unnamed" : label;
        // '|' is the entry separator; a label containing one would split a
        // single pass into two columns of the dump.
        if (base.indexOf('|') >= 0) {
            base = base.replace('|', '/');
        }
        return side == null || side.isEmpty() ? base : base + '(' + side + ')';
    }

    /** Appends one entry while there is room; returns the new count (pure, capped). */
    static int appendOrderEntry(final String[] labels, final int count, final String entry) {
        int limit = Math.min(labels.length, ORDER_ENTRY_LIMIT);
        if (count < 0 || count >= limit) {
            return count;
        }
        labels[count] = entry;
        return count + 1;
    }

    /**
     * The {@code perf order frame=1 a | b | …} line. At most
     * {@link #ORDER_ENTRY_LIMIT} entries are emitted; a frame with more passes
     * ends in {@code | …} instead of growing without bound (pure).
     */
    static String passOrderLine(
            final int frame,
            final String[] labels,
            final int count,
            final boolean overflow
    ) {
        int limit = Math.min(labels.length, ORDER_ENTRY_LIMIT);
        int emitted = Math.max(0, Math.min(count, limit));
        StringBuilder line = new StringBuilder("perf order frame=").append(frame);
        for (int index = 0; index < emitted; index++) {
            line.append(index == 0 ? " " : ORDER_SEPARATOR).append(labels[index]);
        }
        if (overflow) {
            line.append(emitted == 0 ? " " : ORDER_SEPARATOR).append(ORDER_OVERFLOW);
        }
        return line.toString();
    }

    /** Emits and closes the current frame's dump; no-op when not capturing. */
    private static void flushOrderLine() {
        if (orderFrame == 0) {
            return;
        }
        MetalProbeReport.record(passOrderLine(orderFrame, ORDER_LABELS, orderCount, orderOverflow));
        orderFrame = 0;
        orderCount = 0;
        orderOverflow = false;
        for (int index = 0; index < ORDER_LABELS.length; index++) {
            ORDER_LABELS[index] = null;
        }
    }

    static void recordEncoderSwitch() {
        if (ENABLED) {
            encoderSwitches++;
        }
    }

    /**
     * Classifies one {@code renderCommandEncoder} call (pure, native-free).
     *
     * <p>The argument set is exactly the state the real branch tests, in the
     * real order: an attachment-set comparison can only be made between two
     * render encoders, so a missing or non-render open encoder wins first; then
     * the color slot count, the per-slot handles and the depth handle (the
     * three ways {@code sameAttachmentHandles} can fail); then the pending
     * clear, which blocks reuse even with identical attachments. The result is
     * {@link EncoderDecision#REUSE} in exactly the case the branch returns the
     * open encoder.</p>
     */
    static EncoderDecision encoderDecision(
            final boolean hasOpenEncoder,
            final boolean openIsRenderEncoder,
            final int openColorSlots,
            final int newColorSlots,
            final boolean sameSlotHandles,
            final boolean sameDepthHandle,
            final boolean clearPending
    ) {
        if (!hasOpenEncoder) {
            return EncoderDecision.NO_OPEN;
        }
        if (!openIsRenderEncoder) {
            return EncoderDecision.KIND;
        }
        if (openColorSlots != newColorSlots) {
            return EncoderDecision.COLOR_COUNT;
        }
        if (!sameSlotHandles) {
            return EncoderDecision.SLOT_HANDLE;
        }
        if (!sameDepthHandle) {
            return EncoderDecision.DEPTH;
        }
        if (clearPending) {
            return EncoderDecision.CLEAR;
        }
        return EncoderDecision.REUSE;
    }

    /** Folds one encoder decision into the current window. */
    static void recordEncoderDecision(final EncoderDecision decision) {
        if (ENABLED) {
            ENCODER_DECISION_COUNTS[(decision == null ? EncoderDecision.OTHER : decision).ordinal()]++;
        }
    }

    /**
     * The {@code perf encoders reuse=…} attribution line: why the 44 passes
     * became 31 native encoders, split into the branch that rejected reuse.
     * Every bucket except {@code reuse} opened exactly one native render
     * encoder, so their sum tracks {@code perf encoders real=…}.
     */
    static String encoderDecisionSummary(final long[] counts) {
        StringBuilder line = new StringBuilder("perf encoders");
        for (EncoderDecision decision : ENCODER_DECISIONS) {
            long value = counts == null || decision.ordinal() >= counts.length
                    ? 0L
                    : counts[decision.ordinal()];
            line.append(' ').append(decision.token).append('=').append(value);
        }
        return line.toString();
    }

    /** One native render encoder opened to encode draws ({@code renderCommandEncoder} new branch). */
    static void recordRenderEncoderCreated() {
        if (ENABLED) {
            realRenderEncoders++;
        }
    }

    /** One transient render encoder opened only to materialize a pending clear. */
    static void recordClearEncoderCreated() {
        if (ENABLED) {
            clearRenderEncoders++;
        }
    }

    /** One blit encoder opened. */
    static void recordBlitEncoderCreated() {
        if (ENABLED) {
            blitEncoders++;
        }
    }

    /**
     * Attachment bytes of one native render encoder. Unlike
     * {@link #passTraffic} this is charged once per encoder, so a window full of
     * passes that reuse the same attachment set reports the traffic Metal
     * actually pays ({@code load} only for attachments the encoder opened
     * without a clear, {@code store} for every attached extent).
     */
    static EncoderTraffic encoderTraffic(
            final long[] colorBytes,
            final boolean[] colorCleared,
            final long depthBytes,
            final boolean depthCleared
    ) {
        return encoderTraffic(colorBytes, colorCleared, null, null, depthBytes, depthCleared);
    }

    /**
     * Encoder traffic with P1 attachment-action skips: a slot whose load or
     * store was elided contributes nothing to that side. {@code null} skip
     * arrays reproduce the plain model above.
     */
    static EncoderTraffic encoderTraffic(
            final long[] colorBytes,
            final boolean[] colorCleared,
            final boolean[] colorLoadSkipped,
            final boolean[] colorStoreSkipped,
            final long depthBytes,
            final boolean depthCleared
    ) {
        long load = 0L;
        long store = 0L;
        for (int index = 0; index < colorBytes.length; index++) {
            long bytes = colorBytes[index];
            if (bytes <= 0L) {
                continue;
            }
            if (colorStoreSkipped == null || index >= colorStoreSkipped.length
                    || !colorStoreSkipped[index]) {
                store += bytes;
            }
            if (!colorCleared[index]
                    && (colorLoadSkipped == null || index >= colorLoadSkipped.length
                    || !colorLoadSkipped[index])) {
                load += bytes;
            }
        }
        if (depthBytes > 0L) {
            store += depthBytes;
            if (!depthCleared) {
                load += depthBytes;
            }
        }
        return new EncoderTraffic(load, store);
    }

    /**
     * Folds the attachment bytes this window elided through the P1 masks into
     * the probe, so the {@code perf bandwidth elided} line can show the
     * saving next to the declared/encoder totals.
     */
    static void recordElidedAttachmentTraffic(final long loadBytes, final long storeBytes) {
        if (!ENABLED) {
            return;
        }
        if (loadBytes > 0L) {
            elidedLoadBytes += loadBytes;
        }
        if (storeBytes > 0L) {
            elidedStoreBytes += storeBytes;
        }
    }

    /** Folds one finished native render encoder into the window. */
    static void recordEncoderTraffic(final long loadBytes, final long storeBytes) {
        if (!ENABLED) {
            return;
        }
        encoderLoadBytes += loadBytes;
        encoderStoreBytes += storeBytes;
    }

    /**
     * The {@code perf encoders ...} line. {@code real} counts draw encoders,
     * {@code clear} the transient clear encoders and {@code blit} the blit
     * encoders; only {@code real} should track pass creations when attachments
     * are stable.
     */
    static String encoderSummary(final long real, final long clear, final long blit) {
        return String.format(Locale.ROOT, "perf encoders real=%d clear=%d blit=%d", real, clear, blit);
    }

    /**
     * Per-reason texture-copy attribution. {@code reason} is a constant label
     * owned by the caller (history canonicalization, depth captures, fallback
     * blits, ...), so a window can show which copy it is paying for.
     */
    static void recordTextureCopy(final long bytes, final String reason) {
        if (!ENABLED || bytes <= 0L) {
            return;
        }
        textureCopyBytes += bytes;
        String key = reason == null ? COPY_REASON_OTHER : reason;
        long[] slot = COPY_TRAFFIC.get(key);
        if (slot == null) {
            if (COPY_TRAFFIC.size() >= TRAFFIC_NAME_LIMIT) {
                key = TRAFFIC_OTHER;
                slot = COPY_TRAFFIC.get(key);
            }
            if (slot == null) {
                slot = new long[1];
                COPY_TRAFFIC.put(key, slot);
            }
        }
        slot[0] += bytes;
    }

    /** One clear request queued on the encoder (folded into a pass clear or materialized). */
    static void recordClearQueued() {
        if (ENABLED) {
            clearsQueued++;
        }
    }

    /**
     * One pending clear dropped because its target was torn down before the
     * clear was flushed (generation rebuild / resize).
     */
    static void recordClearDiscarded() {
        if (ENABLED) {
            clearsDiscarded++;
        }
    }

    /** One pending clear that had to open its own transient render encoder. */
    static void recordClearMaterialized() {
        if (ENABLED) {
            clearsMaterialized++;
        }
    }

    /**
     * Folds one render pass's attachment traffic into the window. {@code name}
     * is the pass label the renderer already builds; it is only touched while
     * the probe is enabled.
     */
    static void recordRenderPassTraffic(final String name, final PassTraffic traffic) {
        if (!ENABLED || traffic == null) {
            return;
        }
        trafficLoadBytes += traffic.loadBytes();
        trafficStoreBytes += traffic.storeBytes();
        String key = name == null ? "unnamed" : name;
        long[] slot = PASS_TRAFFIC.get(key);
        if (slot == null && PASS_TRAFFIC.size() >= TRAFFIC_NAME_LIMIT) {
            key = TRAFFIC_OTHER;
            slot = PASS_TRAFFIC.get(key);
        }
        if (slot == null) {
            slot = new long[TRAFFIC_FIELDS];
            PASS_TRAFFIC.put(key, slot);
        }
        slot[TRAFFIC_LOAD] += traffic.loadBytes();
        slot[TRAFFIC_STORE] += traffic.storeBytes();
        slot[TRAFFIC_COLORS] = traffic.colorAttachments();
        slot[TRAFFIC_DEPTH] = traffic.depthAttachment() ? 1L : 0L;
        slot[TRAFFIC_PASSES]++;
        if (!traffic.fullCoverage()) {
            slot[TRAFFIC_PARTIAL]++;
        }
        if (traffic.loadBytes() == 0L && (traffic.colorAttachments() > 0 || traffic.depthAttachment())) {
            // Fully cleared pass: independent clear work (or a clear-dominated
            // fullscreen pass) that could be folded into loadAction=.clear.
            clearOnlyPasses++;
        }
    }

    /**
     * Attachment traffic of one render pass creation.
     *
     * <p>Native always stores ({@code storeAction = .store}) and only clears an
     * attachment when the descriptor supplied a clear value; without one it
     * loads the previous contents ({@code loadAction = .load}). The load side
     * is therefore exactly "attachments this pass reads without ever using
     * them", which is the Apple-tile bandwidth waste this probe hunts for.</p>
     *
     * @param areaX           render area origin x
     * @param areaY           render area origin y
     * @param areaWidth       render area width
     * @param areaHeight      render area height
     * @param extentWidth     attachment (level 0) width all attachments share
     * @param extentHeight    attachment (level 0) height all attachments share
     * @param allViewsFull    every attachment view covers its whole texture
     * @param colorBpp        bytes per pixel per color slot; {@code 0} = unused slot
     * @param colorCleared    whether that slot supplies a clear value
     * @param depthBpp        depth bytes per pixel; {@code 0} = no depth attachment
     * @param depthCleared    whether the depth attachment supplies a clear value
     */
    static PassTraffic passTraffic(
            final int areaX,
            final int areaY,
            final int areaWidth,
            final int areaHeight,
            final int extentWidth,
            final int extentHeight,
            final boolean allViewsFull,
            final int[] colorBpp,
            final boolean[] colorCleared,
            final int depthBpp,
            final boolean depthCleared
    ) {
        long pixels = (long) areaWidth * (long) areaHeight;
        long load = 0L;
        long store = 0L;
        int colors = 0;
        for (int index = 0; index < colorBpp.length; index++) {
            int bytesPerPixel = colorBpp[index];
            if (bytesPerPixel <= 0) {
                continue;
            }
            colors++;
            store += pixels * bytesPerPixel;
            if (!colorCleared[index]) {
                load += pixels * bytesPerPixel;
            }
        }
        boolean depth = depthBpp > 0;
        if (depth) {
            store += pixels * depthBpp;
            if (!depthCleared) {
                load += pixels * depthBpp;
            }
        }
        boolean fullCoverage = allViewsFull
                && areaX == 0
                && areaY == 0
                && areaWidth == extentWidth
                && areaHeight == extentHeight;
        return new PassTraffic(load, store, colors, depth, fullCoverage);
    }

    static void recordUniformUpload(final long bytes) {
        if (ENABLED && bytes > 0L) {
            uniformUploadBytes += bytes;
        }
    }

    /**
     * One completed command-buffer GPU duration in milliseconds.
     *
     * <p>This is the whole-frame number only. The per-pass split is
     * {@link #recordGpuPass}: the native side samples each render / compute
     * encoder's start and end with {@code MTLCounterSampleBuffer} built from
     * {@code MTLCommonCounterSetTimestamp} and resolves the pairs once the
     * carrying command buffer completes.</p>
     */
    static void recordGpuFrame(final double milliseconds) {
        if (!ENABLED || !Double.isFinite(milliseconds) || milliseconds < 0.0) {
            return;
        }
        pushGpuSample(milliseconds);
        gpuSum += milliseconds;
        if (gpuCount < SAMPLE_CAPACITY) {
            gpuCount++;
        }
        gpuNext = (gpuNext + 1) % SAMPLE_CAPACITY;
    }

    /**
     * Folds one sampled pass into the window. One sample pair is one native
     * render or compute encoder's GPU lifetime, attributed to the label of the
     * pass that opened that encoder (a pass that reused an already-open encoder
     * pays no second encoder and therefore no second pair).
     *
     * @param label        pass label the renderer already builds; sanitized and
     *                     truncated by {@link #gpuPassLabel} before it is keyed
     * @param milliseconds resolved {@code end - start} of the sample pair
     */
    static void recordGpuPass(final String label, final double milliseconds) {
        if (GPU_PASS_TALLY == null || !Double.isFinite(milliseconds) || milliseconds < 0.0) {
            return;
        }
        foldGpuPass(GPU_PASS_TALLY, label, Math.round(milliseconds * 1_000_000.0));
    }

    /**
     * Pure tally fold: adds one sampled encoder to {@code tally} (label &rarr;
     * {@code {occurrences, summed nanos, max nanos}}), sanitizing the label and
     * merging overflow labels into {@code (other)} so a label that embeds a
     * frame counter cannot grow the map without bound.
     *
     * @return the slot the sample was folded into
     */
    static long[] foldGpuPass(final HashMap<String, long[]> tally, final String label, final long nanos) {
        String key = gpuPassLabel(label);
        long[] slot = tally.get(key);
        if (slot == null && tally.size() >= GPU_PASS_LIMIT) {
            key = TRAFFIC_OTHER;
            slot = tally.get(key);
        }
        if (slot == null) {
            slot = new long[GPU_PASS_FIELDS];
            tally.put(key, slot);
        }
        slot[GPU_PASS_COUNT]++;
        slot[GPU_PASS_SUM] += Math.max(0L, nanos);
        slot[GPU_PASS_MAX] = Math.max(slot[GPU_PASS_MAX], Math.max(0L, nanos));
        return slot;
    }

    /**
     * One command buffer hit {@link GpuSampleLedger#MAX_SAMPLES} and stopped
     * sampling. Folded into the window and reported once, so a pathologically
     * long command buffer cannot flood the probe.
     */
    static void recordGpuPassTruncated() {
        if (ENABLED && GPU_PASSES) {
            gpuTruncatedBuffers++;
        }
    }

    /**
     * The per-pass GPU timing path is inert for this run; emitted once, by the
     * profiler that discovered it. {@code reason} is one of
     * {@link #gpuUnavailableReason}'s tokens.
     */
    static void recordGpuUnavailable(final String reason) {
        if (ENABLED) {
            MetalProbeReport.record("perf gpu unavailable reason=" + reason);
        }
    }

    /**
     * The probe's single-token pass label: every whitespace character becomes
     * {@code '_'} so a {@code perf gpu pass=} line stays one token per field,
     * then the result is capped at {@link #GPU_LABEL_LIMIT} characters.
     * {@code null} and the empty string map to {@code unnamed} (pure).
     */
    static String gpuPassLabel(final String label) {
        String base = label == null || label.isEmpty() ? "unnamed" : label;
        char[] chars = null;
        int limit = Math.min(base.length(), GPU_LABEL_LIMIT);
        for (int index = 0; index < limit; index++) {
            char value = base.charAt(index);
            if (Character.isWhitespace(value)) {
                if (chars == null) {
                    chars = base.toCharArray();
                }
                chars[index] = '_';
            }
        }
        String sanitized = chars == null ? base : new String(chars, 0, base.length());
        return sanitized.length() <= GPU_LABEL_LIMIT
                ? sanitized
                : sanitized.substring(0, GPU_LABEL_LIMIT);
    }

    /**
     * One {@code perf gpu pass=…} line: the window average in {@code ms=} (the
     * required field), then the number of sampled encoders and the slowest
     * single encoder of the window (pure).
     */
    static String gpuPassLine(
            final String label,
            final long count,
            final long sumNanos,
            final long maxNanos
    ) {
        double averageMillis = count <= 0L ? 0.0 : sumNanos / (double) count / 1_000_000.0;
        return String.format(Locale.ROOT, "perf gpu pass=%s ms=%.2f n=%d max=%.2f",
                gpuPassLabel(label), averageMillis, count, maxNanos / 1_000_000.0);
    }

    /**
     * The native availability code &rarr; the encoder kinds this run can
     * sample (pure). Stage-boundary sampling rides on the pass descriptor and
     * therefore covers both encoder kinds at once; the draw/dispatch-boundary
     * fallback covers one kind per supported sampling point.
     */
    static int gpuSupportedKinds(final int mask) {
        if ((mask & GPU_MODE_STAGE_BOUNDARY) != 0) {
            return GPU_KIND_RENDER | GPU_KIND_COMPUTE;
        }
        int kinds = 0;
        if ((mask & GPU_KIND_RENDER) != 0) {
            kinds |= GPU_KIND_RENDER;
        }
        if ((mask & GPU_KIND_COMPUTE) != 0) {
            kinds |= GPU_KIND_COMPUTE;
        }
        return kinds;
    }

    /**
     * Native availability code &rarr; the {@code reason=} token of
     * {@code perf gpu unavailable}. The native probe returns a positive
     * sampling-point mask when sampling works, {@code 0} when the OS has no
     * counter sampling at all, {@code -1} when no counter set named
     * {@code MTLCommonCounterSetTimestamp} exists and {@code -2} when it exists
     * but no sampling point this profiler uses is supported;
     * {@link #GPU_NO_SYMBOL} means the loaded dylib predates the entry points
     * (pure).
     */
    static String gpuUnavailableReason(final int code) {
        if (code == GPU_NO_SYMBOL) {
            return "noSymbol";
        }
        if (code == -1) {
            return "counterSet";
        }
        if (code == -2) {
            return "samplingPoint";
        }
        return "unsupported";
    }

    /**
     * Sample-index accounting for one command buffer: every native encoder that
     * opens reserves two consecutive indices (start, end), so pair {@code k}
     * always lives at {@code 2k} / {@code 2k+1}. Pure — the native side only
     * ever sees the indices, the labels stay on this side. The cap is checked
     * before a pair is reserved, so a truncated command buffer leaves complete
     * pairs behind and never reads an unwritten index.
     */
    static final class GpuSampleLedger {
        /** Hard cap on timestamp samples per command buffer (128 encoder pairs). */
        static final int MAX_SAMPLES = 256;
        private static final int MAX_PAIRS = MAX_SAMPLES / 2;
        private final String[] labels = new String[MAX_PAIRS];
        private int samples;
        private int pairs;
        private boolean open;
        private boolean truncated;

        void reset() {
            samples = 0;
            pairs = 0;
            open = false;
            truncated = false;
            Arrays.fill(labels, null);
        }

        /**
         * Reserves the start index of one encoder.
         *
         * @return the sample index to hand to the native sample call, or
         *         {@code -1} when the cap is reached (the caller then leaves
         *         this encoder unsampled and {@link #end()} pairs nothing)
         */
        int start(final String label) {
            if (open) {
                return -1;
            }
            if (samples + 2 > MAX_SAMPLES) {
                truncated = true;
                return -1;
            }
            labels[pairs] = label;
            open = true;
            return samples++;
        }

        /**
         * Reserves a whole pair at once: the stage-boundary path hands both
         * indices to the pass descriptor before the encoder exists, so there is
         * no later {@link #end()} call.
         *
         * @return the pair's start index (its end index is {@code start + 1}),
         *         or {@code -1} when the cap is reached
         */
        int reservePair(final String label) {
            if (open) {
                return -1;
            }
            if (samples + 2 > MAX_SAMPLES) {
                truncated = true;
                return -1;
            }
            labels[pairs] = label;
            pairs++;
            int start = samples;
            samples += 2;
            return start;
        }

        /** Reserves the end index of the open pair; {@code -1} when nothing is open. */
        int end() {
            if (!open) {
                return -1;
            }
            open = false;
            pairs++;
            return samples++;
        }

        int sampleCount() {
            return samples;
        }

        int pairCount() {
            return pairs;
        }

        String labelAt(final int pair) {
            return pair >= 0 && pair < labels.length ? labels[pair] : null;
        }

        boolean truncated() {
            return truncated;
        }
    }

    private static void report() {
        long frames = totalFrames;
        String cpuAvg = formatMillis(average(frameSum, windowFrames));
        String cpuP95 = formatMillis(percentile(FRAME_SAMPLES, frameCount));
        String gpuAvg = gpuCount == 0
                ? "n/a"
                : String.format(Locale.ROOT, "%.1fms", gpuSum / gpuCount);
        String gpuP95 = gpuCount == 0
                ? "n/a"
                : String.format(Locale.ROOT, "%.1fms", gpuPercentile(gpuCount));

        MetalProbeReport.record(String.format(Locale.ROOT,
                "perf frame n=%d cpuAvg=%s cpuP95=%s gpuAvg=%s gpuP95=%s"
                        + " draws=%d passCreate=%d passSubmit=%d encoders=%d"
                        + " copies=%.1fMB uniforms=%.1fMB",
                frames, cpuAvg, cpuP95, gpuAvg, gpuP95,
                drawCalls, renderPassesCreated, renderPassesSubmitted, encoderSwitches,
                textureCopyBytes / BYTES_PER_MIB, uniformUploadBytes / BYTES_PER_MIB));

        // Real native encoder openings, next to the per-pass `encoders=` switch
        // count above: pass creations that reuse the open encoder do not pay a
        // new encoder, which is what makes the declared bandwidth an upper bound.
        MetalProbeReport.record(encoderSummary(realRenderEncoders, clearRenderEncoders, blitEncoders));

        // Why the per-pass encoder reuse did or did not happen: one bucket per
        // `renderCommandEncoder` call, so every non-reuse bucket is one native
        // encoder and their sum tracks realRenderEncoders above.
        MetalProbeReport.record(encoderDecisionSummary(ENCODER_DECISION_COUNTS));

        StringBuilder stages = new StringBuilder("perf stages");
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            stages.append(' ').append(stage.label).append('=');
            appendStageStats(stages, index);
        }
        MetalProbeReport.record(stages.toString());

        reportBandwidth();
        reportCopies();
        reportGpuPasses();

        resetWindow();
    }

    /**
     * The per-pass GPU timing block ({@code -Dmetallum.perf.perPassGpu} only):
     * one {@code perf gpu pass=<label> ms=<avg> n=<count> max=<max>} line per
     * distinct pass label, heaviest accumulated GPU time first. A window that
     * resolved no samples prints nothing here — the profiler already emitted
     * its one {@code perf gpu unavailable reason=…} line if sampling never
     * started. Samples trail the frame by up to the submit ring depth (a
     * command buffer's pairs are only final once its completion is observed),
     * so the last few frames of a window land in the next one.
     */
    private static void reportGpuPasses() {
        if (GPU_PASS_TALLY == null) {
            return;
        }
        if (gpuTruncatedBuffers > 0L) {
            MetalProbeReport.record("perf gpu truncated buffers=" + gpuTruncatedBuffers
                    + " cap=" + GpuSampleLedger.MAX_SAMPLES);
        }
        if (GPU_PASS_TALLY.isEmpty()) {
            return;
        }
        int emitted = 0;
        for (Map.Entry<String, long[]> entry : rankGpuPasses(GPU_PASS_TALLY)) {
            if (emitted >= GPU_PASS_TOP) {
                break;
            }
            long[] slot = entry.getValue();
            MetalProbeReport.record(gpuPassLine(
                    entry.getKey(), slot[GPU_PASS_COUNT], slot[GPU_PASS_SUM], slot[GPU_PASS_MAX]
            ));
            emitted++;
        }
    }

    /**
     * Pure ranking of the per-pass GPU tally: heaviest accumulated GPU time
     * first (that is where a frame actually spends its time), ties broken by
     * the label so the line order is deterministic.
     */
    static List<Map.Entry<String, long[]>> rankGpuPasses(final Map<String, long[]> tally) {
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(tally.entrySet());
        ranked.sort((left, right) -> {
            int byTime = Long.compare(right.getValue()[GPU_PASS_SUM], left.getValue()[GPU_PASS_SUM]);
            return byTime != 0 ? byTime : left.getKey().compareTo(right.getKey());
        });
        return ranked;
    }

    /**
     * One declared-traffic total (every render pass charged full-screen load +
     * store), the matching encoder-boundary total, and the eight heaviest pass
     * names. Both totals share the window; {@code declared} is the
     * upper-bound model this probe has always reported, {@code encoder} is
     * what the native encoders were actually charged. Sorted by load+store so
     * the top of the list is where the bandwidth goes; {@code full=true} means
     * the render area covered every attachment view, so a
     * {@code .dontCare}/clear-only load decision would be safe for it.
     */
    private static void reportBandwidth() {
        long passesPerFrame = windowFrames == 0
                ? 0L
                : Math.round(renderPassesCreated / (double) windowFrames);
        MetalProbeReport.record(String.format(Locale.ROOT,
                "perf bandwidth declared w=%d load=%dMB store=%dMB passes=%d clears=%d clearQ=%d clearDrop=%d kinds=%d",
                windowFrames, toMib(trafficLoadBytes), toMib(trafficStoreBytes), passesPerFrame,
                clearsMaterialized + clearOnlyPasses, clearsQueued, clearsDiscarded, PASS_TRAFFIC.size()));

        MetalProbeReport.record(String.format(Locale.ROOT,
                "perf bandwidth encoder w=%d load=%dMB store=%dMB encoders=%d",
                windowFrames, toMib(encoderLoadBytes), toMib(encoderStoreBytes),
                realRenderEncoders + clearRenderEncoders));

        // P1: attachment bytes the graph did not load/store this window. Both
        // sides come from the per-encoder extents the masks actually skipped.
        MetalProbeReport.record(String.format(Locale.ROOT,
                "perf bandwidth elided w=%d load=%dMB store=%dMB",
                windowFrames, toMib(elidedLoadBytes), toMib(elidedStoreBytes)));

        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(PASS_TRAFFIC.entrySet());
        ranked.sort((left, right) -> Long.compare(weight(right.getValue()), weight(left.getValue())));
        int emitted = 0;
        for (Map.Entry<String, long[]> entry : ranked) {
            if (emitted >= TOP_PASSES) {
                break;
            }
            long[] slot = entry.getValue();
            MetalProbeReport.record(String.format(Locale.ROOT,
                    "perf bandwidth pass=%s load=%dMB store=%dMB colors=%d depth=%d full=%s",
                    sanitizeName(entry.getKey()),
                    toMib(slot[TRAFFIC_LOAD]), toMib(slot[TRAFFIC_STORE]),
                    slot[TRAFFIC_COLORS], slot[TRAFFIC_DEPTH],
                    slot[TRAFFIC_PARTIAL] == 0L ? "true" : "false"));
            emitted++;
        }
    }

    /** One line with the window's texture-copy bytes per reason (non-zero only). */
    private static void reportCopies() {
        if (COPY_TRAFFIC.isEmpty()) {
            return;
        }
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(COPY_TRAFFIC.entrySet());
        ranked.sort((left, right) -> Long.compare(right.getValue()[0], left.getValue()[0]));
        StringBuilder line = new StringBuilder("perf copies total=")
                .append(toMib(textureCopyBytes)).append("MB");
        for (Map.Entry<String, long[]> entry : ranked) {
            long bytes = entry.getValue()[0];
            if (bytes <= 0L) {
                continue;
            }
            line.append(' ').append(sanitizeName(entry.getKey())).append('=')
                    .append(toMib(bytes)).append("MB");
        }
        MetalProbeReport.record(line.toString());
    }

    private static long weight(final long[] slot) {
        return slot[TRAFFIC_LOAD] + slot[TRAFFIC_STORE];
    }

    private static long toMib(final long bytes) {
        return Math.round(bytes / BYTES_PER_MIB);
    }

    /** Pass/copy keys are single space-free tokens so the probe line stays parseable. */
    private static String sanitizeName(final String name) {
        return name.indexOf(' ') < 0 ? name : name.replace(' ', '_');
    }

    private static void appendStageStats(final StringBuilder out, final int index) {
        int count = STAGE_COUNT[index];
        if (count == 0) {
            out.append("n/a");
            return;
        }
        long[] samples = STAGE_SAMPLES[index];
        long p95 = percentile(samples, count);
        out.append(formatMillis((double) STAGE_MIN[index]))
                .append('/')
                .append(formatMillis(STAGE_SUM[index] / (double) count))
                .append('/')
                .append(formatMillis((double) p95));
    }

    private static void resetWindow() {
        for (int index = 0; index < STAGES.length; index++) {
            STAGE_NEXT[index] = 0;
            STAGE_COUNT[index] = 0;
            STAGE_MIN[index] = Long.MAX_VALUE;
            STAGE_SUM[index] = 0L;
        }
        frameNext = 0;
        frameCount = 0;
        frameSum = 0L;
        gpuNext = 0;
        gpuCount = 0;
        gpuSum = 0.0;
        drawCalls = 0L;
        renderPassesCreated = 0L;
        renderPassesSubmitted = 0L;
        encoderSwitches = 0L;
        realRenderEncoders = 0L;
        clearRenderEncoders = 0L;
        blitEncoders = 0L;
        encoderLoadBytes = 0L;
        encoderStoreBytes = 0L;
        elidedLoadBytes = 0L;
        elidedStoreBytes = 0L;
        if (ENCODER_DECISION_COUNTS != null) {
            Arrays.fill(ENCODER_DECISION_COUNTS, 0L);
        }
        textureCopyBytes = 0L;
        uniformUploadBytes = 0L;
        trafficLoadBytes = 0L;
        trafficStoreBytes = 0L;
        clearsQueued = 0L;
        clearsMaterialized = 0L;
        clearsDiscarded = 0L;
        clearOnlyPasses = 0L;
        if (PASS_TRAFFIC != null) {
            PASS_TRAFFIC.clear();
        }
        if (COPY_TRAFFIC != null) {
            COPY_TRAFFIC.clear();
        }
        if (GPU_PASS_TALLY != null) {
            GPU_PASS_TALLY.clear();
        }
        gpuTruncatedBuffers = 0L;
        windowFrames = 0;
    }

    private static void pushSample(final long[] samples, final int next, final long value) {
        samples[next] = value;
    }

    private static void pushGpuSample(final double value) {
        GPU_SAMPLES[gpuNext] = value;
    }

    private static double average(final long sum, final int count) {
        return count == 0 ? Double.NaN : sum / (double) count;
    }

    /**
     * Nearest-rank p95 over the retained samples. A static scratch array keeps
     * even the 120-frame report free of per-window allocation.
     */
    private static long percentile(final long[] samples, final int count) {
        if (count <= 0) {
            return 0L;
        }
        int usable = Math.min(count, SAMPLE_CAPACITY);
        System.arraycopy(samples, 0, SCRATCH, 0, usable);
        Arrays.sort(SCRATCH, 0, usable);
        int rank = (int) Math.ceil(0.95 * usable) - 1;
        return SCRATCH[Math.max(0, Math.min(rank, usable - 1))];
    }

    private static double gpuPercentile(final int count) {
        if (count <= 0) {
            return 0.0;
        }
        double[] sorted = new double[Math.min(count, SAMPLE_CAPACITY)];
        System.arraycopy(GPU_SAMPLES, 0, sorted, 0, sorted.length);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(rank, sorted.length - 1))];
    }

    /**
     * Frame and stage samples are stored in nanoseconds from
     * {@link System#nanoTime()}; the probe reports milliseconds.
     */
    private static String formatMillis(final double nanos) {
        return Double.isFinite(nanos)
                ? String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0)
                : "n/a";
    }
}
