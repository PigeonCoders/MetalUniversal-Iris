package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Iris logical render-target formats lowered to renderable Metal formats. */
final class IrisMetalRenderTargetFormats {
    static final int MAX_LOGICAL_TARGETS = 32;
    static final GpuFormat DEFAULT_FORMAT = GpuFormat.RGBA8_UNORM;

    private static final Pattern COLORTEX = Pattern.compile("\\bcolortex(\\d+)\\b");
    private static final Pattern COLORIMG = Pattern.compile("\\bcolorimg(\\d+)\\b");
    private static final Pattern LEGACY = Pattern.compile("\\b(gcolor|gdepth|gnormal|composite|gaux([1-4]))\\b");

    private IrisMetalRenderTargetFormats() {
    }

    /**
     * Allocates only the logical targets the pack can actually reference.
     * {@code PackRenderTargetDirectives} always carries entries for all 32
     * indices, so using its key set made the backend allocate 32 main+alt
     * textures (~250 MB at 1180x820 RGBA8) even for packs that only use
     * colortex0..7 — enough to exhaust the iPad's GPU budget.
     */
    static GpuFormat[] from(final PackDirectives directives, final ProgramSet programSet) {
        return allocate(directives, highestReferencedTarget(programSet));
    }

    static GpuFormat[] from(final PackDirectives directives) {
        return allocate(directives, highestReferencedTarget(directives));
    }

    private static GpuFormat[] allocate(final PackDirectives directives, final int highestReferenced) {
        Map<Integer, RenderTargetSettings> settings = directives
                .getRenderTargetDirectives()
                .getRenderTargetSettings();
        int highest = Math.max(0, highestReferenced);
        if (highest >= MAX_LOGICAL_TARGETS) {
            throw new IllegalArgumentException(
                    "Iris render target colortex" + highest + " exceeds the supported 0.."
                            + (MAX_LOGICAL_TARGETS - 1) + " range"
            );
        }

        GpuFormat[] formats = new GpuFormat[highest + 1];
        Arrays.fill(formats, DEFAULT_FORMAT);
        for (Map.Entry<Integer, RenderTargetSettings> entry : settings.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= formats.length) {
                continue;
            }
            RenderTargetSettings target = entry.getValue();
            if (target.getInternalFormat() != null) {
                formats[index] = fromInternalName(target.getInternalFormat().name());
            }
        }
        return formats;
    }

    private static int highestReferencedTarget(final PackDirectives directives) {
        return directives.getRenderTargetDirectives()
                .getRenderTargetSettings().keySet().stream()
                .filter(index -> index != null && index >= 0)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    /** Highest colortex/legacy index referenced by any raster or compute source. */
    static int highestReferencedTarget(final ProgramSet programSet) {
        int highest = -1;
        for (ProgramId id : ProgramId.values()) {
            ProgramSource source = programSet.get(id).orElse(null);
            highest = considerSource(highest, source);
        }
        for (ProgramArrayId array : ProgramArrayId.values()) {
            for (ProgramSource source : programSet.getComposite(array)) {
                highest = considerSource(highest, source);
            }
            for (ComputeSource[] computeArray : programSet.getCompute(array)) {
                if (computeArray == null) {
                    continue;
                }
                for (ComputeSource compute : computeArray) {
                    if (compute != null && compute.getSource().isPresent()) {
                        highest = considerText(highest, compute.getSource().orElseThrow());
                    }
                }
            }
        }
        ProgramSource finalSource = programSet.get(ProgramId.Final).orElse(null);
        highest = considerSource(highest, finalSource);
        for (ComputeSource compute : programSet.getFinalCompute()) {
            if (compute != null && compute.getSource().isPresent()) {
                highest = considerText(highest, compute.getSource().orElseThrow());
            }
        }
        for (ComputeSource compute : programSet.getShadowCompute()) {
            if (compute != null && compute.getSource().isPresent()) {
                highest = considerText(highest, compute.getSource().orElseThrow());
            }
        }
        return Math.max(0, highest);
    }

    private static int considerSource(int highest, ProgramSource source) {
        if (source == null || !source.isValid()) {
            return highest;
        }
        int[] drawBuffers = source.getDirectives().getDrawBuffers();
        for (int target : drawBuffers) {
            highest = Math.max(highest, target);
        }
        highest = considerText(highest, source.getVertexSource().orElse(""));
        highest = considerText(highest, source.getFragmentSource().orElse(""));
        return highest;
    }

    private static int considerText(int highest, String text) {
        Matcher color = COLORTEX.matcher(text);
        while (color.find()) {
            highest = Math.max(highest, Integer.parseInt(color.group(1)));
        }
        Matcher image = COLORIMG.matcher(text);
        while (image.find()) {
            highest = Math.max(highest, Integer.parseInt(image.group(1)));
        }
        Matcher legacy = LEGACY.matcher(text);
        while (legacy.find()) {
            String name = legacy.group(1);
            int target = switch (name) {
                case "gcolor" -> 0;
                case "gdepth" -> 1;
                case "gnormal" -> 2;
                case "composite" -> 3;
                default -> 4 + Integer.parseInt(legacy.group(2)) - 1;
            };
            highest = Math.max(highest, target);
        }
        return highest;
    }

    static GpuFormat fromInternalName(final String name) {
        return switch (name) {
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA16" -> GpuFormat.RGBA16_UNORM;
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            case "R8I" -> GpuFormat.R8_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "RGB8I" -> GpuFormat.RGBA8_SINT;
            case "RGBA8I" -> GpuFormat.RGBA8_SINT;
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "RGB8UI" -> GpuFormat.RGBA8_UINT;
            case "RGBA8UI" -> GpuFormat.RGBA8_UINT;
            case "R16I" -> GpuFormat.R16_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RGB16I" -> GpuFormat.RGBA16_SINT;
            case "RGBA16I" -> GpuFormat.RGBA16_SINT;
            case "R16UI" -> GpuFormat.R16_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RGB16UI" -> GpuFormat.RGBA16_UINT;
            case "RGBA16UI" -> GpuFormat.RGBA16_UINT;
            case "R32I" -> GpuFormat.R32_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RGB32I" -> GpuFormat.RGBA32_SINT;
            case "RGBA32I" -> GpuFormat.RGBA32_SINT;
            case "R32UI" -> GpuFormat.R32_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RGB32UI" -> GpuFormat.RGBA32_UINT;
            case "RGBA32UI" -> GpuFormat.RGBA32_UINT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            default -> throw new IllegalArgumentException(
                    "Unsupported Iris render-target format " + name
            );
        };
    }
}
