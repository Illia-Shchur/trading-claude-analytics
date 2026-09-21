# Liquidation exploratory v003

This successor records the owner-authorized preliminary backtest before historical outcomes are evaluated. It preserves v002 and the liquidation-structure family; it does not reset previous exposure.

The immutable [policy](exploratory-policy.json) and [precommit](frozen-precommit.json) govern this run. The independently reviewed revisions permit a DEVELOPMENT run below 30 groups, use hourly execution and trade-price mark proxies, assume contract rules, and exclude funding. They do not permit promotion or authoritative WFO claims.

## Fixed experiments

Report all three: core one-entry (1% risk, 2x); three stages without macro; three stages with the S&P addition gate. Both branches retain their structural exits and the 60-day first-fill ceiling. No parameter or winner selection is permitted. The parent unconditional-direction and price-plus-OI controls are deferred; this run cannot establish incremental routing superiority.

Report fixed 1-, 3- and 7-day unlevered responses from the first fully observable qualified shock and separately the first routed entry decision. These price-response diagnostics are before all costs and are distinct from portfolio PnL after assumed execution costs but before funding.

## Dependence and evidence

Market-shock clusters are the transitive union of selected geometry intervals extended by 72 hours, shared across assets. They are defined without returns. Holding-period overlaps are reported separately. Neither count is called proven independent sample size. Shared cluster bootstrap and a conservative shared 67-day calendar-block sensitivity expose dependence assumptions. The 67-day purge and seven-day embargo remain unchanged; no WFO is claimed by this full-sample exploratory run.

The independent pre-freeze reviewer required funding-template consistency, explicit deferral of original direction controls and unrun stresses, exact UTC boundary anchors after all inputs are available, and the core risk allocation. All were incorporated before freeze. Downloaded historical archives remain retrospective proxies. Missingness is measured, not filled as zero; exposure is never erased because a held-position bar is missing.

Raw archives, normalized inputs and detailed outputs remain in gitignored `.research-run/liquidation-exploratory-v003/`. A run must reopen the exact policy/input/executor bytes and retain hashes, all three results, exclusions and low-count warnings. The entire evaluated window becomes exposed DEVELOPMENT evidence.
