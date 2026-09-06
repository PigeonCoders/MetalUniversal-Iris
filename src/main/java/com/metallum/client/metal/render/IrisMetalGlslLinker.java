package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lowers Iris's OpenGL-style paired GLSL into a shared Metal resource ABI. */
@Environment(EnvType.CLIENT)
public final class IrisMetalGlslLinker {
    public static final String UNIFORM_BLOCK_NAME = "MetallumIrisUniforms";
    public static final String SODIUM_PUSH_CONSTANT_BLOCK_NAME = "MetallumSodiumPushConstants";

    private static final Pattern UNIFORM_STATEMENT =
            Pattern.compile("(?m)^[ \\t]*uniform\\b([^;{}]*);");
    private static final Pattern OPAQUE_TYPE =
            Pattern.compile("[iu]?(sampler|image|texture)\\w*|atomic_uint");
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+([A-Za-z_]\\w*)\\s*\\{"
    );
    private static final Pattern HOSTILE_IDENTIFIER = Pattern.compile(
            "\\b(new|delete|this|template|typename|namespace|operator|private|public|protected|virtual"
                    + "|using|mutable|friend|extern|register|typedef|union|enum|auto|char|short|signed"
                    + "|unsigned|class|constexpr|nullptr|throw|try|catch|kernel|device|constant|thread"
                    + "|threadgroup|half|sampler)\\b"
    );
    private static final Set<String> UNIFORM_QUALIFIERS = Set.of(
            "lowp", "mediump", "highp", "coherent", "volatile", "restrict", "readonly", "writeonly"
    );

    private static final List<LooseUniform> SODIUM_PUSH_CONSTANTS = List.of(
            new LooseUniform("vec3", "u_RegionOffset", ""),
            new LooseUniform("int", "u_CurrentTime", ""),
            new LooseUniform("uint", "u_RegionID", "")
    );
    private static final String SODIUM_PUSH_CONSTANT_BLOCK = """
            layout(push_constant) uniform MetallumSodiumPushConstants {
                vec3 u_RegionOffset;
                int u_CurrentTime;
                uint u_RegionID;
            };
            """;

    private static final Map<String, Std140Type> STD140_TYPES = Map.ofEntries(
            Map.entry("float", new Std140Type(4, 4)),
            Map.entry("int", new Std140Type(4, 4)),
            Map.entry("uint", new Std140Type(4, 4)),
            Map.entry("bool", new Std140Type(4, 4)),
            Map.entry("vec2", new Std140Type(8, 8)),
            Map.entry("ivec2", new Std140Type(8, 8)),
            Map.entry("uvec2", new Std140Type(8, 8)),
            Map.entry("bvec2", new Std140Type(8, 8)),
            Map.entry("vec3", new Std140Type(16, 12)),
            Map.entry("ivec3", new Std140Type(16, 12)),
            Map.entry("uvec3", new Std140Type(16, 12)),
            Map.entry("bvec3", new Std140Type(16, 12)),
            Map.entry("vec4", new Std140Type(16, 16)),
            Map.entry("ivec4", new Std140Type(16, 16)),
            Map.entry("uvec4", new Std140Type(16, 16)),
            Map.entry("bvec4", new Std140Type(16, 16)),
            Map.entry("mat2", new Std140Type(16, 32)),
            Map.entry("mat3", new Std140Type(16, 48)),
            Map.entry("mat4", new Std140Type(16, 64))
    );

    private IrisMetalGlslLinker() {
    }

    public static LinkedRasterProgram linkDefault(
            final IrisMetalProgramFrontend.RasterProgram program
    ) {
        return link(program, false);
    }

    public static LinkedRasterProgram linkSodium(
            final IrisMetalProgramFrontend.RasterProgram program
    ) {
        return link(program, true);
    }

    private static LinkedRasterProgram link(
            final IrisMetalProgramFrontend.RasterProgram program,
            final boolean sodium
    ) {
        Objects.requireNonNull(program, "program");
        if (program.requiresUnsupportedMetalStage()) {
            throw new LinkException(program.name(), "geometry/tessellation stage requires Metal lowering");
        }

        try {
            LooseExtraction vertex = extractLooseUniforms(normalize(program.vertexSource()));
            LooseExtraction fragment = extractLooseUniforms(normalize(program.fragmentSource()));

            List<LooseUniform> vertexPack = sodium
                    ? partitionSodiumPushConstants(program.name(), vertex.uniforms())
                    : vertex.uniforms();
            List<LooseUniform> fragmentPack = sodium
                    ? partitionSodiumPushConstants(program.name(), fragment.uniforms())
                    : fragment.uniforms();
            List<LooseUniform> shared = dedupeByName(List.of(vertexPack, fragmentPack));
            List<UniformMember> layout = computeStd140Layout(program.name(), shared);

            String vertexSource = vertex.body();
            String fragmentSource = fragment.body();
            if (!shared.isEmpty()) {
                String block = renderUniformBlock(shared);
                vertexSource = insertBlock(vertexSource, block);
                fragmentSource = insertBlock(fragmentSource, block);
            }
            if (sodium && vertexPack.size() != vertex.uniforms().size()) {
                vertexSource = insertBlock(vertexSource, SODIUM_PUSH_CONSTANT_BLOCK);
            }
            if (sodium && fragmentPack.size() != fragment.uniforms().size()) {
                fragmentSource = insertBlock(fragmentSource, SODIUM_PUSH_CONSTANT_BLOCK);
            }

            Map<String, String> samplerTypes = new LinkedHashMap<>();
            collectSamplers(vertexSource, samplerTypes);
            collectSamplers(fragmentSource, samplerTypes);
            Set<String> blockNames = new LinkedHashSet<>();
            collectUniformBlocks(vertexSource, blockNames);
            collectUniformBlocks(fragmentSource, blockNames);
            int blockSize = layout.isEmpty()
                    ? 0
                    : alignUp(layout.getLast().offset() + layout.getLast().byteSize(), 16);

            return new LinkedRasterProgram(
                    program,
                    vertexSource,
                    fragmentSource,
                    layout,
                    blockSize,
                    samplerTypes.entrySet().stream()
                            .map(entry -> new SamplerDecl(entry.getKey(), entry.getValue()))
                            .toList(),
                    List.copyOf(blockNames)
            );
        } catch (LinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LinkException(program.name(), String.valueOf(exception.getMessage()), exception);
        }
    }

    private static String normalize(final String source) {
        return HOSTILE_IDENTIFIER.matcher(stripComments(source)).replaceAll("metallum_id_$1");
    }

    private static LooseExtraction extractLooseUniforms(final String source) {
        Matcher matcher = UNIFORM_STATEMENT.matcher(source);
        StringBuilder body = new StringBuilder(source.length());
        List<LooseUniform> uniforms = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            String statement = matcher.group(1).trim();
            List<String> tokens = leadingTokens(statement);
            int typeIndex = 0;
            while (typeIndex < tokens.size() && UNIFORM_QUALIFIERS.contains(tokens.get(typeIndex))) {
                typeIndex++;
            }
            if (typeIndex >= tokens.size()) {
                continue;
            }
            String type = tokens.get(typeIndex);
            if (OPAQUE_TYPE.matcher(type).matches()) {
                continue;
            }
            int declaratorsStart = statement.indexOf(type) + type.length();
            body.append(source, last, matcher.start());
            last = matcher.end();
            for (String declarator : splitTopLevel(statement.substring(declaratorsStart))) {
                LooseUniform uniform = parseLooseDeclarator(type, declarator);
                if (uniform == null) {
                    throw new IllegalStateException("cannot parse uniform declarator '" + declarator + "'");
                }
                uniforms.add(uniform);
            }
        }
        body.append(source, last, source.length());
        return new LooseExtraction(body.toString(), uniforms);
    }

    private static List<LooseUniform> partitionSodiumPushConstants(
            final String programName,
            final List<LooseUniform> uniforms
    ) {
        Map<String, LooseUniform> expected = new LinkedHashMap<>();
        SODIUM_PUSH_CONSTANTS.forEach(uniform -> expected.put(uniform.name(), uniform));
        List<LooseUniform> pack = new ArrayList<>(uniforms.size());
        int matched = 0;
        for (LooseUniform uniform : uniforms) {
            LooseUniform sodium = expected.get(uniform.name());
            if (sodium == null) {
                pack.add(uniform);
            } else if (!sodium.equals(uniform)) {
                throw new LinkException(
                        programName,
                        "Sodium push constant '" + uniform.name() + "' changed type or shape"
                );
            } else {
                matched++;
            }
        }
        if (matched != 0 && matched != SODIUM_PUSH_CONSTANTS.size()) {
            throw new LinkException(
                    programName,
                    "Sodium push-constant ABI is partial: " + matched + "/" + SODIUM_PUSH_CONSTANTS.size()
            );
        }
        return List.copyOf(pack);
    }

    private static List<LooseUniform> dedupeByName(final List<List<LooseUniform>> stageUniforms) {
        Map<String, LooseUniform> byName = new LinkedHashMap<>();
        for (List<LooseUniform> stage : stageUniforms) {
            for (LooseUniform uniform : stage) {
                LooseUniform previous = byName.putIfAbsent(uniform.name(), uniform);
                if (previous != null && !previous.equals(uniform)) {
                    throw new IllegalStateException(
                            "uniform '" + uniform.name() + "' differs across shader stages"
                    );
                }
            }
        }
        return List.copyOf(byName.values());
    }

    private static List<UniformMember> computeStd140Layout(
            final String programName,
            final List<LooseUniform> uniforms
    ) {
        List<UniformMember> result = new ArrayList<>(uniforms.size());
        int cursor = 0;
        for (LooseUniform uniform : uniforms) {
            Std140Type type = STD140_TYPES.get(uniform.type());
            if (type == null) {
                throw new LinkException(
                        programName,
                        "uniform '" + uniform.name() + "' has unsupported std140 type '" + uniform.type() + "'"
                );
            }
            int arrayCount = parseArrayCount(programName, uniform);
            int alignment = arrayCount == 0 ? type.alignment() : 16;
            int byteSize = arrayCount == 0
                    ? type.byteSize()
                    : alignUp(type.byteSize(), 16) * arrayCount;
            int offset = alignUp(cursor, alignment);
            result.add(new UniformMember(
                    uniform.type(), uniform.name(), arrayCount, offset, byteSize
            ));
            cursor = offset + byteSize;
        }
        return List.copyOf(result);
    }

    private static int parseArrayCount(final String programName, final LooseUniform uniform) {
        if (uniform.arraySuffix().isEmpty()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("^\\[(\\d+)]$").matcher(uniform.arraySuffix());
        if (!matcher.matches()) {
            throw new LinkException(
                    programName,
                    "uniform '" + uniform.name() + "' has non-literal or multidimensional array shape '"
                            + uniform.arraySuffix() + "'"
            );
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String renderUniformBlock(final List<LooseUniform> uniforms) {
        StringBuilder block = new StringBuilder("layout(std140) uniform ")
                .append(UNIFORM_BLOCK_NAME).append(" {\n");
        for (LooseUniform uniform : uniforms) {
            block.append("    ").append(uniform.declaration()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    private static String insertBlock(final String source, final String block) {
        int index = directivePreludeEnd(source);
        return source.substring(0, index) + block + source.substring(index);
    }

    private static int directivePreludeEnd(final String source) {
        int index = 0;
        while (index < source.length()) {
            int newline = source.indexOf('\n', index);
            int end = newline < 0 ? source.length() : newline + 1;
            String line = source.substring(index, end).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return index;
            }
            index = end;
        }
        return index;
    }

    private static void collectSamplers(final String source, final Map<String, String> out) {
        Matcher matcher = UNIFORM_STATEMENT.matcher(source);
        while (matcher.find()) {
            String statement = matcher.group(1).trim();
            List<String> tokens = leadingTokens(statement);
            int typeIndex = 0;
            while (typeIndex < tokens.size() && UNIFORM_QUALIFIERS.contains(tokens.get(typeIndex))) {
                typeIndex++;
            }
            if (typeIndex >= tokens.size()) {
                continue;
            }
            String type = tokens.get(typeIndex);
            if (!OPAQUE_TYPE.matcher(type).matches()) {
                continue;
            }
            int declaratorsStart = statement.indexOf(type) + type.length();
            for (String declarator : splitTopLevel(statement.substring(declaratorsStart))) {
                LooseUniform uniform = parseLooseDeclarator(type, declarator);
                if (uniform == null) {
                    continue;
                }
                String previous = out.putIfAbsent(uniform.name(), type);
                if (previous != null && !previous.equals(type)) {
                    throw new IllegalStateException(
                            "opaque uniform '" + uniform.name() + "' differs across shader stages"
                    );
                }
            }
        }
    }

    private static void collectUniformBlocks(final String source, final Set<String> out) {
        Matcher matcher = UNIFORM_BLOCK.matcher(source);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    private static List<String> leadingTokens(final String statement) {
        List<String> tokens = new ArrayList<>(5);
        Matcher matcher = Pattern.compile("[A-Za-z_]\\w*").matcher(statement);
        while (matcher.find() && tokens.size() < 5) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static List<String> splitTopLevel(final String source) {
        List<String> parts = new ArrayList<>(2);
        int depth = 0;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '(' || value == '[' || value == '{') {
                depth++;
            } else if (value == ')' || value == ']' || value == '}') {
                depth--;
            } else if (value == ',' && depth == 0) {
                parts.add(source.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(source.substring(start));
        return parts;
    }

    private static LooseUniform parseLooseDeclarator(final String type, final String declarator) {
        Matcher matcher = Pattern.compile(
                "^\\s*([A-Za-z_]\\w*)\\s*((?:\\[[^]\\r\\n]*]\\s*)*)"
        ).matcher(declarator);
        if (!matcher.find()) {
            return null;
        }
        return new LooseUniform(type, matcher.group(1), matcher.group(2).replaceAll("\\s+", ""));
    }

    private static String stripComments(final String source) {
        StringBuilder output = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char value = source.charAt(index);
            if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    output.append(' ');
                    index++;
                }
            } else if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                output.append("  ");
                index += 2;
                while (index < source.length()
                        && !(source.charAt(index) == '*'
                        && index + 1 < source.length()
                        && source.charAt(index + 1) == '/')) {
                    output.append(source.charAt(index) == '\n' ? '\n' : ' ');
                    index++;
                }
                if (index < source.length()) {
                    output.append("  ");
                    index += 2;
                }
            } else {
                output.append(value);
                index++;
            }
        }
        return output.toString();
    }

    private static int alignUp(final int value, final int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private record LooseUniform(String type, String name, String arraySuffix) {
        String declaration() {
            return type + " " + name + arraySuffix;
        }
    }

    private record LooseExtraction(String body, List<LooseUniform> uniforms) {
    }

    private record Std140Type(int alignment, int byteSize) {
    }

    public record UniformMember(
            String type,
            String name,
            int arrayCount,
            int offset,
            int byteSize
    ) {
    }

    public record SamplerDecl(String name, String glslType) {
        public boolean storageImage() {
            return glslType.startsWith("image")
                    || glslType.startsWith("iimage")
                    || glslType.startsWith("uimage");
        }

        public boolean sampled() {
            return !storageImage();
        }
    }

    public record LinkedRasterProgram(
            IrisMetalProgramFrontend.RasterProgram program,
            String vertexGlsl,
            String fragmentGlsl,
            List<UniformMember> uniformLayout,
            int uniformBlockSize,
            List<SamplerDecl> samplers,
            List<String> uniformBlockNames
    ) {
        public LinkedRasterProgram {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(vertexGlsl, "vertexGlsl");
            Objects.requireNonNull(fragmentGlsl, "fragmentGlsl");
            uniformLayout = List.copyOf(uniformLayout);
            samplers = List.copyOf(samplers);
            uniformBlockNames = List.copyOf(uniformBlockNames);
        }

        public String name() {
            return program.name();
        }
    }

    public static final class LinkException extends RuntimeException {
        LinkException(final String programName, final String message) {
            this(programName, message, null);
        }

        LinkException(final String programName, final String message, final Throwable cause) {
            super("Iris program '" + programName + "': " + message, cause);
        }
    }
}
