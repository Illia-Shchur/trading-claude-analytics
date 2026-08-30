package com.tradinganalytics.cli;

import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdaptersCommandAdapter;
import com.tradinganalytics.marketdata.research.ResearchDataCommandAdapter;
import com.tradinganalytics.research.calibration.CalibrationCommandResult;
import com.tradinganalytics.research.calibration.CalibrationCorpusCommand;
import com.tradinganalytics.research.calibration.CalibrationPaths;
import com.tradinganalytics.research.calibration.CalibrationRunCommand;
import com.tradinganalytics.research.legacy.LegacyResearchCommandAdapter;
import com.tradinganalytics.research.legacy.LegacyResearchMigrationV3CommandAdapter;
import com.tradinganalytics.research.legacy.LegacyResearchNextCommandAdapter;
import com.tradinganalytics.research.v5.StrategyProspectiveRunnerCommandAdapter;
import com.tradinganalytics.research.swing.SwingCalibrationCommand;
import com.tradinganalytics.research.swing.SwingCalibrationLintCommand;
import com.tradinganalytics.research.swing.SwingCandidates;
import com.tradinganalytics.research.swing.SwingCrossValidateCommand;
import com.tradinganalytics.research.swing.SwingEngineCommand;
import com.tradinganalytics.research.swing.SwingStrategyCrossValidateCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/**
 * Spring/Picocli entry points for already-ported tools whose compatibility
 * adapters intentionally retain the original Node command-line grammar.
 *
 * <p>The passthrough boundary is deliberate: Picocli owns application-level
 * dispatch while each exhaustively differential-tested adapter remains the
 * sole parser for its legacy arguments. Unknown options are therefore retained
 * as positional values in their original order.</p>
 */
public final class PortedToolCliCommands {
    private PortedToolCliCommands() {}

    abstract static class StreamAdapter implements Callable<Integer> {
        @Unmatched
        private List<String> arguments = new ArrayList<>();

        @Spec
        private CommandSpec spec;

        protected abstract int execute(String[] arguments, PrintStream stdout, PrintStream stderr);

        @Override
        public final Integer call() {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int status;
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                status = execute(arguments.toArray(String[]::new), out, err);
            }
            spec.commandLine().getOut().print(stdout.toString(StandardCharsets.UTF_8));
            spec.commandLine().getErr().print(stderr.toString(StandardCharsets.UTF_8));
            return status;
        }
    }

    abstract static class CalibrationAdapter implements Callable<Integer> {
        @Unmatched
        private List<String> arguments = new ArrayList<>();

        @Spec
        private CommandSpec spec;

        protected final List<String> arguments() {
            return List.copyOf(arguments);
        }

        protected abstract CalibrationCommandResult execute();

        @Override
        public final Integer call() {
            CalibrationCommandResult result = execute();
            spec.commandLine().getOut().print(result.stdout());
            spec.commandLine().getErr().print(result.stderr());
            return result.exitCode();
        }
    }

    @Component
    @Command(name = "calib-corpus", description = "Build a bounded calibration corpus")
    public static final class CalibrationCorpusCli extends CalibrationAdapter {
        private final Path repositoryRoot;

        public CalibrationCorpusCli() {
            this(CalibrationPaths.repositoryRoot(Path.of("")));
        }

        CalibrationCorpusCli(Path repositoryRoot) {
            this.repositoryRoot = repositoryRoot;
        }

        @Override
        protected CalibrationCommandResult execute() {
            return CalibrationCorpusCommand.run(arguments(), repositoryRoot);
        }
    }

    @Component
    @Command(name = "calib-run", description = "Manage a deterministic calibration run")
    public static final class CalibrationRunCli extends CalibrationAdapter {
        private final Path workingDirectory;
        private final Path repositoryRoot;

        public CalibrationRunCli() {
            this(Path.of("").toAbsolutePath().normalize());
        }

        CalibrationRunCli(Path workingDirectory) {
            this(workingDirectory, CalibrationPaths.repositoryRoot(workingDirectory));
        }

        CalibrationRunCli(Path workingDirectory, Path repositoryRoot) {
            this.workingDirectory = workingDirectory;
            this.repositoryRoot = repositoryRoot;
        }

        @Override
        protected CalibrationCommandResult execute() {
            return CalibrationRunCommand.run(arguments(), workingDirectory, repositoryRoot);
        }
    }

    @Component
    @Command(name = "public-data-adapters", description = "Backfill or resume public market data")
    public static final class PublicDataAdaptersCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return PublicDataAdaptersCommandAdapter.run(arguments, stdout, stderr,
                    new PublicDataAdapters.JdkInjectableHttpClient());
        }
    }

    @Component
    @Command(name = "research-data", description = "Manage immutable research-lake data")
    public static final class ResearchDataCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return ResearchDataCommandAdapter.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "migrate-research-v3", description = "Inventory legacy research for v3 migration")
    public static final class LegacyResearchMigrationV3Cli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return LegacyResearchMigrationV3CommandAdapter.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "strategy-research", description = "Run the legacy v1-v3 research command surface")
    public static final class LegacyStrategyResearchCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return LegacyResearchCommandAdapter.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "strategy-research-next",
            description = "Run the deterministic strategy-research /4 command surface")
    public static final class LegacyStrategyResearchNextCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return LegacyResearchNextCommandAdapter.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "strategy-prospective-runner",
            description = "Run the future-only prospective ledger scheduled runner")
    public static final class StrategyProspectiveRunnerCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return StrategyProspectiveRunnerCommandAdapter.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "swing-candidates", description = "Print the frozen swing candidate catalog")
    public static final class SwingCandidatesCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingCandidates.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "swing-calibrate", description = "Calibrate swing candidates")
    public static final class SwingCalibrationCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingCalibrationCommand.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "lint-swing-calibration", description = "Lint a swing calibration artifact")
    public static final class SwingCalibrationLintCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingCalibrationLintCommand.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "swing-cross-validate", description = "Cross-validate swing candidates")
    public static final class SwingCrossValidateCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingCrossValidateCommand.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "swing-engine", description = "Build, run, inspect, or benchmark the swing engine")
    public static final class SwingEngineCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingEngineCommand.run(arguments, stdout, stderr);
        }
    }

    @Component
    @Command(name = "swing-strategy-cross-validate", description = "Validate a frozen swing strategy")
    public static final class SwingStrategyCrossValidateCli extends StreamAdapter {
        @Override
        protected int execute(String[] arguments, PrintStream stdout, PrintStream stderr) {
            return SwingStrategyCrossValidateCommand.run(arguments, stdout, stderr);
        }
    }
}
