# Parallel/2 outer-worker comparison — 2026-09-15

This bounded follow-up measures whether four outer workers are preferable to the
current six-worker local profile after the Java 21 collector comparison selected
Parallel GC with `ParallelGCThreads=2`. It is engineering evidence for the
optimized eight-development-seed package on the available Apple M1 Pro / 16 GiB
Mac. It does not retest collectors, change heap or strategy inputs, run reserved
300 repetitions, perform qualification, or claim Windows behavior.

## Frozen inputs and controls

Use the same immutable optimized JAR, package manifest, plan, profile and eight
development seeds as the completed 2026-09-15 collector campaign. Pin Java
21.0.11 at `/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin/java`.
Every child JVM uses `-Xmx1536m`, `-XX:ActiveProcessorCount=1`,
`-XX:+UseParallelGC`, and `-XX:ParallelGCThreads=2`; only outer worker count
varies between 4 and 6. Freeze a separate self-hashed profile and stage
controller receipt for each setting, and verify the actual child collector,
thread count, flags, package, runtime profile and stage identity before ranking.

Run exactly four complete batches in order:

1. `workers4-first` — four outer workers.
2. `workers6-first` — six outer workers.
3. `workers6-second` — six outer workers, reversing relative setting order.
4. `workers4-second` — four outer workers.

Before timing, require AC power, an active `caffeinate -is` guard, no competing
Java/build/archive workload, sufficient disk and a valid clock. Keep raw worker
bytes immutable. Each complete PASS must contain all eight strict outputs, exact
normalized economics, all three curves, raw-byte conservation, and the frozen
independent 7200-trade / 16-book replay. Any correctness or runtime failure is
preserved as FAILED and is never censored into a success. Censoring is a timing
lower bound only and is never ranked.

## Timing and decision rule

The first censor is `1.25 × 1306.413728125 = 1633.01716015625` seconds, based on
the previous validated Parallel/2 mean. Later stages use `1.25 ×` the best
validated complete batch from this campaign. If the first four-worker stage is
censored, stop and inspect comparability before any arbitrary bound change.
Terminate only this campaign's process tree on interruption or censoring.

Rank the two complete observations per setting by eight-worker makespan, excluding
validation time. Report both observations, ranges and means. Use a practical
one-percent engineering tie band with the explicit rule `mean4 <= mean6 * 1.01`:
select four workers when that inequality holds, per the latest user preference.
Select six only when `mean4 > mean6 * 1.01` and both observations pass all
contracts. This margin describes the local configuration choice; it is not a
statistical significance test. If either
setting lacks two valid observations, retain the prior six-worker profile and
report the comparison incomplete.

## Completion

Preserve immutable per-stage receipts, a raw archive with a byte/SHA manifest, a
compact archive and a concise report. If four workers win or fall within the
practical tie band, publish a self-hashed selected local profile plus runbook and
AGENTS update. If six workers are clearly faster, keep the prior selected profile
and document the measured result. Obtain independent review of this plan and any
new runner/controller tests before timing, then independent final review of the
receipts, selection math and profile. Commit and push only scoped evidence,
docs and profile changes to PR13; verify CI on the exact pushed head. Do not
merge.
