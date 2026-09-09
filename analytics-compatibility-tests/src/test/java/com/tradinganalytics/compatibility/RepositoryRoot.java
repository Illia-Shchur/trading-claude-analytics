package com.tradinganalytics.compatibility;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryRoot {
    private RepositoryRoot() {
    }

    static Path find() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("mvnw"))
                    && Files.isRegularFile(current.resolve("docs/java-source-map.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate repository root from " + System.getProperty("user.dir"));
    }
}
