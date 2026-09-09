package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Unregistered command adapter for {@code tools/lint-swing-calibration.mjs}. */
public final class SwingCalibrationLintCommand {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SwingCalibrationLintCommand() {}

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        return run(arguments, stdout, stderr, Path.of("").toAbsolutePath().normalize());
    }

    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr, Path workingDirectory) {
        try {
            String name = arguments.length == 0 ? "calibrations/swing-btc-eth.json" : arguments[0];
            Path path = Path.of(name).isAbsolute() ? Path.of(name).normalize() : workingDirectory.resolve(name).toAbsolutePath().normalize();
            JsonNode report = MAPPER.readTree(Files.readString(path));
            List<String> errors = SwingCalibrationLinter.lint(report, workingDirectory);
            if (!errors.isEmpty()) {
                stderr.println("FAIL swing calibration lint: " + String.join("; ", errors));
                return 1;
            }
            stdout.println("PASS swing calibration lint: " + path);
            return 0;
        } catch (Exception exception) {
            stderr.println("Error: " + exception.getMessage());
            return 1;
        }
    }
}
