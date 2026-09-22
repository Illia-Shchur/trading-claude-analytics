package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.PrintStream;

/** Exact process-boundary adapter for {@code tools/strategy-research-v5.mjs}. */
public final class StrategyResearchV5CommandAdapter {
    public static final String COMMANDS = "data-backfill|data-raw-replay|feature-build|metadata-build|daily-stress-preflight|liquidation-input-qualify|liquidation-input-verify|liquidation-profile-contract|liquidation-profile-validate|liquidation-profile-assess-physical|liquidation-v2-plan|liquidation-v2-physical-build|liquidation-v2-physical-verify|liquidation-v2-freeze|liquidation-v2-replay|liquidation-v2-replay-resumable|liquidation-v2-evidence|liquidation-v2-run-evaluate|liquidation-v2-staged-plan-no-macro|liquidation-v2-staged-plan-macro|liquidation-v2-staged-replay|liquidation-v2-staged-evidence|liquidation-hourly-preflight|liquidation-hourly-run|"
            + "opportunity-envelope|artifact-build|research-init|experiment-freeze|search-genetic|"
            + "research-run|overfit-audit|prospective-runner|readiness-audit|deployment-audit|"
            + "fixed-baseline|fixed-baseline-refinement|fixed-baseline-produce-signal-bars|operating-characteristics-preflight|operating-characteristics-run|operating-characteristics-diagnose|operating-characteristics-successor-preflight|operating-characteristics-successor-run|operating-characteristics-successor-record-attempt|operating-characteristics-parallel-profile|operating-characteristics-parallel-preflight|operating-characteristics-parallel-run|operating-characteristics-parallel-worker|operating-characteristics-corrected-successor-preflight|operating-characteristics-corrected-successor-run|operating-characteristics-corrected-parallel-profile|operating-characteristics-corrected-parallel-plan|operating-characteristics-corrected-parallel-preflight|operating-characteristics-corrected-parallel-run|operating-characteristics-corrected-parallel-worker|operating-characteristics-corrected-parallel-qualify|operating-characteristics-corrected-parallel-validate-qualification|freeze-refinement|portfolio-reconcile|prospective-outcome-reconcile|evidence-disposition|canonical-hash-batch|lineage-inventory|matching-attrition|freeze-successor-control|validate|index|presentation-export";
    public static final String USAGE = "usage: strategy-research-v5.mjs " + COMMANDS;
    public static final String HELP_USAGE = USAGE + " [options]";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StrategyResearchV5CommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        String command = args == null || args.length == 0 || args[0] == null ? "" : args[0];
        ObjectNode options = flags(args, 1);
        try {
            if (command.isEmpty() || "--help".equals(command) || "-h".equals(command)
                    || options.path("help").isBoolean() && options.path("help").booleanValue()
                    || options.path("h").isBoolean() && options.path("h").booleanValue()) {
                stdout.println(HELP_USAGE); return 0;
            }
            JsonNode result;
            if ("presentation-export".equals(command)) {
                java.nio.file.Path root = options.has("root") ? java.nio.file.Path.of(options.path("root").asText()) : null;
                java.nio.file.Path output = options.has("output") ? java.nio.file.Path.of(options.path("output").asText()) : null;
                boolean fixture = options.path("fixture").asBoolean(false);
                java.time.Instant asOf = options.has("as_of")
                        ? java.time.Instant.parse(options.path("as_of").asText()) : null;
                result = StrategyResearchUiExportV5.write(root, output, fixture, asOf);
            } else if ("fixed-baseline".equals(command)) {
                result = StrategyFixedBaselineV5.run(options);
            } else if ("fixed-baseline-refinement".equals(command)) {
                result = StrategyFixedBaselineV5.runRefinement(options);
            } else if ("fixed-baseline-produce-signal-bars".equals(command)) {
                result = StrategyFixedBaselineV5.buildSignalBarsFromVerifiedParquet(options);
            } else if ("daily-stress-preflight".equals(command)) {
                result = DailyStressPreflightV1.run(options);
            } else if ("liquidation-input-qualify".equals(command)) {
                result = LiquidationInputQualificationV1.audit(options);
            } else if ("liquidation-input-verify".equals(command)) {
                result = LiquidationInputQualificationV1.verify(options);
            } else if ("liquidation-profile-contract".equals(command)) {
                result = LiquidationDailyStressProfileV1.frozenContract();
            } else if ("liquidation-profile-validate".equals(command)) {
                ObjectNode profile = readObjectOption(options, "profile");
                LiquidationDailyStressProfileV1.validate(profile);
                result = JsonNodeFactory.instance.objectNode().put("status", "VALID")
                        .put("profile_sha256", profile.path("content_sha256").asText())
                        .put("max_lifecycle_days", profile.path("windows").path("maximum_lifecycle_days").asInt())
                        .put("authoritative_wfo_permitted", profile.path("authoritative_wfo_permitted").asBoolean());
            } else if ("liquidation-profile-assess-physical".equals(command)) {
                ObjectNode profile = readObjectOption(options, "profile");
                ObjectNode physicalOptions = JsonNodeFactory.instance.objectNode();
                physicalOptions.set("manifest", readObjectOption(options, "manifest"));
                physicalOptions.put("root", requiredText(options, "root"));
                physicalOptions.set("profile", profile);
                result = LiquidationDailyStressProfileV1.assessPhysicalManifest(profile, physicalOptions);
            } else if ("liquidation-v2-plan".equals(command)) {
                result = LiquidationV2PhysicalDataV1.frozenPlan(readObjectOption(options, "profile"));
            } else if ("liquidation-v2-physical-build".equals(command)) {
                ObjectNode physicalOptions = JsonNodeFactory.instance.objectNode();
                physicalOptions.set("profile", readObjectOption(options, "profile"));
                physicalOptions.set("inputs", readObjectOption(options, "inputs"));
                physicalOptions.put("root", requiredText(options, "root"));
                result = physicalOptions.path("inputs").path("feature").path("partitions").isArray()
                        ? LiquidationV2PhysicalDataV1.buildDevelopment(physicalOptions)
                        : LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(physicalOptions);
            } else if ("liquidation-v2-physical-verify".equals(command)) {
                ObjectNode physicalOptions = JsonNodeFactory.instance.objectNode();
                physicalOptions.set("profile", readObjectOption(options, "profile"));
                physicalOptions.set("manifest", readObjectOption(options, "manifest"));
                physicalOptions.put("root", requiredText(options, "root"));
                result = physicalOptions.path("manifest").path("artifacts").path("feature").path("partitions").isArray()
                        ? LiquidationV2PhysicalDataV1.verifyDevelopment(physicalOptions)
                        : LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(physicalOptions);
            } else if ("liquidation-v2-freeze".equals(command)) {
                result = LiquidationPortfolioReplayV1.freeze(readObjectOption(options, "options"));
            } else if ("liquidation-v2-replay".equals(command)) {
                result = LiquidationPortfolioReplayV1.run(readObjectOption(options, "options"));
            } else if ("liquidation-v2-replay-resumable".equals(command)) {
                result = LiquidationPortfolioReplayV1.runResumable(readObjectOption(options, "options"));
            } else if ("liquidation-v2-evidence".equals(command)) {
                result = LiquidationPortfolioReplayV1.evaluate(readObjectOption(options, "options"));
            } else if ("liquidation-v2-run-evaluate".equals(command)) {
                result = LiquidationPortfolioReplayV1.runAndEvaluate(readObjectOption(options, "options"));
            } else if ("liquidation-v2-staged-plan-no-macro".equals(command)) {
                result = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                        readObjectOption(options, "core_replay"), readObjectOption(options, "core_evidence"),
                        readObjectOption(options, "candidate_inventory"));
            } else if ("liquidation-v2-staged-plan-macro".equals(command)) {
                ObjectNode macroOptions = JsonNodeFactory.instance.objectNode();
                for (String key : new String[] {"freeze", "no_macro_plan", "core_replay", "core_evidence",
                        "no_macro_replay", "no_macro_evidence"}) {
                    macroOptions.set(key, readObjectOption(options, key));
                }
                macroOptions.put("out", requiredText(options, "out"));
                if (options.has("checkpoint_out")) {
                    macroOptions.put("checkpoint_out", requiredText(options, "checkpoint_out"));
                }
                result = LiquidationPortfolioReplayV1.freezeStagedMacro(macroOptions);
            } else if ("liquidation-v2-staged-replay".equals(command)) {
                result = LiquidationPortfolioReplayV1.runStaged(readObjectOption(options, "options"));
            } else if ("liquidation-v2-staged-evidence".equals(command)) {
                result = LiquidationPortfolioReplayV1.evaluateStaged(readObjectOption(options, "options"));
            } else if ("liquidation-hourly-preflight".equals(command)) {
                result = LiquidationHourlyDevelopmentReplayV1.preflight(options);
            } else if ("liquidation-hourly-run".equals(command)) {
                result = LiquidationHourlyDevelopmentReplayV1.run(options);
            } else if ("operating-characteristics-preflight".equals(command)) {
                result = StrategyOperatingCharacteristicsV4.preflight(readObjectOption(options, "plan"));
            } else if ("operating-characteristics-run".equals(command)) {
                result = StrategyOperatingCharacteristicsV4.run(options);
            } else if ("operating-characteristics-diagnose".equals(command)) {
                result = StrategyOperatingCharacteristicsSuccessorV1.diagnose(options);
            } else if ("operating-characteristics-successor-preflight".equals(command)) {
                result = StrategyOperatingCharacteristicsSuccessorV1.preflight(readObjectOption(options, "plan"));
            } else if ("operating-characteristics-successor-run".equals(command)) {
                result = StrategyOperatingCharacteristicsSuccessorV1.run(options);
            } else if ("operating-characteristics-successor-record-attempt".equals(command)) {
                result = StrategyOperatingCharacteristicsSuccessorV1.recordAttempt(options);
            } else if ("operating-characteristics-corrected-successor-preflight".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedSuccessorV1.preflight(readObjectOption(options, "plan"));
            } else if ("operating-characteristics-corrected-successor-run".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedSuccessorV1.run(options);
            } else if ("operating-characteristics-parallel-profile".equals(command)) {
                result = StrategyOperatingCharacteristicsParallelV1.executionProfile(options);
            } else if ("operating-characteristics-parallel-preflight".equals(command)) {
                result = StrategyOperatingCharacteristicsParallelV1.preflight(options);
            } else if ("operating-characteristics-parallel-run".equals(command)) {
                result = StrategyOperatingCharacteristicsParallelV1.run(options);
            } else if ("operating-characteristics-parallel-worker".equals(command)) {
                if (!options.path("internal").asBoolean(false) || !options.has("payload")) {
                    throw new IllegalArgumentException("parallel worker is internal and requires --internal --payload");
                }
                ObjectNode payload = readObjectOption(options, "payload");
                if (payload.path("corrected_accounting").asBoolean(false)
                        || payload.path("plan").path("schema").asText().contains("corrected")) {
                    throw new IllegalArgumentException("frozen parallel worker rejects corrected payloads; use the corrected worker command");
                }
                result = StrategyOperatingCharacteristicsParallelV1.worker(options);
            } else if ("operating-characteristics-corrected-parallel-profile".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfile(options);
            } else if ("operating-characteristics-corrected-parallel-plan".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.createDevelopmentPlan(
                        readObjectOption(options, "base_plan"), readObjectOption(options, "profile"));
            } else if ("operating-characteristics-corrected-parallel-preflight".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.preflight(options);
            } else if ("operating-characteristics-corrected-parallel-run".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.run(options);
            } else if ("operating-characteristics-corrected-parallel-worker".equals(command)) {
                if (!options.path("internal").asBoolean(false) || !options.has("payload")) {
                    throw new IllegalArgumentException("corrected parallel worker is internal and requires --internal --payload");
                }
                ObjectNode payload = readObjectOption(options, "payload");
                if (!payload.path("corrected_accounting").asBoolean(false)
                        || !payload.path("plan").path("schema").asText().contains("corrected")) {
                    throw new IllegalArgumentException("corrected parallel worker requires a corrected payload and plan");
                }
                result = StrategyOperatingCharacteristicsParallelV1.worker(options);
            } else if ("operating-characteristics-corrected-parallel-qualify".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.qualifyDevelopment(options);
            } else if ("operating-characteristics-corrected-parallel-validate-qualification".equals(command)) {
                result = StrategyOperatingCharacteristicsCorrectedParallelV1.validateQualification(options);
            } else if ("portfolio-reconcile".equals(command)) {
                result = StrategyResearchImprovementV1.reconcilePortfolio(readArrayOption(options, "trades"));
            } else if ("prospective-outcome-reconcile".equals(command)) {
                result = StrategyProspectiveOutcomeReconciliationV1.reconcile(readObjectOption(options, "input"));
            } else if ("freeze-refinement".equals(command)) {
                result = StrategyResearchImprovementV1.freezeRefinementInventory(readObjectOption(options, "input"));
            } else if ("evidence-disposition".equals(command)) {
                result = StrategyResearchImprovementV1.disposition(options);
            } else if ("canonical-hash-batch".equals(command)) {
                result = StrategyResearchImprovementV1.canonicalHashBatch(options);
            } else if ("lineage-inventory".equals(command)) {
                result = StrategyEvidenceV1.lineageInventory(options);
            } else if ("matching-attrition".equals(command)) {
                result = StrategyEvidenceV1.matchingAttrition(options);
            } else if ("freeze-successor-control".equals(command)) {
                result = StrategyEvidenceV1.freezeSuccessorControlDesign(options);
            } else {
                result = StrategyResearchV5.runAuthoritativeV5Cli(command, options);
            }
            if (result != null) {
                stdout.print(NodePrettyJson.write(result)); return 0;
            }
            stderr.println("unknown strategy-research-v5 command: " + command);
            stderr.println(USAGE); return 1;
        } catch (RuntimeException error) {
            stderr.println(message(error)); return 1;
        }
    }

    private static ObjectNode readObjectOption(ObjectNode options, String key) {
        JsonNode value = readOption(options, key);
        if (!value.isObject()) throw new IllegalArgumentException("--" + key + " must point to a JSON object");
        return (ObjectNode) value;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode readArrayOption(ObjectNode options, String key) {
        JsonNode value = readOption(options, key);
        if (!value.isArray()) throw new IllegalArgumentException("--" + key + " must point to a JSON array");
        return (com.fasterxml.jackson.databind.node.ArrayNode) value;
    }

    private static JsonNode readOption(ObjectNode options, String key) {
        JsonNode raw = options.get(key);
        if (raw == null || !raw.isTextual()) {
            throw new IllegalArgumentException("--" + key + " requires a JSON file path");
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(java.nio.file.Files.readString(java.nio.file.Path.of(raw.asText())));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("cannot read --" + key + ": " + error.getMessage(), error);
        }
    }

    private static String requiredText(ObjectNode options, String key) {
        JsonNode value = options.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return value.asText();
    }

    static ObjectNode flags(String[] args, int start) {
        ObjectNode options = JSON.objectNode();
        if (args == null) return options;
        for (int index = Math.max(0, start); index < args.length; index++) {
            String argument = args[index];
            if (argument == null || !argument.startsWith("--")) continue;
            String rawKey = argument.substring(2); JsonNode value;
            if (index + 1 >= args.length || args[index + 1] != null && args[index + 1].startsWith("--")) {
                value = JSON.booleanNode(true);
            } else value = JSON.textNode(args[++index] == null ? "" : args[index]);
            options.set(rawKey, value); options.set(rawKey.replace('-', '_'), value);
        }
        return options;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
