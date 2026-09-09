package com.tradinganalytics.infrastructure.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes an argv vector without a shell and bounds time plus both output streams. */
public final class BoundedProcessExecutor {
    public record Limits(Duration timeout, int maxStdoutBytes, int maxStderrBytes) {
        public static final Limits DEFAULT = new Limits(Duration.ofSeconds(30), 1_048_576, 1_048_576);

        public Limits {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("process timeout must be positive");
            }
            if (maxStdoutBytes < 1 || maxStderrBytes < 1) {
                throw new IllegalArgumentException("process output ceilings must be positive");
            }
        }
    }

    public record Result(int exitCode, byte[] stdout, byte[] stderr, Duration duration) {
        public Result {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        @Override public byte[] stdout() { return stdout.clone(); }
        @Override public byte[] stderr() { return stderr.clone(); }

        public String stdoutUtf8() { return new String(stdout, StandardCharsets.UTF_8); }
        public String stderrUtf8() { return new String(stderr, StandardCharsets.UTF_8); }
    }

    private BoundedProcessExecutor() {}

    public static Result execute(List<String> command, Path workingDirectory, Limits limits) {
        return execute(command, workingDirectory, Map.of(), limits);
    }

    public static Result execute(
            List<String> command, Path workingDirectory, Map<String, String> environment, Limits limits) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(limits, "limits");
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("process command must be a non-empty argv vector");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        if (workingDirectory != null) {
            builder.directory(PathConfinement.requireRealDirectory(workingDirectory, "process working directory").toFile());
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new CustodyException("bounded process cannot be started", error);
        }
        AtomicReference<CustodyException> overflow = new AtomicReference<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> readBounded(
                    process.getInputStream(), limits.maxStdoutBytes(), "stdout", process, overflow));
            Future<byte[]> stderr = executor.submit(() -> readBounded(
                    process.getErrorStream(), limits.maxStderrBytes(), "stderr", process, overflow));
            boolean exited;
            try {
                exited = process.waitFor(limits.timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminate(process);
                throw new CustodyException("bounded process wait was interrupted", interrupted);
            }
            if (!exited) {
                terminate(process);
                awaitTermination(process);
                throw new CustodyException("bounded process exceeded timeout " + limits.timeout());
            }
            byte[] out = future(stdout, "stdout");
            byte[] err = future(stderr, "stderr");
            CustodyException outputFailure = overflow.get();
            if (outputFailure != null) {
                throw outputFailure;
            }
            return new Result(process.exitValue(), out, err, Duration.ofNanos(System.nanoTime() - started));
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    private static byte[] readBounded(
            InputStream input, int maximum, String stream, Process process,
            AtomicReference<CustodyException> overflow) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8_192));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) total + count > maximum) {
                overflow.compareAndSet(null,
                        new CustodyException("bounded process " + stream + " exceeded byte ceiling"));
                terminate(process);
                return output.toByteArray();
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static byte[] future(Future<byte[]> future, String stream) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CustodyException("bounded process " + stream + " collection was interrupted", interrupted);
        } catch (ExecutionException error) {
            throw new CustodyException("bounded process " + stream + " cannot be collected", error.getCause());
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void awaitTermination(Process process) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
