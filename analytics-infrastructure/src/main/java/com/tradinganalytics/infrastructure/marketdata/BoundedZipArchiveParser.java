package com.tradinganalytics.infrastructure.marketdata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the bounded subset of ZIP archives accepted by the public data adapters.
 *
 * <p>The parser owns the archive-format bookkeeping so the adapter facade can focus on
 * translating archive rows into the canonical market-data shape. Limits are supplied by the
 * facade to keep the policy constants in one place.</p>
 */
final class BoundedZipArchiveParser {
    private BoundedZipArchiveParser() {}

    static List<PublicDataAdapters.ZipMember> parse(
            byte[] bytes,
            long maxMemberBytes,
            long hardMemberBytes,
            int maxEntries,
            long maxTotalBytes) {
        byte[] body = bytes == null ? new byte[0] : bytes.clone();
        long memberLimit = Math.min(hardMemberBytes, maxMemberBytes);
        if (memberLimit < 1) throw failure("Binance archive decompression limit is invalid");
        int eocd = findEndOfCentralDirectory(body);
        if (eocd < 0 || eocd + 22 > body.length) {
            throw failure("Binance archive is not a ZIP (missing EOCD)");
        }

        int disk = u16(body, eocd + 4);
        int centralDisk = u16(body, eocd + 6);
        int entries = u16(body, eocd + 10);
        long centralBytes = u32(body, eocd + 12);
        long centralOffset = u32(body, eocd + 16);
        long centralEnd = centralOffset + centralBytes;
        if (disk != 0 || centralDisk != 0 || entries == 0xffff || entries > maxEntries
                || centralEnd > eocd || centralEnd < centralOffset) {
            throw failure(
                    "Binance archive uses unsupported multi-disk, ZIP64, or invalid central-directory bounds");
        }

        List<PublicDataAdapters.ZipMember> output = new ArrayList<>();
        List<long[]> ranges = new ArrayList<>();
        long cursor = centralOffset;
        long total = 0;
        for (int index = 0; index < entries; index++) {
            if (cursor < centralOffset || cursor + 46 > centralEnd
                    || u32(body, (int) cursor) != 0x02014b50L) {
                throw failure("Binance archive central directory is truncated or invalid");
            }
            int method = u16(body, (int) cursor + 10);
            long expectedCrc = u32(body, (int) cursor + 16);
            long compressedSize = u32(body, (int) cursor + 20);
            long uncompressedSize = u32(body, (int) cursor + 24);
            int nameLength = u16(body, (int) cursor + 28);
            int extraLength = u16(body, (int) cursor + 30);
            int commentLength = u16(body, (int) cursor + 32);
            long localOffset = u32(body, (int) cursor + 42);
            long centralRecordEnd = cursor + 46L + nameLength + extraLength + commentLength;
            if (centralRecordEnd > centralEnd) {
                throw failure("Binance archive central-directory record exceeds its declared bounds");
            }
            String name = safeArchiveName(new String(
                    body, (int) cursor + 46, nameLength, StandardCharsets.UTF_8));
            cursor = centralRecordEnd;
            if (localOffset + 30 > body.length || u32(body, (int) localOffset) != 0x04034b50L) {
                throw failure("Binance archive local header is invalid or out of bounds: " + name);
            }
            int localNameLength = u16(body, (int) localOffset + 26);
            int localExtraLength = u16(body, (int) localOffset + 28);
            long localNameStart = localOffset + 30;
            long dataStart = localNameStart + localNameLength + localExtraLength;
            if (dataStart > body.length || dataStart > centralOffset
                    || localNameStart + localNameLength > body.length) {
                throw failure("Binance archive local header fields exceed bounds: " + name);
            }
            String localName = new String(
                    body, (int) localNameStart, localNameLength, StandardCharsets.UTF_8);
            if (!localName.equals(name)) {
                throw failure("Binance archive central/local filename mismatch: " + name);
            }
            long dataEnd = dataStart + compressedSize;
            if (dataEnd > body.length || dataEnd > centralOffset || dataEnd < dataStart) {
                throw failure("Binance archive member is truncated or overlaps the central directory: " + name);
            }
            for (long[] range : ranges) {
                if (dataStart < range[1] && dataEnd > range[0]) {
                    throw failure("Binance archive members overlap: " + name);
                }
            }
            ranges.add(new long[] {dataStart, dataEnd});
            if (uncompressedSize > memberLimit || compressedSize > memberLimit
                    || total + uncompressedSize > maxTotalBytes) {
                throw failure("Binance archive member exceeds bounded decompression limits: " + name);
            }

            byte[] compressed = java.util.Arrays.copyOfRange(
                    body, (int) dataStart, (int) dataEnd);
            byte[] content = switch (method) {
                case 0 -> compressed;
                case 8 -> inflate(compressed, Math.min(memberLimit, maxTotalBytes - total), name);
                default -> throw failure(
                        "Binance archive compression method is unsupported for " + name + ": " + method);
            };
            CRC32 crc = new CRC32();
            crc.update(content);
            if (content.length != uncompressedSize || crc.getValue() != expectedCrc) {
                throw failure("Binance archive member checksum/size mismatch: " + name);
            }
            total += content.length;
            output.add(new PublicDataAdapters.ZipMember(name, content, method, expectedCrc));
        }
        if (cursor != centralEnd) {
            throw failure("Binance archive central-directory entry count/length mismatch");
        }
        return List.copyOf(output);
    }

    private static int findEndOfCentralDirectory(byte[] body) {
        for (int index = body.length - 22;
                index >= Math.max(0, body.length - 65_557); index--) {
            if (u32(body, index) == 0x06054b50L) return index;
        }
        return -1;
    }

    private static byte[] inflate(byte[] compressed, long limit, String name) {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) break;
                if (count == 0 && inflater.needsDictionary()) throw new DataFormatException();
                if ((long) output.size() + count > limit) {
                    throw failure("Binance archive decompression output exceeds the hard limit: " + name);
                }
                output.write(buffer, 0, count);
            }
            if (!inflater.finished()) throw new DataFormatException();
            return output.toByteArray();
        } catch (DataFormatException error) {
            throw failure("Binance archive DEFLATE stream is invalid: " + name, error);
        } finally {
            inflater.end();
        }
    }

    private static String safeArchiveName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || List.of(name.split("/", -1)).contains("..")) {
            throw failure("Binance archive contains an unsafe member path: " + name);
        }
        return name;
    }

    private static long u32(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static int u16(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
