package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local Parquet roles for the physical null-selection path. */
class StrategyEvaluatorV5PortablePhysicalTest {
    @TempDir Path temporary;

    @Test
    void localPhysicalRolesRunNullSelectionAndRejectTamperedCheckpointBytes() throws Exception {
        Fixture fixture = fixture();
        try (StrategyEvaluatorV5.LoadedEvaluator loaded =
                StrategyEvaluatorV5.loadAuthoritativeEvaluatorV5(fixture.loadOptions())) {
            assertThat(loaded.evaluator().physicalNullSelectionVerified()).isTrue();

            StrategyStatisticalV5.PhysicalNullRunner runner =
                    StrategyStatisticalV5.makePhysicalNullRunnerV5(fixture.runnerOptions(), loaded.evaluator());
            assertThat(runner.contract().path("integration_status").asText()).isEqualTo("WIRED_PRODUCTION");

            ObjectNode first = runner.run(fixture.selectionRequest());
            assertThat(first.path("schema").asText()).isEqualTo("strategy-v5-physical-null-selection/1");
            assertThat(first.path("checkpoint_status").asText()).isEqualTo("COMPLETED");
            assertThat(first.path("transformed_label_ref").path("path").asText()).isNotBlank();
            assertThat(first.path("selected_outcome_vector_ref").path("path").asText()).isNotBlank();
            assertThat(first.path("selected_candidate_id").asText()).isNotBlank();
            double quantity = 10.0 / 100.0;
            double modeledEntry = 100.0 * (1.0 + 5.0 / 10_000.0);
            double modeledExit = 102.0 * (1.0 - 5.0 / 10_000.0);
            double fees = (modeledEntry * quantity + modeledExit * quantity) * .001;
            double slippage = (100.0 * quantity + 102.0 * quantity) * 5.0 / 10_000.0;
            double expectedNetR = ((102.0 - 100.0) * quantity - fees - slippage) / 10.0;
            JsonNode selectedVector = JsonHashes.mapper().readTree(Files.readAllBytes(
                    Path.of(first.path("selected_outcome_vector_ref").path("path").asText())));
            assertThat(selectedVector.path("schema").asText()).isEqualTo("strategy-v5-physical-null-vector/1");
            assertThat(selectedVector.path("rows")).hasSize(1);
            assertThat(selectedVector.path("rows").get(0).path("episode_id").asText()).isEqualTo("episode-7");
            assertThat(selectedVector.path("rows").get(0).path("traded").asBoolean()).isTrue();
            assertThat(selectedVector.path("rows").get(0).path("net_r").asDouble())
                    .isCloseTo(expectedNetR, within(1e-12));
            assertThat(first.path("selected_statistic").asDouble()).isCloseTo(expectedNetR, within(1e-12));
            assertThat(runner.run(fixture.selectionRequest()).toString()).isEqualTo(first.toString());

            Path checkpoint = Path.of(first.path("checkpoint_ref").path("path").asText());
            byte[] checkpointBytes = Files.readAllBytes(checkpoint);
            Files.writeString(checkpoint, "tampered", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> runner.run(fixture.selectionRequest()))
                    .hasMessageContaining("physical null checkpoint/reference is not valid JSON");

            // Restore the checkpoint so the second mutation reaches a different
            // persisted role and exercises its byte commitment independently.
            Files.write(checkpoint, checkpointBytes);
            Path transformedLabel = Path.of(first.path("transformed_label_ref").path("path").asText());
            Files.writeString(transformedLabel, " \n", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> runner.run(fixture.selectionRequest()))
                    .hasMessageContaining("physical null checkpoint/reference bytes are tampered");
        }
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("portable-lake"));
        Class.forName("org.duckdb.DuckDBDriver");
        List<String[]> episodes = List.of(
                new String[] {"episode-1", "2025-01-01T00:00:00.000Z"},
                new String[] {"episode-2", "2025-02-15T00:00:00.000Z"},
                new String[] {"episode-3", "2025-04-01T00:00:00.000Z"},
                new String[] {"episode-4", "2025-05-15T00:00:00.000Z"},
                new String[] {"episode-5", "2025-06-29T00:00:00.000Z"},
                new String[] {"episode-6", "2025-08-13T00:00:00.000Z"},
                new String[] {"episode-7", "2025-11-01T00:00:00.000Z"});
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement()) {
            StringBuilder featureRows = new StringBuilder();
            StringBuilder labelRows = new StringBuilder();
            StringBuilder executionRows = new StringBuilder();
            for (String[] episode : episodes) {
                if (!featureRows.isEmpty()) featureRows.append(" UNION ALL ");
                if (!labelRows.isEmpty()) labelRows.append(" UNION ALL ");
                if (!executionRows.isEmpty()) executionRows.append(" UNION ALL ");
                String id = episode[0];
                String decision = episode[1];
                String resolution = Instant.parse(decision).plusSeconds(180).toString();
                String availability = Instant.parse(decision).plusSeconds(240).toString();
                String identity = "'btc' AS asset, 'BINANCE' AS venue, 'BINANCE_SPOT' AS instrument, "
                        + "'BTCUSDT' AS symbol, '" + id + "' AS signal_id, '" + id + "' AS episode_id, "
                        + "'" + decision + "' AS decision_time";
                featureRows.append("SELECT ").append(identity)
                        .append(", '").append(decision).append("' AS event_time, '")
                        .append(decision).append("' AS availability_time, true AS signal_eligible, 2.0 AS edge");
                labelRows.append("SELECT ").append(identity)
                        .append(", '").append(decision).append("' AS entry_time, '")
                        .append(resolution).append("' AS resolution_time, '")
                        .append(resolution).append("' AS resolution_ceiling_time, '")
                        .append(availability).append("' AS availability_time");
                executionRows.append("SELECT ").append(identity)
                        .append(", '").append(availability).append("' AS availability_time")
                        .append(", 'COMPLETED_4H_BOUNDARY' AS decision_timestamp_convention")
                        .append(", '4h' AS decision_timeframe, 'BINANCE_SPOT' AS instrument")
                        .append(", 60000::BIGINT AS interval_ms, ")
                        .append(childBars(decision))
                        .append(", {'available_liquidity_usd':1000000.0,'participation_cap':1.0," 
                                + "'order_notional_usd':10.0} AS capacity_inputs")
                        .append(", 0.001::DOUBLE AS fee_rate")
                        .append(", 5.0::DOUBLE AS slippage_bps");
            }
            statement.execute("COPY (" + featureRows + ") TO '" + sql(root.resolve("feature.parquet"))
                    + "' (FORMAT PARQUET)");
            statement.execute("COPY (" + labelRows + ") TO '" + sql(root.resolve("label.parquet"))
                    + "' (FORMAT PARQUET)");
            statement.execute("COPY (" + executionRows + ") TO '" + sql(root.resolve("execution.parquet"))
                    + "' (FORMAT PARQUET)");
        }
        ObjectNode options = specOptions();
        ObjectNode spec = StrategyEvaluatorV5.makeEvaluatorSpecV5(options);
        ObjectNode manifest = object().put("schema", "strategy-v5-separated-parquet/1")
                .put("status", "AUTHORITATIVE_PARQUET").put("authoritative", true)
                .put("predictor_registry_sha256", options.path("predictorRegistry").path("content_sha256").asText())
                .put("precommit_sha256", spec.path("precommit_sha256").asText())
                .put("dataset_root_sha256", JsonHashes.sha256("portable-dataset"));
        ObjectNode artifacts = manifest.putObject("artifacts");
        for (String role : List.of("feature", "label", "execution")) {
            Path file = root.resolve(role + ".parquet");
            artifacts.putObject(role).put("path", file.getFileName().toString())
                    .put("sha256", JsonHashes.sha256(Files.readAllBytes(file))).put("bytes", Files.size(file));
        }
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));

        String behavior = JsonHashes.sha256("portable-behavior");
        ObjectNode source = object().put("schema", "strategy-v5-statistical-input/1").put("version", 1);
        source.putObject("lineage").put("dataset_sha256", manifest.path("dataset_root_sha256").asText())
                .put("candidate_set_sha256", JsonHashes.sha256("portable-candidates"))
                .put("feature_set_sha256", artifacts.path("feature").path("sha256").asText())
                .put("label_set_sha256", artifacts.path("label").path("sha256").asText())
                .put("execution_set_sha256", artifacts.path("execution").path("sha256").asText());
        source.putArray("candidates").addObject().put("candidate_id", "c")
                .put("behavior_sha256", behavior);
        ArrayNode sourceEpisodes = source.putArray("episodes");
        for (String[] episode : episodes) {
            String id = episode[0];
            String decision = episode[1];
            String resolution = Instant.parse(decision).plusSeconds(180).toString();
            String availability = Instant.parse(decision).plusSeconds(240).toString();
            sourceEpisodes.addObject().put("episode_id", id).put("asset", "btc")
                    .put("decision_time", decision).put("resolution_time", resolution)
                    .put("label_availability_time", availability).put("execution_availability_time", availability)
                    .put("eligible", true).putObject("candidate_returns").putObject("c")
                    .put("net_r", .2).put("traded", true);
        }
        source.putObject("metadata").put("fold_id", "portable");
        source.put("content_sha256", JsonHashes.ownHash(source));

        ObjectNode exposureOptions = object().put("hypothesisFamily", "portable-null")
                .put("datasetSha256", manifest.path("dataset_root_sha256").asText());
        exposureOptions.putArray("entries").addObject().put("behavior_sha256", behavior)
                .put("dataset_sha256", manifest.path("dataset_root_sha256").asText());
        ObjectNode exposure = StrategyStatisticalV5.makeExposureHead(exposureOptions);
        source.put("exposure_head_sha256", exposure.path("content_sha256").asText());
        source.put("content_sha256", JsonHashes.ownHash(source));
        return new Fixture(root, manifest, spec, options, source, exposure, behavior, metadata(root));
    }

    private static ObjectNode specOptions() {
        ObjectNode gene = object().put("schema", "strategy-v5-statistical-gene-space/1");
        gene.putArray("genes").addObject().put("name", "threshold").put("type", "continuous")
                .put("min", 0).put("max", 3).put("step", 1).put("default", 1)
                .put("usage", "predicate:edge:GTE");
        gene.put("content_sha256", JsonHashes.ownHash(gene));
        ObjectNode registry = object().put("schema", "strategy-v5-predictor-registry/1")
                .put("version", 1).put("status", "FROZEN");
        registry.putArray("predictors").addObject().put("id", "edge").put("scalar_type", "number")
                .put("source_field", "close").put("source_family", "TEST_PIT_FEATURE")
                .put("availability_derivation", "completed bar close").put("pit_role", "PREDICTOR")
                .put("lookback_ms", 0).put("code_sha256", JsonHashes.sha256("predictor-code"))
                .put("config_sha256", JsonHashes.sha256("predictor-config"));
        registry.put("content_sha256", JsonHashes.ownHash(registry));
        ObjectNode candidate = object().put("direction", "long").put("entry_policy", "NEXT_BAR_OPEN")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000).put("risk_amount_usd", 10);
        candidate.putObject("exit_policy").put("type", "TIME_STOP");
        ObjectNode executionContract = object();
        executionContract.putObject("sizing_contract").put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 10);
        ObjectNode options = object().put("strategyFamily", "portable-physical")
                .put("precommitSha256", JsonHashes.sha256("precommit"));
        options.set("geneSpace", gene); options.set("predictorRegistry", registry);
        options.set("predicate", predicate("GTE", object().put("$gene", "threshold")));
        options.set("candidateTemplate", candidate); options.set("executionContract", executionContract);
        return options;
    }

    private static ObjectNode predicate(String op, JsonNode expected) {
        return object().put("predictor_id", "edge").put("op", op).set("value", expected);
    }

    private static String childBars(String decision) {
        Instant start = Instant.parse(decision);
        return "[" + barStruct(start, 100, 101, 99, 100) + ","
                + barStruct(start.plusSeconds(60), 100, 102, 100, 101) + ","
                + barStruct(start.plusSeconds(120), 101, 103, 101, 102) + "] AS child_bars";
    }

    private static String barStruct(Instant time, double open, double high, double low, double close) {
        String stamp = time.toString();
        String available = time.plusSeconds(60).toString();
        return "{'event_time':'" + stamp + "','open':" + open + ",'high':" + high
                + ",'low':" + low + ",'close':" + close + ",'volume':1.0,'availability_time':'"
                + available + "'}";
    }

    private static ObjectNode metadata(Path root) throws Exception {
        ObjectNode value = object();
        value.put("source_root", root.toString());
        value.set("contract_spec", metadataReceipt("CONTRACT_SPEC", object()
                .put("contract_multiplier", 1).put("step_size", .00001).put("min_qty", .00001)
                .put("max_qty", 100_000).put("min_notional", 10).put("max_notional", 1_000_000), root));
        value.set("fee_schedule", metadataReceipt("FEE_SCHEDULE", object().put("taker_fee_rate", .001), root));
        value.set("execution_model", metadataReceipt("EXECUTION_MODEL", object()
                .put("slippage_bps", 5).put("impact_bps", 0).put("outage_policy", "FAIL")
                .put("gap_policy", "FILL_AT_OPEN"), root));
        return value;
    }

    private static ObjectNode metadataReceipt(String kind, ObjectNode terms, Path root) throws Exception {
        ObjectNode receipt = object().put("kind", kind).put("status", "PUBLIC_OBSERVED")
                .put("authoritative", true).put("source", "PORTABLE_PHYSICAL_FIXTURE")
                .put("source_root_reference", Path.of(System.getProperty("user.dir"))
                        .toAbsolutePath().normalize().relativize(root.toAbsolutePath().normalize()).toString());
        ObjectNode record = terms.deepCopy().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("effective_from", "2020-01-01T00:00:00.000Z")
                .put("effective_to", "2027-01-01T00:00:00.000Z")
                .put("availability_time", "2020-01-01T00:00:00.000Z");
        receipt.set("records", JsonHashes.mapper().createArrayNode().add(record));
        ObjectNode normalized = object().put("schema", "strategy-v5-normalized-source/1").put("kind", kind);
        normalized.putArray("raw_receipts");
        normalized.put("content_sha256", JsonHashes.ownHash(normalized));
        Path normalizedPath = root.resolve(kind.toLowerCase(java.util.Locale.ROOT) + "-normalized.json");
        byte[] normalizedBytes = JsonHashes.mapper().writeValueAsBytes(normalized);
        Files.write(normalizedPath, normalizedBytes);
        receipt.putArray("source_receipts").addObject().put("path", normalizedPath.getFileName().toString())
                .put("content_sha256", normalized.path("content_sha256").asText())
                .put("byte_sha256", JsonHashes.sha256(normalizedBytes)).put("bytes", normalizedBytes.length);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        return receipt;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static String sql(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }

    private record Fixture(Path root, ObjectNode manifest, ObjectNode spec, ObjectNode options,
            ObjectNode source, ObjectNode exposure, String behavior, ObjectNode metadata) {
        ObjectNode loadOptions() {
            ObjectNode value = object().put("root", root.toString()).put("cacheRoot", root.resolve("cache").toString())
                    .put("workerCount", 1).put("maxRowsPerRole", 10).put("maxMaterializedBytesPerRole", 1_000_000);
            value.set("manifest", manifest.deepCopy());
            value.set("evaluatorSpec", spec.deepCopy());
            value.set("geneSpace", options.path("geneSpace").deepCopy());
            value.set("predictorRegistry", options.path("predictorRegistry").deepCopy());
            value.set("metadata", metadata.deepCopy());
            return value;
        }

        ObjectNode runnerOptions() {
            ObjectNode value = object();
            value.set("roleManifest", manifest.deepCopy());
            value.set("geneSpace", options.path("geneSpace").deepCopy());
            value.set("exposureHead", exposure.deepCopy());
            value.set("selectionConstraints", object().put("minEpisodes", 1).put("minExpectancy", -1)
                    .put("minProfitFactor", 0).put("minCoverage", 0).put("maxDrawdownR", 10)
                    .put("maxCostR", 10).put("requireCapacityPass", true));
            value.put("selectionEndAt", "2026-01-01T00:05:00.000Z");
            ArrayNode definitions = JsonHashes.mapper().createArrayNode();
            definitions.addObject().put("behavior_sha256", behavior).set("chromosome", object().put("threshold", 1));
            value.set("behaviorDefinitions", definitions);
            value.put("physicalNullRoot", root.resolve("null").toString());
            return value;
        }

        ObjectNode selectionRequest() {
            ObjectNode value = object().put("method", "block_permuted_labels").put("seed", 7).put("iteration", 0)
                    .put("selected_candidate_id", "c").put("selected_trade_count", 0);
            value.set("source_artifact", source.deepCopy());
            value.set("selected_episode_ids", JsonHashes.mapper().createArrayNode().add("episode-7"));
            ObjectNode budget = object().put("population", 2).put("generations", 1)
                    .put("minGenerations", 1).put("plateauGenerations", 1)
                    .put("crossoverProbability", .9).put("halfLifeMonths", 18);
            budget.set("seeds", JsonHashes.mapper().createArrayNode().add(11).add(23).add(47));
            value.set("selection_budget", budget);
            return value;
        }
    }
}
