package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SecureOperationsTest {
    @TempDir Path temporary;

    @Test
    void immutableWriteIsExclusiveIdempotentAndCollisionSafe() throws IOException {
        byte[] expected = "immutable\n".getBytes(StandardCharsets.UTF_8);
        Path output = SecureFileOperations.writeImmutable(temporary, "nested/output.json", expected);
        assertThat(Files.readAllBytes(output)).isEqualTo(expected);
        assertThat(SecureFileOperations.writeImmutable(temporary, "nested/output.json", expected))
                .isEqualTo(output);
        assertThatThrownBy(() -> SecureFileOperations.writeImmutable(
                temporary, "nested/output.json", "different\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("collision");
        assertThatThrownBy(() -> SecureFileOperations.writeImmutable(
                temporary, "../escape.json", expected))
                .isInstanceOf(CustodyException.class)
                .hasMessageMatching("(?i).*(relative|traversal).*" );
    }

    @Test
    void immutableWriteRejectsExistingSymlinkHardlinkAndLinkedParent() throws IOException {
        byte[] expected = "{}\n".getBytes(StandardCharsets.UTF_8);
        Path outside = temporary.resolve("outside.json");
        Files.write(outside, expected);
        Files.createSymbolicLink(temporary.resolve("symlink.json"), outside);
        assertThatThrownBy(() -> SecureFileOperations.writeImmutable(temporary, "symlink.json", expected))
                .isInstanceOf(CustodyException.class).hasMessageContaining("regular");

        Path original = temporary.resolve("original.json");
        Files.write(original, expected);
        Files.createLink(temporary.resolve("hardlink.json"), original);
        assertThatThrownBy(() -> SecureFileOperations.writeImmutable(temporary, "hardlink.json", expected))
                .isInstanceOf(CustodyException.class).hasMessageContaining("singly-linked");

        Path realParent = Files.createDirectory(temporary.resolve("real-parent"));
        Files.createSymbolicLink(temporary.resolve("linked-parent"), realParent);
        assertThatThrownBy(() -> SecureFileOperations.writeImmutable(
                temporary, "linked-parent/output.json", expected))
                .isInstanceOf(CustodyException.class).hasMessageContaining("parent");
    }

    @Test
    void atomicReplacePublishesWholeFileAndLeavesNoTemporary() throws IOException {
        Files.writeString(temporary.resolve("index.json"), "old\n");
        byte[] replacement = "new complete value\n".getBytes(StandardCharsets.UTF_8);
        Path output = SecureFileOperations.atomicReplace(temporary, "index.json", replacement);

        assertThat(Files.readAllBytes(output)).isEqualTo(replacement);
        try (var entries = Files.list(temporary)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("index.json");
        }
    }

    @Test
    void atomicReplaceWillNotFollowExistingLinkTargets() throws IOException {
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "outside\n");
        Files.createDirectory(temporary.resolve("root"));
        Files.createSymbolicLink(temporary.resolve("root/index.json"), outside);

        assertThatThrownBy(() -> SecureFileOperations.atomicReplace(
                temporary.resolve("root"), "index.json", "inside\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("regular");
        assertThat(Files.readString(outside)).isEqualTo("outside\n");
    }

    @Test
    void boundedProcessReturnsExactStreamsAndNonzeroExit() {
        BoundedProcessExecutor.Result result = BoundedProcessExecutor.execute(
                List.of("/bin/sh", "-c", "printf out; printf err >&2; exit 7"),
                temporary, new BoundedProcessExecutor.Limits(Duration.ofSeconds(2), 100, 100));
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.stdoutUtf8()).isEqualTo("out");
        assertThat(result.stderrUtf8()).isEqualTo("err");
        byte[] copy = result.stdout();
        copy[0] = 'X';
        assertThat(result.stdoutUtf8()).isEqualTo("out");
    }

    @Test
    void boundedProcessEnforcesTimeoutAndEachOutputCeiling() {
        assertThatThrownBy(() -> BoundedProcessExecutor.execute(
                List.of("/bin/sh", "-c", "sleep 2"), temporary,
                new BoundedProcessExecutor.Limits(Duration.ofMillis(50), 100, 100)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("timeout");

        assertThatThrownBy(() -> BoundedProcessExecutor.execute(
                List.of("/bin/sh", "-c", "i=0; while [ $i -lt 200 ]; do printf x; i=$((i+1)); done"),
                temporary, new BoundedProcessExecutor.Limits(Duration.ofSeconds(2), 10, 100)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("stdout");

        assertThatThrownBy(() -> BoundedProcessExecutor.execute(
                List.of("/bin/sh", "-c", "i=0; while [ $i -lt 200 ]; do printf x >&2; i=$((i+1)); done"),
                temporary, new BoundedProcessExecutor.Limits(Duration.ofSeconds(2), 100, 10)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("stderr");
    }

    @Test
    void boundedProcessValidatesRequestsAndPassesExplicitEnvironment() {
        BoundedProcessExecutor.Result environment = BoundedProcessExecutor.execute(
                List.of("/bin/sh", "-c", "printf %s \"$BOUNDARY_TEST_VALUE\""), temporary,
                Map.of("BOUNDARY_TEST_VALUE", "present"),
                new BoundedProcessExecutor.Limits(Duration.ofSeconds(2), 100, 100));
        assertThat(environment.stdoutUtf8()).isEqualTo("present");

        assertThatThrownBy(() -> new BoundedProcessExecutor.Limits(Duration.ZERO, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedProcessExecutor.Limits(Duration.ofSeconds(1), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedProcessExecutor.execute(
                List.of(), temporary, BoundedProcessExecutor.Limits.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedProcessExecutor.execute(
                List.of("/definitely/missing/program"), temporary,
                new BoundedProcessExecutor.Limits(Duration.ofSeconds(1), 1, 1)))
                .isInstanceOf(CustodyException.class).hasMessageContaining("started");
    }
}
