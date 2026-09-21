# Liquidation structure research package

The owner's Q&A is now recorded as a falsifiable conditional long/short strategy. This package freezes a proposed baseline before outcome testing; it does not claim an edge.

- [Specification and rules](SPECIFICATION.md): premise, all agreed preferences, proposed objective event/entry/exit definitions, capital accounting and staged research design.
- [Data feasibility and code gaps](FEASIBILITY.md): actual free-source probes, provenance limits, 60-day/1-hour/multi-tranche gaps, and the reason evaluation remains blocked.
- [Precommit input](precommit-input.json): canonical `strategy-precommit/1` input with explicit UNKNOWN/proxy data classifications.
- [Provenance](provenance.json): inspected Git revisions and scope.
- `FREEZE-MANIFEST.json`: byte hashes binding the reviewed package and Java-generated immutable premise.

Generated immutable JSON/Markdown, validation receipts and raw probes live in `.research-run/liquidation-structure-v001/`; they are gitignored run outputs. The authored files here are not trading reports or a production experiment. No candidate returns were inspected. No paid data, monitoring or live order was authorized or performed.

The core test comes before the owner's complete staged strategy: first test whether structure distinguishes recovery from continuation, then test staged risk and finally the independent S&P 500 context gate. RSI and other extra indicators are deferred; no composite score or genetic search is proposed.

Current conclusion: **premise recorded; full historical execution BLOCKED by data provenance/coverage and executor contract gaps.** A free shorter-history liquidation source is reachable, so paid data is not yet established as necessary.
