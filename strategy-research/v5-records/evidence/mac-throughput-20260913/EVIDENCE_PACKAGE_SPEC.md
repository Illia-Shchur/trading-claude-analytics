# Compact throughput evidence package spec

The package is a small, hash-manifested companion to the persistent raw stage archive. It is built only after every stage has a terminal `stage-control.json` and `stage-result.json`; any running or incomplete stage aborts packaging. The package does not certify qualification or change the engineering-only scope.

`package-throughput-evidence.py` explicitly copies plans and their snapshots, plan-change and preflight receipts, the reference capsule and normalization smoke, package manifest/plan/profile, build receipts, independent reviews including the final throughput review, real package-binding and harness-test receipts, harness source and tests, baseline restoration/audit/inventory receipts, archive custody and stage archive receipts, and each stage's small control, validation, independent audit, timing, execution, GC, censor, and resource receipts. Each copied file is byte-checked and listed with source path, size, and SHA-256 in `evidence-manifest.json`.

The script omits the executable JAR, raw worker-result JSON, payloads, scratch repositories, stdout/stderr and GC logs, and the recovered baseline archive. The raw stage tree and raw archive stay in the separate ignored evidence archive. Per-file and total size caps prevent large files from entering the compact package by accident. Existing package outputs are never overwritten.

Stage receipts are copied byte-for-byte. For `stage-4w-1024`, the original `gc-summary.json`, `execution-records.json`, and `batch-metrics.json` remain unchanged and are included only as provenance. The derived summary uses `gc-diagnostic-correction.json` for that stage's GC values and identifies the correction receipt as the source. Never interpret the original 4-worker GC counters as complete: the earlier parser missed padded HotSpot tags.

Only matching recognized terminal control/result receipts are packageable. A stage result with `status: PASS`, strict validation PASS, independent frozen audit PASS, all eight jobs complete, and clock integrity PASS is shown as validated or comparison eligible. A censored stage has `batch_seconds: null`; its elapsed time appears only as a lower bound and diagnostic evidence, never as a completed timing or incumbent. The derived summary reports the selected tested configuration with measured alternatives, without implying that it is fastest or a global optimum.

Run after root confirms all desired stages have reached terminal validation:

```sh
python3 .report-run/throughput-search-20260913/package-throughput-evidence.py \
  --output .report-run/throughput-search-20260913/throughput-evidence-final.tar.gz
```

The script prints the archive path, byte size, SHA-256, included-file count, and stage statuses. Preserve that output beside the resulting archive.
