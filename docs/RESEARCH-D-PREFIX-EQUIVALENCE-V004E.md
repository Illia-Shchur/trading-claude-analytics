# D post-amendment prefix equivalence receipt (v004e)

The v004e prefix is the additive corrected source-freeze run. The earlier
v004d freeze/result bytes remain at their original paths, and the intermediate
v004d corrected transition is retained under explicit `*-corrected` paths. This
run uses the unchanged v004 plan and generator with the bounded per-run
RSS/deadline guard. It is a fixed-stage synthetic diagnostic, not calibration,
promotion evidence, public PIT evidence, or observed-fill evidence.

## Frozen inputs

- Plan: `operating-characteristics-plan-v004.json`, content SHA
  `63e17392714365810db25d52f382f5b9578a2cead9ee06b0335e4360b41eb235`.
- Source freeze: `operating-characteristics-source-freeze-v004e.json`, content
  SHA `8fe7bb979f01c68b799f71577b538e04598969504b8ee49cb1e8a7d18fa7ffe7`.
- Generator source SHA:
  `c804ca1405255c58195d3a06685a452c73188c9a58d275ec891a7741170befce`.
- Executor: `executors/analytics-current-20260906-e11-post-amendment.jar`,
  SHA `783e9ebef5a5e706d0ed7fbb5d30aaf21ae7d4cfcfbb934a69556e21a9c4bf35`.
- Build input fingerprint:
  `d119ab5448d8666e3d861a428165c4c33323446a0a666f0d2b8ffc26af956f5d`.

The freeze was created before this prefix's generated outcomes. Its prior
outcome list preserves all earlier prefix content hashes, so it does not claim
that those outcomes were unopened.

## Command and result

```sh
java -jar strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/executors/analytics-current-20260906-e11-post-amendment.jar \
  strategy-research-v5 operating-characteristics-run \
  --plan strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-plan-v004.json \
  --source-freeze strategy-research/experiments/fk-deleveraging-baseline-v002/operating-characteristics-source-freeze-v004e.json \
  --baseline strategy-research/definitions/fk-deleveraging-absorption/v002.json \
  --controls strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json \
  --experiment strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json \
  --replications 2 --episodes-per-replication 10 \
  --out strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/operating-characteristics-prefix-v004e.json
```

The result is `PREFIX_COMPLETE`, content SHA
`58f183b276f770aff22d086061f7cee05ba25344b44feb0716001349ba80646e`, byte
SHA `5eeb2ece1a7de8ddde22e1754a651797606b27dd024d408d16b8445f364f321f`.
Both replications completed with ten event/control series and eight tested
independent units per replication. Both are `INSUFFICIENT_EVIDENCE` under the
frozen 30-unit minimum; rates and Wilson bounds are unavailable. No resource
abort occurred. Runtime was 32.108169541 seconds and peak sampled RSS was
2,263,678,976 bytes under the 6 GiB prefix limit.

## Equivalence disposition

Compared with the retained corrected4 pre-freeze prefix (content SHA
`afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`, byte SHA
`8f7dcf6b4c064ddf36bf1f2534dc1010b8e2e1b670a8ce6a597329dd3660824c`), the
independent audit found identical generator input hashes, setup audits,
control selections, metrics, dispositions, decisions, and portfolio totals.
Eight representative event/control trades matched in price, quantity, costs,
and net P&L; maximum Decimal net residual was `9.856661156040E-14`.
Lifecycle hashes and portfolio mark points differ only under the conservative
close-derived availability amendment. This is bounded numerical/economic
equivalence for the two-replication prefix, not a claim about all 200 full-run
replications or byte identity.
