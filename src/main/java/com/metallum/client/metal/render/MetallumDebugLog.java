package com.metallum.client.metal.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Temporary on-device diagnostic sink. Pojav/Amethyst keeps Minecraft's
 * Log4j output in sandbox paths that are awkward to export, so the Iris pass
 * diagnostics also append to {@code metallum-debug.log} in the game/home
 * directory. Remove together with the debug logging commit.
 */
public final class MetallumDebugLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private MetallumDebugLog() {
    }

    public static synchronized void log(final String line) {
        String message = LocalTime.now().format(TIME) + " " + line + System.lineSeparator();
        for (String property : new String[]{"user.dir", "pojav.launcher.home", "user.home", "java.io.tmpdir"}) {
            String root = System.getProperty(property);
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(root, "metallum-debug.log").toAbsolutePath();
                Files.writeString(path, message, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            } catch (IOException | RuntimeException ignored) {
                // Try the next candidate directory.
            }
        }
    }
}
