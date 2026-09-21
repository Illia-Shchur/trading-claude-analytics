# Implementation and backtest plan — daily liquidation stress v002

Planning date: 2026-09-20. Baseline: commit `5c3f20d` on `codex/liquidation-daily-stress-v002`. This is a plan, not an executed experiment or a change to the frozen strategy. No new strategy returns are inspected in preparing it.

## Objective and governing boundary

Build a reproducible simulator for the approved BTC/ETH/SOL/AAVE Binance USDT isolated perpetual strategy, then test the premise, entries, staged allocation and macro contribution in that order. A working simulator and credible historical information are separate requirements.

Historical availability qualification cannot be manufactured by adding a delay to today's archive. The data audit must establish what value was observable at a historical decision, including revisions. If evidence cannot establish this, the system must still be able to run an explicitly labeled retrospective diagnostic, but it must block authoritative walk-forward/confirmation claims. The [research protocol](../../../strategy-research/RESEARCH-PROTOCOL.md) states: “A contract can record a proxy, but a proxy cannot be relabelled as PIT truth.”

Keep the [frozen v002 specification](SPECIFICATION.md), its [v001 predecessor](../liquidation-structure-v001/SPECIFICATION.md), and [OI timing clarification](CLARIFICATIONS.md) unchanged. New technical policies and any necessary semantic clarifications get separate immutable versions and parent hashes. Preserve the `liquidation-structure` family and its cumulative search exposure.

## Approved trading contract

| Component | Implementation target |
|---|---|
| Instruments | BTC, ETH, SOL, AAVE; Binance USDT linear perpetuals; both directions; isolated margin |
| Account | $20,000 initially; margin commitment up to 100% of current equity, subject to transferable collateral and cost reserves |
| Risk | 5% planned loss per position; 10% combined, with no long/short netting credit |
| Tranches | Three stages; frozen reference-equity allowances of 1%, 1.5%, 2.5%; current-equity limits can reduce them |
| Leverage | Existing v001 implementation proposal: 2x / 2x / 3x, applied to the common isolated position |
| Decisions | Completed daily stress gate, completed 4h structure, subsequent completed 1h confirmations; 1m execution paths |
| Stops/exits | Common structural stop; whole-position exits; recovery target for reversal, tightening trailing stop for continuation |
| Holding limit | 60 × 24 hours from the first actual fill; additions never reset it |
| Macro | No initial-entry veto; second stage neutral/supportive, third supportive; deterioration keeps existing stages |
| Allocation | First confirmed, first served; frozen BTC/ETH/SOL/AAVE tie order; no rebalancing to fund a newer signal |
| Scope | Public/free data, historical research only; at most 24 hours local research compute |

At the initial $20,000 equity, tranche risk allowances are $200 / $300 / $500, the position ceiling is $1,000, and the portfolio ceiling is $2,000. These are planned stop-loss budgets, not margin amounts or guaranteed maximum realized losses. Gaps, fees, funding and liquidation remain explicit.

## Work package 1 — qualify the historical inputs

**Start here, before acquiring a large execution archive.** The importer already has 5,997 daily observations. Reuse those immutable receipts; do not silently replace their vintage.

Create an asset × series × time-range qualification matrix. Separate these questions: did an observation exist, is its value valid, when was that exact value available, can it have been revised, and is coverage sufficient for the intended use?

| Input | Evidence to establish | Treatment when unresolved |
|---|---|---|
| Daily liquidations | Exact contract/venue, UTC bucket semantics, publication delay, revision/backfill behavior, missing-row meaning, Binance sampling limitations | Keep Coinalyze `PROXY_DISCLOSED`; retain t+48h as an assumption, never proven availability |
| Open interest | Base-quantity units, cadence, historical endpoint observations, publication lag and revision provenance | Block the price/OI core if unavailable; never substitute USD OI, funding or volume |
| 4h/1h price bars | Complete bars, source/close timestamps, causal aggregation, continuity and historical availability policy | Mark gaps and reject affected setups; do not rebuild a missing bar from future observations |
| 1m trades/marks | Exact eligible fill and mark paths, gap/outage policy, complete lifecycle coverage | Block unpriceable fills or affected evaluations; do not discard an adverse open-position gap |
| Funding settlements | Actual signed rates/events, including zeros and any interval changes, contract identity and settlement marks | Missing settlements block execution economics; future rates never enter entry predicates |
| Contract and execution metadata | Historically effective lot/notional filters, fee schedule, maintenance tiers/deductions, liquidation charges, collateral/leverage rules | Present-day metadata is a disclosed approximation, not historical proof |
| S&P 500 context | Completed sessions, actual publication timing, applicable release/vintage evidence, holidays/DST and revisions | UNKNOWN blocks additions when macro is enabled; the separate no-macro experiment remains identifiable |
| Positioning ratios | Ratio definition, timestamp, coverage and provenance | Report diagnostic coverage/missingness; do not invent a new trading gate |

Implement a typed qualification receipt with event time, period end, observed value vintage, documented/verified availability, retrieval time, evidence references and hashes, units, missing ranges, and permitted evidence use. Retrieval time is not historical availability. Use qualification states such as verified, disclosed proxy, unknown and unavailable; their mapping into production schema/policy must be explicit and versioned.

Research official provider documentation and public archives first. Compare repeated downloads for revision detection, but absence of detected changes today does not prove past immutability. A provider's historical version/publication records or a documented and substantiated immutable-source contract may establish the required evidence; marketing claims about retention alone do not.

Do not contact providers, purchase data or start ongoing capture without separate authorization. If public sources cannot close a gap, document the exact missing fields/date ranges and investigate whether a paid source actually supplies them before recommending it. Forward capture can establish future vintages, not repair the past, and is outside this historical-only request.

**Acceptance gate:** every required input has an explicit qualification result and machine-enforced permitted use. Mandatory unknown/proxy inputs block promotion. Complete calendar coverage never overrides that gate. The useful outcome can be either a qualified interval or a precise, reproducible diagnostic limitation.

## Work package 2 — add a versioned execution and research policy

Introduce a capability-bound profile for this exact four-asset, shorter-history, isolated-perpetual family. Preserve existing eight-asset/five-year defaults and 30-day legacy behavior. Merely changing a global `30` to `60` is insufficient.

Bind the following in schemas, policy hashes and validators: exact instruments, source window, daily/4h/1h/1m clocks, three-stage state, maximum lifecycle, order/funding collision rules, historical metadata qualification, portfolio budgets, data-gap behavior, and evidence tier. Use the current v5 façade with additive versioned contracts; proposed new command names are to be finalized during implementation, not treated as existing commands.

Extend the physical data pipeline so features, outcome labels and execution rows remain separate Parquet roles, queried through the pinned DuckDB tooling. Freeze the full permitted opportunity envelope before reading outcomes; hydrate all mechanically eligible windows under that envelope, not a profitable subset. Cover warmup, up to six days of setup waits, the full 60-day hold and execution margin.

**Acceptance gate:** the new profile accepts its explicit 60-day contract; old contracts still reject unsupported horizons. A caller cannot select 60 days while retaining 30-day hydration, purge, metadata coverage or executor identity. Scope and PIT-tier overrides fail closed.

## Work package 3 — build and test the execution engine

Build this against synthetic fixtures while data qualification proceeds. Implement the simpler one-entry mode and the three-stage mode through the same accounting engine; having staged support available does not authorize inspecting its returns before the core test.

Use an event-driven position state machine: flat → armed setup → initial entry → second entry → third entry → closed/cancelled. Armed/entry-wait expiry, stop, target, timeout, liquidation and data failure must be explicit transitions. Separate strategy state from cash/margin accounting.

Each asset has one common isolated position with signed quantity, weighted entry, common stop, isolated collateral, leverage, mark value, funding, realized fees, reference equity and first-fill timestamp. Additions are changes to that position, not independent trades with separate stop clocks. Keep tranche attribution for analysis, but count a position once in trade statistics.

Implement:

- Reference-equity tranche budgets plus current-equity position and portfolio checks; include already-paid fees, known funding debits and estimated closing costs as specified. Winning stops cannot create negative-risk credits or a fourth tranche.
- Quantity rounded down to effective historical lot size; minimum notional, free collateral and 1% of the last completed minute's traded base-volume capacity. Never size with the future fill bar's volume.
- Position-level 2x→3x leverage adjustment and explicitly modeled collateral release. A leverage increase alone does not increase exposure or authorize spending unavailable profit. No automatic margin top-up.
- Funding charged once on quantity actually open at each settlement, using contemporaneous mark and the frozen event-order rule. Preserve zero events and paid/received attribution without double counting in cash or PnL.
- Historical maintenance tiers and mark-driven liquidation after additions, funding and leverage changes. Simulate liquidation losses/charges as outcomes when the mechanics are qualified; missing mechanics make the result unqualified. Never omit liquidated trades or close them at a favorable stop price.
- Full-position structural stop/recovery target/trailing exit, conservative gap fills, stop-first ambiguous 1m stop/target collisions and liquidation priority as frozen. New trailing levels only apply after their source 4h bar closes.
- A day-60 exit order at the deadline with executable latency. If a venue outage prevents execution, retain the exposure until the first permitted fill and disclose the overrun; do not invent a fill or reset the clock. Freeze this operational interpretation before outcomes.
- Deterministic common-timestamp processing for settlements, exits and entries. Where exact exchange ordering is not evidenced, disclose the conservative simulation assumption and block stronger execution claims as needed.

**Acceptance gate:** independent hand-calculated fixtures reconcile quantity, collateral, funding, cash and marked equity for both directions, all three stages, changing tiers, insufficient collateral, gap/liquidation collisions, and the day-60 boundary. Portfolio contention must produce identical results regardless of input-file ordering.

## Work package 4 — implement the causal strategy router and additions

Reuse the already tested daily gate. Add the frozen fast and 72h price/OI geometries, pre-event levels, reversal/continuation confirmation, 1h zones and next-eligible-minute execution. OI freshness is checked at each historical event endpoint; both observations must already be known before the later setup decision.

The router cannot arm before assumed/qualified daily availability, nor use a 4h branch confirmation that closed before it. A pivot requiring two right-hand bars is unavailable until the second one closes. Duplicate daily stress flags cannot open multiple positions in the same asset.

For each addition, require fresh price confirmation, a fixed eligible pivot/zone, the next distinct entry hour and all risk/capital checks. The common stop never widens, the reversal target never moves, and the holding clock never restarts. The macro module only controls additions, with a distinct UNKNOWN state; a previously blocked addition needs a new confirmation after eligibility returns.

**Acceptance gate:** boundary fixtures demonstrate no future-bar leakage, no same-hour multiple stages, no pre-publication entry, no post-target reversal addition, no macro-driven forced exit, and exact deterministic cancellation/tie behavior.

## Work package 5 — connect portfolio replay, statistics and evidence gates

Replay all four assets on a shared chronological marked-equity path. Record rejected and partially sized entries, reserved margin/costs, mark gaps, funding, concurrent exposure and liquidation. Use account-currency PnL; never add raw R to cash. Compute drawdown from the intratrade mark path rather than closed trades alone.

Extend both outer and inner chronological fold generation and validation to the profile's minimum 67-day purge, actual outcome-overlap removal and seven-day embargo. Update dependent resampling, lifecycle coverage, PBO/null machinery and manifests together. Preserve legacy 30-day records and defaults. Treat correlated symbols and overlapping 60-day outcomes as dependent.

The qualification receipt and executor capability identity must be reopened by experiment freeze and evaluation, not merely copied into a report. Unknown/proxy load-bearing data can permit a development replay, but must not emit authoritative WALK_FORWARD_OOS, sealed evidence or SHADOW. Local historical work cannot manufacture sealed custody or live authorization.

**Acceptance gate:** same inputs/policy/seed reproduce the same decisions and economics; interrupted/restarted runs are equivalent; stale hashes, unsupported profile versions, future features, role mixing, missing marks/funding and inadequate purge fail closed.

## Code map and reviewable delivery order

| Change | Existing code boundary to integrate/review |
|---|---|
| Qualification and raw receipts | `analytics-infrastructure/.../marketdata/CoinalyzeDailyData.java`; source receipt/PIT logic in `StrategyResearchDataV5.java` |
| Frozen scope, 60-day envelope and executor binding | `StrategyResearchAuthoritativeV5.java`, research schemas and capability/policy validators |
| 4h/1h decisions, feature/label/execution roles, perpetual metadata | `StrategyResearchDataV5.java`; current exact-4h and spot-only metadata boundaries |
| Shared staged position and exits | New versioned staged engine alongside/reusing tested arithmetic from `TradeLifecycleV5.java`; preserve existing contracts |
| Cash, margin and marked equity | `StrategyPortfolioV5.java`, `StrategyPortfolioRiskV5.java`, and existing corrected-accounting helpers; avoid a second inconsistent funding ledger |
| Purging, overlap and statistics | `StrategyStatisticalV5.java` plus authoritative fold validators and experiment policies |
| CLI and diagnostics | `StrategyResearchV5CommandAdapter.java`; retain `DailyStressPreflightV1.java` as the historical diagnostic |

Deliver as separate reviewable commits: (1) qualification receipts/audit, (2) policy/schema/data-envelope extension, (3) executor/accounting, (4) router/staging, (5) portfolio/statistics/evidence integration, (6) frozen experiment artifacts and results. Actual implementation uses the repository's required implementation-agent routing and independent review. Parallelize source investigation with synthetic-engine work; run Maven through one owner to avoid the earlier build collision.

For each production change, run affected correctness tests and required clean reactor checks. Enforce explicit changed-code coverage of at least 80% lines and 80% branches against the reviewed base; do not rely on a looser checker default. Include legacy parity, tampering, negative-input and temporal-boundary tests. Documentation-only edits need link/configuration checks.

## Backtest sequence after implementation

### 1. Freeze the run and audit feasible sample size

Reopen the exact v002 precommit, qualified/proxy source manifests, executor/policy hashes, asset scope, candidate inventory, evidence labels and acceptance rules. Keep November 11, 2022 through July 14, 2026 as the decision window and September 20, 2026 exclusive as the execution boundary. AAVE's actual eligible windows remain determined by its gaps; do not fill or drop them selectively.

Inventory mechanically eligible setups and dependence-aware episodes before inspecting PnL. Daily stress counts are not trade counts. If the remaining data or sample cannot support the frozen tests, report BLOCKED or INSUFFICIENT_EVIDENCE rather than lowering thresholds or shortening the purge.

Use one frozen baseline per planned stage, plus its explicitly named controls, with no parameter optimizer. Freeze and record the exact candidate count before outcome evaluation; rejected variants, sensitivity experiments and reruns that inspect different behavior remain in cumulative K.

### 2. Test the minimal core first

Run the one-entry, score-free router against always-reversal and always-continuation controls using the frozen eligible-decision comparison contract, identical execution assumptions and the same 60-day maximum. Report branch and direction results even when they fail. Stop advancement if routed net expectancy or incremental evidence fails; do not rescue the premise with more indicators.

### 3. Test entry timing, then staged allocation

Evaluate only the small entry-confirmation comparison set frozen before outcomes. Advance a surviving predecessor by hash.

Then compare the three-stage lifecycle to the surviving one-entry baseline. Report both account-dollar returns and risk-normalized results: a larger deployed risk budget alone is not evidence that staging improves timing. Include a predeclared risk/exposure-matched diagnostic comparison or attribution, explicitly counted in the experiment budget; it must not silently change the owner's deployed strategy. Keep the same 60-day horizon in both arms.

Core R uses frozen initial-tranche risk; staged R uses the frozen full-position 5% reference risk. Also report tranche R, utilization and dollar PnL so unused stages cannot inflate the headline statistic.

### 4. Test macro last

Compare the surviving staged strategy with and without the single frozen S&P 500 addition filter. Use availability-safe completed sessions, explicit UNKNOWN handling and the predeclared add-one/leave-one-out attribution. Do not add RSI, rolling-correlation gates or a composite score during this test.

### 5. Chronological validation and execution stress

Use development before 2024, eight quarterly outer windows in 2024–2025, and the already labeled 2026 development extension. Each stage must select only within the permitted training history; observing the whole 2024–2025 period and then choosing a later-stage variant exposes those windows. Use nested training-only decisions for an OOS claim, or honestly label the repeated historical comparisons DEVELOPMENT/EXPOSED. If inputs remain proxies, chronological splits are sensitivity diagnostics, not qualified WFO evidence.

Retain the existing frozen gates:

- At least 30 effective independent episodes, 20 completed positions per branch, 60 portfolio trades, and 5 positive outer folds out of 8. These are floors, not proof of adequate power.
- 10,000 synchronized market-time bootstrap draws, seed 20260920; p20 net expectancy >0, p20 incremental expectancy >0, and familywise max-statistic p≤0.05 against the frozen controls.
- At least 67-day dependence blocks and actual-overlap purging; seven-day embargo. The shortened first training window may be inadequate and must be reported.
- Positive base net PnL and marked-equity drawdown ≤30%, as the existing proposed research acceptance rule, not a guarantee.
- All five frozen stresses: doubled execution costs, doubled funding debits without doubled credits, +0.25R adverse gap, 1% capacity participation, and predeclared venue outages. Preserve their observation/expectancy requirements; a no-op outage is not a pass.

Lag sensitivity or alternate metadata assumptions may be useful for proxy diagnostics, but freeze the small set and count it before viewing returns. Do not select whichever lag looks most profitable or let robustness under assumed lags certify historical availability.

### 6. Retain the complete result and decision

Deliver a source-qualification matrix, coverage/gap report, frozen experiment and lineage hashes, compact position/tranche/event ledger, marked-equity path, metrics for every tested candidate, yearly/fold/asset/branch/direction breakdowns, stress results, skipped-entry reasons, funding/cost/margin attribution, and an explicit decision with failed gates.

Report net expectancy, win rate, average winner/loser, drawdown and duration, tails/loss runs, liquidation count, time in market, margin utilization, signal overlap and concentration. Preserve reproducible commands and restartable checkpoints. No historical output authorizes live trading.

## Compute budget and decision points

Treat the 24 hours as a cap on the authorized local research run, not a promise that software development or missing historical evidence can be completed in one day. Before the full run, benchmark decoding/replay/statistics without selecting strategy parameters from PnL. Project the remaining workload, reserve time for validation/reporting, cache immutable source partitions and resume safely. If the projection exceeds the cap, return COMPUTE_INCOMPLETE with completed work and required resources; do not quietly reduce the frozen 10,000 draws or omit candidates/stresses.

No additional preference is needed to begin the implementation sequence: the strategy and historical-only scope are already specified. User input is needed only if the data audit requires spending money, contacting a provider, starting forward collection, changing a frozen trading rule/date window, or changing the compute budget. A diagnostic result with unresolved provenance is a valid delivered research limitation; it is not a substitute for qualification.
