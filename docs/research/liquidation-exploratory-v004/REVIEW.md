# Independent review and original-four equivalence

An independent Astra review inspected the frozen policy, premise interpretation, changed router/replay/accounting source, and the regenerated executor-003 historical trace. Final source and actual-trace reviews passed. No numeric entry, exit, stop, target, risk, macro or execution rule was changed. The original four retain their relative asset priority.

The review identified diagnostic defects in intermediate traces: an exclusive-end daily count, stage-cycle state leakage, overwritten pivot-invalidation timestamps and inconsistent terminal labels, missing branch terminal timestamps, and first/all-failure counter inconsistency. These were corrected and the earlier outputs were retained. No threshold was tuned based on profit.

Root independently compared executor 003 against the final v003 historical result: all 110 comparisons passed, covering all six simulations' numeric account states, event-stream hashes, complete positions, equity curves, funnels and the fixed response/dependence diagnostics.

The independent regenerated-trace check verified, in all three original-four variants:

- 676 stress-date traces and 73 admitted setups; 57 entry expiries, 11 branch cancellations and five closed positions.
- Every admitted setup has a terminal reason and timestamp.
- Each first-failure counter is included in the corresponding all-failed counter.
- 1,342 eligible daily-availability dates; no missing dates for BTC/ETH/SOL and six for AAVE within this window.
- The longest BTC trade first invalidates its frozen pivot on October 16, 2025 at 00:00 UTC; its flags, 82 skipped H1 checks, 20 skipped H4 checks and terminal explanation agree.

Run reviewed: `2026-09-21T16-36-11-108323Z-5cbe83db-c6c2-48d1-a39e-814ebcd6d891`. Retained executable SHA-256: `229cbb2465338f8de65b4f8cb10be31a6470ece910c09497c4018eb99547b6d3`.

The original-four result remains development evidence. Exact reproduction verifies this implementation comparison; it does not establish a market edge or validate the expanded universe.

## Final diagnostics policy repair and expanded trace

A later isolated review passed the narrow diagnostics policy repair: v004 exact ordered nine-asset scope and strict diagnostic-only flags are accepted; numeric calculations and v003 fixture behavior are unchanged. An adjacent test helper expected the wrong exception message and was corrected to distinguish audit-policy rejection from predecessor-hash rejection. Final production source matches executable 004 exactly.

Original-four run `2026-09-21T17-12-15-468386Z-f97d095a-6d31-4233-9a31-420a00972e93` again passed all 110 numeric-equivalence checks. Expanded run `2026-09-21T17-11-57-279757Z-89e6598f-b036-479c-97ce-4b524056d430` passed independent actual-trace review across all three variants: 1,407 stress dates, 150 admitted setups, 12 confirmed setups, 16 attempts and eight single-tranche short continuation trades. All admitted setups have terminal reasons/timestamps, first-failure counts are bounded by all-failure counts, and pivot state agrees with its diagnosis.

The reviewer independently rebuilt 52 shared shock clusters from 179 geometry intervals plus the frozen 72-hour rule, and deduplicated 24 variant position records to eight actual holdings in five overlap components. Root separately recomputed exact-decimal PnL for baseline and doubled-cost simulations: all six reconcile to zero difference. Expanded staging blockers are six without an eligible strict pivot, one without favorable H4 after entry, and one frozen pivot invalidated by the trailing stop. No blocking source or trace issue remains.

Final report consistency review also passed: reported counts, response anchors, confidence intervals, dates and source-result/exposure hashes match the retained outputs. A final test review verified a true exact-exclusive-boundary regression and explicit nine-asset loader/policy rejection cases. The empty-account ordering test is named as a serialization-order check, not a claim of capital-constrained entry arbitration.
