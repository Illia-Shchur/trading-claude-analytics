package com.tradinganalytics.research.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.lib.ToolchainSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Native domain/state-machine port of every export in {@code tools/calib-run.mjs}. */
public final class CalibrationRun {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter NODE_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    public static final List<String> PHASES = List.of("extract", "grade", "diagnose", "verify", "synthesize");
    public static final Map<String, String> DEFAULT_MODELS = defaultModels();
    public static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("scoring-and-gates", "Composite score weights/bands/thresholds AND the confirmation-gate board that drives phase unlocks. Did the score track reality or whipsaw? Noisy single-day inputs? Bands that saturate through a normal move? Identify PRO-CYCLICAL gates that turn OFF as conditions improve for the thesis (the better the setup, the fewer gates pass) and quantify any score/gate divergence."),
            new Dimension("capital-deployment", "Phase sizing, unlock thresholds, AND stop placement/logic — the money-moving layer. Did the pyramid INVERT (smallest tranche at the worst price, locked out at the best)? Cross-asset: did a lower-conviction asset deploy more than a higher-conviction one? Were stops placed where they eject the position at the worst moment, or where they contradict the framework's own add-zones? Near-misses? Weigh REALIZED fills/round-trips (position ledger, below) over narrated intent where both exist."),
            new Dimension("forecast-calibration", "Score->probability grid, weighted EV, and the narrative/judgment layer vs the quant layer. Persistent directional bias (positive EV while price went the other way)? Monotonic mapping that ignores trend/regime? Over/under-stated confidence in prose; regimes declared \"resolved\" then falsified; what encodable guardrails would have helped?"),
            new Dimension("data-integrity", "Data noise, source disagreement, stale/derived inputs carried at full credit, cross-asset coherence, and cross-framework (inverse) consistency — was the companion check actually COMPUTED each report or eyeballed?"));
    public static final List<String> SOLO_PANEL_DIMENSIONS = List.of("capital-deployment");
    public static final Map<String, JsonNode> SCHEMAS = schemas();
    private static final Map<String, String> FRAMEWORK_LABELS = Map.of(
            "fallen_knives", "Fallen Knives (LONG/accumulation framework)",
            "flying_rocket", "Flying Rocket (SHORT/distribution framework — Hard Rule 6: asymmetry tax; its discipline may only ever be tightened, never loosened)");
    private static final List<String> LENSES = List.of(
            "Lens emphasis: OVERFIT + COUNTERFACTUAL — would this tune have helped on the realized path AND on plausible alternate paths (V-bounce, deeper washout, sideways grind)?",
            "Lens emphasis: GUARDRAIL COLLISION + UNINTENDED CONSEQUENCES — trace every interaction with unlock thresholds, overrides, stops, and caps; find the path where this tune does damage.",
            "Lens emphasis: EVIDENCE VERIFICATION — independently re-derive every number in the rationale from the graded paths and source reports; hunt for misquoted or invented data.");

    private final Path repositoryRoot;
    private final Supplier<Instant> now;
    private final Supplier<String> randomSuffix;
    private final Consumer<String> diagnostics;
    private final CalibrationRegistry registry = new CalibrationRegistry(JSON);

    public CalibrationRun(Path repositoryRoot) {
        this(repositoryRoot, Instant::now, CalibrationRun::randomSuffix, ignored -> {});
    }

    public CalibrationRun(Path repositoryRoot, Supplier<Instant> now, Supplier<String> randomSuffix,
                          Consumer<String> diagnostics) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.now = now;
        this.randomSuffix = randomSuffix;
        this.diagnostics = diagnostics;
    }

    /** Minimal structural validator: required keys, types and enums. */
    public static List<String> validateSchema(JsonNode object, JsonNode schema) {
        return validateSchema(object, schema, "$");
    }

    public static List<String> validateSchema(JsonNode object, JsonNode schema, String path) {
        List<String> errors = new ArrayList<>();
        if (object == null || object.isNull() || (!object.isObject() && !object.isArray())) {
            errors.add(path + ": expected object, got " + (object == null || object.isNull() ? "null" : jsType(object)));
            return errors;
        }
        for (JsonNode key : iterable(schema.path("required")))
            if (!object.has(key.asText())) errors.add(path + ": missing required \"" + key.asText() + "\"");
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) for (Map.Entry<String, JsonNode> field : properties.properties()) {
            String key = field.getKey(); JsonNode sub = field.getValue();
            if (!object.has(key)) continue;
            JsonNode value = object.get(key); String type = sub.path("type").asText();
            if ("array".equals(type)) {
                if (!value.isArray()) { errors.add(path + "." + key + ": expected array"); continue; }
                if (sub.has("items")) for (int index = 0; index < value.size(); index++) {
                    JsonNode item = value.get(index), itemSchema = sub.get("items");
                    if ("string".equals(itemSchema.path("type").asText())) {
                        if (!item.isTextual()) errors.add(path + "." + key + "[" + index + "]: expected string");
                    } else errors.addAll(validateSchema(item, itemSchema, path + "." + key + "[" + index + "]"));
                }
            } else if ("string".equals(type)) {
                if (!value.isTextual()) errors.add(path + "." + key + ": expected string");
                else if (sub.path("enum").isArray() && !containsText(sub.path("enum"), value.asText()))
                    errors.add(path + "." + key + ": \"" + value.asText() + "\" not one of " + joinText(sub.path("enum"), "|"));
            } else if ("boolean".equals(type)) {
                if (!value.isBoolean()) errors.add(path + "." + key + ": expected boolean");
            } else if ("object".equals(type)) errors.addAll(validateSchema(value, sub, path + "." + key));
        }
        return List.copyOf(errors);
    }

    public ObjectNode loadRun(Path runDir) throws Exception {
        return (ObjectNode) readJson(runDir.resolve("run.json"));
    }

    public void saveRun(Path runDir, JsonNode run) throws Exception {
        Files.writeString(runDir.resolve("run.json"), ToolchainSupport.canonicalJSON(run) + "\n", StandardCharsets.UTF_8);
    }

    public InitResult cmdInit(InitOptions options) throws Exception {
        if (options.corpus() == null)
            throw new IllegalArgumentException("The \"paths[1]\" argument must be of type string. Received null");
        Path corpusDir = CalibrationPaths.resolve(repositoryRoot, options.corpus());
        if (!Files.exists(corpusDir.resolve("corpus.json")))
            throw new IllegalArgumentException("no corpus.json in " + corpusDir + " — run tools/calib-corpus.mjs first");
        JsonNode corpusFile = readJson(corpusDir.resolve("corpus.json"));
        String mode = present(options.mode()) ? options.mode() : "full";
        if (!List.of("full", "scoped", "meta").contains(mode))
            throw new IllegalArgumentException("--mode must be full|scoped|meta, got \"" + mode + "\"");
        if ("meta".equals(mode))
            throw new IllegalArgumentException("meta mode does not run the market backtest pipeline — see SKILL.md \"Calibrating the calibrator\"");
        List<String> scopeItems = present(options.scopeItems()) ? commaList(options.scopeItems())
                : "full".equals(mode) ? List.of("all dimensions", "prior-tune re-validation", "full corpus window") : List.of();
        List<String> scopeSkipped = present(options.scopeSkipped()) ? commaList(options.scopeSkipped()) : List.of();
        if (!"full".equals(mode) && scopeSkipped.isEmpty())
            throw new IllegalArgumentException("--mode " + mode + " requires --scope-skipped (a scoped/meta run must declare what it is not covering)");

        List<String> warnings = new ArrayList<>();
        String anchors = null;
        if (present(options.anchors()))
            anchors = Files.readString(CalibrationPaths.resolve(repositoryRoot, options.anchors()), StandardCharsets.UTF_8).strip();
        else warnings.add("no --anchors supplied — Hard Rule 1 requires live ground-truth anchors; every grade will be degraded without them");
        JsonNode position = NullNode.instance;
        if (present(options.position()))
            position = readJson(CalibrationPaths.resolve(repositoryRoot, options.position()));
        else warnings.add("no --position supplied — Principle 6 requires realized-P&L context (tools/position.mjs all --json); Grade will have no fills evidence");

        Path registryPath = !present(options.registry()) ? repositoryRoot.resolve("reports/calibration-registry.json")
                : CalibrationPaths.resolve(repositoryRoot, options.registry());
        ObjectNode registryValue = registry.load(registryPath);
        ArrayNode priorRejections = JSON.createArrayNode();
        for (JsonNode entry : iterable(registryValue.path("entries")))
            if (List.of("rejected", "withheld").contains(entry.path("verdict").asText())) priorRejections.add(entry.deepCopy());
        JsonNode priorCalibrations = !present(options.priorCalibrations()) ? JSON.createArrayNode()
                : readJson(CalibrationPaths.resolve(repositoryRoot, options.priorCalibrations()));
        Path skillDir = !present(options.skillDir()) ? repositoryRoot.resolve(".claude/skills")
                : CalibrationPaths.resolve(repositoryRoot, options.skillDir());
        List<String> targetSkills = !present(options.targetSkills())
                ? List.of(skillDir.resolve("fallen-knives-analytics/SKILL.md").toString(), skillDir.resolve("flying-rocket-analytics/SKILL.md").toString())
                : commaList(options.targetSkills()).stream().map(value -> CalibrationPaths.resolve(repositoryRoot, value).toString()).toList();
        Instant instant = now.get();
        String runId = !present(options.runId())
                ? "run-" + NODE_ISO.format(instant).substring(0, 10) + "-" + randomSuffix.get() : options.runId();
        Path runDir = CalibrationPaths.resolve(repositoryRoot, !present(options.out()) ? ".calib-run/" + runId : options.out());
        Files.createDirectories(runDir);

        LinkedHashMap<String, String> models = new LinkedHashMap<>();
        for (String phase : PHASES) models.put(phase, DEFAULT_MODELS.get(phase));
        if (options.models() != null && options.models().isObject())
            options.models().properties().forEach(entry -> models.put(entry.getKey(), entry.getValue().asText()));
        ObjectNode knobs = JSON.createObjectNode(); knobs.put("skepticsPerTune", 1); knobs.put("extractChunk", 6); knobs.put("verifyChunk", 5);
        if (options.knobs() != null && options.knobs().isObject()) knobs.setAll((ObjectNode) options.knobs());

        ObjectNode run = JSON.createObjectNode(); run.put("schema", "calib-run/1"); run.put("run_id", runId);
        run.put("created_at", NODE_ISO.format(instant)); run.put("corpusDir", corpusDir.toString());
        run.set("corpus", corpusFile.path("reports").deepCopy()); run.put("mode", mode);
        run.set("scopeItems", strings(scopeItems)); run.set("scopeSkipped", strings(scopeSkipped));
        if (anchors == null) run.set("anchors", NullNode.instance); else run.put("anchors", anchors);
        run.set("position", position.deepCopy()); run.set("priorRejections", priorRejections);
        run.set("priorCalibrations", priorCalibrations.deepCopy()); run.put("skillDir", skillDir.toString());
        run.set("targetSkills", strings(targetSkills));
        ObjectNode modelNode = run.putObject("models"); models.forEach(modelNode::put); run.set("knobs", knobs);
        ObjectNode phases = run.putObject("phases");
        PHASES.forEach(phase -> phases.putObject(phase).put("status", "pending"));
        saveRun(runDir, run);
        Files.writeString(runDir.resolve("coverage.json"), ToolchainSupport.canonicalJSON(emptyCoverage()) + "\n", StandardCharsets.UTF_8);
        return new InitResult(runDir, run, List.copyOf(warnings));
    }

    public List<ObjectNode> cmdPlan(Path runDir, String phase) throws Exception {
        ObjectNode run = loadRun(runDir);
        int index = PHASES.indexOf(phase);
        if (index < 0) throw new IllegalArgumentException("unknown phase \"" + phase + "\" — one of " + String.join("|", PHASES));
        if (index > 0) {
            String previous = PHASES.get(index - 1);
            String status = run.path("phases").path(previous).path("status").asText("pending");
            if (!"collected".equals(status))
                throw new IllegalArgumentException("plan " + phase + ": phase \"" + previous + "\" is not collected yet (status: " + status + ") — the phase barrier");
        }
        Path resolved = runDir.toAbsolutePath().normalize();
        return switch (phase) {
            case "extract" -> planExtract(resolved, run);
            case "grade" -> planGrade(resolved, run);
            case "diagnose" -> planDiagnose(resolved, run);
            case "verify" -> planVerify(resolved, run);
            case "synthesize" -> planSynthesize(resolved, run);
            default -> throw new IllegalStateException();
        };
    }

    public ObjectNode cmdCollect(Path runDir, String phase) throws Exception {
        ObjectNode run = loadRun(runDir);
        if (!PHASES.contains(phase))
            throw new IllegalArgumentException("unknown phase \"" + phase + "\" — one of " + String.join("|", PHASES));
        Path resolved = runDir.toAbsolutePath().normalize();
        return switch (phase) {
            case "extract" -> collectExtract(resolved, run);
            case "grade" -> collectGrade(resolved, run);
            case "diagnose" -> collectDiagnose(resolved, run);
            case "verify" -> collectVerify(resolved, run);
            case "synthesize" -> collectSynthesize(resolved, run);
            default -> throw new IllegalStateException();
        };
    }

    public ObjectNode cmdStatus(Path runDir) throws Exception {
        ObjectNode run = loadRun(runDir), status = JSON.createObjectNode();
        status.set("run_id", run.get("run_id")); status.set("mode", run.get("mode"));
        status.set("models", run.get("models")); status.set("phases", run.get("phases")); return status;
    }

    public static List<String> revisionLogPaths(List<String> targetSkills) {
        if (targetSkills == null) return List.of();
        return targetSkills.stream().map(value -> value.replaceFirst("SKILL\\.md$", "REVISION-LOG.md")).toList();
    }

    public static Boundary postCalibrationBoundary(JsonNode corpus, JsonNode priorCalibrations) {
        List<String> dates = new ArrayList<>(), calibrations = new ArrayList<>();
        for (JsonNode report : iterable(corpus)) if (truthy(report.get("d"))) dates.add(report.path("d").asText());
        for (JsonNode calibration : iterable(priorCalibrations)) if (truthy(calibration.get("date"))) calibrations.add(calibration.path("date").asText());
        dates.sort(String::compareTo); calibrations.sort(String::compareTo);
        String start = dates.isEmpty() ? "" : dates.get(0), latest = calibrations.isEmpty() ? "" : calibrations.get(calibrations.size() - 1);
        if (start.isEmpty()) return new Boundary(latest, latest);
        String target = start;
        for (String date : calibrations) if (date.substring(0, Math.min(10, date.length())).compareTo(start) <= 0) target = date;
        return new Boundary(start, target);
    }

    public static List<JsonNode> zeroTuneDiagnoses(JsonNode diagnoses) {
        if (diagnoses == null || diagnoses.isNull())
            throw new IllegalArgumentException("Cannot read properties of null (reading 'filter')");
        if (!diagnoses.isArray()) throw new IllegalArgumentException("diagnoses.filter is not a function");
        List<JsonNode> output = new ArrayList<>();
        for (JsonNode diagnosis : iterable(diagnoses))
            if (zeroLength(diagnosis.get("proposed_tunes"))) output.add(diagnosis);
        return List.copyOf(output);
    }

    public static String mergeStrictestWins(JsonNode votes) {
        for (JsonNode vote : iterable(votes)) if ("reject".equals(vote.path("recommendation").asText())) return "reject";
        for (JsonNode vote : iterable(votes)) if ("adopt_with_modification".equals(vote.path("recommendation").asText())) return "adopt_with_modification";
        return "adopt";
    }

    public static TriageResult applyTriageClusters(ArrayNode allTunes, JsonNode clusters) {
        if (clusters == null || !clusters.isArray() || clusters.isEmpty()) return new TriageResult(allTunes, 0);
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        for (JsonNode tune : allTunes) byName.put(tune.path("name").asText(), (ObjectNode) tune);
        Set<String> merged = new LinkedHashSet<>();
        for (JsonNode cluster : clusters) {
            String keepName = cluster.path("keep").asText(); ObjectNode keep = byName.get(keepName);
            if (keep == null || merged.contains(keepName)) continue;
            for (JsonNode merge : iterable(cluster.path("merge"))) {
                String name = merge.asText(); ObjectNode victim = byName.get(name);
                if (name.equals(keepName) || victim == null || merged.contains(name)) continue;
                if (!java.util.Objects.equals(victim.get("framework"), keep.get("framework"))) continue;
                JsonNode mergedFrom = keep.get("merged_from");
                if (!(mergedFrom instanceof ArrayNode array)) throw new IllegalStateException("merged_from.push is not a function");
                merged.add(name); array.add(name);
            }
        }
        ArrayNode tunes = JSON.createArrayNode();
        for (JsonNode tune : allTunes) if (!merged.contains(tune.path("name").asText())) tunes.add(tune);
        return new TriageResult(tunes, merged.size());
    }

    // ========================================================================
    // EXTRACT
    // ========================================================================
    private List<ObjectNode> planExtract(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "extract"), corpusDir = Path.of(run.path("corpusDir").asText());
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode report : iterable(run.path("corpus")))
            groups.computeIfAbsent(report.path("t").asText() + "|" + report.path("a").asText(), ignored -> new ArrayList<>()).add(report);
        int chunkSize = Math.min(knob(run, "extractChunk", 6), 8), chunkIndex = 0;
        List<ObjectNode> tasks = new ArrayList<>();
        for (List<JsonNode> group : groups.values()) for (List<JsonNode> files : chunks(group, chunkSize)) {
            chunkIndex++;
            JsonNode first = files.get(0);
            String taskId = "extract-" + slug(first.path("t").asText()) + "-" + slug(first.path("a").asText()) + "-" + chunkIndex;
            List<String> fileLines = new ArrayList<>(); ArrayNode digests = JSON.createArrayNode(); boolean multi = false;
            for (int index = 0; index < files.size(); index++) {
                JsonNode report = files.get(index); JsonNode digest = digest(corpusDir, report.path("f").asText());
                if ("MULTI".equals(report.path("a").asText())) multi = true;
                fileLines.add((index + 1) + ". " + corpusDir.resolve(report.path("f").asText() + ".slice.md")
                        + " (" + report.path("a").asText() + ", dated " + report.path("d").asText() + ")"
                        + (digest == null ? " — NO machine block (pre-epoch report; extract numeric claims from prose where the digest is empty)" : ""));
                ObjectNode row = digests.addObject(); row.put("file", report.path("f").asText());
                row.set("digest", digest == null ? NullNode.instance : digest);
            }
            String body = "task_id: " + taskId + "\n\n"
                    + "You are a forensic analyst. Read these " + files.size() + " " + frameworkLabel(first.path("t").asText())
                    + " PRE-SLICED report file(s), IN ORDER (the machine block, the \"Verified Live Data Points\" section, and the Composite Score section were already stripped — their numbers are supplied below inline, already authoritative; do not treat their absence as a gap):\n"
                    + String.join("\n", fileLines) + "\n\n"
                    + "Digests (already-parsed numeric fields — authoritative, do not re-derive or contradict them; a null digest means the report predates the machine-block epoch):\n"
                    + json(digests) + "\n\n"
                    + "Return exactly ONE extract per file, in the SAME ORDER, echoing the exact filename in \"file\". "
                    + (multi ? "combined_* files are multi-asset: prefix EVERY extracted item with its asset ticker. " : "")
                    + "Per report, extract EVERY testable prediction and forward-looking claim: all IF->THEN \"Pattern\" conditionals, all action items, discretionary actions explicitly DECLINED (these are predictions too), and any falsifiable thesis statements. If the digest carries no ev.scenarios (pre-epoch report), extract the probability matrix from prose instead — otherwise leave probability_scenarios empty, the digest already has it. "
                    + "Reports in a series repeat standing predictions — extract them EACH time they appear (each report is graded on its own claims); do NOT summarize across reports or skip \"unchanged\" items. Extract faithfully; do not editorialize.\n\n"
                    + "Return JSON matching this shape exactly (top-level key \"extracts\", one object per file, fields: file, stance, probability_scenarios[], pattern_predictions[], falsifiable_claims[], declined_actions[], notable):\n"
                    + json(SCHEMAS.get("CHUNK_EXTRACT"));
            ObjectNode task = writePromptTask(directory, taskId, model(run, "extract"), body);
            ArrayNode metadata = task.putArray("files");
            for (JsonNode report : files) { ObjectNode row = metadata.addObject(); copy(row, report, "f", "a", "t", "d"); }
            tasks.add(task);
        }
        writeTasks(directory.resolve("plan.json"), tasks);
        ObjectNode state = JSON.createObjectNode(); state.put("status", "planned"); state.put("task_count", tasks.size());
        run.with("phases").set("extract", state); saveRun(runDir, run); return List.copyOf(tasks);
    }

    private ObjectNode collectExtract(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "extract"), corpusDir = Path.of(run.path("corpusDir").asText());
        JsonNode plan = readJson(directory.resolve("plan.json"));
        ArrayNode extracts = JSON.createArrayNode(), dropped = JSON.createArrayNode(), failed = JSON.createArrayNode();
        for (JsonNode task : iterable(plan.path("tasks"))) {
            ReadResult result = readOut(task);
            if (!result.ok) {
                failed.add(failure(task, result.reason));
                for (JsonNode file : iterable(task.path("files"))) dropped.add(file.path("f").asText());
                continue;
            }
            List<String> errors = validateSchema(result.data, SCHEMAS.get("CHUNK_EXTRACT"));
            JsonNode items = errors.isEmpty() ? result.data.path("extracts") : null;
            if (items == null || !items.isArray()) {
                failed.add(failure(task, errors.isEmpty() ? "no extracts array" : String.join("; ", errors)));
                for (JsonNode file : iterable(task.path("files"))) dropped.add(file.path("f").asText());
                continue;
            }
            if (items.size() == task.path("files").size()) {
                for (int index = 0; index < items.size(); index++)
                    extracts.add(joinExtract(items.get(index), task.path("files").get(index), digest(corpusDir, task.path("files").get(index).path("f").asText())));
            } else {
                Map<String, JsonNode> byName = new LinkedHashMap<>();
                for (JsonNode item : items) byName.put(item.path("file").asText(), item);
                for (JsonNode file : iterable(task.path("files"))) {
                    JsonNode item = byName.get(file.path("f").asText());
                    if (item == null) dropped.add(file.path("f").asText());
                    else extracts.add(joinExtract(item, file, digest(corpusDir, file.path("f").asText())));
                }
            }
        }
        if (!failed.isEmpty()) return failedResult(failed);
        ObjectNode joined = JSON.createObjectNode(); joined.set("extracts", extracts); joined.set("droppedReports", dropped);
        writeCanonical(directory.resolve("joined.json"), joined);
        ObjectNode coverage = JSON.createObjectNode(); coverage.set("dropped_reports", dropped); updateCoverage(runDir, coverage);
        ObjectNode state = JSON.createObjectNode(); state.put("status", "collected");
        state.put("task_count", plan.path("tasks").size()); state.put("extracts", extracts.size()); state.put("dropped_reports", dropped.size());
        run.with("phases").set("extract", state); saveRun(runDir, run);
        ObjectNode output = ok(); output.put("extracts", extracts.size()); output.put("dropped_reports", dropped.size()); return output;
    }

    // ========================================================================
    // GRADE
    // ========================================================================
    private List<ObjectNode> planGrade(Path runDir, ObjectNode run) throws Exception {
        JsonNode extractJoined = readJson(phaseDir(runDir, "extract").resolve("joined.json"));
        JsonNode extracts = extractJoined.path("extracts"); Path directory = phaseDir(runDir, "grade");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (JsonNode report : iterable(run.path("corpus")))
            if (!"MULTI".equals(report.path("a").asText())) keys.add(report.path("t").asText() + "|" + report.path("a").asText());
        List<ObjectNode> tasks = new ArrayList<>();
        for (String key : keys) {
            String[] pair = key.split("\\|", -1); String framework = pair[0], asset = pair[1]; ArrayNode series = JSON.createArrayNode();
            for (JsonNode extract : iterable(extracts))
                if (framework.equals(extract.path("framework").asText())
                        && (asset.equals(extract.path("asset").asText()) || "MULTI".equals(extract.path("asset").asText()))) series.add(extract);
            if (series.isEmpty()) continue;
            String taskId = "grade-" + slug(framework) + "-" + slug(asset);
            String body = "task_id: " + taskId + "\n\n"
                    + "Grade the predictive accuracy of " + frameworkLabel(framework) + " on " + asset + ". Grade ONLY this framework's predictions.\n"
                    + anchorsText(run) + "\n\n"
                    + "Realized-P&L ledger context (Hard Rule 8 — read .band before any figure; a non-FRESH/STALE band means state \"no realized evidence\" explicitly, never infer a zero):\n"
                    + positionText(run) + "\n\n"
                    + "Chronological extracts for " + asset + " (each carries a digest with score/gates/ev/deployment already parsed — treat as authoritative):\n"
                    + json(series) + "\n\n"
                    + "Reconstruct the realized path. GRADE every probability-matrix modal call, every IF->THEN conditional, every falsifiable claim, and every deployment/stop instruction vs what actually happened (later reports + anchors = truth). Mark correct/partial/wrong/untested with evidence — keep each evidence line tight (<=25 words). "
                    + "Independently verify the 2-3 most load-bearing realized-path numbers (leg low, end price) against "
                    + (truthy(run.get("anchors")) ? "the live anchors above" : "the source reports") + ". "
                    + "Then assess: (1) EV calibration bias; (2) deployment quality; (3) stop analysis; (4) realized_pnl_note. Be quantitative and unsparing.\n\n"
                    + "Return JSON matching this shape:\n" + json(SCHEMAS.get("GRADE"));
            ObjectNode task = writePromptTask(directory, taskId, model(run, "grade"), body); task.put("series", key); tasks.add(task);
        }
        String crossvalId = "grade-crossval"; ArrayNode slim = JSON.createArrayNode();
            for (JsonNode extract : iterable(extracts)) { ObjectNode row = slim.addObject(); copy(row, extract, "file", "asset", "framework"); row.set("date", extract.get("report_date")); JsonNode digest = extract.get("digest"); if (digest != null && !digest.isNull()) { optional(row, "score", digest.get("score")); optional(row, "ev", digest.get("ev")); } }
        ObjectNode crossval = writePromptTask(directory, crossvalId, model(run, "grade"),
                "task_id: " + crossvalId + "\n\nAssess cross-framework / cross-validation discipline (inverse-companion consistency, if applicable). Was the inverse score actually COMPUTED each report or eyeballed? Did the check go stale precisely when it mattered most? Should a computed companion score be mandatory?\nReport series (slim):\n"
                        + json(slim) + "\n\nReturn JSON: {\"crossval\": \"<concise prose with a clear recommendation>\"}");
        crossval.put("kind", "crossval"); tasks.add(crossval);

        Boundary boundary = postCalibrationBoundary(run.path("corpus"), run.path("priorCalibrations"));
        if ("full".equals(run.path("mode").asText()) && !run.path("priorCalibrations").isEmpty()) {
            String taskId = "grade-prior-tunes"; List<String> artifacts = new ArrayList<>();
            for (JsonNode prior : iterable(run.path("priorCalibrations"))) artifacts.add(prior.path("retro").asText() + " (" + prior.path("date").asText() + ": " + prior.path("summary").asText() + ")");
            ArrayNode series = JSON.createArrayNode();
            for (JsonNode extract : iterable(extracts)) if (boundary.boundary().isEmpty() || extract.path("report_date").asText().compareTo(boundary.boundary()) >= 0) {
                ObjectNode row = series.addObject(); copy(row, extract, "file", "asset", "framework"); row.set("date", extract.get("report_date")); JsonNode digest = extract.get("digest"); if (digest != null && !digest.isNull()) optional(row, "score", digest.get("score")); row.set("stance", extract.get("stance"));
            }
            String seriesText = series.isEmpty()
                    ? "Post-calibration report series: EMPTY — no report in the corpus post-dates " + boundary.boundary() + ". Report every tune as not_exercised and say so loudly in \"overall\"; do NOT invent behavioural evidence.\n\n"
                    : "Post-calibration report series (slim), " + series.size() + " report(s):\n" + json(series) + "\n\n";
            String body = "task_id: " + taskId + "\n\n"
                    + "You are re-validating the PRIOR calibration(s) of this framework — the calibrator grades itself before it grades the framework.\n"
                    + "Prior calibration artifacts — Read each memo: " + String.join(" ; ", artifacts) + "\n"
                    + "Also Read the framework revision log(s): " + revisionLogsText(run) + "\n" + anchorsText(run) + "\n\n"
                    + "RE-VALIDATION TARGET: the calibration dated " + (boundary.target().isEmpty() ? "(unknown)" : boundary.target()) + " — this corpus window is its out-of-sample test. "
                    + "Grade ITS adopted tunes. A LATER calibration may exist in the prior-calibration list (a scoped or meta run); do not mistake it for the target.\n"
                    + seriesText
                    + "For EVERY tune the prior calibration ADOPTED: did the changed rule show up in subsequent reports' behavior? Verdict: validated / harmful / not_exercised / indeterminate, with quantified evidence (<=25 words). "
                    + "Also list prior predictions graded \"untested\" that have since RESOLVED, with new verdicts. (The rejected-tune list is supplied deterministically via args.priorRejections — do not re-derive it.)\n\n"
                    + "Return JSON matching this shape:\n" + json(SCHEMAS.get("PRIOR_GRADE"));
            ObjectNode prior = writePromptTask(directory, taskId, model(run, "grade"), body); prior.put("kind", "prior_grade"); tasks.add(prior);
        }
        writeTasks(directory.resolve("plan.json"), tasks);
        ObjectNode state = JSON.createObjectNode(); state.put("status", "planned"); state.put("task_count", tasks.size());
        run.with("phases").set("grade", state); saveRun(runDir, run); return List.copyOf(tasks);
    }

    private ObjectNode collectGrade(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "grade"); JsonNode plan = readJson(directory.resolve("plan.json"));
        ArrayNode grades = JSON.createArrayNode(), dropped = JSON.createArrayNode(), failed = JSON.createArrayNode();
        JsonNode crossval = NullNode.instance, priorGrade = NullNode.instance;
        for (JsonNode task : iterable(plan.path("tasks"))) {
            ReadResult result = readOut(task); String kind = task.path("kind").asText();
            if ("crossval".equals(kind)) {
                if (result.ok && result.data != null && result.data.path("crossval").isTextual()) crossval = result.data.get("crossval");
                else failed.add(failure(task, result.ok ? "missing \"crossval\" string field" : result.reason));
                continue;
            }
            if ("prior_grade".equals(kind)) {
                if (result.ok) { List<String> errors = validateSchema(result.data, SCHEMAS.get("PRIOR_GRADE")); if (errors.isEmpty()) priorGrade = result.data; else failed.add(failure(task, String.join("; ", errors))); }
                else failed.add(failure(task, result.reason));
                continue;
            }
            if (!result.ok) { failed.add(failure(task, result.reason)); dropped.add(task.path("series").asText()); continue; }
            List<String> errors = validateSchema(result.data, SCHEMAS.get("GRADE"));
            if (!errors.isEmpty()) { failed.add(failure(task, String.join("; ", errors))); dropped.add(task.path("series").asText()); continue; }
            String[] pair = task.path("series").asText().split("\\|", -1); ObjectNode grade = ((ObjectNode) result.data).deepCopy();
            grade.put("framework", pair[0]); grade.put("asset", pair[1]); grades.add(grade);
        }
        if (!failed.isEmpty()) return failedResult(failed);
        ObjectNode joined = JSON.createObjectNode(); joined.set("grades", grades); joined.set("crossval", crossval); joined.set("priorGrade", priorGrade);
        writeCanonical(directory.resolve("joined.json"), joined); ObjectNode patch = JSON.createObjectNode(); patch.set("dropped_series", dropped); updateCoverage(runDir, patch);
        ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("task_count", plan.path("tasks").size());
        state.put("series", grades.size()); state.put("dropped_series", dropped.size()); run.with("phases").set("grade", state); saveRun(runDir, run);
        ObjectNode output = ok(); output.put("series", grades.size()); output.put("dropped_series", dropped.size()); return output;
    }
    // ========================================================================
    // DIAGNOSE + null-adversary sub-round
    // ========================================================================
    private List<ObjectNode> planDiagnose(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "diagnose"); String status = run.path("phases").path("diagnose").path("status").asText();
        JsonNode grade = readJson(phaseDir(runDir, "grade").resolve("joined.json"));
        JsonNode grades = grade.path("grades"), crossval = grade.get("crossval"), priorGrade = grade.get("priorGrade");
        LinkedHashSet<String> frameworks = new LinkedHashSet<>();
        for (JsonNode report : iterable(run.path("corpus"))) frameworks.add(report.path("t").asText());
        if (status.isEmpty() || "pending".equals(status)) {
            List<ObjectNode> tasks = new ArrayList<>();
            for (String framework : frameworks) for (Dimension dimension : DIMENSIONS) {
                String taskId = "diagnose-" + slug(framework) + "-" + slug(dimension.key());
                ArrayNode frameworkGrades = JSON.createArrayNode();
                for (JsonNode item : iterable(grades)) if (framework.equals(item.path("framework").asText())) frameworkGrades.add(item);
                String body = "task_id: " + taskId + "\n\n"
                        + "Quantitative framework auditor. Framework: " + frameworkLabel(framework) + ". Dimension: " + dimension.key() + ". Focus: " + dimension.focus() + "\n\n"
                        + anchorsText(run) + "\n\nRealized-P&L ledger context:\n" + positionText(run)
                        + "\n\nGraded results for THIS framework:\n" + json(frameworkGrades) + "\n\nCross-validation:\n" + jsString(crossval) + "\n\n"
                        + "Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence and name the prior rejection you are answering):\n"
                        + priorRejectionText(run) + "\n\n"
                        + "Previously-ADOPTED tunes, re-validated out-of-sample this run (propose REVERSING one only if it graded harmful):\n"
                        + priorTunesText(priorGrade) + "\n\n"
                        + "Diagnose SPECIFIC flaws with hard evidence (tight quotes, <=25 words each), rate severity, then propose concrete TUNES with exact before->after values and expected effect. Fewer, stronger tunes beat many weak ones. Preserve what worked. It is a legitimate finding to propose ZERO tunes if the sample genuinely supports no change — but say explicitly what you checked and ruled out, because a zero-tune dimension gets an independent adversarial pass before it is trusted.\n\n"
                        + "Return JSON matching this shape:\n" + json(SCHEMAS.get("DIAGNOSE"));
                ObjectNode task = writePromptTask(directory, taskId, model(run, "diagnose"), body);
                task.put("framework", framework); task.put("dimension", dimension.key()); task.put("origin", "diagnose"); tasks.add(task);
            }
            writeTasks(directory.resolve("plan.json"), tasks);
            run.with("phases").putObject("diagnose").put("status", "planned"); saveRun(runDir, run); return List.copyOf(tasks);
        }
        if ("awaiting_null_adversary".equals(status))
            throw new IllegalArgumentException("diagnose: null-adversary round already planned — run `collect diagnose` to ingest it before planning again");
        if ("collected".equals(status)) { diagnostic("diagnose already collected — nothing to plan"); return List.of(); }
        throw new IllegalArgumentException("diagnose: unexpected status \"" + status + "\"");
    }

    private ObjectNode collectDiagnose(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "diagnose"); String status = run.path("phases").path("diagnose").path("status").asText();
        JsonNode plan = readJson(directory.resolve("plan.json"));
        if ("planned".equals(status)) {
            ArrayNode diagnoses = JSON.createArrayNode(), failed = JSON.createArrayNode();
            for (JsonNode task : iterable(plan.path("tasks"))) {
                ReadResult result = readOut(task);
                if (!result.ok) { failed.add(failure(task, result.reason)); continue; }
                List<String> errors = validateSchema(result.data, SCHEMAS.get("DIAGNOSE"));
                if (!errors.isEmpty()) { failed.add(failure(task, String.join("; ", errors))); continue; }
                ObjectNode diagnosis = ((ObjectNode) result.data).deepCopy();
                diagnosis.set("framework", task.get("framework")); diagnosis.set("dimension", task.get("dimension")); diagnosis.set("origin", task.get("origin")); diagnoses.add(diagnosis);
            }
            if (!failed.isEmpty()) return failedResult(failed);
            List<JsonNode> zero = zeroTuneDiagnoses(diagnoses);
            if (!zero.isEmpty()) {
                Path adversaryDirectory = runDir.resolve("03b-null-adversary"); List<ObjectNode> tasks = new ArrayList<>();
                for (JsonNode diagnosis : zero) {
                    String framework = diagnosis.path("framework").asText(), dimension = diagnosis.path("dimension").asText();
                    String taskId = "null-adversary-" + slug(framework) + "-" + slug(dimension);
                    ObjectNode findings = JSON.createObjectNode(); findings.set("flaws", diagnosis.get("flaws")); findings.set("dimension", diagnosis.get("dimension"));
                    String body = "task_id: " + taskId + "\n\n"
                            + "You are the NULL ADVERSARY. A consensus diagnoser looked at " + frameworkLabel(framework) + "'s " + dimension + " dimension and proposed ZERO tunes. Your job is to attack that null specifically — do not accept \"nothing wrong\" without trying hard to find something.\n\n"
                            + "The diagnoser's own findings and reasoning (what it checked and ruled out):\n" + json(findings) + "\n\n"
                            + anchorsText(run) + "\n\nRealized-P&L ledger context:\n" + positionText(run) + "\n\n"
                            + "Previously-REJECTED or WITHHELD tunes (do NOT re-propose one unless you cite NEW out-of-sample evidence):\n" + priorRejectionText(run) + "\n\n"
                            + "Find what the consensus missed, if anything is genuinely there. If you ALSO find nothing after a real adversarial attempt, return zero tunes and say specifically what you tried that the original diagnoser didn't.\n\n"
                            + "Return JSON matching this shape:\n" + json(SCHEMAS.get("DIAGNOSE"));
                    ObjectNode task = writePromptTask(adversaryDirectory, taskId, model(run, "diagnose"), body);
                    task.put("framework", framework); task.put("dimension", dimension); task.put("origin", "null_adversary"); tasks.add(task);
                }
                writeTasks(adversaryDirectory.resolve("plan.json"), tasks);
                ObjectNode before = JSON.createObjectNode(); before.set("diagnoses", diagnoses); writeCanonical(directory.resolve("diagnoses_pre_na.json"), before);
                ObjectNode state = JSON.createObjectNode(); state.put("status", "awaiting_null_adversary"); state.put("zero_tune_dimensions", zero.size());
                run.with("phases").set("diagnose", state); saveRun(runDir, run);
                ObjectNode output = ok(); output.put("awaiting_null_adversary", true); output.put("tasks", tasks.size()); return output;
            }
            ObjectNode joined = JSON.createObjectNode(); joined.set("diagnoses", diagnoses); joined.put("null_adversary_passes", 0); writeCanonical(directory.resolve("joined.json"), joined);
            ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("diagnoses", diagnoses.size()); state.put("null_adversary_passes", 0);
            run.with("phases").set("diagnose", state); saveRun(runDir, run);
            ObjectNode output = ok(); output.put("diagnoses", diagnoses.size()); output.put("null_adversary_passes", 0); return output;
        }
        if ("awaiting_null_adversary".equals(status)) {
            Path adversaryDirectory = runDir.resolve("03b-null-adversary");
            JsonNode adversaryPlan = readJson(adversaryDirectory.resolve("plan.json"));
            ObjectNode prior = (ObjectNode) readJson(directory.resolve("diagnoses_pre_na.json"));
            ArrayNode diagnoses = prior.withArray("diagnoses"), failed = JSON.createArrayNode(); int passes = 0;
            for (JsonNode task : iterable(adversaryPlan.path("tasks"))) {
                ReadResult result = readOut(task); if (!result.ok) { failed.add(failure(task, result.reason)); continue; }
                List<String> errors = validateSchema(result.data, SCHEMAS.get("DIAGNOSE"));
                if (!errors.isEmpty()) { failed.add(failure(task, String.join("; ", errors))); continue; }
                ObjectNode diagnosis = ((ObjectNode) result.data).deepCopy(); diagnosis.set("framework", task.get("framework"));
                diagnosis.set("dimension", task.get("dimension")); diagnosis.set("origin", task.get("origin")); diagnoses.add(diagnosis); passes++;
            }
            if (!failed.isEmpty()) return failedResult(failed);
            ObjectNode joined = JSON.createObjectNode(); joined.set("diagnoses", diagnoses); joined.put("null_adversary_passes", passes); writeCanonical(directory.resolve("joined.json"), joined);
            ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("diagnoses", diagnoses.size()); state.put("null_adversary_passes", passes);
            run.with("phases").set("diagnose", state); saveRun(runDir, run);
            ObjectNode output = ok(); output.put("diagnoses", diagnoses.size()); output.put("null_adversary_passes", passes); return output;
        }
        if ("collected".equals(status)) { diagnostic("diagnose already collected"); ObjectNode output = ok(); output.put("already", true); return output; }
        throw new IllegalArgumentException("diagnose: unexpected status \"" + status + "\"");
    }
    // ========================================================================
    // VERIFY: triage -> skeptic panels -> pre-apply
    // ========================================================================
    private List<ObjectNode> planVerify(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "verify"); String status = run.path("phases").path("verify").path("status").asText();
        if (status.isEmpty() || "pending".equals(status)) {
            if (!"collected".equals(run.path("phases").path("diagnose").path("status").asText()))
                throw new IllegalArgumentException("verify: diagnose phase not collected yet");
            Files.createDirectories(directory); ArrayNode allTunes = collectAllTunes(runDir);
            ObjectNode all = JSON.createObjectNode(); all.set("allTunes", allTunes); writeCanonical(directory.resolve("all_tunes.json"), all);
            if (allTunes.size() > 8) {
                String taskId = "verify-triage"; ArrayNode slim = JSON.createArrayNode();
                for (JsonNode tune : allTunes) { ObjectNode row = slim.addObject(); copy(row, tune, "name", "framework", "dimension", "before", "after"); }
                String body = "task_id: " + taskId + "\n\n"
                        + "Tune triage. The candidate tunes below were proposed independently across framework×dimension diagnoses (some from a null-adversary pass) — overlapping proposals are common. Cluster NEAR-DUPLICATES only: pick the strongest/most precise variant as \"keep\" and list the others in \"merge\". Do NOT cluster tunes that merely touch the same section but change different things. Tunes not in any cluster are kept automatically — omit them.\n"
                        + "Candidate tunes:\n" + json(slim) + "\n\nReturn JSON matching this shape:\n" + json(SCHEMAS.get("TRIAGE"));
                ObjectNode task = writePromptTask(directory, taskId, model(run, "verify"), body);
                writeTasks(directory.resolve("plan_triage.json"), List.of(task));
                run.with("phases").putObject("verify").put("status", "awaiting_triage"); saveRun(runDir, run); return List.of(task);
            }
            return planVerifyPanels(runDir, run, allTunes);
        }
        if ("triaged".equals(status)) {
            JsonNode value = readJson(directory.resolve("tunes_post_triage.json"));
            return planVerifyPanels(runDir, run, (ArrayNode) value.path("tunes"));
        }
        if ("panels_collected".equals(status)) {
            JsonNode verdicts = readJson(directory.resolve("verdicts.json"));
            ArrayNode adjudicated = (ArrayNode) verdicts.path("adjudicated"), adopted = (ArrayNode) verdicts.path("adoptedSet");
            ArrayNode rejected = (ArrayNode) verdicts.path("rejectedSet"), unadjudicated = (ArrayNode) verdicts.path("unadjudicated");
            JsonNode editAudit = readJson(directory.resolve("edit_audit.json")).get("editAudit");
            if (adopted.isEmpty()) {
                ObjectNode joined = JSON.createObjectNode(); joined.set("adjudicated", adjudicated); joined.set("adoptedSet", adopted);
                joined.set("rejectedSet", rejected); joined.set("unadjudicated", unadjudicated); joined.set("preapply", NullNode.instance); joined.set("editAudit", editAudit);
                writeCanonical(directory.resolve("joined.json"), joined);
                ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("adopted", 0);
                state.put("rejected", rejected.size()); state.put("unadjudicated", unadjudicated.size()); run.with("phases").set("verify", state); saveRun(runDir, run);
                diagnostic("no adopted tunes — pre-apply audit skipped, verify collected"); return List.of();
            }
            JsonNode grades = readJson(phaseDir(runDir, "grade").resolve("joined.json")).path("grades");
            ArrayNode paths = compactPaths(grades), adoptedSlim = JSON.createArrayNode();
            for (JsonNode item : adopted) {
                JsonNode tune = item.path("tune"); ObjectNode row = adoptedSlim.addObject();
                copy(row, tune, "name", "framework", "dimension", "origin", "before", "after"); row.set("recommendation", item.get("recommendation"));
                ArrayNode modifications = row.putArray("modifications"), guardrails = row.putArray("guardrail_notes"), toolchain = row.putArray("toolchain_notes");
                for (JsonNode vote : iterable(item.path("votes"))) {
                    if (truthy(vote.get("modification"))) modifications.add(vote.get("modification"));
                    if (truthy(vote.get("guardrail_collision"))) guardrails.add(vote.get("guardrail_collision"));
                    if (truthy(vote.get("toolchain_coupling"))) toolchain.add(vote.get("toolchain_coupling"));
                }
            }
            String taskId = "verify-preapply";
            String body = "task_id: " + taskId + "\n\n"
                    + "FINAL PRE-APPLY AUDIT of the adopted tuning set — the last gate before these edits hit live SKILL files. Read the target skill file(s): " + joinText(run.path("targetSkills"), " ; ") + ".\n"
                    + "Adopted tunes with their skeptic votes:\n" + json(adoptedSlim) + "\n\n" + anchorsText(run)
                    + "\nGraded realized paths: " + json(paths) + "\nPreviously-rejected or withheld tunes:\n" + priorRejectionText(run) + "\n\n"
                    + "For EACH tune produce final_text and check: mutual consistency, reachability, throttle, decoupling, threshold crossings, denominator, scope (edit surface is the target SKILL file(s) only), toolchain coupling.\n\n"
                    + "Return JSON matching this shape:\n" + json(SCHEMAS.get("PREAPPLY"));
            ObjectNode task = writePromptTask(directory, taskId, model(run, "verify"), body);
            ObjectNode plan = JSON.createObjectNode(); ArrayNode tasks = plan.putArray("tasks"); tasks.add(task); plan.set("editAudit", editAudit); writeCanonical(directory.resolve("plan_preapply.json"), plan);
            ObjectNode state = JSON.createObjectNode(); state.put("status", "awaiting_preapply"); state.put("adopted", adopted.size());
            run.with("phases").set("verify", state); saveRun(runDir, run); return List.of(task);
        }
        if (List.of("awaiting_triage", "awaiting_panels", "awaiting_preapply").contains(status))
            throw new IllegalArgumentException("verify: sub-round \"" + status + "\" already planned — run `collect verify` first");
        if ("collected".equals(status)) { diagnostic("verify already collected — nothing to plan"); return List.of(); }
        throw new IllegalArgumentException("verify: unexpected status \"" + status + "\"");
    }

    private List<ObjectNode> planVerifyPanels(Path runDir, ObjectNode run, ArrayNode tunes) throws Exception {
        Path directory = phaseDir(runDir, "verify"); List<JsonNode> solo = new ArrayList<>(), batch = new ArrayList<>();
        for (JsonNode tune : tunes) (SOLO_PANEL_DIMENSIONS.contains(tune.path("dimension").asText()) ? solo : batch).add(tune);
        List<List<JsonNode>> groups = chunks(batch, knob(run, "verifyChunk", 5));
        JsonNode grades = readJson(phaseDir(runDir, "grade").resolve("joined.json")).path("grades");
        String paths = json(compactPaths(grades)); int skeptics = knob(run, "skepticsPerTune", 1); List<ObjectNode> tasks = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) for (int skeptic = 0; skeptic < skeptics; skeptic++) {
            List<JsonNode> group = groups.get(groupIndex); String taskId = "verify-batch" + (groupIndex + 1) + "-" + (skeptic + 1);
            List<String> blocks = new ArrayList<>(); ArrayNode names = JSON.createArrayNode();
            for (int index = 0; index < group.size(); index++) { blocks.add("--- TUNE " + (index + 1) + " of " + group.size() + " ---\n" + tuneBlock(group.get(index))); names.add(group.get(index).path("name").asText()); }
            String body = "task_id: " + taskId + "\n\n" + skepticIntro(skeptic, skeptics)
                    + "Adjudicate EACH of the " + group.size() + " tunes below SEPARATELY — echo each tune_name EXACTLY; independent verdicts.\n\n"
                    + String.join("\n\n", blocks) + "\n\n" + skepticCore(run, paths)
                    + "\n\nReturn JSON matching this shape:\n" + json(SCHEMAS.get("BATCH_VERDICT"));
            ObjectNode task = writePromptTask(directory, taskId, model(run, "verify"), body); task.put("kind", "batch"); task.set("group", names); tasks.add(task);
        }
        for (int tuneIndex = 0; tuneIndex < solo.size(); tuneIndex++) for (int skeptic = 0; skeptic < skeptics; skeptic++) {
            JsonNode tune = solo.get(tuneIndex); String taskId = "verify-solo" + (tuneIndex + 1) + "-" + (skeptic + 1);
            String body = "task_id: " + taskId + "\n\n" + skepticIntro(skeptic, skeptics)
                    + "This tune touches CAPITAL DEPLOYMENT or STOPS — it moves money and gets your undivided scrutiny.\n\n" + tuneBlock(tune) + "\n\n"
                    + skepticCore(run, paths) + "\n\nReturn JSON matching this shape:\n" + json(SCHEMAS.get("VERDICT"));
            ObjectNode task = writePromptTask(directory, taskId, model(run, "verify"), body); task.put("kind", "solo"); task.put("tuneName", tune.path("name").asText()); tasks.add(task);
        }
        String auditId = "verify-applied-edits-audit";
        String auditBody = "task_id: " + auditId + "\n\nAudit the parameter edits ALREADY APPLIED to these skill file(s): "
                + joinText(run.path("targetSkills"), " ; ") + ". Read each — the applied rule lives in the OPERATIVE text (§4/§5/§6), and its provenance entry lives in the sibling revision log: "
                + revisionLogsText(run) + "\n" + anchorsText(run) + "\n\n"
                + "Evaluate: internal consistency; reachability of any new trigger; throttle/runaway safety; for an inverse-companion framework, were dangerous mirrors correctly withheld; toolchain coupling drift; concrete remaining edits needed.\n\n"
                + "Return JSON: {\"editAudit\": \"<detailed prose>\"}";
        ObjectNode audit = writePromptTask(directory, auditId, model(run, "verify"), auditBody); audit.put("kind", "edit_audit"); tasks.add(audit);
        ObjectNode plan = JSON.createObjectNode(); ArrayNode taskArray = plan.putArray("tasks"); tasks.forEach(taskArray::add); plan.set("tunes", tunes); writeCanonical(directory.resolve("plan_panels.json"), plan);
        run.with("phases").putObject("verify").put("status", "awaiting_panels"); saveRun(runDir, run); return List.copyOf(tasks);
    }

    private ObjectNode collectVerify(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "verify"); String status = run.path("phases").path("verify").path("status").asText();
        if ("awaiting_triage".equals(status)) {
            ArrayNode allTunes = (ArrayNode) readJson(directory.resolve("all_tunes.json")).path("allTunes");
            JsonNode task = readJson(directory.resolve("plan_triage.json")).path("tasks").get(0); ReadResult result = readOut(task);
            if (!result.ok) return failedResult(array(failure(task, result.reason)));
            List<String> errors = validateSchema(result.data, SCHEMAS.get("TRIAGE"));
            if (!errors.isEmpty()) return failedResult(array(failure(task, String.join("; ", errors))));
            TriageResult triage = applyTriageClusters(allTunes, result.data.path("clusters")); ObjectNode file = JSON.createObjectNode(); file.set("tunes", triage.tunes()); writeCanonical(directory.resolve("tunes_post_triage.json"), file);
            ObjectNode state = JSON.createObjectNode(); state.put("status", "triaged"); state.put("proposed", allTunes.size()); state.put("after_triage", triage.tunes().size());
            run.with("phases").set("verify", state); saveRun(runDir, run); ObjectNode output = ok(); output.put("proposed", allTunes.size()); output.put("after_triage", triage.tunes().size()); return output;
        }
        if ("awaiting_panels".equals(status)) {
            JsonNode plan = readJson(directory.resolve("plan_panels.json")); ArrayNode tunes = (ArrayNode) plan.path("tunes");
            Map<String, ArrayNode> votes = new LinkedHashMap<>(); for (JsonNode tune : tunes) votes.put(tune.path("name").asText(), JSON.createArrayNode());
            JsonNode editAudit = NullNode.instance; ArrayNode failed = JSON.createArrayNode();
            for (JsonNode task : iterable(plan.path("tasks"))) {
                ReadResult result = readOut(task); String kind = task.path("kind").asText();
                if ("edit_audit".equals(kind)) {
                    if (result.ok && result.data != null && result.data.path("editAudit").isTextual()) editAudit = result.data.get("editAudit");
                    else failed.add(failure(task, result.ok ? "missing \"editAudit\" string" : result.reason));
                    continue;
                }
                if (!result.ok) { failed.add(failure(task, result.reason)); continue; }
                if ("batch".equals(kind)) {
                    List<String> errors = validateSchema(result.data, SCHEMAS.get("BATCH_VERDICT"));
                    if (!errors.isEmpty()) { failed.add(failure(task, String.join("; ", errors))); continue; }
                    Set<String> group = new LinkedHashSet<>(); for (JsonNode name : iterable(task.path("group"))) group.add(name.asText());
                    for (JsonNode vote : iterable(result.data.path("verdicts"))) if (group.contains(vote.path("tune_name").asText()) && votes.containsKey(vote.path("tune_name").asText())) votes.get(vote.path("tune_name").asText()).add(vote);
                } else if ("solo".equals(kind)) {
                    List<String> errors = validateSchema(result.data, SCHEMAS.get("VERDICT"));
                    if (!errors.isEmpty()) { failed.add(failure(task, String.join("; ", errors))); continue; }
                    ArrayNode destination = votes.get(task.path("tuneName").asText()); if (destination != null) destination.add(result.data);
                }
            }
            if (!failed.isEmpty()) return failedResult(failed);
            ArrayNode adjudicated = JSON.createArrayNode(), unadjudicated = JSON.createArrayNode();
            for (JsonNode tune : tunes) {
                ArrayNode tuneVotes = votes.get(tune.path("name").asText());
                if (tuneVotes.isEmpty()) { unadjudicated.add(tune); continue; }
                ObjectNode item = adjudicated.addObject(); item.set("tune", tune); item.put("recommendation", mergeStrictestWins(tuneVotes)); item.set("votes", tuneVotes);
            }
            ArrayNode adopted = JSON.createArrayNode(), rejected = JSON.createArrayNode();
            for (JsonNode item : adjudicated) ("reject".equals(item.path("recommendation").asText()) ? rejected : adopted).add(item);
            ObjectNode verdicts = JSON.createObjectNode(); verdicts.set("adjudicated", adjudicated); verdicts.set("adoptedSet", adopted); verdicts.set("rejectedSet", rejected); verdicts.set("unadjudicated", unadjudicated); writeCanonical(directory.resolve("verdicts.json"), verdicts);
            ObjectNode audit = JSON.createObjectNode(); audit.set("editAudit", editAudit); writeCanonical(directory.resolve("edit_audit.json"), audit);
            ObjectNode patch = JSON.createObjectNode(); ArrayNode names = patch.putArray("unadjudicated_tunes"); unadjudicated.forEach(tune -> names.add(tune.path("name").asText())); updateCoverage(runDir, patch);
            ObjectNode state = JSON.createObjectNode(); state.put("status", "panels_collected"); state.put("adopted", adopted.size()); state.put("rejected", rejected.size()); state.put("unadjudicated", unadjudicated.size());
            run.with("phases").set("verify", state); saveRun(runDir, run); ObjectNode output = ok(); output.put("adopted", adopted.size()); output.put("rejected", rejected.size()); output.put("unadjudicated", unadjudicated.size()); return output;
        }
        if ("awaiting_preapply".equals(status)) {
            JsonNode plan = readJson(directory.resolve("plan_preapply.json")), task = plan.path("tasks").get(0); ReadResult result = readOut(task);
            if (!result.ok) return failedResult(array(failure(task, result.reason)));
            List<String> errors = validateSchema(result.data, SCHEMAS.get("PREAPPLY")); if (!errors.isEmpty()) return failedResult(array(failure(task, String.join("; ", errors))));
            JsonNode verdicts = readJson(directory.resolve("verdicts.json")); ObjectNode joined = JSON.createObjectNode();
            copy(joined, verdicts, "adjudicated", "adoptedSet", "rejectedSet", "unadjudicated"); joined.set("preapply", result.data); joined.set("editAudit", plan.get("editAudit")); writeCanonical(directory.resolve("joined.json"), joined);
            int adopted = verdicts.path("adoptedSet").size(), rejected = verdicts.path("rejectedSet").size(), unadjudicated = verdicts.path("unadjudicated").size();
            ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("adopted", adopted); state.put("rejected", rejected); state.put("unadjudicated", unadjudicated); run.with("phases").set("verify", state); saveRun(runDir, run);
            ObjectNode output = ok(); output.put("adopted", adopted); return output;
        }
        if ("collected".equals(status)) {
            if (!Files.exists(directory.resolve("joined.json"))) {
                JsonNode verdicts = readJson(directory.resolve("verdicts.json")); JsonNode audit = readJson(directory.resolve("edit_audit.json")).get("editAudit");
                ObjectNode joined = JSON.createObjectNode(); copy(joined, verdicts, "adjudicated", "adoptedSet", "rejectedSet", "unadjudicated"); joined.set("preapply", NullNode.instance); joined.set("editAudit", audit); writeCanonical(directory.resolve("joined.json"), joined);
            }
            ObjectNode output = ok(); output.put("already", true); return output;
        }
        throw new IllegalArgumentException("verify: unexpected status \"" + status + "\"");
    }
    // ========================================================================
    // SYNTHESIZE
    // ========================================================================
    private List<ObjectNode> planSynthesize(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "synthesize");
        JsonNode grade = readJson(phaseDir(runDir, "grade").resolve("joined.json"));
        JsonNode diagnose = readJson(phaseDir(runDir, "diagnose").resolve("joined.json"));
        JsonNode verify = readJson(phaseDir(runDir, "verify").resolve("joined.json"));
        ObjectNode coverage = readCoverage(runDir); ArrayNode slim = JSON.createArrayNode();
        for (JsonNode item : iterable(verify.path("adjudicated"))) {
            JsonNode tune = item.path("tune"); ObjectNode row = slim.addObject();
            copy(row, tune, "name", "framework", "dimension", "origin", "before", "after"); row.set("recommendation", item.get("recommendation")); row.set("absorbed", tune.get("merged_from"));
            ArrayNode votes = row.putArray("votes");
            for (JsonNode vote : iterable(item.path("votes"))) {
                ObjectNode compact = votes.addObject(); compact.set("rec", vote.get("recommendation"));
                JsonNode why = "reject".equals(vote.path("recommendation").asText()) ? vote.get("refutation_attempt")
                        : truthy(vote.get("modification")) ? vote.get("modification")
                        : truthy(vote.get("guardrail_collision")) ? vote.get("guardrail_collision") : JSON.getNodeFactory().textNode("");
                compact.set("why", why);
                if (vote.has("counterfactual")) compact.set("counterfactual", vote.get("counterfactual"));
                if (vote.has("toolchain_coupling")) compact.set("toolchain_coupling", vote.get("toolchain_coupling"));
            }
        }
        ObjectNode scope = JSON.createObjectNode(); scope.set("mode", run.get("mode")); scope.set("items", run.get("scopeItems")); scope.set("skipped", run.get("scopeSkipped"));
        String prior;
        if (truthy(grade.get("priorGrade"))) prior = json(grade.get("priorGrade"));
        else prior = "full".equals(run.path("mode").asText()) ? "first calibration — none" : "SKIPPED — " + run.path("mode").asText() + " run scope excluded it";
        String preapply = truthy(verify.get("preapply")) ? json(verify.get("preapply")) : "n/a";
        String taskId = "synthesize-memo";
        String body = "task_id: " + taskId + "\n\n"
                + "Lead allocator writing the AUTHORITATIVE retrospective + strategy-correction memo. Calm, data-driven, unsentimental.\n\n"
                + "== Run scope ==\n" + json(scope) + "\n\n== Prior-calibration re-validation ==\n" + prior
                + "\n\n== Prior rejections held on the line this run ==\n" + priorRejectionText(run)
                + "\n\n== Per-series grades ==\n" + json(grade.path("grades")) + "\n\n== Cross-validation ==\n" + jsString(grade.get("crossval"))
                + "\n\n== Diagnoses (incl. null-adversary passes, origin-tagged) ==\n" + json(diagnose.path("diagnoses"))
                + "\n\n== Adjudicated verdicts (strictest-wins; \"absorbed\" = near-duplicates merged at triage) ==\n" + json(slim)
                + "\n\n== Pre-apply audit ==\n" + preapply + "\n\n== Applied-edits audit ==\n" + jsString(verify.get("editAudit"))
                + "\n\n== Coverage gaps ==\n" + json(coverage) + "\n\n"
                + "Markdown memo, sections: 1) Executive verdict. 1b) Run scope. 2) Prior-calibration re-validation. 3) Realized-path scorecard incl. realized P&L. 4) Prediction-accuracy analysis. 5) Structural flaws ranked, flag null-adversary-sourced ones. 6) VERIFIED tuning set table (Before/After/Verdict/Why/Toolchain edit required). 7) Remaining edits + coverage disclosure. 8) What to preserve + N=1 caveat. Specific, quantitative, honest.\n\n"
                + "Write your markdown memo directly to the out path below (NOT wrapped in JSON) — this task's output is markdown text, not a schema.";
        ObjectNode task = writePromptTask(directory, taskId, model(run, "synthesize"), body); writeTasks(directory.resolve("plan.json"), List.of(task));
        run.with("phases").putObject("synthesize").put("status", "planned"); saveRun(runDir, run); return List.of(task);
    }

    private ObjectNode collectSynthesize(Path runDir, ObjectNode run) throws Exception {
        Path directory = phaseDir(runDir, "synthesize"); JsonNode plan = readJson(directory.resolve("plan.json")), task = plan.path("tasks").get(0);
        Path out = Path.of(task.path("out").asText()); if (!Files.exists(out)) return failedResult(array(failure(task, "no output file written")));
        String memo = Files.readString(out, StandardCharsets.UTF_8); JsonNode verify = readJson(phaseDir(runDir, "verify").resolve("joined.json"));
        ObjectNode coverage = readCoverage(runDir), result = JSON.createObjectNode(), scope = result.putObject("scope");
        scope.set("mode", run.get("mode")); scope.set("items", run.get("scopeItems")); scope.set("skipped", run.get("scopeSkipped"));
        ObjectNode counts = result.putObject("counts"); counts.put("adopted", verify.path("adoptedSet").size()); counts.put("rejected", verify.path("rejectedSet").size());
        counts.put("unadjudicated", verify.path("unadjudicated").size()); counts.put("dropped_reports", coverage.path("dropped_reports").size());
        counts.put("dropped_series", coverage.path("dropped_series").size()); counts.put("null_adversary_passes", run.path("phases").path("diagnose").path("null_adversary_passes").asInt(0));
        ArrayNode adoptedTunes = result.putArray("adopted_tunes");
        for (JsonNode item : iterable(verify.path("adoptedSet"))) {
            JsonNode tune = item.path("tune"), audit = null;
            if (verify.path("preapply").path("tunes").isArray()) for (JsonNode candidate : verify.path("preapply").path("tunes"))
                if (candidate.path("name").asText().equals(tune.path("name").asText())) { audit = candidate; break; }
            ObjectNode row = adoptedTunes.addObject(); copy(row, tune, "name", "framework"); row.set("recommendation", item.get("recommendation")); row.set("origin", tune.get("origin"));
            if (audit != null) {
                row.set("apply_ok", audit.get("apply_ok")); row.set("final_text", audit.get("final_text"));
                if (audit.has("toolchain_edit_required")) row.set("toolchain_edit_required", audit.get("toolchain_edit_required"));
                if (audit.has("flags")) row.set("flags", audit.get("flags"));
            } else {
                row.put("apply_ok", false); row.put("final_text", ""); row.put("toolchain_edit_required", "UNKNOWN — missing from pre-apply audit");
                row.put("flags", "MISSING FROM PRE-APPLY AUDIT — do not apply");
            }
        }
        ArrayNode rejectedTunes = result.putArray("rejected_tunes");
        for (JsonNode item : iterable(verify.path("rejectedSet"))) {
            JsonNode tune = item.path("tune"); ObjectNode row = rejectedTunes.addObject(); copy(row, tune, "name", "framework"); List<String> reasons = new ArrayList<>();
            for (JsonNode vote : iterable(item.path("votes"))) if ("reject".equals(vote.path("recommendation").asText())) reasons.add(vote.path("refutation_attempt").asText()); row.put("why", String.join(" | ", reasons));
        }
        ArrayNode unadjudicated = result.putArray("unadjudicated_tunes");
        for (JsonNode tune : iterable(verify.path("unadjudicated"))) { ObjectNode row = unadjudicated.addObject(); copy(row, tune, "name", "framework"); }
        result.set("coverage", coverage); result.put("memo", memo); writeCanonical(directory.resolve("result.json"), result);
        ObjectNode state = JSON.createObjectNode(); state.put("status", "collected"); state.put("adopted", verify.path("adoptedSet").size()); state.put("rejected", verify.path("rejectedSet").size());
        run.with("phases").set("synthesize", state); saveRun(runDir, run); ObjectNode output = ok(); output.put("result_path", directory.resolve("result.json").toString()); return output;
    }

    private ObjectNode writePromptTask(Path directory, String taskId, String model, String body) throws Exception {
        Files.createDirectories(directory.resolve("tasks")); Files.createDirectories(directory.resolve("out"));
        Path out = directory.resolve("out").resolve(taskId + ".json");
        Path prompt = directory.resolve("tasks").resolve(taskId + ".prompt.md");
        String footer = "\n\n---\nWrite your JSON result to exactly this path (Write tool): " + out + "\n"
                + "Then reply with ONLY one line: \"OK <task_id> <n>\" (n = a rough size indicator, e.g. item count) or "
                + "\"FAIL <task_id> <reason, <=15 words>\". Do not restate your findings in the reply — the file is the deliverable.";
        Files.writeString(prompt, body + footer, StandardCharsets.UTF_8);
        ObjectNode task = JSON.createObjectNode(); task.put("task_id", taskId); task.put("model", model);
        task.put("prompt", prompt.toString()); task.put("out", out.toString()); return task;
    }

    private ArrayNode collectAllTunes(Path runDir) throws Exception {
        JsonNode diagnoses = readJson(phaseDir(runDir, "diagnose").resolve("joined.json")).path("diagnoses");
        ArrayNode tunes = JSON.createArrayNode();
        for (JsonNode diagnosis : iterable(diagnoses)) for (JsonNode proposed : iterable(diagnosis.path("proposed_tunes"))) {
            ObjectNode tune = ((ObjectNode) proposed).deepCopy(); tune.set("dimension", diagnosis.get("dimension"));
            tune.set("framework", diagnosis.get("framework")); tune.set("origin", diagnosis.get("origin")); tune.set("merged_from", JSON.createArrayNode()); tunes.add(tune);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (JsonNode value : tunes) {
            ObjectNode tune = (ObjectNode) value; String name = tune.path("name").asText(); int count = seen.getOrDefault(name, 0);
            seen.put(name, count + 1); if (count > 0) tune.put("name", name + " #" + (count + 1));
        }
        return tunes;
    }

    private static ArrayNode compactPaths(JsonNode grades) {
        ArrayNode paths = JSON.createArrayNode();
        for (JsonNode grade : iterable(grades)) { ObjectNode row = paths.addObject(); row.put("series", grade.path("framework").asText() + "/" + grade.path("asset").asText()); row.set("path", grade.get("realized_path")); }
        return paths;
    }

    private String skepticCore(ObjectNode run, String paths) {
        return anchorsText(run) + "\nGraded realized paths: " + paths + "\n\n"
                + "Previously-rejected or withheld tunes (a lookalike gets back in ONLY with new out-of-sample evidence — name what changed):\n" + priorRejectionText(run) + "\n\n"
                + "FIRST verify every number the rationale cites against the graded paths (Read the source report in " + run.path("corpusDir").asText() + " or reports/ if load-bearing) — a tune built on misquoted data is an automatic reject.\n"
                + "Mount the strongest refutation: Overfit? Worse outcome on a plausible ALTERNATE path? Does it weaken a guardrail? Internal inconsistency? If the target is the SHORT-side framework, ANY loosening of stops, gates, thresholds, or size caps is an automatic reject (Hard Rule 6).\n"
                + "TOOLCHAIN COUPLING: if this tune moves a band/threshold/cap value that tools/lib.mjs mirrors, name the exact lib.mjs symbol in \"toolchain_coupling\".\n"
                + "Run a counterfactual over the actual realized path. Recommendation: adopt / adopt_with_modification / reject, with the modification if any.";
    }

    private static String skepticIntro(int index, int total) {
        return "You are SKEPTIC " + (index + 1) + " of " + total + "; your job is to REFUTE proposed changes to a live framework. Default to skepticism — a tune must EARN adoption. "
                + LENSES.get(index % LENSES.size()) + "\n";
    }

    private static String tuneBlock(JsonNode tune) {
        String result = "name: " + tune.path("name").asText() + "\nframework: " + frameworkLabel(tune.path("framework").asText())
                + "\ndimension: " + tune.path("dimension").asText();
        if ("null_adversary".equals(tune.path("origin").asText())) result += "\norigin: NULL ADVERSARY (proposed after a consensus of diagnosers found nothing — scrutinize accordingly)";
        if (tune.path("merged_from").isArray() && !tune.path("merged_from").isEmpty()) result += "\nabsorbed near-duplicates: " + joinText(tune.path("merged_from"), ", ");
        return result + "\nbefore: " + tune.path("before").asText() + "\nafter: " + tune.path("after").asText() + "\nrationale: " + tune.path("rationale").asText();
    }

    private static String positionText(ObjectNode run) {
        return truthy(run.get("position")) ? json(run.get("position"))
                : "NOT SUPPLIED — no realized-P&L evidence available this run; Grade must state this explicitly rather than infer a zero.";
    }

    private static String anchorsText(ObjectNode run) {
        return truthy(run.get("anchors")) ? run.path("anchors").asText() : "(no live anchors supplied)";
    }

    private static String priorRejectionText(ObjectNode run) {
        if (!run.path("priorRejections").isArray() || run.path("priorRejections").isEmpty())
            return "none (first calibration, or no prior rejections on record)";
        List<String> lines = new ArrayList<>();
        for (JsonNode rejection : run.path("priorRejections"))
            lines.add("- [" + rejection.path("date").asText() + " " + rejection.path("verdict").asText() + "] "
                    + rejection.path("name").asText() + " (" + rejection.path("framework").asText() + "/" + rejection.path("surface").asText() + "): " + rejection.path("why").asText());
        return String.join("\n", lines);
    }

    private static String priorTunesText(JsonNode priorGrade) {
        if (priorGrade == null || !priorGrade.path("tunes").isArray() || priorGrade.path("tunes").isEmpty())
            return "none (first calibration, scoped run, or the re-validation agent failed)";
        List<String> lines = new ArrayList<>();
        for (JsonNode tune : priorGrade.path("tunes")) lines.add("- " + tune.path("name").asText() + ": " + tune.path("verdict").asText() + " — " + tune.path("evidence").asText());
        return String.join("\n", lines);
    }

    private static String revisionLogsText(ObjectNode run) {
        List<String> targets = new ArrayList<>(); for (JsonNode value : iterable(run.path("targetSkills"))) targets.add(value.asText());
        List<String> paths = revisionLogPaths(targets);
        if (paths.isEmpty()) return "(no target skill supplied — no revision log to read)";
        return String.join(" ; ", paths) + "\nIf a path above does not exist, the log may still be an inline \"## Framework Revision Log\" section of the SKILL itself (pre-2026-08-07 layout) — read it there. "
                + "Do NOT proceed as though the framework has no adopted-tune history: an empty history and an unreadable one are different findings, and reporting the second as the first is the exact failure this pipeline exists to catch.";
    }

    private static ObjectNode joinExtract(JsonNode extract, JsonNode file, JsonNode digest) {
        ObjectNode value = ((ObjectNode) extract).deepCopy(); value.put("file", file.path("f").asText()); value.put("asset", file.path("a").asText());
        value.put("framework", file.path("t").asText()); value.put("report_date", file.path("d").asText()); value.set("digest", digest == null ? NullNode.instance : digest); return value;
    }

    private static ObjectNode failure(JsonNode task, String reason) { ObjectNode value = JSON.createObjectNode(); value.set("task_id", task.get("task_id")); value.put("reason", reason); return value; }
    private static ObjectNode failedResult(ArrayNode failed) { ObjectNode value = JSON.createObjectNode(); value.put("ok", false); value.set("failed", failed); return value; }
    private static ObjectNode ok() { ObjectNode value = JSON.createObjectNode(); value.put("ok", true); return value; }
    private static ArrayNode array(JsonNode value) { ArrayNode result = JSON.createArrayNode(); result.add(value); return result; }
    private static void copy(ObjectNode target, JsonNode source, String... fields) { for (String field : fields) if (source != null && source.has(field)) target.set(field, source.get(field)); }
    private static void optional(ObjectNode target, String field, JsonNode value) { if (value != null && !value.isMissingNode()) target.set(field, value); }
    private static void writeCanonical(Path path, JsonNode value) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, ToolchainSupport.canonicalJSON(value) + "\n", StandardCharsets.UTF_8); }
    private static void writeTasks(Path path, List<ObjectNode> tasks) throws Exception { ObjectNode plan = JSON.createObjectNode(); ArrayNode values = plan.putArray("tasks"); tasks.forEach(values::add); writeCanonical(path, plan); }
    private static JsonNode digest(Path corpusDir, String file) throws Exception { Path path = corpusDir.resolve(file + ".digest.json"); if (!Files.exists(path)) return null; JsonNode value = readJson(path); return value.isNull() ? null : value; }
    private static String model(ObjectNode run, String phase) { return run.path("models").path(phase).asText(DEFAULT_MODELS.get(phase)); }
    private static int knob(ObjectNode run, String name, int fallback) { JsonNode value = run.path("knobs").get(name); return value == null || value.isNull() ? fallback : value.asInt(); }
    private static <T> List<List<T>> chunks(List<T> values, int size) { List<List<T>> result = new ArrayList<>(); for (int index = 0; index < values.size(); index += size) result.add(new ArrayList<>(values.subList(index, Math.min(values.size(), index + size)))); return result; }
    private static String jsString(JsonNode value) { if (value == null || value.isNull()) return "null"; if (value.isTextual()) return value.asText(); if (value.isBoolean() || value.isNumber()) return value.asText(); return "[object Object]"; }

    private void diagnostic(String message) { diagnostics.accept(message + "\n"); }

    private static JsonNode readJson(Path path) throws Exception {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        try {
            JsonNode value = JSON.readTree(raw);
            if (value == null || value.isMissingNode())
                throw new IllegalArgumentException("Unexpected end of JSON input");
            return value;
        } catch (Exception exception) {
            throw new IllegalArgumentException(CalibrationPaths.jsonParseMessage(raw, exception), exception);
        }
    }

    private ReadResult readOut(JsonNode task) {
        Path out = Path.of(task.path("out").asText());
        if (!Files.exists(out)) return new ReadResult(false, null, "no output file written");
        try {
            String raw = Files.readString(out, StandardCharsets.UTF_8); JsonNode value = JSON.readTree(raw);
            if (value == null || value.isMissingNode()) return new ReadResult(false, null, "unparseable JSON: Unexpected end of JSON input");
            return new ReadResult(true, value, null);
        } catch (Exception exception) {
            String raw; try { raw = Files.readString(out, StandardCharsets.UTF_8); } catch (Exception ignored) { raw = ""; }
            return new ReadResult(false, null, "unparseable JSON: " + CalibrationPaths.jsonParseMessage(raw, exception));
        }
    }

    private ObjectNode updateCoverage(Path runDir, ObjectNode patch) throws Exception {
        Path path = runDir.resolve("coverage.json");
        ObjectNode current = Files.exists(path) ? (ObjectNode) readJson(path) : emptyCoverage();
        for (Map.Entry<String, JsonNode> field : patch.properties()) {
            if (current.path(field.getKey()).isArray()) current.withArray(field.getKey()).addAll((ArrayNode) field.getValue());
            else current.set(field.getKey(), field.getValue());
        }
        Files.writeString(path, ToolchainSupport.canonicalJSON(current) + "\n", StandardCharsets.UTF_8); return current;
    }

    private ObjectNode readCoverage(Path runDir) throws Exception {
        Path path = runDir.resolve("coverage.json");
        return Files.exists(path) ? (ObjectNode) readJson(path) : emptyCoverage();
    }

    private static ObjectNode emptyCoverage() {
        ObjectNode value = JSON.createObjectNode(); value.set("dropped_reports", JSON.createArrayNode());
        value.set("dropped_series", JSON.createArrayNode()); value.set("unadjudicated_tunes", JSON.createArrayNode());
        value.set("sampled_out", JSON.createArrayNode()); value.set("notes", JSON.createArrayNode()); return value;
    }

    private static Path phaseDir(Path runDir, String phase) {
        return runDir.resolve(String.format("%02d-%s", PHASES.indexOf(phase) + 1, phase));
    }

    private static String frameworkLabel(String framework) { return FRAMEWORK_LABELS.getOrDefault(framework, framework); }
    private static String slug(String value) { return value.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("^-+|-+$", ""); }
    private static String json(JsonNode value) { try { return JSON.writeValueAsString(value); } catch (Exception exception) { throw new IllegalArgumentException(exception); } }
    private static boolean truthy(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return false; if (value.isBoolean()) return value.asBoolean(); if (value.isNumber()) return value.asDouble() != 0 && !Double.isNaN(value.asDouble()); if (value.isTextual()) return !value.asText().isEmpty(); return true; }
    private static Iterable<JsonNode> iterable(JsonNode value) { return value != null && value.isArray() ? value : List.of(); }
    private static ArrayNode strings(List<String> values) { ArrayNode result = JSON.createArrayNode(); values.forEach(result::add); return result; }
    private static List<String> commaList(String value) { List<String> result = new ArrayList<>(); for (String item : value.split(",", -1)) result.add(item.trim()); return List.copyOf(result); }
    private static boolean present(String value) { return value != null && !value.isEmpty(); }
    private static boolean zeroLength(JsonNode value) { if (value == null || value.isNull() || value.isMissingNode()) return true; if (value.isArray()) return value.isEmpty(); if (value.isTextual()) return value.asText().isEmpty(); return true; }
    private static String joinText(JsonNode values, String delimiter) { List<String> result = new ArrayList<>(); for (JsonNode value : iterable(values)) result.add(value.asText()); return String.join(delimiter, result); }
    private static boolean containsText(JsonNode values, String expected) { for (JsonNode value : iterable(values)) if (expected.equals(value.asText())) return true; return false; }
    private static String jsType(JsonNode value) { if (value.isTextual()) return "string"; if (value.isBoolean()) return "boolean"; if (value.isNumber()) return "number"; return "object"; }
    private static String randomSuffix() { return Long.toString(Math.abs(Double.doubleToLongBits(Math.random())), 36).substring(0, 4); }

    private static Map<String, JsonNode> schemas() {
        try {
            LinkedHashMap<String, JsonNode> result = new LinkedHashMap<>();
            result.put("CHUNK_EXTRACT", JSON.readTree("""
                    {"type":"object","required":["extracts"],"properties":{"extracts":{"type":"array","items":{"type":"object","required":["file","stance","probability_scenarios","pattern_predictions","falsifiable_claims"],"properties":{"file":{"type":"string"},"stance":{"type":"string"},"probability_scenarios":{"type":"array","items":{"type":"object","required":["scenario","probability","target_range","trigger"]}},"pattern_predictions":{"type":"array","items":{"type":"string"}},"falsifiable_claims":{"type":"array","items":{"type":"string"}},"declined_actions":{"type":"array","items":{"type":"string"}},"notable":{"type":"string"}}}}}}
                    """));
            result.put("GRADE", JSON.readTree("""
                    {"type":"object","required":["asset","realized_path","prediction_grades","ev_calibration","deployment_quality","stop_analysis","realized_pnl_note","overall"],"properties":{"asset":{"type":"string"},"realized_path":{"type":"array","items":{"type":"object","required":["date","price","score"]}},"prediction_grades":{"type":"array","items":{"type":"object","required":["prediction","source_date","verdict","evidence"],"properties":{"verdict":{"type":"string","enum":["correct","partially_correct","wrong","untested"]}}}},"ev_calibration":{"type":"string"},"deployment_quality":{"type":"string"},"stop_analysis":{"type":"string"},"realized_pnl_note":{"type":"string"},"overall":{"type":"string"}}}
                    """));
            result.put("PRIOR_GRADE", JSON.readTree("""
                    {"type":"object","required":["tunes","resolved_untested","overall"],"properties":{"tunes":{"type":"array","items":{"type":"object","required":["name","verdict","evidence"],"properties":{"verdict":{"type":"string","enum":["validated","harmful","not_exercised","indeterminate"]}}}},"resolved_untested":{"type":"array","items":{"type":"object","required":["prediction","verdict","evidence"]}},"overall":{"type":"string"}}}
                    """));
            result.put("DIAGNOSE", JSON.readTree("""
                    {"type":"object","required":["dimension","flaws","proposed_tunes"],"properties":{"dimension":{"type":"string"},"flaws":{"type":"array","items":{"type":"object","required":["flaw","evidence","severity"],"properties":{"severity":{"type":"string","enum":["critical","high","medium","low"]}}}},"proposed_tunes":{"type":"array","items":{"type":"object","required":["name","before","after","rationale"]}}}}
                    """));
            result.put("TRIAGE", JSON.readTree("""
                    {"type":"object","required":["clusters"],"properties":{"clusters":{"type":"array","items":{"type":"object","required":["keep","merge","reason"]}}}}
                    """));
            result.put("VERDICT", JSON.readTree("""
                    {"type":"object","required":["tune_name","holds","refutation_attempt","overfit_risk","unintended_consequences","recommendation"],"properties":{"recommendation":{"type":"string","enum":["adopt","adopt_with_modification","reject"]}}}
                    """));
            result.put("BATCH_VERDICT", JSON.readTree("""
                    {"type":"object","required":["verdicts"],"properties":{"verdicts":{"type":"array"}}}
                    """));
            result.put("PREAPPLY", JSON.readTree("""
                    {"type":"object","required":["tunes","overall"],"properties":{"tunes":{"type":"array","items":{"type":"object","required":["name","apply_ok","final_text","flags"]}}}}
                    """));
            return Collections.unmodifiableMap(result);
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static Map<String, String> defaultModels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("extract", "haiku"); result.put("grade", "sonnet"); result.put("diagnose", "opus");
        result.put("verify", "opus"); result.put("synthesize", "opus"); return Collections.unmodifiableMap(result);
    }

    public record Dimension(String key, String focus) {}
    public record Boundary(String boundary, String target) {}
    public record TriageResult(ArrayNode tunes, int mergedCount) {}
    public record InitResult(Path runDir, ObjectNode run, List<String> warnings) {}
    public record InitOptions(String corpus, String mode, String scopeItems, String scopeSkipped, String position,
                              String anchors, String registry, String priorCalibrations, String skillDir,
                              String targetSkills, String out, String runId, JsonNode models, JsonNode knobs) {}
    private record ReadResult(boolean ok, JsonNode data, String reason) {}
}
