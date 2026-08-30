package com.tradinganalytics.infrastructure.security;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Rejects links and special files, with optional bounded JSON-only evidence custody. */
public final class SafeTreeVerifier {
    public record Options(CustodyLimits limits, boolean evidenceOnly) {
        public static final Options EVIDENCE = new Options(CustodyLimits.DEFAULT, true);
        public static final Options REPOSITORY = new Options(CustodyLimits.DEFAULT, false);

        public Options {
            if (limits == null) {
                throw new CustodyException("evidence custody limits are required");
            }
        }
    }

    public record TreeSummary(int files, long totalBytes) {}

    private SafeTreeVerifier() {}

    public static TreeSummary verify(Path root) {
        return verify(root, "evidence tree", Options.EVIDENCE);
    }

    public static TreeSummary verify(Path root, String label, Options options) {
        Path base = PathConfinement.requireRealDirectory(root, label);
        Counter counter = new Counter();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw new CustodyException(display(base, directory, label) + " is not a real directory");
                    }
                    assertInside(base, directory, label);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String display = display(base, file, label);
                    if (attributes.isSymbolicLink()) {
                        throw new CustodyException(display + " is a symlink");
                    }
                    if (!attributes.isRegularFile()) {
                        throw new CustodyException(display + " is not a regular, singly-linked file");
                    }
                    PathConfinement.requireSingleLink(file, display);
                    assertInside(base, file, label);
                    counter.files++;
                    if (options.evidenceOnly()) {
                        if (counter.files > options.limits().maxFiles()) {
                            throw new CustodyException(label + " exceeds the file-count ceiling");
                        }
                        String relative = base.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                        EvidenceContentValidator.validateFilename(relative, label);
                        if (attributes.size() > options.limits().maxFileBytes()) {
                            throw new CustodyException(label + " file exceeds the per-file byte ceiling: " + relative);
                        }
                        if (counter.totalBytes + attributes.size() > options.limits().maxTotalBytes()) {
                            throw new CustodyException(label + " tree exceeds the total byte ceiling");
                        }
                        byte[] bytes = PathConfinement.readSinglyLinkedFile(file, display);
                        counter.totalBytes += bytes.length;
                        EvidenceContentValidator.validateBytes(bytes, label, relative);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    throw new CustodyException(display(base, file, label) + " cannot be inspected", error);
                }
            });
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be traversed", error);
        }
        return new TreeSummary(counter.files, counter.totalBytes);
    }

    private static void assertInside(Path base, Path path, String label) {
        try {
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(base)) {
                throw new CustodyException(display(base, path, label) + " resolves outside its approved root");
            }
        } catch (IOException error) {
            throw new CustodyException(display(base, path, label) + " cannot be resolved", error);
        }
    }

    private static String display(Path base, Path path, String label) {
        Path relative = base.relativize(path);
        return relative.toString().isEmpty() ? label : label + "/" + relative;
    }

    private static final class Counter {
        int files;
        long totalBytes;
    }
}
