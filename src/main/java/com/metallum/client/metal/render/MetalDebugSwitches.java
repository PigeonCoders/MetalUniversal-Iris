package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime diagnostic switches (all default off; no behavior change when unset). */
public final class MetalDebugSwitches {
    /** Identifies this probe build in device logs. */
    public static final String BUILD_TAG = "probe5";
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
    /**
     * Real terrain shadow-caster pass; on by default.
     * {@code -Dmetallum.iris.shadowPass=off} restores the old placeholder
     * behavior: casters are not rendered and the shadow maps are cleared to
     * "nothing occludes" before shadowcomp runs.
     */
    public static final boolean SHADOW_PASS = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.shadowPass", "on").trim()
    );
    /**
     * Debug kill switch: skips the shadow caster pass <em>and</em> the
     * shadowcomp stage, leaving the cleared shadow maps for the main pass to
     * sample (i.e. no shadows at all). {@code -Dmetallum.iris.debug.noShadows}.
     */
    public static final boolean NO_SHADOWS = Boolean.getBoolean("metallum.iris.debug.noShadows");
    /**
     * Shadow culling override: {@code advanced}, {@code box} or {@code none}.
     * Empty (default) follows the pack's {@code shadow.culling} directive.
     * {@code -Dmetallum.iris.debug.shadowCulling=advanced|box|none}.
     */
    public static final String SHADOW_CULLING = System.getProperty("metallum.iris.debug.shadowCulling", "").trim();
    /** Non-terrain world-program override; on by default, {@code -Dmetallum.iris.worldPass=off} disables it. */
    public static final boolean WORLD_PASS = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass", "on").trim()
    );
    /**
     * World-override depth attachment: {@code true} (default) makes taken-over
     * draws test/write the vanilla scene depth, so translucents (water) see
     * entity/hand depth; {@code false} restores the old Iris depthtex0
     * attachment. A/B diagnostic: {@code -Dmetallum.iris.worldPass.depthVanilla=false}.
     */
    public static final boolean WORLD_PASS_DEPTH_VANILLA = !"false".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass.depthVanilla", "true").trim()
    );
    /**
     * Materializes the per-draw core transforms ({@code iris_NormalMat},
     * {@code iris_ModelViewMatInverse}, {@code iris_ProjMatInverse},
     * {@code renderStage}) from the engine's {@code DynamicTransforms} /
     * {@code Projection} for every taken-over world draw. {@code false}
     * restores the old behavior (those members stay zeroed):
     * {@code -Dmetallum.iris.worldPass.perDrawNormals=false}.
     */
    public static final boolean WORLD_PASS_PER_DRAW_NORMALS = !"false".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass.perDrawNormals", "true").trim()
    );
    /** Logs the sampler keys of the first non-terrain world pass once per process. */
    public static final boolean LOG_SAMPLERS = Boolean.getBoolean("metallum.iris.worldPass.logSamplers");
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
                || NO_VANILLA_SKY || NO_VANILLA_CLOUDS || NO_CLOUDS_HARD || MAGENTA_CLEAR
                || !SHADOW_PASS || NO_SHADOWS || !SHADOW_CULLING.isEmpty()) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: build={} skipDeferred={} skipPost={} view={} skipPass={} noShadowMatrices={} zeroVl={} zeroBloom={} stageStrip={} noVanillaSky={} noVanillaClouds={} noCloudsHard={} magentaClear={} shadowPass={} noShadows={} shadowCulling={}",
                    BUILD_TAG, SKIP_DEFERRED, SKIP_POST, VIEW, SKIP_PASS, NO_SHADOW_MATRICES, ZERO_VL, ZERO_BLOOM,
                    System.getProperty("metallum.iris.debug.stageStrip", ""),
                    NO_VANILLA_SKY, NO_VANILLA_CLOUDS, NO_CLOUDS_HARD, MAGENTA_CLEAR,
                    SHADOW_PASS, NO_SHADOWS, SHADOW_CULLING);
        }
    }
}
