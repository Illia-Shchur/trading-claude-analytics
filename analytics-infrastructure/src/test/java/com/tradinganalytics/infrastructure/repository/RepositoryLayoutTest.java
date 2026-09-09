package com.tradinganalytics.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryLayoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configuredRootWinsAndMustBeValid() throws Exception {
        Path repository = repositoryAt(temporaryDirectory.resolve("configured"));
        assertThat(RepositoryLayout.locate(
                repository.toString(), temporaryDirectory, new URI("file:///unrelated/app.jar")))
                .isEqualTo(repository.toAbsolutePath().normalize());

        assertThatThrownBy(() -> RepositoryLayout.locate(
                temporaryDirectory.resolve("missing").toString(), temporaryDirectory, null))
                .hasMessageContaining(RepositoryLayout.ROOT_ENVIRONMENT_VARIABLE);
    }

    @Test
    void walksUpFromCodeLocationBeforeWorkingDirectory() throws Exception {
        Path codeRepository = repositoryAt(temporaryDirectory.resolve("code-repo"));
        Path workingRepository = repositoryAt(temporaryDirectory.resolve("working-repo"));
        Path classes = Files.createDirectories(codeRepository.resolve("analytics-cli/target/classes"));

        assertThat(RepositoryLayout.locate(null, workingRepository.resolve("nested"), classes.toUri()))
                .isEqualTo(codeRepository);
    }

    @Test
    void walksUpFromWorkingDirectoryAsFallback() throws Exception {
        Path repository = repositoryAt(temporaryDirectory.resolve("repo"));
        Path nested = Files.createDirectories(repository.resolve("data/runs/example"));

        assertThat(RepositoryLayout.locate(null, nested, new URI("memory:///classes")))
                .isEqualTo(repository);
    }

    @Test
    void failsWhenNoRepositoryCanBeFound() {
        assertThatThrownBy(() -> RepositoryLayout.locate(null, temporaryDirectory, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not locate");
    }

    private static Path repositoryAt(Path path) throws Exception {
        Files.createDirectories(path.resolve("schemas"));
        Files.writeString(path.resolve("pom.xml"), "<project/>");
        return path.toAbsolutePath().normalize();
    }
}
