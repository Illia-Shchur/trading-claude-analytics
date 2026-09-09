package com.tradinganalytics.research.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

class CalibrationPropertiesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CalibrationCorpus corpus = new CalibrationCorpus(JSON);

    @Property(tries = 300)
    void eventPreservingCapNeverLosesEndpointsOrMisaccountsFiles(
            @ForAll @IntRange(min = 1, max = 60) int size,
            @ForAll @IntRange(min = 1, max = 30) int cap) {
        List<com.fasterxml.jackson.databind.JsonNode> reports = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            ObjectNode report = JSON.createObjectNode(); report.put("f", "r" + index); report.put("t", "fallen_knives");
            ObjectNode digest = report.putObject("digest"); digest.put("ok", true);
            digest.putObject("gates").put("passed", 3); digest.putObject("score").put("adjusted", 9.25);
            digest.putObject("deployment").putArray("tranches"); reports.add(report);
        }
        CalibrationCorpus.Selection selection = corpus.selectWithCap(reports, cap);
        assertThat(selection.keptIndexes()).contains(0, size - 1);
        Set<String> keptFiles = new LinkedHashSet<>();
        selection.keptIndexes().forEach(index -> keptFiles.add("r" + index));
        assertThat(Collections.disjoint(keptFiles, selection.sampledOut())).isTrue();
        assertThat(keptFiles.size() + selection.sampledOut().size()).isEqualTo(size);
        if (size <= cap) assertThat(selection.keptIndexes()).hasSize(size);
        else if (!selection.capExceededByEvents()) assertThat(selection.keptIndexes()).hasSize(cap);
    }

    @Property(tries = 250)
    void terminalMachineBlockAccountsUtf8AndHashesRawExactly(
            @ForAll @StringLength(max = 80) String prose,
            @ForAll @StringLength(max = 80) String payloadText) throws Exception {
        String raw = JSON.writeValueAsString(payloadText) + "\n";
        String delimiter = "\n---\n\n```json machine\n";
        String suffix = "```\n";
        String report = prose + delimiter + raw + suffix;
        CalibrationCorpus.DropResult result = corpus.dropMachineBlock(report);
        assertThat(result.text()).isEqualTo(prose + "\n");
        assertThat(result.dropped().raw()).isEqualTo(raw);
        assertThat(result.dropped().bytes()).isEqualTo((delimiter + raw + suffix).getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.dropped().sha256()).isEqualTo(Sha256.hex(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Property(tries = 200)
    void validatorReportsEveryMissingTopLevelRequirement(
            @ForAll @IntRange(min = 0, max = 7) int keepCount) {
        var schema = CalibrationRun.SCHEMAS.get("GRADE");
        ObjectNode value = JSON.createObjectNode(); ArrayNode required = (ArrayNode) schema.path("required");
        for (int index = 0; index < Math.min(keepCount, required.size()); index++) value.put(required.get(index).asText(), "x");
        List<String> errors = CalibrationRun.validateSchema(value, schema);
        assertThat(errors.stream().filter(message -> message.contains("missing required")).count())
                .isEqualTo(required.size() - Math.min(keepCount, required.size()));
    }
}
