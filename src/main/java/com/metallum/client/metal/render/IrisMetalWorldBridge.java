package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Non-terrain world-program wiring: routes entity / block-entity /
 * moving-block (M1) and first-person hand / held item / glint (M2) draws
 * through the shaderpack's {@code gbuffers_*} programs into the Iris gbuffer,
 * so the final composite no longer erases them.
 *
 * <p>Three entry points cooperate like the terrain bridge:
 * {@link #armForDraw} (from {@code PreparedRenderType.drawFromBuffer} HEAD)
 * resolves the draw's {@link RenderPipeline} to a whitelisted shader key and
 * stashes it as the pending key, {@link #rewriteWorldDescriptor} (from
 * {@code MetalCommandEncoder.createRenderPass}) consumes that key and swaps
 * the vanilla pass attachments for the Iris gbuffer, and
 * {@link #installPipeline} (from {@code IrisRenderPassMixin.setPipeline}
 * HEAD) installs the generation-owned compiled shaderpack pipeline for the
 * draw. Whichever entry point misses, the pass stays vanilla. Fully inert
 * unless the world pass override is enabled (on by default;
 * {@code -Dmetallum.iris.worldPass=off} disables it).</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalWorldBridge {
    /**
     * World-override whitelist: shader keys whose draws are taken over;
     * everything else stays vanilla. Covers M1 (entities, block entities,
     * moving blocks) plus the reachable M2 hand/held-item keys produced by the
     * {@link MetalIrisPipelines} selectors ({@code HandRenderer}-active
     * branches) and the constant glint mapping. The fullbright HAND_*_BRIGHT
     * and HAND_TEXT_INTENSITY variants are deliberately absent: no selector
     * can produce them (upstream {@code IrisPipelines} never returns them
     * either).
     */
    static final Set<ShaderKey> WORLD_OVERRIDE_KEYS = Set.of(
            ShaderKey.ENTITIES_SOLID,
            ShaderKey.ENTITIES_CUTOUT,
            ShaderKey.ENTITIES_CUTOUT_DIFFUSE,
            ShaderKey.ENTITIES_TRANSLUCENT,
            ShaderKey.ENTITIES_EYES,
            ShaderKey.ENTITIES_EYES_TRANS,
            ShaderKey.BLOCK_ENTITY,
            ShaderKey.BLOCK_ENTITY_BRIGHT,
            ShaderKey.BLOCK_ENTITY_DIFFUSE,
            ShaderKey.BE_TRANSLUCENT,
            ShaderKey.MOVING_BLOCK,
            ShaderKey.HAND_CUTOUT,
            ShaderKey.HAND_CUTOUT_DIFFUSE,
            ShaderKey.HAND_TRANSLUCENT,
            ShaderKey.HAND_WATER_DIFFUSE,
            ShaderKey.HAND_TEXT,
            ShaderKey.HAND_TEXT_TRANSLUCENT,
            ShaderKey.GLINT
    );

    private static final ThreadLocal<WorldContext> ACTIVE_WORLD_PASS = new ThreadLocal<>();
    /**
     * Draw-time arming signal set by {@link #armForDraw} at
     * {@code PreparedRenderType.drawFromBuffer} HEAD and consumed by
     * {@link #rewriteWorldDescriptor} when that same draw creates its render
     * pass. 26.2 dispatches entity / block-entity draws by pipeline identity,
     * so this replaces the dead phase-based gate.
     */
    private static final ThreadLocal<ShaderKey> PENDING_WORLD_KEY = new ThreadLocal<>();
    private static final Set<String> REPORTED_INSTALLS = new HashSet<>();
    private static final Set<String> REPORTED_SKIPS = new HashSet<>();
    private static final Set<String> REPORTED_PIPELINE_SKIPS = new HashSet<>();
    private static final Set<String> REPORTED_INPUT_AUDITS = new HashSet<>();
    private static boolean drawVertexBuffersReported;
    private static boolean mainDepthCapturedThisFrame;
    private static boolean samplersReported;

    private IrisMetalWorldBridge() {
    }

    /** Resets the per-frame lazy main-depth capture; called from {@code beginLevelRendering}. */
    static void beginFrame() {
        ACTIVE_WORLD_PASS.remove();
        PENDING_WORLD_KEY.remove();
        mainDepthCapturedThisFrame = false;
    }

    /** Clears the active world pass; called from {@code MetalIrisClearMixin} at pass submit. */
    public static void endPass() {
        ACTIVE_WORLD_PASS.remove();
        PENDING_WORLD_KEY.remove();
    }

    /**
     * Arms the world override for the draw that is about to be encoded,
     * called from {@code PreparedRenderType.drawFromBuffer} HEAD. Returns
     * {@code false} (and clears any stale pending key) whenever the draw must
     * stay vanilla: world pass override disabled, no Metal world pipeline,
     * shadow pass, or the source pipeline does not map to a whitelisted
     * shader key. On success the resolved key is stashed in
     * {@link #PENDING_WORLD_KEY} and consumed by
     * {@link #rewriteWorldDescriptor} for this same draw.
     */
    public static boolean armForDraw(final RenderPipeline source) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return false;
        }
        PENDING_WORLD_KEY.remove();
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            return false;
        }
        ShaderKey key = MetalIrisPipelines.getShaderKeyForPipeline(pipeline, source);
        if (key == null || !WORLD_OVERRIDE_KEYS.contains(key)) {
            recordPipelineSkip(source, key, "not-whitelisted");
            return false;
        }
        PENDING_WORLD_KEY.set(key);
        return true;
    }

    /**
     * Rewrites the descriptor of a vanilla world pass into an Iris gbuffer
     * write descriptor when the draw that is creating the pass was armed by
     * {@link #armForDraw} with a whitelisted shader key. The pending key
     * is consumed here (and nowhere else), so a draw that was not armed, or
     * whose re-creation races a pass boundary, can never take over an
     * unrelated pass. The first rewritten pass of each frame also lazily
     * copies the vanilla main depth (which by then contains the terrain) into
     * the Iris depthtex0 texture.
     *
     * <p>By default the rewritten pass keeps the <em>vanilla</em> depth
     * attachment: taken-over draws must test/write the same depth buffer the
     * terrain bridge uses, or translucent terrain (water) passes would never
     * see entity/hand depth and would draw straight through them.
     * {@code -Dmetallum.iris.worldPass.depthVanilla=false} restores the old
     * Iris-depthtex0 attachment for A/B comparison.</p>
     */
    public static RenderPassDescriptor rewriteWorldDescriptor(
            final MetalDevice device,
            final RenderPassDescriptor descriptor
    ) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return descriptor;
        }
        // Consume the draw's arming signal before any further gate, so a skip
        // can never leave the key pending for the next, unrelated pass.
        ShaderKey key = consumePendingKey();
        if (key == null) {
            return descriptor;
        }
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null || !pipeline.shouldOverrideCoreShaders(true)) {
            return descriptor;
        }
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            return descriptor;
        }
        ProgramRequest request = shaderKeyToProgramRequest(key);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().vanilla(
                        request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
                );
        if (linked.isEmpty()) {
            recordSkip(key, "no-program");
            return descriptor;
        }
        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
        int[] drawBuffers = program.program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        IrisMetalRenderTargets renderTargets = pipeline.resources().renderTargets();
        if (!mainDepthCapturedThisFrame) {
            mainDepthCapturedThisFrame = true;
            RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
            if (depthAttachment != null) {
                GpuTextureView vanillaDepth = depthAttachment.textureView();
                if (vanillaDepth != null) {
                    renderTargets.captureMainDepth(
                            device.createCommandEncoder(), vanillaDepth.texture()
                    );
                }
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> vanillaDepth =
                MetalDebugSwitches.WORLD_PASS_DEPTH_VANILLA ? descriptor.depthAttachment() : null;
        GpuTextureView depthView = vanillaDepth == null ? null : vanillaDepth.textureView();
        OptionalDouble depthClear = vanillaDepth == null || vanillaDepth.clearValue() == null
                ? OptionalDouble.empty()
                : vanillaDepth.clearValue();
        ACTIVE_WORLD_PASS.set(new WorldContext(pipeline, key));
        recordInstall(key, program.name(), drawBuffers, "draw");
        return renderTargets.createWorldWriteDescriptor(
                descriptor.label().get(), drawBuffers, descriptor.renderArea, depthView, depthClear
        );
    }

    /**
     * Installs the generation-owned shaderpack pipeline for a non-terrain
     * world draw, bypassing RenderPass's vanilla format/count validation the
     * same way {@link IrisMetalTerrainBridge#installPipeline} does for Sodium.
     * Returns {@code false} (leaving the pass vanilla) unless a rewritten
     * world pass is active and the mapped key is on the world-override whitelist.
     */
    public static boolean installPipeline(
            final RenderPassBackend backend,
            final RenderPipeline source
    ) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return false;
        }
        if (!(backend instanceof MetalRenderPass metalPass)) {
            return false;
        }
        WorldContext context = currentContext();
        if (context == null) {
            return false;
        }
        MetalWorldRenderingPipeline pipeline = context.pipeline();
        ShaderKey key = MetalIrisPipelines.getShaderKeyForPipeline(pipeline, source);
        if (key == null || !WORLD_OVERRIDE_KEYS.contains(key)) {
            // A context exists only for armed, whitelisted draws; reaching this
            // means the source pipeline no longer resolves the same way as at
            // arm time. Record once per pipeline to make real-device triage
            // possible instead of failing silently.
            recordPipelineSkip(source, key, "not-whitelisted");
            return false;
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal world override has no active Metal device");
        }
        if (!pipeline.compiledPrograms().isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal world override crossed Metal device ownership");
        }
        ProgramRequest request = shaderKeyToProgramRequest(key);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().vanilla(
                        request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
                );
        if (linked.isEmpty()) {
            recordSkip(key, "no-program");
            return false;
        }
        IrisMetalCompiledPrograms.RasterState state = IrisMetalCompiledPrograms.RasterState.from(
                source, source.getVertexFormatBinding(0)
        );
        Optional<MetalCompiledRenderPipeline> compiled = pipeline.compiledPrograms().vanilla(
                request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs(), state
        );
        if (compiled.isEmpty()) {
            recordSkip(key, "no-compiled");
            return false;
        }
        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
        MetalCompiledRenderPipeline resolved = compiled.orElseThrow();
        checkAttachmentSignature(program, resolved, metalPass, key);
        metalPass.setCompiledPipeline(resolved);
        if (resolved.resource(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME) != null) {
            metalPass.setUniform(
                    IrisMetalGlslLinker.UNIFORM_BLOCK_NAME,
                    pipeline.uniformSlice(key)
            );
        }
        recordInstall(key, program.name(), program.program().drawBuffers(), "install");
        recordInputAudit(key, source, resolved);
        return true;
    }

    /**
     * One-shot per-key probe of the vertex input contract actually used for a
     * taken-over draw: the runtime vertex format elements (which double as the
     * MTL vertex descriptor attributes, assigned sequentially), the generic
     * fallback inputs, and the Metal buffer slots reserved for them. The
     * per-draw bound slot list is recorded by
     * {@link #recordDrawVertexBuffers} from the first draw.
     */
    private static void recordInputAudit(
            final ShaderKey key,
            final RenderPipeline source,
            final MetalCompiledRenderPipeline compiled
    ) {
        if (!REPORTED_INPUT_AUDITS.add(key.getName())) {
            return;
        }
        StringBuilder audit = new StringBuilder("world override attrs key=").append(key.getName());
        VertexFormat format = source.getVertexFormatBinding(0);
        if (format == null) {
            audit.append(" format=<null>");
        } else {
            List<VertexFormatElement> elements = format.getElements();
            audit.append(" formatStride=").append(format.getVertexSize()).append('B');
            for (int location = 0; location < elements.size(); location++) {
                VertexFormatElement element = elements.get(location);
                audit.append(" [").append(location).append(':').append(element.name())
                        .append('@').append(element.offset())
                        .append(' ').append(element.format()).append(']');
            }
        }
        audit.append(" firstSlot=").append(compiled.firstAvailableVertexBufferSlot())
                .append(" vertexBufferCount=").append(compiled.vertexBufferCount())
                .append(" genericSlot=").append(compiled.genericVertexBufferSlot())
                .append(" generic=").append(compiled.genericVertexInputs());
        MetalProbeReport.record(audit.toString());
    }

    /**
     * One-shot record of the vertex buffers the engine actually bound for the
     * first world-override draw, so a real-device run can be checked against
     * the {@link #recordInputAudit} contract.
     */
    static void recordDrawVertexBuffers(final MetalRenderPass metalPass) {
        if (drawVertexBuffersReported || currentContext() == null) {
            return;
        }
        drawVertexBuffersReported = true;
        StringBuilder slots = new StringBuilder();
        int boundCount = 0;
        for (int slot = 0; slot < MetalRenderPass.MAX_VERTEX_BUFFERS; slot++) {
            if (!metalPass.isVertexBufferBound(slot)) {
                continue;
            }
            if (boundCount++ > 0) {
                slots.append(',');
            }
            slots.append(slot);
        }
        MetalProbeReport.record(
                "world override draw vertexBuffers bound=[" + slots + "] count=" + boundCount
        );
    }

    /**
     * The resolved program's DRAWBUFFERS must match the attachments the pass
     * was rewritten to; when variants disagree (e.g. an entity pass whose key
     * differs from the phase's primary key) this surfaces as a diagnostic
     * exception instead of silently drawing into the wrong attachments.
     */
    private static void checkAttachmentSignature(
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final MetalCompiledRenderPipeline compiled,
            final MetalRenderPass metalPass,
            final ShaderKey key
    ) {
        MTLPixelFormat[] expected = compiled.colorAttachmentFormats();
        MTLPixelFormat[] actual = metalPass.colorAttachmentFormats();
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Iris Metal world pass attachment signature mismatch: key=" + key
                            + " program=" + program.name()
                            + " drawBuffers=" + Arrays.toString(program.program().drawBuffers())
                            + " pipelineFormats=" + Arrays.toString(expected)
                            + " renderPassFormats=" + Arrays.toString(actual)
            );
        }
    }

    /**
     * Reads and clears the draw-time arming signal. 26.2 dispatches entity /
     * block-entity draws by pipeline identity, so the whitelist decision is
     * made in {@link #armForDraw} and only the consuming draw inherits it.
     */
    private static @Nullable ShaderKey consumePendingKey() {
        ShaderKey key = PENDING_WORLD_KEY.get();
        PENDING_WORLD_KEY.remove();
        return key;
    }

    /**
     * Derives the vanilla program request for a shader key, mirroring
     * {@code IrisRenderingPipeline.createShader}: {@code isFullbright} comes
     * from {@code shouldIgnoreLightmap}, {@code isLines}/{@code isGlint}/
     * {@code isText} from the key, {@code isIE} is always {@code false}.
     */
    public static ProgramRequest shaderKeyToProgramRequest(final ShaderKey key) {
        boolean lines = key == ShaderKey.LINES;
        boolean clouds = key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM;
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(
                key.getVertexFormat(),
                key.shouldIgnoreLightmap(),
                lines,
                key.isGlint(),
                key.isText(),
                false
        );
        return new ProgramRequest(key.getProgram(), key.getAlphaTest(), lines, clouds, inputs);
    }

    /** Resolves gbuffer/texture samplers for non-terrain world draws (generic subset of the terrain table). */
    static MetalRenderPass.@Nullable TextureViewAndSampler fallbackSampler(
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        WorldContext context = currentContext();
        if (context == null) {
            return null;
        }
        if (MetalDebugSwitches.LOG_SAMPLERS && !samplersReported) {
            samplersReported = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] worldPass samplers: {}", bound.keySet());
            MetalProbeReport.record("worldPass samplers=" + bound.keySet());
        }
        // 26.2's vanilla-core transformer re-exposes the engine's render-type
        // textures under Iris names, so a patchVanilla program declares those
        // instead of the names the engine binds: the albedo bound as Sampler0
        // becomes "gtexture" (and upstream's texture/tex/u_MainSampler
        // aliases), the overlay bound as Sampler1 by RenderSetup.useOverlay
        // becomes "iris_overlay" (EntityPatcher), and the lightmap bound as
        // Sampler2 by useLightmap becomes "lightmap". Resolve them from the
        // engine-bound map when present.
        MetalRenderPass.TextureViewAndSampler vanillaAlias = switch (name) {
            case "gtexture", "texture", "tex", "u_MainSampler" -> bound.get("Sampler0");
            case "iris_overlay" -> bound.get("Sampler1");
            case "lightmap" -> bound.get("Sampler2");
            default -> null;
        };
        if (vanillaAlias != null) {
            return vanillaAlias;
        }
        // Fullbright / overlay-less render types (eg the eyes pass, which binds
        // only Sampler0) leave Sampler1/Sampler2 unbound while the program
        // still declares iris_overlay/lightmap. Upstream Iris substitutes a
        // 1x1 white pixel: a white overlay gives entityColor = (1,1,1,0), whose
        // rgb the entity workaround zeroes (no hurt tint), and a white lightmap
        // yields fullbright. Missing anything else keeps the caller's
        // Missing sampler diagnostic.
        if ("iris_overlay".equals(name) || "lightmap".equals(name)) {
            return context.pipeline().resources().whitePixel().binding();
        }
        if ("noisetex".equals(name)) {
            return context.pipeline().resources().noiseTexture().binding();
        }
        IrisMetalRenderTargets renderTargets = context.pipeline().resources().renderTargets();
        GpuTextureView depthView = switch (name) {
            case "depthtex0" -> renderTargets.mainDepthView();
            case "depthtex1" -> renderTargets.noTranslucentsDepthView();
            case "depthtex2" -> renderTargets.noHandDepthView();
            default -> null;
        };
        if (depthView != null) {
            return new MetalRenderPass.TextureViewAndSampler(
                    depthView,
                    renderTargets.depthSampler()
            );
        }
        int colorIndex = renderTargetIndex(name);
        if (colorIndex >= 0) {
            if (colorIndex >= renderTargets.colorTargets().targetCount()) {
                throw new IllegalStateException(
                        "Sampler " + name + " resolves to colortex" + colorIndex
                                + " but this generation owns only "
                                + renderTargets.colorTargets().targetCount() + " targets"
                );
            }
            return new MetalRenderPass.TextureViewAndSampler(
                    renderTargets.colorTargets().readView(colorIndex),
                    renderTargets.colorSampler(colorIndex)
            );
        }
        IrisMetalShadowTargets shadowTargets = context.pipeline().resources().shadowTargets();
        if (shadowTargets == null) {
            return null;
        }
        int shadowDepthIndex = switch (name) {
            case "shadowtex0", "shadowtex0HW", "watershadow" -> 0;
            case "shadowtex1", "shadowtex1HW" -> 1;
            default -> -1;
        };
        if (shadowDepthIndex >= 0) {
            boolean comparison = !name.endsWith("HW");
            return new MetalRenderPass.TextureViewAndSampler(
                    shadowDepthIndex == 0
                            ? shadowTargets.shadowDepthView()
                            : shadowTargets.shadowDepthNoTranslucentsView(),
                    shadowTargets.depthSampler(shadowDepthIndex, comparison)
            );
        }
        int shadowColorTarget = shadowColorIndex(name);
        if (shadowColorTarget >= 0) {
            return new MetalRenderPass.TextureViewAndSampler(
                    shadowTargets.colorView(shadowColorTarget, context.pipeline().shadowReadSnapshot()),
                    shadowTargets.colorSampler(shadowColorTarget)
            );
        }
        return null;
    }

    private static void recordInstall(
            final ShaderKey key,
            final String program,
            final int[] drawBuffers,
            final String where
    ) {
        if (REPORTED_INSTALLS.add(key.getName())) {
            MetalProbeReport.record("world override install " + where
                    + " key=" + key.getName()
                    + " program=" + program
                    + " drawBuffers=" + Arrays.toString(drawBuffers));
        }
    }

    private static void recordSkip(final ShaderKey key, final String reason) {
        if (REPORTED_SKIPS.add(key.getName() + ":" + reason)) {
            MetalProbeReport.record("world override skip key=" + key.getName() + " reason=" + reason);
        }
    }

    /**
     * Diagnostic for draws that were considered but left vanilla. Deduped per
     * (reason, key, source pipeline) so repeated draws do not flood the probe
     * report.
     */
    private static void recordPipelineSkip(
            final RenderPipeline source,
            final @Nullable ShaderKey key,
            final String reason
    ) {
        String keyName = key == null ? "<none>" : key.getName();
        if (REPORTED_PIPELINE_SKIPS.add(reason + ":" + keyName + ":" + source.getLocation())) {
            MetalProbeReport.record("world override skip key=" + keyName
                    + " source=" + source.getLocation()
                    + " reason=" + reason);
        }
    }

    private static int shadowColorIndex(final String name) {
        if (name.equals("shadowcolor")) {
            return 0;
        }
        if (!name.startsWith("shadowcolor") || name.startsWith("shadowcolorimg")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("shadowcolor".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int renderTargetIndex(final String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives
                .LEGACY_RENDER_TARGETS.indexOf(name);
    }

    private static @Nullable WorldContext currentContext() {
        WorldContext context = ACTIVE_WORLD_PASS.get();
        if (context == null) {
            return null;
        }
        if (activePipeline() != context.pipeline()) {
            ACTIVE_WORLD_PASS.remove();
            throw new IllegalStateException("Iris Metal world context crossed world generations");
        }
        return context;
    }

    private static @Nullable MetalWorldRenderingPipeline activePipeline() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof MetalWorldRenderingPipeline metal ? metal : null;
    }

    /** A shader key and the compilation knobs needed to resolve its program. */
    public record ProgramRequest(
            ProgramId program,
            AlphaTest alphaTest,
            boolean lines,
            boolean clouds,
            ShaderAttributeInputs inputs
    ) {
    }

    private record WorldContext(
            MetalWorldRenderingPipeline pipeline,
            ShaderKey key
    ) {
    }
}
