# Performance development evidence — 2026-09-08

This retention archive contains engineering measurements, not strategy promotion
or confirmation evidence. The parent evidence directory's `.retention-archive`
marker keeps these records outside the authoritative strategy index. None of the
300 held-out seeds in statistical plan v004 was opened.

## Scope and reproduction

The isolated build starts at Git revision `30262ac0f910bbec6471dbbd3132a476da04866f`
and overlays the session-owned paths listed in `isolated-build-snapshot-final.json`.
Unrelated concurrent changes in the main working directory were excluded. The
snapshot hashes bind build inputs as they stood before final documentation edits;
the independently computed build fingerprint binds actual packaged inputs.

The declared target is 28 cores / 32 GiB. This connected host reports 10 logical
CPUs / 16 GiB. The new ceiling is 48 hours / 26 GiB aggregate RSS / 128 GiB managed
disk, with at most eight workers, two active JVM processors and a 2-GiB heap per
worker, a 3-GiB worker RSS ceiling, a 2-GiB coordinator reservation and 6 GiB for the
OS. Local admission limits this host to two workers. A profile is not a measurement.

The full-size probe used development seed 202609080002, 450 event/control pairs,
288 clusters, 900 series and 14,400 minutes per series. Its predeclared plan,
STARTED receipt, external RSS/wall measurement and disk samples are retained.
The API evaluation completed, but portfolio integrity failed on the preexisting
exit-boundary stale-mark defect described in the dated performance review.
It cannot qualify a confirmation run.

`full-geometry-row.json.gz` and `full-geometry-producer-audit.json.gz` preserve the
original files byte for byte on decompression. `compressed-raw-receipts.json`
records compressed and original byte hashes. The smaller probe summary is a
parent-derived summary, with its own hash and an explicit original producer hash.
Do not replace an original producer hash with a summary hash.

The initial eager/bounded equivalence used development seed 202609080001 and the
v001 packaged checkpoint. `parent-parity-check.json` independently compares full
metrics, selections, attempts and portfolio. The gzip row receipts permit exact
replay inspection. The JFR summary uses already-exposed seeds 910000000/910000001;
profiling overhead makes it unsuitable as a controlled timing baseline.

The harness sources and shell scripts are retained as audit tooling. Their paths
refer to the original workspace: restore them beneath
`.report-run/performance-20260908/`, or update the explicit workspace/output paths
before replay. Shell scripts accept the research module JAR, executable JAR and
output directory in that order. Extract the research module and all dependencies
from the same executable; do not mix a newer module with an older executable.
The full-size harness is development-only and must retain its disjoint seed and
predeclared limits. Repeating it is not required to inspect the retained output.

Packaged benchmark configurations record exact argv, JVM flags, working directory,
slot plan and worker count. The independent observer samples the coordinator's
process tree every 0.5 seconds; sampled peaks may miss short excursions. A single
one-worker/two-worker timing pair does not establish stable target throughput.
Use separate fresh ledger directories for independent development replays. Resume
must verify existing artifacts and must not add another statistical observation.

Large executable JARs are retained locally in the existing gitignored
`fixed-baseline-full-declared-v004/executors/` directory. Their byte identities
are recorded; binaries are not included in the Git commit. Archived raw results
remain readable without those binaries. Reproduction from source requires the
recorded Maven wrapper/toolchain inputs and exact isolated source snapshot.

## Boundaries

Increasing the execution budget changes no hypothesis, seed, effect, cluster,
confidence bound, multiplicity rule, stop, position size or promotion criterion.
The full preflight remains blocked. Target hardware qualification and an
independently corrected/versioned portfolio evaluator are still prerequisites.
The performance plan and review in `docs/` explain accepted simplifications,
verification results and the accounting defect in detail.


## Accepted final checkpoint

The final executable is `analytics-performance-20260908-final-v005.jar`, SHA-256
`8c6e73508022f78b35bcbe3d59acb1e5e38707b48cf9a0c542ddf97133a4a549`.
Accepted execution plan: `parallel-confirmation-plan-v002.json`; its preflight
remains blocked. The v001 declaration is retained as a draft with a missing
redundant source-identity schema field, not accepted execution evidence.
`executor_source_sha256` in the final plan denotes the compiled input fingerprint,
matching `executor_build_input_fingerprint` and qualification receipt semantics.

The final benchmark pair took 34.376/19.058 seconds with one/two workers.
`serial-parallel-independent-audit.json` records exact normalized raw equivalence
and the new portable digest, which additionally excludes two absolute policy-file
paths while retaining their content hashes. The historical economic digest remains
unchanged and can differ across relocated workers. The parent normalization source
is retained. `packaged-resume-audit.json` records successful idempotent resume and
rejection of corrupted/missing artifacts. `bounded-parity-final/` independently
repeats eager/bounded equivalence with the final packaged research module.

Validation: full Java reactor plus final affected reruns account for 1,037 tests,
zero failures/errors and two explained skips; seven Python tests passed. Twelve
accepted contracts and own hashes validated. Earlier profiling and pre-portable
checkpoint observations remain historical development evidence. No final runtime
measurement is claimed for eight workers or for all 300 confirmation jobs.
