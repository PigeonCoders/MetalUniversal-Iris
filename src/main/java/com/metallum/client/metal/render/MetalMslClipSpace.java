package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing for SPIRV-Cross-generated vertex MSL.
 *
 * <p>SPIRV-Cross's {@code SPVC_COMPILER_OPTION_FLIP_VERTEX_Y} only mirrors the
 * Y axis for Metal's top-left framebuffer convention. It does NOT remap the
 * OpenGL clip-space Z range [-w, w] to Metal's [0, w]. Without that remap every
 * vertex whose GL clip-space z is negative — i.e. the front half of the view
 * frustum — is clipped away by Metal, producing a see-through (x-ray) world.
 * LWJGL's spvc bindings expose neither the common
 * {@code fixup_clipspace} option nor an MSL backend equivalent, so the
 * SPIRV-Cross fixup_clipspace formula {@code z' = (z + w) * 0.5} is applied to
 * the emitted MSL text exactly where the Y-flip line was emitted.
 *
 * <p>The shaderpack path always enables this because Iris passes shaderpacks
 * a GL-convention [-w, w] projection. Vanilla Blaze3D shaders enable it only
 * while Iris is active ({@link MetalIrisDepthConvention#conventionalDepthActive()}):
 * Iris's UndoReverseZ mixins rewrite Blaze3D's projection to GL convention
 * whenever a pack is loaded, but otherwise vanilla already emits Metal's
 * zero-to-one convention.
 */
@Environment(EnvType.CLIENT)
final class MetalMslClipSpace {
    private static final Pattern CLIP_SPACE_FLIP_LINE = Pattern.compile(
            "(?m)^([ \\t]*)out\\.gl_Position\\.y = -\\(out\\.gl_Position\\.y\\);"
    );
    private static final String CLIP_SPACE_Z_FIXUP = "out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5;"
            + "    // Adjust clip-space for Metal";

    private MetalMslClipSpace() {
    }

    /**
     * Applies the missing clip-space Z remap to generated vertex MSL.
     *
     * <p>Idempotent: if a SPIRV-Cross version already emitted the remap, the
     * source is returned unchanged. When a vertex shader carries a
     * {@code [[position]]} output but no Y-flip line could be found (a
     * different SPIRV-Cross output shape), a warning is logged once per shape
     * so the missing depth remap is not silent.
     */
    static String fixup(final String msl) {
        if (msl == null || msl.contains("out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5")) {
            return msl;
        }
        Matcher matcher = CLIP_SPACE_FLIP_LINE.matcher(msl);
        if (!matcher.find()) {
            if (msl.contains("[[position]]")) {
                Metallum.LOGGER.warn(
                        "[MetalUniversal/Iris] Vertex MSL has a [[position]] output but no SPIRV-Cross Y-flip line; "
                                + "clip-space Z was NOT remapped from [-w,w] to Metal [0,w]. "
                                + "Front-half geometry may be clipped (see-through terrain)."
                );
            }
            return msl;
        }

        matcher.reset();
        StringBuffer fixed = new StringBuffer(msl.length() + 128);
        while (matcher.find()) {
            String indent = matcher.group(1);
            matcher.appendReplacement(
                    fixed,
                    Matcher.quoteReplacement(
                            indent + CLIP_SPACE_Z_FIXUP + "\n" + indent + "out.gl_Position.y = -(out.gl_Position.y);"
                    )
            );
        }
        matcher.appendTail(fixed);
        return fixed.toString();
    }
}
