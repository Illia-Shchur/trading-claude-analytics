package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CalibrationRegistryCommandTest {
    @TempDir
    Path repository;

    private StringWriter stdout;
    private StringWriter stderr;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(repository.resolve("reports"));
        stdout = new StringWriter();
        stderr = new StringWriter();
        commandLine = new CommandLine(new CalibrationRegistryCommand(repository, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
    }

    @Test
    void validatesMissingRegistryAsAnEmptyRegistry() {
        assertThat(commandLine.execute("validate")).isZero();
        assertThat(stderr.toString()).isEqualTo("OK — 0 entries, schema valid\n");
    }

    @Test
    void listsFilteredEntriesAsPrettyCanonicalJson() throws Exception {
        Files.writeString(repository.resolve("reports/calibration-registry.json"), """
                {"schema":"calibration-registry/1","entries":[
                  {"date":"2026-08-01","run_id":"1","framework":"both","surface":"risk","name":"Keep hard stop","verdict":"rejected","why":"unsafe"},
                  {"date":"2026-08-02","run_id":"2","framework":"fallen_knives","surface":"score","name":"Score tune","verdict":"adopted","why":"evidence"}
                ]}
                """);

        assertThat(commandLine.execute("list", "--verdict", "rejected", "--json")).isZero();
        assertThat(stdout.toString()).contains("\"name\": \"Keep hard stop\"").doesNotContain("Score tune");
        assertThat(stderr.toString()).isEmpty();
    }

    @Test
    void appendRejectsAnInvalidPayloadWithoutWritingRegistry() throws Exception {
        Files.writeString(repository.resolve("bad.json"), "{\"name\":\"incomplete\"}");

        assertThat(commandLine.execute("append", "bad.json")).isOne();
        assertThat(stderr.toString()).contains("appended entries would break validation");
        assertThat(repository.resolve("reports/calibration-registry.json")).doesNotExist();
    }

    @Test
    void matchPrintsPointersButNeverTurnsThemIntoVerdicts() throws Exception {
        Files.writeString(repository.resolve("reports/calibration-registry.json"), """
                {"schema":"calibration-registry/1","entries":[
                  {"date":"2026-08-01","run_id":"1","framework":"both","surface":"funding threshold","name":"Relax funding veto threshold","verdict":"rejected","why":"unsafe"}
                ]}
                """);

        assertThat(commandLine.execute("match", "relax funding threshold")).isZero();
        assertThat(stdout.toString()).isEqualTo(
                "[3] 2026-08-01 rejected: Relax funding veto threshold\n    why: unsafe\n\n");
    }
}
