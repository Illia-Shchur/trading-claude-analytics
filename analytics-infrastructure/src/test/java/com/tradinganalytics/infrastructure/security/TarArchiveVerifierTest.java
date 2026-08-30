package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TarArchiveVerifierTest {
    @TempDir Path temporary;

    @Test
    void acceptsBoundedJsonAndOptionLookingMemberWithoutExecutingIt() throws IOException {
        Path archive = archive("safe.tar", List.of(
                Member.file("./safe.json", "{}\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("--checkpoint-action=exec=touch PWN.json", "{}\n".getBytes(StandardCharsets.UTF_8))));

        assertThat(TarArchiveVerifier.verify(archive))
                .isEqualTo(new SafeTreeVerifier.TreeSummary(2, 6));
        assertThat(Files.exists(temporary.resolve("PWN.json"))).isFalse();
    }

    @Test
    void permitsRepositoryArchiveContentOnlyWhenEvidenceScanningIsExplicitlyDisabled() throws IOException {
        Path archive = archive("repository.tar", List.of(
                Member.file("README.md", "# source\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("tools/main.mjs", "export {}\n".getBytes(StandardCharsets.UTF_8))));
        assertThat(TarArchiveVerifier.verify(archive, "repository archive",
                SafeTreeVerifier.Options.REPOSITORY).files()).isEqualTo(2);
        assertThatThrownBy(() -> TarArchiveVerifier.verify(archive))
                .hasMessageContaining("non-JSON");
    }

    @Test
    void rejectsEveryHostileEvidencePayloadBeforeExtraction() throws IOException {
        List<Member> hostile = List.of(
                Member.file("large.json", "{}\nX".getBytes(StandardCharsets.UTF_8)),
                Member.file("notes.md", "# untrusted\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("private.json", "{}\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("receipt.json", "{\"payload\":\"-----BEGIN PRIVATE KEY-----\"}\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("receipt.json", "{\"payload\":\"-----BEGIN RSA PRIVATE KEY-----\"}\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("receipt.json", "{\"payload\":\"-----BEGIN PUBLIC KEY-----\"}\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("receipt.json", new byte[] {'{', (byte) 0xff, '}'}),
                Member.file("receipt.json", withNul("{\"payload\":\"ok\"}")),
                Member.file("receipt.json", "not-json\n".getBytes(StandardCharsets.UTF_8)),
                Member.file("receipt.json", "{\"x\":1,\"x\":2}\n".getBytes(StandardCharsets.UTF_8)));

        for (int index = 0; index < hostile.size(); index++) {
            Member member = hostile.get(index);
            Path archive = archive("hostile-" + index + ".tar", List.of(member));
            SafeTreeVerifier.Options options = index == 0
                    ? new SafeTreeVerifier.Options(new CustodyLimits(10, 3, 100), true)
                    : SafeTreeVerifier.Options.EVIDENCE;
            assertThatThrownBy(() -> TarArchiveVerifier.verify(archive, "proposed evidence archive", options))
                    .as(member.name()).isInstanceOf(CustodyException.class);
        }
    }

    @Test
    void rejectsTraversalAbsoluteControlDuplicateAndLinkEntries() throws IOException {
        List<List<Member>> cases = List.of(
                List.of(Member.file("../escape.json", json())),
                List.of(Member.file("/absolute.json", json())),
                List.of(Member.file("bad" + (char) 1 + ".json", json())),
                List.of(Member.file("same.json", json()), Member.file("same.json", json())),
                List.of(Member.symbolicLink("escape.json", "../outside.json")),
                List.of(Member.hardLink("alias.json", "target.json")),
                List.of(Member.special("pipe.json", TarArchiveEntry.LF_FIFO)));

        for (int index = 0; index < cases.size(); index++) {
            Path archive = archive("entry-" + index + ".tar", cases.get(index));
            assertThatThrownBy(() -> TarArchiveVerifier.verify(archive))
                    .as("case " + index).isInstanceOf(CustodyException.class)
                    .hasMessageMatching("(?i).*(relative|traversal|non-regular|duplicate|control|path).*" );
        }
    }

    @Test
    void enforcesArchiveFileCountAndTotalBytesIndependently() throws IOException {
        Path archive = archive("limits.tar", List.of(
                Member.file("a.json", json()), Member.file("b.json", json())));
        assertThatThrownBy(() -> TarArchiveVerifier.verify(archive, "archive",
                new SafeTreeVerifier.Options(new CustodyLimits(1, 10, 20), true)))
                .hasMessageContaining("file-count");
        assertThatThrownBy(() -> TarArchiveVerifier.verify(archive, "archive",
                new SafeTreeVerifier.Options(new CustodyLimits(3, 10, 3), true)))
                .hasMessageContaining("total");
    }

    @Test
    void rejectsSymlinkAndHardlinkedArchiveFilesThemselves() throws IOException {
        Path archive = archive("base.tar", List.of(Member.file("safe.json", json())));
        Path symlink = temporary.resolve("linked.tar");
        Files.createSymbolicLink(symlink, archive);
        assertThatThrownBy(() -> TarArchiveVerifier.verify(symlink))
                .hasMessageContaining("regular");

        Path hardlink = temporary.resolve("hardlinked.tar");
        Files.createLink(hardlink, archive);
        assertThatThrownBy(() -> TarArchiveVerifier.verify(hardlink))
                .hasMessageContaining("singly-linked");
    }

    @Test
    void rejectsTruncatedBytesThatCommonsCompressWouldOtherwiseTreatAsEndOfArchive()
            throws IOException {
        Path truncated = temporary.resolve("truncated.tar");
        Files.writeString(truncated, "not a tar archive", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TarArchiveVerifier.verify(truncated))
                .isInstanceOf(CustodyException.class)
                .hasMessageContaining("complete tar archive");
    }

    private Path archive(String name, List<Member> members) throws IOException {
        Path archive = temporary.resolve(name);
        try (OutputStream bytes = Files.newOutputStream(archive);
             TarArchiveOutputStream output = new TarArchiveOutputStream(bytes)) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Member member : members) {
                TarArchiveEntry entry = member.type() == TarArchiveEntry.LF_NORMAL
                        ? new TarArchiveEntry(member.name(), true)
                        : new TarArchiveEntry(member.name(), member.type(), true);
                if (member.type() == TarArchiveEntry.LF_NORMAL) entry.setSize(member.bytes().length);
                if (member.linkName() != null) entry.setLinkName(member.linkName());
                output.putArchiveEntry(entry);
                if (member.type() == TarArchiveEntry.LF_NORMAL) output.write(member.bytes());
                output.closeArchiveEntry();
            }
            output.finish();
        }
        return archive;
    }

    private static byte[] json() { return "{}\n".getBytes(StandardCharsets.UTF_8); }

    private static byte[] withNul(String prefix) {
        byte[] input = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] result = java.util.Arrays.copyOf(input, input.length + 1);
        result[result.length - 1] = 0;
        return result;
    }

    private record Member(String name, byte type, String linkName, byte[] bytes) {
        static Member file(String name, byte[] bytes) {
            return new Member(name, TarArchiveEntry.LF_NORMAL, null, bytes);
        }
        static Member symbolicLink(String name, String target) {
            return new Member(name, TarArchiveEntry.LF_SYMLINK, target, new byte[0]);
        }
        static Member hardLink(String name, String target) {
            return new Member(name, TarArchiveEntry.LF_LINK, target, new byte[0]);
        }
        static Member special(String name, byte type) {
            return new Member(name, type, null, new byte[0]);
        }
    }
}
