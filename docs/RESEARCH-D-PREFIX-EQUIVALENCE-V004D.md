# D post-amendment prefix transition receipt (v004d corrected)

This transition preserves the original v004d execution path while recording
a corrected prior-outcome lineage. The original v004d freeze/result bytes remain
at their original paths; the transition bytes use explicit `*-corrected` paths.
The run occurred after the earlier prefix had exposed outcomes. It uses the unchanged v004 plan and generator semantics with a new
packaged executable containing the bounded per-run RSS/deadline guard. It is a
fixed-stage synthetic diagnostic; it is not calibration, promotion evidence,
or a claim about public PIT data or observed fills.

## Frozen inputs

- Plan: `operating-characteristics-plan-v004.json`, content SHA
  `63e17392714365810db25d52f382f5b9578a2cead9ee06b0335e4360b41eb235`.
- Additive source freeze:
  `operating-characteristics-source-freeze-v004d-corrected.json`, content SHA
  `26a193cf3e28b679a0252c7dad14fd523977fc4c17ebd41ac184cd90e222bdf1`.
- Generator source SHA:
  `c804ca1405255c58195d3a06685a452c73188c9a58d275ec891a7741170befce`.
- Executor:
  `executors/analytics-current-20260906-e11-post-amendment.jar`, SHA
  `783e9ebef5a5e706d0ed7fbb5d30aaf21ae7d4cfcfbb934a69556e21a9c4bf35`.
- Build input fingerprint:
  `d119ab5448d8666e3d861a428165c4c33323446a0a666f0d2b8ffc26af956f5d`.

The freeze was created before this prefix's generated outcomes, while its
`prior_draft_outcomes_opened` flag records the earlier WIP/prefix exposure.

## Command and result

The exact invocation was:

```sh
java -jar strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/analytics-current-20260906-e11-post-amendment.jar \
  strategy-research-v5 operating-characteristics-run \
  --plan strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json \
  --source-freeze strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-source-freeze-v004d-corrected.json \
  --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
  --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
  --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
  --replications 2 --episodes-per-replication 10 \
  --out strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/operating-characteristics-prefix-v004d-corrected.json
```

The retained result is
`operating-characteristics-prefix-v004d-corrected.json`: content SHA
`103a43c938d57ebbf665ae090b53c0cbaed2b5494978e02b52d56f35bbe66a35` and byte
SHA `b33e24e3155696fc2c824cfdac0d1716b9f442f70e666f30e853c4df30e6dff0`.
It is `PREFIX_COMPLETE`, with two completed replications, ten event/control
series per replication, eight tested independent units per replication, and
both replications classified `INSUFFICIENT_EVIDENCE` under the frozen
30-unit minimum. Rates and Wilson bounds are unavailable by contract. No
resource abort occurred; runtime was 32.743570541 seconds and peak sampled
RSS was 2,163,064,832 bytes under the 6 GiB prefix limit.

## Equivalence disposition

Against the retained corrected4 pre-freeze prefix (`/tmp/d-v004-prefix-corrected4.json`,
content SHA `afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`,
byte SHA `8f7dcf6b4c064ddf36bf1f2534dc1010b8e1e2b670a8ce6a597329dd3660824c`),
the additive run preserves all generator input hashes, setup audits, control
selections, metrics, dispositions, decisions, and portfolio totals. The eight
representative event/control trades have identical prices, quantities, costs,
and net P&L; the maximum independent Decimal net residual is
`9.856661156040E-14`. Lifecycle hashes and portfolio mark points differ only
because the amendment applies conservative close-derived availability. This is
bounded numerical/economic equivalence for the 2-replication prefix, not a
claim about all 200 full-run replications or byte identity. The earlier result
and the amended result remain additive.
