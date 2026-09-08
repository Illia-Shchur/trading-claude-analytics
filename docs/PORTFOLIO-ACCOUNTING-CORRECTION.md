# Portfolio accounting correction

`StrategyFixedBaselineV5` remains the frozen historical evaluator. Its portfolio
books and retained hashes are never rewritten. New work that needs a corrected
equity curve calls the additive `StrategyFixedBaselinePortfolioCorrectionV1`
API in `analytics-research`.

```java
ObjectNode corrected = StrategyFixedBaselinePortfolioCorrectionV1
        .correctLegacyBook(legacyBook);
```

`legacyBook` is an existing V5 event or control book containing `trades`,
`starting_equity_usdt`, and `ending_equity_usdt`. The method deep-copies its
trades, binds the source book's canonical SHA-256, and returns a new
`strategy-fixed-baseline-portfolio-correction-result/1` receipt. It does not
modify the caller's node. For new evidence, use the typed input contract
`strategy-fixed-baseline-portfolio-correction-input/1` with
`version: 1`, `starting_capital_usdt`, `declared_ending_equity_usdt`, and `trades`.
This is a Java API; no CLI command is added.

An empty trade array is a valid explicit empty book: its curve is empty and
its terminal invariants are vacuously true when declared ending equity equals
starting capital. A nonempty closed book must end at an `EXIT` with no active
or marked positions.

The correction parses all event times as `Instant`, orders equal instants as
`EXIT`, `ENTRY`, then `MARK`, and uses the active interval
`[entry_time, exit_availability_time)`. An exact-exit legacy mark is excluded
and listed in `excluded_exit_boundary_marks`; a mark after exit, duplicate
canonical mark instant, partial exit, invalid lifecycle, or inconsistent P&L
fails deterministically. Each curve row includes `marked_holdings_usdt`,
`active_position_count`, and sorted `active_trade_ids`. The terminal receipt
requires zero active/marked positions and reconciles curve equity with cash,
the declared ending equity, and starting capital plus net P&L.
An empty book has an empty curve and unchanged starting cash. Its closure
invariants are vacuously true; no synthetic EXIT is created. The correction
supports the frozen long spot, single full-exit contract.

The result carries `accounting_version`, `evaluator_identity`,
`source_fingerprint`, `algorithm_fingerprint`, `build_identity`, and a nested
correction receipt. The source fingerprint is the compiled build marker when
available and is reported as `UNKNOWN` when running from an unmarked classes
directory; the deterministic algorithm descriptor remains separately named.

This is an additive correction API. Existing parallel and successor workers
continue to use frozen V5, no new qualification receipt is issued by this API,
and the retained old full-geometry probe remains invalid. Full confirmation
stays blocked until a separately bound corrected worker and qualification
receipt are produced; resource-only hardware measurements remain independent.
