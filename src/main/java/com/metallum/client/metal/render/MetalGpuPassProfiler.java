package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLComputeCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Per-pass GPU timing through {@code MTLCounterSampleBuffer} +
 * {@code MTLCommonCounterSetTimestamp} ({@code -Dmetallum.perf.perPassGpu}).
 *
 * <p>Every native render / compute encoder gets one sample pair, attributed to
 * the label of the pass that opened it (a pass that reused an already-open
 * encoder opens no encoder and therefore pays no second pair). Two native
 * modes produce that pair:</p>
 *
 * <ul>
 *   <li><b>Stage boundary</b> (primary; the only sampling point Apple silicon
 *       supports): the pair is attached to the pass descriptor's
 *       {@code sampleBufferAttachments[0]} when the encoder is created
 *       ({@code startOfVertexSampleIndex}..{@code endOfFragmentSampleIndex} for
 *       render, {@code startOfEncoderSampleIndex}..{@code endOfEncoderSampleIndex}
 *       for compute) through {@code makeRenderCommandEncoderSampled} /
 *       {@code makeComputeCommandEncoderSampled}. Nothing is inserted into the
 *       command stream, so the measurement adds no barrier.</li>
 *   <li><b>Draw / dispatch boundary</b> (fallback for immediate-mode GPUs such
 *       as Intel Macs): one {@code sampleCounters} call right after the encoder
 *       opens and one right before it ends, with {@code barrier: false} for
 *       render and {@code true} for compute.</li>
 * </ul>
 *
 * <p>A command buffer borrows one {@link Sample} (a timestamp buffer plus the
 * label/index ledger) from a small pool and carries it until the submit ring
 * observes that buffer completing; the sample is resolved at exactly that
 * moment and only then returned to the pool. Tying ownership to the command
 * buffer instead of to a ring index means an unusual submit pattern (empty
 * submits, skipped frames) can never resolve one frame's indices against
 * another frame's labels or overwrite a timestamp the GPU still needs. Buffers
 * are created with {@code .shared} storage, so the resolve is a plain host read
 * of {@code MTLCounterResultTimestamp} nanoseconds.</p>
 *
 * <p>Everything here is optional: a device without the timestamp counter set,
 * a device whose sampling points this profiler needs are unsupported, a failed
 * sample-buffer creation, a failing entry point and a dylib that predates the
 * symbols all latch the profiler off after one
 * {@code perf gpu unavailable reason=…} line. The caller then runs exactly the
 * pre-existing encoder path — nothing is thrown.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalGpuPassProfiler {
    /** The switch itself; a {@code static final} the JIT folds to {@code false}. */
    static final boolean ENABLED = MetalDebugSwitches.PER_PASS_GPU;

    /**
     * Pool cap. Three command buffers can be in flight at once and the ring can
     * hold one more record than it resolves, so four samples cover the steady
     * state; the headroom absorbs an empty-submit pattern that leaves records
     * unresolved for a while. A buffer that cannot borrow a sample simply goes
     * unsampled.
     */
    private static final int MAX_SAMPLES_IN_FLIGHT = 8;

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final int SAMPLE_CAPACITY = MetalPerformanceProbe.GpuSampleLedger.MAX_SAMPLES;
    /** One timestamp (nanoseconds) per sample; the scratch is shared by every resolve. */
    private static final long SCRATCH_BYTES = (long) SAMPLE_CAPACITY * Long.BYTES;

    private final MemorySegment device;
    private final Sample[] pool = new Sample[MAX_SAMPLES_IN_FLIGHT];
    private final MemorySegment resolveScratch = Arena.ofAuto().allocate(SCRATCH_BYTES);

    /** Sample of the command buffer currently being encoded, {@code null} when none. */
    private @Nullable Sample current;
    /** Label of the pass that is opening encoders right now. */
    private @Nullable String passLabel;
    private boolean probed;
    private boolean available;
    private boolean unavailableReported;
    /**
     * True when samples ride on the pass descriptor's
     * {@code sampleBufferAttachments[0]} (stage boundary) instead of
     * {@code sampleCounters} calls. This is the Apple-silicon path: the device
     * only supports {@code MTLCommonCounterSamplingPointStage} there.
     */
    private boolean stageBoundary;
    /** Sampling-point mask the device supports: {@link MetalPerformanceProbe#GPU_KIND_RENDER} / {@code _COMPUTE} bits. */
    private int supportedKinds;

    MetalGpuPassProfiler(final MemorySegment device) {
        this.device = device;
    }

    /**
     * Names the pass whose encoders follow. Called once per
     * {@code createRenderPass} / {@code createComputePass}, never per draw, so
     * it costs one reference assignment per pass.
     */
    void setPassLabel(final @Nullable String label) {
        this.passLabel = label;
    }

    /**
     * Starts the sample bookkeeping for a new command buffer and returns the
     * sample it owns (to be handed back to {@link #complete}); {@code null}
     * leaves this command buffer unsampled.
     */
    @Nullable
    Sample beginCommandBuffer() {
        if (!ensureAvailable()) {
            return null;
        }
        Sample sample = acquire();
        current = sample;
        if (sample != null) {
            sample.kind = 0;
            sample.ledger.reset();
        }
        return sample;
    }

    /**
     * Opens the render encoder of the next pass, attaching a stage-boundary
     * sample pair to its descriptor when that mode is active. In every other
     * case (draw-boundary fallback, exhausted sample cap, no borrowed sample)
     * the call is exactly the caller's old v2/v3 selection.
     *
     * <p>Only reachable while the profiler exists, i.e. while
     * {@code -Dmetallum.perf.perPassGpu} is on.</p>
     */
    MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MTLCommandBuffer commandBuffer,
            final MemorySegment[] colorAttachments,
            final MemorySegment depthAttachment,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final int clearDepthEnabled,
            final double clearDepthValue,
            final int @Nullable [] loadDontCare,
            final int @Nullable [] storeDontCare
    ) {
        Sample sample = current;
        if (stageBoundary && sample != null && sample.sampleBuffer != null) {
            int index = sample.ledger.reservePair(passLabel);
            if (index >= 0) {
                return commandBuffer.makeRenderCommandEncoderSampled(
                        colorAttachments, depthAttachment, viewportWidth, viewportHeight,
                        clearColorEnabled, clearColorValues, clearDepthEnabled, clearDepthValue,
                        loadDontCare, storeDontCare, sample.sampleBuffer, index, index + 1
                );
            }
        }
        return loadDontCare == null && storeDontCare == null
                ? commandBuffer.makeRenderCommandEncoderV2(
                        colorAttachments, depthAttachment, viewportWidth, viewportHeight,
                        clearColorEnabled, clearColorValues, clearDepthEnabled, clearDepthValue
                )
                : commandBuffer.makeRenderCommandEncoderV3(
                        colorAttachments, depthAttachment, viewportWidth, viewportHeight,
                        clearColorEnabled, clearColorValues, clearDepthEnabled, clearDepthValue,
                        loadDontCare, storeDontCare
                );
    }

    /**
     * Opens the compute encoder, attaching a stage-boundary sample pair to its
     * pass descriptor when that mode is active; otherwise exactly
     * {@link MTLCommandBuffer#makeComputeCommandEncoder()}.
     */
    MTLComputeCommandEncoder makeComputeCommandEncoder(final MTLCommandBuffer commandBuffer) {
        Sample sample = current;
        if (stageBoundary && sample != null && sample.sampleBuffer != null) {
            int index = sample.ledger.reservePair(passLabel);
            if (index >= 0) {
                return commandBuffer.makeComputeCommandEncoderSampled(sample.sampleBuffer, index, index + 1);
            }
        }
        return commandBuffer.makeComputeCommandEncoder();
    }

    /**
     * Inserts the start sample of one encoder.
     *
     * @param kind     {@link MetalPerformanceProbe#GPU_KIND_RENDER} or
     *                 {@link MetalPerformanceProbe#GPU_KIND_COMPUTE}
     * @param encoder  native encoder handle
     * @param label    explicit label, or {@code null} to use the current pass label
     */
    void beginSample(final int kind, final MemorySegment encoder, final @Nullable String label) {
        Sample sample = current;
        if (stageBoundary || sample == null || sample.kind != 0 || (supportedKinds & kind) == 0) {
            return;
        }
        int index = sample.ledger.start(label == null ? passLabel : label);
        if (index < 0) {
            return;
        }
        sample.kind = kind;
        sample(kind, encoder, sample, index);
    }

    /** Inserts the end sample of the encoder that is about to be ended. */
    void endSample(final int kind, final MemorySegment encoder) {
        Sample sample = current;
        if (sample == null || sample.kind != kind) {
            return;
        }
        sample.kind = 0;
        int index = sample.ledger.end();
        if (index >= 0) {
            sample(kind, encoder, sample, index);
        }
    }

    /**
     * Resolves the samples of one command buffer into the probe. {@code sample}
     * is the one {@link #beginCommandBuffer()} handed to that command buffer,
     * so it is called exactly once per borrowed sample, after the submit ring
     * observed the buffer completing. The sample returns to the pool here.
     */
    void complete(final @Nullable Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            if (available) {
                resolveInto(sample);
            }
        } catch (Throwable throwable) {
            disable("resolve");
        } finally {
            sample.ledger.reset();
            sample.kind = 0;
            sample.inUse = false;
        }
    }

    /** Releases the sample buffers with the device (buffers are never freed mid-frame). */
    void close() {
        for (int index = 0; index < pool.length; index++) {
            Sample sample = pool[index];
            if (sample != null && sample.sampleBuffer != null) {
                MetalNativeBridge.metallum_release_object(sample.sampleBuffer);
                sample.sampleBuffer = null;
            }
            pool[index] = null;
        }
        current = null;
        available = false;
    }

    private boolean ensureAvailable() {
        if (probed) {
            return available;
        }
        probed = true;
        if (!MetalNativeBridge.metallum_gpu_counters_available()) {
            disable("noSymbol");
            return false;
        }
        int mask = MetalNativeBridge.metallum_gpu_counters_supported(device);
        if (mask <= 0) {
            disable(MetalPerformanceProbe.gpuUnavailableReason(mask));
            return false;
        }
        boolean stage = (mask & MetalPerformanceProbe.GPU_MODE_STAGE_BOUNDARY) != 0;
        if (stage
                ? !MetalNativeBridge.metallum_gpu_stage_sampling_available()
                : !MetalNativeBridge.metallum_gpu_encoder_sampling_available()) {
            // The dylib exports the probe but not the entry points this mode
            // needs (a partially updated build): no sampling, no crash.
            disable("noSymbol");
            return false;
        }
        int kinds = MetalPerformanceProbe.gpuSupportedKinds(mask);
        if (kinds == 0) {
            disable("samplingPoint");
            return false;
        }
        stageBoundary = stage;
        supportedKinds = kinds;
        available = true;
        return true;
    }

    /** Takes a free sample, creating its timestamp buffer on first use. */
    private @Nullable Sample acquire() {
        for (int index = 0; index < pool.length; index++) {
            Sample sample = pool[index];
            if (sample == null) {
                sample = new Sample();
                pool[index] = sample;
            }
            if (sample.inUse) {
                continue;
            }
            if (sample.sampleBuffer == null) {
                MemorySegment handle = MetalNativeBridge.metallum_gpu_counters_create_buffer(
                        device, SAMPLE_CAPACITY
                );
                if (MetalNativeBridge.isNullHandle(handle)) {
                    disable("bufferCreate");
                    return null;
                }
                sample.sampleBuffer = handle;
            }
            sample.inUse = true;
            return sample;
        }
        // Every sample is still in flight: leave this command buffer unsampled
        // rather than reuse a timestamp buffer the GPU may still be writing.
        return null;
    }

    /** Latches the profiler off and reports why exactly once; buffers stay for {@link #close()}. */
    private void disable(final String reason) {
        available = false;
        supportedKinds = 0;
        current = null;
        if (unavailableReported) {
            return;
        }
        unavailableReported = true;
        MetalPerformanceProbe.recordGpuUnavailable(reason);
    }

    private void sample(final int kind, final MemorySegment encoder, final Sample sample, final int index) {
        if (sample.sampleBuffer == null) {
            return;
        }
        try {
            if (kind == MetalPerformanceProbe.GPU_KIND_RENDER) {
                MetalNativeBridge.metallum_MTLRenderCommandEncoder_gpu_sample(
                        encoder, sample.sampleBuffer, index
                );
            } else {
                MetalNativeBridge.metallum_MTLComputeCommandEncoder_gpu_sample(
                        encoder, sample.sampleBuffer, index
                );
            }
        } catch (Throwable throwable) {
            // A probe must never take the frame down; stop sampling from here.
            disable("sample");
        }
    }

    private void resolveInto(final Sample sample) {
        int samples = sample.ledger.sampleCount();
        int pairs = sample.ledger.pairCount();
        if (samples == 0 || pairs == 0 || sample.sampleBuffer == null) {
            return;
        }
        if (!resolve(sample.sampleBuffer, samples)) {
            return;
        }
        for (int pair = 0; pair < pairs; pair++) {
            long start = resolveScratch.get(LONG, (long) pair * 2L * Long.BYTES);
            long end = resolveScratch.get(LONG, ((long) pair * 2L + 1L) * Long.BYTES);
            // An index the GPU never sampled reads back as 0 / ~0; a pair that
            // is not strictly increasing is not a duration and must not be
            // reported as one.
            if (start <= 0L || end <= start) {
                continue;
            }
            MetalPerformanceProbe.recordGpuPass(
                    sample.ledger.labelAt(pair), (end - start) / 1_000_000.0
            );
        }
        if (sample.ledger.truncated()) {
            MetalPerformanceProbe.recordGpuPassTruncated();
        }
    }

    private boolean resolve(final MemorySegment sampleBuffer, final int samples) {
        return MetalNativeBridge.metallum_gpu_counters_resolve(sampleBuffer, samples, resolveScratch) == 0;
    }

    /**
     * One pooled {@code MTLCounterSampleBuffer} plus the label/index ledger of
     * the command buffer that currently owns it.
     */
    static final class Sample {
        final MetalPerformanceProbe.GpuSampleLedger ledger = new MetalPerformanceProbe.GpuSampleLedger();
        @Nullable
        MemorySegment sampleBuffer;
        /** Encoder kind whose start sample is open, {@code 0} when none. */
        int kind;
        boolean inUse;
    }
}
