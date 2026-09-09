# Successor evaluation boundary — 2026-09-07

This note records the additive diagnosis and successor operating-characteristics
boundary. It is development evidence only. It does not promote a strategy,
open a trading family, or authorize activation.

## Retained v004 diagnosis

The packaged `operating-characteristics-diagnose` command reconciled all 200 raw
rows with the compact projection and recomputed every numeric falsifier and
joint decision. The final diagnostic receipt is retained at
`strategy-research/v5-records/evidence/research-evidence-20260907/successor-predecessor-diagnosis-20260907.json`
with content hash
`69aaa16e9725e6ea4f57b2de57c24ffcf5eef4c294cbd54f24d867d9fd9f282b`.

The four cells are 2/50, 0/50, 14/50, and 45/50 joint decisions for no edge,
zero effect, 0.02, and 0.04. Component pass counts expose the 0.02 attrition:
event p20 27/50, paired p20 22/50, event p-value 24/50, paired p-value 19/50.
The no-edge Wilson upper bound is 0.1346; this does not establish a false
positive rate above 0.10. The 0.04 lower bound is 0.7864, below the 0.80
standard. These are Monte Carlo confidence intervals for the conditional simulation
decision rate in the retained synthetic sample, not estimates of design power.

## Historical v001 successor checkpoint

The pre-outcome plan is
`strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-successor-plan-v001.json`
with content hash
`cab35bae44b541a9c44e0a110b37a79149d18a0c20c6bee1cbc54a52cf9ec949`.
It keeps the v004 dependence geometry at 288 calendar clusters, 162 paired
clusters, 450 treated and 450 control series per replication, and 75
replications per cell. The four cells use immutable, disjoint full seeds and a
separate two-replication, ten-episode prefix seed namespace. The full denominator
and Wilson decision rules were frozen before successor outcomes.

The sample-size note is analytical development planning only. A Gaussian
approximation using the observed component rates suggests roughly 181 event and
288 paired clusters for marginal 0.95 component rates. It ignores the p20 floor,
bootstrap dependence, and uncertainty in those rates, so it is not a validated
power result. The chosen 288-cluster geometry preserves the prior singleton and
double-cluster ratio while making the resource consequence explicit.

This section describes the earlier v001 checkpoint and is superseded by the
durable v003 precommit below. Its physical lineage inventory was unresolved
lower-bound evidence, hash
`465aeee9d9b623224482414e10b7ee490bd7d13bca9405a5f55317e2e60a3468`. The
outcome-blind successor control design is frozen as development evidence, hash
`4bb912ddfd1d4d2b04b2116da77b742376fe1a5aeb7caf7032cb022a61bffc4b`; it uses
same-asset matching, no cross-asset fallback, no reuse within asset and calendar
year, a five-year PIT lookback, and retains unmatched rows.

## Historical v001 resource checkpoint

The packaged v001 executable was
`analytics-cli/target/analytics-cli-1.0.0-SNAPSHOT-exec.jar`, SHA-256
`a7daad3626115e1c8e89f266f0ec76deae9bfad64666e9a0d8c56d51fb8eee67`.
The retained preflight estimate is 3,888,000,000 generated minute bars
(a count), 265,154 seconds of wall time, and 59,742,388,224 bytes of estimated
peak RSS from the retained v004 runtime and declared geometry. The declared
envelope is 720 minutes, 8 GiB RSS, and
20 GiB transient disk. The full confirmation therefore fails closed before
outcomes with `SUCCESSOR_FULL_GEOMETRY_EXCEEDS_DECLARED_8GIB_12H_ENVELOPE`.

The bounded prefix did execute through the shared fixed evaluator. Its result
and append-only ledger remain in
`.report-run/research-evidence-review-20260907/` as the historical v001
checkpoint: `successor-prefix-result-20260907.json` and
`successor-prefix-ledger-20260907.json`. It completed 2/2 NO_EDGE attempts in
32.04 seconds with 2,957,459,456 bytes peak RSS. The ledger retains two durable
STARTED reservations and their two COMPLETE retries; its planned denominator is
two slots, and `measured=false` because a prefix cannot meet the 30-cluster
minimum or support Wilson interpretation. Each COMPLETE receipt binds the
packaged executor and generated input hash; caller-authored COMPLETE imports are
rejected.

No v001 prefix statistic is used as confirmation or power evidence. The full run
remains runnable through the same adapter once a separately justified resource
envelope exists, but this plan records the current machine-budget blocker rather
than spending resources on an underpowered four-cell run.

## Historical v003 durable precommit and v002 implementation validation

The prior additive precommit checkpoint was
`strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-successor-plan-v003.json`
with content hash
`97fbf3c59290b977fe879cc09f55542e7c510c43baa4ef2e524039f5ee294abb` and byte
hash `f87f89680e9fb0d0356613cb789c88aded64ed0c20115ff74660ef1343438bc1`.
It binds the corrected durable evidence under
`strategy-research/v5-records/evidence/research-evidence-20260907/`: lineage
inventory content hash `2abef32d5b95e0f7faf6bf8f1fff5e7363ca1e480a609280cea4442242cd43a3`,
matching attrition content hash
`085cd84aff23c8290ba97f2d07050a37375d5a8b4386ae4dcdb7148d7b74d598`, and
successor control design content hash
`f2a391fe27c18f50dc9e7df52f64330a5515ed5a2b315bb3caf20bb862948201`.
The predecessor diagnosis, prefix result, append-only ledger, raw-output audit,
and preflight receipt are retained in that same durable directory.

The precommit defines explicit no-edge and zero-effect null cells, with no target
for the zero-effect diagnostic, and +0.02/+0.04 cumulative treated log-return
alternatives. The effect is applied as `effect / 14,400` to each treated
post-decision minute log return; +0.02 and +0.04 therefore mean approximately
2.0201% and 4.0811% multiplicative endpoint moves before the evaluator's costs
and selection. The joint rule remains the intersection of the four V004 numeric
falsifiers. Component intervals are pointwise diagnostics, with no multiplicity
adjustment or post-hoc pooling.

The 75-replication count is fixed before outcomes. At `n=75`, the Wilson 95%
upper bound for the no-edge cell is at most 0.10 only through 2 successes
(0.0921; 3 gives 0.1111), and the lower bound for a positive-effect cell reaches
0.80 at 67 successes (0.8034; 66 gives 0.7874). The approximate planning
calculation uses prior component rates and the retained 14-singleton/18-paired
cluster dependence ratio; it ignores p20 floors, bootstrap behavior, dependence
uncertainty, and rate uncertainty. It is a geometry rationale, never a validated
power claim.

The v002 implementation validation used the same retained packaged evaluator,
fresh disjoint prefix seeds `910000000` and `910000001`, and complete raw output
retention. It is recorded as development execution only: 2/2 NO_EDGE rows,
32.025 seconds, 3,045,261,312 bytes peak RSS, `measured=false`. The raw result
and ledger are bound by content and byte hashes; the independent raw audit
reconciles both 10-trade event/control books, every trade cost/net sum, row
projections, and raw evaluator hashes. This prefix cannot satisfy a 75-replication
criterion and is not a v003 measured result.

The v003 preflight checkpoint is
`strategy-research/v5-records/evidence/research-evidence-20260907/successor-preflight-v003-final-20260907.json`,
content hash `e326622e52caf43db215b48b56439478bf2bd132da98694136cbfc0b9978ffd2`.
It is `BLOCKED_RESOURCE_ESTIMATE`: the frozen full
geometry is estimated at 265,154 seconds and 59,742,388,224 bytes peak RSS,
outside the 720-minute, 8-GiB, 20-GiB transient-disk envelope. The runnable
successor command therefore fails closed before full outcomes and retains an
explicit next step: qualify a complete resource envelope, including a disk
estimate/enforcement check and an exact-geometry prefix or conservative
equivalent, then rerun preflight before executing the unchanged frozen plan
with the append-only ledger. The current preflight reports `max_disk_bytes` but
does not estimate or enforce disk usage, and the two-replication prefix is a
 resource/retention check rather than an exact-geometry measurement.

## Corrected v004 precommit — 2026-09-08

The v003 bytes above remain preserved and immutable. An independent byte audit
found that its `matching_attrition_byte_sha256` transcription omitted the final
`b`; that metadata error is recorded in
`strategy-research/v5-records/evidence/research-evidence-20260907/successor-plan-v004-erratum-20260908.json`.
The retained artifact's actual SHA-256 is
`268810e97ca3b1c53a6c24da9448716d7006ecd0f7bae21fb628eea46507351b`.

The corrected additive precommit is
`strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-successor-plan-v004.json`
with content hash
`5bcc1b1a6884674e98fc7a4639c83e3a99aff3a96890b7dcbb23a6df057db94b` and
byte hash
`4859aabd63738aaae264fbd5a8fe67927b35f87c0c083fbf3c2dac3e8a688a42`.
It changes only the corrected metadata binding and its explicit erratum
references. The executor JAR, source, seeds, geometry, hypotheses, budget,
stopping policy, and all outcome status remain unchanged; no full outcomes
were opened and activation remains prohibited.

The existing packaged executor validated the corrected plan with this retained
receipt:
`strategy-research/v5-records/evidence/research-evidence-20260907/successor-preflight-v004-20260908.json`,
content hash
`14199e42c564fa05656e904a59d5753f21ff2915b6397fd39935eb1109e90020`, byte
hash `5f776a5cc928bc48805501cdd132c289e7fef5bac8e4d822a9c8b174da14bf18`.
The command exited successfully and returned `BLOCKED_RESOURCE_ESTIMATE`,
with `plan_sha256` equal to the corrected v004 content hash, outcomes closed,
3,888,000,000 expected minute bars, 265,154 estimated wall seconds, and
59,742,388,224 estimated peak RSS bytes. The preflight still reports the
declared disk limit without estimating or enforcing it; an exact-geometry
resource qualification remains required before any future full execution.

## Evidence boundaries

`operating-characteristics-diagnose` is a raw/compact reconciliation and numeric
cutoff audit. `operating-characteristics-successor-preflight` is a resource
decision. `operating-characteristics-successor-run --prefix` is an execution and
retention check; it sets `measured=false`. The full successor runner is the only
path that can produce measured cell counts, and it cannot run while its frozen
preflight is blocked. All commands remain diagnostic, promotion-ineligible, and
activation-ineligible.
