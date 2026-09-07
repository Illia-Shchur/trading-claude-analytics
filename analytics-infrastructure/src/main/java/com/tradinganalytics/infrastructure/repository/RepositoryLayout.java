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
        } else if (codeLocation != null) {
            // Spring Boot's executable JAR class loader reports a nested URL
            // such as jar:nested:/repo/app.jar/!BOOT-INF/classes/!/. Resolve
            // the outer archive so commands can locate the repository even
            // when java -jar is launched from an unrelated working directory.
            String raw = codeLocation.toString();
            int bang = raw.indexOf('!');
            if (bang >= 0) raw = raw.substring(0, bang);
            if (raw.startsWith("jar:")) raw = raw.substring("jar:".length());
            if (raw.startsWith("nested:")) raw = "file:" + raw.substring("nested:".length());
            try {
                Path codePath = Path.of(URI.create(raw)).toAbsolutePath().normalize();
                starts.add(Files.isDirectory(codePath) ? codePath : codePath.getParent());
            } catch (RuntimeException ignored) {
                // The working-directory search below remains the fallback.
            }
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
