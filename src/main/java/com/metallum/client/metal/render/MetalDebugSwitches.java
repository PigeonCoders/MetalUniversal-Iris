package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime diagnostic switches (all default off; no behavior change when
 * unset). Enable-style flags accept a bare {@code -Dname} argument or
 * {@code -Dname=true}; the {@code =off}/{@code =false} kill switches keep
 * their own parsing.
 */
public final class MetalDebugSwitches {
    /** Identifies this probe build in device logs. */
    public static final String BUILD_TAG = "probe5";
    public static final boolean SKIP_DEFERRED = enabledFlag("metallum.iris.debug.skipDeferred");
    public static final boolean SKIP_POST = enabledFlag("metallum.iris.debug.skipPost");
    public static final String VIEW = System.getProperty("metallum.iris.debug.view", "").trim();
    /**
     * Debug-view brightness multiplier ({@code -Dmetallum.iris.debug.viewGain=<float>}),
     * baked as a literal into the sampled debug-view blit so an almost-black
     * target (MakeUp's {@code gaux3} exposure history) becomes readable. Only
     * finite positive values are accepted; default 1 (byte-identical shader).
     */
    public static final float VIEW_GAIN = parseViewGain(
            System.getProperty("metallum.iris.debug.viewGain", "1").trim()
    );
    public static final String SKIP_PASS = System.getProperty("metallum.iris.debug.skipPass", "").trim();
    public static final boolean NO_SHADOW_MATRICES = enabledFlag("metallum.iris.debug.noShadowMatrices");
    public static final boolean ZERO_VL = enabledFlag("metallum.iris.debug.zeroVl");
    public static final boolean ZERO_BLOOM = enabledFlag("metallum.iris.debug.zeroBloom");
    public static final boolean NO_VANILLA_SKY = enabledFlag("metallum.iris.debug.noVanillaSky");
    public static final boolean NO_VANILLA_CLOUDS = enabledFlag("metallum.iris.debug.noVanillaClouds");
    public static final boolean NO_CLOUDS_HARD = enabledFlag("metallum.iris.debug.noCloudsHard");
    public static final boolean MAGENTA_CLEAR = enabledFlag("metallum.iris.debug.magentaClear");
    /**
     * Probe-only: logs every per-drawbuffer {@code blend.*} override that
     * {@code IrisMetalCompiledPrograms.colorTargets} folds into the Metal PSO
     * blend state (once per program+slot+target), so the colortex&rarr;slot
     * mapping can be verified on device.
     * {@code -Dmetallum.iris.debug.blendOverrides}.
     */
    public static final boolean BLEND_OVERRIDES = enabledFlag("metallum.iris.debug.blendOverrides");
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
    public static final boolean NO_SHADOWS = enabledFlag("metallum.iris.debug.noShadows");
    /**
     * Shadow culling override: {@code advanced}, {@code box} or {@code none}.
     * Empty (default) follows the pack's {@code shadow.culling} directive.
     * {@code -Dmetallum.iris.debug.shadowCulling=advanced|box|none}.
     */
    public static final String SHADOW_CULLING = System.getProperty("metallum.iris.debug.shadowCulling", "").trim();
    /**
     * M6.1.1 GL-NDC depth emulation for shadow caster vertex shaders. BSL's
     * shadow VSH scales {@code gl_Position.z} and relies on GL's NDC
     * [-1,1]&rarr;depth [0,1] viewport transform, which Metal's clip space does
     * not perform; the fix wraps the shadow vertex main with that transform.
     * On by default; {@code -Dmetallum.iris.debug.shadowDepthFix=off} disables
     * the wrapper (and switches the caster matrices back to the zero-to-one
     * ortho), reproducing the pre-fix "shadow=0 everywhere" behavior.
     */
    public static final boolean SHADOW_DEPTH_FIX = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.debug.shadowDepthFix", "on").trim()
    );
    /**
     * Mellow waving-foliage depth fix. The main pass rasterizes in the engine's
     * zero-to-one projection space (Sodium's {@code u_ProjectionMatrix}), but
     * {@code gbufferProjection} is handed to packs in Iris's OpenGL [-1,1]
     * space. A pack vertex shader that rewrites {@code gl_Position} from
     * {@code gbufferProjection} (Mellow's WAVE_LEAVES branch) therefore stores
     * depth {@code 2d-1} instead of {@code d}, so waving leaves occlude blocks
     * out to about twice their own distance. The fix renames only the
     * vertex-stage identifier to a second block member fed by the engine-space
     * projection (the same engine-forward / pack-inverse split the port already
     * applies to {@code iris_ProjMat}/{@code iris_ProjMatInverse}), leaving
     * fragment-stage pack math in OpenGL space. On by default;
     * {@code -Dmetallum.iris.debug.vertexEngineProjection=off} restores the
     * previous shared-space behavior for A/B.
     */
    public static final boolean VERTEX_ENGINE_PROJECTION = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.debug.vertexEngineProjection", "on").trim()
    );
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
    /**
     * Per-target {@code size.buffer.*} resolution support; on by default.
     * {@code -Dmetallum.iris.sizeBuffer=off} forces every colortex target back
     * to the base (main render target) extent, reproducing the pre-support
     * uniform-size behavior without touching the pack parser.
     */
    public static final boolean SIZE_BUFFER = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.sizeBuffer", "on").trim()
    );
    /**
     * Frame-boundary history canonicalization: moves each target's previous
     * frame read side into its main texture before the per-frame flip reset,
     * so cross-frame readers (MakeUp's {@code gaux3} auto-exposure history,
     * TAA history) always see the last write even when the final pass was
     * skipped. {@code -Dmetallum.iris.frameHistory=off} restores the old
     * behavior (canonicalization only in {@code executeFinal}).
     */
    public static final boolean FRAME_HISTORY = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.frameHistory", "on").trim()
    );
    /** Logs the sampler keys of the first non-terrain world pass once per process. */
    public static final boolean LOG_SAMPLERS = enabledFlag("metallum.iris.worldPass.logSamplers");
    /**
     * H1 diagnosis: per compiled shaderpack program, dumps every SAMPLED_IMAGE
     * resource's per-stage compact indices together with the
     * {@code [[texture(N)]]}/{@code [[sampler(N)]]} indices parsed from the
     * emitted MSL, and warns (never throws) when a stage's expected index is
     * absent from that stage's MSL index set.
     * {@code -Dmetallum.iris.debug.dumpBindings}.
     */
    public static final boolean DUMP_BINDINGS = enabledFlag("metallum.iris.debug.dumpBindings");
    /**
     * H3 settling aid: once per second, logs the frame time inputs the Iris
     * uniform buffer is filled from (frameTime/frameTimeCounter/frameCounter/
     * viewWidth/viewHeight). {@code -Dmetallum.iris.debug.logUniforms}.
     */
    public static final boolean LOG_UNIFORMS = enabledFlag("metallum.iris.debug.logUniforms");
    /**
     * Flip/side receipts into {@link MetalProbeReport}: composite's colortex6
     * read/write side, final's colortex6/colortex1 read side, and the
     * end-of-frame canonicalization bits ({@code 6} missing there is the red
     * flag for MakeUp's dead exposure history). Content-deduplicated and capped
     * so it cannot flood the probe. Enabled by its own
     * {@code -Dmetallum.iris.debug.flipTrace} or automatically by any probe
     * session flag (view/dumpBindings/logUniforms) so the evidence rides along
     * with the existing diagnostics.
     */
    public static final boolean FLIP_TRACE = enabledFlag("metallum.iris.debug.flipTrace")
            || DUMP_BINDINGS || LOG_UNIFORMS || !VIEW.isEmpty();
    public static final List<StripEntry> STAGE_STRIP = parseStageStrip(
            System.getProperty("metallum.iris.debug.stageStrip", "").trim()
    );
    private static final Set<String> SKIP_PASS_NAMES = parsePassNames(SKIP_PASS);
    /**
     * True when at least one probe/debug switch is active. Gates probe output
     * such as {@link MetalProbeReport} lines (and mirrors the condition for the
     * startup switch log) so release runs stay inert.
     */
    public static final boolean PROBES_ACTIVE = SKIP_DEFERRED || SKIP_POST || !VIEW.isEmpty()
            || !SKIP_PASS_NAMES.isEmpty() || NO_SHADOW_MATRICES || ZERO_VL || ZERO_BLOOM
            || !STAGE_STRIP.isEmpty() || NO_VANILLA_SKY || NO_VANILLA_CLOUDS
            || NO_CLOUDS_HARD || MAGENTA_CLEAR || !SHADOW_PASS || NO_SHADOWS
            || !SHADOW_CULLING.isEmpty() || !SHADOW_DEPTH_FIX || BLEND_OVERRIDES
            || !SIZE_BUFFER || DUMP_BINDINGS || LOG_UNIFORMS || FLIP_TRACE
            || !VERTEX_ENGINE_PROJECTION;

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

    /**
     * Enabled-style switch: a bare {@code -Dname} (present with an empty value)
     * and an explicit {@code -Dname=true} are both on; unset, {@code =false}
     * and any other value are off. Use only for flags whose absence is the off
     * state; the {@code =off}/{@code =false} kill switches keep their own
     * parsers.
     */
    static boolean enabledFlag(final String name) {
        String value = System.getProperty(name);
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
    }

    /** Finite positive gain only; anything else (unset, NaN, 0, negative) stays 1. */
    private static float parseViewGain(final String raw) {
        try {
            float gain = Float.parseFloat(raw);
            return Float.isFinite(gain) && gain > 0.0f ? gain : 1.0f;
        } catch (NumberFormatException malformed) {
            return 1.0f;
        }
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
        if (PROBES_ACTIVE) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: build={} skipDeferred={} skipPost={} view={} viewGain={} skipPass={} noShadowMatrices={} zeroVl={} zeroBloom={} stageStrip={} noVanillaSky={} noVanillaClouds={} noCloudsHard={} magentaClear={} shadowPass={} noShadows={} shadowCulling={} shadowDepthFix={} blendOverrides={} sizeBuffer={} dumpBindings={} logUniforms={} flipTrace={}",
                    BUILD_TAG, SKIP_DEFERRED, SKIP_POST, VIEW, VIEW_GAIN, SKIP_PASS, NO_SHADOW_MATRICES, ZERO_VL, ZERO_BLOOM,
                    System.getProperty("metallum.iris.debug.stageStrip", ""),
                    NO_VANILLA_SKY, NO_VANILLA_CLOUDS, NO_CLOUDS_HARD, MAGENTA_CLEAR,
                    SHADOW_PASS, NO_SHADOWS, SHADOW_CULLING, SHADOW_DEPTH_FIX, BLEND_OVERRIDES, SIZE_BUFFER,
                    DUMP_BINDINGS, LOG_UNIFORMS, FLIP_TRACE);
        }
    }
}
