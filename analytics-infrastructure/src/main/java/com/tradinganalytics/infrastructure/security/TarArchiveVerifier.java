package com.tradinganalytics.infrastructure.security;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Inspects every tar member and bounded byte before callers are allowed to extract it. */
public final class TarArchiveVerifier {
    private TarArchiveVerifier() {}

    public static SafeTreeVerifier.TreeSummary verify(Path archive) {
        return verify(archive, "evidence archive", SafeTreeVerifier.Options.EVIDENCE);
    }

    public static SafeTreeVerifier.TreeSummary verify(
            Path archive, String label, SafeTreeVerifier.Options options) {
        PathConfinement.validateSinglyLinkedFile(archive, label);
        try {
            long size = Files.size(archive);
            if (size < 1_024 || size % 512 != 0) {
                throw new CustodyException(label + " is not a complete tar archive");
            }
        } catch (CustodyException error) {
            throw error;
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be inspected", error);
        }
        int files = 0;
        long totalBytes = 0;
        Set<String> names = new HashSet<>();
        try (InputStream source = Files.newInputStream(archive);
             TarArchiveInputStream tar = new TarArchiveInputStream(source)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) {
                    throw new CustodyException(label + " contains an unreadable entry");
                }
                String name = normalizedMember(entry.getName());
                if (!name.isEmpty()) {
                    PathConfinement.repositoryRelativePath(name, label + " member");
                    if (!names.add(name)) {
                        throw new CustodyException(label + " contains a duplicate member: " + name);
                    }
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()
                        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new CustodyException(label + " contains a non-regular entry: " + name);
                }
                files++;
                if (options.evidenceOnly()) {
                    if (files > options.limits().maxFiles()) {
                        throw new CustodyException(label + " exceeds the file-count ceiling");
                    }
                    EvidenceContentValidator.validateFilename(name, label);
                    if (entry.getSize() < 0 || entry.getSize() > options.limits().maxFileBytes()) {
                        throw new CustodyException(label + " member exceeds the per-file byte ceiling: " + name);
                    }
                    byte[] bytes = readBounded(tar, options.limits().maxFileBytes(), label, name);
                    totalBytes += bytes.length;
                    if (totalBytes > options.limits().maxTotalBytes()) {
                        throw new CustodyException(label + " exceeds the total byte ceiling");
                    }
                    EvidenceContentValidator.validateBytes(bytes, label, name);
                }
            }
        } catch (CustodyException error) {
            throw error;
        } catch (IOException error) {
            throw new CustodyException(label + " cannot be inspected", error);
        }
        return new SafeTreeVerifier.TreeSummary(files, totalBytes);
    }

    private static byte[] readBounded(TarArchiveInputStream tar, long maximum, String label, String name)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximum, 8_192));
        byte[] buffer = new byte[8_192];
        long total = 0;
        int count;
        while ((count = tar.read(buffer)) != -1) {
            total += count;
            if (total > maximum) {
                throw new CustodyException(label + " member exceeds the per-file byte ceiling: " + name);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String normalizedMember(String value) {
        String name = value == null ? "" : value;
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.endsWith("/") && !name.isEmpty()) {
            name = name.substring(0, name.length() - 1);
        }
        return name.equals(".") ? "" : name;
    }
}
