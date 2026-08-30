package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.research.legacy.LegacyResearchV3;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class StrategyAttestationCliCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-02T03:04:05Z"), ZoneOffset.UTC);

    @TempDir Path temporary;

    @Test
    void missingOrUnknownActionPrintsLegacyUsageAndSucceeds() {
        Invocation missing = execute();
        assertThat(missing.exitCode()).isZero();
        assertThat(missing.stdout()).isEqualTo(
                "usage: strategy-attestation.mjs keygen|reserve|burn|sign|verify|import\n");
        assertThat(missing.stderr()).isEmpty();

        Invocation unknown = execute("unknown");
        assertThat(unknown.exitCode()).isZero();
        assertThat(unknown.stdout()).isEqualTo(missing.stdout());
    }

    @Test
    void keygenForwardsUnmatchedLegacyFlagsAndNeverPrintsPrivateKey() throws Exception {
        Invocation result = execute("keygen", "--private-out", "keys/private.pem", "--public-out", "keys/public.pem");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        JsonNode output = JSON.readTree(result.stdout());
        Path privateKey = Path.of(output.path("private_key_path").asText());
        Path publicKey = Path.of(output.path("public_key_path").asText());
        assertThat(privateKey).exists();
        assertThat(publicKey).exists();
        assertThat(Files.readString(privateKey)).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(Files.readString(publicKey)).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(result.stdout()).doesNotContain("BEGIN PRIVATE KEY");
    }

    @Test
    void burnCreatesAnExclusiveDurableReceipt() throws Exception {
        ObjectNode reservation = JSON.createObjectNode();
        reservation.put("schema", LegacyResearchV3.RESERVATION_SCHEMA);
        reservation.put("seal_id", "adapter-test");
        reservation.put("status", "RESERVED");
        reservation = LegacyResearchV3.withHash(reservation);
        Files.writeString(temporary.resolve("reservation.json"), NodePrettyJson.write(reservation));

        Invocation first = execute("burn", "--reservation", "reservation.json", "--burn-root", "burns");
        assertThat(first.exitCode()).isZero();
        Path burned = Path.of(JSON.readTree(first.stdout()).path("burned").asText());
        assertThat(burned).exists();
        assertThat(Files.readString(burned)).isEqualTo(reservation.path("content_sha256").asText() + "\n");

        Invocation second = execute("burn", "--reservation", "reservation.json", "--burn-root", "burns");
        assertThat(second.exitCode()).isOne();
        assertThat(second.stderr()).contains("already burned");
    }

    @Test
    void keygenFailsClosedWhenEitherOutputIsMissing() {
        Invocation result = execute("keygen", "--private-out", "private.pem");
        assertThat(result.exitCode()).isOne();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("private key is never printed");
    }

    private Invocation execute(String... arguments) {
        StrategyAttestationCliCommand command = new StrategyAttestationCliCommand(
                temporary, Map.of(), CLOCK, (directory, args) -> "0".repeat(40) + "\n");
        CommandLine line = new CommandLine(command);
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
