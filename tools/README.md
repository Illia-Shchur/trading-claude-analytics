# Deterministic Toolchain

## Durable strategy research registry

`node tools/strategy-research.mjs` manages the Git-tracked
`strategy-research/` audit record. Commands: `precommit`, `generate`, `evaluate`, `run`,
`stats`, `plateau`, `ablations`, `portfolio`, `stress`, `monitor`, `record`,
`validate`, `rebuild-index`, `list`, `show`, `compare`, and `import-legacy`.
It delegates simulation to `tools/swing-engine.mjs`, deterministically expands
grids, deduplicates effective behavior, rejects candidate-ID conflicts, records
declared/effective K and hashes per series, and refuses overwrites.

Only compact evidence is tracked; raw feature stores and caches stay outside
Git. All statuses fail closed per asset and portfolio, and no registry run can
bypass the separately governed activation contract. Full details and examples:
`strategy-research/README.md`.

New candidate families must use `strategy-precommit/1` and the staged
`strategy-definition/2` + canonical `strategy-experiment/3` contract. The
legacy v1/v2 experiment/evidence surfaces are read-only. The precommit is
immutable and contains the premise, falsifier, ranges, PIT/availability
contract, replication groups, deferred score role, and crypto-only tradable
universe. Use `precommit --input <filled.json>` followed by
`generate --precommit <frozen.json>`; the CLI never invents a hypothesis.
Stages are CORE_PREMISE, ENTRY_TIMING, RISK_LIFECYCLE, INDEPENDENT_CONTEXT,
and COMPOSITE_SCORE. A later stage must link its predecessor hash, and a
composite score is forbidden until a score-free baseline exists.

`tools/strategy-research-v2.mjs` provides deterministic event/time-block
resampling, a candidate-set conditional max-statistic p-value (not SPA and not
a distribution-free guarantee), bootstrap expectancy intervals/p20, plateau topology,
completed-bar as-of joins, stress scenarios, and prospective monitoring.
`tools/strategy-portfolio.mjs` is a separate crypto-only portfolio simulator:
non-crypto instruments are rejected, while crypto derivatives require
instrument/venue/collateral/funding metadata. See
`strategy-research/RESEARCH-PROTOCOL.md` for the full workflow.

## Machine-first report pipeline (report-machine/2 and report-machine/3)

New reports are authored as strict JSON drafts and published as an immutable
`report-machine/2` payload plus a deterministic `report-markdown/1` view. JSON
is the source of truth; the Markdown machine block is a compatibility view and
must round-trip canonically.

```text
fresh position + live evidence + prior-report projection
  -> draft JSON -> finalize -> lint JSON
  -> render full Markdown -> pair lint -> export -> commit JSON + Markdown
```

Pinned dependencies are `ajv@8.20.0`, `canonicalize@4.0.0`, and
`jsonc-parser@3.3.1`. Canonical JSON is JCS-style: recursively sorted object
keys, array order preserved, minified UTF-8, and one terminal newline. Prices,
money, quantities, ratios, and source decimals are plain-decimal strings;
bounded counts, gate IDs, probabilities, and half-point scores remain native
numbers. Unavailable measurements are status-wrapped with `value:null`.

```sh
node tools/finalize-report.mjs .report-run/draft.json --out reports/<stem>.json
node tools/lint-report.mjs reports/<stem>.json
node tools/render-report.mjs reports/<stem>.json --mode full --out reports/<stem>.md
node tools/render-report.mjs reports/<stem>.json --mode summary
node tools/lint-report.mjs reports/<stem>.json --markdown reports/<stem>.md
node tools/export-signals.mjs --strict
```

`position_controls` is mandatory in every v2 document. Flat positions use
`required:false/status:NOT_APPLICABLE`; open positions require the complete
candidate, veto, selection, venue-order, ladder, PnL, ratchet, liquidation/
zone, risk, and execution-audit set; data-limited positions cannot fabricate
`HOLD`/`RETAIN`. The v2 validator and renderer make no network calls and read
no current clock. Legacy `report-machine/1` Markdown remains supported.

Shadow swing reports may use `report-machine/3`: the JSON sidecar is canonical and
the Markdown view is deliberately compact. It carries `setup`, `features`,
`trigger`, `vetoes`, `risk_budget`, `expectancy_r`, and `audit`, while source,
provenance, position, and phase-attribution details stay in the sidecar. The
view has no embedded machine block; its final audit line includes the sidecar
SHA-256 prefix. This contract cannot authorize entries until a BTC+ETH
walk-forward activation artifact passes; legacy v1–2 decision rules remain
operative until then and must not be mixed with v3 scores. `lint-report.mjs`
checks the pair and rejects the removed
Market/evidence/data-quality, substitutions/source-register/provenance,
phase-registry/canonical-tags, and canonical-payload sections. v1–2 reports
remain read-compatible.

Every v3 sidecar records `model_activation`. `SHADOW` and `CANDIDATE_REVIEW`
force `entry_authorized:false`; `ACTIVE` requires the committed calibration
artifact path, its full SHA-256, and an activation timestamp. Non-flow legs
also retain their state/impulse decomposition: technical 2+2, macro 1.5+1.5,
sentiment 1.5+1.5, valuation 2+1, and structure 1+1.

`tools/swing-score.mjs` is the shared pure implementation of swing-score/1:
six bounded legs, completed-bar flow coverage, three mechanical gates,
phase lines/caps, hard vetoes, 1.5% portfolio and 3% asset risk sizing, and
the two-bar trigger window. `tools/swing-calibrate.mjs` backfills up to three
years of BTC/ETH completed 4h features. The default backfill joins Binance
spot/futures taker quote flow, Binance Data Vision OI samples, Binance funding,
FRED DXY/real-yield observations, Coin Metrics MVRV, and Alternative.me
sentiment. Absent observations remain in the full OHLC label denominator and
are listed in coverage metadata. Funding is explicitly a carried
latest-settled event state; macro/sentiment/valuation use prior completed
observations with a conservative next-UTC-day availability lag and carry/revision risk is disclosed. A one-year warmup is fetched for calendar lookbacks but labels remain restricted to the requested window. The six non-flow legs carry
explicit state/impulse checks and source provenance, while OI/funding are
interpreted relative to FK-long, FR-A-short, and FR-B-short setup direction.
The calibration then runs an 18-month development / quarterly chronological
walk-forward / untouched six-month holdout layout. Signals are de-duplicated
into non-overlapping 30-day episodes and round-trip fees/slippage are debited
in R. Activation requires all six BTC/ETH series to meet the declared five
episode floor, positive net expectancy, precision ≥45%, early capture >0,
three regimes, and ≥80% aligned-feature coverage against the full OHLC label
universe (excluded labels remain in the denominator).
OHLC-only backfills remain label generation, not feature calibration, and stay
`SHADOW`; a failed holdout never promotes a threshold. Activation also requires
point-in-time-safe vintages and explicit acceptance of the historical proxy
contract. The current revised FRED/Coin Metrics histories and missing
ETF/on-chain/reserve/stablecoin inputs therefore cannot activate it.

### Fast swing research engine

`tools/swing-engine.mjs` separates backfill from evaluation. It consumes
`datasets[].features` (or can call the deterministic BTC/ETH backfill adapter),
deduplicates bars by asset/timeframe/framework/channel, and writes a hashed
columnar feature store. Candidates are declarative FK/FR contracts; lifecycle
simulation uses next-bar fills, OHLC stops/targets/time stops, partial exits,
fees, slippage, funding-event de-duplication, risk-budget sizing, and
episode-level anti-overlap. Feature rows are routed by completed-bar
availability (not bar-open month), and future-label fields are rejected at
cache-build time. Selection uses purged development rows only. A candidate must
have at least 10 costed trades, two regimes, positive expectancy/PF, and
compliant drawdown and positive search-adjusted expectancy before it can be
measured OOS; feasible candidates rank by the deterministic bootstrap
20th-percentile mean R. The conservative `sqrt(2 log K/n)` adjustment is a
hard selection gate, with K counted from the eligible distinct candidate models
for that series. Holdout evaluation opens only after at least 20 aggregated OOS
trades, positive OOS expectancy/PF, and three positive folds.

```sh
# Build from a saved feature export (or use --assets btc,eth --years 3).
node tools/swing-engine.mjs build-cache --input features.json \
  --out .report-run/fast-backtest/features.json.gz
node tools/swing-engine.mjs build-cache --assets btc,eth --years 3 \
  --cache-dir data/swing-calibration/cache \
  --out .report-run/fast-backtest/btc-eth-feature-store.json.gz

# Run candidates and render the development/OOS/holdout summary.
node tools/swing-engine.mjs run --cache .report-run/fast-backtest/btc-eth-feature-store.json.gz \
  --candidates candidates.json --out .report-run/fast-backtest/run.json \
  --summary .report-run/fast-backtest/summary.md

# Emit the frozen market-context library, or evaluate an explicit ID subset.
node tools/swing-candidates.mjs > .report-run/fast-backtest/candidates.json
node tools/swing-engine.mjs run --cache .report-run/fast-backtest/btc-eth-feature-store.json.gz \
  --candidates .report-run/fast-backtest/candidates.json \
  --candidate-ids fk-deleveraging-absorption-fast \
  --out .report-run/fast-backtest/fixed-run.json

# Measure cached candidate throughput and inspect admitted holdout trades.
node tools/swing-engine.mjs benchmark --cache .report-run/fast-backtest/btc-eth-feature-store.json.gz \
  --candidate-count 1000 --out .report-run/fast-backtest/benchmark.json
node tools/swing-engine.mjs inspect-trades \
  --run .report-run/fast-backtest/run.json
```

The feature store's `features_sha256` and run artifact's `run_sha256` are
verified before reads, evaluation, benchmarking, or trade inspection; tamper
or missing-feature checks fail closed. These artifacts are research-only and
always `SHADOW`: a `point_in_time_safe:false` backfill, revised public history,
or incomplete carry/proxy coverage cannot activate live FK/FR gates. Full-sample
diagnostics that could expose the confirmation window are not emitted. Ordinary
local confirmation is labelled `EXPOSED_CONFIRMATION`; `SEALED_CONFIRMATION`
requires a caller-supplied token and matching precommitted holdout-data hash.

For a fixed candidate selected on prior assets, `tools/swing-cross-validate.mjs`
opens one precommitted validation asset once, verifies the frozen candidate
hash, and reports per-calendar-year stability, after-cost expectancy/PF,
bootstrap downside, drawdown, and whether funding was actually charged. It
refuses to overwrite an existing validation output, and secondary diagnostics
cannot replace a failed primary candidate.

For a frozen multi-component long/short router,
`tools/swing-strategy-cross-validate.mjs` additionally merges both directions
onto one chronological per-asset episode timeline. Its precommit binds the
strategy/component hashes, unseen asset, lifecycle, costs, coverage gates, and
one-time output. Set `selection_hypothesis_count` to the full declared search
multiplicity; that K flows into the hard search-adjusted expectancy check.
The validator also requires R and dollar profit factors, positive total return,
calendar/block consistency, doubled-cost survival, actual funding charges, and
a sealed feature-store hash. It refuses to overwrite an opened result.

Validate an emitted artifact with `node tools/lint-swing-calibration.mjs
<calibration.json>`. An `ACTIVE` artifact's digest is recomputed after stripping
only its self-referential activation metadata (and the convenience `artifact`
object); a mismatch fails lint.

For a single normalized calculation, use
`node tools/compute.mjs swing-score --legs '<json>' [--flow '<json>']
--framework fallen_knives|flying_rocket --phase 1A --trigger-valid`.

`schemas/report-machine-2.schema.json` is the Draft 2020-12 shape;
`tools/report-contract.mjs` is the shared schema + semantic validator;
`finalize-report.mjs` is the only v2 publisher; and `render-report.mjs` is the
pure deterministic view generator. `test/report-v2-test.mjs` covers flat/cold
start, live long, Channel-B dry, live short, stale/unknown position, unreliable
basis, custody exceptions, strict JSON negatives, and machine-block round trips.

`position.mjs` consumes the newest historical `exports/position-snapshot-*.json` by default. Pass `--file` with a specific file or directory to override selection; the legacy `position-snapshot.json` remains a migration fallback.

### Futures-only snapshot projection and validity

`position.mjs sp500` now resolves an open derivative through
`futures.open_positions[].analytics_asset` (`SPYUSDT → SP500`), so the absence of a spot `positions[]` row
does not become `NOT_COVERED`. It preserves exact human attribution, current-sequence fills, and protective
order safety metadata; it never infers a phase from symbol, size, or timing.

Validity is event-driven by default: the newest structurally valid export remains the position of record
regardless of elapsed time because the owner exports whenever a new trade occurs. `generated_at`,
`source.holdings_as_of`, and futures component clocks remain visible audit metadata but do not age out the
snapshot. Missing or incomplete futures component status/current-sequence income coverage still limits the
claim because that is a coverage defect, not staleness. Pass `--max-age-min N` only when an explicit strict-time
audit is desired; strict mode bands on `generated_at`, never the unchanged holdings clock.
This section supersedes the older age-band sentence retained in the compact `position.mjs` table row.

Node scripts (no dependencies, Node ≥18) that make the frameworks' numbers computed, not narrated. Introduced 2026-07-10 after the doc audit found the recurring failure modes were exactly the hand-done steps: RSI never computed (4-report NOT-FOUND debt), `ceil(7/9×8)` misprinted as 6 in three reports, EV sum-checks done by eye, ADR silently absorbing a half-session.

## Strategy research foundation v3 (canonical path)

Use `node tools/research-data.mjs snapshot` to create an immutable
`strategy-data-manifest/2` snapshot. Raw/normalized/features/labels/quality are
separate physical layers; Parquet/DuckDB is authoritative and JSONL/CSV is
staging only. The snapshot identity excludes wall-clock receipts, computes real
coverage/gaps, and partitions by dataset version, asset, venue, instrument,
timeframe, year, and month. Public adapters live in
`tools/public-data-adapters.mjs` (Binance spot/linear OHLC, OI, funding,
Alternative.me, ALFRED/FRED vintages, and timestamped prospective capture).
Bounded OHLC, open-interest, and funding backfills expose response-byte hashes,
retry/rate-limit policy, coverage receipts, and `--resume` continuation; hitting
a page/row bound remains explicitly incomplete.

The v3 evaluator requires frozen lineage and never accepts caller-authored
metrics/trades for authoritative phases. `evaluate-v3` executes local
DEVELOPMENT/WALK_FORWARD_OOS research from the frozen experiment, candidate
set, PIT feature/label manifests, and pinned swing executor. The specialized
public-unseen-data custody runner is not shipped. `CI_ATTESTED_CONFIRMATION`
is a signed SHADOW result only; local code cannot mint SEALED or ACTIVE. Use
`strategy-attestation.mjs keygen --private-out ... --public-out ...`, a remote
immutable burn tag/receipt, and append-only import records. v1/v2 remain
read-only. Run `npm run research:docker` for the pinned DuckDB integration
test and `npm run research:migrate` for the deterministic eight-asset legacy
index (BTC, ETH, SOL, BNB, XRP, ADA, LINK, AAVE; DOGE excluded).

## Strategy research v5 BASE_ONLY path

New strategy families use `node tools/strategy-research-v5.mjs` and the
physical chain `feature-build` → `metadata-build` → `opportunity-envelope --hydrate` →
`artifact-build` → `research-init` → `experiment-freeze` → `search-genetic`. `feature-build` can
derive completed-bar price and strictly prior, same-asset Binance USD-M
funding predictors from a verified `BASE_ONLY` acquisition. Missing optional
market-flow archives do not block a strategy that did not declare them;
metric-dependent strategies remain blocked.

For spot execution, `metadata-build` turns one frozen, hash-bound local policy
into physically reopenable `CONTRACT_SPEC`, `FEE_SCHEDULE`, and
`EXECUTION_MODEL` receipts. The output remains explicitly `USER_BOUND`, covers
the final signal through its maximum lifecycle, applies exchange lot/notional
filters and two-sided costs in evaluation, and is never activation evidence.
The command rejects derivative execution instead of borrowing spot
assumptions; USD-M funding may still be used as context for a spot strategy.

`artifact-build` internally creates separated FEATURE/LABEL/EXECUTION/MARK
JSONL and verified Parquet. Spot-only research retains a typed zero-row MARK
role; it never fabricates a derivative mark series. `research-init` reopens
those roles and the exact v2 opportunity custody, derives independent episode
records, initializes the repository-anchored family cumulative exposure HEAD,
and carries its K unchanged across rolling dataset snapshots,
and writes the statistical genesis. Caller-authored episodes, returns, metrics,
features, labels, execution rows, hypothesis-family values, or K counters are
rejected. `experiment-freeze` then derives the immutable experiment/3 lineage,
executor identity (including the exact execution-metadata bundle), and exact
crypto asset/instrument scope from reopened physical inputs plus the frozen
user-authored experiment policy; lineage and
statistical-output overrides are rejected. See `strategy-research/V5-README.md`
for full commands and required frozen inputs.

## Report-phase attribution contract (2026-08-14)

Machine reports dated on/after 2026-08-12 publish an immutable `report-phase-registry/1` under `tagging.registry`. `canonicalReportPhaseTag()` derives exact tags from the filename's framework, asset, date, and local HHMM: `FK-P1A-BTC-20260813-1744` and `FR-A-1A-SP500-20260812-1542` are representative examples. Decisions are `AUTHORIZED`, `LOCKED`, `STAND_DOWN`, or `UNVERIFIED`; they are not encoded as tag suffixes and are never inferred from a later fill. `FR-B-3` is invalid, and FR channel `none` uses FR-A's rubric vocabulary. `active_tags`/`reserved_tags` remain compatibility fields only; registry validity is independent of fills. Run `node tools/backfill-report-phase-registry.mjs --check` to audit the expected 67 machine-block / 66 prose-only split, then run it to backfill only machine reports.

## Automated market-gap coverage (2026-08-19)

`fetch.mjs` now closes four recurring BTC/ETH report gaps. `onchain` uses Coin Metrics Community data to reconstruct MVRV-Z and report exchange reserves/flows; `coinbase_premium` computes the completed-day premium and three-day streak; `context.positioning.open_interest_90d` reads official Binance Data Vision archives; and `macro.equities_breadth_200dma` calculates SPY-universe breadth from current State Street holdings plus TradingView close/SMA200 data, failing closed below 95% coverage. True LTH remains explicitly `PROVIDER_GATED`—no unrelated age band is substituted. ETF flows and news remain separate live-web inputs. These additions do not change any rubric band; only the pre-existing OI squeeze condition may now receive a verified boolean instead of `null`. This section supersedes the older `fetch.mjs` table-row sentence saying all on-chain data is uncovered.

## Coinglass-style market-flow panel (2026-08-22)

For crypto assets, `fetch.mjs` emits `context.market_flow` (`market-flow/1`) on completed 4-hour bars:

- aggregated spot CVD;
- aggregated futures taker bid/ask delta and futures CVD;
- aggregate OI candles;
- OI-weighted funding candles.

Set `COINGLASS_API_KEY` to use Coinglass cross-exchange data (`Binance,OKX,Bybit`). The integration uses the all-plan aggregated taker buy/sell endpoints and computes window-relative CVD as cumulative `taker_buy_usd - taker_sell_usd`; OI OHLC and OI-weighted funding come from their dedicated endpoints. Coinglass's native aggregated-CVD endpoints require Startup or higher, so they are not required for this implementation. Never commit the key.

Without the key, the tool discovers every active Binance spot market for the asset quoted in USDT/USDC/FDUSD/TUSD/BUSD/USDP and every active stable-USD USD-M perpetual. It sums quote-volume taker flow into Binance-aggregate spot/futures CVD, aggregates each perpetual's 30-minute `sumOpenInterestValue`, and resamples completed 4-hour sampled OI OHLC candles. For funding it forward-fills each contract's latest settled `fundingRate` onto the contemporaneous 30-minute OI observations, computes `Σ(rate × USD OI) / Σ(USD OI)`, and resamples that Binance-wide weighted series to completed 4-hour candles.

The fallback is explicitly **single venue**, not cross-exchange. Stablecoin quotes are nominal USD; OI high/low are sampled 30-minute observations rather than continuous extrema; and the aggregate funding rate remains a raw fraction per each contract's funding interval (`0.0001 = 0.01%`). Do not annualize it unless all included contract intervals are verified. `context.market_flow.binance_aggregate` lists discovered/included symbols, sampling method, units, coverage, and partial fetch errors. CVD remains rebased to zero at the first returned completed bar, so absolute CVD is not comparable across runs. The panel is disclosed context only; the two framework skills define how it may inform Analyst Read/discretion without becoming an automatic score or gate.

| Tool | Purpose |
|---|---|
| `lib.mjs` | Pure math: Wilder RSI-14, SMA, FK/FR rubric band classifiers (edge conventions codified: FK edges → higher-score band, FR edges → lower-score band per Hard Rule 6), `ceil` gate thresholds, per-asset .5 rounding, weighted EV + sum-check, stop coherence, ADR (full sessions only), F&G streaks, FR funding/cycle-cap/squeeze-penalty. **FR two-channel architecture (2026-07-27):** `frChannel()` routes A / B / stand-down and fails closed to stand-down on any missing regime input; `frB.*` carries the Channel B rubric bands; `FR_SCORE_UNLOCK` (11/13/15/19) and `FR_GATE_FLOORS` (3/5/6/8); `frPhasesUnlockedByScore()` reads Phase 3 off the MECHANICAL score; `s5StopCheck()` bounds an analyst stop to 6% ABOVE fill (the mirror of FK's `d5StopCheck`, which bounds 15% below); `frRatchetCheck()` enforces the S6 ratchet with **no** exception, unlike FK's D6. **Mirrors SKILL.md rules letter-for-letter — a SKILL band change must change this file in the same commit.** **Fill + feed helpers (2026-07-29):** `fillPrice()`/`trancheFilled()`/`entryLooksLikeFill()` (the fill predicate and the heuristic that catches an under-encoded prose fill); `EPOCHS`, `reportFileMeta()`, `localToUtcISO()` (DST resolved from the platform tz database, not a hardcoded −4/−5), `signalRubric()`, `legSpec()`, `inferChannel()`, `inferDiscretion()`, `gateMask()`, `unlockFor()`, `canonicalJSON()`, `feedChanged()` — all pure, so `selftest.mjs` covers them and `export-signals.mjs` only does I/O. **Toolchain-extension math (2026-08, commit 1):** `pearson()` (null, never NaN, on zero variance — a NaN would fail the `>0.7` surcharge gate open), `median()`, `pctChange()`, `consecutiveRun()`, `smaSlope()`, `stdev()`, `logReturns()`, `alignSeries()` (inner-joins two date-keyed series — the crypto-vs-equity weekend-drop join). **Daily trend (commit 2):** `dailyTrend()` derives every `frChannel()`/`frB.*` daily input from a raw OHLC session array — encodes `ma200_falling` as strict `<0`, the 50/200 gap-narrowing check on `\|gap\|` (not the signed value), and `bounce_age_sessions` as sessions SINCE the 40-session low (not low-to-high, which is emitted alongside as `sessions_low_to_high`); `frStallConfirmation()` is the FR §4B single-session stall check. **FR composite + bands (commit 4):** `fr.distributionBand()`/`fr.vulnerabilityBand()` (§4A legs, count-of-3 clamped 0–3 — deliberately NOT aliases of `frB.structureBand`, since they mirror different SKILL sections); `frComposite()` is the score arithmetic extracted verbatim from `lint-report.mjs`'s inline check (shared by FK and FR — FK calls it with `penalty:0`), clamping `mechanical`/`adjusted` to 0–20 BEFORE any cap, and gating the cap on `cap.applied` (not merely `cap` being present). **FR companion (commit 6):** `frCompanion()` computes the mandatory Hard Rule 5 companion score end-to-end from one fetch run's `market` data plus the analyst `counts` with no classifier (on-chain / options / flows / swing-point judgment). Routes via `frChannel()`; Channel B scores the §4B legs, Channel A and stand-down ('none') both score §4A (stand-down still needs a headline number for cross-validation). A missing count scores 0 but is recorded in `inputs_missing` with a `score_floor`/`score_ceiling` bound; if that range straddles 9 or 12, `hard_rule_5_dischargeable` is false. `oi_within_5pct_of_90d_high` unknown reports as `null` in the output but is treated as `true` INTERNALLY for the squeeze-trap penalty — the alternative (assuming false) suppresses the escalation, which is fail-open. **Spot panel (commit 7):** `spotPanel()` computes a proper median over multi-venue quotes — a `bar_close` quote (Yahoo daily candle) is always frozen out of the median; a stale-but-close quote is excluded without being flagged; a stale AND divergent quote is excluded WITH an explicit reason + age; spread is exactly-0.5%-is-not-flagged (strict SKILL letter); zero usable quotes degrades to `canonical:null`, never a throw. **Funding (commit 8):** `fundingBlock()` converts Binance's raw fundingRate FRACTION to the PERCENT `fr.annualizedFunding()` expects (a 100× unit trap), and emits BOTH `longest_negative_run_intervals` (8h prints, what FK capitulation-(b) counts) and `longest_negative_run_sessions` (calendar days, what FR-B relative-sentiment-(b) counts) under unit-explicit keys — conflating them is up to a 3× scoring error. `oi_90d_high_available` is always `false`: Binance's OI history only covers ~30 days, and `fr.squeezeTrapPenalty()` wants a 90-day high — a 30-day number never passes itself off as one. **Correlation (commit 10):** `correlationFromCloses()` joins two `{date,close}` series (`alignSeries` — the crypto-7d/equity-5d weekend-drop join). *(Corrected 2026-08 — see the market-data-extension entry below: this originally ran `pearson()` on the aligned PRICE LEVELS, which is stale as of the A1 fix; it now aligns dates first, then takes `logReturns()` of each aligned series, then runs `pearson()` on those.)* `correlationRegime()`'s label ladder (inverse/decoupled/mild/risk-on) is descriptive only; only `corr > 0.7` (surcharge) and `corr < 0.8` (Phase 2 condition) carry consequence — asserted by dedicated vector names so a label edge is never mistaken for a threshold. `corr: null` (not computed) routes to surcharge OFF / Phase 2 satisfied, the SKILL's documented default. **Snapshot digest (commit 11):** `snapshotDigestPayload()` defines what a `tools/snapshot.mjs` run_id/sha256 is keyed on — `fetched_at` and each block's `errors[]` are stripped, so a transient venue timeout or the seconds between two fetches never fork the run id for otherwise-identical data. **Trading-day calendar (commit 13):** `US_MARKET_HOLIDAYS` (pinned, dated, cross-checked by direct weekday computation — not copied from memory), `isTradingDay()` (equity excludes weekends + holidays; `crypto` is ALWAYS true — trades every day), `nextNTradingDays()`, `tradingDaysBetween()`. Good Friday (equities closed, crypto open) and the July-4-observed-on-a-Friday case are both pinned vectors. **Market-data extension (2026-08) — DISCLOSED CONTEXT ONLY, no band/gate/threshold/phase-size/stop/cap reads from any of these; promoting one into the rubric is a `framework-calibration` job, not a toolchain one.** Fixed one live defect first: `correlationFromCloses()` now aligns dates THEN takes `logReturns()` of each aligned series THEN runs `pearson()` — it previously ran Pearson on raw price LEVELS while every machine block already claimed "log returns," which is spurious between two trending series (a selftest vector proves two independently-trending synthetic series read >0.9 in levels but <0.5 in log returns). **Tier 0 (zero new requests):** `percentileRank()`/`distributionStats()` (midrank on ties, nulls dropped not coerced to 0); `realizedVol()`/`realizedVolBlock()`/`rollingRealizedVol()` (annualized stdev of log returns; `annualize` is an asset-class convention — crypto 365, equities/gold 252 — supplied by the caller, never defaulted in `lib.mjs`); `rollingWilderRSI()`/`rollingDrawdownFromATH()` (running high SEEN SO FAR, not the eventual series max — no look-ahead)/`rollingSMADistance()`, each feeding a percentile-vs-own-history reading for an EXISTING metric. **Tier 1:** `deribitVolBlock()` — BTC/ETH options vol surface (DVOL, ATM IV, a MONEYNESS-based skew explicitly named `skew_90_110_moneyness_pct` since the book summary carries no per-instrument delta, and VRP = ATM IV − rv30); an EMPTY Deribit book/DVOL (SOL's actual live response — it's a listed currency but returns empty arrays, not an error) reads `available:false`, never a fabricated 0 IV, mirroring gold's absent `funding` block. `basisBlock()` — perp basis + carry, preserving the funding sign convention VERBATIM (positive = longs pay shorts = carry income to a short) rather than restating it. `positioningBlock()` — Binance long/short account ratio, taker buy/sell ratio, open interest; states its own scope in the OUTPUT, not just a comment: ~30-day history cap, Binance-account-weighted single-venue, never market-wide. `netLiquidity()` — WALCL − RRP − TGA; the FRED unit trap (WALCL/WTREGEN in $ millions, RRPONTSYD in $ billions) is converted INSIDE the function, pinned by a vector using the REAL probed magnitudes (~$5.8T). `stablecoinBlock()` — DefiLlama aggregate supply, labeled third-party/back-revision-prone. **Tier 2:** `tripwireDiff()` — pure snapshot-to-snapshot boundary-crossing diff (band edges, gate-6, `frChannel()` routing, funding sign, the FR phase-of-cycle cap tier, an F&G gate-1 streak threshold, and an optional report-authored checkpoint's ADR-distance), reusing the SAME classifiers a report would, never a reimplementation — see `tripwire.mjs`. **FR-parity plan (2026-08-05) — FR's own scored inputs and vetoes had never gotten the FK treatment; every item below is short-side.** **FR1:** `fundingBlock()` gains `longest_run_below_minus5_annualized_intervals`/`sustained3_below_minus5`/`single_interval_below_minus7`/`most_recent_below_minus7_intervals_ago` — the ACTUAL inputs `fr.squeezeTrapPenalty()` needs (funding annualized <−5% sustained ≥3 intervals, Channel B gate 8, the only gate in either framework that voids an unlock on its own). The pre-existing `longest_negative_run_intervals` (merely-negative prints, what FK capitulation-(b) counts) is a ~1000× looser bar and is KEPT, never replaced — a `threshold_note` names the trap explicitly so the two are never conflated. **FR2:** `deribitVolBlock()` gains `skew_sign_convention` in its output (POSITIVE = ~10%-OTM put richer than the ~10%-OTM call = downside hedging bid; a blow-off COMPRESSES or INVERTS the skew, not richens it) — the same discipline as `fundingBlock`/`basisBlock`'s sign lines, added because the FR SKILL's own gloss read backwards. **FR3:** `tripwireDiff()` gains 7 Channel B / FR-only crossings (`fr_euphoria_band`, `fr_momentum_band`, `frb_rally_band`, `frb_momentum_band`, `frb_weekly_rsi50_qualifier` — the weekly-RSI≥50 hard qualifier, a distinct event from a band delta — `frb_maturity_penalty`, `fr_gate8_sustained_negative` — the ACTUAL scoring-relevant funding boundary, as distinct from the pre-existing informational `funding_sign`); every check requires the field on BOTH snapshots, so a prev snapshot predating FR1 yields no fabricated crossing. **FR4:** `rollingBouncePct()` (a FIXED trailing-window low, unlike `rollingDrawdownFromATH`'s running-since-start high) and `rollingTrailingHighDistance()` (a FIXED trailing-window high) feed percentiles for daily RSI, bounce %, and the phase-of-cycle cap's own `high_1y.pct_below` input — three FR-only metrics that never got one despite scoring bigger legs than weekly RSI does anywhere in FK. **FR5:** `borrowBlock()` — Bitfinex spot-borrow, parsing the raw `GET /v2/ticker/f<CCY>` array; FRR is a DAILY rate FRACTION (the same unit-trap class as Binance's `fundingRate`), converted to percent/annualized INSIDE the function at 8 decimal places (6dp double-rounds a chained daily→annualized computation into a double-digit relative error — caught by the selftest vectors before shipping); three caveats (single venue, a LENDING book not the short's actual borrow venue, frequently THIN) stated in the output. **FR6:** `shortEV()` mirrors SKILL §5/§6 letter-for-letter: `carry_ev_pct_true` (signed) vs `carry_ev_pct_floored` (income floored to `min(true,0)` — a real cost still counts in full, income never helps a short clear a gate), the +3% minimum-edge filter and the 40%-of-target carry veto both STRICT `>` (matching the SKILL's "must exceed"/"if carry >" wording exactly), and a `ledger_note` naming the THIRD documented sign trap in this repo: the position snapshot's `funding_usd` is account cashflow and inverts against this market-rate convention. **`sentimentProxyBlock()` (2026-08-05) — UNSCORED.** Gold has no F&G, and the sentiment leg has always taken the NOT-FOUND fallback of 2. Two free daily candidates were backtested over 10y and BOTH REJECTED: GVZ shows no contrarian gradient (fwd-20d by percentile bucket 1.10/1.39/1.20/0.54/1.20/1.48 % — a volatility index is direction-blind, gold IV spikes on crisis-bid melt-UPS too); the PHYS closed-end premium has the right shape (gold price cancels in the ratio; not COT, so no double-key) and a clean full-sample gradient with an arb-pinned GLD control, but INVERTS across split-half (2018-21 deep-discount 60d fwd −1.48%/36% win vs 2022-26 +7.71%/82%), its tail returns 3.67% against a 3.59% unconditional baseline, and its 140 days are only 16 distinct episodes (effective N≈16). Selftest vectors pin the two properties that matter: `scored:false` never flips, and the CEF premium reads ~0 when the trust tracks a TRIPLING reference exactly — proving the underlying price cancels and the block is not a disguised momentum input. **`proximityPanel()` (2026-08-05) — DISCLOSED CONTEXT, NOT A TRIGGER.** Closes §9.2 item 4 of the 2026-08-05 gold report: `tripwireDiff()` only fires AFTER a boundary is crossed, so a metric can sit a hair from a routing change for several reports with the board silent. Reports the DISTANCE from the current reading to every consequential edge — FR Channel A eligibility and both phase-of-cycle cap tiers, FK gate 6ʼs ±8% 200-week band, the 200dma slope sign flip, the next FK momentum-band edge, FR-Bʼs weekly-RSI≥50 hard qualifier, the next FK sentiment-band edge — using the SAME classifiers the frameworks already score with, so it adds no new rubric. On the 2026-08-05 gold snapshot it reproduces that reportʼs own two untracked observations (Channel A 2.78pp away = +3.69% of price; 200dma slope 0.35pp from its flip) and surfaces one the report MISSED: weekly RSI 38.98 is 1.02 points from 40, where the momentum leg drops 2→1. `price_move_required_pct` is emitted only where the boundary is invertible in price — RSI/sentiment edges deliberately carry `null` rather than a fabricated precision. `near` is a PER-METRIC band (5pp price, 0.5pp slope, 3 RSI/index points), never a percentage of the thresholdʼs own magnitude: 25% of the RSI-50 qualifier would be 12.5 points — most of a bandʼs width — and 25% of a 0-valued slope threshold is 0, so a slope could never register. Selftest pins the FK edge convention it exposed: edges belong to the HIGHER-score band, so a leg is lost by EXCEEDING an edge, not by reaching it — evaluating the consequence AT the edge reports "drops from 2 to 2" and hides the transition. |
| `compute.mjs` | CLI over `lib.mjs`. `node tools/compute.mjs <rsi\|thresholds\|round\|band\|ev\|stop-coherence\|adr\|streak\|fr-funding\|fr-cap\|squeeze\|sma\|drawdown\|trend\|stall\|fr-composite\|fr-companion\|corr\|tier1\|percentile\|rvol\|vol-surface\|basis\|positioning\|netliq\|stablecoin\|marketdata\|short-ev\|borrow> ...` — every command echoes inputs so the JSON can be pasted into a report's audit trail. `trend` derives every daily-timeframe `frChannel()`/`frB.*` input (RSI-14, 50/200dma, 200dma slope, 40-session low, bounce %, bounce age SINCE the low) from a raw OHLC session array — this is the block that used to be hand-computed off-tool. `stall` checks FR §4B single-session stall confirmation. `fr-composite` runs the shared FK/FR score arithmetic (leg sum → mechanical/raw/adjusted, clamp, cap). `fr-companion` runs the end-to-end mandatory FR companion score from market data + analyst counts. `corr` runs `correlationFromCloses()` — the join + log-return Pearson correlation vs SPX. `tier1` checks `calendar-tier1.json` for tier-1 macro releases inside the next N trading days from a date, warning loudly if any entry's `verified_on` is >30 days stale or the window runs past the calendar's last entry. **Market-data extension (2026-08), all DISCLOSED CONTEXT ONLY:** `percentile` (percentile rank + distribution stats of any series); `rvol` (rv10/rv30/rv90 + rv30's own percentile); `vol-surface` (Deribit ATM IV / skew / VRP; BTC/ETH only); `basis` (perp basis + carry vs a supplied risk-free rate); `positioning` (Binance long/short + taker + OI, ~30d history); `netliq` (Fed net liquidity — pass WALCL/WTREGEN in FRED's $ millions and RRPONTSYD in FRED's $ billions; the conversion happens inside `netLiquidity()`); `stablecoin` (DefiLlama aggregate supply); `marketdata --asset <asset> [--max-age-days N]` (reads `marketdata.json`, warns on stale/missing entries, mirrors `tier1`'s discipline). **FR-parity plan (2026-08-05):** `squeeze --funding-annualized X [--sustained3] [--oi-within-5pct] [--single-below-7]` (the FR SKILL §4 squeeze-trap penalty — Channel B gate 8, the only true veto in either framework; take `--sustained3` from `fundingBlock()`'s `sustained3_below_minus5`, never `longest_negative_run_intervals`) — NOT disclosed context, a real gate. `short-ev --directional-ev N --funding-annualized N --hold-days N [--target-gain-pct N]` (SKILL §5/§6 — carry zero-floor, the +3% minimum-edge filter, the 40%-of-target carry veto; also NOT disclosed context). `borrow --ticker <@file.json\|json>` (Bitfinex spot-borrow, DISCLOSED CONTEXT ONLY — single venue, a lending book, frequently thin). |
| `fetch.mjs` | Live numeric backbone: `node tools/fetch.mjs btc\|eth\|sol\|gold\|spx\|ndx\|macro`. Sources: CoinGecko (spot, ATH), Yahoo chart API (weekly/daily candles → RSI-14, 200-week SMA ±8% gate-6 check, ADR sessions, 1-y high for the FR cycle cap), alternative.me (F&G spot / 3-day avg / gate-1 daily-print streaks), FRED DFII10 (10y real yield). Cross-checks spot across sources and flags >1.5% divergence. **Does NOT cover ETF flows (Farside is bot-blocked), on-chain, or news — those stay live web fetches per Hard Rule 1.** `node tools/fetch.mjs spot <asset>` prints just the spot block. **Spot panel, step B COMPLETE (commit 12, 2026-08-03):** `spot.panel` adds Binance/Coinbase/Kraken venue quotes and computes a proper median per FK SKILL [R:canonical-spot], and **`spot.canonical` IS that median** — as is the `spot` value every downstream consumer reads (ATH drawdown, the 200-week SMA's `pct_vs_spot` and its gate-6 ±8% boolean, `trend`/ma200), which is what makes the flip real rather than cosmetic. `spot.canonical_source` reports `panel_median` or, when no venue quote is usable, `priority_first_fallback` (a partial fetch still yields a scorable report instead of a null spot). `spot.canonical_median` survives as a **deprecated echo** for step-A consumers and `spot.method_conflict` is now `null`. Observed flip delta on the 2026-08-03 live run: BTC −0.02%, ETH −0.07% — **gate-6 boolean unchanged on both** (BTC true, ETH false) and **FR phase-of-cycle cap tier unchanged on both** (8, >20% below the 1-y high). Gold has no crypto venues and degrades honestly to `low_confidence:true`, never a crash. **Transport hardening (commit 11):** `getJSON()` now times out at 8s and retries twice on network errors/5xx only (never 4xx) — a hung venue no longer blocks the whole `Promise.all` forever. `fetchAsset`/`fetchMacro`/`ASSETS` are exported and the CLI is guarded behind an `import.meta.url` check, so `tools/snapshot.mjs` can import this file without triggering its command-line behavior. **Funding (commit 8):** `funding` (Binance fapi, 45×8h intervals) is ABSENT (not a zero-filled block) for assets with no perp, e.g. gold. **Macro additions (commit 9):** `irx` (Yahoo ^IRX, 13-week T-bill) + FRED DGS3MO cross-check feed `dry_powder_benchmark` (the idle-cash opportunity-cost figure); `spx.series` carries the full daily close series (3mo range) for commit 10's correlation join. **Market-data extension (2026-08) — every field below is DISCLOSED CONTEXT ONLY, top-level `outp.context` (never nested under `score`/`weekly`/`trend`), and reads no scoring band, gate, threshold, phase size, stop, or cap:** every asset's `fetchAsset()` now carries `context.realized_vol` (rv10/rv30/rv90 + rv30 percentile vs its own 2y), `context.drawdown_pct_vs_2y_high`(_percentile) (running high WITHIN the fetched window, explicitly not the true ATH), `context.distance_to_200dma_pct`(_percentile), `context.weekly_rsi14_percentile`, `context.volume` (raw + percentile only — **no synthesized `turnover_usd`**: Yahoo's `volume` units are NOT consistent across tickers, verified live as USD-denominated quote volume for crypto pairs but CONTRACT COUNT for `GC=F`, so multiplying by spot would be wrong by orders of magnitude for one or the other), `context.fng_percentile_vs_2y` (F&G request bumped `limit` 30→730, same single request — the SCORED streak fields are still computed off a `.slice(0,30)` so they stay byte-identical), `context.funding_annualized_percentile_vs_history` (Binance funding `limit` bumped 45→1000, same single request — `fundingBlock()` still slices the last 45 internally so `outp.funding` is unchanged), `context.deribit` (BTC/ETH only — `ASSETS.<asset>.deribit`; absent, never a fabricated 0, for SOL/gold), `context.basis` (perp basis/carry, needs `ASSETS.<asset>.perp`), and `context.positioning` (Binance long/short + taker + OI, same `perp` gate). `fetchMacro()` additionally carries `hy_oas`/`nfci` (FRED, credit stress/financial conditions), `net_liquidity` (FRED WALCL/RRPONTSYD/WTREGEN — see `netLiquidity()`'s unit-trap note in the `lib.mjs` row), `move` (Yahoo ^MOVE, bond vol), and `stablecoin_supply` (DefiLlama, asset-agnostic). **FR-parity plan (2026-08-05):** `context.daily_rsi14_percentile_vs_2y`/`context.bounce_pct_percentile_vs_2y`/`context.high_1y_pct_below_percentile_vs_2y` — the three FR-only metrics (Channel B momentum leg, the §4B rally leg, the phase-of-cycle cap's own input) that never got a percentile despite scoring bigger legs than weekly RSI does anywhere in FK; zero new HTTP requests, derived from series already fetched. `context.borrow` (BTC/ETH/SOL only — `ASSETS.<asset>.bitfinexFunding`; absent, never a fabricated zero, for gold/spx/ndx) — Bitfinex spot-borrow, single-venue/lending-book/thin caveats stated in the output. `ASSETS` also gains `spx`/`ndx` (Yahoo `^GSPC`/`^NDX`, shaped identically to `gold`) so a non-crypto Flying Rocket adaptation (already run five times ad hoc: gold ×2, UNI ×2, SPX) is reproducible from the committed repo — this does not put an index in FR's declared scope, which is still gated by the SKILL's own §2.5 caveat. **ATH verification (2026-08-05):** for a Yahoo-sourced `athRange` asset (gold/spx/ndx) the window high is no longer disclaimed as "NOT all-time" — the tool also pulls the `max`/`1mo` series, compares the PRE-window maximum, and emits `all_time_verified` + `pre_window_high`. Gold verifies true (pre-window max 1911.60 @ 2011-09 vs a 5586.20 in-window high), discharging the "10y window high, not a verified ATH" stale-input-debt line every gold report has carried. The comparison is one-sided by design — monthly bars can clip an intra-month spike, so it can only CONFIRM the window dominates, never silently overturn it. **Gold sentiment proxies (2026-08-05):** `context.sentiment_proxy` (gold only) emits GVZ percentile + the PHYS closed-end premium/discount, both flagged `scored:false` — see `sentimentProxyBlock()` for the 10y backtest that keeps them OUT of the scored sentiment leg. |
| `lint-report.mjs` | `node tools/lint-report.mjs reports/<file>.md [--legacy]` — validates the report's ` ```json machine ` block (schema `report-machine/1`): filename convention, legs-sum(+FK D1 discretionary term)→raw, per-asset rounding + FK 0–20 clamp, gate denominator/thresholds/[V]-count, EV recompute (0.5% relative tolerance), Rally ≤50% cap, deployed+dry=100, stop coherence, FR mandatory price+time stops and ≤50% cap. **FK Analyst Discretion Layer (2026-07-27):** `score.discretionary` required and bounded ±2 on a 0.5 step (warning-only for reports dated before 2026-07-27), a D5 hard stop ≤15% below fill on every **analyst-channel** tranche (`channel` D1/D2) with no analyst Phase 3, ≤40% non-mechanical capital with the Override's ≤25% counted inside it (`channel: "override"` is mechanical — capped, but exempt from D5 and the Phase-3 bar), and the cut score unlock lines (1A ≥8, 1B ≥11, 2 ≥15, 3 ≥17) enforced on filled tranches. **FR two-channel + S1–S6 (2026-07-27), all fail-closed:** `channel` ("A"/"B"/"none") required, and `"B"` additionally requires a `regime` block proving >20% off the 1-y ATH, a falling 200dma, and price below it — a bear-continuation short cannot be written outside the regime that defines it; `score.discretionary` and `score.mechanical` required and arithmetic-checked (FR mechanical includes the penalty and the Channel A cap, excludes only the S1 term); analyst tranches (`discretionary:true` + `channel` S1/S2) take the S5 tax — stop ≤6% **above** fill, clock ≤14d, ≤20% of book, S2 is Phase 1A only, no Phase 3; Channel B tranches are ≤30% of book, barred from Phase 3, clocked ≤21d (≤28d at Phase 2), and may not declare a cycle cap; `prior_stop` triggers the S6 ratchet check. Mis-encoding an analyst fill as `discretionary:false` is an error, not a silent cap bypass. **Fill encoding (2026-07-29):** every mechanical check above is gated on a tranche being FILLED, and that predicate — `deployed === true \|\| typeof entry === 'number'` — had **never once been true**: all 152/152 tranches across 39 reports encode `entry` as prose. Score unlock lines, gate floors, stop bands, size caps and the ratchet were unreachable code, and a 20%-of-book Phase-3 short at score 0/20 on 0/9 gates with a stop 40% BELOW entry linted clean with zero warnings. `report-machine/1` now takes optional numeric `entry_price` and boolean `deployed` alongside the prose `entry` (which keeps its own meaning — which zone, why blocked, blended MTM); a prose `entry` that reads like a fill without an `entry_price` **warns** before 2026-07-29 and **errors** on/after. This relaxes nothing — it makes existing discipline bind, including four of Hard Rule 6's seven never-relax items. **`companion_fr`/`correlation`/`trend_residual` (2026-08, commit 14):** before `COMPANION_FR_EPOCH` (2026-08-03) both the legacy nested `inputs.companion_fr={composite,gates,cap_bound}` shape and the top-level shape are tolerated with a migration warning; on/after, `companion_fr` is required top-level with `score` (0-20), `channel` EXACTLY `"A"`/`"B"`/`"none"` (a compound string like `"none — STAND DOWN"` moves its prose to `channel_note`), a complete `regime`/`routing` block when `channel:"B"`, `cross_validation`, and `standalone_report_owed` forced true at `score>=9` unconditionally. `correlation` is optional, no epoch, error only when internally inconsistent (`surcharge_applied` must equal `corr>0.7`; `phase2_corr_condition` — prose or boolean — must not contradict `corr<0.8`); a `correlation.method` string that reads "price-level" without "log return" now also WARNS (no epoch), since `correlationFromCloses()` computes log returns. `trend_residual.active_downtrend` must be a boolean if present. **`marketdata.json` backing (2026-08, warn-only, no epoch):** if `key_inputs` cites `mvrv_z`/`realized_price`/`lth_mvrv`/`sth_mvrv`, warns when `tools/marketdata.json` has no dated entry for that asset+metric — a local file read, not a network call. Exit 1 = fix before committing, never override. |
| `position.mjs` | **Position of record (Hard Rule 8).** `node tools/position.mjs <asset\|all> [--file <path>] [--max-age-min N] [--fills N]` — reads `position-snapshot.json`, exported from the personal-accounting ledger and derived from **actual Binance fills**: real quantity, ACB cost basis, unrealized/realized PnL, **real dry powder**, open futures positions, fill-level history, closed round-trips, and win rate / profit factor / expectancy **per tag and per framework prefix**. Freshness is **event-driven by default**: the newest structurally valid export remains the position of record regardless of elapsed time because the owner exports after each new trade. `generated_at`, `source.holdings_as_of`, and futures component clocks remain audit metadata; missing/incomplete futures status or current-sequence income coverage still limits the claim. Pass `--max-age-min N` only for an explicit strict-time audit; strict mode bands on `generated_at` only, with missing/unparseable `generated_at` failing closed. **Exit codes are the contract:** `0` FRESH by default or FRESH/STALE under strict mode (STALE is descriptive only and may **not** satisfy a phase-dependent unlock precondition or fill a realized ledger column); `1` EXPIRED / missing / unparseable / wrong schema → **cold start per Hard Rule 4, stated explicitly**; `2` NOT_COVERED (an asset with no ledger counterpart **and no alias**) → carry state forward from the prior report, and **never read a zero position from it**. **`gold` aliases onto PAXG** (2026-07-28) and comes back covered, carrying `requested_asset`/`ledger_asset`/`alias_note` so the report can say the position is held as tokenized gold rather than bullion. Every covered response also carries a **`custody`** block (2026-07-29), read before any quantity: `RECONCILED` (live agrees with the replay), `EXPLAINED_BY_EXTERNAL_TRANSFER` (the shortfall matches recorded net withdrawals — report the asset **HELD OFF-VENUE** quoting `off_venue_qty`, **never** as flat or exited, and say the mark is custody-adjusted), `EXPLAINED_BY_SYNTHETIC_OPENING_BALANCE` (the shortfall matches a synthetic `OPENING_BALANCE` seed the ledger's floor migration carried across its data floor — an **accounting artefact, not coins**: report the **live** quantity as the position, never the replayed one, and treat that asset's cost basis and PnL as contaminated), or `UNEXPLAINED` (the shortfall has no accounting — a **data defect, not a position**: report no figure in either direction). A custody-adjusted mark is a belief the ledger cannot verify — it cannot tell cold storage from a sale on another venue — so it never satisfies a phase-dependent unlock precondition. Every covered response also carries a **`basis`** block (2026-07-29), orthogonal to custody: custody asks where the coins are, `basis.reliable` asks whether the ledger knows what they cost, and an asset can be fully `RECONCILED` on quantity with no derivable basis. It goes false when the replay disposed of more than it ever saw acquired — a margin short, or an acquisition that was never ingested, and the ledger cannot tell which. Then: no average cost, cost basis, unrealized PnL or ROI is quoted, realized PnL is an **upper bound** (a short realizes against a zero basis), and the quantity alone stands as the position. This exists because the ledger's engine used to snap "sold more than held" to zero as if it were dust, so every margin short round trip added its full size to the position — reporting **833.5 SOL against a true 1.98** on the real account. Two carve-outs survive even at FRESH: snapshot marks are informational and never become canonical spot, and phase attribution comes from **deal tags only** — an untagged holding is reported real-but-`UNTAGGED`, never inferred from size or timing. Deliberately **not** a `fetch.mjs` subcommand: `fetch.mjs` is network-only with a public source on every block, and that is what makes Hard Rule 1 auditable. |
| `export-signals.mjs` | **The A → B contract.** `node tools/export-signals.mjs [--dry-run] [--strict] [--out <path>]` — projects every report's machine block into `exports/signal-feed.json` (schema `signal-feed/1`) for the personal-accounting ledger. The **only tool here that writes**, and it refuses any `--out` outside `exports/` rather than clamping it. Committed output, so: byte-stable serialization (keys sorted recursively, 2-space indent, trailing newline) and skip-write-if-unchanged-except-`generated_at`, or every regeneration is diff noise. **Scans by the filename regex, never by "contains a machine block"** — `grep -l '```json machine' reports/*.md` returns 40 files but only 39 are reports; `calibration_ledger.md` quotes the fence in prose, and a grep-first scanner ingests the calibration ledger as a signal. Missing fields from the older epoch are **resolved, not marked unknown**: a pre-2026-07-27 FR report has no `channel` because Channel B did not exist, so the feed emits `channel:"A"` with `channel_inferred:true` and a basis; a missing `score.discretionary` predates the Analyst Discretion Layer, when discretion was structurally impossible. Channel B reuses Channel A's five leg **keys** for a different rubric, so legs are emitted as an ordered array of `{ordinal, block_key, rubric_name, value, max}` under a `rubric` discriminator (`FK/1`\|`FR-A/1`\|`FR-B/1`) — there is no representation in which `euphoria` silently means rally extension. The 66 prose-only reports predating 2026-07-11 are skipped **loudly** (`skipped[]` + a stderr summary) and are expected, never failures; `--strict` fires only on a post-epoch report with no block. See `exports/README.md`. |
| `selftest.mjs` | Regression vectors for `lib.mjs` (includes the ETH `ceil(7/9×8)=7` misprint and the gold no-flush valuation cap as permanent regressions), plus `calib-corpus.mjs`/`calib-registry.mjs` wiring vectors (byte reconciliation, fail-open on a non-matching heading/unparseable block, registry schema validation, the keyword-overlap matcher) and `calib-run.mjs` orchestration vectors (schema validation incl. nested arrays/enums, event-report detection, `--max-per-series` never dropping an event, strictest-wins vote merge, triage cluster rebuild dropping nothing silently, zero-tune dimension detection). Run before calibrations and after any `lib.mjs` edit. |
| `calib-corpus.mjs` | **New (2026-08, framework-calibration cost pass; extended 2026-08-06).** `node tools/calib-corpus.mjs --since YYYY-MM-DD [--until YYYY-MM-DD] [--framework fallen_knives\|flying_rocket] [--asset btc,eth,gold] [--max-per-series N] [--out .calib-run/<dir>]` — deterministic corpus selection + slicing for `framework-calibration`, replacing its hand-typed `REPORT_FILES` array. Filename-first scan via `reportFileMeta()`, mirroring `export-signals.mjs`'s discipline (`calibration_ledger.md`/retrospectives excluded structurally, never by an exclude-list). Per selected report, writes a `.slice.md` (the report text minus the ` ```json machine ` block minus the "Verified Live Data Points" section minus the "\<Framework\> Composite Score" section — each matched by HEADING TEXT, never by section number, since measured numbering is not stable across the corpus) and a `.digest.json` (the machine block's numeric fields projected — score/gates/EV/deployment/stops — so extraction agents never re-read or re-derive them). **Drop-list, not keep-list, and fail-open throughout:** an unparseable or absent machine block, or a non-matching section heading, passes the report through UNMODIFIED with a loud manifest flag — a missed match costs tokens, never coverage. `--max-per-series N` (default 12) caps a series without ever dropping an **event** report (no digest, a `gates.passed` change, a tranche/stop change, a >1-point score move, or an FK/FR unlock-line crossing) — if events alone meet or exceed the cap, ALL of them are kept and `cap_exceeded_by_events` is set; only the non-event remainder is evenly sampled down. `corpus.json` (parsed identity + byte accounting per report) is the `REPORT_FILES` replacement; `manifest.json` carries the aggregate reduction, every coverage flag (`byte_reconciliation_failures`, `verified_data_section_not_matched`), and now `sampled_out`/`cap_exceeded_by_events`. Measured on the 2026-07-04→2026-08-05 FK window (49 reports, cap non-binding — every report is an event): **42.1% byte reduction** (up from 31.9% before the Composite-Score drop), 0 reconciliation failures. A parallel measurement — a stricter "identical score/gates/tranche fingerprint vs. the prior report" compression pass — was tried and **rejected on evidence**: 0 of 60 reports in the window qualified. Output is gitignored (`.calib-run/`) — reproducible from `reports/` on demand, not repo state. |
| `calib-registry.mjs` | **New (2026-08, framework-calibration cost pass).** Structured, append-only tuning history at `reports/calibration-registry.json` (schema `calibration-registry/1`), replacing the per-run agent that re-derived the prior-rejection list from prose ledger memos every calibration. `node tools/calib-registry.mjs list [--framework <t>] [--verdict adopted\|adopted_with_modification\|rejected\|withheld\|unadjudicated] [--since YYYY-MM-DD] [--json]`, `validate`, `append <payload.json>`, `match "<tune name or keywords>"` (a loose keyword-overlap pointer against rejected/withheld entries — never an automatic verdict, always something for a Diagnose/Verify agent to go confirm or refute). Backfilled with the rejections (and, where the ledger named them individually, the adoptions) from all four real calibration runs to date; entries recoverable only as an aggregate count in the prose ledger carry `source:"ledger-prose"` and a pointer to the memo rather than an invented per-tune breakdown. The prose ledger (`calibration_ledger.md`) stays the human-readable narrative; this file is its structured, enforcement-grade twin. |
| `calib-run.mjs` | **New (2026-08-06, framework-calibration execution-model pass).** The canonical execution path for `framework-calibration`'s 5-phase adversarial pipeline — replaces reliance on the packaged `Workflow` tool, unavailable in 2 of the last 3 real invocations of the skill and in the session that built this driver. Has real filesystem access (unlike the `Workflow` template it replaces) but no agent access: `init` writes a run's config (`--corpus`, `--mode full\|scoped\|meta`, `--position`, `--anchors`, `--registry`, `--prior-calibrations`); `plan <phase>` writes one PROMPT FILE per task into `.calib-run/<run>/0N-<phase>/tasks/` (the calling agent loop Reads each and spawns one `Agent` call, model chosen from the run's map — Extract=haiku, Grade=sonnet, Diagnose/null-adversary/Verify/Synthesize=opus); each agent Writes its JSON result to the `out/` path the prompt names and replies with only a one-line `OK`/`FAIL` status, so payloads never enter the orchestrating conversation's context; `collect <phase>` validates every output against the phase schema (`validateSchema`/`SCHEMAS`, lifted verbatim from `backtest-workflow.template.js`) and performs the deterministic join/merge, **exiting 1 naming only the failed `task_id`s** — a missing/malformed output is resumable (re-spawn that one agent, re-run `collect`) rather than losing the whole run. Phase barrier is mechanical: `plan <phase>` refuses while the prior phase isn't `collected`. Diagnose and Verify each carry internal sub-rounds — Diagnose's zero-tune dimensions trigger a `03b-null-adversary` round that `plan verify` refuses to skip past (Principle 9, the exact ordering bug 2026-08-05b exhibited); Verify runs triage (if >8 tunes) → skeptic panels (batched, solo for capital-deployment tunes) + applied-edits audit → pre-apply audit, each gated the same way. Exports pure functions independent of the file-based state machine — `isEventReport`/`selectWithCap` (re-exported from `calib-corpus.mjs`), `zeroTuneDiagnoses`, `mergeStrictestWins`, `applyTriageClusters`, `validateSchema` — which `selftest.mjs` pins directly. `backtest-workflow.template.js` remains valid if `Workflow` is ever available but is **not** the reference implementation (it cannot import this file's logic — a Workflow script has no filesystem access — so the two can drift; re-verify the template by hand against these vectors before relying on it). |
| `calendar-tier1.json` | **New (commit 13).** Hand-maintained, dated, sourced tier-1 macro release calendar (FOMC, NFP) for the current + next quarter. Not a live API by design — no deps, no secrets story, and this repo auto-pushes to a public remote. Each entry carries its own `source` + `verified_on`; `compute.mjs tier1` warns loudly when an entry is stale or the requested window outruns the data. |
| `snapshot.mjs` | **Run cache (commit 11).** `node tools/snapshot.mjs btc,eth,gold [--macro] [--reuse <run_id>]` — fetches once via `fetch.mjs` and writes `data/runs/<run_id>/snapshot.json` (`/data/` is already gitignored), `run_id = <YYYYMMDD-HHMM>-<sha8 of the digest>`. Every report drawn from one run cites the same `run_id`/`sha256` in `key_inputs.data_snapshot`, making "one snapshot per report run" provable rather than asserted. `--reuse <run_id>` replays a stored snapshot byte-identically (plus `replayed_from`/`age_min`) instead of re-fetching. Refuses any `--out` outside `data/`, mirroring `export-signals.mjs`'s `exports/` guard. |
| `marketdata.json` | **New (market-data-extension plan, D1).** Hand-maintained, dated, sourced manual on-chain inputs (MVRV-Z, realized price, LTH/STH MVRV) — mirrors `calendar-tier1.json`'s discipline exactly: schema header, per-entry `source` + `verified_on`, a `known_gaps` array, and "every entry needs its OWN `verified_on`." Deliberately **not** automated — the value in these inputs has historically come from *rejecting* bad data (a stale funding claim, a two-month-old MVRV-Z, a mismatched-venue liquidation figure), which an API would swallow. `compute.mjs marketdata` reads it; `lint-report.mjs` warns (never errors) when a machine block cites one of these metrics with no backing entry for that asset. |
| `tripwire.mjs` | **Snapshot boundary tripwire (market-data-extension plan, D2; Channel B / FR-only crossings added by the FR-parity plan, FR3, 2026-08-05).** `node tools/tripwire.mjs [--dir data/runs] [--checkpoints <json>]` — reads the two NEWEST stored `snapshot.mjs` records and reports only SCORING-RELEVANT crossings (FK sentiment/momentum band edges, the gate-1 streak threshold, gate-6's `within_8pct` boolean, `frChannel()` routing, the FR phase-of-cycle cap tier, FR §4A euphoria/momentum band edges, every §4B Channel B band edge — rally, momentum, the weekly-RSI≥50 hard qualifier, the bounce-maturity penalty — the ACTUAL scoring-relevant funding boundary (`fr_gate8_sustained_negative`, distinct from the informational `funding_sign`), and — only if `--checkpoints` supplies a report-authored line — a whole-ADR-unit change in distance to it), each via `tripwireDiff()` calling the SAME classifier a report would use, never a reimplementation. Every FR check requires the field on BOTH snapshots — a prev snapshot predating FR1/FR4 yields no fabricated crossing. Pure diff logic lives in `lib.mjs` (selftested); this file only does the read + print. No network. No writes at all — read-only, with a read-boundary guard mirroring `snapshot.mjs`'s write guard. **Local script, run by hand — no scheduler, no cron, no auto-commit.** |

Workflow per report: `position` → `fetch` → score with `compute` outputs → save report ending in the machine block → `lint` → `export-signals` → commit. `position` comes first because what you already hold changes what the rest of the report is deciding — sizing a Phase 3 before checking the dry powder exists is how a plan ends up spending money it does not have. The SKILLs' "Deterministic Toolchain" sections are authoritative for what is mandatory.

### Strategy-research/3 authoritative path

Use `strategy-research.mjs evaluate-v3` for new research, with
`--experiment`, `--manifest`, `--features`, `--labels`, and `--candidates`.
EXPOSED_CONFIRMATION additionally requires `--parent-evidence` pointing to the
validated prior WFO bundle. It requires a frozen v3 experiment/candidate set,
PIT feature/label sets and a `strategy-data-manifest/2`; it writes a content-addressed
`strategy-evidence-bundle/2`. The bundle derives every candidate × asset row,
runtime candidate accounting, compact canonical selected/OOS trades, metrics, stress and a union-timeline
mark-to-market crypto portfolio through the registered `swing-engine/1`
adapter. It binds executor source/config, data/feature/environment/package
hashes, chronology and reconciliation hashes. Use --record-root to append
the bundle and linked strategy-run/3; --out alone is not a durable registry
record. The old `run` command is
read-compatible import/migration only: caller metrics/trades/stress/portfolio
are `EXTERNAL_EXPOSED`, cannot satisfy an authoritative gate, and never produce
`ACTIVE` or `CANDIDATE_REVIEW`. Local `SEALED_CONFIRMATION` is rejected;
confirmation/prospective evaluation requires a frozen per-asset selection and
behavioral alias/K contract, while WFO selection and K are TRAIN-only per
declared fold. Unsupported options,
multi-leg carry/basis, HFT/arbitrage, queue/latency and atomic-spread claims
fail closed or remain diagnostic/SHADOW.
