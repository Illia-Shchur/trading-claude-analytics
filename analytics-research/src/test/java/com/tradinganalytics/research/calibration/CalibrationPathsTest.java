package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationPathsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void repositoryRootUsesTheJavaReactorInsteadOfAJavaScriptMarker() throws Exception {
        Path checkout = temporaryDirectory.resolve("checkout");
        Files.createDirectories(checkout.resolve("analytics-research"));
        Files.createDirectories(checkout.resolve("reports"));
        Files.writeString(checkout.resolve("pom.xml"), "<project/>\n");
        Files.writeString(checkout.resolve("analytics-research/pom.xml"), "<project/>\n");
        Path nested = Files.createDirectories(checkout.resolve("one/two/three"));

        assertThat(CalibrationPaths.repositoryRoot(nested)).isEqualTo(checkout);
    }
}
