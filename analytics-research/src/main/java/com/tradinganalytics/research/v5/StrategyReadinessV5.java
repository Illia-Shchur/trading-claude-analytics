package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.WriterInstallationReceipts;
import com.tradinganalytics.infrastructure.security.ActionsAttestationVerifierV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact Java owner for {@code tools/strategy-readiness-v5.mjs}. */
public final class StrategyReadinessV5 {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long MAX_PROSPECTIVE_LEASE_MS = 90L * 86_400_000L;
    private static final long FIFTEEN_MINUTES_MS = 15L * 60_000L;
    private static final long THIRTY_DAYS_MS = 30L * 86_400_000L;
    private static final long SEVEN_DAYS_MS = 7L * 86_400_000L;
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder()
            .appendInstant(3).toFormatter();

    private static final long SETTINGS_AUDITOR_APP_ID = 4_716_635L;
    private static final long SETTINGS_AUDITOR_INSTALLATION_ID = 156_531_963L;
    private static final String SETTINGS_AUDITOR_APP_SLUG = "strategy-v5-settings-auditor";
    private static final String SETTINGS_AUDITOR_SECRET_NAME =
            "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM";
    private static final Map<String, String> SETTINGS_AUDITOR_PERMISSIONS = Map.of(
            "actions", "read",
            "administration", "read",
            "environments", "read",
            "metadata", "read",
            "secrets", "read");

    private static final Set<String> SUPPORTED = Set.of(
            "strategy-research-run/5", "strategy-wfo-result/1", "strategy-wfo-result/2",
            "strategy-overfit-audit/1", "strategy-v5-statistical-contracts/1",
            "strategy-v5-statistical-input/1", "strategy-v5-statistical-exposure-head/1",
            "strategy-v5-statistical-genetic-run/1", "strategy-v5-statistical-fold/1",
            "strategy-v5-statistical-evaluation/1", "strategy-v5-statistical-wfo/1",
            "strategy-v5-statistical-audit/1", "strategy-v5-statistical-null-controls/1",
            "strategy-v5-statistical-vector-inventory/1", "strategy-v5-statistical-null-replay/1",
            "strategy-v5-statistical-stress-decision/1",
            "strategy-v5-statistical-portfolio-decision/1",
            "strategy-v5-statistical-genetic-checkpoint/1",
            "strategy-v5-statistical-registry-journal/1",
            "strategy-v5-statistical-behavior-definition-registry/1",
            "strategy-v5-separated-artifacts/1", "strategy-v5-authoritative-data-plan/1",
            "strategy-v5-data-checkpoint/1", "strategy-v5-source-receipt/1",
            "strategy-v5-source-bundle/1", "strategy-v5-authoritative-command-receipt/1",
            "strategy-v5-authoritative-acquisition/1", "strategy-v5-authoritative-coverage/1",
            "strategy-v5-dated-futures-catalog/2", "strategy-v5-promoted-coverage/1",
            "strategy-v5-parquet-conversion/1", "strategy-v5-role-derivation-receipt/1",
            "strategy-v5-metadata-receipt/1", "strategy-v5-timeframe-requirements/1",
            "strategy-v5-feature-dag/1", "strategy-v5-feature-plan/1",
            "strategy-v5-opportunity-domain/1", "strategy-v5-opportunity-envelope/1",
            "strategy-v5-opportunity-envelope/2", "strategy-v5-opportunity-hydration/1",
            "strategy-v5-opportunity-hydration/2", "strategy-v5-execution-partition-set/1",
            "strategy-v5-trade-lifecycle/1", "strategy-v5-lifecycle-trust/1",
            "strategy-v5-authoritative-stage-artifact/1",
            "strategy-v5-authoritative-stress-contract/1",
            "strategy-v5-authoritative-stress-execution/1", "strategy-mark-artifact/1",
            "strategy-portfolio-policy/2", "strategy-portfolio-risk/1",
            "strategy-portfolio-stress-input/1", "strategy-portfolio-stress-result/1",
            "strategy-selected-trades/1", "strategy-selected-evaluation/1",
            "strategy-execution-fill-artifact/1", "strategy-candidate-set/5",
            "strategy-v5-predictor-registry/1", "strategy-v5-evaluator-spec/1",
            "strategy-experiment/1", "strategy-experiment/2", "strategy-experiment/3",
            "strategy-precommit/1", "strategy-prospective-ledger/2",
            "strategy-prospective-replay-index/1", "strategy-prospective-replay-registry/1",
            "strategy-prospective-signed-evidence/2", "strategy-activation-revocation/1",
            "strategy-actions-only-secret-evidence/1", "github-settings-drift-evidence/1",
            "strategy-github-prospective-attestation/1",
            "strategy-github-attestation-key-registry/1", "strategy-deployment-audit/1",
            "github-deployment-settings-capture/1", "github-settings-api-receipt/1",
            "github-writer-installation-receipt/1", "strategy-readiness-evidence-manifest/1");

    private static final List<String> PIPELINE = List.of(
            "features", "signal_intent", "labels", "execution_fills", "trades",
            "metrics", "stresses", "portfolio", "wfo");
    private static final List<String> DATA_ROLES = List.of("feature", "label", "execution", "mark");
    private static final List<String> CRITICAL_DIMENSIONS = List.of(
            "governance", "statistical", "pit", "opportunity", "execution", "portfolio");

    private StrategyReadinessV5() {}

    public static String hash(JsonNode value) { return JsonHashes.canonicalSha256(value); }
    public static String hash(String value) { return JsonHashes.sha256(value); }
    public static String hash(byte[] value) { return JsonHashes.sha256(value); }
    public static String ownHash(JsonNode value) { return ownHash(value, "content_sha256"); }
    public static String ownHash(JsonNode value, String field) { return JsonHashes.ownHash(value, field); }

    public static ObjectNode withHash(ObjectNode value) { return withHash(value, "content_sha256"); }

    public static ObjectNode withHash(ObjectNode value, String field) {
        ObjectNode copy = value == null ? object() : value.deepCopy();
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    /** Exact pure port of {@code environmentReviewSafe}. */
    public static boolean environmentReviewSafe(JsonNode value) {
        if (value == null || !value.isObject()
                || !nonNegativeSafeInteger(value.get("reviewer_count"))
                || !nonNegativeSafeInteger(value.get("protection_rule_count"))) return false;
        JsonNode explicit = value.get("required_reviewer_rule_count");
        Long required;
        if (explicit == null) {
            if (value.path("reviewer_count").asLong() > 0) required = 1L;
            else if (value.path("protection_rule_count").asLong() == 0) required = 0L;
            else required = null;
        } else {
            required = nonNegativeSafeInteger(explicit) ? Long.valueOf(explicit.longValue()) : null;
        }
        return required != null
                && required <= value.path("protection_rule_count").asLong()
                && required == value.path("reviewer_count").asLong()
                && (required == 0 || value.path("prevent_self_review").asBoolean(false));
    }

    public static ObjectNode buildReadinessAuditV5() { return buildReadinessAuditV5(object()); }

    /** Evidence-derived readiness. Missing or unverifiable physical dependencies score zero. */
    public static ObjectNode buildReadinessAuditV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        long nowMs = input.has("now") ? parseTimestamp(input.get("now"), "now must be a valid timestamp")
                : System.currentTimeMillis();
        JsonNode manifestSpec = input.get("evidenceManifest");
        Manifest manifest = readEvidenceManifest(manifestSpec);
        ObjectNode evidence = input.path("evidence").isObject()
                ? (ObjectNode) input.path("evidence") : object();
        ObjectNode manifestEvidence = object();
        if (manifest != null) {
            for (JsonNode spec : rows(manifest.value().get("entries"))) {
                manifestEvidence.set(text(spec.get("id")), spec.deepCopy());
            }
        }
        ObjectNode supplied = evidence.size() > 0 ? evidence : manifestEvidence;
        if (manifest != null && evidence.size() > 0) {
            for (JsonNode entry : rows(manifest.value().get("entries"))) {
                JsonNode suppliedEntry = supplied.get(text(entry.get("id")));
                boolean exact = false;
                for (JsonNode row : rowsOrOne(suppliedEntry)) {
                    if (row != null && row.isObject()
                            && same(row.get("path"), entry.get("path"))
                            && same(row.get("sha256"), entry.get("sha256"))
                            && (!entry.hasNonNull("schema") || same(row.get("schema"), entry.get("schema")))) {
                        exact = true;
                    }
                }
                if (!exact) throw fail("evidence manifest entry is not supplied exactly: "
                        + text(entry.get("id")));
            }
        }

        List<ObjectNode> artifacts = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        supplied.fields().forEachRemaining(entry -> {
            for (JsonNode raw : rowsOrOne(entry.getValue())) {
                if (raw == null || raw.isNull()) continue;
                ObjectNode spec = raw.deepCopy();
                spec.put("id", entry.getKey());
                readVerifiedArtifact(spec, nowMs, seenIds, artifacts);
            }
        });
        if (manifest != null) {
            ObjectNode row = object();
            row.put("id", "evidence-manifest");
            row.put("path", manifest.path().toString());
            row.put("schema", text(manifest.value().get("schema")));
            row.put("ok", true);
            row.put("byte_sha256", manifest.byteSha256());
            row.put("content_sha256", manifest.contentSha256());
            row.putArray("failures");
            row.put("manifest", true);
            row.set("value", manifest.value().deepCopy());
            artifacts.add(0, row);
        }
        Map<String, ObjectNode> verified = new LinkedHashMap<>();
        for (ObjectNode row : artifacts) verified.put(text(row.get("id")), row);

        JsonNode run = get(verified, "run");
        JsonNode wfo = get(verified, "wfo");
        JsonNode overfit = get(verified, "overfit");
        JsonNode data = get(verified, "data");
        JsonNode execution = get(verified, "execution");
        JsonNode portfolio = get(verified, "portfolio");
        JsonNode prospective = get(verified, "prospective");
        JsonNode root = get(verified, "trustRoot");
        JsonNode previousRoot = get(verified, "previousTrustRoot");
        JsonNode activation = get(verified, "activation");
        JsonNode github = get(verified, "github");
        JsonNode githubDrift = get(verified, "githubDrift");
        if (githubDrift == null) githubDrift = get(verified, "githubSettingsDrift");
        JsonNode apiReceipt = get(verified, "githubApiReceipt");

        ObjectNode physicalChain = physicalReadinessChain(
                verified, run, data, wfo, overfit, portfolio,
                verified.containsKey("data") ? nullablePath(verified.get("data").get("path")) : null);
        ObjectNode opportunityChain = physicalOpportunityChain(verified, run);
        ArrayNode dimensions = array();

        boolean runExact = physicalChain.path("core").asBoolean(false)
                && ok(verified, "run") && "strategy-research-run/5".equals(schema(verified, "run"))
                && "AUTHORITATIVE_RECOMPUTED".equals(text(run == null ? null : run.get("provenance")))
                && containsAll(run == null ? null : run.get("pipeline"), PIPELINE)
                && validHash(first(run == null ? null : run.path("lineage").get("candidate_set_sha256"),
                        run == null ? null : run.get("candidate_set_sha256")))
                && validHash(run == null ? null : run.path("lineage").get("manifest_sha256"))
                && validHash(run == null ? null : run.get("manifest_sha256"));
        dimensions.add(dimension("governance", "Research governance and reproducibility",
                List.of(
                        check("governance.run-schema", "exact authoritative run schema", runExact,
                                pathEvidence(verified, "run")),
                        check("governance.run-hash", "run content and byte hashes verified",
                                ok(verified, "run") && validHash(verified.get("run").get("content_sha256")),
                                pathEvidence(verified, "run"))),
                List.of(
                        check("governance.immutable-lineage", "run has complete immutable lineage",
                                runExact && validHash(run.path("lineage").get("feature_rows_sha256"))
                                        && validHash(run.path("lineage").get("label_rows_sha256"))
                                        && validHash(run.path("lineage").get("execution_rows_sha256"))
                                        && validHash(run.path("lineage").get("mark_rows_sha256")),
                                pathEvidence(verified, "run")),
                        check("governance.authoritative", "authoritative provenance is declared",
                                runExact && run.path("accounting").path("zero_episode_binding").asBoolean(false)
                                        && run.path("gate_status").path("all_required_stages").asBoolean(false),
                                pathEvidence(verified, "run")))));

        boolean legacyWfoExact = "strategy-wfo-result/2".equals(schema(verified, "wfo"))
                && intValue(wfo, "fold_count") == 8 && size(wfo == null ? null : wfo.get("folds")) == 8
                && number(wfo == null ? null : wfo.get("purge_ms")) >= THIRTY_DAYS_MS
                && number(wfo == null ? null : wfo.get("embargo_ms")) >= SEVEN_DAYS_MS
                && "TRAIN_ONLY".equals(text(wfo == null ? null : wfo.get("selection_phase")))
                && "OUTER_OOS_UNWEIGHTED".equals(text(wfo == null ? null : wfo.get("test_phase")))
                && "UNWEIGHTED".equals(text(wfo == null ? null : wfo.get("oos_weighting")))
                && wfoLineageExact(wfo) && wfoFoldEvidenceExact(wfo);
        boolean statisticalWfoExact = "strategy-v5-statistical-wfo/1".equals(schema(verified, "wfo"))
                && intValue(wfo, "fold_count") == 8 && size(wfo == null ? null : wfo.get("folds")) == 8
                && rows(wfo == null ? null : wfo.get("folds")).stream().allMatch(fold ->
                        number(fold.get("purge_ms")) >= THIRTY_DAYS_MS
                                && number(fold.get("embargo_ms")) >= SEVEN_DAYS_MS
                                && "TRAIN_ONLY".equals(text(fold.get("selection_phase"))))
                && "UNWEIGHTED".equals(text(wfo == null ? null : wfo.get("oos_weighting")))
                && validHash(first(wfo == null ? null : wfo.get("exposure_head_sha256"),
                        wfo == null ? null : wfo.path("lineage").get("exposure_head_sha256")))
                && wfo != null && wfo.path("audit").path("fail_closed_missing_inputs").asBoolean(false)
                && !"ACTIVE".equals(text(wfo.path("audit").get("decision")))
                && validHash(wfo.get("oos_artifact_sha256")) && validHash(wfo.get("vector_inventory_sha256"))
                && wfoLineageExact(wfo) && wfoFoldEvidenceExact(wfo);
        boolean wfoExact = physicalChain.path("wfo").asBoolean(false) && ok(verified, "wfo")
                && (legacyWfoExact || statisticalWfoExact);
        boolean legacyOverfitExact = "strategy-overfit-audit/1".equals(schema(verified, "overfit"))
                && overfit != null && overfit.path("fail_closed_missing_inputs").asBoolean(false)
                && overfit.path("null_controls").path("pass").asBoolean(false)
                && "PASS".equals(text(overfit.path("max_statistic").get("status")))
                && number(overfit.get("search_adjusted_expectancy_r")) > 0
                && overfitNumericsExact(overfit);
        boolean statisticalOverfitExact = "strategy-v5-statistical-audit/1".equals(schema(verified, "overfit"))
                && overfit != null && overfit.path("fail_closed_missing_inputs").asBoolean(false)
                && overfit.path("gates").path("search_adjusted_expectancy_positive").asBoolean(false)
                && overfit.path("gates").path("max_statistic").asBoolean(false)
                && overfit.path("gates").path("null_controls").asBoolean(false)
                && overfit.path("pass").asBoolean(false) && "SHADOW".equals(text(overfit.get("decision")))
                && overfitNumericsExact(overfit);
        boolean overfitExact = physicalChain.path("overfit").asBoolean(false) && ok(verified, "overfit")
                && (legacyOverfitExact || statisticalOverfitExact);
        dimensions.add(dimension("statistical", "Statistical selection controls",
                List.of(
                        check("statistical.wfo-schema", "exact eight-fold WFO artifact", wfoExact,
                                pathEvidence(verified, "wfo")),
                        check("statistical.overfit-schema", "exact fail-closed overfit artifact", overfitExact,
                                pathEvidence(verified, "overfit"))),
                List.of(
                        check("statistical.purged-embargoed", "30-day purge and seven-day embargo", wfoExact,
                                pathEvidence(verified, "wfo")),
                        check("statistical.lineage-bound", "WFO binds immutable dataset/precommit/experiment lineage",
                                wfoExact && wfoLineageExact(wfo), pathEvidence(verified, "wfo")))));

        boolean dataExact = physicalChain.path("data").asBoolean(false) && ok(verified, "data")
                && "strategy-v5-separated-artifacts/1".equals(schema(verified, "data"))
                && "AUTHORITATIVE_PARQUET".equals(text(data == null ? null : data.get("status")))
                && "AUTHORITATIVE".equals(text(data == null ? null : data.get("storage_role")))
                && "PARQUET".equals(text(data == null ? null : data.get("format")))
                && data != null && data.path("authoritative").asBoolean(false)
                && validHash(data.get("dataset_root_sha256")) && data.path("artifacts").isObject()
                && DATA_ROLES.stream().allMatch(role -> data.path("artifacts").path(role)
                        .path("authoritative").asBoolean(false)
                        && "PARQUET".equals(text(data.path("artifacts").path(role).get("format")))
                        && validHash(data.path("artifacts").path(role).get("sha256")))
                && physicalDataArtifactSet(data, verified.get("data").get("path"));
        dimensions.add(dimension("pit", "PIT historical data readiness",
                List.of(
                        check("pit.authoritative-schema", "authoritative separated v5 Parquet artifact set",
                                dataExact, pathEvidence(verified, "data")),
                        check("pit.physical-hashes", "feature/label/execution/mark files are hash-bound",
                                dataExact && DATA_ROLES.stream().allMatch(role ->
                                        validHash(data.path("artifacts").path(role).get("sha256"))),
                                pathEvidence(verified, "data"))),
                List.of(
                        check("pit.availability-contract", "label and execution artifacts preserve availability boundaries",
                                dataExact && arrayContains(data.path("artifacts").path("label").get("field_names"), "availability_time")
                                        && arrayContains(data.path("artifacts").path("execution").get("field_names"), "availability_time"),
                                pathEvidence(verified, "data")),
                        check("pit.no-staging-substitute", "no staging artifact is accepted as authoritative",
                                dataExact && "AUTHORITATIVE".equals(text(data.get("storage_role")))
                                        && data.path("authoritative").asBoolean(false),
                                pathEvidence(verified, "data")))));

        dimensions.add(dimension("opportunity", "Frozen opportunity domain and physical hydration",
                List.of(
                        check("opportunity.feature-contract-lineage",
                                "feature DAG and plan are physically reopened and hash-linked",
                                opportunityChain.path("capability").asBoolean(false)
                                        && present(opportunityChain.get("graph"))
                                        && present(opportunityChain.get("plan")),
                                rowEvidence(opportunityChain.get("graph"), opportunityChain.get("plan"))),
                        check("opportunity.v2-contracts",
                                "complete non-fixture domain, envelope and hydration v2 artifacts are present",
                                opportunityChain.path("capability").asBoolean(false)
                                        && present(opportunityChain.get("domain"))
                                        && present(opportunityChain.get("envelope"))
                                        && present(opportunityChain.get("hydration")),
                                rowEvidence(opportunityChain.get("domain"), opportunityChain.get("envelope"),
                                        opportunityChain.get("hydration")))),
                List.of(
                        check("opportunity.physical-chain",
                                "v2 hydration reopens its physical v1 partition source and complete coverage",
                                opportunityChain.path("physical_complete").asBoolean(false)
                                        && opportunityChain.path("operational").asBoolean(false),
                                rowEvidence(opportunityChain.get("physical"), opportunityChain.get("hydration"))),
                        check("opportunity.run-lineage", "the authoritative run binds the exact frozen v2 envelope",
                                opportunityChain.path("operational").asBoolean(false),
                                concat(pathEvidence(verified, "run"), rowEvidence(opportunityChain.get("envelope")))))));

        boolean executionExact = physicalChain.path("core").asBoolean(false)
                && ok(verified, "execution") && "strategy-research-run/5".equals(schema(verified, "execution"))
                && execution != null && "AUTHORITATIVE_RECOMPUTED".equals(text(execution.get("provenance")))
                && validHash(execution.get("execution_rows_sha256"))
                && arrayContains(execution.get("pipeline"), "execution_fills")
                && arrayContains(execution.get("pipeline"), "trades")
                && execution.path("candidate_metrics").isArray()
                && rows(execution.get("candidate_metrics")).stream().allMatch(row -> row.path("trades").isArray());
        dimensions.add(dimension("execution", "Execution realism",
                List.of(
                        check("execution.authoritative-schema", "execution is derived from exact v5 run",
                                executionExact, pathEvidence(verified, "execution")),
                        check("execution.physical-rows", "execution rows are physically bound",
                                executionExact && validHash(execution.get("execution_rows_sha256")),
                                pathEvidence(verified, "execution"))),
                List.of(
                        check("execution.fill-recompute", "fills/trades are recomputed in canonical pipeline",
                                executionExact && arrayContains(execution.get("pipeline"), "metrics"),
                                pathEvidence(verified, "execution")),
                        check("execution.label-separation", "execution is separate from signal predicates",
                                executionExact && validHash(execution.get("label_rows_sha256"))
                                        && validHash(execution.get("feature_rows_sha256")),
                                pathEvidence(verified, "execution")))));

        boolean portfolioExact = physicalChain.path("portfolio").asBoolean(false)
                && ok(verified, "portfolio") && "strategy-portfolio-risk/1".equals(schema(verified, "portfolio"))
                && portfolio != null && "AUTHORITATIVE_RECOMPUTED".equals(text(portfolio.get("provenance")))
                && portfolio.path("pass").asBoolean(false) && validHash(portfolio.get("mark_artifact_sha256"))
                && "MEASURED".equals(text(portfolio.path("marginal_risk_contribution").get("status")))
                && portfolio.path("marginal_risk_contribution").path("component_sum_matches_portfolio").asBoolean(false)
                && portfolio.path("asset_decisions").isArray()
                && "PASS".equals(text(portfolio.path("portfolio_decision").get("status")))
                && number(portfolio.path("exposure").get("current_equity")) > 0;
        dimensions.add(dimension("portfolio", "Portfolio and risk realism",
                List.of(
                        check("portfolio.authoritative-schema", "physical portfolio risk artifact is exact v5 schema",
                                portfolioExact, pathEvidence(verified, "portfolio")),
                        check("portfolio.physical-mark", "portfolio binds a physical mark artifact",
                                portfolioExact && physicalDependency(verified, "portfolio", row ->
                                        "strategy-mark-artifact/1".equals(text(row.get("schema")))
                                                && sameText(row.get("byte_sha256"), portfolio.get("mark_bytes_sha256"))),
                                pathEvidence(verified, "portfolio"))),
                List.of(
                        check("portfolio.pnl-mrc", "actual PnL covariance/MRC reconciles",
                                portfolioExact && portfolio.path("pnl_covariance_by_asset").isArray(),
                                pathEvidence(verified, "portfolio")),
                        check("portfolio.separate-decisions", "asset and portfolio decisions remain separate",
                                portfolioExact && portfolio.path("asset_decisions").size() > 0
                                        && "PASS".equals(text(portfolio.path("portfolio_decision").get("status"))),
                                pathEvidence(verified, "portfolio")))));

        boolean prospectiveExact = ok(verified, "prospective")
                && "strategy-prospective-signed-evidence/2".equals(schema(verified, "prospective"))
                && number(prospective == null ? null : prospective.get("sequence")) >= 1
                && validHash(prospective == null ? null : prospective.get("previous_head_sha256"))
                && validHash(prospective == null ? null : prospective.get("new_head_sha256"))
                && validHash(prospective == null ? null : prospective.get("replay_previous_head_sha256"))
                && validHash(prospective == null ? null : prospective.get("replay_new_head_sha256"))
                && prospective != null && prospective.path("evidence").isArray()
                && "asset".equals(text(prospective.path("asset_approval").get("role")))
                && "portfolio".equals(text(prospective.path("portfolio_approval").get("role")))
                && !sameText(prospective.path("asset_approval").get("key_id"),
                        prospective.path("portfolio_approval").get("key_id"));
        dimensions.add(dimension("prospective", "Prospective validation readiness",
                List.of(
                        check("prospective.publication-schema", "portable signed publication schema is exact",
                                prospectiveExact, pathEvidence(verified, "prospective")),
                        check("prospective.evidence-digests", "all publication evidence is hash-bound",
                                prospectiveExact && rows(prospective.get("evidence")).stream()
                                        .allMatch(row -> validHash(row.get("sha256"))),
                                pathEvidence(verified, "prospective"))),
                List.of(
                        check("prospective.replay-revocation", "replay and revocation heads are recorded",
                                prospectiveExact && validHash(prospective.get("replay_entry_sha256"))
                                        && prospective.path("replay_protection").asBoolean(false)
                                        && prospective.path("revocation_registry").asBoolean(false),
                                pathEvidence(verified, "prospective")),
                        check("prospective.lease", "publication lease is bounded",
                                prospectiveExact && validFutureLease(prospective.get("lease_expires_at"), nowMs),
                                pathEvidence(verified, "prospective")))));

        boolean rootVerified = verifyTrustRootForReadiness(verified, root, previousRoot, nowMs);
        boolean driftExact = ok(verified, "githubDrift")
                && "github-settings-drift-evidence/1".equals(schema(verified, "githubDrift"))
                && githubDrift != null && Set.of("BASELINE_ESTABLISHED", "CLEAR")
                        .contains(text(githubDrift.get("status")))
                && github != null && sameText(githubDrift.get("current_capture_sha256"), github.get("content_sha256"))
                && apiReceipt != null && sameText(githubDrift.get("current_api_receipt_sha256"), apiReceipt.get("content_sha256"));
        boolean githubExact = githubExact(github, verified, driftExact);
        boolean layeredGithubPolicy = layeredGithubPolicy(github);
        JsonNode writerInstallation = get(verified, "writerInstallation");
        boolean writerInstallationExact = ok(verified, "writerInstallation") && github != null
                && github.path("rulesets").path("evidence_writer_app_id").asLong(Long.MIN_VALUE)
                        == WriterInstallationReceipts.WRITER_APP_ID
                && WriterInstallationReceipts.verifyWriterInstallationReceipt(
                        writerInstallation, github.path("repository").asText(), github.get("repository_id"));
        boolean githubExactWithCustody = githubExact && layeredGithubPolicy
                && settingsAuditorProofExact(github.get("settings_auditor_installation"),
                        text(github.get("repository")), github.get("repository_id"),
                        text(github.path("settings_token_identity").get("token_kind")))
                && github.path("writer_environment_protection").path("verified").asBoolean(false)
                && !github.path("writer_environment_protection").path("can_admins_bypass").asBoolean(true)
                && environmentReviewSafe(github.path("writer_environment_protection"))
                && github.path("evidence_writer_secret").path("verified").asBoolean(false)
                && github.path("actions_permissions").path("verified").asBoolean(false)
                && writerInstallationExact;
        boolean activationBundleVerified = false;
        try {
            Path ledgerPath = custodyDirectory(verified.get("ledger"));
            Path replayPath = custodyDirectory(verified.get("replay"));
            ObjectNode evidencePaths = object();
            for (ObjectNode row : verified.values()) if (row.hasNonNull("path"))
                evidencePaths.put(text(row.get("id")), text(row.get("path")));
            if (!prospectiveExact || !rootVerified || !githubExactWithCustody
                    || ledgerPath == null || replayPath == null
                    || !ok(verified, "githubAttestation") || !ok(verified, "githubApiReceipt")
                    || !ok(verified, "githubCycleReceipt")
                    || !ok(verified, "githubAttestationKeyRegistry") || !driftExact) {
                throw fail("activation bundle prerequisites are incomplete");
            }
            ObjectNode activationOptions = object();
            activationOptions.set("publication", prospective.deepCopy());
            activationOptions.put("ledgerPath", ledgerPath.toString());
            activationOptions.put("replayPath", replayPath.toString());
            activationOptions.set("trustRoot", root.deepCopy());
            activationOptions.put("pinnedTrustRootFingerprint",
                    text(verified.get("trustRoot").get("pinned_trust_root_fingerprint")));
            activationOptions.put("pinnedGenesisFingerprint",
                    text(verified.get("trustRoot").get("pinned_trust_root_genesis_fingerprint")));
            if (previousRoot != null) activationOptions.set("previousTrustRoot", previousRoot.deepCopy());
            activationOptions.set("evidencePaths", evidencePaths);
            activationOptions.set("githubCapture", github.deepCopy());
            putArtifactPathAndHash(activationOptions, verified, "github", "githubCapturePath", "githubCaptureSha256");
            putArtifactPathAndHash(activationOptions, verified, "githubApiReceipt", "githubApiReceiptPath", "githubApiReceiptSha256");
            putArtifactPathAndHash(activationOptions, verified, "githubDrift", "githubDriftPath", "githubDriftSha256");
            activationOptions.put("githubCycleReceiptSha256", text(verified.get("githubCycleReceipt").get("byte_sha256")));
            putArtifactPathAndHash(activationOptions, verified, "githubAttestation", "githubAttestationPath", "githubAttestationSha256");
            activationOptions.put("githubAttestationPublicKeyFingerprint",
                    text(verified.get("githubAttestation").get("pinned_attestation_key_fingerprint")));
            activationOptions.put("githubAttestationKeyRegistryPath",
                    text(verified.get("githubAttestationKeyRegistry").get("path")));
            activationOptions.put("githubAttestationKeyRegistrySha256",
                    text(verified.get("githubAttestationKeyRegistry").get("content_sha256")));
            activationOptions.put("githubAttestationKeyRegistryByteSha256",
                    text(verified.get("githubAttestationKeyRegistry").get("byte_sha256")));
            activationOptions.put("nowAt", nowMs);
            activationOptions.put("expectedLineageSha256", text(prospective.get("lineage_sha256")));
            verifyActivationBundleV5(activationOptions);
            activationBundleVerified = true;
        } catch (RuntimeException ignored) {
            activationBundleVerified = false;
        }
        boolean activationExact = ok(verified, "activation")
                && "strategy-deployment-audit/1".equals(schema(verified, "activation"))
                && activation != null && !activation.path("blocked").asBoolean(true)
                && !activation.path("blocked_until_external_prerequisites").asBoolean(true)
                && activationBundleVerified;
        dimensions.add(dimension("activation", "Activation readiness",
                List.of(
                        check("activation.trust-root-signature",
                                "root bundle and pinned fingerprint are verified cryptographically",
                                rootVerified, pathEvidence(verified, "trustRoot")),
                        check("activation.publication-signature",
                                "signed evidence and decisions are cryptographically verified",
                                activationBundleVerified, pathEvidence(verified, "prospective"))),
                List.of(
                        check("activation.deployment-capture",
                                "deployment audit and GitHub settings are externally verified",
                                activationExact && githubExactWithCustody,
                                concat(pathEvidence(verified, "activation"), pathEvidence(verified, "github"),
                                        pathEvidence(verified, "writerInstallation"))),
                        check("activation.never-self-declared", "activation has no self-declared bypass",
                                activationExact && activation.path("checks").isObject()
                                        && allObjectValuesTrue(activation.path("checks")),
                                pathEvidence(verified, "activation")))));

        List<ObjectNode> criticalRows = new ArrayList<>();
        for (JsonNode row : dimensions) if (CRITICAL_DIMENSIONS.contains(text(row.get("id"))))
            criticalRows.add((ObjectNode) row);
        double testingScore = roundOne(criticalRows.stream().mapToDouble(row -> row.path("score").asDouble()).sum()
                / criticalRows.size());
        String testingStatus = criticalRows.stream().allMatch(row -> row.path("score").asDouble() >= 8)
                ? "READY" : testingScore >= 5 ? "LIMITED" : "BLOCKED";
        ObjectNode activationRow = null;
        for (JsonNode row : dimensions) if ("activation".equals(text(row.get("id")))) activationRow = (ObjectNode) row;

        ObjectNode result = object();
        result.put("schema", "strategy-readiness-audit/2");
        result.put("version", 2);
        result.put("generated_at", input.has("generatedAt")
                ? iso(input.get("generatedAt")) : JS_ISO.format(Instant.now()));
        result.put("basis", "EVIDENCE_DERIVED_OPERATIONAL");
        result.set("dimensions", dimensions);
        ObjectNode testing = result.putObject("strategy_testing_readiness");
        testing.put("score", testingScore);
        testing.put("status", testingStatus);
        testing.set("required_dimensions", arrayOfStrings(CRITICAL_DIMENSIONS));
        ArrayNode testingBlockers = testing.putArray("blockers");
        criticalRows.forEach(row -> row.path("blockers").forEach(testingBlockers::add));
        ObjectNode activationResult = result.putObject("activation");
        activationResult.put("status", text(activationRow.get("status")));
        activationResult.put("ready", activationRow.path("score").asDouble() >= 9
                && activationRow.path("blockers").isEmpty());
        activationResult.put("active_strategy_count", 0);
        ArrayNode verification = result.putArray("artifact_verification");
        for (ObjectNode artifact : artifacts) {
            ObjectNode row = verification.addObject();
            row.put("id", text(artifact.get("id")));
            putNullable(row, "path", artifact.get("path"));
            putNullable(row, "schema", artifact.get("schema"));
            row.put("verified", artifact.path("ok").asBoolean(false));
            putNullable(row, "byte_sha256", artifact.get("byte_sha256"));
            putNullable(row, "content_sha256", artifact.get("content_sha256"));
            row.set("failures", artifact.path("failures").deepCopy());
        }
        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        for (JsonNode dimension : dimensions) for (JsonNode blocker : dimension.path("blockers"))
            limitations.add(text(dimension.get("id")) + ":" + text(blocker));
        for (JsonNode failure : physicalChain.path("failures")) limitations.add("lineage:" + text(failure));
        for (JsonNode failure : opportunityChain.path("failures")) limitations.add("lineage:" + text(failure));
        result.set("limitations", arrayOfStrings(limitations));
        result = withHash(result);
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(result);
        return result;
    }

    public static String renderReadinessMarkdown(JsonNode audit) {
        if (audit == null || !"strategy-readiness-audit/2".equals(text(audit.get("schema")))
                || !sameText(audit.get("content_sha256"), textNode(ownHash(audit)))) {
            throw fail("invalid readiness audit");
        }
        List<String> lines = new ArrayList<>(List.of(
                "# Strategy readiness audit", "",
                "Generated: " + text(audit.get("generated_at")),
                "Basis: " + text(audit.get("basis")), "",
                "| Dimension | Capability | Operational | Overall | Status |",
                "|---|---:|---:|---:|---|"));
        for (JsonNode row : rows(audit.get("dimensions"))) {
            lines.add("| " + text(row.get("title")) + " | " + fixedOne(row.path("capability").path("score").asDouble())
                    + " | " + fixedOne(row.path("operational").path("score").asDouble())
                    + " | " + fixedOne(row.path("score").asDouble()) + " | "
                    + text(row.get("status")) + " |");
        }
        lines.add("");
        lines.add("Strategy-testing readiness: **"
                + fixedOne(audit.path("strategy_testing_readiness").path("score").asDouble()) + "/10 ("
                + text(audit.path("strategy_testing_readiness").get("status")) + ")**");
        lines.add("Activation: **" + text(audit.path("activation").get("status")) + "**");
        lines.add("");
        lines.add("## Dimension evidence");
        for (JsonNode row : rows(audit.get("dimensions"))) {
            lines.add("");
            lines.add("### " + text(row.get("title")) + " — " + text(row.get("status")));
            lines.add("");
            for (String kind : List.of("Capability", "Operational")) {
                JsonNode bucket = row.path(kind.toLowerCase(Locale.ROOT));
                lines.add("#### " + kind + " (" + bucket.path("earned").asInt() + "/"
                        + bucket.path("total").asInt() + " points)");
                for (JsonNode check : rows(bucket.get("checks"))) {
                    String evidence = check.path("evidence").isArray() && !check.path("evidence").isEmpty()
                            ? joinText(check.path("evidence"), ", ") : "none";
                    lines.add("- " + (check.path("passed").asBoolean(false) ? "PASS" : "FAIL") + " "
                            + text(check.get("id")) + ": " + text(check.get("description"))
                            + ". Evidence: " + evidence);
                }
            }
            lines.add("- Limitations/blockers: " + (row.path("blockers").isArray()
                    && !row.path("blockers").isEmpty() ? joinText(row.path("blockers"), ", ") : "none"));
        }
        lines.add("");
        lines.add("## Verified artifacts");
        for (JsonNode artifact : rows(audit.get("artifact_verification"))) {
            lines.add("- " + (artifact.path("verified").asBoolean(false) ? "PASS" : "FAIL") + " "
                    + text(artifact.get("id")) + ": " + orText(artifact.get("schema"), "unknown")
                    + "; bytes=" + orText(artifact.get("byte_sha256"), "none")
                    + "; content=" + orText(artifact.get("content_sha256"), "none")
                    + "; failures=" + (artifact.path("failures").isArray()
                    && !artifact.path("failures").isEmpty()
                    ? joinText(artifact.path("failures"), ", ") : "none"));
        }
        lines.add("");
        lines.add("## Aggregate limitations");
        JsonNode limitations = audit.get("limitations");
        if (limitations == null || limitations.isNull()) lines.add("- none");
        else for (JsonNode limitation : rows(limitations)) lines.add("- " + text(limitation));
        return String.join("\n", lines) + "\n";
    }

    public static ObjectNode writeReadinessAudit(String path, ObjectNode options) {
        return writeReadinessAudit(Path.of(path), options);
    }

    public static ObjectNode writeReadinessAudit(Path path, ObjectNode options) {
        ObjectNode audit = buildReadinessAuditV5(options);
        try {
            Files.writeString(path.toAbsolutePath().normalize(), NodePrettyJson.write(audit),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
        return audit;
    }

    public static ObjectNode signActionsAttestationV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        JsonNode fields = input.get("fields");
        String privateKeyPem = text(input.get("privateKeyPem"));
        if (fields == null || !fields.isObject() || privateKeyPem.isEmpty())
            throw fail("Actions attestation signing requires protected private key input");
        ObjectNode value = object();
        value.put("schema", "strategy-github-prospective-attestation/1");
        value.put("version", 1);
        fields.fields().forEachRemaining(entry -> value.set(entry.getKey(), entry.getValue().deepCopy()));
        value.put("protected", true);
        String publicPem = text(value.get("public_key_pem"));
        if (publicPem.isEmpty() || text(value.get("key_id")).isEmpty()
                || ActionsAttestationVerifierV5.publicKeyFingerprint(publicPem) == null) {
            throw fail("Actions attestation requires an exact Ed25519 public SPKI key and key id");
        }
        ObjectNode payload = attestationPayload(value);
        value.put("attestation_payload_sha256", hash(payload));
        value.put("signature", StrategyProspectiveV5.signPayload(payload, privateKeyPem));
        ObjectNode result = withHash(value);
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(result);
        return result;
    }

    public static boolean verifyActionsAttestation(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        return ActionsAttestationVerifierV5.verify(new ActionsAttestationVerifierV5.Request(
                input.get("attestation"), input.get("capture"), input.get("publication"),
                nullableText(input.get("bytesSha256")),
                parseTimestamp(input.get("nowMs"), "Actions attestation verification time is invalid"),
                nullableText(input.get("pinnedFingerprint")), nullableText(input.get("apiReceiptSha256")),
                nullableText(input.get("cycleReceiptSha256")),
                nullableText(input.get("ledgerPriorHeadSha256")),
                nullableText(input.get("ledgerNewHeadSha256")),
                input.hasNonNull("ledgerSequence") ? jsInteger(input.get("ledgerSequence")) : null,
                input.get("trustedKeyRegistry"), nullableText(input.get("trustedKeyRegistrySha256")),
                nullableText(input.get("trustedKeyRegistryByteSha256"))));
    }

    /** Exact activation-boundary checks, delegating cryptography to existing owners. */
    public static ObjectNode verifyActivationBundleV5(ObjectNode options) {
        ObjectNode input = options == null ? object() : options;
        long nowMs = input.has("nowAt") ? parseTimestamp(input.get("nowAt"),
                "activation verification time is invalid") : System.currentTimeMillis();
        JsonNode publication = input.get("publication");
        String expectedLineage = nullableText(input.get("expectedLineageSha256"));
        if (publication == null || publication.isNull()
                || (expectedLineage != null && !expectedLineage.equals(text(publication.get("lineage_sha256")))))
            throw fail("activation publication lineage mismatch");

        ObjectNode trustOptions = object();
        trustOptions.put("nowAt", nowMs);
        putNullable(trustOptions, "pinnedFingerprint", input.get("pinnedTrustRootFingerprint"));
        putNullable(trustOptions, "pinnedGenesisFingerprint", input.get("pinnedGenesisFingerprint"));
        if (input.hasNonNull("previousTrustRoot"))
            trustOptions.set("previousRoot", input.get("previousTrustRoot").deepCopy());
        StrategyProspectiveV5.verifyTrustRoot((ObjectNode) input.get("trustRoot"), trustOptions);

        JsonNode rawCapture = input.get("githubCapture");
        JsonNode capture = rawCapture != null && rawCapture.path("value").isObject()
                ? rawCapture.path("value") : rawCapture;
        if (capture == null || !"github-deployment-settings-capture/1".equals(text(capture.get("schema")))
                || !sameText(capture.get("content_sha256"), textNode(ownHash(capture))))
            throw fail("physical GitHub settings capture is required");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(capture);
        boolean auditorProofExact = settingsAuditorProofExact(capture.get("settings_auditor_installation"),
                text(capture.get("repository")), capture.get("repository_id"),
                text(capture.path("settings_token_identity").get("token_kind")))
                && settingsAuditorSecretExact(capture.get("settings_token_secret"),
                        text(capture.path("settings_token_identity").get("token_kind")));
        boolean branchAppPolicy = capture.path("branch_protection").path("restrictions")
                        .path("apps_verified").asBoolean(false)
                && size(capture.path("branch_protection").path("restrictions").get("apps")) > 0
                && size(capture.path("branch_protection").path("restrictions").get("users")) == 0
                && size(capture.path("branch_protection").path("restrictions").get("teams")) == 0;
        boolean rulesetAppPolicy = rulesetAppPolicy(capture);
        boolean legacyBranchPolicy = capture.path("branch_protection").path("api_status").asInt(-1) == 200
                && capture.path("branch_protection").path("enforce_admins").asBoolean(false)
                && capture.path("branch_protection").path("required_pull_request_reviews").asBoolean(false)
                && capture.path("branch_protection").path("required_status_checks").asBoolean(false)
                && !capture.path("branch_protection").path("allow_force_pushes").asBoolean(true)
                && !capture.path("branch_protection").path("allow_deletions").asBoolean(true)
                && branchAppPolicy;
        if (!capture.path("verified").asBoolean(false) || !auditorProofExact
                || !capture.path("repository_visibility_verified").asBoolean(false)
                || !Set.of("PUBLIC", "PRIVATE").contains(text(capture.get("repository_visibility")))
                || !"github-api".equals(text(capture.path("api_response").get("provider")))
                || !capture.path("branch_protection").path("verified").asBoolean(false)
                || (!legacyBranchPolicy && (!capture.path("rulesets").path("verified").asBoolean(false)
                        || !rulesetAppPolicy))
                || !capture.path("environment_protection").path("verified").asBoolean(false)
                || capture.path("environment_protection").path("can_admins_bypass").asBoolean(true)
                || !environmentReviewSafe(capture.path("environment_protection"))
                || !capture.path("actions_permissions").path("verified").asBoolean(false)
                || !capture.path("settings_token_secret").path("verified").asBoolean(false)
                || !capture.path("settings_token_identity").path("verified").asBoolean(false)
                || !"APP".equals(text(capture.path("settings_token_identity").get("token_kind")))
                || capture.path("settings_token_identity").path("app_id").asLong(Long.MIN_VALUE)
                        != SETTINGS_AUDITOR_APP_ID
                || !capture.path("oidc_signature_verified").asBoolean(false)
                || !capture.path("oidc_subject_restricted").asBoolean(false)
                || jsTruthy(capture.get("blocked_reason")))
            throw fail("GitHub custody/protection is unavailable; activation remains blocked");

        Path capturePath = requiredPhysical(input, "githubCapturePath", "githubCaptureSha256",
                "physical GitHub settings capture bytes are required");
        byte[] captureBytes = readBytes(capturePath);
        String captureBytesSha256 = hash(captureBytes);
        JsonNode captureOnDisk = parseJson(captureBytes, "GitHub settings capture bytes are not JSON");
        if (!captureBytesSha256.equals(text(input.get("githubCaptureSha256")))
                || !sameText(captureOnDisk.get("content_sha256"), capture.get("content_sha256")))
            throw fail("GitHub settings capture byte/content binding failed");

        JsonNode evidencePaths = input.path("evidencePaths");
        JsonNode writerPathNode = first(evidencePaths.get("writerInstallation"),
                evidencePaths.get("writer_installation"), evidencePaths.get("githubWriterInstallation"));
        if (writerPathNode == null || text(writerPathNode).isEmpty()
                || !Files.exists(Path.of(text(writerPathNode)).toAbsolutePath().normalize()))
            throw fail("physical writer-App installation receipt is required");
        Path writerPath = Path.of(text(writerPathNode)).toAbsolutePath().normalize();
        byte[] writerBytes = readBytes(writerPath);
        String writerSha = hash(writerBytes);
        JsonNode writer = parseJson(writerBytes, "writer-App installation receipt is not JSON");
        if (capture.path("rulesets").path("evidence_writer_app_id").asLong(Long.MIN_VALUE)
                    != WriterInstallationReceipts.WRITER_APP_ID
                || !WriterInstallationReceipts.verifyWriterInstallationReceipt(writer,
                        text(capture.get("repository")), capture.get("repository_id")))
            throw fail("writer-App installation receipt is invalid or not bound to capture");

        Path apiPath = requiredPhysical(input, "githubApiReceiptPath", "githubApiReceiptSha256",
                "physical GitHub API receipt bytes are required");
        byte[] apiBytes = readBytes(apiPath);
        if (!hash(apiBytes).equals(text(input.get("githubApiReceiptSha256"))))
            throw fail("GitHub API receipt byte hash mismatch");
        JsonNode api = parseJson(apiBytes, "GitHub API receipt is not JSON");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(api);
        if (!apiReceiptMatchesCapture(api, capture, rulesetAppPolicy))
            throw fail("GitHub API receipt is blocked or does not match the physical capture");

        Path driftPath = requiredPhysical(input, "githubDriftPath", "githubDriftSha256",
                "physical GitHub settings drift evidence is required");
        byte[] driftBytes = readBytes(driftPath);
        if (!hash(driftBytes).equals(text(input.get("githubDriftSha256"))))
            throw fail("GitHub settings drift evidence byte hash mismatch");
        JsonNode drift = parseJson(driftBytes, "GitHub settings drift evidence is not JSON");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(drift);
        if (!"github-settings-drift-evidence/1".equals(text(drift.get("schema")))
                || !sameText(drift.get("content_sha256"), textNode(ownHash(drift)))
                || !Set.of("BASELINE_ESTABLISHED", "CLEAR").contains(text(drift.get("status")))
                || !sameText(drift.get("current_capture_sha256"), capture.get("content_sha256"))
                || !sameText(drift.get("current_api_receipt_sha256"), api.get("content_sha256")))
            throw fail("GitHub settings drift demotes activation");

        Path registryPath = requiredPhysical(input, "githubAttestationKeyRegistryPath",
                "githubAttestationKeyRegistrySha256",
                "separate physical Actions attestation key registry is required");
        byte[] registryBytes = readBytes(registryPath);
        String registryByteSha = hash(registryBytes);
        if (input.hasNonNull("githubAttestationKeyRegistryByteSha256")
                && !registryByteSha.equals(text(input.get("githubAttestationKeyRegistryByteSha256"))))
            throw fail("trusted Actions key registry byte hash mismatch");
        JsonNode registry = parseJson(registryBytes, "trusted Actions key registry is not JSON");
        if (!"strategy-github-attestation-key-registry/1".equals(text(registry.get("schema")))
                || !sameText(registry.get("content_sha256"), textNode(ownHash(registry)))
                || !sameText(registry.get("content_sha256"), input.get("githubAttestationKeyRegistrySha256")))
            throw fail("trusted Actions key registry content binding failed");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(registry);

        String attestationPathValue = text(input.get("githubAttestationPath"));
        if (attestationPathValue.isEmpty() || text(input.get("githubAttestationSha256")).isEmpty()
                || !Files.exists(Path.of(attestationPathValue).toAbsolutePath().normalize()))
            throw fail("external GitHub/OIDC attestation is required");
        byte[] attestationBytes = readBytes(Path.of(attestationPathValue).toAbsolutePath().normalize());
        if (!hash(attestationBytes).equals(text(input.get("githubAttestationSha256"))))
            throw fail("GitHub attestation byte hash mismatch");
        JsonNode attestation = parseJson(attestationBytes, "GitHub attestation is not JSON");
        String cycleSha = text(input.get("githubCycleReceiptSha256"));
        List<String> requiredEvidence = List.of(text(input.get("githubAttestationSha256")),
                captureBytesSha256, text(input.get("githubApiReceiptSha256")), cycleSha,
                registryByteSha, text(input.get("githubDriftSha256")), writerSha);
        if (!validHash(cycleSha) || !publication.path("evidence").isArray()
                || requiredEvidence.stream().anyMatch(required -> !evidenceContains(publication, required)))
            throw fail("activation publication evidence inventory omits settings/API/drift/cycle/attestation/key-registry/writer-installation bytes");

        ObjectNode attestationOptions = object();
        attestationOptions.set("attestation", attestation);
        attestationOptions.set("capture", capture);
        attestationOptions.set("publication", publication);
        attestationOptions.put("bytesSha256", captureBytesSha256);
        attestationOptions.put("nowMs", nowMs);
        putNullable(attestationOptions, "pinnedFingerprint", input.get("githubAttestationPublicKeyFingerprint"));
        attestationOptions.put("apiReceiptSha256", text(input.get("githubApiReceiptSha256")));
        attestationOptions.put("cycleReceiptSha256", cycleSha);
        attestationOptions.set("trustedKeyRegistry", registry);
        attestationOptions.put("trustedKeyRegistrySha256", text(input.get("githubAttestationKeyRegistrySha256")));
        attestationOptions.put("trustedKeyRegistryByteSha256", registryByteSha);
        verifyActionsAttestation(attestationOptions);

        ObjectNode prospectiveOptions = object();
        prospectiveOptions.put("ledgerPath", text(input.get("ledgerPath")));
        prospectiveOptions.put("replayPath", text(input.get("replayPath")));
        prospectiveOptions.set("trustRoot", input.get("trustRoot").deepCopy());
        putNullable(prospectiveOptions, "pinnedTrustRootFingerprint", input.get("pinnedTrustRootFingerprint"));
        putNullable(prospectiveOptions, "pinnedTrustRootGenesisFingerprint", input.get("pinnedGenesisFingerprint"));
        if (input.hasNonNull("previousTrustRoot"))
            prospectiveOptions.set("previousTrustRoot", input.get("previousTrustRoot").deepCopy());
        prospectiveOptions.set("evidencePaths", evidencePaths.deepCopy());
        prospectiveOptions.put("nowAt", nowMs);
        ObjectNode publicationResult = StrategyProspectiveV5.verifyProspectivePublication(
                (ObjectNode) publication, prospectiveOptions);
        if (parseTimestamp(publication.get("lease_expires_at"), "activation publication lease expired") <= nowMs)
            throw fail("activation publication lease expired");
        ObjectNode result = object();
        result.put("verified", true);
        result.put("activation", "VERIFIED_BUT_NO_STRATEGY_AUTHORIZATION");
        result.put("strategy_authorization", "REQUIRED");
        result.set("publication", publicationResult);
        ObjectNode githubResult = result.putObject("github");
        githubResult.put("verified", true);
        githubResult.put("settings_content_sha256", text(capture.get("content_sha256")));
        githubResult.put("attestation_content_sha256", text(attestation.get("content_sha256")));
        putNullable(githubResult, "attestation_key_fingerprint",
                input.get("githubAttestationPublicKeyFingerprint"));
        return result;
    }

    private static ObjectNode physicalOpportunityChain(Map<String, ObjectNode> verified, JsonNode run) {
        ArrayNode failures = array();
        ObjectNode graph = semanticProductionArtifact(
                productionArtifact(verified, "strategy-v5-feature-dag/1", value -> true),
                "FEATURE_GRAPH");
        ObjectNode domain = semanticProductionArtifact(
                productionArtifact(verified, "strategy-v5-opportunity-domain/1", value ->
                        value.path("domain_complete").asBoolean(false)
                                && number(value.get("branch_count")) == size(value.get("branches"))
                                && size(value.get("branches")) > 0), "OPPORTUNITY_DOMAIN");
        ObjectNode envelope = semanticProductionArtifact(
                productionArtifact(verified, "strategy-v5-opportunity-envelope/2", value ->
                        value.path("windows").isArray() && !value.path("windows").isEmpty()),
                "OPPORTUNITY_ENVELOPE");
        if (graph != null && graph.path("semantic_invalid").asBoolean(false))
            failures.add("OPPORTUNITY_FEATURE_GRAPH_SEMANTIC_VALIDATION_FAILED");
        if (domain != null && domain.path("semantic_invalid").asBoolean(false))
            failures.add("OPPORTUNITY_DOMAIN_SEMANTIC_VALIDATION_FAILED");
        if (envelope != null && envelope.path("semantic_invalid").asBoolean(false))
            failures.add("OPPORTUNITY_ENVELOPE_SEMANTIC_VALIDATION_FAILED");
        ObjectNode plan = productionArtifact(verified, "strategy-v5-feature-plan/1", value -> true);
        ObjectNode hydration = productionArtifact(verified, "strategy-v5-opportunity-hydration/2",
                value -> value.path("windows").isArray() && !value.path("windows").isEmpty());
        ObjectNode physical = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-v5-opportunity-hydration/1".equals(text(row.get("schema")))
                && "STAGING_COMPLETE".equals(text(row.path("value").get("status")))
                && "STAGING".equals(text(row.path("value").get("storage_role")))
                && !row.path("value").path("authoritative").asBoolean(true));
        ObjectNode partitionSet = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-v5-execution-partition-set/1".equals(text(row.get("schema"))));
        if (graph == null || plan == null || !sameText(graph.path("value").get("content_sha256"),
                plan.path("value").get("graph_sha256"))) failures.add("FEATURE_GRAPH_PLAN_LINEAGE_NOT_REOPENED");
        if (domain == null) failures.add("OPPORTUNITY_DOMAIN_PHYSICAL_DEPENDENCY_MISSING");
        if (envelope == null) failures.add("OPPORTUNITY_ENVELOPE_V2_PHYSICAL_DEPENDENCY_MISSING");
        if (hydration == null) failures.add("OPPORTUNITY_HYDRATION_V2_PHYSICAL_DEPENDENCY_MISSING");
        if (physical == null) failures.add("OPPORTUNITY_HYDRATION_1_PHYSICAL_DEPENDENCY_MISSING");
        if (domain != null && envelope != null) {
            if (!sameText(envelope.path("value").get("opportunity_domain_sha256"),
                    domain.path("value").get("content_sha256")))
                failures.add("OPPORTUNITY_DOMAIN_ENVELOPE_LINEAGE_MISMATCH");
            if (plan != null && !sameText(envelope.path("value").get("plan_sha256"),
                    plan.path("value").get("content_sha256")))
                failures.add("OPPORTUNITY_PLAN_ENVELOPE_LINEAGE_MISMATCH");
            if (graph != null && !sameText(envelope.path("value").get("graph_sha256"),
                    graph.path("value").get("content_sha256")))
                failures.add("OPPORTUNITY_GRAPH_ENVELOPE_LINEAGE_MISMATCH");
        }
        if (hydration != null && envelope != null) {
            if (!sameText(hydration.path("value").get("envelope_sha256"),
                    envelope.path("value").get("content_sha256")))
                failures.add("OPPORTUNITY_ENVELOPE_HYDRATION_LINEAGE_MISMATCH");
            if (size(hydration.path("value").get("windows")) != size(envelope.path("value").get("windows"))
                    || rows(hydration.path("value").get("windows")).stream().anyMatch(window ->
                            !"COMPLETE".equals(text(window.get("lifecycle_status")))
                                    || !window.path("eligible").asBoolean(false)))
                failures.add("OPPORTUNITY_HYDRATION_INCOMPLETE_OR_INELIGIBLE");
            if (!validHash(hydration.path("value").get("physical_hydration_sha256")))
                failures.add("OPPORTUNITY_PHYSICAL_HYDRATION_HASH_MISSING");
        }
        String physicalPartitionRoot = null;
        if (physical != null && hydration != null) {
            JsonNode value = physical.path("value");
            if (!sameText(hydration.path("value").get("physical_hydration_sha256"),
                    value.get("content_sha256")))
                failures.add("OPPORTUNITY_PHYSICAL_HYDRATION_CONTENT_BINDING_FAILED");
            if (!value.path("hydrated_before_outcomes").asBoolean(false)
                    || !value.path("captures").isArray() || value.path("captures").isEmpty()
                    || rows(value.get("captures")).stream().anyMatch(capture ->
                            !capture.path("coverage").path("complete").asBoolean(false)
                                    || !validHash(capture.path("partition").get("sha256"))))
                failures.add("OPPORTUNITY_PHYSICAL_HYDRATION_COVERAGE_NOT_REOPENED");
            List<Path> bases = new ArrayList<>();
            Path physicalPath = nullablePath(physical.get("path"));
            if (physicalPath != null && physicalPath.getParent() != null) bases.add(physicalPath.getParent());
            Path rootReference = nullablePath(value.get("root_reference"));
            if (rootReference != null) bases.add(rootReference);
            for (JsonNode capture : rows(value.get("captures"))) {
                JsonNode partition = capture.path("partition");
                List<Path> candidates = resolveCandidates(partition.get("path"), bases);
                boolean reopened = candidates.stream().anyMatch(candidate -> physicalPartition(candidate,
                        partition.get("sha256"), partition.get("bytes")));
                if (!reopened) failures.add("OPPORTUNITY_PHYSICAL_PARTITION_BYTES_NOT_REOPENED");
            }
            List<String> hashes = rows(value.get("captures")).stream()
                    .map(capture -> text(capture.path("partition").get("sha256")))
                    .filter(StrategyReadinessV5::validHash).sorted().toList();
            if (!hashes.isEmpty()) physicalPartitionRoot = hash(arrayOfStrings(hashes));
            if (physicalPartitionRoot != null && !physicalPartitionRoot.equals(
                    text(hydration.path("value").get("partition_set_sha256"))))
                failures.add("OPPORTUNITY_PARTITION_SET_PHYSICAL_HASH_MISMATCH");
        }
        if (partitionSet != null && hydration != null) {
            JsonNode partitions = partitionSet.path("value").get("partitions");
            List<String> hashes = rows(partitions).stream().map(row -> text(row.get("sha256")))
                    .filter(StrategyReadinessV5::validHash).sorted().toList();
            if (partitionSet.path("value").path("fixture_only").asBoolean(true)
                    || !"AUTHORITATIVE".equals(text(partitionSet.path("value").get("provenance")))
                    || number(partitionSet.path("value").get("partition_count")) != size(partitions)
                    || hashes.isEmpty() || !hash(arrayOfStrings(hashes)).equals(
                            text(hydration.path("value").get("partition_set_sha256"))))
                failures.add("OPPORTUNITY_PARTITION_SET_LINEAGE_MISMATCH");
        }
        boolean runBinding = run != null && "AUTHORITATIVE_RECOMPUTED".equals(text(run.get("provenance")))
                && envelope != null && sameText(run.path("lineage").get("envelope_sha256"),
                        envelope.path("value").get("content_sha256"));
        if (!runBinding) failures.add("RUN_OPPORTUNITY_ENVELOPE_LINEAGE_MISSING_OR_MISMATCHED");
        Set<String> contractFailures = Set.of("FEATURE_GRAPH_PLAN_LINEAGE_NOT_REOPENED",
                "OPPORTUNITY_FEATURE_GRAPH_SEMANTIC_VALIDATION_FAILED",
                "OPPORTUNITY_DOMAIN_SEMANTIC_VALIDATION_FAILED",
                "OPPORTUNITY_ENVELOPE_SEMANTIC_VALIDATION_FAILED",
                "OPPORTUNITY_DOMAIN_PHYSICAL_DEPENDENCY_MISSING",
                "OPPORTUNITY_ENVELOPE_V2_PHYSICAL_DEPENDENCY_MISSING",
                "OPPORTUNITY_HYDRATION_V2_PHYSICAL_DEPENDENCY_MISSING",
                "OPPORTUNITY_DOMAIN_ENVELOPE_LINEAGE_MISMATCH",
                "OPPORTUNITY_PLAN_ENVELOPE_LINEAGE_MISMATCH",
                "OPPORTUNITY_GRAPH_ENVELOPE_LINEAGE_MISMATCH",
                "OPPORTUNITY_ENVELOPE_HYDRATION_LINEAGE_MISMATCH",
                "OPPORTUNITY_HYDRATION_INCOMPLETE_OR_INELIGIBLE",
                "OPPORTUNITY_PHYSICAL_HYDRATION_HASH_MISSING");
        boolean capability = graph != null && plan != null && domain != null && envelope != null
                && hydration != null && rows(failures).stream().noneMatch(row -> contractFailures.contains(text(row)));
        boolean physicalComplete = physical != null && rows(failures).stream().noneMatch(row ->
                text(row).startsWith("OPPORTUNITY_PHYSICAL_") || text(row).startsWith("OPPORTUNITY_PARTITION_"));
        ObjectNode result = object();
        result.put("capability", capability);
        result.put("operational", capability && physicalComplete && runBinding);
        result.put("physical_complete", physicalComplete);
        result.set("failures", failures);
        setNullable(result, "graph", graph); setNullable(result, "plan", plan);
        setNullable(result, "domain", domain); setNullable(result, "envelope", envelope);
        setNullable(result, "hydration", hydration); setNullable(result, "physical", physical);
        setNullable(result, "partitionSet", partitionSet);
        return result;
    }

    private static ObjectNode physicalReadinessChain(Map<String, ObjectNode> verified,
            JsonNode run, JsonNode data, JsonNode wfo, JsonNode overfit, JsonNode portfolio,
            Path dataPath) {
        ArrayNode failures = array();
        ObjectNode dataRow = verified.get("data"), runRow = verified.get("run"),
                wfoRow = verified.get("wfo"), overfitRow = verified.get("overfit"),
                portfolioRow = verified.get("portfolio");
        List<Path> dataBases = new ArrayList<>();
        if (dataPath != null && dataPath.getParent() != null) dataBases.add(dataPath.getParent());
        Path sourcePath = data == null ? null : nullablePath(data.path("source_manifest_reference").get("path"));
        if (sourcePath != null && sourcePath.getParent() != null) dataBases.add(sourcePath.getParent());
        if (dataRow == null || !dataRow.path("ok").asBoolean(false) || data == null
                || !"strategy-v5-separated-artifacts/1".equals(text(data.get("schema"))))
            failures.add("DATA_MANIFEST_NOT_VERIFIED");
        if (data != null) {
            ObjectNode root = object();
            for (String field : List.of("plan_sha256", "predictor_registry_sha256", "source_manifest_sha256",
                    "source_manifest_reference", "source_dataset_root_sha256", "transformation_code_sha256",
                    "label_code_sha256", "execution_code_sha256", "config_sha256", "precommit_sha256",
                    "envelope_sha256", "artifacts")) if (data.has(field)) root.set(field, data.get(field).deepCopy());
            if (!sameText(data.get("dataset_root_sha256"), textNode(hash(root))))
                failures.add("DATASET_ROOT_RECOMPUTATION_FAILED");
        }
        ObjectNode source = physicalReference(data == null ? null : data.get("source_manifest_reference"),
                dataBases, "SOURCE_MANIFEST");
        if (!source.path("ok").asBoolean(false)) source.path("failures").forEach(failures::add);
        else {
            JsonNode sourceValue = source.get("value");
            if (sourceValue != null && sourceValue.hasNonNull("schema")
                    && !text(sourceValue.get("schema")).startsWith("strategy-v5-"))
                failures.add("SOURCE_MANIFEST_SCHEMA_UNEXPECTED");
            if (sourceValue != null && data != null && !sameText(sourceValue.get("content_sha256"),
                    data.get("source_manifest_sha256"))) failures.add("SOURCE_MANIFEST_LINEAGE_MISMATCH");
        }
        ObjectNode conversion = physicalReference(data == null ? null
                        : data.path("conversion").get("source_artifact_manifest_reference"),
                dataBases, "PARQUET_SOURCE_MANIFEST");
        if (data != null && "PARQUET".equals(text(data.get("format")))
                && (!conversion.path("ok").asBoolean(false)
                || !sameText(conversion.path("value").get("content_sha256"),
                        data.path("conversion").get("source_artifact_manifest_sha256")))) {
            if (!conversion.path("failures").isEmpty()) conversion.path("failures").forEach(failures::add);
            else failures.add("PARQUET_SOURCE_MANIFEST_LINEAGE_MISMATCH");
        }
        if (!physicalDataArtifactSet(data, dataPath == null ? null : textNode(dataPath.toString())))
            failures.add("PHYSICAL_PARQUET_REOPEN_FAILED");
        ObjectNode rolePaths = object();
        for (String role : DATA_ROLES) {
            JsonNode artifact = data == null ? null : data.path("artifacts").get(role);
            if (artifact == null || artifact.isNull() || artifact.isMissingNode()) {
                failures.add("DATA_ROLE_" + role.toUpperCase(Locale.ROOT) + "_MISSING");
                continue;
            }
            Path found = resolveCandidates(artifact.get("path"), dataBases).stream()
                    .filter(candidate -> Files.exists(candidate) && hashFile(candidate).equals(text(artifact.get("sha256"))))
                    .findFirst().orElse(null);
            if (found == null) failures.add("DATA_ROLE_" + role.toUpperCase(Locale.ROOT) + "_BYTES_UNBOUND");
            else rolePaths.put(role, found.toString());
            if (!validHash(artifact.get("derivation_receipt_sha256"))
                    || text(artifact.get("derivation_receipt_path")).isEmpty())
                failures.add("DATA_ROLE_" + role.toUpperCase(Locale.ROOT) + "_RECEIPT_UNBOUND");
        }
        boolean core = dataRow != null && dataRow.path("ok").asBoolean(false)
                && runRow != null && runRow.path("ok").asBoolean(false) && failures.isEmpty()
                && run != null && data != null
                && sameText(run.get("manifest_sha256"), dataRow.get("content_sha256"))
                && sameText(run.path("lineage").get("manifest_sha256"), run.get("manifest_sha256"))
                && DATA_ROLES.stream().allMatch(role -> sameText(
                        run.path("lineage").get(role + "_rows_sha256"),
                        data.path("artifacts").path(role).get("sha256")));
        boolean roleBinding = core && sameText(run.path("lineage").get("feature_rows_sha256"),
                data.path("artifacts").path("feature").get("sha256"))
                && sameText(run.path("lineage").get("label_rows_sha256"),
                        data.path("artifacts").path("label").get("sha256"))
                && sameText(run.path("lineage").get("execution_rows_sha256"),
                        data.path("artifacts").path("execution").get("sha256"))
                && sameText(run.path("lineage").get("mark_rows_sha256"),
                        data.path("artifacts").path("mark").get("sha256"));
        if (!roleBinding) failures.add("RUN_DATA_ROLE_LINEAGE_MISMATCH");
        ObjectNode candidateSet = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-candidate-set/5".equals(text(row.get("schema"))) && run != null
                && sameText(row.path("value").get("content_sha256"),
                        run.path("lineage").get("candidate_set_sha256")));
        if (candidateSet == null) failures.add("CANDIDATE_SET_PHYSICAL_DEPENDENCY_MISSING");
        JsonNode expectedHead = first(wfo == null ? null : wfo.get("exposure_head_sha256"),
                wfo == null ? null : wfo.path("lineage").get("exposure_head_sha256"));
        ObjectNode exposureHead = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-v5-statistical-exposure-head/1".equals(text(row.get("schema")))
                && sameText(row.path("value").get("content_sha256"), expectedHead));
        if (exposureHead == null) failures.add("EXPOSURE_HEAD_PHYSICAL_DEPENDENCY_MISSING");
        boolean wfoChain = wfoRow != null && wfoRow.path("ok").asBoolean(false)
                && wfo != null && wfoLineageMatches(wfo, data, run) && exposureHead != null;
        if (!wfoChain) failures.add("WFO_PHYSICAL_LINEAGE_NOT_REOPENED");
        boolean overfitChain = overfitRow != null && overfitRow.path("ok").asBoolean(false)
                && overfit != null && exposureHead != null
                && sameText(overfit.get("exposure_head_sha256"),
                        exposureHead.path("value").get("content_sha256"))
                && (!overfit.hasNonNull("vector_inventory_sha256")
                        || validHash(overfit.get("vector_inventory_sha256")));
        if (!overfitChain) failures.add("OVERFIT_PHYSICAL_LINEAGE_NOT_REOPENED");
        ObjectNode mark = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-mark-artifact/1".equals(text(row.get("schema"))) && portfolio != null
                && sameText(row.path("value").get("content_sha256"), portfolio.get("mark_artifact_sha256")));
        boolean markBinding = mark != null && portfolio != null
                && sameText(portfolio.get("mark_bytes_sha256"), mark.get("byte_sha256"))
                && (sameText(mark.path("value").get("source_manifest_sha256"),
                        dataRow == null ? null : dataRow.get("byte_sha256"))
                || sameText(mark.path("value").get("source_manifest_sha256"),
                        dataRow == null ? null : dataRow.get("content_sha256")));
        if (!markBinding) failures.add("PORTFOLIO_MARK_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED");
        ObjectNode stress = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && Set.of("strategy-portfolio-stress-input/1", "strategy-portfolio-stress-result/1")
                        .contains(text(row.get("schema"))) && portfolio != null
                && sameText(row.path("value").get("content_sha256"), portfolio.get("stress_artifact_sha256")));
        boolean stressBinding = stress != null && portfolio != null
                && sameText(stress.path("value").get("selected_trades_sha256"),
                        first(portfolio.path("lineage").get("selected_trades_sha256"),
                                portfolio.get("selected_trades_sha256")))
                && sameText(stress.path("value").get("evaluation_sha256"),
                        first(portfolio.path("lineage").get("evaluation_sha256"),
                                portfolio.get("evaluation_sha256")))
                && sameText(stress.path("value").get("execution_fills_sha256"),
                        first(portfolio.path("lineage").get("execution_fills_sha256"),
                                portfolio.get("execution_fills_sha256")))
                && (!"strategy-portfolio-stress-result/1".equals(text(stress.get("schema")))
                        || "AUTHORITATIVE_RECOMPUTED".equals(text(stress.path("value").get("provenance"))));
        if (!stressBinding) failures.add("PORTFOLIO_STRESS_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED");
        ObjectNode policy = findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && "strategy-portfolio-policy/2".equals(text(row.get("schema"))) && portfolio != null
                && sameText(row.path("value").get("content_sha256"),
                        portfolio.path("lineage").get("policy_sha256")));
        if (policy == null) failures.add("PORTFOLIO_POLICY_PHYSICAL_DEPENDENCY_MISSING");
        boolean selected = dependencyHash(verified, portfolio, "selected_trades_sha256",
                "strategy-selected-trades/1");
        boolean fills = dependencyHash(verified, portfolio, "execution_fills_sha256",
                "strategy-execution-fill-artifact/1");
        if (!selected) failures.add("PORTFOLIO_SELECTED_TRADES_PHYSICAL_DEPENDENCY_MISSING");
        if (!fills) failures.add("PORTFOLIO_EXECUTION_FILLS_PHYSICAL_DEPENDENCY_MISSING");
        boolean portfolioChain = portfolioRow != null && portfolioRow.path("ok").asBoolean(false)
                && portfolio != null && !arrayContains(failures,
                        "PORTFOLIO_MARK_PHYSICAL_DEPENDENCY_MISSING_OR_MISMATCHED")
                && stressBinding && policy != null && selected && fills;
        if (!portfolioChain) failures.add("PORTFOLIO_PHYSICAL_LINEAGE_NOT_REOPENED");
        boolean coreResult = core && roleBinding && rows(failures).stream().noneMatch(value ->
                text(value).startsWith("DATA_") || "PHYSICAL_PARQUET_REOPEN_FAILED".equals(text(value))
                        || "SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED".equals(text(value)));
        ObjectNode result = object();
        result.put("core", coreResult);
        result.put("data", dataRow != null && dataRow.path("ok").asBoolean(false)
                && !arrayContains(failures, "PHYSICAL_PARQUET_REOPEN_FAILED")
                && !arrayContains(failures, "DATASET_ROOT_RECOMPUTATION_FAILED"));
        result.put("wfo", wfoChain); result.put("overfit", overfitChain);
        result.put("portfolio", portfolioChain); result.set("failures", failures);
        result.set("rolePaths", rolePaths);
        return result;
    }

    private static void readVerifiedArtifact(ObjectNode spec, long nowMs, Set<String> seenIds,
            List<ObjectNode> artifacts) {
        String id = text(spec.get("id"));
        if (id.isEmpty()) return;
        if (!seenIds.add(id)) throw fail("duplicate evidence id " + id);
        String pathValue = text(spec.get("path"));
        Path path = pathValue.isEmpty() ? null : Path.of(pathValue).toAbsolutePath().normalize();
        if (path == null || !Files.exists(path)) return;
        if (!validHash(spec.get("sha256"))) return;
        byte[] bytes = readBytes(path);
        String byteSha = hash(bytes);
        ArrayNode failures = array();
        if (!byteSha.equals(text(spec.get("sha256")))) failures.add("ARTIFACT_BYTE_HASH_MISMATCH");
        JsonNode value = null;
        try { value = JsonHashes.mapper().readTree(bytes); }
        catch (IOException ignored) { failures.add("ARTIFACT_NOT_JSON"); }
        String valueSchema = value == null ? "" : text(value.get("schema"));
        if (value == null || !SUPPORTED.contains(valueSchema) || !spec.hasNonNull("schema")
                || !valueSchema.equals(text(spec.get("schema"))))
            failures.add("UNSUPPORTED_OR_MISMATCHED_SCHEMA");
        if (value != null && SUPPORTED.contains(valueSchema)) {
            try { ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value); }
            catch (RuntimeException ignored) { failures.add("CENTRAL_SCHEMA_VALIDATION_FAILED"); }
        }
        if (value != null && StrategyStatisticalV5.STAT_SCHEMA.containsValue(valueSchema)) {
            try { StrategyStatisticalV5.validateContractSchema(value); }
            catch (RuntimeException ignored) { failures.add("STATISTICAL_SEMANTIC_VALIDATION_FAILED"); }
        }
        if (value != null && (!validHash(value.get("content_sha256"))
                || !sameText(value.get("content_sha256"), textNode(ownHash(value)))))
            failures.add("CONTENT_HASH_MISMATCH");
        if (value != null && spec.hasNonNull("content_sha256")
                && !sameText(value.get("content_sha256"), spec.get("content_sha256")))
            failures.add("CONTENT_HASH_BINDING_MISMATCH");
        if (value != null && spec.has("max_age_ms")) {
            JsonNode createdNode = firstTruthy(value.get("generated_at"), value.get("captured_at"),
                    value.get("created_at"), textNode("0"));
            try {
                long created = parseTimestamp(createdNode, "invalid");
                if (created + (long) number(spec.get("max_age_ms")) < nowMs) failures.add("ARTIFACT_EXPIRED");
            } catch (RuntimeException ignored) { failures.add("ARTIFACT_EXPIRED"); }
        }
        ObjectNode result = object();
        result.put("id", id); result.put("path", path.toString());
        putNullable(result, "schema", value == null ? null : textNode(valueSchema));
        if (value != null) result.set("value", value);
        result.put("ok", failures.isEmpty()); result.put("byte_sha256", byteSha);
        putNullable(result, "content_sha256", value == null ? null : value.get("content_sha256"));
        putNullable(result, "pinned_trust_root_fingerprint", spec.get("pinned_trust_root_fingerprint"));
        putNullable(result, "pinned_trust_root_genesis_fingerprint",
                spec.get("pinned_trust_root_genesis_fingerprint"));
        putNullable(result, "pinned_attestation_key_fingerprint",
                spec.get("pinned_attestation_key_fingerprint"));
        result.set("failures", failures);
        artifacts.add(result);
        for (JsonNode dependency : rows(spec.get("dependencies")))
            if (dependency.isObject()) readVerifiedArtifact((ObjectNode) dependency, nowMs, seenIds, artifacts);
    }

    private static Manifest readEvidenceManifest(JsonNode spec) {
        if (spec == null || spec.isNull() || spec.isMissingNode()) return null;
        String pathValue = spec.isTextual() ? spec.asText() : text(spec.get("path"));
        Path path = pathValue.isEmpty() ? null : Path.of(pathValue).toAbsolutePath().normalize();
        if (path == null || !Files.exists(path)) throw fail("evidence manifest is missing");
        byte[] bytes = readBytes(path);
        String byteSha = hash(bytes);
        JsonNode value;
        try { value = JsonHashes.mapper().readTree(bytes); }
        catch (IOException error) { throw fail("evidence manifest is not valid JSON: " + error.getMessage()); }
        if (value == null || !"strategy-readiness-evidence-manifest/1".equals(text(value.get("schema")))
                || !sameText(value.get("content_sha256"), textNode(ownHash(value))))
            throw fail("evidence manifest schema/content hash is invalid");
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
        if (spec.isObject() && spec.hasNonNull("sha256")
                && !byteSha.equals(text(spec.get("sha256"))))
            throw fail("evidence manifest byte hash mismatch");
        if (spec.isObject() && spec.hasNonNull("content_sha256")
                && !sameText(spec.get("content_sha256"), value.get("content_sha256")))
            throw fail("evidence manifest content hash mismatch");
        return new Manifest(path, (ObjectNode) value, byteSha, text(value.get("content_sha256")));
    }

    private static boolean physicalDataArtifactSet(JsonNode data, JsonNode dataPath) {
        if (data == null || !data.path("artifacts").isObject()) return false;
        Path base = nullablePath(dataPath);
        if (base != null) base = base.getParent();
        List<Path> bases = base == null ? List.of() : List.of(base, base.getParent());
        for (String role : DATA_ROLES) {
            JsonNode artifact = data.path("artifacts").path(role);
            if (text(artifact.get("path")).isEmpty() || !validHash(artifact.get("sha256"))
                    || !"PARQUET".equals(text(artifact.get("format")))
                    || !"AUTHORITATIVE".equals(text(artifact.get("storage_role")))
                    || !artifact.path("authoritative").asBoolean(false)) return false;
            boolean exists = resolveCandidates(artifact.get("path"), bases).stream().anyMatch(path ->
                    parquetPhysicalFile(path, text(artifact.get("sha256")), artifact.get("bytes")));
            if (!exists) return false;
        }
        return true;
    }

    private static ObjectNode physicalReference(JsonNode reference, List<Path> bases, String label) {
        ObjectNode result = object(); ArrayNode failures = result.putArray("failures");
        if (reference == null || !reference.isObject() || text(reference.get("path")).isEmpty()
                || !validHash(reference.get("content_sha256")) || !validHash(reference.get("byte_sha256"))) {
            failures.add(label + ":REFERENCE_INCOMPLETE"); result.put("ok", false); return result;
        }
        Path path = resolveCandidates(reference.get("path"), bases).stream().filter(candidate ->
                Files.exists(candidate) && hashFile(candidate).equals(text(reference.get("byte_sha256"))))
                .findFirst().orElse(null);
        if (path == null) {
            failures.add(label + ":PHYSICAL_BYTES_MISSING_OR_TAMPERED"); result.put("ok", false); return result;
        }
        JsonNode value = null;
        try { value = JsonHashes.mapper().readTree(readBytes(path)); }
        catch (IOException ignored) { failures.add(label + ":NOT_JSON"); }
        if (value != null && (!validHash(value.get("content_sha256"))
                || !sameText(value.get("content_sha256"), textNode(ownHash(value)))
                || !sameText(value.get("content_sha256"), reference.get("content_sha256"))))
            failures.add(label + ":CONTENT_BINDING_FAILED");
        result.put("ok", failures.isEmpty()); result.put("path", path.toString());
        if (value != null) result.set("value", value);
        return result;
    }

    private static ObjectNode productionArtifact(Map<String, ObjectNode> verified, String schema,
            java.util.function.Predicate<JsonNode> predicate) {
        return findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && schema.equals(text(row.get("schema")))
                && !row.path("value").path("fixture_only").asBoolean(true)
                && "AUTHORITATIVE".equals(text(row.path("value").get("provenance")))
                && predicate.test(row.path("value")));
    }

    private static ObjectNode semanticProductionArtifact(ObjectNode row, String label) {
        if (row == null) return null;
        try {
            String schema = text(row.get("schema"));
            if ("strategy-v5-feature-dag/1".equals(schema))
                FeatureDagV5.validateFeatureGraphV5(row.get("value"));
            if ("strategy-v5-opportunity-domain/1".equals(schema))
                OpportunityV5.validateOpportunityDomainV5(row.get("value"));
            if ("strategy-v5-opportunity-envelope/2".equals(schema))
                OpportunityV5.validateOpportunityEnvelopeV5(row.get("value"));
            return row;
        } catch (RuntimeException ignored) {
            ObjectNode copy = row.deepCopy(); copy.put("semantic_invalid", true);
            copy.put("semantic_label", label); return copy;
        }
    }

    private static ObjectNode dimension(String id, String title,
            List<ObjectNode> capabilityChecks, List<ObjectNode> operationalChecks) {
        ObjectNode capability = summarize(capabilityChecks), operational = summarize(operationalChecks);
        double score = roundOne((capability.path("score").asDouble()
                + operational.path("score").asDouble()) / 2.0);
        ObjectNode result = object(); result.put("id", id); result.put("title", title);
        result.set("capability", capability); result.set("operational", operational);
        result.put("score", score); result.put("status", score >= 9 ? "READY" : score >= 5 ? "LIMITED" : "BLOCKED");
        ArrayNode blockers = result.putArray("blockers");
        for (ObjectNode check : concatChecks(capabilityChecks, operationalChecks))
            if (!check.path("passed").asBoolean(false)) blockers.add(text(check.get("id")));
        return result;
    }

    private static ObjectNode summarize(List<ObjectNode> checks) {
        int total = checks.stream().mapToInt(row -> row.path("points").asInt()).sum();
        int earned = checks.stream().filter(row -> row.path("passed").asBoolean(false))
                .mapToInt(row -> row.path("points").asInt()).sum();
        ObjectNode result = object(); result.put("score", total == 0 ? 0 : roundOne(earned * 10.0 / total));
        result.put("earned", earned); result.put("total", total);
        ArrayNode rows = result.putArray("checks"); checks.forEach(rows::add);
        return result;
    }

    private static ObjectNode check(String id, String description, boolean passed, List<String> evidence) {
        ObjectNode result = object(); result.put("id", id); result.put("description", description);
        result.put("points", 1); result.put("passed", passed);
        result.set("evidence", arrayOfStrings(evidence)); return result;
    }

    private static boolean verifyTrustRootForReadiness(Map<String, ObjectNode> verified,
            JsonNode root, JsonNode previousRoot, long nowMs) {
        try {
            if (!ok(verified, "trustRoot")
                    || !"strategy-prospective-trust-root/1".equals(schema(verified, "trustRoot"))) return false;
            ObjectNode args = object(); args.put("nowAt", nowMs);
            args.put("pinnedFingerprint", text(verified.get("trustRoot").get("pinned_trust_root_fingerprint")));
            args.put("pinnedGenesisFingerprint",
                    text(verified.get("trustRoot").get("pinned_trust_root_genesis_fingerprint")));
            if (previousRoot != null) args.set("previousRoot", previousRoot.deepCopy());
            return StrategyProspectiveV5.verifyTrustRoot((ObjectNode) root, args)
                    && sameText(verified.get("trustRoot").get("pinned_trust_root_fingerprint"),
                            root.get("pinned_fingerprint"))
                    && sameText(verified.get("trustRoot").get("pinned_trust_root_genesis_fingerprint"),
                            root.get("genesis_pinned_fingerprint"));
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean githubExact(JsonNode github, Map<String, ObjectNode> verified, boolean driftExact) {
        if (github == null) return false;
        String kind = text(github.path("settings_token_identity").get("token_kind"));
        boolean appOrRuleset = github.path("branch_protection").path("restrictions")
                        .path("apps_verified").asBoolean(false)
                && size(github.path("branch_protection").path("restrictions").get("apps")) > 0
                && size(github.path("branch_protection").path("restrictions").get("users")) == 0
                && size(github.path("branch_protection").path("restrictions").get("teams")) == 0
                || github.path("rulesets").path("api_status").asInt(-1) == 200
                && github.path("rulesets").path("protected_ref_matches").asBoolean(false)
                && github.path("rulesets").path("bypass_verified").asBoolean(false)
                && github.path("rulesets").path("actions_only_bypass_verified").asBoolean(false)
                && github.path("rulesets").path("enforcement_verified").asBoolean(false)
                && github.path("rulesets").path("rules_verified").asBoolean(false);
        return ok(verified, "github")
                && "github-deployment-settings-capture/1".equals(schema(verified, "github"))
                && github.path("verified").asBoolean(false)
                && github.path("repository_visibility_verified").asBoolean(false)
                && Set.of("PUBLIC", "PRIVATE").contains(text(github.get("repository_visibility")))
                && !text(github.get("repository")).isEmpty() && !github.path("repository_id").isNull()
                && !text(github.get("evidence_branch_head_sha256")).isEmpty()
                && github.path("actions_secret").path("verified").asBoolean(false)
                && settingsAuditorSecretExact(github.get("settings_token_secret"), kind)
                && github.path("settings_token_secret").path("verified").asBoolean(false)
                && github.path("settings_token_identity").path("verified").asBoolean(false)
                && "APP".equals(kind)
                && github.path("settings_token_identity").path("app_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_APP_ID
                && settingsAuditorProofExact(github.get("settings_auditor_installation"),
                        text(github.get("repository")), github.get("repository_id"), kind)
                && github.path("branch_protection").path("verified").asBoolean(false)
                && !github.path("branch_protection").path("allow_force_pushes").asBoolean(true)
                && !github.path("branch_protection").path("allow_deletions").asBoolean(true)
                && github.path("rulesets").path("verified").asBoolean(false) && appOrRuleset
                && github.path("environment_protection").path("verified").asBoolean(false)
                && github.path("oidc_signature_verified").asBoolean(false)
                && github.path("oidc_subject_restricted").asBoolean(false) && driftExact;
    }

    private static boolean layeredGithubPolicy(JsonNode github) {
        if (github == null) return false;
        JsonNode rulesets = github.path("rulesets");
        return rulesets.path("verified").asBoolean(false)
                && rulesets.path("layered_policy_verified").asBoolean(false)
                && rulesets.path("immutable_policy_verified").asBoolean(false)
                && rulesets.path("writer_gate_policy_verified").asBoolean(false)
                && rulesets.path("protected_ref_matches").asBoolean(false)
                && rulesets.path("enforcement_verified").asBoolean(false)
                && rulesets.path("rules_verified").asBoolean(false)
                && rulesets.path("actions_bypass_app_ids").isArray()
                && rulesets.path("actions_bypass_app_ids").isEmpty()
                && rulesets.path("layers").isArray()
                && rows(rulesets.get("layers")).stream().anyMatch(StrategyReadinessV5::immutableLayer)
                && rows(rulesets.get("layers")).stream().anyMatch(StrategyReadinessV5::writerGateLayer);
    }

    private static boolean rulesetAppPolicy(JsonNode capture) {
        JsonNode rulesets = capture.path("rulesets");
        return rulesets.path("api_status").asInt(-1) == 200
                && nonNegativeSafeInteger(rulesets.get("evidence_writer_app_id"))
                && rulesets.path("evidence_writer_app_id").asLong() > 0
                && rulesets.path("evidence_writer_credential_configured").asBoolean(false)
                && rulesets.path("actions_bypass_app_ids").isArray()
                && rulesets.path("actions_bypass_app_ids").isEmpty()
                && rulesets.path("protected_ref_matches").asBoolean(false)
                && rulesets.path("bypass_verified").asBoolean(false)
                && rulesets.path("actions_only_bypass_verified").asBoolean(false)
                && rulesets.path("immutable_policy_verified").asBoolean(false)
                && rulesets.path("writer_gate_policy_verified").asBoolean(false)
                && rulesets.path("layered_policy_verified").asBoolean(false)
                && rulesets.path("enforcement_verified").asBoolean(false)
                && rulesets.path("rules_verified").asBoolean(false)
                && rulesets.path("layers").isArray()
                && rows(rulesets.get("layers")).stream().anyMatch(StrategyReadinessV5::immutableLayer)
                && rows(rulesets.get("layers")).stream().anyMatch(StrategyReadinessV5::writerGateLayer);
    }

    private static boolean immutableLayer(JsonNode row) {
        return size(row.get("refs")) == 1 && "refs/heads/main".equals(text(row.path("refs").get(0)))
                && "deletion,non_fast_forward,pull_request".equals(joinText(row.get("rule_types"), ","))
                && size(row.get("bypass_actors")) == 0;
    }

    private static boolean writerGateLayer(JsonNode row) {
        return "WRITER_GATE".equals(text(row.get("layer")))
                && size(row.get("refs")) == 1
                && "refs/heads/strategy-v5-evidence".equals(text(row.path("refs").get(0)))
                && "pull_request,required_status_checks".equals(joinText(row.get("rule_types"), ","))
                && arrayContains(row.get("required_status_contexts"), "strategy-v5-evidence-custody")
                && arrayContainsNumber(row.get("required_status_check_integrations"), 15_368)
                && row.path("strict_status_checks").asBoolean(false)
                && jsInteger(row.path("pull_request_parameters").get("required_approving_review_count")) != null
                && jsInteger(row.path("pull_request_parameters").get("required_approving_review_count")) == 0
                && size(row.get("bypass_actors")) == 0;
    }

    private static boolean apiReceiptMatchesCapture(JsonNode api, JsonNode capture,
            boolean rulesetAppPolicy) {
        JsonNode endpoints = api.path("endpoints");
        boolean rulesetOnlyBranch404 = capture.path("branch_protection").path("api_status").asInt(-1) == 404
                && rulesetAppPolicy;
        boolean auditorRequired = "APP".equals(text(capture.path("settings_token_identity").get("token_kind")));
        List<String> required = new ArrayList<>(List.of("repository", "branch_protection", "branch_head",
                "environment_protection", "rulesets", "ruleset_details", "settings_token_identity",
                "settings_token_secret", "oidc_subject_restriction", "actions_permissions",
                "actions_selected_permissions", "actions_workflow_permissions"));
        if (auditorRequired) required.addAll(List.of("settings_auditor_app",
                "settings_auditor_installation", "settings_auditor_repositories"));
        JsonNode apiIdentity = api.path("settings_token_identity"), captureIdentity = capture.path("settings_token_identity");
        boolean identityMatches = fieldsEqual(apiIdentity, captureIdentity,
                "api_status", "app_id", "user_id", "login", "token_kind", "body_sha256", "verified");
        boolean secretMatches = sameText(api.path("settings_token_secret").get("name"),
                capture.path("settings_token_secret").get("name"))
                && same(api.path("settings_token_secret").get("verified"),
                        capture.path("settings_token_secret").get("verified"))
                && hash(orObject(api.get("settings_token_secret"))).equals(
                        hash(orObject(capture.get("settings_token_secret"))));
        boolean actionsMatches = api.path("actions_permissions").path("verified").asBoolean(false)
                && hash(orObject(api.get("actions_permissions"))).equals(
                        hash(orObject(capture.get("actions_permissions"))));
        JsonNode apiWriter = api.path("writer_environment_protection"), captureWriter = capture.path("writer_environment_protection");
        boolean writerEnvironmentMatches = apiWriter.path("verified").asBoolean(false)
                && captureWriter.path("verified").asBoolean(false)
                && !apiWriter.path("can_admins_bypass").asBoolean(true)
                && !captureWriter.path("can_admins_bypass").asBoolean(true)
                && environmentReviewSafe(apiWriter) && environmentReviewSafe(captureWriter)
                && hash(apiWriter).equals(hash(captureWriter));
        boolean writerSecretMatches = api.path("evidence_writer_secret").path("verified").asBoolean(false)
                && capture.path("evidence_writer_secret").path("verified").asBoolean(false)
                && hash(orObject(api.get("evidence_writer_secret"))).equals(
                        hash(orObject(capture.get("evidence_writer_secret"))));
        boolean rulesetsMatch = api.path("rulesets").path("layered_policy_verified").asBoolean(false)
                && hash(orObject(api.get("rulesets"))).equals(hash(orObject(capture.get("rulesets"))));
        boolean auditorMatches = !auditorRequired || settingsAuditorProofExact(
                api.get("settings_auditor_installation"), text(capture.get("repository")),
                capture.get("repository_id"), text(captureIdentity.get("token_kind")))
                && hash(orObject(api.get("settings_auditor_installation"))).equals(
                        hash(orObject(capture.get("settings_auditor_installation"))));
        boolean installationUnprovenPat = !api.path("installation_proof_verified").asBoolean(true)
                && "PAT".equals(text(captureIdentity.get("token_kind")))
                && endpoints.path("installation").path("status").asInt(-1) == 0;
        boolean installationValid = endpoints.path("installation").path("status").asInt(-1) == 200
                || installationUnprovenPat;
        boolean endpointsValid = required.stream().allMatch(key ->
                endpoints.path(key).path("status").asInt(-1) == 200
                        || key.equals("branch_protection") && rulesetOnlyBranch404);
        return "github-settings-api-receipt/1".equals(text(api.get("schema")))
                && sameText(api.get("content_sha256"), textNode(ownHash(api)))
                && sameText(api.get("repository"), capture.get("repository"))
                && sameText(api.get("evidence_branch"), capture.get("evidence_branch"))
                && sameText(api.get("repository_visibility"), capture.get("repository_visibility"))
                && api.path("repository_visibility_verified").asBoolean(false)
                && api.path("oidc_signature_verified").asBoolean(false)
                && api.path("verified").asBoolean(false)
                && api.path("actions_secret").path("verified").asBoolean(false)
                && hash(orObject(api.get("actions_secret"))).equals(hash(orObject(capture.get("actions_secret"))))
                && identityMatches && secretMatches && actionsMatches && writerEnvironmentMatches
                && writerSecretMatches && rulesetsMatch && auditorMatches && installationValid
                && size(api.get("blockers")) == 0 && endpointsValid;
    }

    private static boolean settingsAuditorSecretExact(JsonNode value, String tokenKind) {
        return !"APP".equals(tokenKind) || value != null
                && SETTINGS_AUDITOR_SECRET_NAME.equals(text(value.get("name")))
                && value.path("environment_status").asInt(-1) == 200
                && value.path("repository_status").asInt(-1) == 404
                && value.path("organization_status").asInt(-1) == 404
                && value.path("verified").asBoolean(false);
    }

    private static boolean settingsAuditorProofExact(JsonNode proof, String repository,
            JsonNode repositoryId, String tokenKind) {
        if (!"APP".equals(tokenKind)) return true;
        if (proof == null || !proof.isObject()) return false;
        String owner = repository == null ? "" : repository.split("/", -1)[0];
        return proof.path("verified").asBoolean(false)
                && proof.path("expected_app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && proof.path("expected_installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("expected_app_slug")))
                && proof.path("app_endpoint_status").asInt(-1) == 200
                && proof.path("installation_endpoint_status").asInt(-1) == 200
                && proof.path("repositories_endpoint_status").asInt(-1) == 200
                && proof.path("app_id").asLong(Long.MIN_VALUE) == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("app_slug")))
                && proof.path("installation_id").asLong(Long.MIN_VALUE)
                        == SETTINGS_AUDITOR_INSTALLATION_ID
                && "selected".equals(text(proof.get("repository_selection")))
                && exactPermissions(proof.get("permissions"))
                && exactPermissions(proof.get("installation_permissions"))
                && proof.path("events").isArray() && proof.path("events").isEmpty()
                && proof.path("installation_events").isArray()
                && proof.path("installation_events").isEmpty()
                && number(proof.path("account").get("id")) > 0
                && owner.equals(text(proof.path("account").get("login")))
                && number(proof.get("accessible_repository_count")) == 1
                && number(proof.path("accessible_repository").get("id")) == number(repositoryId)
                && repository.equals(text(proof.path("accessible_repository").get("full_name")));
    }

    private static boolean exactPermissions(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != SETTINGS_AUDITOR_PERMISSIONS.size())
            return false;
        return SETTINGS_AUDITOR_PERMISSIONS.entrySet().stream().allMatch(entry ->
                entry.getValue().equals(text(value.get(entry.getKey()))));
    }

    private static boolean wfoLineageMatches(JsonNode value, JsonNode data, JsonNode run) {
        JsonNode lineage = value == null ? null : value.path("lineage");
        return value != null && data != null && run != null
                && sameText(first(lineage.get("dataset_root_sha256"), value.get("dataset_root_sha256")),
                        data.get("dataset_root_sha256"))
                && sameText(first(lineage.get("candidate_set_sha256"), value.get("candidate_set_sha256")),
                        run.path("lineage").get("candidate_set_sha256"))
                && validHash(first(lineage.get("precommit_sha256"), value.get("precommit_sha256")))
                && validHash(first(lineage.get("experiment_sha256"), value.get("experiment_sha256")));
    }

    private static boolean wfoLineageExact(JsonNode value) {
        if (value == null) return false;
        JsonNode lineage = value.path("lineage");
        return List.of("dataset_root_sha256", "candidate_set_sha256", "precommit_sha256",
                "experiment_sha256").stream().allMatch(key -> validHash(first(lineage.get(key), value.get(key))));
    }

    private static boolean wfoFoldEvidenceExact(JsonNode value) {
        return value != null && value.path("folds").isArray() && value.path("folds").size() == 8
                && rows(value.get("folds")).stream().allMatch(fold ->
                        "EVALUATED".equals(text(fold.get("status")))
                                && validHash(fold.get("lineage_sha256"))
                                && fold.path("test").path("asset_decisions").isArray()
                                && !fold.path("test").path("asset_decisions").isEmpty()
                                && validHash(fold.path("test").get("vector_inventory_sha256"))
                                && fold.path("test").path("portfolio").get("pass") != null
                                && fold.path("test").path("portfolio").get("pass").isBoolean());
    }

    private static boolean overfitNumericsExact(JsonNode value) {
        return value != null && number(firstTruthy(value.get("sample_count"),
                        value.get("completed_episode_count"))) >= 30
                && number(value.get("search_adjusted_expectancy_r")) > 0
                && number(value.path("max_statistic").get("p_value")) <= 0.10
                && number(value.path("pbo").get("pbo")) <= 0.20
                && number(value.path("dsr").get("probability")) >= 0.95
                && validHash(value.get("exposure_head_sha256"))
                && validHash(value.get("vector_inventory_sha256"));
    }

    private static boolean dependencyHash(Map<String, ObjectNode> verified, JsonNode portfolio,
            String field, String schema) {
        if (portfolio == null) return false;
        JsonNode expected = first(portfolio.path("lineage").get(field), portfolio.get(field));
        return validHash(expected) && findArtifact(verified, row -> row.path("ok").asBoolean(false)
                && schema.equals(text(row.get("schema")))
                && sameText(row.path("value").get("content_sha256"), expected)) != null;
    }

    private static Path custodyDirectory(ObjectNode row) {
        if (row == null || !row.hasNonNull("path")) return null;
        Path candidate = Path.of(text(row.get("path"))).toAbsolutePath().normalize();
        if (Files.exists(candidate.resolve("HEAD.json"))) return candidate;
        Path parent = candidate.getParent();
        return parent != null && Files.exists(parent.resolve("HEAD.json")) ? parent : null;
    }

    private static ObjectNode attestationPayload(JsonNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove(List.of("signature", "content_sha256", "attestation_payload_sha256"));
        return copy;
    }

    private static boolean validFutureLease(JsonNode value, long nowMs) {
        try {
            long lease = parseTimestamp(value, "invalid");
            return lease > nowMs && lease - nowMs <= MAX_PROSPECTIVE_LEASE_MS;
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean parquetPhysicalFile(Path path, String expectedSha256, JsonNode expectedBytes) {
        try {
            if (!Files.isRegularFile(path)) return false;
            long size = Files.size(path);
            if (expectedBytes != null && !expectedBytes.isNull() && size != (long) number(expectedBytes)) return false;
            if (size < 8) return false;
            ByteBuffer header = ByteBuffer.allocate(4), footer = ByteBuffer.allocate(4);
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.read(header); channel.position(size - 4); channel.read(footer);
            }
            return "PAR1".equals(new String(header.array(), StandardCharsets.UTF_8))
                    && "PAR1".equals(new String(footer.array(), StandardCharsets.UTF_8))
                    && hashFile(path).equals(expectedSha256);
        } catch (RuntimeException | IOException ignored) { return false; }
    }

    private static boolean physicalPartition(Path path, JsonNode sha, JsonNode bytes) {
        try {
            return Files.isRegularFile(path)
                    && (bytes == null || !isJsIntegerNumber(bytes) || Files.size(path) == (long) number(bytes))
                    && hashFile(path).equals(text(sha));
        } catch (RuntimeException | IOException ignored) { return false; }
    }

    private static String hashFile(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private static Path requiredPhysical(ObjectNode input, String pathKey, String hashKey, String message) {
        String pathValue = text(input.get(pathKey)), hashValue = text(input.get(hashKey));
        if (pathValue.isEmpty() || !validHash(hashValue)) throw fail(message);
        Path path = Path.of(pathValue).toAbsolutePath().normalize();
        if (!Files.exists(path)) throw fail(message);
        return path;
    }

    private static JsonNode parseJson(byte[] bytes, String message) {
        try { return JsonHashes.mapper().readTree(bytes); }
        catch (IOException error) { throw fail(message); }
    }

    private static byte[] readBytes(Path path) {
        try { return Files.readAllBytes(path); }
        catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }

    private static long parseTimestamp(JsonNode value, String message) {
        try {
            if (value == null || value.isNull() || value.isMissingNode()) throw new IllegalArgumentException();
            if (value.isNumber()) {
                double number = value.doubleValue();
                if (!Double.isFinite(number)) throw new IllegalArgumentException();
                return (long) number;
            }
            String text = value.asText();
            try { return Instant.parse(text).toEpochMilli(); }
            catch (DateTimeParseException ignored) { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); }
        } catch (RuntimeException error) { throw fail(message); }
    }

    private static String iso(JsonNode value) {
        return JS_ISO.format(Instant.ofEpochMilli(parseTimestamp(value, "Invalid time value")));
    }

    private static boolean nonNegativeSafeInteger(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0 && value.longValue() <= MAX_SAFE_INTEGER;
    }

    private static Long jsInteger(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try {
            double number = value.isNumber() ? value.doubleValue() : Double.parseDouble(value.asText());
            if (!Double.isFinite(number) || Math.rint(number) != number
                    || Math.abs(number) > MAX_SAFE_INTEGER) return null;
            return (long) number;
        } catch (RuntimeException ignored) { return null; }
    }

    private static boolean isJsIntegerNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                && Math.rint(value.doubleValue()) == value.doubleValue()
                && Math.abs(value.doubleValue()) <= MAX_SAFE_INTEGER;
    }

    private static double number(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        String text = value.asText().trim();
        if (text.isEmpty()) return 0;
        try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static boolean jsTruthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.asText().isEmpty();
        return true;
    }

    private static boolean same(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) return Double.compare(left.doubleValue(), right.doubleValue()) == 0;
        return left.equals(right);
    }

    private static boolean sameText(JsonNode left, JsonNode right) {
        return left != null && right != null && text(left).equals(text(right));
    }

    private static boolean fieldsEqual(JsonNode left, JsonNode right, String... fields) {
        return Arrays.stream(fields).allMatch(field -> same(left.get(field), right.get(field)));
    }

    private static boolean containsAll(JsonNode array, List<String> required) {
        return array != null && array.isArray() && required.stream().allMatch(value -> arrayContains(array, value));
    }

    private static boolean arrayContains(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode row : array) if (row.isTextual() && value.equals(row.asText())) return true;
        return false;
    }

    private static boolean arrayContainsNumber(JsonNode array, long value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode row : array) if (row.isNumber() && row.doubleValue() == value) return true;
        return false;
    }

    private static boolean evidenceContains(JsonNode publication, String sha) {
        for (JsonNode row : rows(publication.get("evidence")))
            if (sha.equals(text(row.get("sha256")))) return true;
        return false;
    }

    private static boolean physicalDependency(Map<String, ObjectNode> map, String id,
            java.util.function.Predicate<ObjectNode> predicate) {
        return map.values().stream().anyMatch(row -> row.path("ok").asBoolean(false)
                && !id.equals(text(row.get("id"))) && predicate.test(row));
    }

    private static boolean allObjectValuesTrue(JsonNode object) {
        if (object == null || !object.isObject()) return false;
        var values = object.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (!value.isBoolean() || !value.asBoolean()) return false;
        }
        return true;
    }

    private static JsonNode get(Map<String, ObjectNode> verified, String id) {
        ObjectNode row = verified.get(id);
        return row == null ? null : row.get("value");
    }

    private static boolean ok(Map<String, ObjectNode> verified, String id) {
        ObjectNode row = verified.get(id);
        return row != null && row.path("ok").asBoolean(false);
    }

    private static String schema(Map<String, ObjectNode> verified, String id) {
        ObjectNode row = verified.get(id);
        return row == null ? "" : text(row.get("schema"));
    }

    private static ObjectNode findArtifact(Map<String, ObjectNode> map,
            java.util.function.Predicate<ObjectNode> predicate) {
        for (ObjectNode row : map.values()) if (predicate.test(row)) return row;
        return null;
    }

    private static List<Path> resolveCandidates(JsonNode pathNode, List<Path> bases) {
        String value = text(pathNode); if (value.isEmpty()) return List.of();
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(Path.of(value).toAbsolutePath().normalize());
        for (Path base : bases) if (base != null) paths.add(base.resolve(value).toAbsolutePath().normalize());
        return List.copyOf(paths);
    }

    private static List<String> pathEvidence(Map<String, ObjectNode> verified, String id) {
        ObjectNode row = verified.get(id); if (row == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String key : List.of("path", "byte_sha256", "content_sha256"))
            if (row.hasNonNull(key) && !text(row.get(key)).isEmpty()) result.add(text(row.get(key)));
        return result;
    }

    private static List<String> rowEvidence(JsonNode... rows) {
        List<String> result = new ArrayList<>();
        for (JsonNode row : rows) if (present(row))
            for (String key : List.of("path", "byte_sha256", "content_sha256"))
                if (row.hasNonNull(key) && !text(row.get(key)).isEmpty()) result.add(text(row.get(key)));
        return result;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> result = new ArrayList<>(); for (List<T> list : lists) result.addAll(list); return result;
    }

    private static List<ObjectNode> concatChecks(List<ObjectNode> left, List<ObjectNode> right) {
        List<ObjectNode> result = new ArrayList<>(left); result.addAll(right); return result;
    }

    private static void putArtifactPathAndHash(ObjectNode target, Map<String, ObjectNode> verified,
            String id, String pathKey, String hashKey) {
        target.put(pathKey, text(verified.get(id).get("path")));
        target.put(hashKey, text(verified.get(id).get("byte_sha256")));
    }

    private static void putNullable(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) target.putNull(field);
        else target.set(field, value.deepCopy());
    }

    private static void setNullable(ObjectNode target, String field, JsonNode value) {
        target.set(field, value == null ? NullNode.instance : value.deepCopy());
    }

    private static ObjectNode orObject(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : object();
    }

    private static JsonNode first(JsonNode... values) {
        for (JsonNode value : values) if (value != null && !value.isNull() && !value.isMissingNode()) return value;
        return null;
    }

    private static JsonNode firstTruthy(JsonNode... values) {
        for (JsonNode value : values) if (jsTruthy(value)) return value;
        return null;
    }

    private static List<JsonNode> rows(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>(); value.forEach(result::add); return result;
    }

    private static List<JsonNode> rowsOrOne(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        return value.isArray() ? rows(value) : List.of(value);
    }

    private static int size(JsonNode value) { return value != null && (value.isArray() || value.isObject()) ? value.size() : 0; }
    private static int intValue(JsonNode value, String field) { return value == null ? Integer.MIN_VALUE : value.path(field).asInt(Integer.MIN_VALUE); }
    private static boolean present(JsonNode value) { return value != null && !value.isNull() && !value.isMissingNode(); }
    private static String text(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(); }
    private static String nullableText(JsonNode value) { return value == null || value.isNull() || value.isMissingNode() ? null : value.asText(); }
    private static String orText(JsonNode value, String fallback) { String result = text(value); return result.isEmpty() ? fallback : result; }
    private static Path nullablePath(JsonNode value) { String result = text(value); return result.isEmpty() ? null : Path.of(result).toAbsolutePath().normalize(); }
    private static JsonNode textNode(String value) { return JsonHashes.mapper().getNodeFactory().textNode(value); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static ArrayNode arrayOfStrings(Iterable<String> values) { ArrayNode result = array(); values.forEach(result::add); return result; }
    private static String joinText(JsonNode values, String delimiter) { List<String> rows = new ArrayList<>(); for (JsonNode value : rows(values)) rows.add(text(value)); return String.join(delimiter, rows); }
    private static double roundOne(double value) { return Math.round(value * 10.0) / 10.0; }
    private static String fixedOne(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static boolean validHash(JsonNode value) { return validHash(text(value)); }
    private static boolean validHash(String value) { return value != null && value.matches("^[a-f0-9]{64}$"); }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    private record Manifest(Path path, ObjectNode value, String byteSha256, String contentSha256) {}
}
