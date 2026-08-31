package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CiConfirmationCliCommandTest {
    @Test
    void reservationIsMandatory() {
        Invocation result = execute(path -> { throw new AssertionError(); });
        assertThat(result.exitCode()).isOne();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo("confirmation evaluator requires --reservation\n");
    }

    @Test
    void callerAuthoredEvidenceIsRejectedBeforeUnavailableRunnerMessage() {
        Invocation result = execute(path -> JsonNodeFactory.instance.objectNode(),
                "--reservation", "reserved.json", "--metrics", "metrics.json");
        assertThat(result.exitCode()).isOne();
        assertThat(result.stderr()).isEqualTo(
                "caller-authored result/trades/metrics are forbidden for CI confirmation\n");
    }

    @Test
    void preflightAndNormalRunHaveDistinctFailClosedReasons() {
        CiConfirmationCliCommand.ConfirmationValidator valid = path -> JsonNodeFactory.instance.objectNode();
        Invocation preflight = execute(valid, "--reservation", "reserved.json", "--preflight");
        assertThat(preflight.stderr()).contains("preflight failed before any burn action");

        Invocation run = execute(valid, "--reservation", "reserved.json");
        assertThat(run.stderr()).contains("no CI_ATTESTED_CONFIRMATION can be produced");
        assertThat(preflight.exitCode()).isOne();
        assertThat(run.exitCode()).isOne();
    }

    private static Invocation execute(CiConfirmationCliCommand.ConfirmationValidator validator,
                                      String... arguments) {
        CommandLine line = new CommandLine(new CiConfirmationCliCommand(validator));
        StringWriter out = new StringWriter(), err = new StringWriter();
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return new Invocation(line.execute(arguments), out.toString(), err.toString());
    }

    private record Invocation(int exitCode, String stdout, String stderr) { }
}
