package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final long resourceOptions;
    private final long allocationSize;
    @Nullable
    private MemorySegment nativeHandle;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size) {
        super(usage, size);
        this.device = device;

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        this.resourceOptions = toMtlResourceOptions(usage);
        if (size <= 0L) {
            throw new IllegalArgumentException("Metal buffer size must be > 0 (got " + size + ")");
        }
        long aligned = (size + 15L) & ~15L;
        if (aligned <= 0L) {
            throw new IllegalArgumentException("Metal buffer size overflow after alignment: " + size);
        }
        this.allocationSize = aligned;

        MemorySegment pooled = device.tryAcquirePooledBuffer(this.allocationSize, this.resourceOptions);
        if (!MetalNativeBridge.isNullHandle(pooled)) {
            this.nativeHandle = pooled;
            if (this.cpuAccessible) {
                MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(pooled);
                if (MetalNativeBridge.isNullHandle(contents)) {
                    MetalNativeBridge.metallum_release_object(pooled);
                    this.nativeHandle = null;
                    throw new IllegalStateException("MTLBuffer.contents returned null for pooled buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
                }
                this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
            }
            return;
        }

        long max = device.maxBufferAllocationSize();
        if (max > 0L && this.allocationSize > max) {
            throw new IllegalArgumentException("Metal buffer size " + this.allocationSize + " exceeds device max " + max);
        }
        this.nativeHandle = allocateWithOomRecovery(device, this.allocationSize, this.resourceOptions);
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException("Failed to create Metal buffer (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ", device=" + this.device.getClass().getSimpleName() + ") — driver returned null after emergency drain + GC. Process is likely at the iOS jetsam ceiling; reduce render distance / resource pack resolution.");
        }

        if (this.cpuAccessible) {
            MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(this.nativeHandle);
            if (MetalNativeBridge.isNullHandle(contents)) {
                MetalNativeBridge.metallum_release_object(this.nativeHandle);
                this.nativeHandle = null;
                throw new IllegalStateException("MTLBuffer.contents returned null (size=" + this.allocationSize + ", resourceOptions=" + this.resourceOptions + ")");
            }

            this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        }
    }

    /**
     * Allocates an {@code MTLBuffer} with a single OOM-recovery retry.
     *
     * <p>Background: on iOS the app's resident memory is hard-capped by
     * the OS (jetsam, ~3 GB even with the {@code increased-memory-limit}
     * entitlement). When Sodium's {@code GlBufferArena} requests a large
     * vertex/index buffer at the wrong moment, the Metal driver returns
     * {@code null} from {@code newBufferWithLength:options:}. The original
     * code surfaced this as {@code IllegalStateException("Failed to create
     * Metal buffer")}, which propagated up the render thread and — because
     * there is no Minecraft-level catch for it on iOS — the JVM process
     * was killed without writing a crash log, exactly matching the
     * reported "闪退无日志" symptom.
     *
     * <p>Recovery strategy (single-shot, on the render thread):
     * <ol>
     *   <li>Force-drain the per-device buffer pool. The pool can hold up
     *       to 256 MB of "free" {@code MTLBuffer}s that the OS still
     *       charges against the process; releasing them gives the driver
     *       back a large contiguous free region immediately.</li>
     *   <li>{@code System.gc()} + short sleep. Hints the JVM to finalise
     *       any {@code MemorySegment}-backed wrappers whose backing
     *       {@code MTLBuffer} has not yet been released via the cleaner.
     *       Only useful for buffers allocated outside this pool; the
     *       pool itself is already drained in step 1.</li>
     *   <li>Retry the allocation exactly once. If it still fails we
     *       return {@code null} and the caller throws a descriptive
     *       exception rather than NPE'ing its way to an uncatchable
     *       native crash.</li>
     * </ol>
     *
     * <p>The recovery is conservative on purpose: it does NOT loop, does
     * NOT block on GPU work (which could deadlock against the very submit
     * that triggered the allocation), and does NOT release buffers that
     * are still in-flight — only ones already in the pool.
     */
    private static MemorySegment allocateWithOomRecovery(
            final MetalDevice device,
            final long length,
            final long resourceOptions
    ) {
        MemorySegment handle = MetalNativeBridge.metallum_create_buffer(
                device.metalDeviceHandle(), length, resourceOptions);
        if (!MetalNativeBridge.isNullHandle(handle)) {
            return handle;
        }
        // First allocation failed. Almost always means we're at the iOS
        // jetsam ceiling or hit the per-buffer size limit. Try one
        // emergency recovery before giving up.
        Metallum.LOGGER.warn(
                "[metallum] MTLBuffer allocation failed (size={} bytes, options={}); "
                        + "attempting emergency pool drain + GC retry",
                length, resourceOptions);
        try {
            device.emergencyDrainBufferPool();
        } catch (Throwable t) {
            Metallum.LOGGER.warn("[metallum] emergency pool drain threw", t);
        }
        try {
            System.gc();
            Thread.sleep(5L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        MemorySegment retry = MetalNativeBridge.metallum_create_buffer(
                device.metalDeviceHandle(), length, resourceOptions);
        if (MetalNativeBridge.isNullHandle(retry)) {
            Metallum.LOGGER.error(
                    "[metallum] MTLBuffer allocation FAILED after retry (size={} bytes). "
                            + "Process is likely at the iOS jetsam ceiling; reduce render "
                            + "distance / resource pack resolution / loaded mods.",
                    length);
        }
        return retry;
    }

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, final @Nullable MemorySegment wrappedHandle) {
        super(usage, size);
        this.device = device;
        this.cpuAccessible = false;
        this.dynamic = false;
        this.resourceOptions = 0L;
        this.allocationSize = size;
        this.nativeHandle = wrappedHandle;
        this.storage = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
        duplicate.position(Math.toIntExact(offset));
        duplicate.limit(Math.toIntExact(offset + length));
        return duplicate.slice().order(this.storage.order());
    }

    /**
     * CPU view of a sub-range, or {@code null} when this buffer has no host
     * storage. Used by the world bridge to read the engine's
     * {@code DynamicTransforms}/{@code Projection} bytes for per-draw
     * materialization; those buffers are shared (usage 130/136) so the view is
     * valid without {@code USAGE_MAP_READ}.
     */
    @Nullable
    ByteBuffer sliceStorageOrNull(final long offset, final long length) {
        if (this.storage == null) {
            return null;
        }
        return sliceStorage(offset, length);
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null || this.nativeHandle.address() == 0L) {
            throw new IllegalStateException("Native Metal buffer is closed or null");
        }
        return this.nativeHandle;
    }

    boolean isDynamic() {
        return this.dynamic;
    }

    long allocationSize() {
        return this.allocationSize;
    }

    long resourceOptions() {
        return this.resourceOptions;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MemorySegment handle, final ByteBuffer storage) {
        this.nativeHandle = handle;
        this.storage = storage;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeHandle == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueBufferRelease(handle, this.allocationSize, this.resourceOptions);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

    private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
        MTLStorageMode storageMode = isCpuAccessible(usage) || isDynamic(usage) ? MTLStorageMode.Shared : MTLStorageMode.Private;
        return MTLResourceOptions.of(storageMode, MTLHazardTrackingMode.Untracked);
    }
}
