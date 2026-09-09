# Portfolio accounting correction plan

The 80% line/branch coverage threshold recorded in this historical plan and its
measurements is superseded by the current 55%/55% policy in `AGENTS.md`. The
historical threshold and evidence below are retained unchanged.

Reviewed baseline: `fd7935b9ea0320c171353416d94c1e3a6ab56078`.

## Finding

`StrategyFixedBaselineV5.reconcile` sorts timestamp strings before event priority,
accepts exit-boundary marks, and lets MARK insert absent holdings. Its terminal
check uses entry-cost holdings rather than the marked holdings used by the curve.
The known ETH case therefore adds a closed holding of 940.5685429534325 USDT to
cash of 9937.090065771703 USDT. Historical V5 must remain byte-for-byte intact.

## Implementation

1. Add a separately named, versioned portfolio correction class in the existing
   research v5 package. Prefer a narrow correction API over copying the entire
   frozen evaluator. It consumes immutable closed-trade evidence and produces a
   new accounting receipt; it must never overwrite or certify a historical result.
2. Parse timestamps to `Instant`; sort by instant, EXIT/ENTRY/MARK priority, and
   stable trade ID. Reject duplicate IDs, invalid lifecycles, invalid values and
   marks outside `[entry, exit)`. A separate mark-generation/legacy-adaptation
   boundary may exclude exact-exit marks explicitly, recording that correction;
   later marks must fail. Never silently drop arbitrary invalid evidence.
3. Track active positions explicitly. MARK updates only active holdings, EXIT
   removes them before valuation, and same-instant EXIT frees cash before ENTRY.
   Preserve existing costs, capital policy, mark sampling, trade P&L and selection.
4. Include marked value and active-position evidence in curve points. Check every
   point and terminal cash/marked equity/declared equity/start plus net P&L.
   A nonempty closed book must finish with EXIT and zero active/marked holdings.
   Define empty-book behavior explicitly.
5. Bind an explicit accounting version, correction evaluator identity, original
   input canonical hash, build/source identity and corrected canonical hash.
   Provide a documented callable correction entry point and reproducible fixture.
   Keep existing qualification fail-closed and historical resource measurements
   separate. Do not route old receipts through a new identity; if qualification
   integration requires broader scientific requalification, report that blocker.
6. Add regression tests covering both textual timestamp orderings, exit-boundary
   filtering, after-exit rejection, full-capital handoffs, normal marks, empty and
   fully closed books, multiple mixed-format trades, known ETH values, malformed
   inputs, input immutability, and deterministic hashes.

## Review and validation

Luna xhigh implements the correction and focused tests. The parent independently
reviews the changes and replays the corrected fixture to check active holdings,
cash, terminal equity and no phantom positions. Compare SHA-256 of all 442
preexisting strategy-research files, research oracle files and frozen V5 source
against a pre-edit manifest. Run focused and affected research/parallel/
qualification tests, `./mvnw -q package`, relevant Python tests, the required clean
reactor install and changed-Java 80% line/branch coverage gate against the reviewed
baseline, and `git diff --check`. Fix review findings before committing on main.
No held-out seeds, strategy changes, old artifact edits or qualification bypasses.
