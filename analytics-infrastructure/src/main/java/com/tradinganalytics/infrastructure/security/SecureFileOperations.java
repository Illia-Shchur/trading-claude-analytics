package com.tradinganalytics.infrastructure.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Exclusive immutable creation and same-directory atomic replacement beneath an approved root. */
public final class SecureFileOperations {
    private SecureFileOperations() {}

    public static Path writeImmutable(Path root, String relativePath, byte[] bytes) {
        Target target = prepareTarget(root, relativePath, "immutable output");
        if (Files.exists(target.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertExistingBytes(target.path(), bytes, "immutable output collision");
            return target.path();
        }
        try {
            writeExclusive(target.path(), bytes);
        } catch (FileAlreadyExistsException competitor) {
            assertExistingBytes(target.path(), bytes, "immutable output collision");
        } catch (IOException error) {
            throw new CustodyException("immutable output cannot be created: " + target.path(), error);
        }
        assertExistingBytes(target.path(), bytes, "immutable output verification failed");
        forceDirectory(target.path().getParent());
        return target.path();
    }

    public static Path atomicReplace(Path root, String relativePath, byte[] bytes) {
        Target target = prepareTarget(root, relativePath, "atomic output");
        if (Files.exists(target.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertExclusiveRegularFile(target.path(), "atomic output");
        }
        String temporaryName = "." + target.path().getFileName() + ".tmp-" + UUID.randomUUID();
        Path temporary = target.path().resolveSibling(temporaryName);
        try {
            writeExclusive(temporary, bytes);
            try {
                Files.move(temporary, target.path(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new CustodyException("atomic output filesystem does not support atomic rename", unsupported);
            }
            forceDirectory(target.path().getParent());
        } catch (CustodyException error) {
            deleteTemporary(temporary);
            throw error;
        } catch (IOException error) {
            deleteTemporary(temporary);
            throw new CustodyException("atomic output cannot be published: " + target.path(), error);
        }
        assertExistingBytes(target.path(), bytes, "atomic output verification failed");
        return target.path();
    }

    private static Target prepareTarget(Path root, String relativePath, String label) {
        String relative = PathConfinement.repositoryRelativePath(relativePath, label);
        Path base = PathConfinement.requireRealDirectory(root, "approved root");
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new CustodyException(label + " escapes its approved root");
        }
        Path relativeParent = base.relativize(target.getParent());
        Path cursor = base;
        for (Path component : relativeParent) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(cursor);
                } catch (FileAlreadyExistsException ignored) {
                    // A competing creator is inspected below.
                } catch (IOException error) {
                    throw new CustodyException(label + " parent cannot be created", error);
                }
            }
            var attributes = PathConfinement.attributes(cursor, label + " parent");
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new CustodyException(label + " parent contains a symlink or non-directory component");
            }
        }
        return new Target(base, target);
    }

    private static void writeExclusive(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            while (input.hasRemaining()) {
                channel.write(input);
            }
            channel.force(true);
        }
    }

    private static void assertExistingBytes(Path path, byte[] expected, String label) {
        assertExclusiveRegularFile(path, label);
        byte[] actual = PathConfinement.readSinglyLinkedFile(path, label);
        if (!Arrays.equals(actual, expected)) {
            throw new CustodyException(label + ": " + path);
        }
    }

    private static void assertExclusiveRegularFile(Path path, String label) {
        var attributes = PathConfinement.attributes(path, label);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new CustodyException(label + " is not a regular, singly-linked file");
        }
        PathConfinement.requireSingleLink(path, label);
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The file itself has already been fsynced; not all platforms permit directory channels.
        }
    }

    private static void deleteTemporary(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The original failure is more actionable; a unique temp cannot replace the destination.
        }
    }

    private record Target(Path root, Path path) {}
}
