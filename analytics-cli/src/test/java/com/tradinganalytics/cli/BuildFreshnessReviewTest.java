package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent reviewer regressions; no recursive Maven invocation or production-data mutation. */
class BuildFreshnessReviewTest {
    @TempDir Path temporary;

    @Test
    void sourceBytesChangeIdentityEvenWhenModificationTimeIsPreserved() throws Exception {
        Path root = fixture();
        String before = marker(root).getProperty("build.input_fingerprint");
        replacePreservingTime(root.resolve("analytics-probe/src/main/java/Source With Spaces.java"), "changed\n");
        assertThat(marker(root).getProperty("build.input_fingerprint")).isNotEqualTo(before);
    }

    @Test
    void topLevelCompiledSchemaBytesChangeIdentityWithoutTimestampChange() throws Exception {
        Path root = fixture();
        String before = marker(root).getProperty("build.input_fingerprint");
        replacePreservingTime(root.resolve("schemas/probe.json"), "{\"description\":\"changed\"}\n");
        assertThat(marker(root).getProperty("build.input_fingerprint")).isNotEqualTo(before);
    }

    @Test
    void stagedFilenameWithSpacesIsReportedAsDirty() throws Exception {
        Path root = fixture();
        initializeGit(root);
        assertThat(marker(root).getProperty("build.dirty_source")).isEqualTo("false");
        replacePreservingTime(root.resolve("analytics-probe/src/main/java/Source With Spaces.java"), "staged\n");
        assertSuccess(run(root, "git", "add", "analytics-probe/src/main/java/Source With Spaces.java"));
        assertThat(marker(root).getProperty("build.dirty_source")).isEqualTo("true");
    }

    @Test
    void deletedSourceChangesIdentityAndCannotBeReportedClean() throws Exception {
        Path root = fixture();
        initializeGit(root);
        String before = marker(root).getProperty("build.input_fingerprint");
        Files.delete(root.resolve("analytics-probe/src/main/java/Source With Spaces.java"));
        Properties after = marker(root);
        assertThat(after.getProperty("build.input_fingerprint")).isNotEqualTo(before);
        assertThat(after.getProperty("build.dirty_source")).isEqualTo("true");
    }

    @Test
    void mavenConfigurationParticipatesInIdentity() throws Exception {
        Path root = fixture();
        String before = marker(root).getProperty("build.input_fingerprint");
        Files.writeString(root.resolve(".mvn/maven.config"), "-Dcompiler.setting=changed\n");
        assertThat(marker(root).getProperty("build.input_fingerprint")).isNotEqualTo(before);
    }

    @Test
    void emptyInterruptedLockRecoversAndFailedBuildNeverRunsStaleJar() throws Exception {
        checkFailedBuildAfterInterruptedLock(false);
    }

    @Test
    void deadOwnerLockRecoversAndFailedBuildNeverRunsStaleJar() throws Exception {
        checkFailedBuildAfterInterruptedLock(true);
    }

    private void checkFailedBuildAfterInterruptedLock(boolean withDeadOwner) throws Exception {
        Path root = fixture();
        Path jar = root.resolve("analytics-cli/target/analytics-cli-1.0.0-SNAPSHOT-exec.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/META-INF/build-identity.properties"));
            zip.write("build.input_fingerprint=stale\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] staleBytes = Files.readAllBytes(jar);
        Path lock = Files.createDirectory(root.resolve(".analytics-build.lock"));
        if (withDeadOwner) Files.writeString(lock.resolve("pid"), "99999999\n");
        // Deliberately retains the stale JAR. Exit 42 proves the launcher stops at build failure.
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\nprintf 'expected build failure\\n'\nexit 42\n");
        assertThat(root.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        Result result = run(root, "sh", "bin/analytics", "build-identity");
        assertThat(result.exit()).as(result.err()).isEqualTo(42);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("expected build failure");
        assertThat(Files.exists(lock)).isFalse();
        assertThat(Files.readAllBytes(jar)).isEqualTo(staleBytes);
    }

    private Path fixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository with spaces"));
        for (String directory : List.of("tools", "bin", "schemas", ".mvn", "analytics-probe/src/main/java")) {
            Files.createDirectories(root.resolve(directory));
        }
        Path repository = RepositoryLayout.locate();
        Files.copy(repository.resolve("tools/build-input-identity.sh"), root.resolve("tools/build-input-identity.sh"));
        assertThat(root.resolve("tools/build-input-identity.sh").toFile().setExecutable(true)).isTrue();
        Files.copy(repository.resolve("bin/analytics"), root.resolve("bin/analytics"));
        for (String file : List.of("pom.xml", "analytics-probe/pom.xml", "mvnw", "mvnw.cmd")) {
            Files.writeString(root.resolve(file), "fixture\n");
        }
        Files.writeString(root.resolve("analytics-probe/src/main/java/Source With Spaces.java"), "initial\n");
        Files.writeString(root.resolve("schemas/probe.json"), "{}\n");
        Files.writeString(root.resolve(".mvn/maven.config"), "\n");
        return root;
    }

    private void initializeGit(Path root) throws Exception {
        assertSuccess(run(root, "git", "init", "-q"));
        assertSuccess(run(root, "git", "add", "."));
        assertSuccess(run(root, "git", "-c", "user.name=Review", "-c", "user.email=review@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-qm", "review fixture"));
    }

    private Properties marker(Path root) throws Exception {
        Result result = run(root, "sh", "tools/build-input-identity.sh", root.toString());
        assertSuccess(result);
        Properties properties = new Properties();
        properties.load(new StringReader(result.out()));
        assertThat(properties.getProperty("build.input_fingerprint")).matches("[a-f0-9]{64}");
        return properties;
    }

    private static void replacePreservingTime(Path file, String contents) throws Exception {
        FileTime time = Files.getLastModifiedTime(file);
        Files.writeString(file, contents);
        Files.setLastModifiedTime(file, time);
    }

    private Result run(Path root, String... command) throws Exception {
        Path out = temporary.resolve("process-out.txt"), err = temporary.resolve("process-err.txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        for (String name : List.of("ANALYTICS_REPO_ROOT", "ANALYTICS_JAR", "ANALYTICS_MAVEN",
                "BUILD_INPUT_IDENTITY_FINGERPRINT_ONLY")) builder.environment().remove(name);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("command timed out: " + String.join(" ", command));
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static void assertSuccess(Result result) {
        assertThat(result.exit()).as(result.err()).isZero();
    }

    private record Result(int exit, String out, String err) {}
}
