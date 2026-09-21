# Implementation review — liquidation daily stress v002

Review base: `01d8f02d94f44d00024c3effe2b65b41f990d17b`. Worktree: `codex/liquidation-daily-stress-v002`. This is an engineering review, not a strategy-performance result.

**State: engineering review accepted; historical evaluation remains blocked by data and sample qualifications.** Implementation is delegated to Luna with xhigh reasoning. The parent performs independent source review and supplies arithmetic oracles. One implementation owner runs Maven to avoid concurrent build interference.

## Findings addressed in the current accounting checkpoint

- Position and portfolio risk use directional, nonnegative stop losses, paid entry costs, funding debits and closing-cost estimates. Funding credits do not create additional lifetime risk allowance.
- Shared cash, isolated collateral, held closing reserves, realized/unrealized PnL and liabilities reconcile. A full funding debit already reduces economic equity; outstanding debt must not reduce that equity a second time.
- Closing releases the reserve actually held, rather than recomputing a different reserve after a stop ratchet. Closed episodes release open-risk commitment and remain separately recorded.
- Monetary PnL and stop risk use quantity/notional arithmetic directly. Dividing to average entry and multiplying back introduced a small decimal residue that discarded a whole lot during a later portfolio admission.
- Metadata can change by effective time, new episodes can change direction, and new fills do not reset an existing episode's holding clock.
- Minute-open decisions use opening observations; the later minute path must not enter opening admission calculations. Availability governs trailing-stop activation. Day-60 expiry is checked at the executable opening boundary.

Independent zero-cost portfolio oracle: three initial 20-unit positions at 100 with ten-unit stop distances; stage-two marks of 105/95/105 and 20-unit additions; stage-three marks of 110/90/110. Marked equity is $20,900 and the portfolio risk ceiling is $2,090. BTC's third tranche fills 25 units, ETH fills 4.5 units, and SOL's third tranche is rejected. Final quantities are 65 / 44.5 / 40. This checks current marked equity, partial sizing, direction symmetry and the frozen asset tie order together.

Targeted checkpoints exercise physical source normalization and qualification, causal routing, accounting/lifecycle, chronological and evidence helpers, public replay with paired controls, five stress reruns, tamper rejection and CLI dispatch. Later integration changes require their own verification. No earlier green checkpoint replaces the final full clean reactor and explicit 80%/80% coverage gate.

## Integration review requirements

- Preserve the same routed decision inventory across forced controls. Different control exit times may create occupied/no-trade outcomes, but must not silently change the comparison's decision dates or discard those rows.
- Bind source bytes, normalized derivation, separate physical roles, profile, executor and candidate lineage at freeze and reopen them at evaluation. Hash links alone do not establish correct normalization or historical availability.
- Account for the full frozen envelope, including declared missing features. The seven known AAVE daily gaps suppress affected setups and lookbacks; they must neither be filled nor disable every other asset. Missing execution, marks or funding during exposure must remain explicit failures/unresolved economics.
- Use actual S&P session-close timestamps, the extra-session lag, dated calendar exceptions and exact neutral return boundaries. OI observation staleness and publication lag are distinct clocks.
- Keep 4h trailing-stop inputs throughout the final positions' lifecycles. Declare the initial-opportunity cutoff separately from the active-position addition clock.
- Test the full physical-input → causal-router → executable-fill → shared-account path, as well as deterministic restart, chronological fold application, statistics and evidence restrictions. Standalone helper tests do not establish this integration.
- Verify bounded memory/partition reads and the research compute estimate before a full historical run. Synthetic examples cannot establish actual historical sample size, performance or promotion eligibility.

## Evidence and replay review findings

The first evidence implementation counted 72-hour decision clusters as independent episodes. That is unsuitable for positions held up to 60 days. Keep those clusters descriptive; dependence-aware sample-size reporting and synchronized blocks must retain overlapping positions, empty market intervals and the final partial block. A conservative count of occupied 67-day blocks is a proxy, not a proof of statistical independence. Its feasibility against the frozen 30-episode minimum must be disclosed before interpreting returns. Under the implemented conservative policy requiring at least 67 days between connected episode groups, the frozen decision window permits at most 21 groups. That policy therefore cannot satisfy the unchanged 30-episode floor; this is a dependence-policy/sample-window limitation, not an observed failure of the economic premise.

The routed strategy must improve against each required control. Center bootstrap contrasts under the null and compare each observed contrast with the common maximum-null distribution. Equal candidate/control returns must produce zero incremental expectancy and fail advancement. The current candidate inventory cannot substitute for cumulative family exposure; missing ancestor evidence blocks familywide claims. Pooled development metrics must be distinguished from metrics using actual outer-fold membership.

The initial replay review identified integration defects before compilation: malformed generated Java, timestamp-string mismatch at the physical reader boundary, absent prior feature warmup, a future-metadata fallback, stop updates broadcast to comparison accounts, and an absent execution-price chase check. These fixes were subsequently exercised by the full physical-input fixtures in the clean research-module run. A pending order confirmed at the last permitted hour must also survive intervening observations until its execution acknowledgement.

Daily equity samples do not establish intratrade drawdown. Evidence must retain and bind the accounting engine's adverse mark-path accumulator. Open or coverage-blocked outcomes remain visible and block advancement; supplied stress summaries or self-consistent hashes cannot replace runner-produced evidence.

## Outage and stress integration review

The stress review found that a blackout tied only to a new setup's own availability could never affect its much later confirmed entry. The revised design retains every market-qualified daily event independently of position state, creates half-open event-availability windows, and continues mark and funding economics while execution is unavailable. A previously triggered exit must remain latched even if price recovers; liquidation has priority, and execution resumes at the first permitted minute opening.

Regression review additionally covered: funding-triggered liquidation must obey the same execution blackout; the completed-minute path uses its interval start for blackout membership; latching audit labels must match the actual emitted statuses; a still-breached position at reopening must retain its deferred-trigger disclosure; and a blocked routed entry must not remove the paired control rows. Stress acceptance applies to the routed candidate, with control results retained as diagnostics. Full scenario artifacts must be reopened, and funding-debit stress must double actual paid funding while leaving credits unchanged.

Integration testing also found a source-evidence timestamp serialization defect and test-contract mismatches between full artifact-byte hashes and embedded own-content hashes. Funding checks use retained settlement identity, exposed quantity, signed funding totals and cash reconciliation; unbounded minute-event retention is not an acceptable test fix. Retained zero-position funding observations are distinguished from actual cash settlements while exposed.

The staged-plan review also identified silent loss of anchors when a reader required a `stage` field that the core runner did not emit, and rejection of filled `COVERAGE_BLOCKED` positions. Later comparisons require the exact verified initial intent, explicit position identity, numeric 5% of first-fill reference equity, preserved blocked exposures, and separate sequential core → staged → macro predecessor records. A self-consistent caller-authored hash cannot establish predecessor survival.

## Staged integration review

Funding attribution must follow the executable event order, including collisions: a tranche filled at the same timestamp as an earlier funding settlement did not participate in that settlement. Independent zero-cost oracle: 20 units at 100, funding at mark 105 and rate 0.001, then a same-timestamp 20-unit addition at 105, followed by a common exit at 110. Stage one nets 197.90, stage two nets 100, and the position nets 297.90. A timestamp-only inclusive attribution would overallocate funding.

The staged adverse-gap stress must debit 0.25 times the full 5% first-fill reference-equity budget, even when only one or two tranches filled. Summing only filled tranche allowances understates this stress. Core one-entry stress retains its 1% reference basis.

Sequential advancement must use the exact versioned acceptance-gate inventory and actual rerun predecessor evidence. A nonempty arbitrary map of true booleans is not an acceptance contract. The staged evaluator must compute the frozen statistical comparisons, branch/fold floors and dependence-aware metrics; permanent placeholder failures do not complete the requested implementation. Synthetic fixtures exercise mechanics only and never establish historical survival.

Public restart tests now compare complete canonical output bytes against an uninterrupted replay, with a live position at an intermediate checkpoint. They cover missing and older checkpoint mirrors, a forged future mirror, separate operational/checkpoint targets, funding and outage continuity. Canonical OS path aliases are normalized before bindings are compared. Closed or pending positions must not be terminalized merely because a requested intermediate boundary was reached.

## Staged statistical review

The paired bootstrap must use the same observation identities as its market-time blocks and merge boundaries crossed by either arm's holding interval. An unresolved predecessor is not a zero-return control. Each resolved no-trade remains a paired zero.

The primary incremental contrast uses each frozen headline denominator: core PnL / initial 1% reference risk versus staged PnL / full 5% reference risk; macro comparisons use 5% in both arms. Independent oracle: core earns $200 on $200 reference risk and staged earns $1,000 on $1,000 reference risk; both earn 1R and incremental expectancy is zero. Using 5% for both denominators would confuse increased exposure with improved timing. Common-denominator dollar diagnostics may be disclosed separately.

Completed-position cumulative-R drawdown is distinct from marked-equity portfolio drawdown. For ordered R values [1, -2, 3, -4], the cumulative path is [1, -1, 2, -2] and maximum drawdown is 4R; appending zero-return opportunities cannot shrink it. Average position cost-R uses each position's own denominator and paid funding debits, without netting away those costs with funding credits.

Canonical persistence is a statistical input boundary. JCS represents numbers using IEEE754 serialization, whereas an in-memory account can retain higher-precision decimals. Subtracting an in-memory ending equity before serialization can therefore produce different evidence from reopening the identical canonical replay. Evidence arithmetic now consumes the same canonical representation in both paths and retains exact canonical evidence equality. A regression uses ending equity `20312.123456789123456789` to expose this distinction. The 5%-of-equity artifact check uses exact binary64 rounding intervals: the scaled equity interval must overlap the risk interval. A valid independently rounded pair can differ by two representable steps, so an arbitrary one-step bound is insufficient. Tests exercise a valid pair and the nearest non-overlapping rejection. Internal accounting and risk sizing remain unchanged.

Known new exposure attempts do not establish historical family K. Current custody must contain the exact bound attempt prefix, and later appends must not invalidate an earlier canonical predecessor. Stress gates must read the actual frozen stress policy and require genuine execution effects from an outage, not passive exposure alone.

PBO must not be silently implied by the plan's reference to selection diagnostics. This family uses fixed predeclared contrasts and sequential additions, without a parameter grid or winner-selection procedure. Its evidence must therefore label PBO explicitly not applicable to that geometry, without a numeric estimate or passing gate. This does not waive centered-null comparisons, known exposure accounting or the limitation from unknown historical family K.

## Final engineering acceptance

The full clean research module passed, including public replay/restart integration and the complete 60-day staged fixture. A stale exact CLI usage expectation was corrected additively; its 13 tests and the downstream reactor continuation passed. All later changes were test-only or CI/documentation changes. The final source/resource snapshot contains 422 files and is unchanged from the reviewed production build.

Independent final audit confirms current green XML for 108 changed test classes and 454 test methods. The aggregate passes the explicit 80%/80% gate against `01d8f02`: 9,580/10,187 executable lines (94.04%) and 6,925/8,651 branch outcomes (80.05%). The remote-base comparison against `4385b39`, including earlier acquisition work, also passes at 93.87% lines and 80.10% branches. No thresholds or production code were changed to satisfy coverage.

The completed staged fixture preserves no-macro fills [1,2,3], macro fills [1], identical SHORT anchors, a single aggregate exit, funding attribution and the first-fill-based 60-day deadline. Independently rounded artifact components can differ by one binary64 ULP; the test-only bound is restricted to those demonstrated serialization comparisons. Exact engine reconciliation and canonical evidence equality remain required. Separate short-account controls verify cash-neutral opposite funding events with retained gross debit risk, and 2x→3x collateral release without PnL creation.

The actual retained-acquisition audit and profile validation also passed with their expected non-authoritative statuses. This acceptance covers engineering; it does not establish historical input vintages, sufficient independent episodes, profitability or promotion eligibility. No historical candidate returns were inspected.

## Latest public-boundary verification

The current-bytecode public replay suite passed all seven tests, including exact uninterrupted/resumed canonical output equality, missing/older/future checkpoint mirrors, funding and outage continuity. The shared budget integration passed twelve full failed two-hour reservations followed by `COMPUTE_INCOMPLETE` on a valid thirteenth request, with no replay or prefix checkpoint published.

Independent adapter review found that physical-build dispatch tested only for the `feature` key, which is mandatory in both flat synthetic and partitioned input formats. That made the flat synthetic builder unreachable through the CLI. The correction passed positive public-dispatch tests for both formats and a malformed-shape rejection (3/3).

The replay evaluator previously waited until deterministic reexecution completed before rejecting a malformed supplied artifact. Structural prevalidation is verified inside the existing initialization/reconstruction lease, before predecessor/engine replay. It reuses the envelope, identity and opportunity validators; statistics remain in evidence construction, and exact canonical rerun comparison remains mandatory. Failed validation still consumes its reserved budget and cannot publish economic output. Moving physical revalidation outside that lease was rejected during review.

The charged early-artifact regression passed all eleven independent rejection/ABORT checks. The subsequent fresh clean reactor restored the current-class integration paths. The earlier 72.50% checkpoint is superseded by the current measurement above.

The no-macro plan creator is a preliminary `PREDECESSOR_ANCHORED_DEVELOPMENT_PLAN`, not a survival attestation. Its structural all-true gate-map check does not prove complete evidence. The public staged runner reexecutes the predecessor, validates the plan against the recomputed evidence and uses that evidence for survival/statistics before exposing the new candidate. Test descriptions must preserve this distinction.

## Strict stress-counter validation

Independent source review confirmed that staged stress acceptance used `asInt` on observation, unresolved and outage-action counters without first requiring bounded nonnegative integers. Fractional counts could therefore truncate into apparently valid gate values; oversized integral transform counts could also overflow. The core summary validator already enforces integral, `canConvertToInt`, nonnegative counts. The staged validator must enforce the same type contract before applying the unchanged observation and outage-effect thresholds. Public deterministic reexecution still prevents caller-authored summaries from replacing runner results, but that does not excuse accepting malformed statistics inputs. The in-progress clean reactor was stopped before the long staged fixture so the correction can be tested in the final source tree.

The strict-counter correction passed the focused 51-test staged statistics/evidence/plan selector. Regression inputs include `0.5` and string `"0"` for unresolved counters and `4294967296` for integer-wraparound cases, so they exercise the actual earlier coercion defect. The corrected helper also permits deliberately absent stress-evaluation objects to reach the intended rejection boundary. Production sources are frozen for the fresh clean reactor.

The fresh clean reactor passed the full research module and the 60-day staged integration, then stopped at one CLI test whose exact expected help string omitted the 17 new commands. The test was updated additively, preserving the full legacy command list and stream assertions; its focused suite passed 13/13. Production code did not change. The remaining reactor modules subsequently passed from `analytics-cli`, with the four test-only additions run separately before the aggregate.

The reactor continuation and four queued standalone classes passed (8/8 new tests). An upstream-inclusive `verify` regenerated coverage from all module execution files without cleaning or changing production. That intermediate 93.35% line / 76.66% branch measurement included the successful 60-day integration and correctly failed the branch gate. Subsequent tests exercised previously untested contracts through supported entry points; final passing measurements are recorded above.

The next focused batch passed staged-configuration (2), physical/chronological/macro receipt (8), accounting acceptance (4), and core evidence/stress/freeze binding (10) tests. Independent review corrected a vacuous adapter assertion: the adapter does not export `reconciliation_delta_usdt`, so Jackson's default zero was not evidence. Its test now requires numeric marked equity and free collateral of $20,098, derived independently from 20 units × $5 gross profit minus $2 funding, with a closed zero-quantity position. The daily-coverage test also now accepts deliberate clipping at the requested exclusive end while retaining internal-gap and ordering rejection checks. Neither correction changed production.

The latest public verification matrices exercise core and staged result bindings, exact setup reservation/ABORT charges, operational output targets, research-root custody, checkpoint filesystem integrity, routed intent projections and paired unresolved exposure. Root review confirmed all 100 changed test classes have current passing Surefire XML. The long-pivot matrix independently rejects each equal/lower neighbor and proves that the second right-hand H4 close cannot retroactively authorize an earlier H1 bar. The staged envelope control establishes structural acceptance only and deliberately reaches the next predecessor-verification guard; the complete staged engine execution remains established by the separate 60-day integration fixture.

The CI workflow now explicitly enforces 80% lines and 80% branches, matching this plan and AGENTS.md. Its prior 55% flags were inconsistent with that policy. The java-parity timeout increases from 45 to 90 minutes: the fresh clean build already took 41 minutes 26 seconds before the downstream continuation and later additive tests. This changes the verification window only, not the separate 24-hour research compute budget. The workflow diff preserves the remaining jobs.
