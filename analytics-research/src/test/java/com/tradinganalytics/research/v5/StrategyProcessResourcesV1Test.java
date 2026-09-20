package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class StrategyProcessResourcesV1Test {
    @Test
    void unixProbeUsesPsAndConvertsKilobytesToBytes() {
        List<String> command = new ArrayList<>();
        FakeProcess process = new FakeProcess(" 1234\n", 0, false);

        long rss = StrategyProcessResourcesV1.processRssBytes(42L, false, requested -> {
            command.addAll(requested);
            return process;
        });

        assertThat(rss).isEqualTo(1_263_616L);
        assertThat(command).containsExactly("ps", "-o", "rss=", "-p", "42");
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.stderr.closed).isTrue();
        assertThat(process.stdin.closed).isTrue();
    }

    @Test
    void windowsProbeUsesNumericPidPowerShellCommandAndReturnsBytes() {
        List<String> command = new ArrayList<>();

        long rss = StrategyProcessResourcesV1.processRssBytes(31415L, true, requested -> {
            command.addAll(requested);
            return new FakeProcess("65536\r\n", 0, false);
        });

        assertThat(rss).isEqualTo(65_536L);
        assertThat(command).containsExactly(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Process -Id 31415).WorkingSet64");
    }

    @Test
    void invalidPidDoesNotLaunchACommand() {
        List<List<String>> commands = new ArrayList<>();

        assertThat(StrategyProcessResourcesV1.processRssBytes(0L, true, command -> {
            commands.add(command);
            return new FakeProcess("1", 0, false);
        })).isEqualTo(-1L);
        assertThat(commands).isEmpty();
        assertThat(StrategyProcessResourcesV1.processRssBytes(42L, true, null)).isEqualTo(-1L);
    }

    @Test
    void malformedOutputNonzeroExitAndOverflowFailClosed() {
        assertThat(probe(new FakeProcess("not-a-number", 0, false), false)).isEqualTo(-1L);
        assertThat(probe(new FakeProcess("  \r\n", 0, false), false)).isEqualTo(-1L);
        assertThat(probe(new FakeProcess("-1", 0, false), true)).isEqualTo(-1L);
        assertThat(probe(new FakeProcess("1", 1, false), false)).isEqualTo(-1L);
        assertThat(probe(new FakeProcess(Long.toString(Long.MAX_VALUE), 0, false), false)).isEqualTo(-1L);
        assertThat(StrategyProcessResourcesV1.processRssBytes(42L, false, command -> null)).isEqualTo(-1L);
    }

    @Test
    void timeoutDestroysTheProcessAndClosesItsStreams() {
        FakeProcess process = new FakeProcess("1", 0, true);

        assertThat(probe(process, false)).isEqualTo(-1L);
        assertThat(process.destroyed).isTrue();
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.stderr.closed).isTrue();
        assertThat(process.stdin.closed).isTrue();
    }

    @Test
    void launchAndStreamErrorsReturnUnknown() {
        assertThat(StrategyProcessResourcesV1.processRssBytes(42L, false,
                command -> { throw new IOException("synthetic launch failure"); }))
                .isEqualTo(-1L);
        assertThat(StrategyProcessResourcesV1.processRssBytes(42L, false,
                command -> new FakeProcess("1", 0, false) {
                    @Override
                    public InputStream getInputStream() {
                        return new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("synthetic stream failure");
                            }
                        };
                    }
                })).isEqualTo(-1L);
        assertThat(StrategyProcessResourcesV1.processRssBytes(42L, false,
                command -> new FakeProcess("1", 0, false) {
                    @Override
                    public InputStream getInputStream() {
                        return null;
                    }
                })).isEqualTo(-1L);
        assertThat(probe(new FakeProcess("1", 0, false) {
            @Override
            public InputStream getErrorStream() {
                return new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }

                    @Override
                    public void close() throws IOException {
                        throw new IOException("synthetic close failure");
                    }
                };
            }
        }, false)).isEqualTo(1_024L);
    }

    @Test
    void interruptionIsPreservedAndReturnsUnknown() {
        FakeProcess process = new FakeProcess("1", 0, false) {
            @Override
            public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("synthetic interruption");
            }
        };
        try {
            assertThat(probe(process, false)).isEqualTo(-1L);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void currentProcessRssIsAvailableOnTheTestHost() {
        assertThat(StrategyProcessResourcesV1.processRssBytes(ProcessHandle.current().pid()))
                .isGreaterThan(0L);
    }

    private static long probe(Process process, boolean windows) {
        return StrategyProcessResourcesV1.processRssBytes(42L, windows, command -> process);
    }

    private static class FakeProcess extends Process {
        private final TrackingInputStream stdout;
        private final TrackingInputStream stderr = new TrackingInputStream(new byte[0]);
        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final int exitCode;
        private final boolean timeout;
        private boolean destroyed;

        private FakeProcess(String output, int exitCode, boolean timeout) {
            this.stdout = new TrackingInputStream(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.exitCode = exitCode;
            this.timeout = timeout;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return !this.timeout;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        private static final class TrackingInputStream extends ByteArrayInputStream {
            private boolean closed;

            private TrackingInputStream(byte[] data) {
                super(data);
            }

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        private static final class TrackingOutputStream extends ByteArrayOutputStream {
            private boolean closed;

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }
    }
}
