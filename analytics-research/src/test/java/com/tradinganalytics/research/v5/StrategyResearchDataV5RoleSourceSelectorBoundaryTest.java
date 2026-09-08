package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Selector and custody-boundary coverage for role-source production inputs.
 *
 * <p>The two private seams are intentionally scoped: the public producer owns the full verified
 * source-chain, plan, predictor, and derived-row contract, while these tests isolate the pure
 * selector and its physical-reference binding after public-chain validation. Public producer
 * integration remains covered by the node-oracle fixtures.
 */
final class StrategyResearchDataV5RoleSourceSelectorBoundaryTest {
    private static final String H = "a".repeat(64);

    @Test
    void explicitRoleInventoriesNormalizeAliasesAndRejectForgedReferences() {
        ObjectNode explicit = object();
        explicit.set("label", array().add(reference("z.json", H)).add(reference("a.json", H)));
        List<ObjectNode> labels = select(explicit, "LABEL", List.of(), Map.of());
        assertThat(labels).extracting(row -> row.path("path").asText()).containsExactly("a.json", "z.json");

        ObjectNode singularObject = object();
        singularObject.set("EXECUTION", reference("execution.json", H));
        assertThat(select(singularObject, "EXECUTION", List.of(), Map.of())).hasSize(1);

        ObjectNode pluralAlias = object();
        pluralAlias.set("marks", array().add(reference("mark.json", H)));
        assertThat(select(pluralAlias, "MARK", List.of(), Map.of())).hasSize(1);

        ObjectNode empty = object();
        empty.set("feature", array());
        expectSelect(empty, "FEATURE", List.of(), Map.of(),
                "FEATURE authoritative role producer requires a non-empty source partition inventory");

        ObjectNode noPath = object();
        noPath.set("feature", array().add(reference("", H)));
        expectSelect(noPath, "FEATURE", List.of(), Map.of(),
                "FEATURE source inventory reference requires a path and partition hash");

        ObjectNode badHash = object();
        badHash.set("feature", array().add(reference("feature.json", "bad")));
        expectSelect(badHash, "FEATURE", List.of(), Map.of(),
                "FEATURE source inventory reference requires a path and partition hash");

        ObjectNode duplicate = object();
        duplicate.set("feature", array().add(reference("same.json", H)).add(reference("same.json", H)));
        expectSelect(duplicate, "FEATURE", List.of(), Map.of(),
                "FEATURE source inventory contains a duplicate partition");
    }

    @Test
    void inferredInventoriesHonorAcquisitionHydrationRolesAndFundingRequirements() {
        ObjectNode acquisition = manifest(
                capture("signal_bars", "acq/signal.jsonl", H, true),
                capture("raw_signal_bars", "acq/raw-signal.jsonl", H, false),
                capture("context_bars", "acq/context.jsonl", H, false),
                capture("macro_bars", "acq/macro.jsonl", H, false),
                capture("funding_events", "acq/funding.jsonl", H, false),
                capture("mark_bars", "acq/mark.jsonl", H, false));
        ObjectNode hydration = manifest(
                capture("opportunity_bars", "hydration/opportunity.jsonl", H, false),
                markCapture("hydration/mark.jsonl", H));
        List<Object> parts = parts(acquisition, "ACQUISITION", hydration, "HYDRATION");
        Map<String, ObjectNode> fundingRegistry = Map.of("funding_rate", object().put("source_field", "funding_rate")
                .put("source_family", "funding"));

        List<ObjectNode> features = select(object(), "FEATURE", parts, fundingRegistry);
        assertThat(features).extracting(row -> row.path("path").asText()).containsExactly(
                "acq/context.jsonl", "acq/funding.jsonl", "acq/macro.jsonl", "acq/signal.jsonl");
        assertThat(select(object(), "LABEL", parts, fundingRegistry)).extracting(row -> row.path("path").asText())
                .containsExactly("hydration/opportunity.jsonl");
        assertThat(select(object(), "EXECUTION", parts, fundingRegistry)).extracting(row -> row.path("path").asText())
                .containsExactly("hydration/opportunity.jsonl");
        assertThat(select(object(), "MARK", parts, fundingRegistry)).extracting(row -> row.path("path").asText())
                .containsExactly("hydration/mark.jsonl");

        ObjectNode noFunding = manifest(capture("signal_bars", "signal.jsonl", H, true));
        assertThat(select(object(), "FEATURE", parts(noFunding, "ACQUISITION"), fundingRegistry))
                .extracting(row -> row.path("path").asText()).containsExactly("signal.jsonl");
        ObjectNode noCandidates = object();
        expectSelect(noCandidates, "LABEL", parts(manifest(), "ACQUISITION"), Map.of(),
                "LABEL authoritative role producer requires a non-empty source partition inventory");
    }

    @Test
    void inferredMarkInventoryUsesAcquisitionMarkBarsWhenHydrationAbsent() {
        ObjectNode acquisition = manifest(
                capture("signal_bars", "signal.jsonl", H, true),
                capture("mark_bars", "mark.jsonl", H, false));
        List<ObjectNode> marks = select(object(), "MARK", parts(acquisition, "ACQUISITION"), Map.of());
        assertThat(marks).extracting(row -> row.path("path").asText()).containsExactly("mark.jsonl");

        ObjectNode unrelated = manifest(capture("context_bars", "context.jsonl", H, false));
        expectSelect(object(), "MARK", parts(unrelated, "ACQUISITION"), Map.of(),
                "MARK authoritative role producer requires a non-empty source partition inventory");
    }

    @Test
    void sourceRoleBoundsReopenTinyFilesAndRejectAmbiguousOrUnboundPaths(@TempDir Path root) throws Exception {
        byte[] bytes = "{\"asset\":\"btc\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("acq"));
        Files.write(root.resolve("acq/signal.jsonl"), bytes);
        String digest = StrategyResearchDataV5.hash(bytes);
        ObjectNode acquisition = manifest(capture("signal_bars", "acq/signal.jsonl", digest, true));
        List<Object> parts = parts(acquisition, "ACQUISITION");
        List<ObjectNode> refs = List.of(reference("acq/signal.jsonl", digest));
        List<?> bounds = sourceRoleBounds("FEATURE", parts, refs, root);
        assertThat(bounds).hasSize(1);

        ObjectNode wrongHash = reference("acq/signal.jsonl", "b".repeat(64));
        expectBounds("FEATURE", parts, List.of(wrongHash), root, "source partition bytes are missing or tampered");
        expectBounds("FEATURE", parts, List.of(reference("missing.jsonl", digest)), root,
                "source partition is not enumerated by the verified physical source chain");

        ObjectNode wrongRole = manifest(capture("unknown", "acq/signal.jsonl", digest, true));
        expectBounds("FEATURE", parts(wrongRole, "ACQUISITION"), refs, root, "source partition has no role-bound series type");

        ObjectNode second = manifest(capture("signal_bars", "acq/signal.jsonl", digest, true));
        expectBounds("FEATURE", parts(acquisition, "ACQUISITION", second, "ACQUISITION"), refs, root,
                "ambiguously enumerated by more than one physical capture");
    }

    private static List<ObjectNode> select(ObjectNode roleSources, String role, List<?> sourceParts,
            Map<String, ObjectNode> registry) {
        try {
            Class<?> owner = StrategyResearchDataV5.class;
            Method method = owner.getDeclaredMethod("roleSourceReferences", ObjectNode.class, String.class, List.class, Map.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") List<ObjectNode> result = (List<ObjectNode>) method.invoke(null, roleSources, role, sourceParts, registry);
            return result;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("role source selector reflection", error);
        }
    }

    private static List<?> sourceRoleBounds(String role, List<?> sourceParts, List<ObjectNode> refs, Path root) {
        try {
            Method method = StrategyResearchDataV5.class.getDeclaredMethod("sourceRoleBounds", List.class, String.class, List.class, Path.class);
            method.setAccessible(true);
            return (List<?>) method.invoke(null, sourceParts, role, refs, root);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("role source bounds reflection", error);
        }
    }

    private static void expectSelect(ObjectNode roleSources, String role, List<?> sourceParts,
            Map<String, ObjectNode> registry, String message) {
        assertThatThrownBy(() -> select(roleSources, role, sourceParts, registry)).hasMessageContaining(message);
    }

    private static void expectBounds(String role, List<?> sourceParts, List<ObjectNode> refs, Path root, String message) {
        assertThatThrownBy(() -> sourceRoleBounds(role, sourceParts, refs, root)).hasMessageContaining(message);
    }

    private static List<Object> parts(Object... values) {
        try {
            Class<?> type = Class.forName(StrategyResearchDataV5.class.getName() + "$RoleSourcePart");
            Constructor<?> constructor = type.getDeclaredConstructor(ObjectNode.class, String.class);
            constructor.setAccessible(true);
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (int index = 0; index < values.length; index += 2) {
                result.add(constructor.newInstance((ObjectNode) values[index], (String) values[index + 1]));
            }
            return result;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("role source part reflection", error);
        }
    }

    private static ObjectNode manifest(ObjectNode... captures) {
        ObjectNode value = object(); ArrayNode rows = value.putArray("captures");
        for (ObjectNode capture : captures) rows.add(capture);
        return value;
    }

    private static ObjectNode capture(String type, String path, String sha, boolean tradeable) {
        String instrument = Set.of("funding_events", "mark_bars").contains(type)
                ? "BINANCE_USDM_PERPETUAL" : "BINANCE_SPOT";
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", instrument).put("symbol", "BTCUSDT")
                .put("series_type", type).put("tradeable", tradeable);
        capture.set("partition", reference(path, sha));
        return capture;
    }

    private static ObjectNode markCapture(String path, String sha) {
        ObjectNode capture = capture("mark_bars", "hydration/unused.jsonl", sha, false);
        capture.remove("partition");
        capture.set("mark_partition", reference(path, sha));
        return capture;
    }

    private static ObjectNode reference(String path, String sha) {
        return object().put("path", path).put("sha256", sha);
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}
