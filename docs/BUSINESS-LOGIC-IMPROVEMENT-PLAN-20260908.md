# Business logic improvement plan — 2026-09-08

This historical plan records the former 80% coverage policy and its measured
results. The current active policy is 55% line and 55% branch coverage; see
`AGENTS.md`. Historical thresholds and evidence below remain unchanged.

## Objective and ownership

Implement the six priorities in BUSINESS-LOGIC-COVERAGE-AUDIT-20260908.md. Implementation is delegated to gpt-5.6-luna with xhigh reasoning. The primary agent plans, integrates, independently reviews the completed implementation, and verifies the final result. Preserve all existing working-tree changes; do not commit, push, deploy, tune strategy thresholds, or run market research as part of this task.

## Acceptance criteria

1. Strict signal-export failure preserves any existing feed byte for byte and never creates a new output feed. Cover missing machine blocks, malformed input, and mismatched canonical pairs without dry-run.
2. Unknown-age observations do not count as synchronized quotes. Historical/fallback prices remain explicitly available as context but cannot masquerade as verified canonical spot. Test missing timestamps, all-stale/all-bar-close inputs, mixed quotes, future timestamps, and insufficient sources. Review consumers for compatibility consequences.
3. Calculation and report validation agree on trigger age: supplied ages must be finite, integral, nonnegative, and within the completed-bar window. Preserve an explicit, documented missing-age policy. Test helper and command/report boundaries.
4. Add portable physical-data fixtures covering fixed-baseline evaluation and lifecycle/cost outcomes. Replace the machine-specific skipped physical null-selection fixture with reproducible test data. Assert independently derived economic results and rejection of invalid evidence, rather than merely reproducing output hashes.
5. Exercise a valid local physical evidence chain through prospective snapshot verification, deployment audit, and readiness. Derive single-fault rejection cases for identity/hash/scope/timing/replay/revocation/approval/permissions/ledger dependencies where applicable. Assert decision and side effects. No external deployment is required.
6. Enforce an 80% new-code coverage policy in CI using aggregated reactor JaCoCo data. New code means added or modified executable production Java lines in the reviewed diff; branch coverage means JaCoCo branches on those changed lines. Require at least 80% changed-line coverage and 80% changed-branch coverage when the denominator is nonzero. A zero denominator is explicitly not applicable. Include new files and renamed files; do not silently pass on missing coverage, missing base refs, malformed reports, or source-path mismatches. Unchanged legacy coverage does not determine this gate. Publish the aggregate HTML/XML report and gate summary. Include deterministic tests of threshold boundaries and failure behavior.
7. Add focused mutation coverage for business-rule owners, starting with phase/trigger risk and publication semantics. Keep existing mutation jobs. Calibrate bounded jobs against actual results without weakening business rules or ignoring survivors to obtain a green build.

## Work sequence

- Record the current source snapshot as the task baseline for local diff coverage, so pre-existing work is not attributed to this task. CI compares PR changes with the merge base; pushes use the event's previous revision with explicit handling of missing/initial bases.
- Parallel implementation streams: (A) aggregate/diff coverage and CI gates; (B) publication, quote freshness, trigger consistency and regressions; (C) portable fixed-baseline/physical-evaluation tests.
- As a slot becomes available, implement the deployment/readiness evidence-chain tests and focused business mutation jobs. Separate file ownership to avoid concurrent edits.
- Primary-agent review checks logic, test independence, compatibility, filesystem side effects, coverage accounting, and CI base selection. Return concrete findings to Luna for correction.
- Run affected tests during implementation, then the full reactor verify, the new-code gate against the task baseline, the coverage gate's own tests, and relevant bounded mutation jobs. Investigate failures; do not broaden unrelated changes.

## Completion evidence

Record final test counts, new-line/new-branch coverage, mutation results, corrected findings, and any remaining limitations in this plan. Coverage percentages refer to the compiled source snapshot actually tested. If other work changes files concurrently, identify that scope rather than claiming it is verified.

## Primary review outcomes

- Strict export validation now happens before publication. Regressions cover existing-feed preservation, no new feed on failure, malformed/missing machine blocks, mismatched canonical pairs, and the exact machine-block epoch boundary.
- Missing or unrecognized quote timestamps are excluded from synchronized observations. An insufficient/historical fallback is explicitly contextual and cannot populate canonical spot or downstream scoring panels.
- Trigger-age validation is shared by the calculation and report paths. Supplied values must be numeric, finite, integral, nonnegative, and in-window; the existing missing/null-age policy is explicit.
- Physical fixtures exercise real retained roles and portable DuckDB/Parquet inputs. Assertions independently derive lifecycle PnL, fees, slippage, paired returns, and the selected physical null vector. Malformed checkpoints and valid-JSON byte tampering are separate rejection cases.
- Valid signed snapshot, deployment-audit, and readiness chains now have positive and single-fault negative coverage. The tests exposed two production incompatibilities: a verifier required an API receipt field forbidden by its own schema, and the evidence scanner rejected the signer's normal public key. The fixes preserve repository identity bindings and permit only a validated Ed25519 public key in the exact attestation schema/file/field. Private, malformed, misplaced, and encoded forbidden key material remains rejected.
- Fixed-baseline policy paths now use the existing repository locator. A package-private fixture seam supplies a test executor identity; the production entry still requires packaged-JAR identity and has an explicit rejection regression.
- Coverage review corrected grouped JaCoCo source mapping and rename/pre-existing-untracked-file accounting. The gate compares exact percentages and fails closed on invalid inputs. Mutation jobs cover entire business-rule classes, without method exclusions.

## Business mutation gates

| Business owner | Minimum mutation / strength / line coverage | Verified result |
| --- | --- | --- |
| `SwingPhaseRisk` | 95% / 95% / 95% | 141/141 mutations killed, none survived/uncovered; 100% mutation, 100% strength, 164/166 lines (99%) |
| `ExportSignalsCommand` | 80% / 85% / 90% | 273/329 mutations killed, 29 survived, 27 uncovered; 83% mutation, 90% strength, 353/375 lines (94%) |

The publication result is the final full-class rerun after the exact-epoch regression was added (`/tmp/business-publication-final-pit.log`). Both jobs passed their configured thresholds. Remaining publication mutations include projection/metadata compatibility cases and equivalent or unreachable branches; they are visible in the report and were not excluded to make the gate pass.

## Approved fetch-contract changes

Compatibility tests continue to compare complete wire outputs. Their explicitly revised expectations cover the intended business-rule changes: the BTC fixture excludes Kraken's undated 103 quote, leaving fresh values 100/101/102 and a canonical median of 101 instead of 101.5; dependent drawdown, moving-average-distance, bounce and proximity values are updated from that price. Gold's historical 339 price remains context only, with canonical spot and spot-dependent metrics unavailable. All 55 compatibility tests pass in the isolated review workspace.

## Final verification — complete

- Clean full reactor: `./mvnw --batch-mode --no-transfer-progress clean install` — **BUILD SUCCESS**, 1,078 tests, zero failures/errors, one existing packaged-executor prerequisite skip. The newly added physical/evidence-chain tests have no skips. Log: `/tmp/business-logic-verified-final.log`.
- Coverage-gate tests: `python3 -m unittest tools.test_new_code_coverage` — **14 passed**.
- Task changed-code gate: **99/102 executable lines (97.06%)**, **108/116 branches (93.10%)** — both pass the **80%** minimum. Includes the fixed-baseline policy/identity seam, whose changed lines are measured separately from unrelated edits in that same file.
- Both configured business mutation jobs passed; exact results and remaining mutation scope are recorded above.
- Reviewed-file hashes were checked against the compiled snapshot after verification; all 41 reviewed code/configuration/test files still matched. Diff whitespace and changed JSON/XML configuration parsing checks passed.

Generated review artifacts are available locally at `analytics-coverage/target/site/jacoco-aggregate/index.html`, `analytics-coverage/target/business-rule-gate.json`, and `analytics-reporting/target/pit-reports/index.html`. These are build outputs, not committed reports.

### Scope and reproducibility

The workspace contained extensive pre-existing work and continued to change in other tasks. Verification used `/tmp/business-logic-review.JOshY6/workspace`, an isolated filesystem snapshot. The unrelated parallel-research executor was kept at its last compiling snapshot while the task's complete source/test/configuration changes were synced and checked by hash. No source in the user's workspace was reverted to achieve this result.

The initial task baseline is Git tree `e98ef71dfbf00c9e7c3c2fdc4db8c003c6ffadc7`. For local measurement, tree `dc4a050558601e13eb56668d44e14a241cd5c0bc` keeps unrelated production source at the tested snapshot and restores only this task's production edits to their pre-task content. This excludes pre-existing and concurrent work from this task's denominator. The real Git index was not altered. CI has no such scope exception: it gates the entire reviewed PR/push diff against its explicit event base. These local results do not certify the other tasks' unreviewed edits.

Reproduce the scoped gate from the tested snapshot:

```sh
python3 tools/check_new_code_coverage.py \
  --base dc4a050558601e13eb56668d44e14a241cd5c0bc \
  --report analytics-coverage/target/site/jacoco-aggregate/jacoco.xml
```

All seven task-scoped acceptance criteria were complete at the review snapshot. The integration verification below supersedes its uncommitted status.


## Main integration verification — 2026-09-08

Committed all pending changes as `fe3f519` and integrated fetched `origin/main` (`05f4650`). Regenerated the conflicting signal feed from the combined report corpus. Frozen migration-oracle tests now select their explicit required historical inputs, retaining full output/hash comparisons without changing expected results when new reports arrive.

- Actual combined working tree: `./mvnw --batch-mode --no-transfer-progress clean install` succeeded, **1,080 tests, zero failures/errors, one existing skip**. Log: `/tmp/main-merge-complete-verification.log`.
- Changes since pre-task commit `2f493c8`: **96/97 executable lines (98.97%)**, **106/112 branches (94.64%)**; both 80% gates pass.
- Entire combined diff against fetched remote `origin/main`: **50,792/64,773 executable lines (78.42%)**, **35,039/66,453 branches (52.73%)**; both 80% gates fail. This includes the earlier local Java migration absent from remote main. No gate was weakened or bypass added. The local merge does not establish that the eventual remote CI coverage check will pass.
- Coverage gate unit tests: 14 passed. No remote push or deployment performed.
