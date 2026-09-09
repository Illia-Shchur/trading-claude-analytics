package com.tradinganalytics.infrastructure.build;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * Reports the identity of the executable which is actually running.
 *
 * <p>The marker is generated before packaging and deliberately does not contain the
 * final jar digest.  The latter is reported alongside it, avoiding a self-referential
 * digest while still binding a result to the compiled executable and its inputs.</p>
 */
public final class BuildIdentityService {
    private static final String RESOURCE = "META-INF/build-identity.properties";

    private BuildIdentityService() {}

    public static ObjectNode describe(Class<?> anchor) {
        Path codeSource = codeSource(anchor);
        Properties marker = readMarker(anchor);
        ObjectNode result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        result.put("schema", "analytics-build-identity/1");
        result.put("status", marker.isEmpty() ? "UNKNOWN" : "KNOWN");

        ObjectNode executable = result.putObject("executable");
        executable.put("code_source", codeSource.toString());
        if (Files.isRegularFile(codeSource)) {
            executable.put("kind", "JAR");
            executable.put("sha256", sha256(codeSource));
            try { executable.put("bytes", Files.size(codeSource)); }
            catch (IOException error) { executable.putNull("bytes"); }
        } else {
            executable.put("kind", "CLASSES");
            executable.putNull("sha256");
            executable.putNull("bytes");
        }

        ObjectNode compiled = result.putObject("compiled");
        copy(marker, compiled, "build.input_fingerprint", "input_fingerprint");
        copy(marker, compiled, "build.revision", "revision");
        copy(marker, compiled, "build.dirty_source", "dirty_source");
        copy(marker, compiled, "build.java_version", "java_version");
        copy(marker, compiled, "build.java_vendor", "java_vendor");
        copy(marker, compiled, "build.java_runtime", "java_runtime");
        copy(marker, compiled, "build.toolchain_fingerprint", "toolchain_fingerprint");
        copy(marker, compiled, "build.maven_wrapper_sha256", "maven_wrapper_sha256");
        copy(marker, compiled, "build.root_pom_sha256", "root_pom_sha256");
        compiled.put("marker_resource", RESOURCE);

        ObjectNode runtime = result.putObject("runtime");
        runtime.put("java", System.getProperty("java.version", "unknown"));
        runtime.put("cwd", Path.of("").toAbsolutePath().normalize().toString());
        runtime.put("user_dir", System.getProperty("user.dir", "unknown"));
        return result;
    }

    private static void copy(Properties source, ObjectNode target, String sourceKey, String targetKey) {
        String value = source.getProperty(sourceKey);
        if (value == null || value.isBlank()) target.putNull(targetKey);
        else target.put(targetKey, value);
    }

    private static Properties readMarker(Class<?> anchor) {
        Properties marker = new Properties();
        try (InputStream stream = anchor.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream != null) marker.load(stream);
        } catch (IOException ignored) {
            marker.clear();
        }
        return marker;
    }

    private static Path codeSource(Class<?> anchor) {
        try {
            URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
            String raw = location.toString();
            int bang = raw.indexOf('!');
            if (bang >= 0) raw = raw.substring(0, bang);
            if (raw.startsWith("jar:")) raw = raw.substring(4);
            if (raw.startsWith("nested:")) raw = "file:" + raw.substring("nested:".length());
            return Path.of(URI.create(raw)).toAbsolutePath().normalize();
        } catch (Exception error) {
            return Path.of("unknown").toAbsolutePath().normalize();
        }
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            return "UNAVAILABLE";
        }
    }
}
