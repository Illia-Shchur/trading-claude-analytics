package com.tradinganalytics.research.v5;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Portable, bounded process-resource probes used by the v5 workers. */
final class StrategyProcessResourcesV1 {
    static final long PROBE_TIMEOUT_MILLIS = 2_000L;

    private StrategyProcessResourcesV1() { }

    /** Returns resident-set bytes, or {@code -1} when the process cannot be sampled. */
    static long processRssBytes(long pid) {
        return processRssBytes(pid, isWindows(), StrategyProcessResourcesV1::start);
    }

    /** Package-private seam for deterministic command, parsing, and failure tests. */
    static long processRssBytes(long pid, boolean windows, ProcessLauncher launcher) {
        if (pid <= 0L || launcher == null) return -1L;
        List<String> command = windows ? windowsCommand(pid) : unixCommand(pid);
        try {
            Process process = launcher.start(command);
            if (process == null) return -1L;
            InputStream stdout = null;
            InputStream stderr = null;
            OutputStream stdin = null;
            try {
                stdout = process.getInputStream();
                stderr = process.getErrorStream();
                stdin = process.getOutputStream();
                if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return -1L;
                }
                if (process.exitValue() != 0 || stdout == null) return -1L;
                String output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (output.isBlank()) return -1L;
                String value = windows ? output : output.split("\\s+")[0];
                long number = Long.parseLong(value);
                if (number < 0L) return -1L;
                return windows ? number : Math.multiplyExact(number, 1024L);
            } finally {
                closeQuietly(stdout);
                closeQuietly(stderr);
                closeQuietly(stdin);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (IOException | RuntimeException error) {
            return -1L;
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> unixCommand(long pid) {
        return List.of("ps", "-o", "rss=", "-p", Long.toString(pid));
    }

    private static List<String> windowsCommand(long pid) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Process -Id " + Long.toString(pid) + ").WorkingSet64");
    }

    private static Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) { }
    }
}
