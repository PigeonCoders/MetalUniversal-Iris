package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.HashSet;
import java.util.Set;

/** Runtime diagnostic switches (all default off; no behavior change when unset). */
public final class MetalDebugSwitches {
    public static final boolean SKIP_DEFERRED = Boolean.getBoolean("metallum.iris.debug.skipDeferred");
    public static final boolean SKIP_POST = Boolean.getBoolean("metallum.iris.debug.skipPost");
    public static final String VIEW = System.getProperty("metallum.iris.debug.view", "").trim();
    public static final String SKIP_PASS = System.getProperty("metallum.iris.debug.skipPass", "").trim();
    public static final boolean NO_SHADOW_MATRICES = Boolean.getBoolean("metallum.iris.debug.noShadowMatrices");
    public static final boolean ZERO_VL = Boolean.getBoolean("metallum.iris.debug.zeroVl");
    private static final Set<String> SKIP_PASS_NAMES = parsePassNames(SKIP_PASS);

    private MetalDebugSwitches() {
    }

    /** Exact plan-name match against the comma-separated {@code skipPass} list. */
    static boolean shouldSkipPass(final String planName) {
        return planName != null && SKIP_PASS_NAMES.contains(planName);
    }

    private static Set<String> parsePassNames(final String raw) {
        Set<String> names = new HashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return Set.copyOf(names);
    }

    static {
        if (SKIP_DEFERRED || SKIP_POST || !VIEW.isEmpty() || !SKIP_PASS_NAMES.isEmpty()
                || NO_SHADOW_MATRICES || ZERO_VL) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: skipDeferred={} skipPost={} view={} skipPass={} noShadowMatrices={} zeroVl={}",
                    SKIP_DEFERRED, SKIP_POST, VIEW, SKIP_PASS, NO_SHADOW_MATRICES, ZERO_VL);
        }
    }
}
