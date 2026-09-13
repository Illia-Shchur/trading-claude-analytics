# Mac FULL-batch throughput evidence

> Final comparison outline for the compact evidence archive. The fresh 4×1024 batch has passed strict validation and independent replay.

## Scope and method

- Eight fixed FULL development seeds; 450 event/control pairs and 900 trades per artifact.
- Engineering throughput measurements only. No held-out seeds or formal qualification.
- One variable at a time: outer worker count and actual JVM heap; fixed JDK 21, G1, and `ActiveProcessorCount=1`.
- Whole eight-job batch wall time is the throughput measure. RSS, memory compression, swap, pressure, and GC remain diagnostics.

## Evidence status

| Configuration | Batch result | Validation | Interpretation |
| --- | --- | --- | --- |
| 4 workers × 1024 MiB | Complete | PASS | Existing validated reference stage; use the GC correction supplement for GC totals. |
| 8 workers × 1024 MiB | Censored, 0/8 complete | Not validated | Slower-than-reference lower bound only. |
| 8 workers × 896 MiB | Censored, 6/8 complete | Not validated | Lower bound only; GC cliff is diagnostic, not an automatic failure. |
| 6 workers × 1024 MiB | Complete, 2,087.468 s | PASS | Close to the validated 4-worker batch; no material six-worker speedup established. |
| Fresh 4 workers × 1024 MiB | Complete, 2,070.140 s; clock PASS | PASS | Matched repeat; strict validation, independent replay, and clock integrity passed. |

Replace this table from `derived-stage-summary.json` when packaging. Do not describe censored runs as completed makespans, validated artifacts, or candidates eligible to become best.

## Interpreting a near tie

The original 4×1024 batch took 2,094.071 seconds; 6×1024 took 2,087.468 seconds, a 0.315% observed difference. The fresh 4×1024 repeat took 2,070.140 seconds and passed strict validation and independent audit. The two 4-worker times bracket the 6-worker time, so the measurements do not resolve a material throughput difference. Report 6×1024 as the highest tested concurrency without a material observed batch penalty, reflecting the user's preference for more workers; retain 4×1024 as an equally fast alternative. Do not claim six is materially faster, use RAM or Mac usability as a tie-break, or repeat six only to rank a sub-percent gap.

If the matched results are reconsidered, report complete validated batch times and the matched confirmation rule from `PLAN.md`. A slowdown greater than 5% or a censored matched comparison requires fresh matched incumbent/candidate runs before confirming a loss or stopping a direction. Report the selected tested configuration with alternatives; do not imply it is fastest or claim a global minimum.

## Corrected GC reporting

For 4×1024, cite `stages/stage-4w-1024/gc-diagnostic-correction.json` for pause counts, Full GC counts, pause duration, and pause fraction. The initial parser missed padded HotSpot logger tags. Original stage receipts are preserved byte-for-byte; their original 4-worker GC fields are superseded source data and are not the corrected diagnostics.

## Package integrity

Describe the bundle SHA-256 and size from the packaging command output. `evidence-manifest.json` lists each included file's source path, size, and SHA-256. Raw artifacts, executable JARs, logs, payloads, scratch directories, and the recovered archive remain in the separate raw evidence archive.
