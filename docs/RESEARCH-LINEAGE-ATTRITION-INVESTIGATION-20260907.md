# Lineage and matching attrition investigation — 2026-09-07

This note records the bounded Priority 1/2 investigation from the preserved
starting commit `30262ac`. It describes retained bytes and outcome-blind
replays. It does not rewrite the family exposure head, promote a control, or
turn artifact rows into independent observations.

## Retained lineage and attempt custody

The canonical family head is
`strategy-research/v5-records/families/2afbe00fbdbb720a5c2fd1968c447dd1a0478d7670f3b9422b44f86169e3e38e/exposure-head.json`,
content hash
`6e5d6561ca53fefdfe30c27498ee989ca495c2d1decc6e2e39d6250e43aa9f6a`.
Its durable fields are cumulative `K=3` and `exposure_attempt_k=37`. The
three head entries include one `FIXED_BASELINE_DIAGNOSTIC` behavior and two
`FIXED_REFINEMENT_DIAGNOSTIC` behaviors. The head records two exact fixed
attempt behavior/dataset pairs and a fixed-attempt-ledger migration receipt;
it does not provide complete custody for every historical attempt represented
by attempt K=37.

The run-bound scan covers `strategy-research/runs` and verifies the referenced
candidate, metric, and trade byte hashes for 25 run contexts. The final
source-bound inventory reports:

| retained quantity | count | interpretation |
| --- | ---: | --- |
| matching family rows | 5,360 | includes 5,325 candidate rows and 35 family-visible trade rows |
| candidate rows | 5,325 | retained candidate artifact rows |
| candidate IDs | 1,570 | IDs joined across run-bound artifacts |
| candidate-bound metric rows | 5,326 | one repeated candidate-keyed metric row is retained |
| all reopened metric rows | 12,369 | 7,043 are other-family metric rows |
| candidate-keyed outcome units pending full custody join | 44 | retained outcome rows/units, not extra statistical attempts |
| parse failures | 0 | no failure in this bounded root scan |
| proven retry groups | 0 | a behavior hash alone is never a retry proof |
| exact independent exposure lower bound | 0 | custody tuples do not establish the modern K |

The inventory is therefore an `INVENTORIED` lower bound, not a claim that
legacy history is complete. Exact legacy K beyond the canonical K=3 / attempt
K=37, the exact historical code and data custody for every attempt, and retry
custody remain unknown. A zero `unresolvable_attempt_count` means that every
currently discovered matching unit had a joinable local identity; it does not
mean that no historical bytes, attempts, or retries are missing. Missing roots,
parse failures, identity-free rows, and receipt mismatches remain unresolved
when encountered by the CLI.

Retained fixed/refinement result receipts are all development or blocked
records. The fixed baseline e11 result is `BLOCKED`, content hash
`35f69ab55486b4669bb68e309a2b749c763bad3f5678e4181fe5fd4c9b1140ba`; the
full v20 result and v21 recompute have the same canonical result content hash
`a54cef41b51e9c56e033f66673b9d3904454481cdebc571298cf2c4fd614796e` and are
not separate independent attempts. Refinement R1, R2, and R3 are also
`BLOCKED`, with content hashes respectively
`369b4f6c6261489d57b3a255b7a1eedcdad0dbe39656213bf4bb699a0c5821d9`,
`bcf0913c593c084feaa7e26a59765d1504ae5361836be93d94032936d2757368`, and
`6f7ca0dc907a2a7d6f3f1a1bb2d02a39223911ee9a0e79437590127b84d65b78`.
These records preserve failed/fixed/refinement evidence and are not added to
K or to the independent-cluster denominator.

## Outcome-blind physical attrition

The authoritative matcher now emits stage instrumentation through
`StrategyResearchImprovementV1.selectOutcomeBlindControl`; it does not change
that selector's matching predicate. The physical diagnostic reopens only the
source-bound `signal_bars` and `features` roles, validates the retained 126
setup rows against the producer, and leaves labels, child bars, executions,
and outcome values unopened. Historical event admission is therefore bound to
the previously evaluated lifecycle inventory; scheduled 240-hour windows are
used only for candidate reuse/overlap and clustering.

The replay is bound to the fixed baseline content hash
`4d2563ed94eae50e076f75f010adea3232b66ef5bd058f0cd1967051f1f16176`, control
spec content hash
`e2e874b216b86bb6479c3f14f1aab7844b27e05b2eae3da2eb93b7870f525468`, and
physical-input content hash
`4f5ac7a69e4543aa34c66fa7dcf215a1b0a1eaf9ebe5d7178f6da312a7ddfa40`.
The raw retained result is content hash
`a54cef41b51e9c56e033f66673b9d3904454481cdebc571298cf2c4fd614796e` and
byte hash
`824174204a158826f14ce1101a9b95f477e63d84decf75d50c90a1f183e027e8`.

The source result reconciles 130 feature rows to 126 admitted setup events,
three selected controls, 28 merged scheduled event clusters, and three merged
scheduled paired clusters. The source result has 123 admitted events with no
control and three selected controls with zero unresolved selected-control
executions. A missing control is not an execution failure.

Sequential candidate-event combinations from the source-bound inventory are:

```
same_asset_downside_nonshock  123061
unused_nonoverlapping         122866
prior_20_to_365_days           19687
same_hour                       3307
same_weekday                     509
prior_30_bar_return               29
prior_30_bar_realized_volatility   9
prior_30_bar_volume_zscore          3
```

These are combinations, not independent observations. The frozen lifecycle
window is scheduled from each decision time for 240 hours; it is not an actual
trade exit. Pair intervals are unions of the two scheduled endpoint windows
and do not fill the gap between endpoints. Asset labels do not create
independence.

## Additive successor control design

The successor is `FROZEN_DEVELOPMENT`, outcome-blind, and never promotion or
activation eligible. It is same-asset first with no cross-asset fallback and
uses only physically retained point-in-time rows. It expands the predecessor's
maximum prior lookback from 365 to 1,825 days and keeps the predecessor's
matching variables, same-hour and same-weekday rules, downside range, 480-hour
minimum lag, without-replacement policy, standardized-distance tie-break, and
240-hour scheduled lifecycle. The bound venue/instrument are Binance spot,
with the predecessor's 4h signal cadence and fee/slippage rates of 0.001 per
side and 0.00050 per side. No synthetic rows are allowed, and unmatched or
unresolved controls remain in the denominator.

The wider lookback is a new specification. Retained rows are not treated as
verified PIT coverage for the expanded window, and no new outcome run is
claimed here. Development exposure must use the fixed evaluator and preserve
raw attempts before any decision about coverage or promotion.

## Durable artifacts

The final machine artifacts are retained under
`strategy-research/v5-records/evidence/research-evidence-20260907/`:

- `lineage-inventory-v001.json`: content hash
  `2abef32d5b95e0f7faf6bf8f1fff5e7363ca1e480a609280cea4442242cd43a3`, byte
  SHA-256 `c0c3a2cb8abf8037175be2a681003df6da83021537e0159088fc7df65c7a63f1`.
- `matching-attrition-v001.json`: content hash
  `085cd84aff23c8290ba97f2d07050a37375d5a8b4386ae4dcdb7148d7b74d598`, byte
  SHA-256 `268810e97ca3b1c53a6c24da9448716d7006ecd0f7bae21fb628eea46507351b`.
- `successor-control-design-v001.json`: content hash
  `f2a391fe27c18f50dc9e7df52f64330a5515ed5a2b315bb3caf20bb862948201`, byte
  SHA-256 `3c4138bc8ce308542f229cf49079a6fb2695f439bc7a125596ffd8430e617419`.

The three corresponding schemas are
`schemas/strategy-research-lineage-inventory-1.schema.json`,
`schemas/strategy-matching-attrition-1.schema.json`, and
`schemas/strategy-control-successor-design-1.schema.json`. Earlier
`.report-run/research-evidence-review-20260907/` checkpoints remain preserved
and are not overwritten by these durable copies.
