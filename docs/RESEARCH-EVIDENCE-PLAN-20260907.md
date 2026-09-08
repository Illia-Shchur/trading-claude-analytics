# Research evidence improvement plan — 2026-09-07

Planning and independent review: parent (Astra). Implementation: Luna xhigh.

## Scope and starting state

Resolve evidence limitations, without requiring profitability or promotion. Preserve historical artifacts, frozen executors, exposure counts, PIT boundaries, execution costs and all FK/FR safeguards. No trading, activation, deployment or push is authorized.

Initial inspection found the previous uncommitted delivery and HEAD 19 commits ahead of origin/main. After the user's interruption, the checkout is clean at `30262ac` (20 ahead, zero behind); that intervening commit is the preserved starting point for this delivery. Do not rewrite it.

The completed v004 synthetic experiment is development evidence: 2/50 no-edge positives, 0/50 zero-effect positives, and 14/50 and 45/50 positive-effect detections. Its confidence-bound targets were not established. The physical baseline has 126 admitted events, 28 event clusters and three matched clusters; historical exposure is unresolved.

## Ordered work and acceptance gates

1. **P0 — reconstruct historical lineage.** Inventory retained definitions, candidate rows, runs, failures, receipts, hashes and canonical exposure records. Emit a reproducible source-bound inventory that separates artifact duplicates, proven retries, distinct behavior/data exposure, and unresolvable attempts. Counts are lower bounds when custody is incomplete. Never infer missing history or silently convert legacy hashes into an exact modern K. Add conservative reconciliation tooling and negative tests; preserve the authoritative HEAD unless a fully justified append-only reconciliation is possible.

2. **P0 — explain control attrition.** Instrument the authoritative setup/matching path without changing frozen selections. Account for pool construction, eligibility, prior-time and lifecycle restrictions, calipers, reuse, overlap, admission and final pair clustering. Reconcile the historical three matches from physical source data. Record both marginal and sequential attrition where useful, and distinguish selection-stage units from actual lifecycle clusters. Assess history/asset expansion and a successor control specification using outcome-blind development diagnostics; changed matching rules are a new specification, never a repair to historical outcomes.

3. **P1 — diagnose the evaluation design and freeze its successor.** Independently reconcile all 200 prior decisions and Wilson bounds against raw results. Separate Monte Carlo uncertainty from the experiment's detection power, including the joint unconditional/paired rule, event/cluster geometry, dependence, costs and total-log-drift effect units. Record each new diagnostic as development exposure. Before fresh confirmation, freeze hypotheses, economically meaningful effects, nulls, assumptions, sample/replication sizes, confidence/multiplicity rules, disjoint seeds/scenarios, stopping/incomplete-run policy and wall/RSS/disk budget. Preserve acceptance standards. Reuse production Java evaluation; analytical planning calculations must not masquerade as execution simulation.

4. **P1 — execute only a feasible frozen evaluation.** Implement additive, resumable execution with durable all-attempt accounting if needed. Bind the plan to an immutable packaged executor and inputs before outcomes. Use a bounded preflight to justify full-run feasibility; retain all partial/failing attempts. Run confirmation when supported within the declared budget. Otherwise retain a precise resource/input blocker and an executable next step; do not substitute a toy simulator or call a prefix confirmation.

5. **P0 completion gate — independent review and regression.** Parent reviews the incremental diff against `30262ac`, independently checks lineage and attrition accounting, recomputes key statistics and compact/raw reconciliation, and verifies the executable's SHA and compiled source identity. Run targeted Java/Python tests followed by the appropriate full reactor and Python suite. Send defects to Luna for correction and rerun affected checks. Record exact counts, skips, limitations and final evidence requirements in a dated review.

## Execution organization

One Luna implementation agent owns lineage and matching first. A second Luna agent may independently inspect prior synthetic evidence and implement the successor evaluation in separate files; it must consume the earlier investigation before freezing decisions affected by physical coverage. Parent performs source/evidence review alongside implementation and owns final acceptance. Shared command adapters and full builds are coordinated to avoid conflicting edits or builds.

## Deliverables

- This prioritized plan and a dated independent review.
- Provenance-backed lineage inventory, including unresolved exposure.
- Matching attrition artifact, explanation and justified successor design.
- Immutable next-evaluation precommitment and feasibility/result receipts.
- Additive Java tooling and meaningful regressions, with packaged identity checks.
- Current guide/matrix additions stating exactly what improved and what remains blocked.

Status: investigation and implementation completed by Luna xhigh; parent review and final regression verification are recorded in `docs/RESEARCH-EVIDENCE-REVIEW-20260907.md`. Historical custody remains a lower bound, physical coverage remains 28 event / 3 matched clusters, and full successor confirmation is resource-blocked. These are retained evidence limitations, not a promotion or profitability claim.
