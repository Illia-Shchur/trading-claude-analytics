package com.tradinganalytics.cli;

import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses mode tails and confines every workflow input/output to its work root. */
final class StrategyV5WorkflowPaths {
    private static final Pattern SENSITIVE_OPTION = Pattern.compile(
            "(?i)(private-key|private_key|privatekey|secret)");

    private StrategyV5WorkflowPaths() {}

    static Map<String, String> flags(String[] args, int start) {
        Map<String, String> result = new LinkedHashMap<>();
        if (args == null) return result;
        for (int index = start; index < args.length; index++) {
            String token = args[index];
            if (token == null || !token.startsWith("-")) continue;
            int prefixLength = token.startsWith("--") ? 2 : 1;
            String key = token.substring(prefixLength);
            int equals = key.indexOf('=');
            String optionName = equals >= 0 ? key.substring(0, equals) : key;
            if (SENSITIVE_OPTION.matcher(optionName).find()) {
                throw new IllegalArgumentException("sensitive option names are not accepted: "
                        + "-".repeat(prefixLength) + optionName);
            }
            if (prefixLength != 2) continue;
            if (equals >= 0) {
                result.put(key.substring(0, equals), key.substring(equals + 1));
            } else if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                result.put(key, args[++index]);
            } else {
                result.put(key, "true");
            }
        }
        return result;
    }

    static String required(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return value;
    }

    static Path path(String value, Path work) {
        return confinedPath(value, work, "path");
    }

    static String outputPath(String value, Path work, String fallback) {
        return confinedPath(value == null || value.isBlank() ? fallback : value, work,
                "output path").toString();
    }

    /** Resolve a caller-supplied path without permitting absolute, traversal, or linked paths. */
    static Path confinedPath(String value, Path work, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("path is required");
        String relative = PathConfinement.repositoryRelativePath(value, label);
        Path base = PathConfinement.requireRealDirectory(work, "workflow working directory");
        Path candidate = base;
        if (!".".equals(relative)) {
            for (String component : relative.split("/", -1)) candidate = candidate.resolve(component);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(base)) throw new IllegalArgumentException(label + " escapes repository");
        Path cursor = base;
        if (!".".equals(relative)) {
            String[] components = relative.split("/", -1);
            for (int index = 0; index < components.length; index++) {
                cursor = cursor.resolve(components[index]);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(cursor, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (IOException error) {
                    throw new IllegalArgumentException(label + " cannot be inspected", error);
                }
                if (attributes.isSymbolicLink()) throw new IllegalArgumentException(label + " contains a symlink");
                boolean last = index == components.length - 1;
                if (!last && !attributes.isDirectory())
                    throw new IllegalArgumentException(label + " contains a non-directory component");
                if (attributes.isRegularFile()) PathConfinement.requireSingleLink(cursor, label);
                else if (!attributes.isDirectory())
                    throw new IllegalArgumentException(label + " contains a special file");
            }
        }
        return candidate;
    }

    static Path confinedAbsolute(Path candidate, Path work, String label) {
        Path base = PathConfinement.requireRealDirectory(work, "workflow working directory");
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) throw new IllegalArgumentException(label + " escapes repository");
        Path relative = base.relativize(absolute);
        return confinedPath(relative.toString().replace(absolute.getFileSystem().getSeparator(), "/"),
                work, label);
    }

    static void copyExclusive(Path source, Path target) {
        try {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("file is missing: " + source);
            }
            byte[] bytes = PathConfinement.readSinglyLinkedFile(source, "immutable source");
            writeExclusive(target, bytes);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cannot copy " + source, error);
        }
    }

    static void writeExclusive(Path target, byte[] bytes) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) throw new IllegalArgumentException("output path has no parent");
            rejectLinkedOutputAncestors(parent);
            Files.createDirectories(parent);
            requireRealOutputParent(parent);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] prior = PathConfinement.readSinglyLinkedFile(target, "immutable output");
                if (!java.util.Arrays.equals(prior, bytes)) {
                    throw new IllegalArgumentException("immutable output collision: " + target);
                }
                return;
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            PathConfinement.validateSinglyLinkedFile(target, "immutable output");
        } catch (FileAlreadyExistsException error) {
            throw new IllegalArgumentException("immutable output collision: " + target, error);
        } catch (IOException error) {
            throw new IllegalArgumentException("immutable output cannot be written: " + target, error);
        }
    }

    private static void requireRealOutputParent(Path parent) {
        Path absolute = parent.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(absolute)) {
                throw new IllegalArgumentException("output parent must be a real directory: " + parent);
            }
            Path real = absolute.toRealPath();
            if (!real.equals(absolute)) {
                throw new IllegalArgumentException("output parent contains a symlink: " + parent);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("output parent cannot be inspected: " + parent, error);
        }
    }

    private static void rejectLinkedOutputAncestors(Path parent) {
        Path absolute = parent.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        if (cursor == null) throw new IllegalArgumentException("output parent has no root");
        for (Path component : absolute) {
            cursor = cursor.resolve(component.toString());
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(cursor)
                    || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("output parent is not a real directory: " + parent);
            }
        }
    }
}
