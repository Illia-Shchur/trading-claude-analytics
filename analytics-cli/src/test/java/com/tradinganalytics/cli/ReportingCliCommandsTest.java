package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ReportingCliCommandsTest {
    @Test
    void finalizeRenderAndLintPreserveStandaloneUsageContracts() {
        Invocation finalize = execute(new FinalizeReportCliCommand());
        assertThat(finalize.exitCode()).isOne();
        assertThat(finalize.stderr()).isEqualTo(
                "usage: ./bin/analytics finalize-report <draft.json> [--out reports/<report_id>.json]\n");

        Invocation render = execute(new RenderReportCliCommand());
        assertThat(render.exitCode()).isOne();
        assertThat(render.stderr()).isEqualTo(
                "usage: ./bin/analytics render-report <report.json> --mode full|summary [--out reports/<stem>.md]\n");

        Invocation lint = execute(new LintReportCliCommand());
        assertThat(lint.exitCode()).isOne();
        assertThat(lint.stderr()).isEqualTo("usage: ./bin/analytics lint-report <report.md> [--legacy]\n");
    }

    @Test
    void exportSignalsPropagatesDomainFailureAndStreams() {
        Invocation result = execute(new ExportSignalsCliCommand(), "--dry-run", "--reports", "missing-report-dir");
        assertThat(result.exitCode()).isOne();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("missing-report-dir");
    }

    @Test
    void rootApplicationAdvertisesEveryReportingWorkflow() {
        Invocation result = execute(new AnalyticsCommand(), "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains(
                "finalize-report", "render-report", "lint-report", "export-signals",
                "backfill-report-phase-registry", "strategy-public-smoke", "research-smoke",
                "strategy-attestation", "ci-confirmation", "ci-burn-tag",
                "verify-evidence-writer-installation");
    }

    private static Invocation execute(Object command, String... arguments) {
        CommandLine line = new CommandLine(command);
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
