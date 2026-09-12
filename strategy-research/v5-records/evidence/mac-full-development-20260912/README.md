# Mac FULL development evidence — 2026-09-12

This is the compact evidence set for the bounded FULL development execution
described in [`docs/MAC-FULL-DEVELOPMENT-20260912.md`](../../../../docs/MAC-FULL-DEVELOPMENT-20260912.md).
It records two waves over the same eight frozen development seeds. All 16
strict artifact validations passed; all eight serial/parallel corrected
economic digests matched. The final independent audit passed for 16 artifacts,
32 books, 14,400 trades, and 2,938,950 points in each of the book and combined
curves, confirming matching metrics, digests, and all three curves by seed.
Formal FULL remains `BLOCKED_RESOURCE`; this package is not a qualification or
performance claim.

## Contents

- `package/`: pinned package manifest, build identity, execution profile, and
  plan. The 107,286,790-byte executor JAR is intentionally not duplicated.
- `run/`: run declaration, preflight, integrity record, runner timing,
  per-wave execution records/resource measurements/strict validation, and
  final independent-audit summary, detail, and status receipts.
- `archive-receipt.json`: verified full-run archive receipt.
- `scripts/`: the development runner, strict Java artifact validator, and
  final independent audit scripts.
- `SHA256SUMS.txt`: file hashes for every retained evidence file other than
  the checksum list itself.

The full per-slot worker-result JSON and 107,286,790-byte executor JAR are not
duplicated in this compact tree. The owner-local, ignored archive is
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.tar.gz`
(827,211,640 bytes; SHA-256
`e5236c2993cf83029426746e36ce269597c67ede0a40aa21e1f5bd64f89c56ac`). Its
receipt confirms all 1,115 archive members and 17 worker results, including the
final JAR. The inventory sidecar at
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.inventory.json`
is 477,087 bytes with SHA-256
`29ca906b8cb206a6217d8c6ccc210052a89c5b05325c0297c70b3fb4cbeefa6c`; it remains
beside the archive rather than being duplicated here.

The initial failed two-worker run is retained in the owner-local, ignored file
`/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-failure-20260911.tar.gz`.
The initial independent audit's path-only comparison failure was superseded by
the corrected replay audit; its failed interim receipt is intentionally
excluded from this final evidence set. The archive's curated prefix must be
restored for replay: `mac-full-development-20260912/paired-waves/` maps to
`/private/tmp/trading-pr13/.report-run/mac-full-repaired/full-paired-waves/`,
and `mac-full-development-20260912/final-package/` maps to
`/private/tmp/trading-pr13/.report-run/mac-full-repaired/final-package/`.
Preserve the original path identity; a fresh harness run uses its own `--run`
path instead.

## Pinned bindings

| Binding | SHA-256 |
| --- | --- |
| Executor JAR | `b78f6b0906b8548ba1ccebe1e69572b2fcc29fac3a438bde017cbf1ea4ad6827` |
| Executor source | `f7e7c28e7d4177da5cfa5cb6675b9b374c85faba598e78f28884533f7dbaf05c` |
| Plan content | `60c9904b3b103ab1ea1069c1faa8129e75879e4bb033f9fa7f7042abfd6ecd75` |
| Execution profile content | `87a3112be2f617793dc40e4cb9e228c51f355c0bd4dc6c84833e66f33c2dd6ae` |

Source revision: `d0d34d0df5b8814c640619756d8d7e51d2717e84` (`dirty_source=false`).
The package manifest binds all four values and must be supplied with
`--manifest` when rerunning the harness.

## Reproduction

See the reproduction command in the linked run report. It requires the full
repository at the pinned revision, JDK 21, and the preserved package directory
including the JAR. A fresh, ignored run directory is required. Reproduction
is limited to this engineering diagnostic; the formal FULL qualification gate
remains blocked on the captured Mac profile.
