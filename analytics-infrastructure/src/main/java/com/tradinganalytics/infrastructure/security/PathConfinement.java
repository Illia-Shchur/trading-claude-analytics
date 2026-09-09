package com.tradinganalytics.infrastructure.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.regex.Pattern;

/** Repository-relative path validation plus no-link physical confinement. */
public final class PathConfinement {
    public enum ExpectedType { ANY, FILE, DIRECTORY }

    public record ResolvedPath(Path absolute, String relative, BasicFileAttributes attributes) {}

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private PathConfinement() {}

    public static String repositoryRelativePath(String value, String label) {
        String text = value == null ? "" : value;
        if (text.isEmpty() || containsControl(text) || text.indexOf('\\') >= 0
                || text.startsWith("/") || WINDOWS_DRIVE.matcher(text).matches()) {
            throw new CustodyException(label + " must be a non-empty repository-relative path");
        }
        String[] parts = text.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals("..")) {
                throw new CustodyException(label + " contains an invalid traversal component");
            }
        }
        return String.join("/", parts);
    }

    public static Path requireRealDirectory(Path root, String label) {
        Path absolute = root.toAbsolutePath().normalize();
        BasicFileAttributes attributes = attributes(absolute, label);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new CustodyException(label + " must be a real directory");
        }
        try {
            return absolute.toRealPath();
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be resolved", error);
        }
    }

    public static ResolvedPath resolve(Path root, String candidate, String label, ExpectedType expectedType) {
        String relativeInput = repositoryRelativePath(candidate, label);
        Path base = requireRealDirectory(root, "approved root");
        Path cursor = base;
        String[] components = relativeInput.split("/");
        BasicFileAttributes attributes = null;
        for (int index = 0; index < components.length; index++) {
            cursor = cursor.resolve(components[index]).normalize();
            if (!cursor.startsWith(base)) {
                throw new CustodyException(label + " escapes its approved root");
            }
            attributes = attributes(cursor, label + " component " + components[index]);
            if (attributes.isSymbolicLink()) {
                throw new CustodyException(label + " contains a symlink");
            }
            boolean last = index == components.length - 1;
            if (!last && !attributes.isDirectory()) {
                throw new CustodyException(label + " contains a non-directory component");
            }
        }
        if (attributes == null) {
            throw new CustodyException(label + " is missing");
        }
        if (expectedType == ExpectedType.FILE && !attributes.isRegularFile()) {
            throw new CustodyException(label + " must be a regular, singly-linked file");
        }
        if (expectedType == ExpectedType.DIRECTORY && !attributes.isDirectory()) {
            throw new CustodyException(label + " must be a directory");
        }
        if (!attributes.isRegularFile() && !attributes.isDirectory()) {
            throw new CustodyException(label + " is not a regular file or directory");
        }
        if (attributes.isRegularFile()) {
            requireSingleLink(cursor, label);
        }
        try {
            Path real = cursor.toRealPath();
            if (!real.startsWith(base)) {
                throw new CustodyException(label + " resolves outside its approved root");
            }
            String normalizedRelative = base.relativize(real).toString().replace(real.getFileSystem().getSeparator(), "/");
            if (normalizedRelative.isEmpty()) {
                throw new CustodyException(label + " path must name a child beneath its approved root");
            }
            return new ResolvedPath(real, normalizedRelative, attributes);
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be resolved", error);
        }
    }

    public static byte[] readSinglyLinkedFile(Path root, String candidate, String label) {
        ResolvedPath resolved = resolve(root, candidate, label, ExpectedType.FILE);
        return readSinglyLinkedFile(resolved.absolute(), label);
    }

    public static byte[] readSinglyLinkedFile(Path path, String label) {
        BasicFileAttributes before = validateSinglyLinkedFile(path, label);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException error) {
            throw new CustodyException(label + " bytes cannot be read", error);
        }
        BasicFileAttributes after = attributes(path, label);
        requireSingleLink(path, label);
        if (after.isSymbolicLink() || !after.isRegularFile()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new CustodyException(label + " changed while it was being read");
        }
        return bytes;
    }

    public static BasicFileAttributes validateSinglyLinkedFile(Path path, String label) {
        BasicFileAttributes attributes = attributes(path, label);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new CustodyException(label + " must be a regular, singly-linked file");
        }
        requireSingleLink(path, label);
        return attributes;
    }

    public static void requireSingleLink(Path path, String label) {
        try {
            Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            long links = ((Number) value).longValue();
            if (links != 1L) {
                throw new CustodyException(label + " must be a regular, singly-linked file");
            }
        } catch (UnsupportedOperationException error) {
            throw new CustodyException(label + " link count cannot be verified", error);
        } catch (IOException error) {
            throw new CustodyException(label + " link count cannot be read", error);
        }
    }

    static BasicFileAttributes attributes(Path path, String label) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new CustodyException(label + " is missing", error);
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint <= 0x1f
                || (codePoint >= 0x7f && codePoint <= 0x9f));
    }
}
