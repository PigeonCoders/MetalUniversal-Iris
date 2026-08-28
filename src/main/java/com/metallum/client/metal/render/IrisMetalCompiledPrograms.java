package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Metal render pipelines owned and retired by one Iris world generation. */
@Environment(EnvType.CLIENT)
final class IrisMetalCompiledPrograms implements AutoCloseable {
    private static final Field IRIS_BLEND_MODE = irisBlendModeField();

    private final MetalDevice device;
    private final int generation;
    private final IrisMetalWorldPrograms sources;
    private final GpuFormat[] targetFormats;
    private final Map<SodiumKey, MetalCompiledRenderPipeline> sodiumPipelines = new HashMap<>();
    private boolean closed;

    IrisMetalCompiledPrograms(
            final MetalDevice device,
            final int generation,
            final IrisMetalWorldPrograms sources,
            final GpuFormat[] targetFormats
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris generation must be positive: " + generation);
        }
        this.generation = generation;
        this.sources = Objects.requireNonNull(sources, "sources");
        if (sources.generation() != generation) {
            throw new IllegalArgumentException(
                    "Iris source generation " + sources.generation()
                            + " does not match compiled generation " + generation
            );
        }
        this.targetFormats = Objects.requireNonNull(targetFormats, "targetFormats").clone();
        if (this.targetFormats.length == 0) {
            throw new IllegalArgumentException("Iris generation has no render-target formats");
        }
        for (int index = 0; index < this.targetFormats.length; index++) {
            Objects.requireNonNull(this.targetFormats[index], "targetFormats[" + index + "]");
        }
    }

    int generation() {
        return this.generation;
    }

    boolean isOwnedBy(final MetalDevice expected) {
        return this.device == expected;
    }

    synchronized Optional<MetalCompiledRenderPipeline> sodium(
            final ProgramId requested,
            final AlphaTest fallbackAlpha,
            final RasterState state
    ) {
        ensureOpen();
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(fallbackAlpha, "fallbackAlpha");
        Objects.requireNonNull(state, "state");
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                this.sources.sodium(requested, fallbackAlpha);
        if (linked.isEmpty()) {
            return Optional.empty();
        }
        SodiumKey key = new SodiumKey(requested, fallbackAlpha, state);
        return Optional.of(this.sodiumPipelines.computeIfAbsent(
                key,
                ignored -> compile("sodium_" + requested.getSourceName(), linked.orElseThrow(), state)
        ));
    }

    synchronized int cachedPipelineCount() {
        return this.sodiumPipelines.size();
    }

    private MetalCompiledRenderPipeline compile(
            final String role,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final RasterState state
    ) {
        ColorTargetState[] colorTargets = colorTargets(program, state);
        Map<String, GpuFormat> vertexFormats = new LinkedHashMap<>();
        for (VertexFormat binding : state.vertexFormats()) {
            if (binding == null) {
                continue;
            }
            binding.getElements().forEach(element ->
                    vertexFormats.putIfAbsent(element.name(), element.format())
            );
        }

        MetalCompiledRenderPipeline compiled;
        try {
            compiled = MetalCrossShaderCompiler.compileShaderpack(
                    this.device,
                    "iris/gen" + this.generation + "/" + role + "/" + program.name(),
                    program.vertexGlsl(),
                    program.fragmentGlsl(),
                    null,
                    vertexFormats,
                    state.primitiveTopology() == PrimitiveTopology.POINTS,
                    state.cull(),
                    state.polygonMode(),
                    state.primitiveTopology(),
                    state.vertexFormats().toArray(VertexFormat[]::new),
                    state.depthStencilState(),
                    colorTargets
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to compile Iris Metal generation " + this.generation
                            + " program " + program.name(),
                    exception
            );
        }
        if (!compiled.isValid()) {
            compiled.close();
            throw new IllegalStateException(
                    "Iris Metal generation " + this.generation + " program " + program.name()
                            + " produced no valid Metal pipeline state"
            );
        }
        validateReflectedResources(program, compiled);
        return compiled;
    }

    private ColorTargetState[] colorTargets(
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final RasterState state
    ) {
        int[] drawBuffers = program.program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        if (drawBuffers.length > ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException(
                    "Iris program " + program.name() + " has " + drawBuffers.length
                            + " DRAWBUFFERS targets; Metal supports at most "
                            + ColorTargetState.MAX_COLOR_TARGETS
            );
        }

        Optional<BlendFunction> globalBlend = state.blendFunction();
        BlendModeOverride globalOverride = program.program().directives()
                .getBlendModeOverride()
                .orElseGet(() -> {
                    ProgramId requested = program.program().resolution().requested();
                    return requested == null ? null : requested.getBlendModeOverride();
                });
        if (globalOverride != null) {
            globalBlend = irisBlendFunction(globalOverride);
        }

        Set<Integer> written = new HashSet<>();
        ColorTargetState[] targets = new ColorTargetState[drawBuffers.length];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int logicalTarget = drawBuffers[slot];
            if (logicalTarget < 0 || logicalTarget >= this.targetFormats.length) {
                throw new IllegalArgumentException(
                        "Iris program " + program.name() + " writes colortex" + logicalTarget
                                + " but generation " + this.generation + " owns only 0.."
                                + (this.targetFormats.length - 1)
                );
            }
            if (!written.add(logicalTarget)) {
                throw new IllegalArgumentException(
                        "Iris program " + program.name()
                                + " repeats logical DRAWBUFFERS target " + logicalTarget
                );
            }

            Optional<BlendFunction> blend = globalBlend;
            for (var override : program.program().directives().getBufferBlendOverrides()) {
                if (override.index() == logicalTarget) {
                    blend = override.blendMode() == null
                            ? Optional.empty()
                            : Optional.of(irisBlendFunction(override.blendMode()));
                }
            }
            targets[slot] = new ColorTargetState(
                    blend,
                    this.targetFormats[logicalTarget],
                    state.writeMask()
            );
        }
        return targets;
    }

    private static void validateReflectedResources(
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final MetalCompiledRenderPipeline compiled
    ) {
        List<String> missing = new ArrayList<>();
        for (String block : program.uniformBlockNames()) {
            boolean pushConstantAlias = IrisMetalGlslLinker.SODIUM_PUSH_CONSTANT_BLOCK_NAME.equals(block)
                    && compiled.resource("push_constants") != null;
            if (compiled.resource(block) == null && !pushConstantAlias) {
                missing.add("uniform block " + block);
            }
        }
        Set<String> declaredSamplers = new HashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : program.samplers()) {
            declaredSamplers.add(sampler.name());
        }
        // Shaderpack headers declare feature-guarded samplers (PBR/DH/Voxy)
        // that never reach the compiled MSL. Validate in the opposite
        // direction: anything the Metal pipeline actually uses must have come
        // from the linked program's declarations.
        for (MetalCompiledRenderPipeline.ResourceBinding binding : compiled.resources()) {
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE
                    && !declaredSamplers.contains(binding.name())) {
                missing.add("undeclared sampler " + binding.name());
            }
        }
        if (!missing.isEmpty()) {
            compiled.close();
            throw new IllegalStateException(
                    "Iris program " + program.name()
                            + " lost reflected Metal resources: " + missing
            );
        }
    }

    private static Field irisBlendModeField() {
        try {
            Field field = BlendModeOverride.class.getDeclaredField("blendMode");
            if (!field.trySetAccessible()) {
                throw new IllegalStateException("Iris BlendModeOverride.blendMode is not accessible");
            }
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Iris blend ABI changed: expected BlendModeOverride.blendMode from Iris 1.11.2",
                    exception
            );
        }
    }

    private static Optional<BlendFunction> irisBlendFunction(final BlendModeOverride override) {
        try {
            BlendMode blendMode = (BlendMode) IRIS_BLEND_MODE.get(override);
            return blendMode == null
                    ? Optional.empty()
                    : Optional.of(irisBlendFunction(blendMode));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Iris blend override", exception);
        }
    }

    private static BlendFunction irisBlendFunction(final BlendMode blendMode) {
        return new BlendFunction(
                irisBlendFactor(blendMode.srcRgb()),
                irisBlendFactor(blendMode.dstRgb()),
                irisBlendFactor(blendMode.srcAlpha()),
                irisBlendFactor(blendMode.dstAlpha())
        );
    }

    private static BlendFactor irisBlendFactor(final int glId) {
        return Arrays.stream(BlendModeFunction.values())
                .filter(function -> function.getGlId() == glId)
                .findFirst()
                .map(function -> BlendFactor.valueOf(function.name()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported Iris blend factor GL id " + glId
                ));
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Iris Metal compiled-program generation " + this.generation + " is closed"
            );
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.waitForSubmittedGpuWork();
        this.sodiumPipelines.values().forEach(MetalCompiledRenderPipeline::close);
        this.sodiumPipelines.clear();
    }

    record RasterState(
            boolean cull,
            PolygonMode polygonMode,
            PrimitiveTopology primitiveTopology,
            List<VertexFormat> vertexFormats,
            @Nullable DepthStencilState depthStencilState,
            Optional<BlendFunction> blendFunction,
            int writeMask
    ) {
        RasterState {
            Objects.requireNonNull(polygonMode, "polygonMode");
            Objects.requireNonNull(primitiveTopology, "primitiveTopology");
            vertexFormats = List.copyOf(vertexFormats);
            blendFunction = Objects.requireNonNull(blendFunction, "blendFunction");
            if ((writeMask & ~ColorTargetState.WRITE_ALL) != 0) {
                throw new IllegalArgumentException("Invalid color write mask " + writeMask);
            }
        }

        static RasterState from(final RenderPipeline source, final VertexFormat primaryVertexFormat) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(primaryVertexFormat, "primaryVertexFormat");
            ColorTargetState sourceTarget = source.getColorTargetState();
            int sourceWriteMask = sourceTarget == null
                    ? ColorTargetState.WRITE_ALL
                    : sourceTarget.writeMask();
            // Sodium uses -1 as its all-components sentinel for a few
            // generated terrain pipelines; normalize it at the backend
            // contract boundary before validating the four Metal channels.
            if (sourceWriteMask == -1) {
                sourceWriteMask = ColorTargetState.WRITE_ALL;
            }
            return new RasterState(
                    source.isCull(),
                    source.getPolygonMode(),
                    source.getPrimitiveTopology(),
                    List.of(primaryVertexFormat),
                    source.getDepthStencilState(),
                    sourceTarget == null ? Optional.empty() : sourceTarget.blendFunction(),
                    sourceWriteMask
            );
        }
    }

    private record SodiumKey(ProgramId requested, AlphaTest fallbackAlpha, RasterState state) {
    }
}
