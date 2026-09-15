# Java 21 GC comparison — 2026-09-15

This engineering campaign compared Java 21 garbage collectors for the fixed eight-job development workload on the available Apple M1 Pro Mac (16 GiB, macOS 15.7.7). It used the optimized immutable package and the same eight development seeds as the prior throughput campaign. Every timed stage held six outer workers, a 1536 MiB heap per JVM, `-XX:ActiveProcessorCount=1`, the pinned Java 21.0.11 executable, and the same package, workload, economics, curves, and frozen independent 7200-trade / 16-book replay. No qualification, reserved repetitions, strategy, trading, or production-source changes were made.

The primary endpoint is the complete eight-worker makespan. Validation and the independent replay are excluded from that endpoint but are required for a stage to rank. All successful rows below passed eight strict validations, exact normalized economics and three curves, raw-byte preservation, collector/thread identity checks, and the frozen independent replay. The first G1 attempt completed workers in 1335.412622417 seconds but its campaign-local receipt omitted two runtime metadata fields; the controller rejected it before replay. It remains an immutable failed, unranked diagnostic observation and consumed one of ten permitted launches. A fresh G1 retry was run before alternatives.

| Launch | Stage | Collector and flags | Worker makespan (s) | Result |
|---:|---|---|---:|---|
| 1 | `baseline-g1` | G1, `-XX:+UseG1GC` | 1335.412622417 | FAILED metadata contract; unranked |
| 2 | `baseline-g1-retry` | G1, `-XX:+UseG1GC` | 1341.242946542 | PASS |
| 3 | `serial` | Serial, `-XX:+UseSerialGC` | 1606.759183084 | PASS |
| 4 | `parallel-threads1` | Parallel, `-XX:+UseParallelGC -XX:ParallelGCThreads=1` | 1377.739203125 | PASS |
| 5 | `parallel-threads2` | Parallel, `-XX:+UseParallelGC -XX:ParallelGCThreads=2` | 1321.463385291 | PASS |
| 6 | `zgc-generational` | ZGC generational, `-XX:+UseZGC -XX:+ZGenerational` | 1499.110695209 | PASS |
| 7 | `repeat-best-alternative` | Parallel, `-XX:+UseParallelGC -XX:ParallelGCThreads=2` | 1291.364070959 | PASS |
| 8 | `repeat-g1` | G1, `-XX:+UseG1GC` | 1355.474582417 | PASS |

The two Parallel/2 observations average 1306.413728125 seconds. The two valid G1 observations average 1348.3587644795 seconds. The 41.9450363545-second mean advantage exceeds the larger within-setting two-run spread of 30.099314332 seconds, so the predeclared rule selects Parallel GC with two GC threads. The selected alternative is about 3.11% faster on these mean makespans. This is a bounded local engineering result; it is not a global optimum, strategy qualification, or a claim about other hosts.

The runnable selected local profile is [selected-local-runtime-profile.json](../strategy-research/v5-records/evidence/mac-gc-comparison-20260915/selected-local-runtime-profile.json), content SHA-256 `4ee96bc162fdda7250376836f5b8111b9601587e455b5c71d833f99d1b2a98e1`. It binds six outer workers, 1536 MiB, `ActiveProcessorCount=1`, Parallel GC with `ParallelGCThreads=2`, Java 21.0.11, and the frozen executor manifest. Its profile validation receipt is in the same evidence directory.

The compact, hash-manifested evidence is [mac-gc-comparison-20260915](../strategy-research/v5-records/evidence/mac-gc-comparison-20260915/). Byte-exact raw campaign custody is recorded in `campaign/archive-custody.json`; the verified external raw archive is `/Users/eternal/.codex/artifacts/trading-pr13-performance/gc-comparison-20260915/raw-campaign-20260915-clean.tar.zst` (SHA-256 `17829baf11e52915802baf5ac5c4f2be406bcf4f0bd692e5610374b7e805571f`, 3,434,897,368 bytes). The final compact archive path, size, and SHA-256 are recorded in the linked evidence manifest; it contains the corrected derived diagnostics and refreshed independent review receipt. The raw archive was streamed and every 2,633 regular members matched the embedded byte/SHA manifest; zstd checksum/decompression passed.

All collector configurations were available under the pinned runtime. Generational ZGC was accepted only with both young and old generation evidence. No Windows machine was available, so this campaign reports no Windows timing or compatibility result. The independent runner review and 23 passing harness tests are preserved in the evidence directory.
