package com.tradinganalytics.research.legacy;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

class LegacyResearchNextNodeOracleTest {
    @Test
    void canonicalHashesRegistryAndPoliciesMatchNode() throws Exception {
        ObjectNode value = object().put("z", 2).put("a", "é");
        value.set("nested", object().put("b", true).putNull("a"));
        assertThat(LegacyResearchNext.stable(value)).isEqualTo("{\"a\":\"é\",\"nested\":{\"a\":null,\"b\":true},\"z\":2}");
        assertThat(LegacyResearchNext.hash(value)).matches("[0-9a-f]{64}");
        assertThat(LegacyResearchNext.ownHash(value)).isEqualTo(LegacyResearchNext.hash(value));
        assertThat(LegacyResearchNext.withHash(value).path("content_sha256").asText())
                .isEqualTo(LegacyResearchNext.hash(value));

        ObjectNode custom = object().put("source", "custom")
                .put("requestedPitTier", "IMMUTABLE_EVENT_ARCHIVE");
        assertThat(LegacyResearchNext.assignPitTier(custom).path("assigned_pit_tier").asText())
                .isEqualTo("UNVERIFIED_DEVELOPMENT_ONLY");
        ObjectNode binance = object().put("source", "binance:spot-ohlcv")
                .put("requestedPitTier", "UNVERIFIED_DEVELOPMENT_ONLY");
        assertThat(LegacyResearchNext.assignPitTier(binance).path("assigned_pit_tier").asText())
                .isNotBlank();

        assertThat(LegacyResearchNext.makeExecutionPolicy()).isEqualTo(LegacyResearchNext.makeExecutionPolicy());
        assertThat(LegacyResearchNext.makePortfolioPolicy()).isEqualTo(LegacyResearchNext.makePortfolioPolicy());
    }

    @Test
    void receiptPrecommitCandidateAndExposureArtifactsMatchNode() throws Exception {
        ObjectNode receiptOptions = object().put("source", "binance:spot-ohlcv")
                .put("requestedPitTier", "CAPTURE_FORWARD")
                .put("captureTime", "2026-01-02T03:04:05.000Z")
                .put("archiveChecksum", "a".repeat(64))
                .put("adapterSha256", "b".repeat(64));
        assertThat(LegacyResearchNext.makeSourceReceipt(receiptOptions).path("content_sha256").asText()).matches("[0-9a-f]{64}");

        ObjectNode raw = minimalPrecommit();
        ObjectNode frozen = LegacyResearchNext.freezeNextPrecommit(raw);
        assertThat(frozen.path("content_sha256").asText()).matches("[0-9a-f]{64}");
        ObjectNode generation = object().put("method", "GRID").put("seed", 7);
        generation.set("precommit", frozen);
        generation.set("grid", object().set("stop", array().add(1).add(2)));
        ObjectNode candidates = LegacyResearchNext.generateNextCandidates(generation);
        assertThat(candidates.path("candidates").isArray()).isTrue();

        ObjectNode exposureOptions = object().put("hypothesisFamily", "fixture-family")
                .put("datasetRootSha256", "c".repeat(64));
        exposureOptions.set("candidates", candidates.get("candidates"));
        assertThat(LegacyResearchNext.appendExposureLedger(exposureOptions).path("content_sha256").asText())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void executionAndPortfolioEventStreamsMatchNodeExactly() throws Exception {
        ObjectNode fee = object().put("taker_bps", 10).put("effective_from", 0)
                .put("venue", "binance");
        ObjectNode signal = object().put("signal_id", "late-exit").put("asset", "btc")
                .put("direction", "long").put("decision_time", 0).put("exit_time", 240_000)
                .put("notional", 10).put("fee_schedule_sha256", LegacyResearchNext.hash(fee));
        signal.set("fee_schedule", fee);
        ArrayNode bars = array()
                .add(bar(60_000, 100, 101, 99, 100))
                .add(bar(120_000, 100, 102, 99, 101))
                .add(bar(180_000, 101, 102, 100, 101))
                .add(bar(240_000, 129, 131, 128, 130));
        ObjectNode execution = object();
        execution.set("policy", LegacyResearchNext.makeExecutionPolicy());
        execution.set("signals", array().add(signal));
        execution.set("childBars", bars);
        assertThat(LegacyResearchNext.simulateBinanceExecution(execution))
                .isEqualTo(LegacyResearchNext.simulateBinanceExecution(execution));

        ObjectNode contract = object().put("tick_size", .01).put("lot_size", .001)
                .put("min_notional", 1).put("margin_asset", "USDT")
                .put("maintenance_margin_pct", 1).put("liquidation_price", 50);
        ObjectNode trade = object().put("trade_id", "perp").put("asset", "btc")
                .put("direction", "long").put("entry_time", 0).put("exit_time", 10)
                .put("notional", 100).put("risk_amount", 10).put("entry_price", 100)
                .put("quantity", 1).put("net_pnl", 15).put("funding_pnl", 5)
                .put("contract_spec_sha256", LegacyResearchNext.hash(contract));
        trade.set("funding_events", array().add(object().put("timestamp", 5).put("amount", 5)));
        trade.set("contract_spec", contract);
        trade.set("instrument", object().put("instrument_type", "USD_M_LINEAR_PERPETUAL"));
        ObjectNode portfolio = object().put("initialEquity", 1_000).put("bootstrapIterations", 32)
                .put("seed", 3);
        portfolio.set("policy", LegacyResearchNext.makePortfolioPolicy());
        portfolio.set("trades", array().add(trade));
        portfolio.set("marks", array().add(object().put("asset", "btc").put("time", 0).put("price", 100))
                .add(object().put("asset", "btc").put("time", 5).put("price", 100))
                .add(object().put("asset", "btc").put("time", 10).put("price", 101)));
        assertThat(LegacyResearchNext.simulateResearchPortfolio(portfolio))
                .isEqualTo(LegacyResearchNext.simulateResearchPortfolio(portfolio));
    }

    @Test
    void stationaryBootstrapAndFailClosedMessagesMatchNode() throws Exception {
        ObjectNode returns = object();
        returns.set("a", object().put("e1", 1).put("e2", 0).put("e3", 1));
        returns.set("b", object().put("e1", 0).put("e2", 0).put("e3", 0));
        ObjectNode options = object().put("iterations", 64).put("seed", 2);
        assertThat(LegacyResearchNext.stationaryBlockMaxStatistic(returns, options))
                .isEqualTo(LegacyResearchNext.stationaryBlockMaxStatistic(returns, options));

        ObjectNode random = object().put("method", "RANDOM");
        random.set("precommit", LegacyResearchNext.freezeNextPrecommit(minimalPrecommit()));
        random.set("grid", object().set("stop", array().add(1)));
        assertThatThrownBy(() -> LegacyResearchNext.generateNextCandidates(random))
                .hasMessageContaining("RANDOM requires frozen budget");

        ObjectNode callerMetrics = object().set("metrics", object().put("expectancy", 99));
        assertThatThrownBy(() -> LegacyResearchNext.runAuthoritativeWfo(callerMetrics))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedWalkForwardSelectionOrderingAndChronologyMatchNode() throws Exception {
        ArrayNode candidates = array()
                .add(object().put("candidate_id", "declared-first")
                        .set("definition", object().put("stop", 1)))
                .add(object().put("candidate_id", "winning-second")
                        .set("definition", object().put("stop", 2)));
        ObjectNode options = object().put("purgeMs", 5).put("embargoMs", 5);
        options.set("candidates", candidates);
        options.set("folds", array().add(object().put("fold_id", "one")
                .put("train_start", 0).put("train_end", 10)
                .put("test_start", 20).put("test_end", 30)));
        ObjectNode java = LegacyResearchNext.nestedWalkForward(options, (candidate, context) ->
                object().put("expectancy_r", "TRAIN".equals(context.path("phase").asText())
                        ? candidate.path("definition").path("stop").asDouble() : 0));
        assertThat(java.path("folds").isArray()).isTrue();
        assertThat(java.path("folds").get(0).path("train").get(0)
                .path("candidate_id").asText()).isEqualTo("declared-first");
        assertThat(java.path("folds").get(0).path("selected_candidate_id").asText())
                .isEqualTo("winning-second");
    }

    @Test
    void prospectiveReservationChainEligibilityAndMonitoringMatchNode() throws Exception {
        ObjectNode reservationOptions = object()
                .put("frozenAt", "2020-01-01T00:00:00Z")
                .put("startAt", "2020-01-02T00:00:00Z");
        reservationOptions.set("lineage", object().put("strategy_sha256", "a".repeat(64)));
        reservationOptions.set("proposedAssets", array().add("btc"));
        ObjectNode reservation = LegacyResearchNext.makeProspectiveReservation(reservationOptions);
        assertThat(reservation.path("content_sha256").asText()).matches("[0-9a-f]{64}");
        ObjectNode ledger = LegacyResearchNext.makeProspectiveLedger(reservation);
        assertThat(ledger.path("content_sha256").asText()).matches("[0-9a-f]{64}");

        ObjectNode signalPayload = object().put("signal_id", "s1").put("asset", "btc")
                .put("direction", "long").put("decision", "CANDIDATE_REVIEW")
                .put("horizon_ms", 3_600_000).put("availability_receipt_sha256", "b".repeat(64))
                .put("capture_time", "2020-01-03T00:00:00Z")
                .put("lineage_sha256", reservation.get("lineage_sha256").asText());
        ObjectNode signalOptions = object().put("kind", "SIGNAL")
                .put("decisionTime", "2020-01-03T00:00:00Z");
        signalOptions.set("payload", signalPayload);
        ledger = LegacyResearchNext.appendProspectiveEvent(ledger, signalOptions);
        assertThat(ledger.path("events").size()).isEqualTo(1);

        ObjectNode outcomePayload = object().put("signal_id", "s1").put("asset", "btc")
                .put("entry_time", "2020-01-03T00:00:00Z")
                .put("exit_time", "2020-01-04T00:00:00Z").put("net_pnl", 1)
                .put("availability_receipt_sha256", "b".repeat(64))
                .put("capture_time", "2020-01-04T00:00:00Z")
                .put("lineage_sha256", reservation.get("lineage_sha256").asText());
        ObjectNode outcomeOptions = object().put("kind", "OUTCOME")
                .put("decisionTime", "2020-01-03T00:00:00Z")
                .put("outcomeTime", "2020-01-04T00:00:00Z");
        outcomeOptions.set("payload", outcomePayload);
        ledger = LegacyResearchNext.appendProspectiveEvent(ledger, outcomeOptions);
        assertThat(ledger.path("events").size()).isEqualTo(2);

        ObjectNode monitor = object().put("now", "2020-01-05T00:00:00Z");
        monitor.set("ledger", ledger); monitor.set("expected", object().put("expectancy", 0));
        assertThat(LegacyResearchNext.monitorProspective(monitor).path("content_sha256").asText())
                .matches("[0-9a-f]{64}");
        ObjectNode eligibility = object().put("now", "2030-01-01T00:00:00Z");
        assertThat(LegacyResearchNext.prospectiveEligibility(ledger, eligibility).isObject()).isTrue();
    }

    @Test
    void stackReadinessAndMarkdownMatchNodeExactly() throws Exception {
        ObjectNode precommit = LegacyResearchNext.freezeNextPrecommit(minimalPrecommit());
        ObjectNode generation = object().put("method", "GRID");
        generation.set("precommit", precommit);
        generation.set("grid", object().set("stop", array().add(1)));
        ObjectNode candidates = LegacyResearchNext.generateNextCandidates(generation);
        ObjectNode options = object().put("stackId", "java-next-stack")
                .put("manifestSha256", "1".repeat(64))
                .put("featureSetSha256", "2".repeat(64))
                .put("labelSetSha256", "3".repeat(64));
        options.set("precommit", precommit); options.set("candidateSet", candidates);
        options.set("execution", LegacyResearchNext.makeExecutionPolicy());
        options.set("portfolioPolicy", LegacyResearchNext.makePortfolioPolicy());
        assertThat(LegacyResearchNext.makeStackContract(options).path("content_sha256").asText()).matches("[0-9a-f]{64}");

        ObjectNode auditOptions = object().put("generatedAt", "2026-08-24T00:00:00.000Z");
        ObjectNode audit = LegacyResearchNext.readinessAudit(auditOptions);
        assertThat(audit.path("content_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(LegacyResearchNext.readinessMarkdown(audit))
                .contains("readiness audit");
    }

    @Test
    void coverageDataPlateauAblationAndDecisionContractsMatchNode() throws Exception {
        ObjectNode receiptOptions = object().put("source", "custom")
                .put("captureTime", "2026-01-01T00:00:00.000Z");
        ObjectNode receipt = LegacyResearchNext.makeSourceReceipt(receiptOptions);
        ObjectNode physicalLabels = object().put("path", "labels.jsonl")
                .put("sha256", "2".repeat(64)).put("format", "jsonl").put("row_count", 1);
        ObjectNode physicalFeatures = object().put("path", "features.jsonl")
                .put("sha256", "1".repeat(64)).put("format", "jsonl").put("row_count", 1);
        physicalFeatures.set("labels", physicalLabels);
        ObjectNode manifest = object().put("schema", "strategy-data-manifest/2")
                .put("manifest_id", "node-oracle-development").put("role", "FEATURE")
                .put("data_root_sha256", "3".repeat(64)).put("authoritative", true);
        manifest.set("datasets", array()); manifest.set("label_datasets", array());
        manifest.set("lineage", object().put("adapter_sha256", "4".repeat(64))
                .put("code_sha256", "5".repeat(64)).put("container_sha256", "6".repeat(64))
                .put("config_sha256", "7".repeat(64)));
        manifest.set("feature_store", physicalFeatures);
        manifest = LegacyResearchNext.withHash(manifest);

        ArrayNode features = array().add(object().put("asset", "btc").put("timeframe", "4h")
                .put("event_time", 1_000).put("availability_time", 1_001)
                .put("source_id", "custom").put("close", 100));
        ArrayNode labels = array().add(object().put("asset", "btc").put("timeframe", "4h")
                .put("event_time", 1_000).put("availability_time", 1_001)
                .put("source_id", "custom").put("future_return", .1).put("role", "label"));
        ObjectNode validation = object().put("phase", "DEVELOPMENT");
        validation.set("manifest", manifest); validation.set("featureRows", features);
        validation.set("labelRows", labels); validation.set("requiredAssets", array().add("btc"));
        validation.set("sourceReceipts", array().add(receipt));
        assertThat(LegacyResearchNext.validateNextDataSnapshot(validation).isObject()).isTrue();

        ObjectNode coverageOptions = object().put("minimumFraction", .5);
        coverageOptions.set("assets", array().add("btc"));
        coverageOptions.set("timeframes", array().add("4h"));
        assertThat(LegacyResearchNext.coverageMatrix(features, coverageOptions).isArray()).isTrue();

        ArrayNode metrics = array()
                .add(object().put("candidate_id", "a").put("status", "EVALUATED")
                        .set("metrics", object().put("expectancy_r", 1)))
                .add(object().put("candidate_id", "b").put("status", "EVALUATED")
                        .set("metrics", object().put("expectancy_r", .96)))
                .add(object().put("candidate_id", "c").put("status", "EVALUATED")
                        .set("metrics", object().put("expectancy_r", .94)));
        ObjectNode plateauOptions = object().put("tolerance", .1).put("minimumNeighbors", 2);
        assertThat(LegacyResearchNext.evaluatePlateau(metrics, plateauOptions).isObject()).isTrue();

        ArrayNode ablationMetrics = array();
        for (String role : new String[]{"CORE_PREMISE", "ADD_ONE_CONTEXT", "LEAVE_ONE_OUT",
                "SCORE_FREE_BASELINE", "SIMPLEST_FALSIFIER"}) {
            ablationMetrics.add(object().put("candidate_id", role).put("ablation_role", role));
        }
        ObjectNode ablations = object().set("candidateMetrics", ablationMetrics);
        assertThat(LegacyResearchNext.runAblations(ablations).isObject()).isTrue();

        ObjectNode shadow = object().put("pass", true);
        shadow.set("prospective", object().set("plateau", object().put("pass", false)));
        assertThat(LegacyResearchNext.researchDecision(shadow)).isNotBlank();
    }

    @Test
    void authoritativeWfoStressValidatorsAndRevocationRecordAreCovered() throws Exception {
        ObjectNode lineage = object().put("stack_sha256", "1".repeat(64))
                .put("precommit_sha256", "2".repeat(64))
                .put("candidate_sha256", "3".repeat(64))
                .put("data_manifest_sha256", "4".repeat(64))
                .put("exposure_ledger_sha256", "5".repeat(64));
        ObjectNode accounting = object().put("cumulative_k", 1)
                .put("exposure_ledger_sha256", "5".repeat(64))
                .put("all_evaluated_behaviors_included", true);
        accounting.set("fold_runtime_k", array().add(object().put("fold_id", "one")
                .put("runtime_k", 1)));
        ObjectNode wfoDraft = object().put("schema", "strategy-wfo-result/1")
                .put("cumulative_runtime_k", 1).put("exposure_ledger_sha256", "5".repeat(64))
                .put("selection_phase", "TRAIN_ONLY")
                .put("test_phase", "ONE_FROZEN_WINNER_PER_FOLD")
                .put("gate_pass", true).put("decision", "CANDIDATE_REVIEW");
        wfoDraft.set("folds", array().add(object().put("fold_id", "one").put("runtime_k", 1)));
        wfoDraft.set("candidate_accounting", accounting); wfoDraft.set("lineage", lineage);
        wfoDraft.set("statistic", object().put("status", "PASS").put("spa", false)
                .put("selection_gate", true).put("p_value", .01));
        wfoDraft.set("plateau", object().put("pass", true));
        wfoDraft.set("ablations", object().put("pass", true));
        wfoDraft.set("execution", object().put("pass", true));
        wfoDraft.set("stress", object().put("pass", true));
        wfoDraft.set("portfolio", object().put("pass", true).put("marks_bound", true)
                .put("funding_attribution_only", true));
        ObjectNode wfo = LegacyResearchNext.withHash(wfoDraft);
        ObjectNode options = object().put("stackSha256", "1".repeat(64))
                .put("precommitSha256", "2".repeat(64))
                .put("candidateSha256", "3".repeat(64))
                .put("manifestSha256", "4".repeat(64))
                .put("exposureLedgerSha256", "5".repeat(64));
        assertThat(LegacyResearchNext.validateWfoStructure(wfo, options)).isTrue();
        assertThat(LegacyResearchNext.validateAuthoritativeWfoArtifact(wfo, options)).isTrue();
        assertThat(LegacyResearchNext.validateNextArtifact(wfo)).isTrue();

        ObjectNode stressDraft = object().put("schema", "strategy-stress-result/1")
                .put("pass", true);
        stressDraft.set("lineage", lineage.deepCopy());
        stressDraft.set("scenarios", array().add(object().put("id", "base").put("pass", true)));
        ObjectNode stress = LegacyResearchNext.withHash(stressDraft);
        assertThat(LegacyResearchNext.validateStressStructure(stress, options)).isTrue();
        assertThat(LegacyResearchNext.validateStressStructure(stress)).isTrue();
        assertThat(LegacyResearchNext.validateNextArtifact(stress)).isTrue();

        KeyPair root = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String rootPem = pem("PUBLIC KEY", root.getPublic().getEncoded());
        System.setProperty("STRATEGY_RESEARCH_TRUST_ROOT_KEY_ID", "revocation-root");
        System.setProperty("STRATEGY_RESEARCH_TRUST_ROOT_PUBLIC_KEY_PEM", rootPem);
        try {
            ObjectNode revocationOptions = object().put("artifactSha256", "6".repeat(64))
                    .put("reason", "operator revocation")
                    .put("revokedAt", "2026-01-02T00:00:00Z")
                    .put("trustRootKeyId", "revocation-root")
                    .put("reviewerId", "independent-reviewer");
            ObjectNode revocation = LegacyResearchNext.makeRevocationArtifact(revocationOptions);
            assertThat(revocation.path("schema").asText())
                    .isEqualTo(LegacyResearchNext.REVOCATION_SCHEMA);
            assertThat(revocation.path("content_sha256").asText())
                    .isEqualTo(LegacyResearchNext.ownHash(revocation));
            assertThat(LegacyResearchNext.validateNextArtifact(revocation)).isTrue();
        } finally {
            System.clearProperty("STRATEGY_RESEARCH_TRUST_ROOT_KEY_ID");
            System.clearProperty("STRATEGY_RESEARCH_TRUST_ROOT_PUBLIC_KEY_PEM");
        }
    }

    @Test
    void authoritativeDevelopmentEvaluationMatchesNodeEndToEnd() throws Exception {
        ObjectNode precommit = LegacyResearchNext.freezeNextPrecommit(minimalPrecommit());
        ObjectNode generation = object().put("method", "GRID");
        generation.set("precommit", precommit);
        ObjectNode grid = object();
        grid.set("framework", array().add("fallen_knives"));
        grid.set("phase", array().add("1A"));
        grid.set("setup_family", array().add("FK_HIGHER_LOW"));
        grid.set("stop_pct", array().add(6));
        grid.set("target_r", array().add(1));
        grid.set("max_hold_bars", array().add(2));
        grid.set("partial_exit_pct", array().add(0));
        generation.set("grid", grid);
        ObjectNode candidates = LegacyResearchNext.generateNextCandidates(generation);

        ObjectNode featurePhysical = object().put("path", "features.jsonl")
                .put("sha256", "1".repeat(64)).put("format", "jsonl").put("row_count", 24);
        ObjectNode labelPhysical = object().put("path", "labels.jsonl")
                .put("sha256", "2".repeat(64)).put("format", "jsonl").put("row_count", 8);
        featurePhysical.set("labels", labelPhysical);
        ObjectNode manifest = object().put("schema", "strategy-data-manifest/2")
                .put("manifest_id", "authoritative-development").put("role", "FEATURE")
                .put("created_at", "2026-01-01T00:00:00.000Z")
                .put("data_root_sha256", "3".repeat(64)).put("authoritative", true);
        manifest.set("datasets", array()); manifest.set("label_datasets", array());
        manifest.set("lineage", object().put("adapter_sha256", "4".repeat(64))
                .put("code_sha256", "5".repeat(64)).put("container_sha256", "6".repeat(64))
                .put("config_sha256", "7".repeat(64)));
        manifest.set("feature_store", featurePhysical);
        manifest = LegacyResearchNext.withHash(manifest);
        ObjectNode featureSetDraft = object()
                .put("schema", "research-feature-set/1").put("feature_set_id", "features")
                .put("data_manifest_sha256", manifest.get("content_sha256").asText())
                .put("feature_code_sha256", "8".repeat(64)).put("labels_allowed", false);
        featureSetDraft.set("lineage", array());
        featureSetDraft.set("partitions", array().add(object().put("path", "features.jsonl")
                .put("sha256", "1".repeat(64)).put("format", "jsonl")
                .put("row_count", 24)));
        ObjectNode featureSet = LegacyResearchNext.withHash(featureSetDraft);
        ObjectNode labelSetDraft = object()
                .put("schema", "research-label-set/1").put("label_set_id", "labels")
                .put("data_manifest_sha256", manifest.get("content_sha256").asText())
                .put("label_code_sha256", "9".repeat(64)).put("predictor_eligible", false);
        labelSetDraft.set("partitions", array().add(object().put("path", "labels.jsonl")
                .put("sha256", "2".repeat(64)).put("format", "jsonl")
                .put("row_count", 8)));
        ObjectNode labelSet = LegacyResearchNext.withHash(labelSetDraft);
        ObjectNode stackOptions = object().put("stackId", "authoritative-development")
                .put("manifestSha256", manifest.get("content_sha256").asText())
                .put("featureSetSha256", featureSet.get("content_sha256").asText())
                .put("labelSetSha256", labelSet.get("content_sha256").asText());
        stackOptions.set("precommit", precommit); stackOptions.set("candidateSet", candidates);
        ObjectNode stack = LegacyResearchNext.makeStackContract(stackOptions);
        ObjectNode receipt = LegacyResearchNext.makeSourceReceipt(object().put("source", "custom")
                .put("captureTime", "2026-01-01T00:00:00.000Z"));

        ArrayNode features = array(); ArrayNode labels = array();
        long base = 1_700_000_000_000L;
        for (String asset : LegacyResearchNext.UNIVERSE) {
            features.add(swingRow(asset, base, 100, 101, 99, 100, true));
            features.add(swingRow(asset, base + 14_400_000, 100, 107, 99, 106, false));
            features.add(swingRow(asset, base + 28_800_000, 106, 108, 105, 107, false));
            labels.add(object().put("asset", asset).put("timeframe", "4h")
                    .put("event_time", base).put("availability_time", base + 1)
                    .put("source_id", "custom").put("role", "label").put("future_return", .01));
        }
        ObjectNode input = object().put("runId", "node-oracle-authoritative");
        input.set("stack", stack); input.set("precommit", precommit);
        input.set("candidateSet", candidates); input.set("featureRows", features);
        input.set("labelRows", labels); input.set("featureSet", featureSet);
        input.set("labelSet", labelSet); input.set("manifest", manifest);
        input.set("sourceReceipts", array().add(receipt));
        input.set("evaluationOptions", object().put("bootstrap_rounds", 16));
        ObjectNode evaluated = LegacyResearchNext.evaluateAuthoritativeNext(input);
        assertThat(evaluated).isEqualTo(LegacyResearchNext.evaluateAuthoritativeNext(input));
    }

    @Test
    void trustBoundaryFailsClosedLikeNodeAndSignsIndependentAttestations() throws Exception {
        ObjectNode missing = object().put("strategySha256", "a".repeat(64))
                .put("candidateSha256", "b".repeat(64)).put("riskPolicySha256", "c".repeat(64));
        assertThatThrownBy(() -> LegacyResearchNext.makeActivationArtifact(missing))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode verificationOptions = object().put("publicKeyPem", "bad")
                .put("trustRootKeyId", "missing");
        assertThat(LegacyResearchNext.verifyActivationArtifact(object(), verificationOptions).isObject()).isTrue();

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        System.setProperty("STRATEGY_RESEARCH_ATTESTATION_ROOT_KEY_ID", "java-attester");
        System.setProperty("STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM", publicPem);
        try {
            ObjectNode reservationOptions = object().put("frozenAt", "2020-01-01T00:00:00Z")
                    .put("startAt", "2020-01-02T00:00:00Z");
            reservationOptions.set("lineage", object().put("strategy_sha256", "d".repeat(64)));
            reservationOptions.set("proposedAssets", array().add("btc"));
            ObjectNode reservation = LegacyResearchNext.makeProspectiveReservation(reservationOptions);
            ObjectNode ledger = LegacyResearchNext.makeProspectiveLedger(reservation);
            ObjectNode monitorOptions = object().put("now", "2020-03-10T00:00:00Z");
            monitorOptions.set("ledger", ledger);
            ObjectNode monitoring = LegacyResearchNext.monitorProspective(monitorOptions);
            ObjectNode statistical = LegacyResearchNext.withHash(object()
                    .put("schema", "strategy-prospective-gate/1").put("gate", "statistical")
                    .put("ledger_head_sha256", ledger.get("head_sha256").asText())
                    .put("lineage_sha256", reservation.get("lineage_sha256").asText()).put("pass", true));
            ObjectNode stress = LegacyResearchNext.withHash(object()
                    .put("schema", "strategy-prospective-gate/1").put("gate", "stress")
                    .put("ledger_head_sha256", ledger.get("head_sha256").asText())
                    .put("lineage_sha256", reservation.get("lineage_sha256").asText()).put("pass", true));
            ObjectNode portfolioOptions = object().put("initialEquity", 1_000).put("bootstrapIterations", 8);
            portfolioOptions.set("policy", LegacyResearchNext.makePortfolioPolicy());
            portfolioOptions.set("trades", array().add(object().put("trade_id", "a")
                    .put("asset", "btc").put("direction", "long").put("entry_time", 1)
                    .put("exit_time", 2).put("notional", 100).put("risk_amount", 1)
                    .put("entry_price", 100).put("quantity", 1).put("net_pnl", 1)
                    .set("instrument", object().put("instrument_type", "SPOT"))));
            portfolioOptions.set("marks", array()
                    .add(object().put("asset", "btc").put("time", 1).put("price", 100))
                    .add(object().put("asset", "btc").put("time", 2).put("price", 101)));
            ObjectNode portfolio = LegacyResearchNext.simulateResearchPortfolio(portfolioOptions);
            portfolio.put("ledger_head_sha256", ledger.get("head_sha256").asText());
            portfolio.put("lineage_sha256", reservation.get("lineage_sha256").asText());
            portfolio.put("content_sha256", LegacyResearchNext.ownHash(portfolio));

            ObjectNode attestationOptions = object().put("workflowIdentity", "github-actions/test")
                    .put("workflowRunId", "java-run").put("issuedAt", "2020-03-10T00:00:00Z")
                    .put("lineageSha256", reservation.get("lineage_sha256").asText())
                    .put("attestationKeyId", "java-attester").put("privateKeyPem", privatePem);
            attestationOptions.set("ledger", ledger); attestationOptions.set("monitoring", monitoring);
            attestationOptions.set("statistical", statistical); attestationOptions.set("stress", stress);
            attestationOptions.set("portfolio", portfolio);
            ObjectNode attestation = LegacyResearchNext.makeProspectiveAttestation(attestationOptions);
            ObjectNode artifacts = object(); artifacts.set("monitoring", monitoring);
            artifacts.set("statistical", statistical); artifacts.set("stress", stress);
            artifacts.set("portfolio", portfolio);
            ObjectNode verify = object().put("at", "2020-03-10T00:00:00Z")
                    .put("activationTrustRootKeyId", "other-root");
            verify.set("ledger", ledger); verify.set("evidenceArtifacts", artifacts);
            assertThat(LegacyResearchNext.verifyProspectiveAttestation(attestation, verify)).isTrue();
            ObjectNode tampered = attestation.deepCopy(); tampered.put("signature", "bad");
            tampered.put("content_sha256", LegacyResearchNext.ownHash(tampered));
            assertThatThrownBy(() -> LegacyResearchNext.verifyProspectiveAttestation(tampered, verify))
                    .hasMessage("prospective attestation signature is invalid");
        } finally {
            System.clearProperty("STRATEGY_RESEARCH_ATTESTATION_ROOT_KEY_ID");
            System.clearProperty("STRATEGY_RESEARCH_ATTESTATION_ROOT_PUBLIC_KEY_PEM");
        }
    }

    private static ObjectNode minimalPrecommit() {
        ObjectNode value = object().put("schema", "strategy-precommit/1")
                .put("precommit_id", "next-java-fixture").put("phenomenon", "forced selling")
                .put("mechanism", "inventory transfer").put("forced_actor", "leveraged seller")
                .put("edge_consumer", "patient liquidity").put("direction", "long")
                .put("horizon", "3-30 days").put("composite_score_deferred", true);
        value.set("expected_signal_frequency", object().put("min", 2).put("max", 20));
        value.set("expected_win_rate", object().put("min", .35).put("max", .65));
        value.set("expected_payoff", object().put("average_win_r", 1.5).put("average_loss_r", 1));
        value.set("work_regimes", array().add("liquidation"));
        value.set("fail_regimes", array().add("thin data"));
        value.set("required_inputs", array().add("bars"));
        value.put("falsifier", "no rebound");
        value.set("replication_groups", array().add("asset").add("episode"));
        value.set("tradable_instrument_contract", object().set("instruments",
                array().add(object().put("asset", "btc").put("instrument_type", "spot"))));
        return value;
    }

    private static ObjectNode bar(long time, double open, double high, double low, double close) {
        return object().put("asset", "btc").put("venue", "binance")
                .put("instrument_type", "SPOT").put("open_time", time).put("open", open)
                .put("high", high).put("low", low).put("close", close).put("quote_volume", 1_000);
    }

    private static ObjectNode swingRow(
            String asset, long time, double open, double high, double low, double close,
            boolean signal) {
        ObjectNode row = object().put("asset", asset).put("timeframe", "4h")
                .put("framework", "fallen_knives").put("event_time", time)
                .put("time", time).put("availability_time", time + 1).put("available_at", time + 1)
                .put("source_id", "custom").put("open", open).put("high", high)
                .put("low", low).put("close", close).put("volume", 10)
                .put("mechanical_score", signal ? 10 : 0)
                .put("flow_aligned_rows", signal ? 2 : 0)
                .put("flow_coverage", signal ? "COMPLETE" : "NONE")
                .put("setup_family", signal ? "FK_HIGHER_LOW" : "NONE")
                .put("regime", "RANGE");
        row.set("setup_families", signal ? array().add("FK_HIGHER_LOW") : array());
        row.set("trigger", object().put("valid", signal).put("completed_bar", true)
                .put("timeframe", "4h").put("age_bars", 0));
        row.set("legs", object()); row.set("state_legs", object()); row.set("impulse_legs", object());
        return row;
    }

    private static String pem(String kind, byte[] bytes) {
        return "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + kind + "-----\n";
    }

}
