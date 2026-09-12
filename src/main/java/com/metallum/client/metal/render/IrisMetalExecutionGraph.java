package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generation-owned Iris execution graph.
 *
 * <p>The graph is deliberately independent of Iris's OpenGL renderer. Iris
 * owns source resolution and directives; this class owns the Metal plans,
 * pipeline states, target-side transitions and execution order for one world
 * generation. A failed required binding is an error, never an empty pass.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalExecutionGraph implements AutoCloseable {
    private static final Pattern COMPUTE_BINDING = Pattern.compile(
            "layout\\s*\\(([^)]*\\bbinding\\s*=\\s*(\\d+)[^)]*)\\)\\s*"
                    + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+|restrict\\s+)*"
                    + "(uniform|buffer)\\s+([A-Za-z_]\\w*)(?:\\s+([A-Za-z_]\\w*))?"
    );
    private static final Pattern COMPUTE_LOCAL_SIZE = Pattern.compile(
            "local_size_x\\s*=\\s*(\\d+).*?local_size_y\\s*=\\s*(\\d+).*?local_size_z\\s*=\\s*(\\d+)",
            Pattern.DOTALL
    );

    enum Stage {
        SETUP(ProgramArrayId.Setup, TextureStage.SETUP, null),
        BEGIN(ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre"),
        SHADOW_COMPOSITE(ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP, null),
        PREPARE(ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre"),
        DEFERRED(ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre"),
        COMPOSITE(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre"),
        FINAL(null, TextureStage.COMPOSITE_AND_FINAL, null);

        private final @Nullable ProgramArrayId arrayId;
        private final TextureStage textureStage;
        private final @Nullable String preFlipDirective;

        Stage(
                final @Nullable ProgramArrayId arrayId,
                final TextureStage textureStage,
                final @Nullable String preFlipDirective
        ) {
            this.arrayId = arrayId;
            this.textureStage = textureStage;
            this.preFlipDirective = preFlipDirective;
        }
    }

    record FlipTransition(BitSet readsFromAlt, BitSet stateAfter) {
        FlipTransition {
            readsFromAlt = (BitSet) readsFromAlt.clone();
            stateAfter = (BitSet) stateAfter.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return (BitSet) readsFromAlt.clone();
        }

        @Override
        public BitSet stateAfter() {
            return (BitSet) stateAfter.clone();
        }
    }

    private record RasterPlan(
            Stage stage,
            int index,
            String name,
            IrisMetalGlslLinker.LinkedRasterProgram program,
            int[] drawBuffers,
            BitSet readsFromAlt,
            BitSet stateAfter,
            String uniformToken
    ) {
        RasterPlan {
            drawBuffers = drawBuffers.clone();
            readsFromAlt = (BitSet) readsFromAlt.clone();
            stateAfter = (BitSet) stateAfter.clone();
        }
    }

    private record ComputeBinding(int binding, String declarationKind, String type, String name) {
        boolean image() {
            return type.contains("image");
        }

        boolean sampler() {
            return type.contains("sampler") || type.contains("texture");
        }

        boolean buffer() {
            return declarationKind.equals("buffer") || (!image() && !sampler());
        }
    }

    private record ComputePlan(
            Stage stage,
            int index,
            ComputeSource source,
            IrisMetalProgramFrontend.ComputeProgram program,
            List<ComputeBinding> bindings,
            String token
    ) {
        ComputePlan {
            bindings = List.copyOf(bindings);
        }
    }

    private record OrderedOperation(
            @Nullable RasterPlan raster,
            @Nullable ComputePlan compute
    ) {
        OrderedOperation {
            if ((raster == null) == (compute == null)) {
                throw new IllegalArgumentException("An Iris graph operation must contain exactly one plan");
            }
        }

        int index() {
            return raster == null ? compute.index() : raster.index();
        }
    }

    private final int generation;
    private final ProgramSet programSet;
    private final IrisMetalWorldPrograms programs;
    private final int targetCount;
    private final EnumMap<Stage, List<RasterPlan>> rasterPlans = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, List<ComputePlan>> computePlans = new EnumMap<>(Stage.class);
    private final List<RasterPlan> shadowRasterPlans = new ArrayList<>();
    private final EnumMap<Stage, List<OrderedOperation>> orderedOperations = new EnumMap<>(Stage.class);
    private final Map<RasterPlan, MetalCompiledRenderPipeline> rasterPipelines = new IdentityHashMap<>();
    private final Map<RasterPlan, MetalCompiledRenderPipeline> shadowRasterPipelines = new IdentityHashMap<>();
    private final Map<ComputePlan, MetalComputePipeline> computePipelines = new IdentityHashMap<>();
    private final List<ComputePlan> finalComputePlans = new ArrayList<>();
    private @Nullable RasterPlan finalPlan;
    private @Nullable MetalCompiledRenderPipeline finalPipeline;
    private @Nullable IrisMetalCenterDepthSampler centerDepthSampler;
    private BitSet state = new BitSet();
    private BitSet shadowState = new BitSet();
    private boolean prepared;
    private boolean closed;

    IrisMetalExecutionGraph(
            final int generation,
            final ProgramSet programSet,
            final IrisMetalWorldPrograms programs,
            final int targetCount
    ) {
        if (generation <= 0 || targetCount <= 0) {
            throw new IllegalArgumentException("Invalid Iris execution graph identity");
        }
        this.generation = generation;
        this.programSet = Objects.requireNonNull(programSet, "programSet");
        this.programs = Objects.requireNonNull(programs, "programs");
        if (programs.generation() != generation) {
            throw new IllegalArgumentException("Execution graph crossed program generation");
        }
        this.targetCount = targetCount;
        for (Stage stage : Stage.values()) {
            rasterPlans.put(stage, new ArrayList<>());
            computePlans.put(stage, new ArrayList<>());
            orderedOperations.put(stage, new ArrayList<>());
        }
        plan();
    }

    private void plan() {
        for (ComputeSource source : programSet.getSetup()) {
            if (source != null && source.isValid()) {
                computePlans.get(Stage.SETUP).add(planCompute(Stage.SETUP, -1, source));
            }
        }

        BitSet current = new BitSet(targetCount);
        for (Stage stage : new Stage[]{
                Stage.BEGIN, Stage.PREPARE, Stage.DEFERRED, Stage.COMPOSITE
        }) {
            if (stage.preFlipDirective != null) {
                applyPreFlips(current, programSet.getPackDirectives()
                        .getExplicitFlips(stage.preFlipDirective), targetCount);
            }
            ProgramSource[] sources = programSet.getComposite(stage.arrayId);
            ComputeSource[][] computes = programSet.getCompute(stage.arrayId);
            int count = Math.max(sources.length, computes.length);
            for (int index = 0; index < count; index++) {
                if (index < computes.length && computes[index] != null) {
                    for (ComputeSource compute : computes[index]) {
                        if (compute != null && compute.isValid()) {
                            computePlans.get(stage).add(planCompute(stage, index, compute));
                        }
                    }
                }
                if (index >= sources.length) {
                    continue;
                }
                ProgramSource source = sources[index];
                if (source == null || !source.isValid()) {
                    continue;
                }
                int[] drawBuffers = validateDrawBuffers(
                        source.getName(), source.getDirectives().getDrawBuffers()
                );
                FlipTransition transition = transition(
                        current,
                        drawBuffers,
                        source.getDirectives().getExplicitFlips(),
                        targetCount
                );
                current = transition.stateAfter();
                String token = token(stage, index, source.getName());
                IrisMetalGlslLinker.LinkedRasterProgram linked = programs.composite(
                        source, stage.textureStage
                );
                rasterPlans.get(stage).add(new RasterPlan(
                        stage, index, source.getName(), linked, drawBuffers,
                        transition.readsFromAlt(), transition.stateAfter(), token
                ));
            }
        }
        ComputeSource[] shadowComputes = programSet.getShadowCompute();
        for (int index = 0; index < shadowComputes.length; index++) {
            ComputeSource source = shadowComputes[index];
            if (source != null && source.isValid()) {
                computePlans.get(Stage.SHADOW_COMPOSITE).add(
                        planCompute(Stage.SHADOW_COMPOSITE, index, source)
                );
            }
        }
        BitSet shadowCurrent = new BitSet();
        BitSet shadowHistory = new BitSet();
        ProgramSource[] shadowSources = programSet.getComposite(ProgramArrayId.ShadowComposite);
        for (int index = 0; index < shadowSources.length; index++) {
            ProgramSource source = shadowSources[index];
            if (source == null || !source.isValid()) {
                continue;
            }
            int[] drawBuffers = validateDrawBuffers(
                    source.getName(), source.getDirectives().getDrawBuffers(), shadowTargetCount()
            );
            FlipTransition transition = transition(
                    shadowCurrent, drawBuffers, source.getDirectives().getExplicitFlips(), shadowTargetCount()
            );
            shadowCurrent = transition.stateAfter();
            String token = token(Stage.SHADOW_COMPOSITE, index, source.getName());
            shadowRasterPlans.add(new RasterPlan(
                    Stage.SHADOW_COMPOSITE,
                    index,
                    source.getName(),
                    programs.composite(source, TextureStage.SHADOWCOMP),
                    drawBuffers,
                    transition.readsFromAlt(),
                    transition.stateAfter(),
                    token
            ));
            shadowHistory.or(transition.stateAfter());
        }
        shadowState = new BitSet();
        state = new BitSet();

        Optional<ProgramSource> finalSource = programSet.get(ProgramId.Final);
        if (finalSource.isPresent() && finalSource.get().isValid()) {
            ProgramSource source = finalSource.get();
            int[] drawBuffers = validateDrawBuffers(source.getName(), source.getDirectives().getDrawBuffers());
            IrisMetalGlslLinker.LinkedRasterProgram linked = programs.finalProgram();
            if (linked == null) {
                throw new IllegalStateException("Final program resolution disappeared during graph planning");
            }
            finalPlan = new RasterPlan(
                    Stage.COMPOSITE, -1, source.getName(), linked, drawBuffers,
                    current, current, token(Stage.COMPOSITE, -1, source.getName())
            );
        }

        for (ComputeSource source : programSet.getFinalCompute()) {
            if (source != null && source.isValid()) {
                finalComputePlans.add(planCompute(Stage.FINAL, -1, source));
            }
        }
        rebuildOrderedOperations();
    }

    private void rebuildOrderedOperations() {
        for (Stage stage : Stage.values()) {
            List<OrderedOperation> operations = orderedOperations.get(stage);
            operations.clear();
            if (stage == Stage.FINAL) {
                for (ComputePlan plan : finalComputePlans) {
                    operations.add(new OrderedOperation(null, plan));
                }
                continue;
            }

            List<ComputePlan> computes = computePlans.get(stage);
            List<RasterPlan> rasters = stage == Stage.SHADOW_COMPOSITE
                    ? shadowRasterPlans
                    : rasterPlans.get(stage);
            for (ComputePlan compute : computes) {
                operations.add(new OrderedOperation(null, compute));
            }
            for (RasterPlan raster : rasters) {
                operations.add(new OrderedOperation(raster, null));
            }
            operations.sort((left, right) -> {
                int byIndex = Integer.compare(left.index(), right.index());
                if (byIndex != 0) {
                    return byIndex;
                }
                // A compute declaration at a slot runs before that slot's
                // raster program, matching Iris's barrier/dispatch order.
                return left.compute() == null ? 1 : -1;
            });
        }
    }

    private ComputePlan planCompute(
            final Stage stage,
            final int index,
            final ComputeSource source
    ) {
        IrisMetalProgramFrontend.ComputeProgram patched = programs.compute(source, stage.textureStage);
        return new ComputePlan(
                stage, index, source, patched,
                reflectComputeBindings(patched.patchedSource(), source.getName()),
                token(stage, index, source.getName())
        );
    }

    void setCenterDepthSampler(final @Nullable IrisMetalCenterDepthSampler sampler) {
        ensureOpen();
        this.centerDepthSampler = sampler;
    }

    void prepare(
            final MetalDevice device,
            final IrisMetalWorldResources resources,
            final IrisMetalUniformValues uniformValues,
            final GpuFormat mainColorFormat
    ) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(uniformValues, "uniformValues");
        IrisMetalRenderTargets targets = resources.renderTargets();
        if (targets.colorTargets().targetCount() != targetCount) {
            throw new IllegalStateException("Execution graph target count changed within generation");
        }
        if (!prepared) {
            for (Stage stage : Stage.values()) {
                for (OrderedOperation operation : orderedOperations.get(stage)) {
                    if (operation.compute() != null) {
                        computePipelines.put(
                                operation.compute(), compileCompute(device, operation.compute())
                        );
                        continue;
                    }
                    RasterPlan plan = operation.raster();
                    uniformValues.register(plan.uniformToken(), plan.name(), plan.program());
                    MetalCompiledRenderPipeline pipeline = stage == Stage.SHADOW_COMPOSITE
                            ? compileShadowRaster(
                                    device, resources.shadowTargets(), plan.program(), plan.name()
                            )
                            : compileRaster(device, targets, plan.program(), plan.name(), null);
                    if (stage == Stage.SHADOW_COMPOSITE) {
                        shadowRasterPipelines.put(plan, pipeline);
                    } else {
                        rasterPipelines.put(plan, pipeline);
                    }
                }
            }
            if (finalPlan != null) {
                int[] finalBuffers = finalPlan.drawBuffers();
                if (finalBuffers.length != 1 || finalBuffers[0] != 0) {
                    throw new IllegalStateException(
                            "Iris final output must write exactly DRAWBUFFERS:0; got "
                                    + java.util.Arrays.toString(finalBuffers)
                    );
                }
                uniformValues.register(finalPlan.uniformToken(), finalPlan.name(), finalPlan.program());
                finalPipeline = compileRaster(
                        device, targets, finalPlan.program(), finalPlan.name(), mainColorFormat
                );
            }
            prepared = true;
        }
        if (finalPlan != null && mainColorFormat == null) {
            throw new IllegalArgumentException("Final Iris pass requires a main color format");
        }
    }

    void executeSetup(final IrisMetalWorldResources resources) {
        executeStage(Stage.SETUP, resources);
    }

    void executeBegin(final IrisMetalWorldResources resources) {
        executeStage(Stage.BEGIN, resources);
    }

    void executePrepare(final IrisMetalWorldResources resources) {
        executeStage(Stage.PREPARE, resources);
    }

    void executeDeferred(final IrisMetalWorldResources resources) {
        executeStage(Stage.DEFERRED, resources);
    }

    void executeComposite(final IrisMetalWorldResources resources) {
        executeStage(Stage.COMPOSITE, resources);
    }

    void executeShadowComposite(final IrisMetalWorldResources resources) {
        ensurePrepared();
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if ((!shadowRasterPlans.isEmpty() || !computePlans.get(Stage.SHADOW_COMPOSITE).isEmpty())
                && shadows == null) {
            throw new IllegalStateException(
                    "Iris generation " + generation + " has shadow passes but no shadow targets"
            );
        }
        currentResourcesForDispatch = resources.renderTargets();
        try {
            for (OrderedOperation operation : orderedOperations.get(Stage.SHADOW_COMPOSITE)) {
                if (operation.compute() != null) {
                    executeCompute(operation.compute(), resources, shadowState);
                    continue;
                }
                RasterPlan plan = operation.raster();
                if (shadows == null) {
                    throw new IllegalStateException("Shadow raster plan has no shadow targets");
                }
                shadows.publishFlipState(plan.readsFromAlt());
                shadowState = plan.readsFromAlt();
                executeShadowRaster(
                        plan, shadowRasterPipelines.get(plan), resources, shadows
                );
                shadows.publishFlipState(plan.stateAfter());
                shadowState = plan.stateAfter();
            }
            if (shadows != null) {
                shadows.generateDepthMipmaps(activeEncoder());
            }
        } finally {
            currentResourcesForDispatch = null;
        }
    }

    void captureNoTranslucentsDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureMainDepth(encoder, sceneDepth);
        targets.captureNoTranslucentsDepth(encoder, targets.mainDepthTexture());
    }

    void sampleCenterDepth(final GpuTextureView sceneDepth, final float frameTime) {
        if (centerDepthSampler != null) {
            centerDepthSampler.sample(sceneDepth, frameTime);
        }
    }

    void captureNoHandDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureMainDepth(encoder, sceneDepth);
        targets.captureNoHandDepth(encoder, targets.mainDepthTexture());
    }

    void captureFinalDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        resources.renderTargets().captureMainDepth(activeEncoder(), sceneDepth);
    }

    void executeFinal(
            final IrisMetalWorldResources resources,
            final GpuTextureView mainColor
    ) {
        ensurePrepared();
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalPingPongTargets colors = targets.colorTargets();
        colors.restore(state);
        executeStage(Stage.FINAL, resources);
        colors.restore(state);
        if (finalPlan == null) {
            activeEncoder().copyTextureToTexture(
                    colors.readTexture(0), mainColor.texture(), 0, 0, 0, 0, 0,
                    targets.width(), targets.height()
            );
        } else {
            executeRaster(finalPlan, finalPipeline, resources, mainColor);
        }
        targets.resetMipmaps();
        for (int target = state.nextSetBit(0); target >= 0; target = state.nextSetBit(target + 1)) {
            MetalGpuTexture source = colors.readTexture(target);
            MetalGpuTexture destination = colors.mainTexture(target);
            if (source != destination) {
                activeEncoder().copyTextureToTexture(
                        source, destination, 0, 0, 0, 0, 0,
                        targets.width(), targets.height()
                );
            }
        }
    }

    private void executeStage(final Stage stage, final IrisMetalWorldResources resources) {
        ensurePrepared();
        if (stage.preFlipDirective != null) {
            applyPreFlips(state, programSet.getPackDirectives().getExplicitFlips(stage.preFlipDirective), targetCount);
        }
        currentResourcesForDispatch = resources.renderTargets();
        try {
            for (OrderedOperation operation : orderedOperations.get(stage)) {
                if (operation.compute() != null) {
                    executeCompute(operation.compute(), resources, state);
                    continue;
                }
                RasterPlan plan = operation.raster();
                resources.renderTargets().colorTargets().restore(plan.readsFromAlt());
                executeRaster(plan, rasterPipelines.get(plan), resources, null);
                resources.renderTargets().colorTargets().restore(plan.stateAfter());
                state = plan.stateAfter();
            }
        } finally {
            currentResourcesForDispatch = null;
        }
    }

    private void executeCompute(
            final ComputePlan plan,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        MetalComputePipeline pipeline = computePipelines.get(plan);
        if (pipeline == null) {
            throw new IllegalStateException(
                    "Iris compute plan has no compiled pipeline: " + plan.source().getName()
            );
        }
        try (MetalComputePass pass = activeEncoder().createComputePass()) {
            pass.setPipeline(pipeline);
            bindCompute(pass, plan, resources, readsFromAlt);
            dispatchCompute(pass, plan);
        }
    }

    private void bindCompute(
            final MetalComputePass pass,
            final ComputePlan plan,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalComputeResources computeResources = resources.computeResources();
        for (ComputeBinding binding : plan.bindings()) {
            if (binding.buffer()) {
                if (computeResources == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " requires generation-owned SSBO binding " + binding.binding()
                                    + " but this resource set has no compute resources"
                    );
                }
                GpuBufferSlice slice = computeResources.storageBuffer(binding.binding());
                if (slice == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing SSBO binding " + binding.binding()
                    );
                }
                pass.bindBuffer(binding.binding(), (MetalGpuBuffer) slice.buffer(), slice.offset());
                continue;
            }
            if (binding.image()) {
                MetalGpuTextureView image = computeResources == null
                        ? null
                        : computeResources.storageImage(binding.name());
                if (image == null) {
                    MetalRenderPass.TextureViewAndSampler target = textureBinding(
                            binding.name(), plan.stage().textureStage, targets, resources, readsFromAlt
                    );
                    image = target == null ? null : (MetalGpuTextureView) target.textureView();
                }
                if (image == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing required storage image '" + binding.name() + "'"
                    );
                }
                pass.bindTextureView(binding.binding(), image);
            } else {
                MetalRenderPass.TextureViewAndSampler texture = computeResources == null
                        ? null
                        : computeResources.sampledImage(binding.name());
                if (texture == null) {
                    texture = textureBinding(
                            binding.name(), plan.stage().textureStage, targets, resources, readsFromAlt
                    );
                }
                if (texture == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing required sampled image '" + binding.name() + "'"
                    );
                }
                pass.bindTextureView(binding.binding(), (MetalGpuTextureView) texture.textureView());
                pass.bindSampler(binding.binding(), ((MetalGpuSampler) texture.sampler()).nativeHandle());
            }
        }
    }

    private void dispatchCompute(final MetalComputePass pass, final ComputePlan plan) {
        if (plan.source().getWorkGroups() != null) {
            org.joml.Vector3i groups = plan.source().getWorkGroups();
            pass.dispatchGroups(groups.x(), groups.y(), groups.z());
            return;
        }
        org.joml.Vector2f relative = plan.source().getWorkGroupRelative();
        float scaleX = relative == null ? 1.0F : relative.x();
        float scaleY = relative == null ? 1.0F : relative.y();
        IrisMetalRenderTargets targets = currentResourcesForDispatch;
        if (targets == null) {
            throw new IllegalStateException("Compute dispatch has no current target extent");
        }
        pass.dispatchThreadsCovering(
                Math.max(1, (int) Math.ceil(targets.width() * scaleX)),
                Math.max(1, (int) Math.ceil(targets.height() * scaleY)),
                1
        );
    }

    private @Nullable IrisMetalRenderTargets currentResourcesForDispatch;

    private void executeRaster(
            final RasterPlan plan,
            final @Nullable MetalCompiledRenderPipeline pipeline,
            final IrisMetalWorldResources resources,
            final @Nullable GpuTextureView overrideColor
    ) {
        if (pipeline == null) {
            throw new IllegalStateException("Iris raster plan has no compiled pipeline: " + plan.name());
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        currentResourcesForDispatch = targets;
        try {
            Set<Integer> readTargets = colorSamplerTargets(plan.program());
            for (int target : plan.program().program().directives().getMipmappedBuffers()) {
                targets.enableReadMipmaps(target);
                activeEncoder().generateMipmaps(targets.colorTargets().readTexture(target));
            }
            MetalCommandEncoder encoder = activeEncoder();
            IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor;
            if (overrideColor != null) {
                RenderPassDescriptor direct = RenderPassDescriptor.create(
                        () -> "Iris final: " + plan.name()
                ).withColorAttachment(overrideColor, Optional.empty())
                        .withRenderArea(new RenderPass.RenderArea(0, 0, targets.width(), targets.height()));
                descriptor = new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                        direct, new MetalGpuTextureView[0]
                );
            } else {
                descriptor = targets.createWriteDescriptor(
                        "Iris " + plan.stage().name().toLowerCase() + ": " + plan.name(),
                        plan.drawBuffers(), null, false, null,
                        readTargets.stream().mapToInt(Integer::intValue).toArray()
                );
            }
            try (descriptor) {
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
                pass.setCompiledPipeline(pipeline);
                bindRaster(pass, pipeline, plan, resources, targets);
                GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
                pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
                pass.setVertexBuffer(0, net.irisshaders.iris.pathways.FullScreenQuadRenderer.INSTANCE.getQuad().slice());
                pass.drawIndexed(6, 1, 0, 0, 0);
            } finally {
                encoder.submitRenderPass();
            }
        } finally {
            targets.resetMipmaps();
            currentResourcesForDispatch = null;
        }
    }

    private void bindRaster(
            final MetalRenderPass pass,
            final MetalCompiledRenderPipeline pipeline,
            final RasterPlan plan,
            final IrisMetalWorldResources resources,
            final IrisMetalRenderTargets targets
    ) {
        if (plan.program().uniformBlockNames().contains(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME)) {
            com.mojang.blaze3d.buffers.GpuBufferSlice slice = uniformSlice(plan.uniformToken());
            if (slice == null) {
                throw new IllegalStateException("Missing uniform block for Iris pass " + plan.name());
            }
            pass.setUniform(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME, slice);
        }
        for (String block : plan.program().uniformBlockNames()) {
            if (!IrisMetalGlslLinker.UNIFORM_BLOCK_NAME.equals(block)) {
                throw new IllegalStateException(
                        "Iris pass " + plan.name() + " requires unsupported uniform block " + block
                );
            }
        }
        IrisMetalComputeResources computeResources = resources.computeResources();
        for (MetalCompiledRenderPipeline.ResourceBinding binding : pipeline.resources()) {
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                // Only samplers that survived GLSL->SPIR-V->MSL compilation
                // need a binding; declarations guarded by optional features
                // (PBR/DH/Voxy) are not in the compiled pipeline and must not
                // fail the pass.
                MetalRenderPass.TextureViewAndSampler sampled = textureBinding(
                        binding.name(), plan.stage().textureStage, targets, resources, plan.readsFromAlt()
                );
                if (sampled == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " is missing required sampler '" + binding.name() + "'"
                    );
                }
                pass.bindTexture(binding.name(), sampled.textureView(), sampled.sampler());
            } else if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER) {
                if (computeResources == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " requires generation-owned SSBO resources"
                    );
                }
                int logicalBinding = MetalCrossShaderCompiler.storageBufferLogicalBinding(binding.name());
                GpuBufferSlice slice = computeResources.storageBuffer(logicalBinding);
                if (slice == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " is missing SSBO binding " + logicalBinding
                    );
                }
                pass.bindStorageBuffer(logicalBinding, slice);
            } else if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE) {
                GpuTextureView view = storageImageBinding(
                        binding.name(), plan.stage().textureStage, targets, resources, plan.readsFromAlt()
                );
                if (view == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " is missing storage image '" + binding.name() + "'"
                    );
                }
                pass.bindStorageImage(binding.name(), view);
            }
        }
    }

    private void executeShadowRaster(
            final RasterPlan plan,
            final MetalCompiledRenderPipeline pipeline,
            final IrisMetalWorldResources resources,
            final IrisMetalShadowTargets shadows
    ) {
        MetalCommandEncoder encoder = activeEncoder();
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                     shadows.createShadowCompositeDescriptor(
                             "Iris shadowcomp: " + plan.name(),
                             plan.drawBuffers(),
                             plan.readsFromAlt(),
                             0,
                             0,
                             shadows.resolution(),
                             shadows.resolution()
                     )) {
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            pass.setCompiledPipeline(pipeline);
            bindRaster(pass, pipeline, plan, resources, resources.renderTargets());
            GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
            pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
            pass.setVertexBuffer(0, net.irisshaders.iris.pathways.FullScreenQuadRenderer.INSTANCE.getQuad().slice());
            pass.drawIndexed(6, 1, 0, 0, 0);
        } finally {
            encoder.submitRenderPass();
        }
        shadows.generateColorMipmaps(encoder);
    }

    private com.mojang.blaze3d.buffers.GpuBufferSlice uniformSlice(final String token) {
        return uniformValues == null ? null : uniformValues.slice(token);
    }

    private @Nullable IrisMetalUniformValues uniformValues;

    void attachUniformValues(final IrisMetalUniformValues values) {
        ensureOpen();
        this.uniformValues = Objects.requireNonNull(values, "values");
    }

    BitSet shadowReadSnapshot() {
        ensureOpen();
        return (BitSet) shadowState.clone();
    }

    void beginFrame(final IrisMetalWorldResources resources, final Vector4fc fogColor) {
        ensureOpen();
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(fogColor, "fogColor");
        state.clear();
        shadowState.clear();
        resources.renderTargets().colorTargets().restore(state);
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows != null) {
            shadows.publishFlipState(shadowState);
            shadows.resetMipmaps();
        }
        resources.renderTargets().clearForFrame(activeEncoder(), fogColor);
        if (resources.computeResources() != null) {
            resources.computeResources().clearForFrame(activeEncoder());
        }
    }

    private MetalRenderPass.TextureViewAndSampler textureBinding(
            final String name,
            final TextureStage stage,
            final IrisMetalRenderTargets targets,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        MetalRenderPass.TextureViewAndSampler standard = null;
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (name.equals(IrisMetalCenterDepthSampler.SAMPLER_NAME)
                || name.equals("centerDepthSmooth")) {
            standard = centerDepthSampler == null ? null : centerDepthSampler.binding();
        } else if (name.equals("noisetex")) {
            standard = resources.noiseTexture().binding();
        } else if (name.equals("normals")) {
            standard = resources.pbrNormals();
        } else if (name.equals("specular")) {
            standard = resources.pbrSpecular();
        } else if (name.equals("vxDepthTexTrans") || name.equals("vxDepthTexOpaque")
                || name.equals("dhDepthTex0") || name.equals("dhDepthTex1")) {
            // Optional Voxy / Distant Horizons depth inputs. Their declarations
            // still reach the compiled pipeline; without those mods GL leaves
            // them at defaults, so bind a neutral 1x1 color texture.
            standard = resources.pbrSpecular();
        } else if (name.equals("gtexture") || name.equals("texture") || name.equals("tex")) {
            standard = vanillaBlockAtlas();
        } else if (name.equals("depthtex0")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.mainDepthView(), targets.depthSampler());
        } else if (name.equals("depthtex1")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.noTranslucentsDepthView(), targets.depthSampler());
        } else if (name.equals("depthtex2")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.noHandDepthView(), targets.depthSampler());
        } else if (shadows != null && (name.startsWith("shadowtex") || name.startsWith("shadowcolor"))) {
            int shadowDepth = name.startsWith("shadowtex1") ? 1 : name.startsWith("shadowtex") ? 0 : -1;
            if (shadowDepth >= 0) {
                boolean comparison = !name.endsWith("HW");
                standard = new MetalRenderPass.TextureViewAndSampler(
                        shadowDepth == 0 ? shadows.shadowDepthView() : shadows.shadowDepthNoTranslucentsView(),
                        shadows.depthSampler(shadowDepth, comparison)
                );
            } else {
                int shadowColor = name.equals("shadowcolor") ? 0 : parseSuffix(name, "shadowcolor");
                if (shadowColor >= 0 && shadowColor < shadowTargetCount()) {
                    standard = new MetalRenderPass.TextureViewAndSampler(
                            shadows.colorView(shadowColor, shadowState), shadows.colorSampler(shadowColor)
                    );
                }
            }
        } else {
            int color = parseSuffix(name, "colortex");
            if (color < 0) {
                color = legacyColorTarget(name);
            }
            if (color >= 0) {
                if (color >= targets.colorTargets().targetCount()) {
                    throw new IllegalStateException("Iris sampler target out of range: " + name);
                }
                standard = new MetalRenderPass.TextureViewAndSampler(
                        targets.colorTargets().readView(color, readsFromAlt), targets.colorSampler(color)
                );
            } else {
                int image = parseSuffix(name, "colorimg");
                if (image >= 0) {
                    if (image >= targets.colorTargets().targetCount()) {
                        throw new IllegalStateException("Iris image target out of range: " + name);
                    }
                    standard = new MetalRenderPass.TextureViewAndSampler(
                            targets.colorTargets().writeView(image, readsFromAlt), targets.colorSampler(image)
                    );
                }
            }
        }
        if (standard == null && resources.computeResources() != null) {
            standard = resources.computeResources().sampledImage(name);
        }
        MetalRenderPass.TextureViewAndSampler override = resources.customTextures()
                .resolve(stage, name);
        return override == null ? standard : override;
    }

    /** Borrows the vanilla block atlas for Iris's {@code gtexture} sampler alias. */
    private MetalRenderPass.@Nullable TextureViewAndSampler vanillaBlockAtlas() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        try {
            AbstractTexture atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            return new MetalRenderPass.TextureViewAndSampler(atlas.getTextureView(), atlas.getSampler());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private @Nullable GpuTextureView storageImageBinding(
            final String name,
            final TextureStage stage,
            final IrisMetalRenderTargets targets,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        IrisMetalComputeResources computeResources = resources.computeResources();
        if (computeResources != null) {
            MetalGpuTextureView custom = computeResources.storageImage(name);
            if (custom != null) {
                return custom;
            }
        }
        MetalRenderPass.TextureViewAndSampler standard = textureBinding(
                name, stage, targets, resources, readsFromAlt
        );
        return standard == null ? null : standard.textureView();
    }

    private MetalCompiledRenderPipeline compileRaster(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final String role,
            final @Nullable GpuFormat overrideFormat
    ) {
        int[] buffers = validateDrawBuffers(program.name(), program.program().drawBuffers());
        ColorTargetState[] colorTargets = new ColorTargetState[buffers.length];
        Map<String, GpuFormat> vertexFormats = new HashMap<>();
        DefaultVertexFormat.POSITION_TEX.getElements().forEach(element ->
                vertexFormats.put(element.name(), element.format())
        );
        for (int index = 0; index < buffers.length; index++) {
            colorTargets[index] = new ColorTargetState(
                    Optional.empty(),
                    overrideFormat == null ? targets.colorTargets().format(buffers[index]) : overrideFormat,
                    ColorTargetState.WRITE_ALL
            );
        }
        try {
            MetalCompiledRenderPipeline pipeline = MetalCrossShaderCompiler.compileShaderpack(
                    device,
                    "iris/gen" + generation + "/graph/" + role + "/" + program.name(),
                    program.vertexGlsl(), program.fragmentGlsl(), null,
                    vertexFormats, false, false,
                    com.mojang.blaze3d.platform.PolygonMode.FILL,
                    PrimitiveTopology.QUADS,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    (DepthStencilState) null,
                    colorTargets
            );
            if (!pipeline.isValid()) {
                pipeline.close();
                throw new IllegalStateException("Invalid Metal pipeline for Iris pass " + program.name());
            }
            return pipeline;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to compile Iris pass " + program.name(), failure);
        }
    }

    private MetalCompiledRenderPipeline compileShadowRaster(
            final MetalDevice device,
            final @Nullable IrisMetalShadowTargets shadows,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final String role
    ) {
        if (shadows == null) {
            throw new IllegalStateException("Missing generation-owned shadow targets for " + role);
        }
        int[] buffers = validateDrawBuffers(program.name(), program.program().drawBuffers(), shadowTargetCount());
        ColorTargetState[] colorTargets = new ColorTargetState[buffers.length];
        Map<String, GpuFormat> vertexFormats = new HashMap<>();
        DefaultVertexFormat.POSITION_TEX.getElements().forEach(element ->
                vertexFormats.put(element.name(), element.format())
        );
        for (int index = 0; index < buffers.length; index++) {
            colorTargets[index] = new ColorTargetState(
                    Optional.empty(), shadows.colorFormat(buffers[index]), ColorTargetState.WRITE_ALL
            );
        }
        try {
            return MetalCrossShaderCompiler.compileShaderpack(
                    device,
                    "iris/gen" + generation + "/shadowcomp/" + role + "/" + program.name(),
                    program.vertexGlsl(), program.fragmentGlsl(), null,
                    vertexFormats, false, false,
                    com.mojang.blaze3d.platform.PolygonMode.FILL,
                    PrimitiveTopology.QUADS,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    null,
                    colorTargets
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to compile Iris shadow composite " + program.name(), failure);
        }
    }

    private MetalComputePipeline compileCompute(final MetalDevice device, final ComputePlan plan) {
        try {
            return MetalComputePipeline.compileGlsl(
                    device,
                    "iris/gen" + generation + "/compute/" + plan.source().getName(),
                    plan.program().patchedSource()
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Failed to compile Iris compute " + plan.source().getName(), failure
            );
        }
    }

    private @Nullable MetalCompiledRenderPipeline pipelineFor(final RasterPlan plan) {
        return plan == finalPlan ? finalPipeline : rasterPipelines.get(plan);
    }

    private MetalComputePipeline computePipelineFor(final ComputePlan plan) {
        MetalComputePipeline pipeline = computePipelines.get(plan);
        if (pipeline != null) {
            return pipeline;
        }
        throw new IllegalStateException("Compute plan disappeared: " + plan.source().getName());
    }

    private static List<ComputeBinding> reflectComputeBindings(final String source, final String name) {
        Matcher matcher = COMPUTE_BINDING.matcher(source);
        List<ComputeBinding> result = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        while (matcher.find()) {
            int binding = Integer.parseInt(matcher.group(2));
            String declarationKind = matcher.group(3);
            String type = matcher.group(4);
            String variable = matcher.group(5);
            if (variable == null || variable.isBlank()) {
                variable = type;
            }
            if (!used.add(binding)) {
                throw new IllegalStateException("Iris compute " + name + " reuses binding " + binding);
            }
            result.add(new ComputeBinding(binding, declarationKind, type, variable));
        }
        Matcher local = COMPUTE_LOCAL_SIZE.matcher(source);
        if (!local.find()) {
            throw new IllegalStateException("Iris compute " + name + " has no literal local_size declaration");
        }
        return List.copyOf(result);
    }

    private static String token(final Stage stage, final int index, final String name) {
        return "iris:graph:" + stage.name() + ":" + index + ":" + name;
    }

    private Set<Integer> colorSamplerTargets(final IrisMetalGlslLinker.LinkedRasterProgram program) {
        Set<Integer> result = new LinkedHashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : program.samplers()) {
            int target = parseSuffix(sampler.name(), "colortex");
            if (target >= 0) {
                result.add(target);
            }
        }
        return result;
    }

    private static int parseSuffix(final String name, final String prefix) {
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Maps Iris's legacy gbuffer sampler aliases to their colortex indices.
     * Mirrors {@code PackRenderTargetDirectives.LEGACY_RENDER_TARGETS}.
     */
    static int legacyColorTarget(final String name) {
        return switch (name) {
            case "gcolor" -> 0;
            case "gdepth" -> 1;
            case "gnormal" -> 2;
            case "composite" -> 3;
            case "gaux1" -> 4;
            case "gaux2" -> 5;
            case "gaux3" -> 6;
            case "gaux4" -> 7;
            default -> -1;
        };
    }

    private int shadowTargetCount() {
        return programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
    }

    static int[] validateDrawBuffers(final String name, final int[] drawBuffers, final int targetCount) {
        if (drawBuffers == null || drawBuffers.length == 0) {
            throw new IllegalStateException("Iris pass " + name + " has no DRAWBUFFERS");
        }
        BitSet seen = new BitSet(targetCount);
        int[] copy = drawBuffers.clone();
        for (int target : copy) {
            if (target < 0 || target >= targetCount) {
                throw new IllegalStateException(
                        "Iris pass " + name + " DRAWBUFFERS target " + target + " is out of range"
                );
            }
            if (!seen.get(target)) {
                seen.set(target);
            } else {
                throw new IllegalStateException(
                        "Iris pass " + name + " repeats DRAWBUFFERS target " + target
                );
            }
        }
        return copy;
    }

    private int[] validateDrawBuffers(final String name, final int[] drawBuffers) {
        return validateDrawBuffers(name, drawBuffers, targetCount);
    }

    static FlipTransition transition(
            final BitSet before,
            final int[] drawBuffers,
            final Map<Integer, Boolean> explicitFlips,
            final int targetCount
    ) {
        BitSet reads = (BitSet) before.clone();
        BitSet after = (BitSet) before.clone();
        for (int target : drawBuffers) {
            if (target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("DRAWBUFFERS target out of range: " + target);
            }
            if (explicitFlips.get(target) != Boolean.FALSE) {
                after.flip(target);
            }
        }
        explicitFlips.forEach((target, shouldFlip) -> {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Explicit flip target out of range: " + target);
            }
            if (Boolean.TRUE.equals(shouldFlip)) {
                after.flip(target);
            }
        });
        return new FlipTransition(reads, after);
    }

    static void applyPreFlips(
            final BitSet state,
            final Map<Integer, Boolean> flips,
            final int targetCount
    ) {
        flips.forEach((target, shouldFlip) -> {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Pre-flip target out of range: " + target);
            }
            if (Boolean.TRUE.equals(shouldFlip)) {
                state.flip(target);
            }
        });
    }

    private MetalCommandEncoder activeEncoder() {
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris execution graph has no active Metal device");
        }
        return device.createCommandEncoder();
    }

    private void ensurePrepared() {
        ensureOpen();
        if (!prepared) {
            throw new IllegalStateException("Iris execution graph generation " + generation + " is not prepared");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris execution graph generation " + generation + " is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MetalComputePipeline pipeline : computePipelines.values()) {
            pipeline.close();
        }
        for (MetalCompiledRenderPipeline pipeline : rasterPipelines.values()) {
            pipeline.close();
        }
        for (MetalCompiledRenderPipeline pipeline : shadowRasterPipelines.values()) {
            pipeline.close();
        }
        computePipelines.clear();
        rasterPipelines.clear();
        shadowRasterPipelines.clear();
        finalPipeline = null;
        centerDepthSampler = null;
    }
}
