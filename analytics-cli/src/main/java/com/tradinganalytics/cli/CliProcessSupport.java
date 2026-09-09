package com.tradinganalytics.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;

/** Shared process execution for CLI commands that invoke the local Git client. */
final class CliProcessSupport {
    private CliProcessSupport() {}

    static String runGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        FutureTask<String> stdoutReader = new FutureTask<>(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        FutureTask<String> stderrReader = new FutureTask<>(
                () -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(stdoutReader);
        Thread.ofVirtual().start(stderrReader);
        int status = process.waitFor();
        String stdout = stdoutReader.get();
        String stderr = stderrReader.get();
        if (status != 0) throw new GitCommandFailure(stderr.trim());
        return stdout;
    }

    static final class GitCommandFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private GitCommandFailure(String message) {
            super(message);
        }
    }
}
