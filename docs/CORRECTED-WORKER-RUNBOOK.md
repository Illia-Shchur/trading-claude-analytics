# Corrected worker development execution

> **Engineering verification complete — 2026-09-11.** See the
> [verification record](CORRECTED-WORKER-VERIFICATION-20260911.md) for the final
> source, package, test, coverage, PREFIX, and audit evidence. FULL remains
> blocked by the declared 28-CPU/32-GiB resource gate and is not qualified; no
> held-out confirmation, activation, or trading is authorized. The evidence
> archive is recorded in the verification record; CI is checked on PR13 head
> after push.

The corrected commands select `StrategyFixedBaselineCorrectedV1` and
`PORTFOLIO_ACCOUNTING_CORRECTION_V1`. The historical commands retain their frozen
V5 accounting. DEVELOPMENT execution does not authorize confirmation, activation,
or trading. A completed PREFIX is not qualification.

## Setup and verification

Use JDK 21 and the repository's Maven 3.9.11 wrapper. On Linux or macOS, run the
clean reactor from a native Unix filesystem; existing custody tests require Unix
hard-link metadata and symbolic links. On the Windows workstation, run it in
Ubuntu 24.04 WSL on an ext4 checkout. Keep unrelated files separate; clone into
a new directory and check out the desired reviewed commit. Do not copy ignored
caches from another host. macOS host identity is read from a bounded
`ioreg IOPlatformUUID` probe and fails closed when that identity is unavailable.

After the delivery branch is published, use a new directory (open
`wsl -d Ubuntu-24.04` from PowerShell first when running on Windows):

```sh
git clone --branch codex/corrected-worker-qualification \
  https://github.com/Illia-Shchur/trading-claude-analytics.git \
  "$HOME/trading-corrected-qualification"
cd "$HOME/trading-corrected-qualification"
git fetch origin
git status --short
git rev-parse HEAD
```

Record the exact commit printed above. Reproducing a retained run requires its
original packaged JAR and declared inputs, rather than rebuilding a different
commit and attaching its output to that run.

From the Linux or macOS checkout:

```sh
java -version
./mvnw --version
./mvnw --batch-mode --no-transfer-progress clean install
python3 tools/check_new_code_coverage.py \
  --base 966b2758ff9e5e499c63d301e8f46bbbe0c423f7 \
  --report analytics-coverage/target/site/jacoco-aggregate/jacoco.xml \
  --minimum 55 --branch-minimum 55
```

The active reviewed-diff policy is an unconditional 55% line and 55% branch
floor. PR #10's former migration exception is historical evidence only. The
runnable application is the `-exec.jar`;
the thin module JAR has no executable manifest.

Run the network-free Python regressions before packaging a fresh executable:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test/python -p 'test_*.py'
```

After the clean research build, measure mutation coverage against the corrected
evaluator and process-resource helper with focused tests. These commands apply
the acceptance floors of 80% mutation, 90% test strength, and 90% line coverage;
record the measured outcome from each focused run.

```sh
./mvnw --batch-mode --no-transfer-progress -pl analytics-research -Pmutation \
  -DtargetClasses=com.tradinganalytics.research.v5.StrategyFixedBaselineCorrectedV1 \
  -DtargetTests=com.tradinganalytics.research.v5.StrategyFixedBaselineCorrectedV1Test \
  -DmutationThreshold=80 -DtestStrengthThreshold=90 -DcoverageThreshold=90 \
  org.pitest:pitest-maven:mutationCoverage

./mvnw --batch-mode --no-transfer-progress -pl analytics-research -Pmutation \
  -DtargetClasses=com.tradinganalytics.research.v5.StrategyProcessResourcesV1 \
  -DtargetTests=com.tradinganalytics.research.v5.StrategyProcessResourcesV1Test \
  -DmutationThreshold=80 -DtestStrengthThreshold=90 -DcoverageThreshold=90 \
  org.pitest:pitest-maven:mutationCoverage
```

## Fresh declaration and physical inputs

Choose a new run directory for each executable and machine. Preserve it after
execution begins. The following commands run from the repository root:

```sh
RUN=.report-run/corrected-worker-$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$RUN"
cp analytics-cli/target/analytics-cli-1.0.0-SNAPSHOT-exec.jar "$RUN/executor.jar"
JAR="$RUN/executor.jar"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$JAR"
else
  shasum -a 256 "$JAR"
fi > "$RUN/executor.sha256"
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-profile \
  > "$RUN/profile.json"
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-plan \
  --base-plan strategy-research/v5-records/evidence/performance-20260908/parallel-confirmation-plan-v002.json \
  --profile "$RUN/profile.json" > "$RUN/plan.json"
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-preflight \
  --plan "$RUN/plan.json" --profile "$RUN/profile.json" --mode PREFIX \
  > "$RUN/preflight-prefix.json"
```

The builder validates the retained statistical declaration, captures the current
packaged executable/source identity and current supporting dependency bytes, and
creates disjoint DEVELOPMENT seed inventories. It does not modify the retained
plan. PREFIX uses the retained two-repetition, ten-episode diagnostic geometry.
Full-size DEVELOPMENT uses one development seed per admitted worker in each of
the four cells, for exactly `4 x effective_workers` slots per run. Every slot
uses the unchanged 450-event/450-control, 162 two-source physical clusters (288
paired statistical clusters), 900-series, 14,400-minute geometry. The statistical
plan's 75 repetitions per cell remain a distinct held-out confirmation declaration
and are not run by this development workflow.

Required tracked inputs are the baseline, controls, experiment, portfolio policy,
and lifecycle timing policy. The successor generator creates physical synthetic
DEVELOPMENT inputs using the repository's existing deterministic workflow. Worker
artifacts retain the input receipts and provenance. Historical Parquet directories
and historical executor JARs are not prerequisites for this synthetic workflow.
Real-market replay requires its own authoritative physical inputs and provenance;
do not substitute the DEVELOPMENT generator for those inputs.

## Bounded local serial and parallel runs

Use distinct ledgers for the two executions, with the same plan and executable:

```sh
for WORKERS in 1 2; do
  java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-run \
    --plan "$RUN/plan.json" --profile "$RUN/profile.json" --mode PREFIX \
    --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
    --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
    --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
    --workers "$WORKERS" --ledger "$RUN/prefix-$WORKERS/ledger.json" \
    --out "$RUN/prefix-$WORKERS/result.json" \
    --max-wall-millis 600000 --max-worker-wall-millis 120000 \
    > "$RUN/prefix-$WORKERS.stdout.json" 2> "$RUN/prefix-$WORKERS.stderr.log"
done
```

Two workers must be admitted by the actual profile. These optional time limits
reduce the existing ceilings. Inspect completion status, every worker artifact,
accounting invariants, and portable economic digests; exit status alone is not
proof of qualification. Keep the ledger, adjacent `.results`, `.logs`, and any
retained `.scratch` directories together. Resume with the exact same command,
plan, profile, executable, inputs, and ledger. Never edit a started declaration
to repair an identity mismatch; create a fresh declaration and run instead.

Tracked delivery evidence contains the machine inventory, reviewed declarations,
validation summaries, and exact hashes. Executable JARs, build products, and bulk
run directories belong in external or gitignored storage. Preserve their raw
bytes and relative layout for later reopening; a hash without the referenced
artifact is not complete qualification evidence.

## Hardware recovery requirement

The workstation's Windows probe reports 28 logical CPUs and 34,138,472,448 bytes
usable physical RAM. The unchanged minimum is 34,359,738,368 bytes, leaving a
221,265,920-byte shortfall. Closing applications changes free memory, not this
total. The installed WSL distribution exposes about 16.65 billion bytes total
RAM and independently fails the same check. No qualification receipt may be
issued from either environment.

Recovery requires a host with at least 28 available CPUs and 32 GiB usable RAM
in the execution environment, with sufficient physical backing. Provision more
RAM or use an eligible host; increasing swap or relabelling the resource profile
does not satisfy the contract. If using WSL, its memory limit must also admit the
required memory after the host is eligible. Recheck with the supported profile
command, alongside `getconf _NPROCESSORS_ONLN` and `free -b` on Linux. Generate a
new profile and plan on that host, bound to the newly packaged executable. Do
not copy a started plan or a qualification receipt from this machine.

The frozen ceilings remain 48 hours wall time, 26 GiB aggregate RSS, 128 GiB
managed disk, 2 GiB coordinator reservation, and 2 GiB heap / 3 GiB RSS per
worker. Admission is at most eight workers and 24 CPUs. Full-size DEVELOPMENT
must complete the `4 x effective_workers` wave across the four cells in distinct
serial and parallel runs, with development seeds disjoint from the held-out
confirmation seeds. A resource-only profile, a PREFIX, or a partial/resumed
measurement does not prove that qualification.

On an eligible host, first perform the fresh declaration steps above, then run
the following from the same repository root. These commands execute only the
new plan's DEVELOPMENT seeds, not its held-out confirmation inventory:

```sh
set -e
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-preflight \
  --plan "$RUN/plan.json" --profile "$RUN/profile.json" --mode FULL \
  > "$RUN/preflight-full.json"
python3 - "$RUN/preflight-full.json" <<'PY'
import json, sys
gate = json.load(open(sys.argv[1]))
assert gate['status'] == 'READY_PRE_OUTCOME', gate
PY
PARALLEL_WORKERS=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["effective_workers"])' "$RUN/profile.json")
for ROLE in serial parallel; do
  WORKERS=1
  if [ "$ROLE" = parallel ]; then WORKERS="$PARALLEL_WORKERS"; fi
  java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-run \
    --plan "$RUN/plan.json" --profile "$RUN/profile.json" --mode FULL \
    --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
    --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
    --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
    --workers "$WORKERS" --ledger "$RUN/full-$ROLE/ledger.json" \
    --out "$RUN/full-$ROLE/result.json" \
    > "$RUN/full-$ROLE.stdout.json" 2> "$RUN/full-$ROLE.stderr.log"
done
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-qualify \
  --plan "$RUN/plan.json" --profile "$RUN/profile.json" \
  --serial-result "$RUN/full-serial/result.json" --serial-ledger "$RUN/full-serial/ledger.json" \
  --parallel-result "$RUN/full-parallel/result.json" --parallel-ledger "$RUN/full-parallel/ledger.json" \
  --out "$RUN/qualification.json" > "$RUN/qualification.stdout.json"
python3 - "$RUN/qualification.json" "$RUN/qualified-profile.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1]))
assert receipt['status'] == 'QUALIFIED'
with open(sys.argv[2], 'w') as output:
    json.dump(receipt['profile'], output, indent=2)
    output.write('\n')
PY
java -jar "$JAR" strategy-research-v5 operating-characteristics-corrected-parallel-validate-qualification \
  --plan "$RUN/plan.json" --profile "$RUN/qualified-profile.json" \
  --serial-result "$RUN/full-serial/result.json" --serial-ledger "$RUN/full-serial/ledger.json" \
  --parallel-result "$RUN/full-parallel/result.json" --parallel-ledger "$RUN/full-parallel/ledger.json" \
  > "$RUN/qualification-validation.json"
```

The qualified profile retains the original development profile. Validation
reopens both runs and their referenced artifacts and measurements; it requires
the original executable and host. Qualification does not authorize running the
held-out confirmation seeds. Stop after receipt validation.

Resume is available for development diagnostics using the original command and
ledger. A qualification measurement must cover an entirely fresh worker wave;
reusing completed artifacts during resume cannot supply missing launch-time
resource evidence. If a run cannot meet that requirement, retain the interrupted
evidence and start a fresh declaration and run instead of editing its ledger.
