# Liquidation daily stress v002 — implementation status

Implementation base: `01d8f02d94f44d00024c3effe2b65b41f990d17b`.
Branch: `codex/liquidation-daily-stress-v002`.
**Engineering implementation and independent review are complete. Historical evaluation remains blocked by the qualifications below.**
The frozen strategy and precommit are unchanged. Software acceptance is not strategy-performance evidence.

## Delivered work

| Work package | Verified result |
|---|---|
| Historical-input qualification | Packaged audit and verification commands reopen the actual retained Coinalyze acquisition, hashes and asset gaps. Every required input has an explicit state and permitted-use limit. Retrieval and assumed availability are separate. |
| Versioned policy and physical contracts | Exact four-asset scope, 60-day lifecycle, 67-day purge, seven-day embargo, execution envelopes and executor identity. Separate feature, label, execution, mark, funding and metadata roles; legacy contracts preserved. |
| Accounting and lifecycle | Shared cash, isolated collateral, three-stage risk and leverage, funding, maintenance, liquidation, common exits and a first-fill holding clock. Independent arithmetic and complete 60-day integration fixtures pass. |
| Causal routing | Completed daily/4h/1h inputs, price/OI geometry, actual-fill acknowledgements, confirmed pivots, ratcheted stops, staged macro gates and retained rejected opportunities. Temporal and paired-control tests pass. |
| Replay and evidence | Budgeted resumable core/staged runners, canonical restart equality, paired predecessor anchors, five stress reruns, intratrade marked-equity paths, chronological folds, dependent resampling and persistent exposure accounting. Synthetic and proxy inputs cannot authorize promotion. |

## Verification

The fresh `./mvnw --batch-mode --no-transfer-progress clean install` passed the full research module, including the complete 60-day staged integration (1,159.858 seconds). It stopped on a stale CLI help expectation; that test was updated additively, preserving legacy commands and stream assertions. Its suite passed 13/13, and the remaining reactor modules completed from `analytics-cli` without cleaning. Subsequent test-only additions passed their focused selectors. An upstream-inclusive no-clean `verify` regenerated the aggregate from the same production bytecode.

The parent independently checked current Surefire XML for **108 changed test classes / 454 test methods**: no failures, errors, missing reports or source files newer than their results. All **422 production/resource files** match the reviewed clean-build snapshot, including checks for added and deleted files. Frozen strategy files, local documentation links and whitespace checks pass.

| Coverage comparison | Executable lines | Branch outcomes | Required gate |
|---|---|---|---|
| Implementation review base `01d8f02` | 9,580 / 10,187 — **94.04%** | 6,925 / 8,651 — **80.05%** | **PASS**, 80% / 80% |
| Remote base `4385b39` including acquisition work | 10,496 / 11,182 — **93.87%** | 7,479 / 9,337 — **80.10%** | **PASS**, 80% / 80% |

Both checks explicitly use `--minimum 80 --branch-minimum 80`. CI now enforces the same thresholds. Its test-job timeout is 90 minutes because the clean local build already took 41 minutes 26 seconds before downstream continuation and later additions; the separate 24-hour research compute cap is unchanged.

The actual packaged qualification commands returned `BLOCKED_FOR_AUTHORITATIVE_RESEARCH` and `PHYSICAL_BYTES_REOPENED_PROVENANCE_UNRESOLVED`. The frozen profile validated with a 60-day lifecycle and authoritative WFO disabled. These expected results confirm enforcement of the data boundary.

See [the independent review](IMPLEMENTATION-REVIEW.md) for corrected defects and arithmetic oracles, and [the command guide](README.md) for execution and resume options. No historical strategy returns were inspected.

## Historical evidence and backtest boundary

The retained Coinalyze acquisition contains 5,997 daily rows: BTC, ETH and SOL each have 1,501; AAVE has 1,494. Its manifest byte SHA-256 is `b52d919d1dbaac0e5ad38a5b373b5ed76e981e9fcbb74e6e5d8266d61da04a6e`; canonical content SHA-256 is `fbafcfb11c6ef19a8f0d4030ca0bc33d58e8c6a4b5442e7d334e1ab19df584ea`. AAVE's seven missing days block affected lookbacks. This retrospective capture does not establish historical first availability or value vintage; t+48 hours remains a disclosed assumption.

Complete historical base-quantity OI, price, minute trade/mark paths, funding settlements and effective contract metadata have not been qualified for the full window. The [source audit](IMPLEMENTATION-SOURCE-AUDIT.md) records public archive probes and their limits. No historical candidate returns have been inspected; synthetic fixture PnL is an accounting oracle, not an investment result.

The implemented conservative 67-day dependence policy permits at most 21 independent episode groups in the frozen decision window, below the unchanged 30-episode floor. This is a sample-window/dependence-policy limitation, not an observed economic rejection. Unknown prior family search exposure also blocks stronger multiplicity claims. Neither complete software nor more archive rows can silently waive these gates.

The authorized scope remains historical research with public/free data. No paid acquisition, provider contact, ongoing capture, live orders or promotion to SHADOW/ACTIVE is included. Follow the [approved implementation and backtest plan](IMPLEMENTATION-AND-BACKTEST-PLAN.md) after engineering acceptance and explicit data qualification.
