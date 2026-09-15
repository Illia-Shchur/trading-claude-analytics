# Parallel/2 four-versus-six worker comparison — 2026-09-15

This bounded local engineering campaign compared four and six outer workers with the current Parallel GC winner. It ran on the available Apple M1 Pro Mac (16 GiB, macOS) with the pinned Java 21.0.11 executable, `Xmx1536MiB`, `-XX:ActiveProcessorCount=1`, `-XX:+UseParallelGC`, `-XX:ParallelGCThreads=2`, the same optimized immutable package, and the same eight development seeds. The fixed endpoint is complete eight-job worker makespan; strict validation, exact normalized economics and three curves, raw-byte conservation, and the frozen 7,200-trade / 16-book replay were required for a result to rank and are excluded from the endpoint.

The four complete stages ran in the predeclared reverse-order schedule `workers4-first`, `workers6-first`, `workers6-second`, `workers4-second`. Every stage passed the collector and thread identity checks, eight strict outputs, exact economics and curves, raw preservation, and independent replay. No qualification, reserved-300, strategy, trading, production-source, or Windows work was run.

| Outer workers | Observation 1 (s) | Observation 2 (s) | Mean (s) | Range (s) |
|---:|---:|---:|---:|---:|
| 4 | 1309.667103333 | 1358.241762041 | 1333.954432687 | 48.574658708 |
| 6 | 1295.495569375 | 1347.777483500 | 1321.636526438 | 52.281914125 |

The four-worker mean was 12.317906250 seconds (0.932% relative to the six-worker mean) slower. The predeclared engineering rule was `mean4 <= mean6 * 1.01`; it therefore selects four workers because the 4-worker mean is within the one-percent practical tie band. This supersedes the historical six-worker recommendation for this local Parallel/2 harness. This margin is an engineering choice for this local workload, not a significance test. The two observations per setting are reported with their ranges and are not treated as statistical significance.

The selected runnable local profile is [selected-local-runtime-profile.json](../strategy-research/v5-records/evidence/mac-gc-four-workers-20260915/selected-local-runtime-profile.json), content SHA-256 `4c410790387d550608452af1ada93135e9a9cce546a9351e78c615416c92907f`. It binds four outer workers, 1536 MiB per worker, `ActiveProcessorCount=1`, Parallel GC with two GC threads, Java 21.0.11, and the immutable executor manifest. Its validation receipt and all stage receipts are in the linked evidence directory.

The durable evidence is [mac-gc-four-workers-20260915](../strategy-research/v5-records/evidence/mac-gc-four-workers-20260915/). The raw campaign archive is `/Users/eternal/.codex/artifacts/trading-pr13-performance/gc-four-workers-20260915/raw-campaign-20260915.tar.zst` with SHA-256 `7a4028c91fec15568d2248e922050e508b7bb5932171f1ee5c3a036cb92fbbeb`; its custody receipt records streamed verification of all 1,328 regular members against the embedded byte/SHA manifest. The evidence manifest records the compact archive path, size, and SHA-256.

GC diagnostics remain descriptive activity totals: the Parallel stages detected only Parallel with two actual GC worker threads. The 4-worker stages reported aggregate pause totals of 570.168476 and 571.209976 seconds; the 6-worker stages reported 625.317692 and 626.929587 seconds. These are sums of worker diagnostics and are not added to makespan. This profile is for the optimized eight-development-seed Mac harness only; it does not change formal production or qualification settings. No Windows timing or compatibility result is available.
