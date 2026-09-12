package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Backend-neutral Iris program source frontend for the Metal world pipeline.
 *
 * <p>Iris remains the only authority for shader-pack preprocessing, program
 * fallback, directives and compatibility transforms. This class deliberately
 * stops before SPIR-V/MSL compilation so a Metal generation can build its
 * program graph without constructing Iris's OpenGL program/framebuffer graph.
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalProgramFrontend {
    private final ProgramFallbackResolver fallbackResolver;
    private final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap;

    public IrisMetalProgramFrontend(final ProgramSet programSet) {
        Objects.requireNonNull(programSet, "programSet");
        this.fallbackResolver = new ProgramFallbackResolver(programSet);
        this.textureMap = programSet.getPackDirectives().getTextureMap();
    }

    /** Resolves a gbuffer/shadow request with Iris's exact fallback chain. */
    public Optional<ResolvedProgram> resolve(final ProgramId requested) {
        Objects.requireNonNull(requested, "requested");
        return this.fallbackResolver.resolve(requested)
                .map(source -> new ResolvedProgram(requested, source));
    }

    public RasterProgram patchSodium(final ResolvedProgram resolved, final AlphaTest fallbackAlpha) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(fallbackAlpha, "fallbackAlpha");
        ProgramSource source = resolved.source();
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
        Map<PatchShaderType, String> patched = transformRaster(
                source,
                () -> TransformPatcher.patchSodium(
                        source.getName(),
                        source.getVertexSource().orElse(null),
                        source.getGeometrySource().orElse(null),
                        source.getTessControlSource().orElse(null),
                        source.getTessEvalSource().orElse(null),
                        source.getFragmentSource().orElse(null),
                        alpha,
                        this.textureMap,
                        false
                )
        );
        return new RasterProgram(resolved, patched, alpha, source.getDirectives());
    }

    public RasterProgram patchVanilla(
            final ResolvedProgram resolved,
            final AlphaTest fallbackAlpha,
            final boolean lines,
            final boolean clouds,
            final ShaderAttributeInputs inputs
    ) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(fallbackAlpha, "fallbackAlpha");
        Objects.requireNonNull(inputs, "inputs");
        ProgramSource source = resolved.source();
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(fallbackAlpha);
        Map<PatchShaderType, String> patched = transformRaster(
                source,
                () -> TransformPatcher.patchVanilla(
                        source.getName(),
                        source.getVertexSource().orElse(null),
                        source.getGeometrySource().orElse(null),
                        source.getTessControlSource().orElse(null),
                        source.getTessEvalSource().orElse(null),
                        source.getFragmentSource().orElse(null),
                        alpha,
                        lines,
                        clouds,
                        true,
                        inputs,
                        this.textureMap
                )
        );
        return new RasterProgram(resolved, patched, alpha, source.getDirectives());
    }

    public RasterProgram patchComposite(final ProgramSource source, final TextureStage stage) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stage, "stage");
        ResolvedProgram resolved = new ResolvedProgram(null, source);
        Map<PatchShaderType, String> patched = transformRaster(
                source,
                () -> TransformPatcher.patchComposite(
                        source.getName(),
                        source.getVertexSource().orElse(null),
                        source.getGeometrySource().orElse(null),
                        source.getFragmentSource().orElse(null),
                        stage,
                        this.textureMap
                )
        );
        AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(AlphaTest.ALWAYS);
        // TEMPORARY BISECTION (revert after one screenshot): strip the final
        // shader down to a plain colortex0 -> main copy, removing CAS,
        // vignette, film grain and color grading. If the radiating bands
        // disappear, they are produced by one of those final effects.
        // TEMPORARY BISECTION (iOS only, revert after one screenshot):
        // force composite7/composite8 to a flat 0.5 grey. final is already a
        // plain colortex0 passthrough in this build, so a striped grey screen
        // means the bands are added after composite8 (final/display), while a
        // flat grey screen means composite7/8 generate them.
        if (stage == TextureStage.COMPOSITE_AND_FINAL
                && ("composite7".equals(source.getName()) || "composite8".equals(source.getName()))
                && com.metallum.client.metal.render.bridge.MetalNativeBridge.isIOS()
                && Boolean.parseBoolean(System.getProperty(
                "metallum.experiment.greyComposite78", "true"))) {
            Map<PatchShaderType, String> forced = new EnumMap<>(patched);
            String fragment = forced.get(PatchShaderType.FRAGMENT);
            String replaced = fragment.replaceFirst(
                    "(?s)void main\\(\\) \\{.*\\}\\s*$",
                    "void main() {\n    Color = vec4(0.5, 0.5, 0.5, 1.0);\n    return;\n}\n"
            );
            if (replaced.equals(fragment)) {
                throw new ProgramFrontendException(
                        source.getName(), "grey-composite pattern not found", null
                );
            }
            forced.put(PatchShaderType.FRAGMENT, replaced);
            patched = Collections.unmodifiableMap(forced);
        }
        if (stage == TextureStage.COMPOSITE_AND_FINAL
                && "final".equals(source.getName())
                && com.metallum.client.metal.render.bridge.MetalNativeBridge.isIOS()
                && Boolean.parseBoolean(System.getProperty(
                "metallum.experiment.plainFinal", "true"))) {
            Map<PatchShaderType, String> forced = new EnumMap<>(patched);
            String fragment = forced.get(PatchShaderType.FRAGMENT);
            String replaced = fragment.replaceFirst(
                    "(?s)void main\\(\\) \\{.*\\}\\s*$",
                    "void main() {\n    Color = texture(colortex0, texcoord);\n}\n"
            );
            if (replaced.equals(fragment)) {
                throw new ProgramFrontendException(
                        source.getName(), "plain-final pattern not found", null
                );
            }
            forced.put(PatchShaderType.FRAGMENT, replaced);
            patched = Collections.unmodifiableMap(forced);
        }
        return new RasterProgram(resolved, patched, alpha, source.getDirectives());
    }

    public ComputeProgram patchCompute(final ComputeSource source, final TextureStage stage) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stage, "stage");
        String original = source.getSource().orElseThrow(
                () -> new ProgramFrontendException(source.getName(), "missing compute source", null)
        );
        try {
            return new ComputeProgram(
                    source,
                    stage,
                    TransformPatcher.patchCompute(source.getName(), original, stage, this.textureMap)
            );
        } catch (ProgramFrontendException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new ProgramFrontendException(source.getName(), "Iris compute transform failed", throwable);
        }
    }

    private static Map<PatchShaderType, String> transformRaster(
            final ProgramSource source,
            final RasterTransform transform
    ) {
        if (!source.isValid()) {
            throw new ProgramFrontendException(source.getName(), "program source is invalid", null);
        }
        try {
            Map<PatchShaderType, String> result = transform.apply();
            if (result == null
                    || result.get(PatchShaderType.VERTEX) == null
                    || result.get(PatchShaderType.FRAGMENT) == null) {
                throw new ProgramFrontendException(
                        source.getName(),
                        "Iris transform did not produce both vertex and fragment stages",
                        null
                );
            }
            EnumMap<PatchShaderType, String> copy = new EnumMap<>(PatchShaderType.class);
            result.forEach((stage, text) -> {
                if (text != null) {
                    copy.put(stage, text);
                }
            });
            return Collections.unmodifiableMap(copy);
        } catch (ProgramFrontendException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new ProgramFrontendException(source.getName(), "Iris raster transform failed", throwable);
        }
    }

    @FunctionalInterface
    private interface RasterTransform {
        Map<PatchShaderType, String> apply();
    }

    public record ResolvedProgram(ProgramId requested, ProgramSource source) {
        public ResolvedProgram {
            Objects.requireNonNull(source, "source");
        }

        public boolean usedFallback() {
            return requested != null && !requested.getSourceName().equals(source.getName());
        }
    }

    public record RasterProgram(
            ResolvedProgram resolution,
            Map<PatchShaderType, String> stages,
            AlphaTest alphaTest,
            ProgramDirectives directives
    ) {
        public RasterProgram {
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(stages, "stages");
            Objects.requireNonNull(alphaTest, "alphaTest");
            Objects.requireNonNull(directives, "directives");
        }

        public String name() {
            return resolution.source().getName();
        }

        public String vertexSource() {
            return stages.get(PatchShaderType.VERTEX);
        }

        public String fragmentSource() {
            return stages.get(PatchShaderType.FRAGMENT);
        }

        public int[] drawBuffers() {
            return directives.getDrawBuffers().clone();
        }

        public boolean requiresUnsupportedMetalStage() {
            return stages.get(PatchShaderType.GEOMETRY) != null
                    || stages.get(PatchShaderType.TESS_CONTROL) != null
                    || stages.get(PatchShaderType.TESS_EVAL) != null;
        }
    }

    public record ComputeProgram(ComputeSource source, TextureStage stage, String patchedSource) {
        public ComputeProgram {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(patchedSource, "patchedSource");
        }

        public String name() {
            return source.getName();
        }
    }

    public static final class ProgramFrontendException extends RuntimeException {
        private final String programName;

        ProgramFrontendException(final String programName, final String message, final Throwable cause) {
            super("Iris program '" + programName + "': " + message, cause);
            this.programName = programName;
        }

        public String programName() {
            return this.programName;
        }
    }
}
