package com.tradinganalytics.marketdata.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResearchDataCommandAdapterTest {
    @TempDir Path temporary;

    @Test
    void everyCliModeSucceedsAndFailuresRemainClosed() throws Exception {
        Path input = temporary.resolve("bars.jsonl");
        Files.writeString(input, """
                {"asset":"btc","time":"2026-01-01T00:00:00Z","availability_time":"2026-01-01T04:00:00Z","close":100}
                {"asset":"btc","time":"2026-01-01T04:00:00Z","availability_time":"2026-01-01T08:00:00Z","close":101}
                """);

        Invocation initialized = invoke("init", "--out", temporary.resolve("initialized").toString());
        assertThat(initialized.status()).as(initialized.stderr()).isZero();
        assertThat(json(initialized.stdout()).path("authoritative_format").asText())
                .isEqualTo("parquet");

        Invocation snapshot = invoke("snapshot", "--input", input.toString(), "--out",
                temporary.resolve("lake").toString(), "--dataset", "core", "--asset", "btc",
                "--pit-tier", "T0_IMMUTABLE_EVENT", "--source", "fixture-ohlc",
                "--format", "jsonl");
        assertThat(snapshot.status()).as(snapshot.stderr()).isZero();
        JsonNode snapshotOutput = json(snapshot.stdout());
        Path manifest = Path.of(snapshotOutput.path("manifest").asText());
        assertThat(manifest).exists();

        Invocation ingest = invoke("ingest", "--input", input.toString(), "--out",
                temporary.resolve("ingest").toString(), "--dataset", "core", "--asset", "btc",
                "--pit", "T0_IMMUTABLE_EVENT", "--source", "fixture-ohlc", "--format", "jsonl");
        Invocation features = invoke("build-features", "--input", input.toString(), "--out",
                temporary.resolve("features").toString(), "--dataset", "core", "--asset", "btc",
                "--pit-tier", "T0_IMMUTABLE_EVENT", "--source", "fixture-ohlc",
                "--format", "jsonl");
        assertThat(ingest.status()).as(ingest.stderr()).isZero();
        assertThat(features.status()).as(features.stderr()).isZero();

        Path builtLabels = temporary.resolve("built-labels.json");
        Invocation labels = invoke("build-labels", "--manifest", manifest.toString(),
                "--out", builtLabels.toString(), "--horizon-bars", "2");
        assertThat(labels.status()).as(labels.stderr()).isZero();
        assertThat(json(labels.stdout()).path("label_set").path("predictor_eligible").asBoolean())
                .isFalse();
        assertThat(invoke("build-labels", "--manifest", manifest.toString(),
                "--out", builtLabels.toString()).status()).isEqualTo(1);

        Invocation validate = invoke("validate", "--manifest", manifest.toString(),
                "--phase", "DEVELOPMENT", "--assets", "btc");
        assertThat(validate.status()).as(validate.stderr()).isZero();
        assertThat(json(validate.stdout()).path("valid").asBoolean()).isTrue();
        Invocation catalog = invoke("catalog-rebuild", "--root", temporary.resolve("lake").toString());
        assertThat(catalog.status()).as(catalog.stderr()).isZero();
        assertThat(json(catalog.stdout()).path("catalog").path("snapshots")).hasSize(1);
        Invocation diff = invoke("diff", "--left", manifest.toString(), "--right", manifest.toString());
        assertThat(diff.status()).as(diff.stderr()).isZero();
        assertThat(json(diff.stdout()).path("same").asBoolean()).isTrue();

        Path parquetStage = temporary.resolve("query.jsonl");
        Files.writeString(parquetStage, """
                {"asset":"btc","event_time":0,"availability_time":1,"close":100}
                {"asset":"eth","event_time":14400000,"availability_time":14400001,"close":200}
                """);
        Path parquet = temporary.resolve("query.parquet");
        ResearchData.writeParquet(parquetStage, parquet);
        Invocation query = invoke("query", "--input", parquet.toString(), "--from", "0",
                "--to", "2026-01-01T00:00:00Z", "--asset", "btc");
        assertThat(query.status()).as(query.stderr()).isZero();
        assertThat(json(query.stdout())).hasSize(1);
        Invocation stagingQuery = invoke("query", "--input", input.toString());
        assertThat(stagingQuery.status()).isEqualTo(1);
        assertThat(stagingQuery.stderr()).contains("authoritative query requires Parquet");

        Path pack = temporary.resolve("lake.pack.json");
        Invocation packed = invoke("pack", "--manifest", manifest.toString(),
                "--out", pack.toString());
        assertThat(packed.status()).as(packed.stderr()).isZero();
        ObjectNode packValue = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(pack));
        assertThat(packValue.path("files")).anySatisfy(file ->
                assertThat(file.path("path").asText()).isEqualTo("manifests/dataset-manifest.json"));
        Path restored = temporary.resolve("restored");
        Invocation restore = invoke("restore", "--pack", pack.toString(), "--out", restored.toString());
        assertThat(restore.status()).as(restore.stderr()).isZero();
        assertThat(json(restore.stdout()).path("complete").asBoolean()).isTrue();
        assertThat(Files.readAllBytes(restored.resolve("manifests/dataset-manifest.json")))
                .isEqualTo(Files.readAllBytes(manifest));

        ObjectNode tampered = packValue.deepCopy();
        ((ObjectNode) tampered.path("files").get(0)).put("bytes_base64",
                Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8)));
        Path tamperedPack = writeJson("tampered.pack.json", tampered);
        Invocation tamperedRestore = invoke("restore", "--pack", tamperedPack.toString(),
                "--out", temporary.resolve("tampered-restore").toString());
        assertThat(tamperedRestore.status()).isEqualTo(1);
        assertThat(tamperedRestore.stderr()).contains("embedded pack content hash mismatch");

        ObjectNode traversal = packValue.deepCopy();
        ((ObjectNode) traversal.path("files").get(0)).put("path", "../escape.json");
        Path traversalPack = writeJson("traversal.pack.json", traversal);
        Invocation traversalRestore = invoke("restore", "--pack", traversalPack.toString(),
                "--out", temporary.resolve("traversal-restore").toString());
        assertThat(traversalRestore.status()).isEqualTo(1);
        assertThat(traversalRestore.stderr()).contains("unsafe pack path");
        assertThat(temporary.resolve("escape.json")).doesNotExist();

        Path lakeRoot = manifest.getParent().getParent();
        Path source = lakeRoot.resolve("snapshot-identity.json");
        Path hardlink = lakeRoot.resolve("hardlink.json");
        Files.createLink(hardlink, source);
        Invocation hardlinkPack = invoke("pack", "--manifest", manifest.toString(), "--out",
                temporary.resolve("hardlink.pack.json").toString());
        assertThat(hardlinkPack.status()).isEqualTo(1);
        assertThat(hardlinkPack.stderr()).contains("singly-linked");
        Files.delete(hardlink);

        Path restoredManifest = restored.resolve("manifests/dataset-manifest.json");
        Path restoredAlias = restored.resolve("manifest-hardlink.json");
        Files.createLink(restoredAlias, restoredManifest);
        Invocation hardlinkRestore = invoke("restore", "--pack", pack.toString(),
                "--out", restored.toString());
        assertThat(hardlinkRestore.status()).isEqualTo(1);
        assertThat(hardlinkRestore.stderr()).contains("singly-linked");

        Invocation usage = invoke();
        assertThat(usage.status()).isZero();
        assertThat(usage.stdout()).contains("init|snapshot|ingest|build-features");
    }

    @Test
    void restoreCannotTraverseAnExistingSymlinkParent() throws Exception {
        ObjectNode pack = JsonHashes.mapper().createObjectNode();
        pack.put("schema", "research-lake-pack/1");
        pack.put("pack_version", 1);
        ObjectNode manifest = pack.putObject("manifest");
        manifest.put("content_sha256", "a".repeat(64));
        byte[] value = "do-not-escape".getBytes(StandardCharsets.UTF_8);
        ObjectNode file = pack.putArray("files").addObject();
        file.put("path", "features/escape.txt");
        file.put("sha256", JsonHashes.sha256(value));
        file.put("bytes_base64", Base64.getEncoder().encodeToString(value));
        Path packPath = writeJson("symlink.pack.json", pack);
        Path root = Files.createDirectory(temporary.resolve("symlink-restore"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(root.resolve("features"), outside);
        Invocation result = invoke("restore", "--pack", packPath.toString(), "--out", root.toString());
        assertThat(result.status()).isEqualTo(1);
        assertThat(result.stderr()).contains("unsafe pack path parent");
        assertThat(outside.resolve("escape.txt")).doesNotExist();
    }

    private Path writeJson(String name, JsonNode value) throws Exception {
        Path path = temporary.resolve(name);
        Files.write(path, JsonHashes.mapper().writeValueAsBytes(value));
        return path;
    }

    private static JsonNode json(String value) throws Exception {
        return JsonHashes.mapper().readTree(value);
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = ResearchDataCommandAdapter.run(args,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Invocation(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int status, String stdout, String stderr) {}
}
