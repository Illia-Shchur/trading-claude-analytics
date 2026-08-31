package com.tradinganalytics.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportingFilesTest {
    @TempDir Path temporaryDirectory;

    @Test
    void repositoryRootIsDiscoveredFromAnyNestedWorkingDirectory() throws Exception {
        Path checkout = temporaryDirectory.resolve("checkout");
        Files.createDirectories(checkout.resolve("analytics-reporting"));
        Files.createDirectories(checkout.resolve("reports"));
        Files.createDirectories(checkout.resolve("schemas"));
        Files.writeString(checkout.resolve("pom.xml"), "<project/>\n");
        Files.writeString(checkout.resolve("analytics-reporting/pom.xml"), "<project/>\n");
        Path nested = Files.createDirectories(checkout.resolve("one/two/three"));

        assertThat(ReportingFiles.repositoryRoot(nested)).isEqualTo(checkout);
    }
}
