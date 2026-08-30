package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class WriterInstallationCliCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPOSITORY = "Illia-Shchur/trading-claude-analytics";
    private static final long REPOSITORY_ID = 1_238_541_043L;
    private static final Instant NOW = Instant.parse("2026-08-28T10:15:30Z");

    @TempDir Path temporary;

    @Test
    void provesTheFrozenInstallationAndWritesAnExclusiveNodePrettyReceipt() throws Exception {
        KeyPair pair = rsaKeyPair();
        Map<String, String> environment = validEnvironment(privatePem(pair));
        List<ApiCall> calls = new ArrayList<>();
        WriterInstallationCliCommand.ApiClient api = (path, token, headers) -> {
            calls.add(new ApiCall(path, token, List.copyOf(headers)));
            return switch (path) {
                case "installation/repositories" -> response(repositories());
                case "app" -> response(app());
                case "app/installations/156524819" -> response(installation());
                default -> throw new AssertionError(path);
            };
        };

        Invocation result = execute(environment, api);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).isEqualTo(new ApiCall(
                "installation/repositories", "writer-token", List.of()));
        assertThat(calls.get(1).path()).isEqualTo("app");
        assertThat(calls.get(2).path()).isEqualTo("app/installations/156524819");
        assertThat(calls.get(1).token()).isEqualTo(calls.get(2).token()).isNotEqualTo("writer-token");
        assertThat(calls.get(1).headers()).containsExactly("Accept: application/vnd.github+json");
        verifyJwt(calls.get(1).token(), pair);

        Path output = temporary.resolve("writer.json");
        JsonNode receipt = JSON.readTree(output.toFile());
        assertThat(receipt.path("generated_at").asText()).isEqualTo(NOW.toString());
        assertThat(WriterInstallationReceipts.verifyWriterInstallationReceipt(
                receipt, REPOSITORY, REPOSITORY_ID)).isTrue();
        assertThat(Files.readString(output)).isEqualTo(NodePrettyJson.write(receipt));
        assertThat(Files.readString(output)).doesNotContain("writer-token", "PRIVATE KEY");
    }

    @Test
    void failsClosedBeforeNetworkForMissingOrWrongProtectedIdentity() throws Exception {
        KeyPair pair = rsaKeyPair();
        int[] calls = {0};
        WriterInstallationCliCommand.ApiClient api = (path, token, headers) -> {
            calls[0]++;
            throw new AssertionError("network must not be called");
        };
        Map<String, String> missingToken = validEnvironment(privatePem(pair));
        missingToken.remove("GH_TOKEN");
        Invocation missing = execute(missingToken, api);
        assertThat(missing.exitCode()).isOne();
        assertThat(missing.stderr()).contains("GITHUB_REPOSITORY and protected writer token are required");

        Map<String, String> wrong = validEnvironment(privatePem(pair));
        wrong.put("V5_EVIDENCE_WRITER_APP_ID", "99");
        Invocation identity = execute(wrong, api);
        assertThat(identity.exitCode()).isOne();
        assertThat(identity.stderr()).contains("exactly match the frozen deployment identity");

        Map<String, String> zero = validEnvironment(privatePem(pair));
        zero.put("V5_EVIDENCE_WRITER_APP_ID", "0");
        assertThat(execute(zero, api).stderr()).contains("writer App id must be a positive integer");

        Map<String, String> safeIntegerBoundary = validEnvironment(privatePem(pair));
        safeIntegerBoundary.put("V5_EVIDENCE_WRITER_APP_ID", "9007199254740991");
        assertThat(execute(safeIntegerBoundary, api).stderr())
                .contains("exactly match the frozen deployment identity");

        Map<String, String> invalidKey = validEnvironment("definitely-not-a-key");
        Invocation key = execute(invalidKey, api);
        assertThat(key.exitCode()).isOne();
        assertThat(key.stderr()).contains("private key is invalid").doesNotContain("definitely-not-a-key");
        assertThat(calls[0]).isZero();
    }

    @Test
    void acceptsThePkcs1PemEncodingUsedByGitHubAppDownloads() throws Exception {
        KeyPair pair = rsaKeyPair();
        WriterInstallationCliCommand.ApiClient api = (path, token, headers) -> switch (path) {
            case "installation/repositories" -> response(repositories());
            case "app" -> response(app());
            default -> response(installation());
        };
        Invocation result = execute(validEnvironment(pkcs1Pem(pair)), api);
        assertThat(result.exitCode()).isZero();
        assertThat(WriterInstallationReceipts.verifyWriterInstallationReceipt(
                JSON.readTree(temporary.resolve("writer.json").toFile()), REPOSITORY, REPOSITORY_ID))
                .isTrue();
    }

    @Test
    void refusesToOverwriteAnExistingReceiptAfterProof() throws Exception {
        KeyPair pair = rsaKeyPair();
        Files.writeString(temporary.resolve("writer.json"), "keep\n");
        WriterInstallationCliCommand.ApiClient api = (path, token, headers) -> switch (path) {
            case "installation/repositories" -> response(repositories());
            case "app" -> response(app());
            default -> response(installation());
        };
        Invocation result = execute(validEnvironment(privatePem(pair)), api);
        assertThat(result.exitCode()).isOne();
        assertThat(Files.readString(temporary.resolve("writer.json"))).isEqualTo("keep\n");
    }

    @Test
    void parsesGhIncludeResponsesWithoutTreatingDiagnosticsAsJson() {
        WriterInstallationReceipts.ApiResponse response =
                WriterInstallationCliCommand.parseApiResponse(
                        "HTTP/2 200 OK\r\ncontent-type: application/json\r\n\r\n{\"ok\":true}\n");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("ok").asBoolean()).isTrue();

        WriterInstallationReceipts.ApiResponse malformed =
                WriterInstallationCliCommand.parseApiResponse("gh: authentication failed");
        assertThat(malformed.status()).isZero();
        assertThat(malformed.body().isObject()).isTrue();
        assertThat(malformed.body()).isEmpty();
    }

    private Invocation execute(
            Map<String, String> environment, WriterInstallationCliCommand.ApiClient api) {
        WriterInstallationCliCommand command = new WriterInstallationCliCommand(
                temporary, environment, Clock.fixed(NOW, ZoneOffset.UTC), api);
        CommandLine line = new CommandLine(command);
        StringWriter stdout = new StringWriter(), stderr = new StringWriter();
        line.setOut(new PrintWriter(stdout, true));
        line.setErr(new PrintWriter(stderr, true));
        return new Invocation(line.execute(), stdout.toString(), stderr.toString());
    }

    private Map<String, String> validEnvironment(String privateKey) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GITHUB_REPOSITORY", REPOSITORY);
        environment.put("GITHUB_REPOSITORY_ID", Long.toString(REPOSITORY_ID));
        environment.put("GH_TOKEN", "writer-token");
        environment.put("V5_EVIDENCE_WRITER_APP_ID",
                Long.toString(WriterInstallationReceipts.WRITER_APP_ID));
        environment.put("V5_EVIDENCE_WRITER_INSTALLATION_ID",
                Long.toString(WriterInstallationReceipts.WRITER_INSTALLATION_ID));
        environment.put("V5_EVIDENCE_WRITER_APP_SLUG", WriterInstallationReceipts.WRITER_APP_SLUG);
        environment.put("V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM", privateKey);
        environment.put("V5_WRITER_INSTALLATION_OUT", "writer.json");
        return environment;
    }

    private static WriterInstallationReceipts.ApiResponse response(JsonNode body) {
        return new WriterInstallationReceipts.ApiResponse(200, body);
    }

    private static ObjectNode app() {
        ObjectNode value = JSON.createObjectNode();
        value.put("id", WriterInstallationReceipts.WRITER_APP_ID);
        value.put("slug", WriterInstallationReceipts.WRITER_APP_SLUG);
        value.set("permissions", permissions());
        value.putArray("events");
        return value;
    }

    private static ObjectNode installation() {
        ObjectNode value = JSON.createObjectNode();
        value.put("id", WriterInstallationReceipts.WRITER_INSTALLATION_ID);
        value.put("app_id", WriterInstallationReceipts.WRITER_APP_ID);
        value.put("app_slug", WriterInstallationReceipts.WRITER_APP_SLUG);
        value.put("repository_selection", "selected");
        value.set("permissions", permissions());
        value.putArray("events");
        value.putObject("account").put("id", 37_546_899L)
                .put("login", "Illia-Shchur").put("type", "User");
        return value;
    }

    private static ObjectNode repositories() {
        ObjectNode value = JSON.createObjectNode();
        value.put("total_count", 1);
        value.putArray("repositories").addObject()
                .put("id", REPOSITORY_ID).put("full_name", REPOSITORY);
        return value;
    }

    private static ObjectNode permissions() {
        ObjectNode value = JSON.createObjectNode();
        value.put("contents", "write");
        value.put("metadata", "read");
        value.put("pull_requests", "write");
        return value;
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String privatePem(KeyPair pair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
    }

    private static String pkcs1Pem(KeyPair pair) {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) pair.getPrivate();
        byte[] body = sequence(
                integer(java.math.BigInteger.ZERO), integer(key.getModulus()),
                integer(key.getPublicExponent()), integer(key.getPrivateExponent()),
                integer(key.getPrimeP()), integer(key.getPrimeQ()),
                integer(key.getPrimeExponentP()), integer(key.getPrimeExponentQ()),
                integer(key.getCrtCoefficient()));
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + encoded
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    private static byte[] integer(java.math.BigInteger value) {
        return der((byte) 0x02, value.toByteArray());
    }

    private static byte[] sequence(byte[]... values) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (byte[] value : values) body.writeBytes(value);
        return der((byte) 0x30, body.toByteArray());
    }

    private static byte[] der(byte tag, byte[] value) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.write(tag);
        if (value.length < 128) {
            output.write(value.length);
        } else {
            int bytes = 0;
            for (int remaining = value.length; remaining > 0; remaining >>>= 8) bytes++;
            output.write(0x80 | bytes);
            for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
                output.write(value.length >>> shift);
            }
        }
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static void verifyJwt(String token, KeyPair pair) throws Exception {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond() - 60);
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 540);
        assertThat(claims.path("iss").asLong()).isEqualTo(WriterInstallationReceipts.WRITER_APP_ID);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private record ApiCall(String path, String token, List<String> headers) {}
    private record Invocation(int exitCode, String stdout, String stderr) {}
}
