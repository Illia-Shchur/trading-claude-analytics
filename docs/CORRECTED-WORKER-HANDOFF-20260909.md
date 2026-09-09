# Corrected worker handoff — unfinished draft

The owner stopped work and requested a PR and continuation prompt on 2026-09-09.
This branch is **not ready to merge and not QUALIFIED**. No held-out confirmation,
activation, or live trading was performed. The final edits were captured without
another clean build or completed independent review.

## Prompt for the next agent

Continue and complete corrected-worker integration and bounded DEVELOPMENT
qualification in this PR for Illia-Shchur/trading-claude-analytics. Read AGENTS.md,
`.agents/skills/strategy-research/SKILL.md`, this handoff, and:

- `docs/PORTFOLIO-ACCOUNTING-CORRECTION.md`
- `docs/RESEARCH-OPERATING-GUIDE.md`
- `docs/RESEARCH-PERFORMANCE-PLAN-20260908.md`
- `docs/RESEARCH-PERFORMANCE-REVIEW-20260908.md`
- `docs/CORRECTED-WORKER-PLAN-20260909.md`
- `docs/CORRECTED-WORKER-RUNBOOK.md`

Fetch origin, inspect this PR's latest commit and working tree, and continue its
`codex/corrected-worker-qualification` branch. Preserve unrelated changes. The
original reviewed merge base is `966b2758ff9e5e499c63d301e8f46bbbe0c423f7`.
Planning and independent review must use gpt-6-astra medium; implementation must
be delegated explicitly to gpt-5.6-luna xhigh. Do not silently substitute models.

Finish the separately versioned corrected evaluator, successor, parallel worker,
coordinator, CLI, schemas, and qualification validation. Preserve frozen V5 and
the existing correction implementation, historical outputs, plans, and receipts.
Correct both event and control books and every dependent equity, holdings,
return, and drawdown metric. Reject unsupported contracts explicitly: the
correction supports long spot with a single full exit. Bind results to evaluator,
algorithm, accounting version, executable, source inputs, profile, and host;
prevent fallback to frozen accounting.

First inspect the last review finding and its unreviewed fix: FULL qualification
previously demanded four raw fields that the actual V5 producer never emits.
The latest fix validates actual 450 event and 450 control trades, complete paired
attempts, 900 lifecycle trades, and actual 288/162 cluster metrics, with the real
synthetic input descriptor bound to the slot. Independently verify this against
production-generated output, not fixtures with invented fields. Verify the
PREFIX artifact test seam invokes strict production validation, and that test
fixtures use real per-slot descriptors and complete books. The latest successor
schema/evaluator/accounting validation edits also need final verification.

Complete deterministic, self-contained regression/integration tests with
independently calculated expectations: timestamp equivalence, EXIT/ENTRY/MARK
ordering, boundary marks, overlaps, empty books, terminal closure, invalid inputs,
event/control aggregates, identity/tamper rejection, and serial/parallel economic
equivalence. Closed books must have zero active and marked holdings; reconcile
ending equity to cash and initial capital plus net P&L. Audit trusted package-only
test seams against strict production custody behavior. Never weaken safeguards
to make Windows tests pass.

Use JDK 21 and the pinned Maven wrapper. Run the final clean reactor and the
changed-production-code gate against an explicit reviewed merge base with **80%
line and 80% branch floors**. PR #10's 55% branch exception does not apply. Finish
applicable mutation checks. Independently review final code, accounting
propagation, evidence bindings, and portability, and fix findings. Update the
runbook with verified setup, physical input preparation, qualification, resume,
and validation commands, identifying tracked versus external artifacts.

Reinspect actual CPU, usable RAM, disk, and available inputs. Do not assume that
freeing RAM changes usable physical RAM or that ignored historical inputs/JARs
exist. Keep the exact current qualification contract: at least 28 CPUs and 32 GiB
usable RAM; 48-hour wall, 26 GiB aggregate RSS, 128 GiB managed disk; coordinator
reservation 2 GiB; each worker 2 GiB heap, 3 GiB RSS, 2 CPUs; at most 8 workers and
24 worker CPUs. Verify these numbers in current code/docs before execution.
Use full DEVELOPMENT geometry and required wave size, with development seeds
disjoint from held-out seeds. Do not run the 75-rep held-out confirmation.

Prepare real physical inputs through supported repository workflows. The
successor's supported synthetic DEVELOPMENT generator creates physical inputs;
do not invent replacement qualification data. Bind a fresh declaration/profile
and immutable plan to the newly packaged executable. Run required serial and
parallel corrected DEVELOPMENT execution only when admitted by the contract;
measure runtime, aggregate RSS and managed disk and retain all worker evidence,
accounting checks, and exact byte/content hashes. Issue and validate a new receipt
only if every check passes. Never reuse checkpoint evidence to qualify another
executable or machine, edit started plans, or call PREFIX/resource-only evidence
QUALIFIED. If hardware prevents FULL qualification, finish independent work and
report the precise blocker and exact recovery commands without relaxing limits.

Commit and push completed fixes to this PR, verify CI on the final commit, and
report tests, coverage, mutation, qualification status and evidence locations.
Do not merge, activate strategies, trade, or run held-out full confirmation.

## State at handoff

New production classes are `StrategyFixedBaselineCorrectedV1`,
`StrategyOperatingCharacteristicsCorrectedParallelV1`,
`StrategyOperatingCharacteristicsCorrectedSuccessorV1`, and
`StrategyProcessResourcesV1`. Shared coordinator/successor and command adapter
have additive corrected routing. Schemas and deterministic tests were added.
The schema registry closes JAR connections; the oracle test uses JVM temporary
storage; coverage tooling normalizes only CRLF/LF equivalent unchanged lines.
The changed-code thresholds were not lowered.

Independent review occurred in several rounds, but final fixes still require
review. Earlier findings addressed both-book replay, live executable/source/host
bindings, original versus qualified profiles, artifact run IDs, reopening both
serial and parallel evidence, distinct execution IDs, and recomputing resource
envelopes. Do not treat this list as final approval.

The Windows workspace is
`C:/Users/Eternal/IdeaProjects/trading-claude-analytics`. Twelve unrelated untracked
BTC/ETH flying-rocket JSON/Markdown reports dated 20260825_0835, 20260831_0431,
and 20260903_0807 are excluded from the PR and must remain untouched.

The isolated parent WSL ext4 checkout is `/root/corrected-worker-20260909` in
Ubuntu-24.04. Native Windows defaults to Java 8; JDK 21 is installed at
`C:/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot`. WSL used OpenJDK
21.0.11 and Maven wrapper 3.9.11. Native/mounted-filesystem custody tests have
Unix hard-link/symlink limitations; use ext4 for the authoritative clean build.

### Verification already available

All paths below are under ignored Windows
`.report-run/corrected-worker-20260909/` unless specified. They are local evidence,
not portable CI dependencies and not committed qualification receipts.

- `wsl-integration-6.log`: clean reactor BUILD SUCCESS in 3:23, completed
  2026-09-09T10:21:58Z, bound to `integration-6.patch`. Later source/test edits
  exist in this PR; this is not verification of the final committed tree.
- `coverage-5.json` and `.md`: last measured gate **FAIL**, 73.60% lines
  (1196/1625), 56.41% branches (748/1326). Later changes are not covered by this
  result. The checkpoint-6 aggregate exists in the WSL checkout but was not
  evaluated as a final gate before stopping.
- `python-tests-final.log`: 23 coverage-tool tests passed.
- `pit-resources-4.log`: resource helper 31/34 mutations killed (91%).
- `pit-corrected-4.log`: earlier evaluator run failed (135/188 killed, 72%).
  A later implementation agent reported 177/188 killed (94%), 97% strength;
  copied HTML is `StrategyFixedBaselineCorrectedV1.java.html`. This later run
  used manual compilation/Surefire, so rerun authoritative PIT after the clean
  final build. Do not cite the earlier log as evidence of the later result.
- Final PR commit CI was not yet run when this document was written.

### Measured PREFIX evidence, not qualification

Checkpoint 4 used 8 slots across 4 cells, 10 events and 10 controls each, with
distinct development seeds. Serial completed 8/8 in 126355 ms, aggregate RSS
1764323328 bytes, managed disk 140866781 bytes. Two-worker parallel completed
8/8 in 66647 ms, aggregate RSS 3523883008 bytes, managed disk 219471749 bytes.
`checkpoint4-equivalence.json` independently reconciles 32 books and confirms
all 8 economic digests match. The independent checker is `inspect-checkpoint.py`.

Raw evidence and packaged executable remain under WSL
`/root/corrected-worker-20260909/.report-run/corrected-worker-20260909/checkpoint4/`.
The runnable fat JAR is `executable.jar`; an accidentally copied thin
`executor.jar` was not used. Executable SHA-256:
`27f38350dfbf5cb564ce33a581ff119b59203d36a984e0cfa36aaae4368bd35a`.
Source-input fingerprint:
`d6abc1ccb80ae37350aa1b426711fe249d99ab8cd189eb6635b27a27c9d2545a`.
The Windows external archive `checkpoint4-raw.tar.gz` is 118209766 bytes,
SHA-256 `d6b9121175ea9cd749dba26362a38a65850f768d4123a8c2624f8f8d43493313`.
Do not commit this archive or executables. These hashes describe an earlier
checkpoint and cannot qualify the final source or a newly packaged executable.

### Exact qualification blocker

The i7-14700KF exposes 28 logical CPUs. Windows usable RAM was 34138472448 bytes,
below the required 34359738368 by 221265920 bytes (about 211 MiB). WSL exposes
only approximately 15.5 GiB. Freeing applications' RAM does not satisfy this
usable-total requirement. Reinspect on continuation; never round up or count
swap. FULL preflight returned `BLOCKED_RESOURCE`; qualification rejected the
host and no receipt was produced. See `checkpoint4-full-preflight.json` and
`checkpoint4-qualify.stderr`.

The tracked machine/input inventory is
`strategy-research/v5-records/evidence/corrected-worker-20260909/machine-input-inventory.json`.
The retained plan is `strategy-research/v5-records/evidence/performance-20260908/parallel-confirmation-plan-v002.json`;
its resource preflight is nested under `statistical_plan`. Baseline definition
and controls/experiment/policy/lifecycle files are tracked. Historical Parquet,
private `/tmp` inputs and old executor JARs were absent; the supported synthetic
DEVELOPMENT generator does not require those historical caches. The runbook
contains recovery commands, which still require final review and eligible
hardware before FULL execution.
