package com.tradinganalytics.reporting;

/** Captured, byte-oriented result of an unregistered reporting command adapter. */
public record ReportingCommandResult(int exitCode, String stdout, String stderr) {
    public ReportingCommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public static ReportingCommandResult success(String stdout, String stderr) {
        return new ReportingCommandResult(0, stdout, stderr);
    }

    public static ReportingCommandResult failure(String stdout, String stderr) {
        return new ReportingCommandResult(1, stdout, stderr);
    }

    /** Used only by each standalone {@code main}; Spring/analytics-cli registration is deliberately separate. */
    public void emitAndExit() {
        System.out.print(stdout);
        System.err.print(stderr);
        if (exitCode != 0) System.exit(exitCode);
    }
}
