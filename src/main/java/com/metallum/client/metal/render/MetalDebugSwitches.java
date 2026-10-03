package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime diagnostic switches (all default off; no behavior change when unset). */
public final class MetalDebugSwitches {
    public static final boolean SKIP_DEFERRED = Boolean.getBoolean("metallum.iris.debug.skipDeferred");
    public static final boolean SKIP_POST = Boolean.getBoolean("metallum.iris.debug.skipPost");
    public static final String VIEW = System.getProperty("metallum.iris.debug.view", "").trim();
    public static final String SKIP_PASS = System.getProperty("metallum.iris.debug.skipPass", "").trim();
    public static final boolean NO_SHADOW_MATRICES = Boolean.getBoolean("metallum.iris.debug.noShadowMatrices");
    public static final boolean ZERO_VL = Boolean.getBoolean("metallum.iris.debug.zeroVl");
    public static final boolean ZERO_BLOOM = Boolean.getBoolean("metallum.iris.debug.zeroBloom");
    public static final boolean NO_VANILLA_SKY = Boolean.getBoolean("metallum.iris.debug.noVanillaSky");
    public static final boolean NO_VANILLA_CLOUDS = Boolean.getBoolean("metallum.iris.debug.noVanillaClouds");
    public static final boolean NO_CLOUDS_HARD = Boolean.getBoolean("metallum.iris.debug.noCloudsHard");
    public static final boolean MAGENTA_CLEAR = Boolean.getBoolean("metallum.iris.debug.magentaClear");
    public static final List<StripEntry> STAGE_STRIP = parseStageStrip(
            System.getProperty("metallum.iris.debug.stageStrip", "").trim()
    );
    private static final Set<String> SKIP_PASS_NAMES = parsePassNames(SKIP_PASS);

    /** One {@code stageStrip} entry: a pass name plus the target index to tile. */
    public record StripEntry(String passName, int targetIndex) {
    }

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

    private static List<StripEntry> parseStageStrip(final String raw) {
        List<StripEntry> entries = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String name = trimmed;
            int target = 0;
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                name = trimmed.substring(0, colon).trim();
                try {
                    target = Integer.parseInt(trimmed.substring(colon + 1).trim());
                } catch (NumberFormatException malformed) {
                    continue; // Unparseable target index: drop the entry.
                }
            }
            if (!name.isEmpty() && target >= 0) {
                entries.add(new StripEntry(name, target));
            }
        }
        return List.copyOf(entries);
    }

    static {
        if (SKIP_DEFERRED || SKIP_POST || !VIEW.isEmpty() || !SKIP_PASS_NAMES.isEmpty()
                || NO_SHADOW_MATRICES || ZERO_VL || ZERO_BLOOM || !STAGE_STRIP.isEmpty()
                || NO_VANILLA_SKY || NO_VANILLA_CLOUDS || NO_CLOUDS_HARD || MAGENTA_CLEAR) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: skipDeferred={} skipPost={} view={} skipPass={} noShadowMatrices={} zeroVl={} zeroBloom={} stageStrip={} noVanillaSky={} noVanillaClouds={} noCloudsHard={} magentaClear={}",
                    SKIP_DEFERRED, SKIP_POST, VIEW, SKIP_PASS, NO_SHADOW_MATRICES, ZERO_VL, ZERO_BLOOM,
                    System.getProperty("metallum.iris.debug.stageStrip", ""),
                    NO_VANILLA_SKY, NO_VANILLA_CLOUDS, NO_CLOUDS_HARD, MAGENTA_CLEAR);
        }
    }
}
