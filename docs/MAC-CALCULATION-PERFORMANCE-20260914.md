# Calculation performance and Mac worker profile — 2026-09-14

The calculation path now writes canonical UTF-8 directly for JSON string, boolean, and null scalars. Structured values and numeric formatting still use the pinned RFC 8785 implementation. This keeps the optimization within a small, differentially tested surface while preserving the existing hash and artifact contracts.

The pre-change JFR runs repeatedly placed hashing and canonicalization at about 92% of inclusive execution samples. Those stack-category shares are not exclusive wall-time phases or removable work, so they do not predict a 92% speedup. The independent review checked JFR metadata, sample and I/O event counts, arithmetic, data-loss status, and recording coverage; it did not independently reaggregate every stack. Bar parsing, duplicate serialized portfolio data, and correction/curve allocation remain unchanged because their redesigns had higher compatibility cost or lower measured opportunity.

## Measured code effect

The fresh pre-change and new-code runs used the same Mac, JDK 21.0.11, 4 workers × 1536 MiB, G1, one active processor per worker, the same eight development seeds, and identical task geometry. The old package took 1921.886 seconds and the new-code anchor took 1339.543 seconds, a 582.343-second (30.30%) lower worker-batch time. The old package's manifest and JAR SHA-256 values are `f9a87031a5442a07e25e913cdcfa8d20380e92399438aa33febf612e2c9f104f` and `b8a34877f8d674c4c16d3e121759f9c7c3a62b0a563226fbe03c02b91b27e6d2`; the new package is bound to manifest `9aaf5ce6e33d5de1147c4708060a71d04c957878a5cb23f32dccb054347c169b`. The old plan and new plan differ only in five package identity hashes. The old package was built from `4d8ec899`; no production Java source changed between that commit and pre-change HEAD `33065e1`.

Both batches passed all eight strict validations, matched the frozen economic reference and three curves, preserved raw bytes, and passed independent replay of 7200 trades across 16 books. The new-code run happened before the old-code reference, and there was one fresh batch per executable. Treat the observed difference as descriptive local evidence, not a randomized or statistically established causal estimate. The earlier 2026-09-13 old-code timing remains in the archived historical record and is not the primary code-effect reference.

The improved Java code has 100.00% changed-line coverage (104/104) and 98.78% changed-branch coverage (81/82), above the required 80%/80% floor. The tests compare direct scalar output with the pinned JCS implementation across all non-surrogate BMP code units, supplementary Unicode boundaries and pairs, malformed surrogate forms, both string and text-node entry points, and the existing contract vectors. The independent source review approved the implementation subject to those checks; package and controller review also approved the frozen identities and runtime binding checks.

## Bounded worker search

Worker times below measure the complete eight-job batch; separate strict validation and replay time is excluded. Every `PASS` is backed by eight strict validations, exact economic hashes and metrics/curve digests, independent replay, raw-byte preservation, valid clock timing, and frozen package/profile/tool bindings.

| New-code stage | Workers × heap | Worker batch | Result |
| --- | ---: | ---: | --- |
| `search-4w-1536` | 4 × 1536 MiB | 1339.543 s | PASS; code-effect anchor |
| `search-4w-1280` | 4 × 1280 MiB | 1375.787 s | PASS |
| `search-6w-1280` | 6 × 1280 MiB | 1365.548 s | PASS |
| `search-6w-1024` | 6 × 1024 MiB | 1383.623 s | PASS |
| `search-8w-1280` | 8 × 1280 MiB | ≥1609.151 s | CENSORED; 1/8 complete, seven unfinished |
| `refine-repeat-4w-1536` | 4 × 1536 MiB | 1329.568 s | PASS |
| `refine-6w-1536` | 6 × 1536 MiB | 1341.405 s | PASS |
| `refine-repeat-6w-1536` | 6 × 1536 MiB | 1341.491 s | PASS |
| `confirm-6w-1536-a` | 6 × 1536 MiB | 1345.565 s | PASS |
| `confirm-6w-1536-b` | 6 × 1536 MiB | 1339.463 s | PASS |

The 8-worker stage crossed the predeclared 1607.452-second censor. Its elapsed value is a lower bound, not a completed timing or a candidate. The two 4-worker × 1536 MiB runs averaged 1334.555 seconds and spanned 9.975 seconds. The four 6-worker × 1536 MiB runs averaged 1341.981 seconds and spanned 6.102 seconds. The 4-worker runs ranged from 1329.568 to 1339.543 seconds, and the 6-worker runs ranged from 1339.463 to 1345.565 seconds; the ranges overlap by 0.080 seconds. The 6-worker mean is 7.426 seconds (0.56%) slower, a difference smaller than the observed 4-worker run spread. Under the plan's tie-break for practically indistinguishable mean batch times at this measurement scale, the selected local setting is 6 workers × 1536 MiB. Six workers were not measurably faster.

## Selected local Mac engineering profile

The runnable, self-hashed profile is [selected-local-runtime-profile.json](../strategy-research/v5-records/evidence/mac-throughput-20260914/selected-local-runtime-profile.json), with content SHA-256 `397e189b69ae26a63a4c08f907410cba19de52a0399c3a75411135e0272d0c95`. It binds to the frozen executor manifest `9aaf5ce6e33d5de1147c4708060a71d04c957878a5cb23f32dccb054347c169b` and records six outer workers, `-Xmx1536m` per worker, `-XX:ActiveProcessorCount=1`, and G1. The harness accepted the profile through its runtime-profile validator.

Use this profile only with the standalone eight-seed Mac engineering harness and its matching frozen package. Keep the machine on AC power and use `caffeinate -is` during timing. The 8-worker / 1280 MiB attempt was censored; effective concurrency remains bounded by the eight jobs. Across the selected 6-worker runs, observed aggregate worker RSS peaked at 10.24 GB (9.54 GiB); RSS, GC pauses, swap, and pressure are observations, not hard limits or acceptance gates.

This local engineering profile does not replace the frozen production or qualification profile, alter the FULL resource gate, or qualify the 16 GiB Mac. The package's FULL preflight remains `BLOCKED_RESOURCE`; no held-out seeds, formal qualification, strategy parameter changes, or trading runs occurred. The repeated batches use the same eight development seeds and are not independent research samples.

The Java change uses platform-neutral encoding logic. The build and throughput campaign ran on macOS with JDK 21; repository CI runs on Ubuntu/JDK 21. The harness, profile, and performance were not validated on Windows, including the i7-14700K / 32 GiB workstation.

The new source commit is `6d5e02dd553a582d2f4052e8d6010d56587f96e9`; the frozen executable JAR SHA-256 is `523a88ef061761479d78f4d2791fc52a1e58ee7e276c6a27890e83154dcfca22`. The evidence folder contains compact stage receipts and checksummed archive custody for the full campaign snapshot and the fresh Phase 5 reference supplement.
