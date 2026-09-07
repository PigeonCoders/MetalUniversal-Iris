package com.metallum.client.metal.render;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-frame, on-device render-thread diagnostics for the sky/water/hand
 * bisection. Unlike the earlier one-shot logging (one line per hook per
 * pipeline generation), this sink stamps every line with the current world
 * frame and de-duplicates only within one frame, so pass ordering can finally
 * be read without cross-frame aliasing.
 *
 * <p>Zero rendering impact: it only appends to {@link MetallumDebugLog}.
 * Remove together with the debug logging commit.
 */
public final class IrisMetalFrameDiagnostics {
    private static volatile int frame;
    private static final Set<String> FRAME_KEYS = new HashSet<>();

    private IrisMetalFrameDiagnostics() {
    }

    public static void beginFrame(final String phase) {
        synchronized (FRAME_KEYS) {
            frame++;
            FRAME_KEYS.clear();
        }
        MetallumDebugLog.log("== frame " + frame + " begin phase=" + phase);
    }

    public static void endFrame(final String phase) {
        MetallumDebugLog.log("== frame " + frame + " finalize phase=" + phase);
    }

    public static void logOnce(final String key, final String message) {
        synchronized (FRAME_KEYS) {
            if (!FRAME_KEYS.add(key)) {
                return;
            }
        }
        MetallumDebugLog.log("frame " + frame + " " + message);
    }

    public static void pass(final String label, final int drawCount, final String attachments) {
        if (frame <= 0 || label == null) {
            return;
        }
        String normalized = label.toLowerCase();
        if (!normalized.contains("sky")
                && !normalized.contains("stars")
                && !normalized.contains("sunrise")
                && !normalized.contains("celestial")
                && !normalized.contains("terrain")
                && !normalized.contains("hand")
                && !normalized.contains("item")
                && !normalized.contains("outline")
                && !normalized.contains("panorama")) {
            return;
        }
        // Every submission, not once per frame: the three Sodium terrain
        // layers share the label "Terrain", so de-duplicating hid the solid /
        // cutout / translucent split entirely.
        MetallumDebugLog.log(
                "frame " + frame + " pass '" + label + "' draws=" + drawCount
                        + " " + attachments
        );
    }

    public static int frame() {
        return frame;
    }
}
