#!/usr/bin/env python3
"""Build a compact, hash-manifested evidence bundle after all stages are terminal."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tarfile
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 100 * 1024 * 1024

STATIC_FILES = [
    "PLAN.md", "PLAN.before-stage-8x1024-censor.md", "PLAN.before-stage-8x896-censor.md",
    "plan-change-receipt.json", "plan-change-after-8w-896-censor.json",
    "preflight-failure-1.json", "reference-capsule.json", "baseline-restoration.json",
    "reference-normalization-smoke.json", "reference-normalization-smoke-attempt-2.json",
    "build-verification.json", "new-code-coverage.txt", "host.json", "operator-state.json",
    "independent-review.json", "independent-gc-parser-review.json",
    "independent-real-package-binding.json", "independent-final-throughput-review.json",
    "harness-tests.json",
    "EVIDENCE_PACKAGE_SPEC.md", "EVIDENCE_README_DRAFT.md", "package-throughput-evidence.py",
    "harness/run_mac_full_development.py", "harness/run_mac_full_development.pre-gc-parser-fix.py",
    "harness/StrictArtifactValidator.java", "run-throughput-stage.py",
    "tests/test_throughput_harness.py",
    "candidate-package/manifest.json", "candidate-package/plan.json", "candidate-package/profile.json",
    "archive-custody.json", "archives/campaign-setup.receipt.json",
]
EXTERNAL_FILES = [
    REPO / "strategy-research/v5-records/evidence/mac-full-development-20260912/run/independent-audit.json",
    Path("/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.inventory.json"),
]
STAGE_FILES = [
    "stage-control.json", "stage-result.json", "package-bindings.json", "command.json",
    "runner-process.json", "run/manifest.json", "run/declaration.json", "run/integrity.json",
    "run/production-full-preflight.json", "run/parallel/batch-metrics.json",
    "run/parallel/gc-summary.json",
    "run/parallel/execution-records.json", "run/parallel/strict-validation.json",
    "run/parallel/resource-measurement.json", "run/parallel/censored.json",
    "independent-audit.json", "raw-artifact-preservation.json",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


TERMINAL_STAGE_STATUSES = {"PASS", "CENSORED", "FAILED", "INTERRUPTED"}


def read_terminal_stage(stage: Path) -> tuple[dict, dict]:
    control_path = stage / "stage-control.json"
    result_path = stage / "stage-result.json"
    if not control_path.is_file() or not result_path.is_file():
        raise RuntimeError(f"stage is incomplete; package only after terminal validation: {stage.name}")
    control, result = read_json(control_path), read_json(result_path)
    if not isinstance(control, dict) or not isinstance(result, dict):
        raise RuntimeError(f"stage receipts must be JSON objects: {stage.name}")
    if control.get("schema") != "mac-throughput-stage-control/1":
        raise RuntimeError(f"unrecognized stage-control schema: {stage.name}")
    if result.get("schema") != "mac-throughput-stage-result/1":
        raise RuntimeError(f"unrecognized stage-result schema: {stage.name}")
    control_status, result_status = control.get("status"), result.get("status")
    if control_status not in TERMINAL_STAGE_STATUSES or result_status not in TERMINAL_STAGE_STATUSES:
        raise RuntimeError(f"stage lacks recognized terminal control/result status: {stage.name}")
    if control_status != result_status:
        raise RuntimeError(f"stage control/result terminal status mismatch: {stage.name}")
    if control.get("stage_name") != stage.name or result.get("stage_name") != stage.name:
        raise RuntimeError(f"stage receipt name does not match directory: {stage.name}")
    if control.get("status") == "PASS" and control.get("runner_exit_code") != 0:
        raise RuntimeError(f"PASS stage has nonzero runner exit: {stage.name}")
    return control, result


def stage_timing_fields(result: dict, batch: dict) -> dict:
    status = result.get("status")
    seconds = batch.get("worker_batch_wall_seconds")
    completed = batch.get("worker_completion_count")
    planned = batch.get("planned_worker_count")
    timing_ok = batch.get("timing_integrity") == "PASS" and result.get("timing_integrity", "PASS") == "PASS"
    if status == "PASS":
        validated_complete = (
            result.get("strict_validator") == "PASS"
            and result.get("independent_frozen_audit") == "PASS"
            and result.get("complete_eight_seed_batch") is True
            and result.get("comparison_eligible") is True
            and completed == planned == 8
            and timing_ok
            and isinstance(seconds, (int, float))
            and seconds > 0
        )
        if not validated_complete:
            raise RuntimeError("PASS stage lacks complete validated eight-job timing")
        return {"batch_seconds": seconds, "elapsed_lower_bound_seconds": None}
    if status == "CENSORED":
        if not timing_ok or completed is None or planned is None or completed >= planned:
            raise RuntimeError("CENSORED stage lacks an incomplete timing-integrity lower bound")
        if not isinstance(seconds, (int, float)) or seconds <= 0:
            raise RuntimeError("CENSORED stage is missing its elapsed lower bound")
        return {"batch_seconds": None, "elapsed_lower_bound_seconds": seconds}
    return {"batch_seconds": None, "elapsed_lower_bound_seconds": None}


def add_file(source: Path, archive_name: str, stage_root: Path, entries: list[dict], total: list[int]) -> None:
    if not source.is_file():
        return
    size = source.stat().st_size
    if size > MAX_FILE_BYTES:
        raise RuntimeError(f"evidence file exceeds the {MAX_FILE_BYTES}-byte cap: {source}")
    total[0] += size
    if total[0] > MAX_TOTAL_BYTES:
        raise RuntimeError(f"compact evidence exceeds the {MAX_TOTAL_BYTES}-byte total cap")
    target = stage_root / archive_name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    digest = sha256(source)
    if sha256(target) != digest:
        raise RuntimeError(f"copied evidence hash mismatch: {source}")
    entries.append({"path": archive_name, "source": str(source), "bytes": size, "sha256": digest})


def add_derived_summary(root: Path, stage_dirs: list[Path], staging: Path,
                        entries: list[dict], total: list[int]) -> None:
    rows = []
    for stage in stage_dirs:
        result = read_json(stage / "stage-result.json")
        batch_path = stage / "run/parallel/batch-metrics.json"
        gc_path = stage / "run/parallel/gc-summary.json"
        batch = read_json(batch_path) if batch_path.is_file() else {}
        gc_source = gc_path
        gc = read_json(gc_path) if gc_path.is_file() else {}
        correction_path = stage / "gc-diagnostic-correction.json"
        if stage.name == "stage-4w-1024":
            if not correction_path.is_file():
                raise RuntimeError("stage-4w-1024 requires its corrected GC diagnostic supplement")
            correction = read_json(correction_path)
            if correction.get("status") != "PASS" or len(correction.get("worker_logs", [])) != 8:
                raise RuntimeError("stage-4w-1024 GC correction is incomplete")
            gc = correction["corrected_aggregate"]
            gc_source = correction_path
        elif correction_path.is_file():
            correction = read_json(correction_path)
            if correction.get("status") == "PASS":
                gc = correction.get("corrected_aggregate", gc)
                gc_source = correction_path
        timing = stage_timing_fields(result, batch)
        validated = result.get("status") == "PASS"
        rows.append({
            "stage_name": stage.name,
            "status": result.get("status"),
            "comparison_eligible": bool(result.get("comparison_eligible")) and validated,
            "validated": validated,
            "workers": result.get("workers", batch.get("workers")),
            "heap_mib": result.get("heap_mib", batch.get("heap_mib")),
            "completed_jobs": batch.get("worker_completion_count"),
            "planned_jobs": batch.get("planned_worker_count"),
            **timing,
            "timing_integrity": batch.get("timing_integrity"),
            "gc_diagnostics": gc,
            "gc_diagnostic_source": str(gc_source) if gc_source.exists() else None,
            "source_receipt_sha256": {
                "stage_result": sha256(stage / "stage-result.json"),
                "batch_metrics": sha256(batch_path) if batch_path.is_file() else None,
                "gc_summary_or_correction": sha256(gc_source) if gc_source.is_file() else None,
            },
        })
    summary = {
        "schema": "mac-throughput-derived-stage-summary/1",
        "scope": "ENGINEERING_ONLY_NOT_QUALIFICATION",
        "stage_results": rows,
        "stage4_gc_note": (
            "Use the hash-bound gc-diagnostic-correction.json for stage-4w-1024 GC totals. "
            "The original gc-summary.json, execution-records.json, and batch-metrics.json are included byte-exactly; "
            "their original GC counters reflect the padded-tag parser defect and are retained only as source evidence."
        ),
        "eligibility_rule": (
            "Only a complete PASS with strict validation and independent audit is validated. "
            "CENSORED runs are lower bounds, not complete timings or incumbents."
        ),
        "conclusion_rule": (
            "Report the selected tested configuration with measured alternatives; "
            "do not imply it is fastest or a global optimum."
        ),
    }
    archive_name = "derived-stage-summary.json"
    target = staging / archive_name
    target.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    size = target.stat().st_size
    total[0] += size
    if total[0] > MAX_TOTAL_BYTES:
        raise RuntimeError("compact evidence exceeds the total size cap")
    entries.append({"path": archive_name, "source": "derived from terminal stage receipts",
                    "bytes": size, "sha256": sha256(target), "derived": True})


def build(output: Path) -> dict:
    output = output.resolve()
    if output.exists():
        raise RuntimeError(f"refusing to overwrite evidence archive: {output}")
    stage_dirs = sorted(path for path in (ROOT / "stages").iterdir() if path.is_dir())
    if not stage_dirs:
        raise RuntimeError("no stage evidence found")
    for stage in stage_dirs:
        read_terminal_stage(stage)

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="throughput-evidence-", dir=ROOT) as temporary:
        staging = Path(temporary)
        entries: list[dict] = []
        total = [0]
        for relative in STATIC_FILES:
            add_file(ROOT / relative, relative, staging, entries, total)
        for source in EXTERNAL_FILES:
            add_file(source, f"external/{source.name}", staging, entries, total)
        for stage in stage_dirs:
            stage_name = stage.name
            for relative in STAGE_FILES:
                if relative.endswith("/strict-validation.json") and not (stage / relative).exists():
                    continue
                if relative.endswith("/censored.json") and not (stage / relative).exists():
                    continue
                add_file(stage / relative, f"stages/{stage_name}/{relative}", staging, entries, total)
            archive_receipt = ROOT / "archives" / f"{stage_name}.receipt.json"
            add_file(archive_receipt, f"archives/{stage_name}.receipt.json", staging, entries, total)
            correction = stage / "gc-diagnostic-correction.json"
            if correction.is_file():
                add_file(correction, f"stages/{stage_name}/gc-diagnostic-correction.json", staging, entries, total)
        add_derived_summary(ROOT, stage_dirs, staging, entries, total)

        manifest = {
            "schema": "mac-throughput-compact-evidence/1",
            "scope": "ENGINEERING_ONLY_NOT_QUALIFICATION",
            "created_at_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "stage_results": {stage.name: read_terminal_stage(stage)[1].get("status") for stage in stage_dirs},
            "included_files": sorted(entries, key=lambda row: row["path"]),
            "excluded_heavy_evidence": [
                "worker-result.json raw artifacts", "executor.jar", "worker stdout/stderr and GC logs",
                "payload.json", "scratch repositories", "recovered baseline archive and raw baseline artifacts",
            ],
            "validation_rule": "Only stage-result PASS means strict plus independent audit passed; CENSORED is a lower bound and never validated or eligible as best.",
        }
        manifest_path = staging / "evidence-manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temp_archive = output.with_name(output.name + ".tmp")
        if temp_archive.exists():
            raise RuntimeError(f"temporary archive already exists; preserving it: {temp_archive}")
        with tarfile.open(temp_archive, "w:gz") as archive:
            for path in sorted(staging.rglob("*")):
                if path.is_file():
                    archive.add(path, arcname=str(path.relative_to(staging)), recursive=False)
        os.replace(temp_archive, output)
    return {"path": str(output), "bytes": output.stat().st_size,
            "sha256": sha256(output), "included_file_count": len(entries) + 1,
            "stage_results": manifest["stage_results"]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        default=ROOT / f"throughput-evidence-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}.tar.gz")
    args = parser.parse_args()
    print(json.dumps(build(args.output), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
