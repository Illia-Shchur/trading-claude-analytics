# V005 results — negative development evidence; no profile advanced

The six new v005 configurations all lost money before funding in this historical replay, and all remained negative when fees and slippage were doubled. The retained v004 baseline alias finished positive, but it is an already exposed comparison, not a new v005 configuration. No profile is advanced.

The post-shock entry profile returned **−2.11%** net of modeled costs before funding, versus **+9.28%** for the retained baseline. The H4 stop and refreshed-staging profiles returned −6.49% and −7.11%. RSI context produced the exact same fills and account trace as refreshed staging. The SMA gate prevented one TRX add; the SMA-only and combined profiles were identical and still lost 5.81%. The replay had no filled Stage 3 tranche.

## Scope and performance

The run used nine USDT perpetuals: BTC, ETH, SOL, AAVE, UNI, BNB, LINK, ZEC and TRX. Hourly execution inputs span August 11, 2022 through September 19, 2026; the decision window is November 11, 2022 through July 14, 2026 inclusive. A separate April 1–August 11, 2022 H1 warmup (August 11 exclusive) feeds daily context only. All profiles start from $20,000. Returns are account equity change after modeled entry/exit costs and **before funding**. Drawdown is measured on the simulated event-price path.

| Profile | Base return | Base max DD | Base L / S / adds | 2× cost return | 2× cost max DD | 2× cost L / S / adds |
|---|---:|---:|---:|---:|---:|---:|
| BASELINE_V004 (retained alias) | +9.28% | 6.23% | 0 / 8 / 0 | +5.61% | 6.10% | 0 / 8 / 0 |
| POST_SHOCK_ENTRY | −2.11% | 8.99% | 22 / 18 / 0 | −7.38% | 9.66% | 22 / 18 / 0 |
| H4_STRUCTURAL_STOP | −6.49% | 9.51% | 22 / 18 / 1 | −10.13% | 10.21% | 22 / 18 / 1 |
| REFRESHED_STAGING | −7.11% | 9.51% | 22 / 18 / 2 | −11.09% | 11.17% | 22 / 18 / 2 |
| DAILY_RSI_CONTEXT | −7.11% | 9.51% | 22 / 18 / 2 | −11.09% | 11.17% | 22 / 18 / 2 |
| DAILY_MA_CONTEXT | −5.81% | 8.78% | 22 / 18 / 1 | −9.72% | 10.03% | 22 / 18 / 1 |
| DAILY_BOTH_CONTEXT | −5.81% | 8.78% | 22 / 18 / 1 | −9.72% | 10.03% | 22 / 18 / 1 |

L/S counts are Stage 1 long/short entry fills; “adds” counts Stage 2 fills. Thus each successor profile filled 40 first entries (22 long, 18 short), plus zero to two Stage 2 adds. The baseline filled eight short first entries. Doubled costs rerun sizing and execution, so drawdown need not move monotonically with return. All 14 serialized base and doubled-cost ledgers report zero ex-funding reconciliation difference; an independent reviewer audited account and fill traces within a tolerance below 1e−8 USDT. All positions were closed by the run end.

The new profiles share the same 40 first entries. By asset, they were: BTC 3 long/5 short; ETH 3/4; SOL 1/3; AAVE 4/1; UNI 1/1; BNB 3/1; LINK 2/0; ZEC 2/0; TRX 3/3. H4 structural-stop filled one TRX add. Refreshed staging and RSI each filled TRX and BTC adds. The MA and combined gates kept the BTC add but suppressed the TRX add. No profile reached Stage 3.

## Event response and dependence

At first observable event availability, the shared event study includes 179 qualified events grouped into 52 market-wide shock clusters. The table reports the price response in the direction opposite to the shock and in the signed shock direction. The 95% intervals use 10,000 bootstrap draws resampled by shared shock cluster (seed 20260921); each interval includes zero.

| Horizon | Opposite-shock mean (95% CI) | Signed-shock mean (95% CI) |
|---|---:|---:|
| 1 day | +0.66% (−0.11%, +1.39%) | −0.66% (−1.39%, +0.11%) |
| 3 days | +0.64% (−0.59%, +2.24%) | −0.64% (−2.24%, +0.59%) |
| 7 days | +1.22% (−0.25%, +2.83%) | −1.22% (−2.83%, +0.25%) |

The seven-day opposite-shock asset means vary: AAVE +2.75% (18 events/15 clusters), BNB −0.51% (21/16), BTC +1.56% (28/21), ETH +1.37% (27/21), LINK −0.89% (21/19), SOL +1.11% (15/12), TRX +0.81% (21/16), UNI +2.73% (14/12), and ZEC +2.16% (14/12). These are descriptive asset slices; no per-asset interval establishes an edge.

The routed-entry response is a separate descriptive anchor. The retained baseline alias has 12 decisions across 7 shock clusters; each of the six new profiles shares the same 54 decisions across 32 clusters. The six rows are repeated profile views of one cohort, not 324 independent observations.

| Routed-direction response | 1 day mean (95% CI) | 3 day mean (95% CI) | 7 day mean (95% CI) |
|---|---:|---:|---:|
| BASELINE_V004 alias (12 decisions / 7 clusters) | −0.07% (−1.98%, +1.63%) | +0.91% (−1.89%, +4.11%) | +3.23% (−3.19%, +11.97%) |
| Each of the six new profiles (same 54 decisions / 32 clusters) | −1.51% (−3.42%, −0.06%) | −1.21% (−3.29%, +0.65%) | −2.66% (−9.34%, +3.06%) |

The new-profile 1-day interval is nominal and excludes zero, but is not multiplicity-adjusted and does not establish a selected edge; the 3- and 7-day intervals include zero. In the new shared cohort, seven-day routed-direction means were −5.35% for continuation and +1.21% for reversal (27 decisions / 20 clusters in each branch), descriptive only. The baseline alias had 9 continuation decisions across 5 clusters (−0.16%) and 3 reversal decisions across 3 clusters (+6.10%).

Routed decisions and completed positions are different stages. Each new profile had 54 routed decisions (27 continuation and 27 reversal) but 40 completed positions (18 continuation, 22 reversal); the baseline alias had 12 routed decisions (9/3) and 8 completed continuation positions (8/0). The precommit's 20-completed-position-per-branch reference is not met for continuation in the new profiles (18); routed-decision counts do not substitute for fills.

There are 27 position-overlap components across the profile traces. Neither the 179 event rows nor the 248 profile-position records are independent samples: the shared-shock and holding-overlap descriptions are 52 shock clusters and 27 overlap components. The 27 components are fewer than the 30 reference threshold, but they are a descriptive grouping, not an estimated effective sample size; effective independence was not qualified. The precommit also references 20 completed positions per branch and eight outer folds. Formal familywise/p20 gates and outer-fold validation were not run. The raw losses support **no advancement**, not a claim of formal statistical familywise rejection.

The daily-context profiles use same-asset UTC daily closes formed only from exactly 24 consecutive hourly bars. Long support is RSI-14 > 50 and close > SMA-200; short support is RSI-14 < 50 and close < SMA-200. Equality is neutral. These context rules never gate first entries. At Stage 2, the S&P 500 condition and each enabled daily context must be neutral or supportive; at Stage 3, all must be supportive. Unknown or opposing context blocks only a new add, and existing positions are retained without context-triggered exits or stop changes. No Stage 3 fill occurred, so that gate was not exercised by a fill.

## Daily context and limits

Retained hourly prices contain 36,024 rows per asset with no internal gaps. The supplemental warmup has 28,368 of 28,512 expected H1 rows; its 144 missing hours are three leading intervals for SOL, ZEC and TRX. No missing bars were filled. The calculator emitted 14,691 complete daily rows, of which 14,565 had RSI-14 and 12,900 had SMA-200 available. Supplemental history entered only the daily-context calculator, not event construction, marks or execution.

Funding is **excluded, not treated as zero**. The run uses retrospective archive vintages whose historical publication times are not verified. Coinalyze liquidation revision/publication latency is not independently qualified, so t+48h availability remains a development assumption. Contract sizes, fees, slippage, maintenance margin and liquidation fees are frozen approximations. Hourly OHLC supplies execution and mark proxies; capacity is capped by a 1% prior-hour volume proxy. The daily warmup has 144 leading missing H1 hours for SOL, ZEC and TRX; these hours remain missing and indicator context does not carry across incomplete daily bars. One inherited behavior also remains ambiguous: reversal recovery-target/midpoint touches before the current confirmation bar are not latched. This run preserves that behavior. The outcome is development evidence only: no forward run, sealed holdout, reset, profile selection or promotion occurred. The retained v004 baseline and predecessor results were already exposed; the successor family adds six declared configurations, all retained in the exposure record.

An independent reviewer audited the 14 base/doubled-cost account and fill traces. The available overlap count does not qualify effective independence, and this study does not validate deployable performance.

## Receipts

The compact machine-readable summary is [results.json](results.json). The full 25 MB trace remains in the ignored research-run bundle and is byte-bound by its completion receipt.

| Artifact | Path | SHA-256 |
|---|---|---|
| Frozen policy | [exploratory-policy.json](exploratory-policy.json) | `cbc554e7982c14c633c929536f6a9483411d6b873d7d0040a45e2b8046c2ae21` |
| Input manifest | [input-manifest.json](../../../.research-run/liquidation-exploratory-v005/input-manifest.json) | `c72cce0d621e0de9c4a41f1bdebc65b8adfb6b32db8aa0765efb7e89f94d66fc` |
| Outer 108-file input freeze | [data-freeze.json](../../../.research-run/liquidation-exploratory-v005/data-freeze.json) | `22d26c4734bf4e862f234793043c5459a3900c3026056b4a5245dd2ac6917d41` |
| Daily-context freeze | [context-warmup/data-freeze.json](../../../.research-run/liquidation-exploratory-v005/context-warmup/data-freeze.json) | `5f418e3aaecb02fb62517310b0236f987d4ed780237c1adfa45b3dcc8e26682a` |
| Daily-context manifest | [context-warmup/context-warmup-manifest.json](../../../.research-run/liquidation-exploratory-v005/context-warmup/context-warmup-manifest.json) | `c2e689573d5cd360e53b2f1daca7f8fa64d7bb8f9a5755924fb53b2415b07e0b` |
| Supplemental archive manifest | [context-warmup/archive-manifest.json](../../../.research-run/liquidation-exploratory-v005/context-warmup/archive-manifest.json) | `5e29a45ee18d7fd263203e2a8cab79f421de5720faa92386e68a86e56ae0c443` |
| Run attempt/source receipt | [attempt.json](../../../.research-run/liquidation-exploratory-v005/runs/2026-09-22T10-05-35-237575Z-2c0ee15d-aa08-4fd7-be64-a141d84ffb99/attempt.json) | `107` source hashes |
| Full result | [result.json](../../../.research-run/liquidation-exploratory-v005/runs/2026-09-22T10-05-35-237575Z-2c0ee15d-aa08-4fd7-be64-a141d84ffb99/result.json) | `f7766ccbd8bf54ad1d29dac59f25230d8d7fb56dddb019b9a361f564734bd8f9` |
| Completion receipt | [completion.json](../../../.research-run/liquidation-exploratory-v005/runs/2026-09-22T10-05-35-237575Z-2c0ee15d-aa08-4fd7-be64-a141d84ffb99/completion.json) | `b4e174baa41484dcef13c782603c0f64e5cef9bc33cc96ae85a079278c0fe10b` (byte SHA-256); content hash `ab74489c6b8420d60089ca61039667b9e69f82c5b115bc8fefe175269f488f4c` |
| Retained executor JAR | [liquidation-daily-context-v005-001.jar](../../../.research-run/liquidation-exploratory-v005/executor/liquidation-daily-context-v005-001.jar) | `7ae562430f8ad1449306238d83ff0aab30fcf7ffb615a5d5db8f004a20c1043b` |
| Executor receipt | [liquidation-daily-context-v005-001-receipt.json](../../../.research-run/liquidation-exploratory-v005/executor/liquidation-daily-context-v005-001-receipt.json) | `607ee7584ec44d216b5048a39b7b380b5a47ea69910366a8ad3b3ef5fc64be4a` |

The full trace's internal `content_sha256` is `1e132300f3c5e4714097938a514648de1d2d92305dfe93568fd6c3bc787aedfe`; the run ID is `2026-09-22T10-05-35-237575Z-2c0ee15d-aa08-4fd7-be64-a141d84ffb99`. The exact source hashes are listed in the attempt and freeze receipts; the executor receipt also records the six production-source hashes used to build the retained JAR.
