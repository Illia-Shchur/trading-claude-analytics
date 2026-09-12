# Mac FULL development execution — 2026-09-12

> **Engineering execution and independent reconciliation passed. Formal FULL
> qualification remains `BLOCKED_RESOURCE`.** This note records the eight
> frozen development seeds in serial and parallel corrected-worker waves. It
> contains no heldout result, qualification, activation, or trading claim.

## Result

The final package completed all eight frozen development seeds in both waves:
16 strict artifact validations passed, with eight unique seeds executed once in
each mode. For every seed, the serial and parallel corrected economic digests
matched. The independent replay audit reconciled all 16 artifacts, 32 books,
14,400 trades, and 2,938,950 book-curve plus 2,938,950 combined-curve points;
it confirmed matching metrics, digests, and all three curves between waves.

| Scenario | Effect | Seed | Serial worker seconds | Parallel worker seconds | Corrected economic digest (prefix) |
| --- | ---: | ---: | ---: | ---: | --- |
| `NO_EDGE` | 0.00 | 920000000 | 1015.245 | 1006.357 | `4512d8e024e1` |
| `NO_EDGE` | 0.00 | 920000001 | 1013.797 | 1006.957 | `24b088944f48` |
| `PLANTED_EDGE` | 0.00 | 920100000 | 1003.300 | 1022.338 | `db2c347a3892` |
| `PLANTED_EDGE` | 0.00 | 920100001 | 1016.460 | 1016.406 | `749fd67f1113` |
| `PLANTED_EDGE` | 0.02 | 920200000 | 1016.837 | 1018.492 | `2d79e91980d7` |
| `PLANTED_EDGE` | 0.02 | 920200001 | 1008.640 | 1023.906 | `ec10748cd9d3` |
| `PLANTED_EDGE` | 0.04 | 920300000 | 990.449 | 1029.673 | `2678545b3fe6` |
| `PLANTED_EDGE` | 0.04 | 920300001 | 1014.187 | 1022.852 | `ab89f25d597c` |

The worker times for parallel jobs overlap and should not be summed as wave
wall time. Serial worker time summed across eight slots was 8,078.914 seconds
(134.65 minutes). The runner's recorded progress total was 12,154.409 seconds;
subtracting the serial worker sum gives an approximate 4,075.495 seconds
(67.93 minutes) parallel-overhead estimate, not an independently recorded
parallel wall-time measurement. The harness's end-to-end engineering
validation elapsed time was 13,223.999 seconds (220.40 minutes).

Peak aggregate worker RSS was 2,527,232,000 bytes in the serial wave and
4,814,405,632 bytes in the parallel wave, within the configured
8,589,934,592-byte aggregate worker RSS limit. Maximum recorded run-disk use
was 9,284,192,648 bytes serial and 9,986,952,595 bytes parallel, below the
21,474,836,480-byte run limit. All source inputs, package files, baseline,
control, and experiment bindings had identical before/after hashes.

Each worker result recorded 450 source events and 288 independent paired
statistical units. The latter is the paired-cluster count; the geometry's 162
double-source clusters and 126 singleton clusters describe physical source
geometry and are not the paired statistical count.

## Package and source bindings

The execution used source revision `d0d34d0df5b8814c640619756d8d7e51d2717e84`
with `dirty_source=false`, OpenJDK 21.0.11, and one immutable package manifest.
The four bound package identities were:

| Binding | SHA-256 |
| --- | --- |
| Executor JAR (107,286,790 bytes) | `b78f6b0906b8548ba1ccebe1e69572b2fcc29fac3a438bde017cbf1ea4ad6827` |
| Executor source fingerprint | `f7e7c28e7d4177da5cfa5cb6675b9b374c85faba598e78f28884533f7dbaf05c` |
| Plan content | `60c9904b3b103ab1ea1069c1faa8129e75879e4bb033f9fa7f7042abfd6ecd75` |
| Execution profile content | `87a3112be2f617793dc40e4cb9e228c51f355c0bd4dc6c84833e66f33c2dd6ae` |
| Plan file bytes | `d13ca86fc8a17c5b5f6e8b6d0cb80835203e53be17922b5d6901aecdfb85756d` |
| Profile file bytes | `636c4ffa98046e010191866e0d813b15f6bb4c1587d3fe158720e964dc555390` |

Integrity also bound the baseline definition to
`4ab073d4b62c77410cca2456066fbae70b93b19ebf93d63922204e1b3548fc1a`, controls
to `8170e98fcf90c30aea67a4f2a4503d85817f158377dd449c5a3ed9b0b0793216`, and
experiment to `805d34f09c7d94cbf9be3efdf5277f384583dbf1646feab2140c443083aeed1f`.
The final package manifest, build identity, profile, plan, run declarations,
strict validations, resource measurements, independent audit receipt, and
reproduction scripts are retained in
[`strategy-research/v5-records/evidence/mac-full-development-20260912/`](../strategy-research/v5-records/evidence/mac-full-development-20260912/).
The full durable archive is the owner-local, ignored file
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.tar.gz`
(827,211,640 bytes; SHA-256
`e5236c2993cf83029426746e36ce269597c67ede0a40aa21e1f5bd64f89c56ac`). Its
compact receipt is retained at
[`archive-receipt.json`](../strategy-research/v5-records/evidence/mac-full-development-20260912/archive-receipt.json).
The receipt verifies all 1,115 archive members, including 17 worker results
and the 107,286,790-byte final JAR. The sidecar inventory is 477,087 bytes
with SHA-256 `29ca906b8cb206a6217d8c6ccc210052a89c5b05325c0297c70b3fb4cbeefa6c`;
it is not duplicated in the tracked evidence tree.

## Failure, repair, and validation history

The first two-worker FULL attempt completed zero slots. It ran out of memory
at seed `920000001` after 731.38 seconds with a 2 GiB worker heap; six slots
were cancelled. Source repair `4697034` added streaming hashing and reduced
retained copies. The frozen V5 producer/evaluator and original correction
implementation were preserved. The final test-only revision is
`d0d34d0df5b8814c640619756d8d7e51d2717e84`.

The clean reactor completed 1,537 tests with zero failures and one skipped test
in 12 minutes 13 seconds; 27 Python checks passed. Focused validation recorded
80 initial checks and 24 corrected checks. PIT and coverage evidence retained
from the validation run is:

- PIT JSON hashing: 88% mutation and 94% strength.
- Corrected evaluator: 224/275 mutations; 400/442 executable lines covered;
  90% line coverage and 97% test strength.
- Resource code: 91% mutation.
- New production-code coverage against reviewed base
  `966b2758ff9e5e499c63d301e8f46bbbe0c423f7`: 85.57% line and 59.48% branch,
  against configured 55% line and 55% branch floors. These are the changed-code
  coverage-gate figures, not whole-reactor coverage.

An initial independent replay attempt was superseded after its identity check
incorrectly treated per-slot `cwd` and `user_dir` values as package identity.
The auditor corrected that path comparison and reran all 16 artifacts. The
final audit passed; each `cwd` and `user_dir` was checked against its own
slot repository path, while the package and compiled identity fields
matched exactly across the replay. The passing audit files and scripts are
included in the compact evidence tree. The initial failed-wave archive remains
as the owner-local, ignored file
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-failure-20260911.tar.gz`.

## Why this was not a 300-run calibration

The frozen geometry has four cells across two scenario families: the
`NO_EDGE` null, a `PLANTED_EDGE` zero-effect diagnostic, and `PLANTED_EDGE`
alternatives at effects 0.02 and 0.04. The 75-replication Wilson criteria were
precommitted in the
[evaluation record](RESEARCH-EVALUATION-20260907.md#historical-v003-durable-precommit-and-v002-implementation-validation)
before outcomes: for `NO_EDGE`, at most 2 successes out of 75 gives a Wilson
95% upper bound of 9.21% (3 gives 11.11%); for a positive-effect cell, at least
67/75 successes gives a Wilson 95% lower bound of 80.34% (66 gives 78.74%).
The zero-effect `PLANTED_EDGE` diagnostic has no acceptance target. The joint
decision remains the intersection of all four precommitted component cutoffs.
Component intervals are pointwise, not multiplicity-adjusted acceptance
claims.
Thus the 300-run plan is 75 observations in each of four cells, not a claim
that this eight-seed development execution is statistically calibrated. Two
replications per cell cannot estimate calibration reliably. The 300-run
extension is not needed to verify the engineering repair and cannot establish
real-market profitability.

## Formal FULL status

The production FULL preflight remains `BLOCKED_RESOURCE`: this Mac reported 10
available CPUs and 16 GiB memory, below the 28-CPU and 32-GiB FULL resource
target. The development harness directly exercised the corrected worker for
the selected 920M seeds because the production coordinator gate correctly
blocks this host. No heldout outcomes were opened, no qualification or
confirmation command was run, and no activation or trading authorization was
granted. The execution artifact reports `performance_claim=NONE`.

## Reproduction and retained archive

The evidence tree includes the development harness, strict Java artifact
validator, and final independent audit scripts. Reproduction requires the full
source repository at the pinned revision, JDK 21, and the preserved package
directory containing `executor.jar`, `plan.json`, `profile.json`, and
`manifest.json`. With those inputs available, run from the evidence directory:

```sh
python3 scripts/run_mac_full_development.py \
  --repo /path/to/repository-at-d0d34d0 \
  --run /path/to/fresh-ignored-run-directory \
  --package /path/to/preserved-final-package \
  --manifest /path/to/preserved-final-package/manifest.json \
  --java /path/to/jdk-21/bin/java \
  --wave both
```

The harness requires a fresh run directory and validates the manifest's four
immutable bindings before execution. This creates a new run: its workers record
that run's own repository paths in `cwd` and `user_dir`. It should be run only
for this bounded engineering diagnostic; it is not a qualification or
confirmation workflow.

After the harness exits with its final PASS manifest, independently audit all
16 worker results with the retained post-run wrapper (substitute the original
harness PID, which must already have exited):

```sh
python3 scripts/post-run-audit.py \
  --root /private/tmp/trading-pr13/.report-run/mac-full-repaired/full-paired-waves \
  --audit scripts/audit-full.py \
  --harness-pid 35262 \
  --completed
```

The command above re-audits the archived run at its original path; PID `35262`
is the completed original harness PID recorded in the audit receipt. The
archive uses a curated prefix, so restore these directories before replay:

| Archive directory | Required restored directory |
| --- | --- |
| `mac-full-development-20260912/paired-waves/` | `/private/tmp/trading-pr13/.report-run/mac-full-repaired/full-paired-waves/` |
| `mac-full-development-20260912/final-package/` | `/private/tmp/trading-pr13/.report-run/mac-full-repaired/final-package/` |

Do not extract the archive at the repository root and run the audit below the
extra curated prefix. The worker artifacts record `cwd` and `user_dir` for
each slot's own repository path. A new harness execution instead uses the fresh
directory passed to `--run`, and its audit root must be that same run directory.
In both cases the audit requires the run-specific path identity and the exact
packaged/compiled executor identity; do not weaken those checks.

Full durable archive (owner-local and ignored):
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.tar.gz`
(827,211,640 bytes; SHA-256
`e5236c2993cf83029426746e36ce269597c67ede0a40aa21e1f5bd64f89c56ac`).
