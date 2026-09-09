# v5 index artifact-layout audit (2026-09-06)

The authoritative `index` command walks every `*.json` below the supplied
root, except paths under `receipts`, transaction/control directories, and the
requested output. It requires each walked file to be a JSON object with an
allowed schema. The indexer is therefore a curated-record indexer, not a
generic archive catalog.

The pre-existing tracked corpus was still indexable. A temporary checkout made
with `git archive HEAD strategy-research/v5-records` (HEAD
`e54675065603ec8a578e482104a1ef15b7409807`) indexed successfully with the
current Java implementation, producing 11 records after the receipt and
existing-index exclusions. This establishes that the default tracked corpus
was not already unsupported before the research-improvement evidence was
added.

The working `strategy-research/v5-records` tree is a different scope: before
the retention boundary was added, it had
130 JSON files, 86 under the retained evidence archive, 9 JSON array files,
30 object files without a `schema`, and 2 `analytics-build-identity/1`
receipts. A read-only probe using the final packaged JAR and an output/receipt
root under `/tmp` failed immediately with:

```
legacy schema is not allowed at the v5 boundary: analytics-build-identity/1
```

Indexing `evidence/role-receipts-v004/` separately fails on its raw array
inputs with `indexed artifact is not a JSON object`. These are physical role
inputs and provenance files, not index records. The retained v002/v003
operating-characteristics plans are also historical invalid-hash errata; the
legacy schema branch can index such a file without proving its declared
`content_sha256` (a one-file v002 probe succeeded and preserved its declared
hash). An index row must not be interpreted as a valid freeze or promotion
binding.

The final boundary is explicit and fail-closed. The physical file
`strategy-research/v5-records/evidence/.retention-archive` contains exactly
`strategy-research-retention-archive/1` plus a newline (SHA-256
`1f5a1a4f5f06632c8565a245d08b37f773741eabe83392e387ae968d6c789beb`). The
authoritative index validates that marker, excludes only the marked evidence
directory, and continues to parse transaction-control paths there so a
canonical publication cannot be hidden. An unmarked, malformed, or symlink
archive fails; invalid JSON or unknown schemas outside it still fail. The
legacy `strategy-fixed-attempt-ledger/1` sidecar is registered as a narrow
read-only compatibility contract rather than skipped.

With that correction, a fresh E14 package indexed the full current
`strategy-research/v5-records` root successfully and produced 13 records:
the 11 canonical tracked records, the current exposure head, and its fixed
attempt ledger. Raw role arrays, build-identity receipts, and historical D
errata remained physically present but were excluded by the marker. A cockpit
or review that needs only selected evidence may still use a separate curated
root containing registered JSON object contracts that passed their own
semantic/content-hash validation, with `--record-root` outside the archive.
The fixed-baseline archive's compact
`strategy-research-evidence-summary/1` projections and
`RetainedFixedEvidenceSchemaTest` remain the appropriate review surface.
The E14 executable SHA is
`4567c7d493f07cdec806f046c40007e02bbac7358c41cd0eb5e1403183395a6d`; the
current-root index content hash is
`6a557327f21e21bb8f9aa0de48a9d9becb198289d3438ea58edf1349aed65686`.
