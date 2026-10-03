package com.metallum.client.metal.render;

import com.metallum.Metallum;

/** Runtime diagnostic switches (all default off; no behavior change when unset). */
public final class MetalDebugSwitches {
    public static final boolean SKIP_DEFERRED = Boolean.getBoolean("metallum.iris.debug.skipDeferred");
    public static final boolean SKIP_POST = Boolean.getBoolean("metallum.iris.debug.skipPost");
    public static final boolean NO_PACK_TERRAIN = Boolean.getBoolean("metallum.iris.debug.noPackTerrain");
    public static final String VIEW = System.getProperty("metallum.iris.debug.view", "").trim();

    private MetalDebugSwitches() {
    }

    static {
        if (SKIP_DEFERRED || SKIP_POST || NO_PACK_TERRAIN || !VIEW.isEmpty()) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: skipDeferred={} skipPost={} noPackTerrain={} view={}",
                    SKIP_DEFERRED, SKIP_POST, NO_PACK_TERRAIN, VIEW);
        }
    }
}
