package com.tradinganalytics.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class ReportingFiles {
    private static final Set<PosixFilePermission> MODE_0644 = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private ReportingFiles() {}

    static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    /**
     * Locate the checkout root the same way the Node commands' script-relative
     * {@code REPO} constant does. Walking the current directory first preserves
     * fixture/check-out isolation; the class location covers invocation from a
     * directory outside the checkout during local development.
     */
    static Path repositoryRoot(Path workingDirectory) {
        Path fromWorkingDirectory = checkoutAncestor(workingDirectory.toAbsolutePath().normalize());
        if (fromWorkingDirectory != null) return fromWorkingDirectory;
        try {
            Path classes = Path.of(ReportingFiles.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path fromClasses = checkoutAncestor(classes.toAbsolutePath().normalize());
            if (fromClasses != null) return fromClasses;
        } catch (Exception ignored) { /* packaged invocation may not expose a file URI */ }
        return workingDirectory.toAbsolutePath().normalize();
    }

    private static Path checkoutAncestor(Path start) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("analytics-reporting/pom.xml"))
                    && Files.isDirectory(current.resolve("reports"))
                    && Files.isDirectory(current.resolve("schemas"))) return current;
            current = current.getParent();
        }
        return null;
    }

    static void atomicWrite(Path target, String text, String tempSuffix) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Path.of(target.toString() + tempSuffix);
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(temp, MODE_0644); }
            catch (UnsupportedOperationException ignored) { /* Windows/non-POSIX */ }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best effort, as in Node */ }
            throw exception;
        }
    }

    static String message(Exception exception) {
        if (exception instanceof java.nio.file.NoSuchFileException missing) {
            return "ENOENT: no such file or directory, open '" + missing.getFile() + "'";
        }
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
