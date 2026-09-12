# Corrected worker verification record — 2026-09-11

> **Follow-up:** the later eight-seed Mac FULL development run is documented
> in [Mac FULL development execution — 2026-09-12](MAC-FULL-DEVELOPMENT-20260912.md)
> and its compact records are in
> [`mac-full-development-20260912`](../strategy-research/v5-records/evidence/mac-full-development-20260912/).
> That follow-up supersedes this note only for the later development-run
> measurements. It does not change the qualification state: formal FULL remains
> `BLOCKED_RESOURCE`, with no heldout confirmation, activation, or trading
> authorization. The hashes, counts, CI coverage, and results below remain the
> historical snapshot for the 2026-09-11 verification.

> **Engineering verification complete.** The final source, package, clean
> reactor, coverage, PREFIX runs, and independent audit passed. The declared
> FULL run is blocked by the resource gate and is not qualified. No held-out
> confirmation, activation, or trading is authorized.

Reviewed base: `966b2758ff9e5e499c63d301e8f46bbbe0c423f7`  
Final source commit: `397e57e8842d1d57c297ad2b31f506b2e6676cef`  
Final source fingerprint: `c99a335303fa4649c83e445d7480cc2cfcbe879f51c22fc1558627680e55496a`  
Final JAR SHA-256: `3ff5aa4612053d2483a83b93f12055bddcd45d25edc75c8ca61d1635b89c87cc` (107,283,641 bytes; `dirty_source=false`)  
Final execution profile SHA-256: `e7c41026cdd9e07afd67e3034dbe5490b908207a11beb64db80308971c11f763`  
Final plan SHA-256: `e0b2b93c2bfbde064e57408cc5b0f99a8ebfd6584794611b8567a8f5573624b0`

## Scope reviewed

The independent Astra review reported no remaining findings. The corrected
worker now binds slot inputs, evaluator, and algorithm; reopens and validates
terminal successor ledger entries; distinguishes 162 two-source physical
clusters from 288 paired statistical clusters; requires evaluator identity and
legacy result hash in the producer receipt schema; and recursively rejects
unsupported nested trade contracts. The bounded macOS `IOPlatformUUID` probe
fails closed without hostname fallback, and disk accounting tolerates only a
concurrently vanished `NoSuchFile` entry.

## Final checks

| Check | Result |
| --- | --- |
| Python regression and discovery checks | **27 passed** |
| Clean reactor | **BUILD SUCCESS** — 1,534 tests, 0 failures, 0 errors, 1 skipped |
| Corrected worker tests | **30/30 passed** |
| Focused JDK21 evaluator, resources, and parallel helper tests | **47/47 passed** |
| Aggregate new-code coverage | **55/55 PASS** — 86.77% line (1,765/2,034), 60.94% branch (1,058/1,736) |
| Corrected evaluator PIT | **196/212 mutations (92%)**; **384/397 lines (97%)**; test strength **96%** |
| Process resources PIT | **31/34 mutations (91%)**; **39/40 lines (98%)**; test strength **91%** |
| Final serial PREFIX | **8/8 complete** — 175,158 ms; aggregate RSS 1,968,111,616 bytes, coordinator RSS 577,732,608 bytes, disk 140,865,329 bytes |
| Final parallel PREFIX | **8/8 complete** — 89,546 ms; aggregate RSS 3,099,213,824 bytes, coordinator RSS 485,883,904 bytes, disk 219,472,102 bytes |
| Independent PREFIX audit | **PASS** — 32 books, 63,152 curve points; all eight portable digests, metrics, and curves matched exactly |

PREFIX measurements carry `performance_claim=NONE`; these bounded runs are
execution and resource checks, not throughput qualifications.

## Actual producer geometry

The retained producer harness accepted the archived actual producer row in
`ENGINEERING_ONLY` mode. It reports `qualification_claim: NONE` and did not
alter the archive or create FULL seeds. Verification output:

- `.report-run/pr13-final/actual-producer-geometry-verification.json`
- source gzip SHA-256: `136f10687b7627e84721cc996d0e0035145900df1f6c31d89e705d679717a9fc`
- raw result content SHA-256: `1e5d5b9a1d9c1b9a2382c56074a37e1cbaa8ca8eb17b72b1e6034cd2900ba093`
- corrected result content SHA-256: `7e692ac1c84a2f678f0c227a9892bffd6e527ca7e03f3d98a2c25b02d9382b88`
- corrected executor identity SHA-256: 64 `e` characters; verification output content SHA-256: `69eb029b03d17457f9a23c72028574aa23a619a84dab9f88ce0c164a1bdce5fc`
- geometry: 450 source events, 288 paired statistical clusters, 126 singleton physical clusters, and 162 double-source physical clusters
- validator result: **ACCEPTED**

## Qualification and delivery status

The current macOS machine has 10 CPUs and 16 GiB RAM; FULL requires 28 CPUs
and 32 GiB. FULL is therefore `BLOCKED_RESOURCE`, with no held-out outcome and
no qualification receipt. The durable compact evidence copies, including the
manifest and final JSON, are under
`strategy-research/v5-records/evidence/corrected-worker-20260911/`.

The external completion archive is
`.report-run/pr13-completion-20260911-397e57e.tar.gz` (335,117,605 bytes,
637 entries, SHA-256
`07ad877032a22faeba4c2e4bba6128fcd671762c2bfed80b074157a8c29f7b1b`). It was
reopened successfully and its packaged JAR hash matched the final identity;
the recorded metadata is `external-archive.json`.

An earlier package checkpoint produced 0/8 because `ProcessSlotExecutor` still
used the frozen worker command; it is retained only as failed checkpoint
provenance. The final package above includes the executor fix and passed both
PREFIX runs. CI is checked on PR13 head after push.
