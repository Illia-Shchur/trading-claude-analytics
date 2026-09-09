# Independent implementation review

Date: 2026-09-05. Reviewer: parent agent. Implementer: Luna xhigh.

Status: implementation in progress; no overall approval yet.

## Checks to close before approval

- Build inputs include top-level `schemas/` and every compiled resource/build input; staged source changes count as dirty.
- Build identity names the actual packaged executable and compiled inputs, including when launched directly from a different directory. Historical Node-oracle hashes retain their original meaning.
- Spring Boot marker lookup handles `BOOT-INF/classes/`; nested code-source URIs resolve to the actual JAR.
- Fresh, failed, concurrent and interrupted builds are safe. Lock cleanup handles stale/empty locks; lock is outside cleaned target; deleted source classes cannot survive a rebuild. Maven runs in the correct directory with logs on stderr.
- Fixed baseline freezes exact numeric entry, lifecycle, cost, sizing, filters, warmup and control rules before any outcome inspection, using a registered additive schema and canonical hash.
- Timeout units agree. Ordinary stop fills use the stop/gap execution rule, not a future bar low masquerading as the executable trigger price.
- Controls are downside bars, selected deterministically from available setup data. Future execution coverage/outcomes do not determine matching or event eligibility; missing required evidence fails evaluation explicitly.
- Historical controls' maximum scheduled lifecycle does not overlap the treated event. Matching lookback and lag must allow this (a seven-day maximum lag cannot accommodate a ten-day lifecycle).
- Perturbing future outcome data leaves pre-decision signals and control selection unchanged.
- Fixed/refinement evidence cannot bypass full promotion, and historical family exposure cannot reset.
- Physical result and repeat semantic hashes are verified independently; trade/portfolio math reconciles.
- Simulation evaluation measures its actual stage, false positives and power with uncertainty; it does not promote a fixture or claim full adaptive calibration from fixed-rule tests.
- Prospective verification distinguishes dormant green workflows from completed evidence cycles; no fabricated remote approval or deployment claim.
- Complete required tests pass with explicit counts/skips and unresolved limitations. Historical records remain unchanged.

## Independent evidence collected so far

- Read-only remote inspection: `docs/RESEARCH-DEPLOYMENT-AUDIT-20260905.md`.
- All eight spot 4h Parquet files referenced by `strategy-research/v5-records/data-raw-replay/parquet-31f85e351861f05edc66794ec57369c0a7fafe67e236676aab5c6cc7bec4cfc6.json` physically exist and their SHA-256 values match. Each manifest reports 10,957 rows. This check establishes file identity only, not full PIT/coverage or executable strategy evidence.
- A temporary Git fixture independently exercised the draft fingerprint helper: source and schema byte changes with preserved timestamps changed the fingerprint correctly. A staged source filename containing a space incorrectly produced `build.dirty_source=false`; sent to Luna for correction and regression coverage. Full-checkout fingerprint runtime was approximately 7.8 seconds on this machine.
- Draft operating-characteristic code generated returns and evaluated a separate fixed-threshold z-test rather than calling the actual baseline execution/decision pipeline. It was rejected as evaluator-validation evidence. Its planted-edge interval also used the complement of the reported power. Package D must replace that draft, not relabel it as completed calibration.

The items above include observations on unfinished drafts sent to Luna before outcome exposure. They are review obligations, not a claim that every draft defect survives in the final implementation.

## Parent build checks

An isolated temporary source snapshot compiled and packaged successfully with `./mvnw -q -pl analytics-cli -am package -DskipTests`. Actual `java -jar ... build-identity` from `/tmp` identified the outer Spring Boot JAR and its independently verified SHA-256. The unchanged launcher returned the same fingerprint and executable.

A schema-byte edit with preserved modification time then triggered one successful rebuild shared by two concurrent launcher calls. Both returned the same new JAR/fingerprint, valid JSON stdout and no leftover lock (63.4 seconds on this machine). Deliberately invalid Java source caused exit 1, empty command stdout and no stale execution; cleanup removed the lock. An earlier snapshot hung on an empty interrupted lock; Luna subsequently added recovery.

The parent added `BuildFreshnessReviewTest.java` to retain independent regression coverage. All seven tests passed in the isolated snapshot using `./mvnw -q -pl analytics-cli -am -Dtest=BuildFreshnessReviewTest -Dsurefire.failIfNoSpecifiedTests=false test`. They cover preserved-mtime source/schema edits, staged filenames with spaces, deleted source, Maven configuration, empty locks and dead-owner locks, including a failed build that deliberately leaves an old JAR present. The latest whitespace/empty-lock corrections passed these checks.

These are package-A checks on a source snapshot; final shared-checkout full-suite verification and remaining packages are still required.

## Prospective review checkpoint

Existing tests already cover completed-bar signal/no-op handling, atomic outcomes, physical tampering, incomplete outcome inputs, CAS/fault recovery, replay and publication signatures. Package E should extend these contracts rather than replace the custody machinery.

`StrategyProspectiveV5.validateOutcomeArtifacts` verifies physical byte identities, lineage and later resolution/receipt timestamps. It does not itself recompute the outcome from the execution lifecycle or reconcile numeric P&L. The enclosing producer and consumers must therefore be reviewed before describing this as verified economic evidence. A new reconciliation contract should establish that distinction without rewriting historical records. Explicit tests are also needed for changed candidate identity on a repeated bar, outcome maturity, missed-bar recovery and stale inputs.

## Fixed-baseline draft review checkpoint

The first `StrategyFixedBaselineV5` draft invokes the production `TradeLifecycleV5`, which is the intended shared execution route. The following findings were sent to Luna before acceptance:

- Bind feature derivation to physical base bars and frozen definitions; hashing caller-supplied precomputed values does not establish point-in-time correctness.
- Anchor the exposure head to the canonical family/history and durably account for attempts. A supplied structurally valid head is insufficient.
- Separate the actual event portfolio from the counterfactual control book. Evaluate unmatched events for unconditional results while reporting missing paired evidence explicitly.
- Exclude already-used controls before selecting the next nearest admissible match.
- Bind the exact supplied baseline/control hashes to the experiment, not matching filename substrings; reject `activation.authorized=true` explicitly.
- Compute dependence-aware paired uncertainty. A pair's two endpoints cannot be counted as two independent observations merely because their time windows do not overlap.
- Keep costs, filters, sizing and lifecycle consistent between the frozen spec and opened receipts. Opening labels before setup/control selection contradicts the stated separation and must be removed or accurately explained.

These findings concern an unfinished draft and remain subject to final verification.


A separate parent snapshot containing the initial fixed runner and extracted infrastructure build service passed `./mvnw -q -pl analytics-cli -am clean package -DskipTests`. Direct JAR identity from `/tmp` also passed. This establishes compilation/integration only; the baseline review findings and physical-run acceptance remain open.

The parent added `StrategyFixedBaselineReviewTest.java`: six independent synthetic tests of the fixed trailing formula, invariance under future price/volume changes, missing/duplicated warmup bars, a late prior dependency and a decision claimed before the source bar closes. All six passed on the latest isolated source snapshot using `./mvnw -q -pl analytics-research -am -Dtest=StrategyFixedBaselineReviewTest -Dsurefire.failIfNoSpecifiedTests=false test` (zero failures/errors/skips, 0.322 seconds in Surefire). This verifies the setup boundary, not physical execution or statistical calibration.

Public transport was checked without opening market outcomes: HEAD requests for the BTCUSDT August 2021 spot 1m archive and Binance BTCUSDT exchange-info both returned HTTP 200. Current metadata availability does not establish historical filter validity.

## First physical integration slice

Luna reported a deliberately frozen one-event BTC integration slice using the verified producer and 14,400 public one-minute bars. It is not the declared eight-asset experiment. The hydration receipt is `strategy-research/v5-records/receipts/fixed-baseline-public-1m-8942b85eaad63c4dd84dbb3c09a5dd12424ff5aec74cc38b773d80d8248e841d.json`; the inspected temporary result is `/tmp/fixed-physical/fixed-baseline-result-6.json`. It contains one event trade, no matched control and one independent episode, with `INSUFFICIENT_EVIDENCE` and retrospective `USER_BOUND` limitations. Full inventory and explicit scope validation remain required.

Independent Decimal arithmetic using quantity 0.02143, entry 46656.07, exit 43856.705799999996, fees 0.001 per side and slippage 0.0005 per side gives net P&L -62.899907984091085591420 USDT. The reported -62.89990798409108 differs by 5.6e-15. Trade arithmetic passes.

An independent scan of the bound one-minute bars finds the first low at or below the 43856.7058 stop in the September 13 14:18 UTC bar (open 44200, low 43700). That matches the lifecycle's STOP/BARRIER bar and price. This verifies the OHLC execution convention, not an observed exchange fill or precise sub-minute fill time.

The slice's portfolio curve does not yet pass: it starts at zero and books the final realized loss at the September 7 entry even though the trade exits September 13. This is neither a funded equity curve nor a correctly timed realized-P&L curve. Luna was asked to correct cash/holdings/marks and freeze capital assumptions; existing slice bytes must remain preserved as development evidence. The runner must also distinguish this integration slice from the required eight-asset experiment.

### Subsequent local portfolio correction

The implementation now has an additive post-slice capital policy, exit-time realization and physical close marks. Parent added three independent ledger regressions: an interim cash deficit cannot be hidden by positive final equity, capacity costs reduce cash consistently with net P&L, and an adverse interim mark produces drawdown before realization. Together with the six setup tests and Luna's four implementation tests, all 13 focused tests passed in the isolated snapshot on September 6 (`-Dtest=StrategyFixedBaselineReviewTest,StrategyFixedBaselineV5Test`, zero failures/errors/skips). Parent accepts these tested local fixes subject to actual runner/full-experiment integration. The old slice remains evidence of its original implementation, not a corrected portfolio result.

### Packaged output boundary

Luna added explicit Picocli stream flushing. Parent rebuilt the isolated CLI and ran a synthetic 1,000-trade `strategy-research-v5 portfolio-reconcile` command through the actual executable JAR. Its 102,225-byte stdout parsed as complete JSON; all 1,000 rows, final signal `999`, and the expected net P&L were present. This independently verifies the large-output fix. The previously reported `research fixed-baseline` invocation was rejected by Picocli; the correct facade is `strategy-research-v5`, and Luna was asked to correct all retained instructions/scripts.

## Full inventory and unresolved review findings

The setup scan reported 130 qualifying opportunities across eight assets. Its provisional scheduled-horizon occupancy admitted 104 and skipped 26. All 130 execution windows were subsequently requested: 126 complete windows and four gaps. Actual-exit chronological admission is still required; the provisional schedule must not silently become the frozen strategy's admission rule.

The zero-control output exposed a wiring defect: the selector requires `position_state=FLAT`, while generated feature/pool rows did not supply that field. Explicit counterfactual book eligibility and an integrated matching fixture are required; manually populated helper tests did not detect this failure. Control-book overlap semantics must be derived and disclosed, not represented as observed real-account positions.

The first full-run statistical outputs were rejected. `p20` was taken from individual trade R values rather than the bootstrap distribution of mean expectancy. The purported block bootstrap sampled individual trade returns and did not use the supplied cluster membership. Correct cluster resampling, independently tested uncertainty, and distinction from family-wide max-statistic correction remain required. Uniformly duplicating observations within the same clusters must not manufacture additional independent evidence. Pair clustering must consider the two actual holding intervals, not fill the unobserved gap between a historical control and its event.

Subsequent independent regression checks passed the corrected expectancy quantile and within-cluster duplication invariance, but first caught another failure: positive paired improvement could pass when the event strategy itself lost money. After the joint-hypothesis correction, all 19 focused tests passed in the isolated snapshot (parent 12, implementation 7; zero failures/errors/skips). Parent accepts those tested local corrections. This does not validate the earlier reported market significance, family-wide correction, full-run control coverage or operating characteristics.

### Independently verified exchange closure

The ETHUSDT September 2021 Binance monthly archive contains the same 120 missing one-minute bars from September 29 07:00 through 08:59 UTC. ZIP SHA-256 `5bbfb9f41522b2fd1472e4f4b164a5ba4711b02aab37eebe0b2b25a1286c90a9` matches Binance's published CHECKSUM. Local verification is under ignored `.report-run/parent-source-gap-check/`.

Binance's [September 29 upgrade notice](https://www.binance.com/en/support/announcement/detail/e2f674fc961d48af9b28edd82896607c), published September 28, confirms spot-trading suspension from 07:00 UTC with an estimated two-hour duration. This is a venue closure, not merely a REST retrieval failure. No candles should be fabricated. Independent ETH replay found entry 2827.97 and stop 2658.2918, with no stop touch before the closure, so its interruption cannot be discarded as post-exit missing data. The original continuous-bar execution contract must retain its limitation; any closure-aware execution policy requires an explicit versioned amendment rather than silently changing the exposed experiment.

### Full physical computation and arithmetic checkpoint

The closure-aware v14/v15 computations retained under `/tmp/fixed-all-physical-v2/` have matching economic semantic hash `e191bfe243c3d81e685e32c03f524bfb0ef10f56f6562fcd73fd2ba182026273`. They contain 130 opportunities, 126 admitted event trades, four open-position skips, three matched control trades and no unresolved executions. They remain diagnostic `INSUFFICIENT_EVIDENCE`: 28 event clusters and three paired clusters are below the frozen 30-cluster minimum. A complete physical computation is not a statistically adequate experiment or promotion evidence.

Parent independently recomputed every trade with Decimal arithmetic, using raw entry/exit prices, quantities and the frozen per-side 0.001 fee and 0.0005 slippage rates. Event net is `1207.4237695607173044` USDT; control net is `291.6628255058353851` USDT. Maximum per-trade residual is `1.6e-13`, and ending-equity reconciliation residual is below `1e-11`. Arithmetic is accepted for this retained computation. The control book is counterfactual; its P&L is not added to the event strategy's performance.

Final acceptance still requires the typed closure policy to be checked against the actual bound contract, canonical legacy-family exposure disclosure, physical setup producer binding and date coverage enforcement, and the complete test suite. Further parent regressions cover entry-cost drawdown and availability of the oldest return denominator; these were missed by the earlier fixtures. Remaining C–F packages are still pending.

The two new parent regressions failed as predicted in an isolated snapshot: 23 focused tests, two failures, zero errors/skips. Separately, all 16 existing lifecycle/prospective custody and runner tests passed (`TradeLifecycleV5NodeOracleTest`, `StrategyProspectiveV5NodeOracleTest`, `StrategyProspectiveRunnerNodeOracleTest`). This confirms compatibility for those tested paths, not the unfinished new prospective capabilities.

Additional control review found that a single occupied-until watermark excludes all earlier controls, including non-overlapping ones, when later events select historical controls out of time order. The frozen policy requires overlap/reuse exclusion; it does not require matched control dates to increase with event dates. Luna was asked to retain selected scheduled intervals and test actual overlap instead. Prior outputs remain implementation-version evidence if the corrected matching changes them.

Legacy source inspection finds `FK_DELEVERAGING_ABSORPTION` in the nested `definition.setup_families` of 4,935 retained candidate rows across 13 legacy runs, with 1,375 distinct retained behavior hashes. These identities establish prior search exposure, but are not automatically an additive v5 effective K; migration/deduplication must be explicit. A scan limited to top-level family fields under `v5-records` misses these records.

Parent independently rebuilt the downside control pool and frozen matching variables/calipers in Python from all 87,656 signal bars. Both the old watermark and correct interval-overlap exclusion choose the same three real pairs for the retained admitted events: LINK June 10, 2023 versus March 11, 2023; XRP April 13, 2024 versus March 16, 2024; SOL April 3, 2025 versus May 9, 2024. The general overlap defect still needs a regression fix, but does not change these matches. This independently confirms sparse matched evidence in this dataset. The check and exact identities are under ignored `.report-run/parent-control-overlap-check.py` and `.json`.

### Subsequent boundary fixes verified

Parent added a retained legacy-alias regression and four independent `TradeLifecycleClosureReviewTest` cases using real physical trust tokens around synthetic data. They verify the reopening `GAP_OPEN` price, rejection of untyped interval arrays, rejection of a closure policy against another venue's bound contract, and rejection when the original wall-clock deadline falls inside a closure.

The focused shared-checkout command `./mvnw -q -pl analytics-research -am -Dtest=TradeLifecycleClosureReviewTest,StrategyFixedBaselineReviewTest,StrategyFixedBaselineV5Test -Dsurefire.failIfNoSpecifiedTests=false test` passed all 28 tests (parent baseline 15, parent closure 4, implementation 9; zero failures/errors/skips). This also establishes red-to-green correction of entry-cost drawdown and the oldest dependency, and recognition of the actual uppercase/underscore legacy alias. Parent accepts these local fixes. Physical producer rederivation, resolved metadata validation and final integration evidence remain under review.

Parent subsequently copied a stable packaged JAR (`ff277e85470d3fde87a4723c17f31f2e1581cc98dd26c94a27e02ba211c64d7e`) and independently altered one physical signal volume while correctly recalculating every signal-role, producer-receipt and physical-input hash with the project's canonical JSON implementation. The original pinned Parquet stayed unchanged. The actual packaged `strategy-research-v5 fixed-baseline` command returned `BLOCKED`, `PIT_SETUP_UNAVAILABLE`, specifically `signal role differs from recomputed verified Parquet producer output`. It failed before exposure append. This verifies rederivation against source values, rather than merely detecting stale hashes or missing files. Parent accepts the source boundary; the runner is currently pinned to this one declared source dataset, and generic dataset support must not be claimed. The diagnostic receipt is under ignored `.report-run/parent-rehashed-source-negative.json`.

### Package B milestone accepted

The retained v20/v21 full computations under `strategy-research/v5-records/evidence/fixed-baseline-full-declared-v004/` have matching economic hash `1e4d63e3cabaeaaa71a09d2106580218c2c3b998693d88927d7050ca7777bb08`, matching provenance semantic hashes and stable attempt K=32. Their complete physical inventory is unchanged. Both honestly report unresolved legacy lineage and insufficient independent samples. Parent accepts the B diagnostic-computation milestone subject to final integration verification. C/F still need measured runtime, a durable full-input assembly workflow, current replay instructions and the requested metadata-dispatch regressions. No strategy promotion or overall task completion is implied.

## Package C refinement review

The initial refinement implementation exposed its threshold override on the public fixed-baseline route. With an anonymous override, execution could change while stage and behavior accounting still described the original baseline. Parent rejected this and Luna stopped the initial refinement attempt. The corrected public entry point rejects all refinement overrides; the verified refinement route calls a private internal path. Member identities are bound to R1=-0.08, R2=-0.10 and R3=-0.12, and behavior accounting binds effective parameters rather than labels.

Parent added `StrategyRefinementReviewTest`. All nine tests passed in an isolated source snapshot with `./mvnw -q -pl analytics-research -am -Dtest=StrategyRefinementReviewTest -Dsurefire.failIfNoSpecifiedTests=false test` (zero failures/errors/skips, 0.810 seconds). They cover a valid freeze, conflicting budget, reset/activation requests, duplicate behaviors and reassigned labels, anonymous/named public overrides, and malformed final-member validation before earlier result files are written. Parent accepts these tested fixes.

Remaining C review requires physical result/lineage checks and interrupted/completed resume. A frozen starting exposure hash cannot be required to equal the mutable current HEAD after the stage's own appends; the implementation must verify an append-only descendant of the bound starting snapshot. Physical input and predecessor contract hashes also need stage binding, beyond their individual hash integrity. C is not yet accepted.

The independent refinement suite was extended to 14 cases. Thirteen pass, including valid own-append descendants, rejection of counter resets/divergent history, and a simple new-dataset retry. The interleaved-dataset case fails: after behavior A/data A and behavior B/data B, behavior A/data B must increment attempt K to 3 while behavior K remains 2; the implementation returned attempt K=2. A global current dataset plus an old behavior entry cannot prove that this behavior was evaluated on that dataset. Luna is correcting durable pair accounting and its atomic resume boundary before final C acceptance. Log: `/tmp/analytics-parent-refinement-interleaved-review.log`.

Parent independently compared the retained draft refinement run under `refinement-results-v004`: R1's event/control/skipped/trade arrays, portfolio, metrics and disposition exactly equal the accepted B result. R2 and R3 opportunity inventories equal the exact stricter subsets of all 130 baseline opportunities (including baseline admission skips): 65 and 29 respectively. These checks support the economic implementation, but do not accept the still-changing exposure custody. Final member metadata must show effective thresholds, and final results/resume must be verified against the corrected executable.

### Atomic pair accounting correction

To let Luna implement D/E/F in parallel, parent took the bounded registry correction with Luna's agreement. Optional `fixed_attempt_pairs` now live inside the authoritative exposure HEAD and share its existing lock, predecessor CAS and atomic replacement. Ordinary statistical appends preserve the pairs. A fixed attempt is deduplicated by behavior plus dataset, while cumulative behavioral K remains unique. The short-lived sidecar is migrated once only when its own hash, family and current HEAD binding validate; its original bytes remain unchanged and the migration digest is retained in HEAD. Subsequent writes do not depend on a stale sidecar.

The isolated command `./mvnw -q -pl analytics-research -am -Dtest=StrategyRefinementReviewTest,StrategyStatisticalV5NodeOracleTest,StrategyStatisticalV5PropertyTest -Dsurefire.failIfNoSpecifiedTests=false test` passed 51 tests: 19 reviewer, 27 existing Node-oracle and 5 property tests, zero failures/errors/skips. New checks cover a competing writer without partial state or deletion of its lock, migration without another attempt, an unknown behavior inserted into a rehashed pair, and loss of frozen pair history. A subsequent twentieth reviewer test covers another stage changing the current dataset while the refinement remains bound to its own immutable starting dataset; all 20 reviewer tests subsequently passed in an isolated rerun (`/tmp/analytics-parent-refinement-final20.log`). Luna must review the parent's production change and complete packaged physical/resume integration before C acceptance.

Parent independently reconciled all 225 trades in refinement run9 (R1 129, R2 67, R3 29) with decimal arithmetic from raw entry/exit prices and quantity, frozen fees/slippage and explicit capacity debit. Maximum residual was 1.6e-13 USDT, and event/control book sums reconcile. Event net results are approximately 1,207.42 / 429.21 / 521.00 USDT respectively; these descriptive development results do not repair the sample-size or legacy-lineage limitations. Run9 uses effective threshold metadata and typed dispositions and measured 434.7 seconds across all three members, but predates the atomic registry correction. The independent arithmetic receipt is `.report-run/parent-refinement-run9-arithmetic.json`.

Parent also exercised the assembly helper in an isolated temporary fixture using the actual p7 JAR's canonical-hash command: fresh nested output, identical-byte retry, changed-role overwrite refusal and temporary-file cleanup all passed. This tests the helper, not the still-required complete public-input reconstruction recipe.

## Package D generator and running experiment

The corrected prefix (`/tmp/d-v004-prefix-corrected4.json`, content SHA `afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`) completed two replications with ten events, ten pairs and eight independent units each. Both are insufficient against the fixed minimum of 30; rates and Wilson bounds are unavailable, as required. Internal runtime is 26.1126 seconds; external wall timing was approximately 27.9 seconds. This is performance/setup evidence, not power or false-positive calibration.

Parent's eight `StrategyOperatingCharacteristicsReviewTest` cases pass against the frozen generator. They verify completed setup timing, shock/volume/RV derived from raw OHLC, identical prior matching state, rejection of a rehashed changed market process, independent Wilson anchors, unavailable rates for incomplete trials, full/prefix dependent sample geometry, and exact planted drift along generated future paths under controlled identical noise. Parent independently reconciled eight representative prefix trades (maximum net residual below 1e-13 USDT) and four contiguous 240-minute setup samples back to the declared -4%/-9% returns.

The initial detached full launch died without output. The actual full run is the foreground process using executor SHA `dec4470d815d05f4eb56b871d4b07657745c06952ac802991e7b6b7a11e9d20c`, source SHA `1927d082ab0af71d694bfda91ef76b3f96851d1be5d125ebc75ddf07f1fdf569`, and the additive v004b source-freeze receipt. Parent independently verified the executable hash and archived the matching source as `executors/StrategyOperatingCharacteristicsV4-v004b-frozen.java`, preserving the prior ed74b5f7 source. The freeze discloses earlier draft-prefix outcome exposure; it must not be described as pristine pre-exposure calibration. A v004c metadata erratum corrects a transcribed prior-prefix hash without changing the actual run binding. At this checkpoint full results were pending; the completed result is audited below. Independent five-second RSS samples are retained under ignored `.report-run/parent-d-full-rss.csv` and its summary.

## Additional timing boundary found during E review

The v002 timeout prose says next executable open after the horizon, whereas the retained production lifecycle uses the final in-horizon bar close (`TIME_STOP_CLOSE`). Its `time` is the bar-open identifier: for example, an ADA horizon ending September 18, 2021 at 08:00 uses the final minute's close but stamps 07:59. Fixed portfolio cash/admission currently consume that stamp. Parent requested an additive, explicitly post-exposure timing erratum and conservative price-availability handling, preserving old evidence and distinguishing bar identifiers from usable execution information. BARRIER stops need the same availability review; a genuine GAP_OPEN is available at the open. Final B/C acceptance remains conditional on resolving this boundary, not merely documenting it.

## Package E numeric review

Parent's ten `StrategyProspectiveReconciliationReviewTest` cases passed in an isolated snapshot. They verify decimal-checked paper arithmetic, missing observed evidence, the authoritative root maturity clock, duplicate IDs, rejection of net-only observed claims, wrong reported P&L, typed numeric inputs, unsupported funding/currency, legitimate observed execution deltas and false observed profit. The initial implementation's clock override and unverified observed net were corrected.

The physical integration is not yet accepted: checking a caller-authored reconciliation's hash/status/source-hash strings does not prove that its numeric rows came from the reopened sources. Parent added `StrategyProspectiveNumericCustodyReviewTest`, requiring rejection of internally consistent fabricated numbers over the legacy opaque-source fixture. The new route must reopen typed numeric source data, recompute the result and bind actual outcome/decision times and identities. Legacy opaque evidence remains read-compatible but cannot become economically verified merely by attaching a calculator result. Observed actual-fill prices must also be distinguished from reference prices plus modeled slippage/impact debits to avoid double charging execution costs.

### Typed forward-source and availability review

The additive lifecycle availability fix passes all four independent `TradeLifecycleAvailabilityReviewTest` cases: an early caller timestamp cannot make a close-derived barrier available at its open; Binance's inclusive end-of-minute timestamp maps to the completed boundary; genuinely later publication remains later; an open gap uses open information. The timing erratum still needs the final physical B/C run.

The upgraded prospective route reopens typed input, label and execution artifacts and rejects both a rehashed false result and a changed input price against unchanged execution source. A valid typed physical fixture appends and retains the numeric input's byte hash. However, the first independent `StrategyProspectiveTypedBoundaryReviewTest` run exposes three remaining boundaries: observed fills were not bound to an observed source role, entry could precede the decision, and numeric trade resolution could be later than its outcome's resolution timestamp. The focused isolated suite ran 21 tests with these three expected failures and no errors/skips (`/tmp/analytics-parent-e-boundary-review.log`). Luna is correcting them; E remains under review.

The revised physical numeric path subsequently passed all 23 focused parent checks (ten arithmetic, eight typed physical boundaries, one opaque-source rejection and four availability tests). Typed observed rows and price convention are now bound to the execution source; asset and causal trade times are checked against the governed cycle.

Review then found the public authoritative prospective runner forwarded only signal inputs, so a direct Java integration test did not establish an operable delayed-outcome path. Luna added governed outcome forwarding and outcome-only append. Parent built canonical physical command fixtures and exercised the actual command adapter: a signal can receive its mature outcome after a newer signal; corrupted numeric bytes, changed outcome receipts and invalid sources leave the ledger unchanged. The first command review exposed missing schema fields for the new no-op receipt, which Luna corrected. Exact original-signal identity, complete outcome dependency inventory and the final combined command rerun remain under review. The legacy low-level oracle fixture needed adaptation to canonical reservation/source/candidate/evaluator schemas; those test-fixture defects were corrected without loosening production schemas.

Luna independently reviewed the parent's C atomic exposure-head change and reported no blocking finding: pair history stays within the existing lock/CAS/atomic write, one-time migration is bound, ordinary appends preserve history, and interleaved dataset tests cover the earlier defect. Final packaged physical/resume testing remains required.

### Governed prospective command acceptance

The final isolated focused command passed all 66 tests with zero failures/errors/skips: `./mvnw -q -pl analytics-research -am '-Dtest=StrategyProspective*Test,TradeLifecycleAvailabilityReviewTest,StrategyFixedBaselineTimingLedgerTest,TradeLifecycleTimingAvailabilityTest,ResearchSchemaRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false test`. Log: `/tmp/analytics-parent-e-governed-final-review.log`; class totals: `.report-run/parent-e-governed-test-summary.json`.

The seven parent command cases establish signal-first/mature-outcome-later, an older outcome after a newer signal, identical outcome retry without ledger mutation, re-opening numeric bytes on retries, changed receipt rejection, invalid source rejection without an outcome append, and exact original signal identity both before append and on retry. The command receipt retains all seven outcome dependency byte hashes. Eight parent typed-boundary tests additionally establish sourced observed-fill arithmetic, cross-asset rejection and causal timestamps. Existing prospective Node compatibility and additive schema checks remain green. Parent accepts E as a locally tested operational capability, subject to the final reactor. No real prospective cycle, governed deployment or real observed fill is claimed.

The independent metadata audit invoked the immutable p7 Java canonical-hash command over 37 small retained JSON artifacts. Current v004 plans, source-freezes, timing and fixed/refinement metadata self-hash correctly. The two historical v002/v003 D plans (and their copied evidence files) have mismatched declared self-hashes; their bytes must remain preserved and explicitly classified as invalid historical draft metadata. They are not the binding v004 plan used by the running full simulation. Audit detail: `.report-run/parent-retained-hash-audit-summary.json`.

### Transport and full-plan declaration boundaries

Four independent Python tests in `test/python/test_fixed_baseline_hydration.py` exposed two transport bugs: applying the September 2021 closure's missing-minute allowance to unrelated later windows, and accepting a truncated existing array as a completed immutable cache. Both were corrected. `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test/python -p 'test_fixed_baseline_hydration.py'` passes all four cases, including the exact 120-minute closure and rejection of an undeclared gap. These are network-free synthetic transport tests; the complete fresh public reconstruction still needs execution.

A ninth independent D regression showed that a rehashed 60-minute horizon could pass preflight while the specialized generator still executed its hardcoded 14,400-minute horizon. The adapter is now explicitly pinned to the exact reviewed v004 plan hash. The nine parent generator tests and two implementation V4 tests pass independently (`/tmp/analytics-parent-d-plan-completeness-final.log`). The running frozen JAR already uses the correct exact v004 plan and remains untouched.

### Packaged timing/resume verification in progress

Parent independently verified executor `06ae9c89a90ddf6e899909875c69d2c4258d5423d1b6a6cd61a4d18e573a4bed` and launched the full original physical input against it. The first invocation, deliberately limited to a 3 GB heap to preserve concurrent simulation headroom, exhausted Java heap during role canonicalization after 121.46 seconds (maximum sampled RSS 3,334,340,608 bytes). It produced no economic result and left the authoritative HEAD byte-identical at K=3, attempts=36. This is a resource failure, not an economic result. The failed log and audit remain under `.report-run/parent-bc-timing-v001/`. A new 4 GB invocation is running under `.report-run/parent-bc-timing-v002/`, followed by an intentional first-member interruption, partial resume and complete retry.

### Packaged timing amendment and interrupted refinement acceptance

The independent packaged run at `.report-run/parent-bc-timing-v002/review-audit.json` passed using executor SHA `06ae9c89a90ddf6e899909875c69d2c4258d5423d1b6a6cd61a4d18e573a4bed`. The reviewer terminated refinement after R1 completed, resumed R2/R3, and retried the completed aggregate. Completed member bytes and modification times remained unchanged; the completed retry also preserved aggregate bytes and time. The atomic migration added the explicit baseline attempt pair without changing K=3 or exposure attempts=36; subsequent interruption/resume/retry preserved HEAD bytes. The legacy sidecar remained byte-identical.

Independent Decimal arithmetic checked 354 trade records across baseline and R1/R2/R3 (129 + 129 + 67 + 29, with R1 intentionally repeating baseline). Maximum net residual was 1.6e-13 USDT. All admissions, raw prices, quantities, costs, net results and statistical metrics matched the retained pre-amendment outputs. Every barrier/time-close exit now becomes available one minute after its bar identifier. Final equity remained identical. Correct chronology changed R2 event-book sampled maximum drawdown from 1300.5840397556676 to 1308.4782308729973 USDT; independent chronological equity-curve reconstruction confirmed the corrected figure. Other sampled drawdowns were unchanged. The numerical comparison is retained at `.report-run/parent-bc-timing-v002/independent-economic-comparison.json`.

These checks establish operational correctness and reproducibility for this physical dataset. The baseline remains BLOCKED: full input coverage does not cure inadequate independent matched controls or unresolved legacy exposure lineage.

### Independent fresh-source reconstruction audit

The parent reopened and byte-hashed all 166 roles in the e11 fresh bundle, totaling 1,043,180,454 bytes; all descriptor hashes and lengths matched. Of these, 162 had the same canonical role content as the original bundle. Four closure-window roles changed their source label from monthly archive to REST. Two also contain small provider-data differences: BNB volume on 2021-09-29 17:40 UTC; BNB close 374.8→374.7 and volume on September 30 14:12 UTC; ETH close 3073.86→3073.85 and volume on September 26 21:42 UTC. All four windows retain 14,280 rows. The source audit is `.report-run/parent-fresh-role-byte-audit.json`.

This is a successful fresh acquisition, not a byte-exact replacement for the original source snapshot. Exact historical replay requires retained original roles. The fresh bundle must be evaluated and attributed separately; a provider revision must not be hidden by overwriting history or reusing the old physical-input identity.

The fresh e11 physical evaluation then completed with the same 130 opportunities, 126 admitted events, three controls, 129 resolved trades and blocked disposition. Parent comparison against the amended original-source baseline found identical trade-level prices, quantities, costs and net P&L, identical statistical metrics, final equity and sampled maximum drawdowns. The small source differences did not affect those evaluated results in this experiment. Provenance-sensitive semantic hashes correctly differ. The new physical identity adds one accounted attempt (36→37), preserving behavioral K=3. Comparison retained at `.report-run/parent-fresh-economic-drift-audit.json`.

### Final complete reactor checkpoint

The parent ran `./mvnw -q test` against an isolated copy of the stable source and retained fixtures. The complete reactor exited 0; Surefire XML reports total 994 tests, zero failures, zero errors and one skip. Module totals: contracts 85, research 387, market-data 55, CLI 95, reporting 47, core 191, infrastructure 80, compatibility 54. The existing `StrategyEvaluatorV5NodeOracleTest.verifiedPhysicalNullSelectionMatchesTheOriginalNodeEndToEndFixture` is skipped because its historical temporary Parquet fixture is incomplete; this is not counted as a passing physical-null verification. The new fixed physical experiment and separate frozen simulation have their own evidence.

The final successful log is `/tmp/analytics-parent-final-reactor-v3.log`, with `.report-run/parent-final-reactor-summary.json`. Two earlier reviewer setup failures are preserved: the initial isolated snapshot omitted historical report fixtures, then its Git tracked-file inventory needed restoring for the legacy-corpus test. Neither was worked around by changing production assertions. All seven Python hydration tests independently passed and `git diff --check` was clean. Later documentation-only changes require no reactor rerun; later executable/test changes require appropriate focused verification and explicit attribution.

### Amended D prefix independent numerical comparison

The parent reopened the earlier corrected4 prefix at `/tmp/d-v004-prefix-corrected4.json` (content SHA `afe0d368612201fb98b506e15010810da873d8fed544d05520ca69a97e5dc4f4`, byte SHA `8f7dcf6b4c064ddf36bf1f2534dc1010b8e1e2b670a8ce6a597329dd3660824c`) and compared it to v004d (content SHA `578743d1b7bd507d9d10c0fb1c4509983fc1c68def0d3cb13547285c2065d7cc`). Both replications preserve exact generated-input hashes, setup-minute audits, representative setup events/control selections, independent-episode arrays, statistical metrics, dispositions, decisions and portfolio net totals. All eight retained representative trades preserve raw entry/exit prices, quantity, fees, slippage, capacity and net P&L; independent Decimal residual is below 9.857e-14 USDT. Lifecycle and portfolio mark timing fields change as expected with availability semantics.

This is bounded numerical equivalence against the explicitly identified earlier pre-freeze prefix, not a claim of identical artifact bytes, all-200-replication equivalence, or a reconstruction of the separate summarized post-freeze prefix. The latter's raw bytes were not needed or invented. The comparison is `.report-run/parent-d-prefix-amendment-comparison.json`.

### Priorities after this engineering delivery

1. Resolve the retained family's legacy experiment lineage before claiming corrected confirmation significance. Keep ambiguous historical exposure visible; neither a clean codebase nor another replay resets it.
2. Design the next experiment around achievable independent event/control coverage. The present three matched clusters cannot support the intended paired inference. Any changed control design must be a new precommitted development specification; do not loosen the existing calipers retrospectively to rescue its result.
3. Use the fixed-stage operating-characteristics result to assess the evaluator's sensitivity at its declared sample/effect sizes. A failed power target is a design finding, not permission to tune the simulator or acceptance thresholds on the same runs. Full adaptive selection still needs its own validation.
4. Prepare the missing prospective custody and eligible-candidate prerequisites, then collect genuinely forward observations. Local reconciliation capability is ready, but this delivery does not create elapsed forward history, observed fills or deployment approval.

### Clean-checkout compact evidence and final boundary review

Compact results now use the separate `strategy-research-evidence-summary/1` contract with `display_only=true`, `promotion_eligible=false`, and `activation_authorized=false`. Each wrapper binds the exact original schema, path, byte length, byte hash and content hash. The parent verified all five wrappers against their preserved raw sources, including retained scalar event/control portfolio totals and sampled drawdowns. A summary cannot substitute for a full result: the refinement predecessor now requires the exact full-result schema. The 21st independent refinement test supplies a rehashed display summary with copied lineage fields and a correspondingly rehashed plan, and verifies rejection before any HEAD or output mutation.

A genuinely clean snapshot, without ignored v20/e11/R1 raw files, passed 34 tests (registry 12, retained artifact 1, refinement 21), zero failures/errors/skips. Evidence: `.report-run/parent-clean-evidence-test-summary.json`, `/tmp/analytics-parent-clean-evidence-review.log`. All required compact records and source freezes remain mandatory in the fixture test; only explicitly listed large raw projections are optional.

The parent also recomputed Java canonical hashes for 59 retained small artifacts. All current artifacts matched. The only four mismatches were the already documented v002/v003 historical D-plan files and their duplicated evidence copies; their original bytes remain preserved and the typed historical hash erratum marks them invalid for freeze binding. Evidence: `.report-run/parent-final-canonical-audit.json`.

### Final complete clean-checkout reactor result

After the summary-schema guard and 21st refinement test, the parent reran the entire reactor from the clean checkout without ignored raw evidence. `./mvnw -q test` exited 0: **995 tests reported, zero failures, zero errors, one existing historical physical-null fixture skip**. The research module is now 388 tests; other module counts are unchanged. This supersedes the earlier 994-test checkpoint for final executable-source coverage. Log: `/tmp/analytics-parent-final-clean-reactor.log`; exact totals and skip reason: `.report-run/parent-final-clean-reactor-summary.json`. The subsequent compact portfolio-total projection fields were independently compared with all five original raw results and also validated by Luna's focused schema test.

### Final packaged executable verification

The parent independently SHA-256 checked `executors/analytics-current-20260906-e12-final.jar`: `423a66c54dc13f07ab91b27565368217f8fda46d9fa402e0e70b2bf6acae50e1`, 107,057,334 bytes. Direct `java -jar <absolute-path> build-identity` from `/private/tmp` reported the same outer-JAR identity, `kind=JAR`, and compiled input fingerprint `f30c41ebc86928d7bd5aa887fefbafc280578579b486fdeaf1f5b671543119fd`. Independently rerunning `bash tools/build-input-identity.sh` on the current checkout produced the same fingerprint. Receipt: `.report-run/parent-e12-final-build-identity.json`. The frozen full D executor remains a separate unchanged historical runtime.

### Default-index regression corrected and final reactor repeated

The final artifact-layout audit established a real introduced regression: the original tracked corpus indexed successfully (11 records), whereas the added mixed retention archive caused default indexing to fail on raw arrays and non-research build-identity records. An explicit `evidence/.retention-archive` marker now declares that directory noncanonical. Its exact bytes are `strategy-research-retention-archive/1` plus a newline (SHA-256 `1f5a1a4f5f06632c8565a245d08b37f773741eabe83392e387ae968d6c789beb`). Unsafe/unknown markers, symlink boundaries, invalid records outside the archive and canonical publication overlap fail closed. Transaction-control paths remain validated. The original files were not moved or rewritten. The preserved fixed-attempt sidecar also has an exact registered read-only schema.

Parent independent tests cover partial JSON in a marked archive without parsing or changing it; unmarked raw files; invalid outside artifacts preserving the prior index; marker versions; directory/marker symlinks; direct archive-root rejection; curated typed summaries; owned and committed canonical publication overlap; and similarly named outside paths. The separate transaction-control test and existing 231-assertion authoritative compatibility oracle also pass.

The parent invoked the actual E13 public index command against the current `strategy-research/v5-records`, placing output/receipts in scratch. It returned 13 records, index hash `6a557327f21e21bb8f9aa0de48a9d9becb198289d3438ea58edf1349aed65686`: the original 11 records plus the current exposure head and legacy sidecar, with no raw archive records. The canonical stored index was not overwritten by this check. See `.report-run/parent-retention-index.stdout` and `docs/RESEARCH-INDEX-ARTIFACT-LAYOUT-AUDIT-20260906.md`.

After this correction the parent repeated the **entire clean-checkout Maven reactor**: **1,007 tests reported, zero failures/errors, one existing historical physical-null fixture skip**. Research now reports 400 tests; other module counts remain unchanged. Log: `/tmp/analytics-parent-final-retention-reactor.log`; complete totals: `.report-run/parent-final-retention-reactor-summary.json`. This supersedes the 995-test checkpoint for final source coverage. Registry corpus: 164 schema documents / 181 IDs. At that reactor checkpoint, the full frozen D simulation was still running as a separate historical executable; its completed audit follows below.

### Final E14 package independently verified

After the 1,007-test reactor, the parent independently verified the E14 outer JAR SHA-256 `4567c7d493f07cdec806f046c40007e02bbac7358c41cd0eb5e1403183395a6d` and size 107,059,288 bytes. Direct invocation from `/private/tmp` reported the same identity and compiled input fingerprint `fd1599dc5b64828c52203f796deb6f884d1f32ec602a97454a44c19bb91ae507`; running the source identity helper independently produced that exact fingerprint. E14 supersedes E12/E13 as the final current-source package. The separate frozen D executor remains unchanged.

### Completed full D experiment and independent audit

The frozen historical executor completed all 200 planned repetitions with no incomplete, invalid or insufficient repetitions and no resource abort. Result content hash: `36ecb2be8690c9bb1bd5f0cf14817df98aeaf7c5a86e53690f0b40e98a4b0a71`; raw byte hash: `1156b5e34e2dca2fec545f5059b61ceac2e066a7c824959d0fc603f08794a8cb`. The actual executor remains `dec4470d…` with source freeze v004b; the current E14 package was not substituted into the running experiment. The dated provenance errata and bounded amended-prefix equivalence limitations still apply.

The parent independently checked all 200 decisions against their recorded joint falsifier and adequacy metrics, all 800 retained representative trade cash calculations (maximum Decimal residual `1.3441744201100e-13` USDT), complete cell accounting, and all Wilson 95% intervals. Audit: `.report-run/parent-d-full-independent-audit.json`.

| Declared cell | Positive decisions | Rate | Independent Wilson 95% interval | Declared target |
| --- | ---: | ---: | --- | --- |
| No edge | 2 / 50 | 4% | 1.10–13.46% | Not met: upper bound exceeds 10% |
| Zero-effect planted null check | 0 / 50 | 0% | 0–7.13% | Null check only; no power target |
| Planted total log drift 0.02 | 14 / 50 | 28% | 17.47–41.67% | Not met: lower bound below 80% |
| Planted total log drift 0.04 | 45 / 50 | 90% | 78.64–95.65% | Not met: lower bound below 80% |

This does not establish that the true false-positive rate exceeds 10%: the experiment fails to establish its required upper bound. Detection of the smaller planted effect is weak at the declared sample; the larger-effect point estimate is high but does not meet the predeclared confidence-bound requirement. Neither thresholds nor replications were changed after observing these results. Any successor design needs a new precommitment, retaining this outcome as prior exposure. These are conditional fixed-stage synthetic diagnostics, not full adaptive-selection calibration, physical market evidence, or promotion authorization.

The evaluator recorded 19,641.381 seconds of internal runtime, below its 720-minute budget; observed process elapsed time was approximately six hours including work outside that timer. The independent five-second RSS monitor collected 3,876 samples and peaked at 6,649,184,256 bytes, below the 8 GiB budget (the evaluator's own sampled peak was 6,638,043,136 bytes). Sampling cannot guarantee an unobserved instantaneous peak. Concurrent development, builds and tests were running during part of this experiment, so this is not an isolated throughput benchmark.

### Final D artifact closeout

The parent independently verified the final display-only D summary (`7df566f3f677f00e47d2535531469de2634bfcf97427a58edb0719e84fe12b3e`) against all 200 raw repetition projections and every retained source byte binding. The generator binding points to the archived v004b source, not the subsequently amended working source. The raw result and frozen executable remain unchanged. Both compact summary and terminal observation are explicitly retained by gitignore exceptions. The terminal record discloses that the child process exit integer was not captured; completeness is evidenced by all 200 completed raw records, independent audit and strict schema validation, not an invented exit-code claim. The raw canonical hash was independently recomputed using the E14 CLI.

All A–F engineering deliverables and their required verification are complete. The unresolved legacy lineage, sparse physical matched-control sample, unmet conditional synthetic confidence-bound targets, and missing prospective deployment prerequisites remain explicit research limitations and priorities. No strategy was promoted or activated. The original HEAD and 18 pre-existing local commits were preserved; this delivery remains uncommitted and unpushed.

The final strategy-research skill also passed the official `skill-creator/scripts/quick_validate.py` validator. The parent supplied its missing PyYAML dependency in an isolated ignored `.report-run/skill-validation-venv`; no project runtime dependency was added.
