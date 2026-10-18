package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Arrays;
import java.util.Locale;

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

    static void recordTextureCopy(final long bytes) {
        if (ENABLED && bytes > 0L) {
            textureCopyBytes += bytes;
        }
    }

    static void recordUniformUpload(final long bytes) {
        if (ENABLED && bytes > 0L) {
            uniformUploadBytes += bytes;
        }
    }

    /** One completed command-buffer GPU duration in milliseconds. */
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

        StringBuilder stages = new StringBuilder("perf stages");
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            stages.append(' ').append(stage.label).append('=');
            appendStageStats(stages, index);
        }
        MetalProbeReport.record(stages.toString());

        resetWindow();
    }

    private static void appendStageStats(final StringBuilder out, final int index) {
        int count = STAGE_COUNT[index];
        if (count == 0) {
            out.append("n/a");
            return;
        }
        long[] samples = STAGE_SAMPLES[index];
        long p95 = percentile(samples, count);
        out.append(formatNumber((double) STAGE_MIN[index]))
                .append('/')
                .append(formatNumber(STAGE_SUM[index] / (double) count))
                .append('/')
                .append(formatNumber((double) p95))
                .append("ms");
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
        textureCopyBytes = 0L;
        uniformUploadBytes = 0L;
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

    private static String formatMillis(final double millis) {
        return Double.isFinite(millis)
                ? String.format(Locale.ROOT, "%.1fms", millis)
                : "n/a";
    }

    private static String formatNumber(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
