# Corrected worker implementation and qualification plan

> **Engineering verification complete — 2026-09-11.** See the
> [verification record](CORRECTED-WORKER-VERIFICATION-20260911.md) for the final
> source, package, test, coverage, PREFIX, and audit evidence. FULL remains
> blocked by the declared 28-CPU/32-GiB resource gate and is not qualified; no
> held-out confirmation, activation, or trading is authorized. The evidence
> archive is recorded in the verification record; CI is checked on PR13 head
> after push.

Reviewed base: `966b2758ff9e5e499c63d301e8f46bbbe0c423f7` (merged PR #10), branch
`codex/corrected-worker-qualification`. Planning and independent review use
Astra medium; implementation uses Luna xhigh. Unrelated untracked reports are
excluded. This is accounting integration and DEVELOPMENT engineering evidence;
no held-out confirmation, strategy activation, or trading is authorized.

1. Inspect the correction API, fixed evaluator's downstream consumers,
   successor generator, parallel coordinator, CLI adapters, schemas and retained
   qualification contract. Preserve historical economic implementations and
   artifacts. Use a separately named corrected evaluator and explicit corrected
   worker dispatch with no legacy fallback.
2. Correct event and control books before downstream summaries are published.
   Recompute every quantity dependent on equity, holdings, returns or drawdown;
   bind accounting version, algorithm, evaluator, source inputs and executable.
   Reject contracts outside long spot with a single full exit.
3. Add independent arithmetic regression vectors for timestamp normalization,
   same-instant EXIT/ENTRY/MARK order, boundary marks, overlapping and empty
   books, closure and invalid inputs. Add self-contained worker/coordinator
   integration and identity-tamper tests. Compare corrected serial and parallel
   economics with explicit provenance-only exclusions.
4. Build using JDK 21 and the pinned Maven wrapper. Run clean
   reactor, Python regressions, applicable mutation tests and changed-production
   coverage against the full reviewed merge-base SHA, with 55% line and 55%
   branch floors. Exact 55% passes; fix failures before final packaging.
5. Freeze fresh executable-bound development declarations. Inventory physical
   inputs; use supported generation/acquisition with retained provenance. No
   historical executor cache is presumed. The existing synthetic successor
   development generator is an authorized diagnostic input workflow, not a
   substitute for real-market physical evidence.
6. Qualification retains the existing exact full geometry inside each development
   slot: 450 episodes, 162 two-source physical clusters (288 paired statistical
   clusters), 900 lifecycle series and 14,400 minutes across the four cells. The
   statistical declaration of 75 repetitions per cell is the held-out
   confirmation inventory, not the development wave size. Development
   qualification requires a complete wave of 4 x effective_workers slots (one
   disjoint development seed per cell for each
   admitted worker) in both the serial and parallel runs, with all development
   seeds disjoint from the held-out inventory. Enforce existing runtime/RSS/disk
   limits and fresh machine checks. A resource profile or prefix comparison is
   not qualification.
7. Independently review code, accounting propagation, identities, portability
   and evidence. Fix findings, rerun affected checks, commit only reviewed files,
   push this branch, open a PR and inspect CI on its final commit. Do not merge.

Initial machine inventory: Intel i7-14700KF, 20 physical cores / 28 logical CPUs;
Windows reports 34,138,472,448 bytes physical memory. The validator requires
at least 34,359,738,368 bytes (32 GiB), so this host is 221,265,920 bytes short.
This is a target qualification blocker, not permission to round up RAM. Initial
C: free space was 799,166,558,208 bytes. JDK 21 is installed at
`C:/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot`; default PATH Java is 8
and must be overridden in each build/run shell. Independently possible code,
test, packaged development and PR work continues. Final evidence will report
observed outcomes and exact recovery commands without issuing a QUALIFIED
receipt on ineligible hardware.

The clean reactor uses the installed Ubuntu 24.04 WSL distribution and its
OpenJDK 21 in a separate ext4 checkout. Native Windows verification exposed
existing Unix hard-link custody and symbolic-link requirements; those safeguards
remain unchanged. WSL exposes only about 15.5 GiB total RAM and therefore also
fails target eligibility. The isolated checkout avoids unrelated reports and
Windows checkout line-ending differences in historical byte receipts. Every
packaged checkpoint gets a fresh declaration and evidence directory; checkpoint
evidence is never transferred to a different executable's qualification.
