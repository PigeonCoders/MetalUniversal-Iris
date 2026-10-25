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
    static final boolean ENABLED = MetalDebugSwitches.PERF;

    static final int REPORT_INTERVAL = 120;
    private static final int SAMPLE_CAPACITY = 128;
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

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
    private static long clearOnlyPasses;

    private static long frameStartNanos;
    private static long totalFrames;
    private static int windowFrames;
    private static boolean switchLineRecorded;

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
    }

    /** Records the frame CPU time and emits the 120-frame window summary. */
    static void endFrame() {
        if (!ENABLED || frameStartNanos == 0L) {
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

    static void recordEncoderSwitch() {
        if (ENABLED) {
            encoderSwitches++;
        }
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
        long load = 0L;
        long store = 0L;
        for (int index = 0; index < colorBytes.length; index++) {
            long bytes = colorBytes[index];
            if (bytes <= 0L) {
                continue;
            }
            store += bytes;
            if (!colorCleared[index]) {
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

    static void recordTextureCopy(final long bytes) {
        recordTextureCopy(bytes, COPY_REASON_OTHER);
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
     * <p>Per-pass GPU time is deliberately not sampled: it needs an
     * {@code MTLCounterSampleBuffer} built from {@code
     * MTLCommonCounterSetTimestamp}, a sample attachment on every encoder and a
     * resolve/readback path, none of which this repository's FFM bridge
     * exposes (only {@code MTLCommandBuffer.gpuStartTime/gpuEndTime}). That is
     * a native (Swift {@code @_cdecl}) change which cannot be built or
     * validated on the Linux development host, so
     * {@code -Dmetallum.perf.perPassGpu} is not wired.</p>
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

        StringBuilder stages = new StringBuilder("perf stages");
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            stages.append(' ').append(stage.label).append('=');
            appendStageStats(stages, index);
        }
        MetalProbeReport.record(stages.toString());

        reportBandwidth();
        reportCopies();

        resetWindow();
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
                "perf bandwidth declared w=%d load=%dMB store=%dMB passes=%d clears=%d clearQ=%d kinds=%d",
                windowFrames, toMib(trafficLoadBytes), toMib(trafficStoreBytes), passesPerFrame,
                clearsMaterialized + clearOnlyPasses, clearsQueued, PASS_TRAFFIC.size()));

        MetalProbeReport.record(String.format(Locale.ROOT,
                "perf bandwidth encoder w=%d load=%dMB store=%dMB encoders=%d",
                windowFrames, toMib(encoderLoadBytes), toMib(encoderStoreBytes),
                realRenderEncoders + clearRenderEncoders));

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
        textureCopyBytes = 0L;
        uniformUploadBytes = 0L;
        trafficLoadBytes = 0L;
        trafficStoreBytes = 0L;
        clearsQueued = 0L;
        clearsMaterialized = 0L;
        clearOnlyPasses = 0L;
        if (PASS_TRAFFIC != null) {
            PASS_TRAFFIC.clear();
        }
        if (COPY_TRAFFIC != null) {
            COPY_TRAFFIC.clear();
        }
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
