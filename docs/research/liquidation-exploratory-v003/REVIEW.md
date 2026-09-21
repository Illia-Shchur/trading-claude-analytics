# Independent implementation review — v003

An independent read-only review preceded the first historical replay. It inspected the hourly adapter, shared account/router changes, response/dependence helper, and integrity checks. It did not inspect historical returns.

Corrected findings included archived trade omission, generic collateral liability preservation, daily lookback double filtering, H4/H1 map collisions, exact hourly fill timing, explicit 60-day exits, event geometry boundaries, event-anchor future-routing leakage, shared bootstrap universes across differing metric subsets, partial calendar-block treatment, and result hash ordering.

The final integrated review found no remaining critical source-level blocker, subject to tests. It checked exact decision-plus-one-hour execution, prior-hour exits before new stop updates and fills, fill-hour exits, staged fill acknowledgments, all six strategy/cost simulations, archived/current trade reconciliation, feature-only shock grouping, and frozen source/artifact hashes. Asset-first observation ordering preserves same-time stop ordering.

The first packaged launch stopped before replay because class CodeSource resolved inside Spring Boot's nested research JAR. A separate review approved the corrected physical executable binding: single JAR launch path, Spring Boot manifest, matching loaded diagnostic class bytes, and outer JAR SHA-256. The failed launch and original executable were retained. No strategy parameters were changed in response.

Remaining interpretation limits: funding excluded; hourly execution and trade-price mark approximation; conservative event-path drawdown; externally enforced 24-hour budget; retrospective source vintage; descriptive small-sample statistics. This review does not establish an economic edge or promotion eligibility.

## Actual historical result review

The independent reviewer reopened the completed run and recomputed all six account results using decimal arithmetic. Baseline net PnL was $2,291.466389147189204035; doubled-cost PnL was $1,673.572402140909799186. Each variant had five closed positions, no open exposure and only first-stage fills. All twelve loaded normalized-source hashes, four run bindings and the result completion digest matched. The parent separately recalculated the three event-response means from normalized hourly CSVs.

The absence of additions was checked against raw hourly prices and the existing router: four trades had no eligible confirmed post-fill H4 pivot before exit. The longest BTC trade formed its first eligible pivot at 113,576.1 on October 15, 2025 at 12:00 UTC, but produced no hourly addition confirmation before the continuation stop tightened below that zone. The three variants therefore provide no observed comparison of staging or macro gating.

Actual-output inspection found two reporting defects: closed short positions displayed stale nonzero gross unrealized PnL although account equity correctly excluded it, and event-response metadata incorrectly described intent conditioning. Both were corrected with regression checks and a retained, reasoned rerun. Neither repair changes signal, sizing, fills, exits or response arithmetic. The first completed output remains retained as exposed development evidence.

The reporting-fix rerun completed successfully. Exact comparison preserved all six account event-stream hashes and fill/exit records, equity curves, reconciliations and funnel counts, plus all response numbers. Corrected closed-position unrealized PnL is zero; response conditioning and direction-availability labels now match the anchor. A separate report review checked the narrative against compact results, including first-fill equity sizing, profit concentration and rounded intervals.

The parent also checked source-event collection against router state: `acceptDaily` emits a qualified event before its busy-position and prior-close checks. The broader shock-response inventory therefore does not silently discard events because a strategy position is open.

Final verification passed at 90.58% line and 80.20% branch coverage. All 47 focused fixtures ran without failures or skips, including policy binding, exact 60-day exit, and a real routed stage-two intent accepted by the shared account and followed by a tightening stop acknowledgment. A chronological fixture preserves stop-before-add behavior; the focused adapter fixture does not claim three historical tranches filled. The five production-file hashes still match the frozen executable used for the reported run.
