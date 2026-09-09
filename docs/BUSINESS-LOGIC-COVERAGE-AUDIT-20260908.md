# Business logic coverage audit — 2026-09-08

The suite is substantial and green, but business-rule assurance is uneven. The highest priorities are preserving published signals on validation failure, enforcing quote freshness, and exercising the physical evidence paths that determine research readiness and deployment eligibility.

## Scope and measurement

Reviewed the current working tree at HEAD `30262ac`, including existing modified and untracked Java sources. `git fetch origin` completed: HEAD is 20 commits ahead of origin/main and 0 behind. This is a working-tree audit, not a claim about the exact deployed or remote version. Existing work was preserved; I made no production-code changes. Additional edits to StrategyFixedBaselineV5 were observed during the audit, so coverage refers to the compiled snapshot used by the recorded test run; subsequent concurrent edits are not certified by these results.

- Full reactor `./mvnw --batch-mode --no-transfer-progress verify`: successful.
- Separate full reactor test run with `-Djacoco.destFile=/tmp/business-coverage-fresh-20260908.exec`: successful. A new execution file prevents old local coverage from inflating the result.
- JaCoCo 0.8.15 CLI combined that execution file with every module's production class directory. This credits tests in CLI and compatibility modules to the business code they execute. Ordinary per-module reports undercount those interactions.
- Surefire results: **1,018 tests, 0 failures, 0 errors, 1 skipped**. These are test-engine counts, not a count of independently verified business requirements.
- Three isolated Java probes used temporary directories and the compiled production classes; their outputs are recorded below. They were not included in coverage measurements.
- Coverage measures the instrumented test JVMs; external Java processes, live production workflows, and manual experiments are not proven covered. No mutation run was performed for this audit.

| Module | Line coverage | Branch coverage |
|---|---:|---:|
| Core calculations | 97.4% | 77.4% |
| Contracts | 95.7% | 89.7% |
| Market data | 93.8% | 68.3% |
| Reporting and positions | 88.8% | 64.3% |
| Infrastructure | 84.1% | 57.8% |
| Research | 73.7% | 49.3% |
| CLI and workflow orchestration | 41.0% | 22.0% |
| **Whole Java application** | **76.6%** | **51.8%** |

Overall: 48,317 of 63,095 executable lines and 33,640 of 64,936 branches covered. Nested classes are included. Research dominates the denominator, so the overall percentage is not a risk score.

## Prioritized findings

### 1. P1 — Strict signal export mutates the published feed before failing

**Confirmed behavior.** `ExportSignalsCommand.run` writes at line 87, then checks strict failures at lines 99–112. A missing post-epoch machine block, malformed report, or mismatched canonical pair can therefore yield a nonzero exit after replacing the prior feed. Downstream readers may observe a partial feed even though the command reports failure.

The strict failure helper in `ReportingPipelineNodeOracleTest.java:333` always supplies `--dry-run`. It asserts exit/output parity without exercising the publication side effect.

Isolated reproduction: seed an output with `LAST_KNOWN_GOOD`, add a post-epoch report with no machine block, then run strict export against the temporary directory. Result: `strict exit=1 previousFeedPreserved=false`.

**Improve:** complete strict validation before any write. Add non-dry-run tests asserting byte-for-byte preservation of an existing feed and absence of a newly created feed for each failure class. Keep successful no-op and atomic replacement tests.

Source: `analytics-reporting/src/main/java/com/tradinganalytics/reporting/ExportSignalsCommand.java:75`.

### 2. P1 — Unknown quote age can be reported as synchronized and confident

**Confirmed behavior.** `MarketSeriesAnalytics.spotPanel` places quotes with null or missing timestamps in its fresh collection. Two such quotes produce `n_synchronized=2` and `low_confidence=false`, even though their age is unknowable.

Isolated reproduction with two positive undated quotes: `undatedQuotes synchronized=2 lowConfidence=false`.

There is also a related fallback contract to resolve: `SpotSnapshotAssembler.java:65` falls back from an unavailable panel median to the first source, which may be a stale CoinGecko observation or a Yahoo daily close. The output discloses `priority_first_fallback`, but still calls the value `canonical`. The gold fallback test explicitly preserves this behavior. This is a design risk for consumers of the top-level field, not proof that every report ignores its accompanying warning/panel.

**Improve:** distinguish verified fresh quotes, unknown-age observations, and historical fallback prices. Require a defensible timestamp for synchronized status; if a source only provides retrieval time, label and handle that contract explicitly. Keep historical fallback values in a separate field and make trade eligibility depend on verified quote coverage. Add absent timestamp, all-stale, all-bar-close, mixed fresh/stale, future timestamp, and one-source boundary cases at the assembler-to-consumer boundary.

Sources: `analytics-market-data/src/main/java/com/tradinganalytics/marketdata/MarketSeriesAnalytics.java:54`; `analytics-market-data/src/main/java/com/tradinganalytics/marketdata/SpotSnapshotAssembler.java:63`.

### 3. P1 — Readiness and deployment evidence paths have low automated coverage

**Coverage gap, not a demonstrated authorization bypass.** Important boundaries are exercised much less than the arithmetic:

| File | Lines | Branches | Specific observation |
|---|---:|---:|---|
| StrategyReadinessV5.java | 63.4% | 27.5% | buildReadinessAuditV5 has 433 missed vs 81 covered branches |
| ProspectiveSnapshotVerifierV5.java | 6.8% | 2.9% | settings artifacts, trusted snapshot graph, and prospective ledger validation methods are unexecuted |
| StrategyV5WorkflowDeployment.java | 0.0% | 0.0% | makeDeploymentAudit and its verification helpers are unexecuted |

CLI tests cover useful early blocked/no-op states, but those do not establish that a complete evidence graph reaches the intended decision or that a subtly invalid graph is rejected at the right boundary.

**Improve:** construct one minimal complete, valid physical evidence chain using deterministic local files and injected external responses. Run it through snapshot verification, deployment audit, readiness, and activation checks. Derive invalid cases by changing one dependency at a time: artifact hash, source revision, scope, expiry, replay identity, revocation, approval identity, permission set, and ledger head. Assert both decision and publication/ledger side effects. No live deployment is necessary.

Sources: `analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyReadinessV5.java:151`; `analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/ProspectiveSnapshotVerifierV5.java:148`; `analytics-cli/src/main/java/com/tradinganalytics/cli/StrategyV5WorkflowDeployment.java:46`.

### 4. P1 — Fixed-baseline economic evaluation is largely outside the test suite

**Coverage gap.** `StrategyFixedBaselineV5.java` has 26.3% line and 20.4% branch coverage. Its `evaluate` method has 116 missed and zero covered branches. `resolveOutcome`, `validatePhysicalCosts`, and `validateResolvedPhysicalCosts` also show no covered branches. This includes current working-tree work; manually produced evidence does not replace regression coverage.

The skipped test, `StrategyEvaluatorV5NodeOracleTest.verifiedPhysicalNullSelectionMatchesTheOriginalNodeEndToEndFixture`, requires an old temporary Parquet lake that is not checked in. Its comment explicitly anticipates skipping on clean CI machines.

**Improve:** generate a tiny reproducible physical dataset in the test directory. Exercise baseline evaluation through real lifecycle and cost resolution, with independently calculated results for one winning trade, one stopped trade, nontrading intervals, unavailable outcomes, late evidence, fees/funding, and duplicate-attempt handling. Make the physical null-selection fixture portable and mandatory. These should be bounded regression fixtures, not a fresh strategy search or calibration.

Sources: `analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyFixedBaselineV5.java:516`; `analytics-research/src/test/java/com/tradinganalytics/research/v5/StrategyEvaluatorV5NodeOracleTest.java:212`.

### 5. P2 — Calculation and report validation disagree about valid trigger age

**Confirmed helper behavior, with downstream mitigation.** `SwingPhaseRisk.triggerWindow` accepts negative age because it checks only the upper bound. The probe returned `negativeAgeTrigger=VALID`. `activePhase` also lacks a lower-bound check. The helper is used by compute and swing calibration code.

However, `ReportMachine3Semantics.java:247` rejects negative and nonintegral age. This is an inconsistency between consumers; it is not evidence that a valid published v3 report can bypass that check.

`SwingPhaseRisk` already has 100% line and 96.9% branch coverage, illustrating why execution percentages alone do not prove rule correctness.

**Improve:** share the completed-bar freshness validation between calculation and reporting. Add -1, fractional, NaN/infinite, zero, exact-window, and just-expired cases with an explicit missing-age policy. Require helper outputs to agree with report validation.

Sources: `analytics-core/src/main/java/com/tradinganalytics/core/swing/SwingPhaseRisk.java:81`; same file at line 153; `analytics-reporting/src/main/java/com/tradinganalytics/reporting/ReportMachine3Semantics.java:247`.

### 6. P2 — Coverage is reported but not enforced for most business rules

The parent POM prepares JaCoCo and creates reports, but declares no `jacoco:check` thresholds or reactor aggregate report. CI has mutation thresholds for four infrastructure/CLI targets: public-data smoke, writer installation, attestation signing, and settings capture. It does not apply mutation gates to phase risk, report authorization, lifecycle PnL, portfolio limits, or readiness.

Parity and frozen-output tests are useful migration protection, but can preserve shared bugs. The strict export and quote-age findings are examples of behavior needing an independent business assertion.

**Improve:** publish a combined reactor report in CI and establish non-regression branch floors per risk-bearing package. Add focused mutation jobs for phase thresholds, stop/carry rules, lifecycle PnL, and publication semantics. Establish baselines first; do not apply an arbitrary global 90% target to legacy and orchestration code. Require each hard rule to map to an owning validator and at least one acceptance, boundary, and rejection test.

Sources: `pom.xml:147`; `.github/workflows/java-migration.yml:28`.

## Additional business-rule coverage to prioritize

- **Position safety:** PositionSnapshots has 93.3% line and 73.1% branch coverage, with useful custody/basis tests. Add consumer-level cases ensuring UNEXPLAINED custody and unreliable basis cannot become authoritative sizing inputs; distinguish real off-venue holdings from synthetic opening balances. This is a recommendation to strengthen cross-layer guarantees, not a newly demonstrated position bug.
- **Lifecycle accounting:** TradeLifecycleV5 is 58.6% branch-covered and StrategyPortfolioRiskV5 is 71.4%. Prioritize same-bar stop/target ambiguity, partial exits, short funding sign, gap fills, overlapping exposures, and exhausted budgets. Use small hand-calculated expected outcomes and conservation invariants.
- **Report authorization:** preserve existing schema, pair-round-trip, and property tests; add scenario matrices across framework, channel, phase, custody, veto, and fill status. The most valuable assertion is that unauthorized state never becomes an active exported signal.

## Suggested implementation order

1. Fix strict-export write ordering and add failure-preserves-output tests.
2. Resolve quote freshness/fallback eligibility and unify trigger-age validation.
3. Add a portable physical-evaluation fixture and a valid deployment/readiness evidence-chain fixture, followed by single-fault rejection cases.
4. Add aggregate coverage reporting, business-rule mutation jobs, and per-package regression floors.

This audit does not change trading thresholds, tune a strategy, or recommend market positions.

## Local evidence

- Build log: `/tmp/business-coverage-20260908.log`.
- Fresh instrumented test log: `/tmp/business-coverage-fresh-20260908.log`.
- Aggregate coverage XML: `/tmp/business-coverage-aggregate.xml`.
- Browsable coverage report: `/tmp/business-coverage-html/index.html`.
- Isolated reproduction source: `/tmp/BusinessCoverageProbe.java`.

Temporary evidence files are local and may be removed by OS cleanup. The measured results and reproduction observations are retained in this audit.
