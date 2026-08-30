package com.tradinganalytics.infrastructure.repository;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Locates repository-owned schemas and data independently of the caller's working directory. */
public final class RepositoryLayout {
    public static final String ROOT_ENVIRONMENT_VARIABLE = "TRADING_ANALYTICS_ROOT";

    private RepositoryLayout() {
    }

    public static Path locate() {
        try {
            return locate(System.getenv(ROOT_ENVIRONMENT_VARIABLE), Path.of(System.getProperty("user.dir")),
                    RepositoryLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException("invalid application code location", error);
        }
    }

    static Path locate(String configuredRoot, Path workingDirectory, URI codeLocation) {
        var starts = new ArrayList<Path>();
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
            if (!isRepositoryRoot(configured)) {
                throw new IllegalStateException(ROOT_ENVIRONMENT_VARIABLE
                        + " does not identify a Trading Analytics repository: " + configured);
            }
            return configured;
        }
        if (codeLocation != null && "file".equalsIgnoreCase(codeLocation.getScheme())) {
            Path codePath = Path.of(codeLocation).toAbsolutePath().normalize();
            starts.add(Files.isDirectory(codePath) ? codePath : codePath.getParent());
        }
        starts.add(workingDirectory.toAbsolutePath().normalize());

        for (Path start : starts) {
            for (Path current = start; current != null; current = current.getParent()) {
                if (isRepositoryRoot(current)) {
                    return current;
                }
            }
        }
        throw new IllegalStateException("could not locate Trading Analytics repository from " + starts);
    }

    public static boolean isRepositoryRoot(Path candidate) {
        return candidate != null
                && Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isDirectory(candidate.resolve("schemas"));
    }
}
