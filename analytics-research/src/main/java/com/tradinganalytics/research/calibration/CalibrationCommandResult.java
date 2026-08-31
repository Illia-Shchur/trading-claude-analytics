package com.tradinganalytics.research.calibration;

/** Captured result for standalone, intentionally unregistered calibration commands. */
public record CalibrationCommandResult(int exitCode, String stdout, String stderr) {
    public CalibrationCommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public static CalibrationCommandResult success(String stdout, String stderr) {
        return new CalibrationCommandResult(0, stdout, stderr);
    }

    public static CalibrationCommandResult failure(String stdout, String stderr) {
        return new CalibrationCommandResult(1, stdout, stderr);
    }

    public void emitAndExit() {
        System.out.print(stdout);
        System.err.print(stderr);
        if (exitCode != 0) System.exit(exitCode);
    }
}
