# Mac corrected-worker throughput study — 2026-09-13

This study measures local batch throughput for the corrected FULL-development worker on the 16 GiB, 10-CPU Mac with JDK 21.0.11. It is an engineering-only run of the fixed eight development seeds, using 450 event/control pairs and 900 trades per artifact. It did not execute held-out seeds, qualify a strategy, or change production Java or profile behavior.

## Result status

The original 4×1024 MiB batch, the 6×1024 MiB batch, and a fresh 4×1024 MiB repeat all completed eight jobs and passed strict validation and independent replay. The fresh repeat took 2,070.140 seconds and passed clock-integrity checks. The repeat confirms that the 6-worker result is inside the observed 4-worker timing range.

| Stage | Workers | Heap | Worker batch time | Jobs complete | Validation | Reading |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| `stage-4w-1024` | 4 | 1024 MiB | 2,094.071 s | 8/8 | PASS | Validated reference. |
| `stage-6w-1024` | 6 | 1024 MiB | 2,087.468 s | 8/8 | PASS | Inside the observed 4-worker time range; no material six-worker speedup. |
| `stage-4w-1024-repeat-1` | 4 | 1024 MiB | 2,070.140 s | 8/8 | PASS | Fresh matched comparison; strict validation, independent audit, and clock integrity passed. |
| `stage-8w-1024` | 8 | 1024 MiB | ≥2,515.780 s | 0/8 | Censored | Lower bound only; it crossed the 1.20× reference censor. |
| `stage-8w-896` | 8 | 896 MiB | ≥2,518.288 s | 6/8 | Censored | Lower bound only; two 0.04 planted-edge jobs remained unfinished. |

The validated 4-worker timings span 2,070.140–2,094.071 seconds; the validated 6-worker batch took 2,087.468 seconds, inside that range. The measurements do not resolve a material throughput difference. Record 6×1024 MiB as the local harness recommendation because it is the highest tested concurrency without a material observed batch penalty and the user prefers more workers. Keep 4×1024 MiB as an equally fast alternative. One six-worker observation and two four-worker observations do not establish statistical superiority. RAM pressure and Mac usability are not tie-breaks.

## Heap and garbage-collection observations

The 1 GiB heap was the lowest tested heap with a fully validated eight-job batch; this is not an absolute minimum claim. Both 8-worker attempts were censored. The 8×1024 MiB run completed no jobs by its 1.20× censor. At 896 MiB, six jobs completed and two planted-edge 0.04 jobs were unfinished when censored. The lower heap coincided with a GC cliff, so the 8-worker heap reduction stopped there.

| Stage | Full GCs | Aggregate worker GC pause fraction | Batch outcome |
| --- | ---: | ---: | --- |
| 4 workers × 1024 MiB, initial | 28 | 16.40% | Validated complete |
| 6 workers × 1024 MiB | 23 | 16.41% | Validated complete |
| 4 workers × 1024 MiB, repeat | 27 | 16.46% | Validated complete |
| 8 workers × 1024 MiB | 0 | 23.31% | Censored, 0/8 jobs |
| 8 workers × 896 MiB | 1,579 | 32.30% | Censored, 6/8 jobs |

The fraction is summed worker GC pause time divided by summed worker lifetime. It is a diagnostic, not a standalone objective or failure gate: 8×1024 had no Full GC but was slower and censored; 8×896 showed many Full GCs and a higher pause fraction. The two censored 896 MiB jobs recorded 574 and 552 Full GCs with per-worker pause fractions of 45.28% and 43.19%.

These GC figures explain why the next stage moved to 6×1024 MiB; they are diagnostics, not correctness failures or automatic stop conditions. RSS is observational. Memory compression, swap growth, and pressure are also diagnostics under the user's authorized test conditions. The original 4×1024 MiB GC summary missed padded HotSpot tags; its corrected GC values come from `gc-diagnostic-correction.json`. Original stage receipts remain byte-exact and are retained separately from the supplement.

## Reproduction and evidence

The stages used the same frozen package and fixed eight-seed declaration. The package manifest SHA-256 is `f9a87031a5442a07e25e913cdcfa8d20380e92399438aa33febf612e2c9f104f`; the executable JAR SHA-256 is `b8a34877f8d674c4c16d3e121759f9c7c3a62b0a563226fbe03c02b91b27e6d2`; source fingerprint `ca94e33f2a56b0a9a3b3f29005a3d1e722e2f5efdf044de2876fe9289858b376`. Worker launches fixed `-XX:ActiveProcessorCount=1` and `-XX:+UseG1GC`; the 4/6/8-worker 1024 MiB stages used `-Xmx1024m`, and the 8-worker heap-reduction stage used `-Xmx896m`. The local harness recommendation is recorded here and does not change the production execution profile or resource gate.

The compact evidence package is extracted under `strategy-research/v5-records/evidence/mac-throughput-20260913/`; use its `evidence-manifest.json` and `derived-stage-summary.json` to verify the included receipts and stage comparison. Byte-exact raw stages and the frozen package are in the separately archived raw evidence at `/Users/eternal/.codex/artifacts/trading-pr13-performance/throughput-search-20260913/`, with custody recorded in `archive-custody.json`. This is a selected tested configuration within the bounded grid; it is not a global or absolute minimum and is not a strategy qualification.

For a future local engineering rerun, use the persistent raw harness or extract
the harness sources from the compact evidence package; they are measurement
tools, not a production automatic default. The selected 6-worker launch uses
`-Xmx1024m -XX:ActiveProcessorCount=1 -XX:+UseG1GC`. Keep the Mac on AC power,
use `caffeinate` during timing, and require the recorded wall-clock integrity
check to pass. The study does not recommend changing the production worker
profile or resource gate.
