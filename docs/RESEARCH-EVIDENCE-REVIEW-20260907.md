# Independent evidence review — 2026-09-07

Reviewer: parent (Astra). Implementers: Luna xhigh. Baseline: `30262ac`.
Status: accepted for the bounded diagnostic engineering scope on 2026-09-08.
Full confirmation and physical promotion remain BLOCKED; residual validation
boundaries are listed below.

## Independently established starting evidence

The parent reopened the raw v004 synthetic result and its compact projection.
All 200 projected repetitions agree exactly with the raw fields. The raw byte
hash is `1156b5e34e2dca2fec545f5059b61ceac2e066a7c824959d0fc603f08794a8cb`.
Numerical p20 and p-value cutoffs reproduce each component flag and every
joint decision. Independent Python Wilson calculations give:

| Cell | Positive / total | Wilson 95% interval | Event p-value passes | Paired p-value passes |
| --- | ---: | ---: | ---: | ---: |
| No edge | 2/50 | 1.1039–13.4601% | 2 | 5 |
| Zero-effect check | 0/50 | 0–7.1348% | 3 | 8 |
| Total log drift 0.02 | 14/50 | 17.4742–41.6651% | 24 | 19 |
| Total log drift 0.04 | 45/50 | 78.6398–95.6524% | 48 | 46 |

The small-effect weakness includes both event and paired evidence; increasing
Monte Carlo repetition count alone does not repair underlying detection power.
The no-edge result does not establish that the true false-positive rate exceeds
10%. The zero-effect check has no power target. These remain conditional
synthetic diagnostics, not adaptive or physical confirmation.

An independent legacy candidate scan found 5,325 family-related retained rows,
1,570 distinct claimed behavior hashes and 25 runs. Every matching candidate ID
has a metric row, and all run-bound candidate/metric/trade byte hashes verify.
These are artifact and metric exposure counts, not a derivation of exact modern
effective K. The canonical family HEAD is K=3 / attempt K=37, hash
`6e5d6561ca53fefdfe30c27498ee989ca495c2d1decc6e2e39d6250e43aa9f6a`.

An independent Python reconstruction from the retained 87,656 signal bars and
126 admitted setup rows reproduces the three selected LINK/XRP/SOL controls.
Sequential candidate-event combination counts are 123,061 same-asset downside
nonshocks; 122,866 after selected-control overlap exclusion; 19,687 within the
prior 20–365-day window; 3,307 same-hour; 509 same-weekday; 29 after the prior
return caliper; nine after volatility; three after volume z-score. These counts
are combinations, not independent observations. Scheduled 240-hour windows
govern conservative clustering; an asset label does not establish independence.

Read-only audit outputs are under
`.report-run/research-evidence-review-20260907/`: `prior-independent-audit.json`,
`legacy-independent-counts.json`, and `controls-independent.json`.

## Draft findings sent to implementation

- Lineage must not infer retries from a lone behavior hash or an empty tuple,
  pick identities from unrelated nested records, or treat missing roots and
  parse failures as clean history. Candidate records need run/metric bindings.
- A frozen successor control design needs concrete strata and matching rules;
  a vague cross-asset fallback cannot be called an executable specification.
- An attempt ledger needs planned-slot and executor validation, durable
  reservation before outcomes, lock/CAS protection and interruption semantics.
  Caller-authored COMPLETE rows cannot substitute for evaluator output.
- Diagnostic reconciliation must verify numerical rules, expected cell/seed
  coverage, invalid/inadequate rows and the complete projection contract.
- An unimplemented successor executor is unfinished engineering, not an
  external blocker. Feasibility must use an implemented shared-evaluator path
  and justified resource/sample assumptions.

These findings concern drafts. Their resolution and final verification will be
recorded below; they are not findings against an accepted final implementation.

## Packaged and numerical checkpoints

The fresh Decimal audit also recomputed all 800 retained representative v004
trades, including gross P&L, two-sided fees/slippage and net P&L. Maximum
absolute residual was `1.34052900055403245840e-13` USDT. The parent independently
unioned the physical scheduled windows and recovered 28 event clusters and
three paired clusters without filling the gaps between paired endpoints.

The Java physical replay in `matching-attrition-java-replay.json` reproduces
every one of the eight Python stage counts above. It conditions admission on
the source-bound retained 126 setup events, verifies them against the physical
producer, and reopens only signal and feature roles. It does not replace actual
historical admission with maximum-horizon occupancy.

The parent ran both new focused suites together through Maven: ten tests, one
failure, zero errors/skips. The failure was the candidate-keyed trade join in
`StrategyEvidenceV1Test`; the five successor tests passed. This checkpoint is
not final acceptance. Log: `.report-run/research-evidence-review-20260907/targeted-checkpoint.log`.

The first successor development prefix completed 2/2 repetitions in 32.04
seconds, with reported sampled RSS 2,957,459,456 bytes and eight independent
units per repetition. Its output and ledger correctly have `measured=false`.
This v001 checkpoint retains metrics and setup/control rows but insufficient
raw trade evidence for a new independent economic audit; the final successor
must retain raw evaluator output. Its frozen JAR is `a7daad3626115e1c8e89f266f0ec76deae9bfad64666e9a0d8c56d51fb8eee67`.
Its plan binds an earlier lineage inventory checkpoint and remains preserved
as development exposure; a complete successor precommitment is additive.

## Independent successor raw audit

The v002 prefix retains the full authoritative evaluator output for both fresh
seeds, `910000000` and `910000001`. The parent independently recomputed all 40
event/control trades using Decimal arithmetic; the maximum absolute net-P&L
residual is `1.3981198660e-13` USDT. Both book totals reconcile, and all compact
metrics and portfolio fields agree with the raw output. Java canonical hashing
also reproduces both embedded raw-result bindings.

An independent Python implementation of the JDK SplittableRandom draws and the
10,000-draw cluster bootstrap reproduces the following p20 / centered-null
p-value pairs:

| Seed | Event | Matched difference |
| --- | --- | --- |
| 910000000 | -0.48658443145898134 / 0.5211478852114788 | -0.45853556041860594 / 0.5286471352864713 |
| 910000001 | -0.6011072926813992 / 0.791920807919208 | -1.060193792472216 / 0.9653034696530347 |

The audit is retained in
`strategy-research/v5-records/evidence/research-evidence-20260907/v002-prefix-independent-audit.json`,
alongside the two extracted raw records, canonical-hash receipt, and compact
copies of the earlier independent lineage, matching, clustering, trade and
planning audits. This is development implementation validation: two small
NO_EDGE repetitions, eight independent units each, and `measured=false`.

The parent verified the frozen v002 executor JAR SHA-256
`0d9cf9dcdfbee96e523dd60916d97dfc6463d2faec099d3b7b0477a5aa2d6757` and
generator-source SHA-256
`63e8ef4d4325d47994763bd8dbe3251bd195112859246e51f2ccad7b549855eb` against
the retained bytes. This executable is distinct from the final workspace build.
The JARs are retained locally under the existing ignored-binary convention;
they are not included by an ordinary `git add`. Reproducing the frozen execution
on another machine requires transferring those exact hash-verified binaries
alongside the retained source and evidence, rather than rebuilding a supposedly
identical JAR from a later checkout.

## Remaining evidence and validation boundaries

Historical inventory is a provenance-backed lower bound. It does not resolve
the exact effective K of unavailable historical attempts; canonical K=3 and
attempt K=37 remain untouched. The control investigation explains the physical
28 event / 3 matched clusters and freezes a separate wider-history development
specification; it supplies no additional verified PIT history or fresh physical
outcomes.

Full confirmation remains unrun. Its 300 repetitions require an estimated
3,888,000,000 generated minute bars, 265,154 seconds and 59,742,388,224 bytes
peak RSS, exceeding the declared 12-hour / 8-GiB envelope and this 16-GiB host.
These are conservative extrapolations, not exact-geometry measurements. Disk
budget is reported but not estimated/enforced by the frozen successor executor.
Resource qualification including disk, an exact-geometry measurement or
defensible equivalent, and a separately frozen feasible envelope are required
before full execution. Merely increasing simulation repetition count would not
repair the observed small-effect detection weakness.

The frozen plan's actual full and prefix seed sets must be checked independently
for disjointness. Its general validator has a one-way cross-cell overlap check;
the review does not certify arbitrary caller-authored successor specifications.
Crash/restart accounting has focused tests, but the review has not performed a
host-crash or multi-process fault-injection campaign. No result here validates
the complete adaptive research pipeline, market profitability or observed fills.

## Final precommit and review corrections

The accepted precommit is the additive
`operating-characteristics-successor-plan-v004.json`, content SHA-256
`5bcc1b1a6884674e98fc7a4639c83e3a99aff3a96890b7dcbb23a6df057db94b`, byte
SHA-256 `4859aabd63738aaae264fbd5a8fe67927b35f87c0c083fbf3c2dac3e8a688a42`.
Parent Python assertions verify all five top-level file byte bindings and the
300 full / eight prefix seeds: all unique and mutually disjoint. The v004
preflight is outcome-unopened and resource-blocked, content SHA-256
`14199e42c564fa05656e904a59d5753f21ff2915b6397fd39935eb1109e90020`.

Review found a 63-character matching-attrition byte hash in the retained v003
metadata. V004 corrects it to the actual 64-character SHA-256; v003 remains
preserved with an explicit erratum. V002 preceded its development prefix;
v003/v004 metadata finalization followed that exposed prefix and preceded all
still-unrun full outcomes. None of these metadata revisions is fresh statistical
evidence or another independent attempt.

Contract validation also identified missing diagnostic flags in the retained
v001 lineage and attrition receipts. Those historical receipts remain preserved;
the current emitter and additive metadata corrections address the discrepancy
without changing any numeric count or decision. A schema-validation pass alone
would not have detected the truncated optional hash, which is why direct
file-binding verification is retained separately.

The packaged index command still returns 13 canonical records after adding the
retention artifacts. It wrote the requested scratch index and an audit receipt;
it did not replace the canonical index or family exposure head. No FK/FR skill,
trading parameter, frozen predecessor source, prior result or tracked historical
evidence file appears in the diff.

## Final verification

`./mvnw -q package` passed the complete pinned reactor: **1,018 tests, zero
failures/errors, one historical-fixture skip**. Both Python discovery suites
passed, **seven tests total**. The focused suites and independent audits are
additional checks, not added again to the reactor count.

The final build also includes exact schema-registry corpus updates, the CLI
help-stream expectation for all seven new commands, schema-based legacy-corpus
classification, and a narrowly tested exclusion for vendored JavaScript inside
a verified Python virtual environment. First-party report-run JavaScript remains
prohibited. These resolved regression failures encountered during review.

All **14 current retained diagnostic contracts/plans** validate through
`ResearchSchemaRegistry`, with Java canonical self-hashes independently checked.
The two superseded v001 metadata receipts are explicitly excluded from this
current-contract claim. Parent comparison confirms that their additive v002
corrections change no existing field except the self-hash; added fields are the
missing diagnostic flag, version/erratum metadata, and predecessor bindings.

The final packaged CLI was inspected from `/private/tmp`, outside the repository.
Its actual file SHA-256 matches its runtime identity:
`a501a81c6f3bc6d9763f9658635eccbcc6dbd35d4dc1fd38f25e2f6376c874d4`.
Its embedded input fingerprint equals a fresh workspace calculation:
`efb0d147eb820165e531b48bcbcb6a19883d3f7786cf36eb9ab0100cb908b260`.
The identity correctly reports revision
`30262ac0f910bbec6471dbbd3132a476da04866f` with `dirty_source=true`; this work
remains uncommitted. This build does not replace the frozen v002 executor.

Final verification receipts and logs are retained under
`strategy-research/v5-records/evidence/research-evidence-20260907/` as
`final-build-identity.json`, `final-test-counts.json`,
`final-package-with-tests.log`, and `contract-artifact-final-validation.log`.
`git diff --check` passes. No commit, push, trade, activation, or deployment was
performed.
