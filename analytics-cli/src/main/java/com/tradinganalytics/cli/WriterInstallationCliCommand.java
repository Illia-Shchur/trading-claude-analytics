package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Spring entry point for {@code tools/verify-evidence-writer-installation.mjs}. */
@Component
@Command(name = "verify-evidence-writer-installation",
        description = "Prove the frozen evidence-writer GitHub App installation")
public final class WriterInstallationCliCommand implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHILD_SECRET = Pattern.compile(
            "PRIVATE_KEY|PEM|PASSWORD|SECRET|(?:^|_)(?:TOKEN|JWT)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    @Spec private CommandSpec spec;

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Clock clock;
    private final ApiClient api;

    public WriterInstallationCliCommand() {
        this(Path.of(""), System.getenv(), Clock.systemUTC(), WriterInstallationCliCommand::callGh);
    }

    WriterInstallationCliCommand(
            Path workingDirectory, Map<String, String> environment, Clock clock, ApiClient api) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public Integer call() {
        try {
            String repository = environment.get("GITHUB_REPOSITORY");
            String writerToken = environment.get("GH_TOKEN");
            if (blank(repository) || blank(writerToken)) {
                throw new IllegalArgumentException(
                        "GITHUB_REPOSITORY and protected writer token are required");
            }
            long appId = positiveInteger(environment.get("V5_EVIDENCE_WRITER_APP_ID"),
                    "writer App id");
            long installationId = positiveInteger(
                    environment.get("V5_EVIDENCE_WRITER_INSTALLATION_ID"), "installation id");
            if (appId != WriterInstallationReceipts.WRITER_APP_ID
                    || installationId != WriterInstallationReceipts.WRITER_INSTALLATION_ID) {
                throw new IllegalArgumentException(
                        "writer App/installation environment must exactly match the frozen deployment identity");
            }
            String privateKeyPem = environment.get("V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM");
            if (blank(privateKeyPem)) {
                throw new IllegalArgumentException(
                        "protected writer App private key is required only in the writer job");
            }
            String jwt = appJwt(appId, privateKeyPem, clock.instant());
            long repositoryId = positiveInteger(environment.get("GITHUB_REPOSITORY_ID"),
                    "repository id");
            List<String> accept = List.of("Accept: application/vnd.github+json");
            WriterInstallationReceipts.ApiResponse repositories =
                    api.get("installation/repositories", writerToken, List.of());
            WriterInstallationReceipts.ApiResponse app = api.get("app", jwt, accept);
            WriterInstallationReceipts.ApiResponse installation = api.get(
                    "app/installations/" + WriterInstallationReceipts.WRITER_INSTALLATION_ID,
                    jwt, accept);
            JsonNode receipt = WriterInstallationReceipts.makeWriterInstallationReceipt(
                    new WriterInstallationReceipts.Request(
                            repository, repositoryId, appId,
                            environment.get("V5_EVIDENCE_WRITER_APP_SLUG"), installationId,
                            repositories, app, installation, clock.instant().toString()));
            Path output = resolve(environment.getOrDefault(
                    "V5_WRITER_INSTALLATION_OUT", "v5-writer-installation-receipt.json"));
            Files.writeString(output, NodePrettyJson.write(receipt), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return 0;
        } catch (Exception error) {
            spec.commandLine().getErr().println(message(error));
            return 1;
        }
    }

    private Path resolve(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private static String appJwt(long appId, String privateKeyPem, Instant now) throws Exception {
        long epoch = now.getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iat\":" + (epoch - 60) + ",\"exp\":" + (epoch + 540)
                + ",\"iss\":" + appId + "}");
        String input = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsaPrivateKey(privateKeyPem));
        signer.update(input.getBytes(StandardCharsets.UTF_8));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static PrivateKey rsaPrivateKey(String pem) throws Exception {
        String normalized = pem.replace("\r", "").trim();
        boolean pkcs1 = normalized.startsWith("-----BEGIN RSA PRIVATE KEY-----");
        String body = normalized
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("protected writer App private key is invalid", error);
        }
        if (pkcs1) encoded = wrapPkcs1(encoded);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception error) {
            throw new IllegalArgumentException("protected writer App private key is invalid", error);
        }
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48,
                (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octet = der((byte) 0x04, pkcs1);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(version);
        content.writeBytes(algorithm);
        content.writeBytes(octet);
        return der((byte) 0x30, content.toByteArray());
    }

    private static byte[] der(byte tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        int length = value.length;
        if (length < 128) {
            output.write(length);
        } else {
            int bytes = 0;
            for (int remaining = length; remaining > 0; remaining >>>= 8) bytes++;
            output.write(0x80 | bytes);
            for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) output.write(length >>> shift);
        }
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long positiveInteger(String value, String label) {
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value);
            long result = number.longValueExact();
            if (result <= 0 || result > MAX_SAFE_INTEGER) throw new NumberFormatException();
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
    }

    private static WriterInstallationReceipts.ApiResponse callGh(
            String path, String token, List<String> headers) throws Exception {
        List<String> command = new ArrayList<>(List.of("gh", "api", "--include"));
        for (String header : headers) {
            command.add("-H");
            command.add(header);
        }
        command.add(path);
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> child = builder.environment();
        child.entrySet().removeIf(entry -> CHILD_SECRET.matcher(entry.getKey()).find());
        child.put("GH_TOKEN", token);
        Process process = builder.start();
        var stdout = new java.util.concurrent.FutureTask<>(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        var stderr = new java.util.concurrent.FutureTask<>(
                () -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(stdout);
        Thread.ofVirtual().start(stderr);
        int status = process.waitFor();
        String out = stdout.get();
        String err = stderr.get();
        return parseApiResponse(status == 0 || !out.isEmpty() ? out : err);
    }

    static WriterInstallationReceipts.ApiResponse parseApiResponse(String output) {
        Matcher matcher = HTTP_STATUS.matcher(output == null ? "" : output);
        int status = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        String[] blocks = (output == null ? "" : output).split("\\r?\\n\\r?\\n");
        String bodyText = blocks.length == 0 ? "{}" : blocks[blocks.length - 1];
        JsonNode body;
        try {
            body = JSON.readTree(bodyText);
            if (body == null) body = JSON.createObjectNode();
        } catch (Exception ignored) {
            body = JSON.createObjectNode();
        }
        return new WriterInstallationReceipts.ApiResponse(status, body);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    @FunctionalInterface
    interface ApiClient {
        WriterInstallationReceipts.ApiResponse get(
                String path, String token, List<String> headers) throws Exception;
    }
}
