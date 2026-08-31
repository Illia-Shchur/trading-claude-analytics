package com.tradinganalytics.research.calibration;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CalibrationPaths {
    private CalibrationPaths() {}

    static Path resolve(Path root, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    public static Path repositoryRoot(Path workingDirectory) {
        Path current = workingDirectory.toAbsolutePath().normalize();
        while (current != null) {
            if (isRepositoryRoot(current)) return current;
            current = current.getParent();
        }
        try {
            current = Path.of(CalibrationPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (current != null) {
                if (isRepositoryRoot(current)) return current;
                current = current.getParent();
            }
        } catch (Exception ignored) { /* packaged runtimes may not expose a file URI */ }
        return workingDirectory.toAbsolutePath().normalize();
    }

    private static boolean isRepositoryRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve("analytics-research/pom.xml"))
                && Files.isDirectory(candidate.resolve("reports"));
    }

    static String message(Exception exception) {
        if (exception instanceof java.nio.file.NoSuchFileException missing)
            return "ENOENT: no such file or directory, open '" + missing.getFile() + "'";
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static String jsonParseMessage(String raw, Exception exception) {
        if (raw == null || raw.trim().isEmpty()) return "Unexpected end of JSON input";
        if ("{".equals(raw)) return "Expected property name or '}' in JSON at position 1 (line 1 column 2)";
        if (raw.matches("(?s)[^\\[\\{\\\"0-9tfn-].*")) {
            char token = raw.charAt(0);
            return "Unexpected token '" + token + "', " + quote(raw) + " is not valid JSON";
        }
        if (raw.matches("(?s).*[,]\\s*}")) {
            int position = raw.lastIndexOf('}');
            return "Expected double-quoted property name in JSON at position " + position
                    + " (line " + line(raw, position) + " column " + column(raw, position) + ")";
        }
        if (raw.matches("(?s).*[,]\\s*]")) {
            return "Unexpected token ']', " + quote(raw) + " is not valid JSON";
        }
        return message(exception);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }

    private static int line(String value, int position) {
        int line = 1; for (int index = 0; index < position; index++) if (value.charAt(index) == '\n') line++; return line;
    }

    private static int column(String value, int position) {
        int newline = value.lastIndexOf('\n', Math.max(0, position - 1)); return position - newline;
    }
}
