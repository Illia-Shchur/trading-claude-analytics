# v002 implementation clarifications — 2026-09-20

This addendum clarifies the already frozen event-endpoint semantics; it changes no threshold, window, asset, risk rule or data claim.

**Open interest:** the five-minute observation lag and ten-minute maximum staleness apply separately at the start and end of the historical 4h/72h event geometry. Both snapshots must have been available at their respective endpoint and before the later daily-based decision. Do not apply the ten-minute test against the daily setup decision (which is much later), and do not replace the event endpoint with fresh post-event OI. This implements v001's instruction to use snapshots before each boundary.

**Sample feasibility:** the first outer fold has approximately 349 development/training calendar days after the minimum 67-day purge, before any further embargo. Eight calendar folds do not establish adequate independent episodes. The daily preflight counts only liquidation flags, not eligible trades or effective independent 60-day outcomes; insufficient sample remains a valid stopping condition.

An independent read-only contract review verified the first eligible date, final full-maturity cutoff, nearest-rank percentile, missing-data policy and preservation of the owner constraints. It identified the OI ambiguity above before any price/OI signal evaluation. No candidate outcome was inspected in this review.
