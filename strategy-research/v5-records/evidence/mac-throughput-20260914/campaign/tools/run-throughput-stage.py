#!/usr/bin/env python3
"""Prepare the frozen reference capsule or run and validate one Mac batch."""

from __future__ import annotations

import argparse
import base64
import gc
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import signal
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_REFERENCE_AUDIT = Path(
    "strategy-research/v5-records/evidence/mac-full-development-20260912/run/independent-audit.json"
)
DEFAULT_ARCHIVE_ROOT = ROOT.parent / "recovered-baseline/mac-full-development-20260912"
DEFAULT_RESTORATION = ROOT / "baseline-restoration.json"
DEFAULT_INVENTORY = Path(
    "/Users/eternal/Desktop/Trading Claude Analytics/.report-run/mac-full-development-20260912.inventory.json"
)
REFERENCE_AUDIT_SHA256 = "19b1dda085df1f4c922d867929dec6460d929f02d7ec96dcdff6729bb186a280"
CANDIDATE_MANIFEST_SHA256 = "9aaf5ce6e33d5de1147c4708060a71d04c957878a5cb23f32dccb054347c169b"
ARCHIVE_SHA256 = "e5236c2993cf83029426746e36ce269597c67ede0a40aa21e1f5bd64f89c56ac"
NORMALIZED_PROVENANCE_PATHS = [
    "/row/raw_evaluator_result/physical_input_sha256",
    "/row/raw_evaluator_result/corrected_input_binding/physical_input_sha256",
    "/row/raw_evaluator_result/physical_source_producer/plan_sha256",
]


class StageInterrupted(Exception):
    def __init__(self, signum: int):
        super().__init__(signal.Signals(signum).name)
        self.signum = signum


def load_harness(path: Path):
    spec = importlib.util.spec_from_file_location("mac_full_development_harness", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load worker harness: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise RuntimeError(f"expected JSON object: {path}")
    return value


def read_json_any(path: Path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def extract_provenance(path: Path, expected_slot: dict) -> dict:
    """Read one historical result at a time and retain only its three bindings."""
    with path.open(encoding="utf-8") as stream:
        artifact = json.load(stream)
    for field in ("mode", "scenario", "effect_size", "replication", "seed"):
        if artifact.get(field) != expected_slot[field]:
            raise RuntimeError(f"archived artifact {field} does not match {expected_slot['slot_id']}")
    if artifact.get("status") != "COMPLETE" or artifact.get("row", {}).get("status") != "COMPLETE":
        raise RuntimeError(f"archived artifact is not complete: {expected_slot['slot_id']}")
    raw = artifact.get("row", {}).get("raw_evaluator_result", {})
    binding = raw.get("corrected_input_binding", {})
    producer = raw.get("physical_source_producer", {})
    provenance = {
        "physical_input_sha256": raw.get("physical_input_sha256"),
        "corrected_input_binding_physical_input_sha256": binding.get("physical_input_sha256"),
        "physical_source_producer_plan_sha256": producer.get("plan_sha256"),
    }
    if any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
           for value in provenance.values()):
        raise RuntimeError(f"archived artifact has incomplete provenance bindings: {expected_slot['slot_id']}")
    portable = artifact.get("portable_economic_sha256")
    if not isinstance(portable, str) or not re.fullmatch(r"[0-9a-f]{64}", portable):
        raise RuntimeError(f"archived artifact has no portable economic hash: {expected_slot['slot_id']}")
    del artifact
    gc.collect()
    return {"provenance": provenance, "portable_economic_sha256": portable}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    try:
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    except OSError:
        pass


def canonical_hash(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                         allow_nan=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def verify_supporting_dependency_receipt(receipt: dict) -> dict:
    """Verify frozen support data using JsonHashes.ownHash's root-field rule."""
    relative = receipt.get("relative_path", "<unknown>")
    try:
        payload = base64.b64decode(receipt["data_base64"], validate=True)
    except (KeyError, ValueError) as error:
        raise RuntimeError(f"support-data payload is not valid base64: {relative}") from error
    if (len(payload) != receipt.get("bytes")
            or hashlib.sha256(payload).hexdigest() != receipt.get("byte_sha256")):
        raise RuntimeError(f"support-data byte binding failed: {relative}")
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"support-data payload is not valid JSON: {relative}") from error
    if not isinstance(document, dict):
        raise RuntimeError(f"support-data payload must be a JSON object: {relative}")
    expected_content_hash = receipt.get("content_sha256")
    if not isinstance(expected_content_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_content_hash):
        raise RuntimeError(f"support-data content receipt is not a SHA-256: {relative}")
    # JsonHashes.ownHash(value) removes only the root content_sha256 key;
    # nested self-hash fields remain part of the canonical object.
    without_root_hash = {key: value for key, value in document.items() if key != "content_sha256"}
    if canonical_hash(without_root_hash) != expected_content_hash:
        raise RuntimeError(f"support-data content binding failed: {relative}")
    return {key: receipt[key] for key in ("relative_path", "bytes", "byte_sha256", "content_sha256")}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def expected_slots(harness) -> dict[str, dict]:
    slots = {}
    for cell in sorted(harness.EXPECTED_DEVELOPMENT_SEEDS.items(),
                       key=lambda item: (item[0][0], item[0][1])):
        (scenario, effect_size), seeds = cell
        for replication, seed in enumerate(seeds):
            slot_id = f"FULL|{scenario}|{effect_size:g}|{replication}|{seed}"
            slots[slot_id] = {
                "slot_id": slot_id, "mode": "FULL", "scenario": scenario,
                "effect_size": effect_size, "replication": replication, "seed": seed,
            }
    if len(slots) != 8:
        raise RuntimeError("the fixed development inventory must contain exactly eight slots")
    return slots


def prepare_reference(args, harness) -> dict:
    repo = args.repo.resolve()
    audit_path = (repo / args.reference_audit).resolve() if not args.reference_audit.is_absolute() else args.reference_audit.resolve()
    archive_root = args.archive_root.resolve()
    restoration = read_json(args.restoration_receipt.resolve())
    inventory = read_json(args.archive_inventory.resolve())
    if restoration.get("sha256") != ARCHIVE_SHA256 or digest_file(Path(restoration["archive"])) != ARCHIVE_SHA256:
        raise RuntimeError("recovered archive SHA does not match its restoration receipt")
    if Path(restoration.get("restored_to", "")).resolve() != archive_root.parent.resolve():
        raise RuntimeError("archive restoration receipt points to a different extraction root")
    if inventory.get("archive_root") != "mac-full-development-20260912":
        raise RuntimeError("recovery inventory names a different archive")
    if inventory.get("scope") != "ENGINEERING_ONLY_NOT_QUALIFICATION":
        raise RuntimeError("recovery inventory scope is not the frozen engineering archive")
    if digest_file(audit_path) != REFERENCE_AUDIT_SHA256:
        raise RuntimeError("tracked independent-audit reference hash changed")
    audit = read_json(audit_path)
    if (audit.get("status") != "PASS" or audit.get("artifacts_audited") != 16
            or audit.get("independently_reconciled_books") != 32):
        raise RuntimeError("tracked reference audit does not cover the frozen 16-artifact baseline")

    fixed_slots = expected_slots(harness)
    inventory_rows = {row["path"]: row for row in inventory.get("files", [])}
    baseline: dict[int, dict] = {}
    seen: set[tuple[str, str]] = set()
    archived_results = []
    if len(audit.get("artifacts", [])) != 16:
        raise RuntimeError("reference audit inventory must contain exactly 16 results")
    for row in audit["artifacts"]:
        slot_id = row.get("slot_id")
        if slot_id not in fixed_slots:
            raise RuntimeError(f"reference audit has an unexpected slot: {slot_id}")
        source = Path(row.get("path", ""))
        match = re.search(r"/(parallel|serial)/slots/([^/]+)/worker-result\.json$", str(source))
        if not match:
            raise RuntimeError(f"reference audit artifact path has no paired wave: {source}")
        wave, slot_dir_name = match.groups()
        if slot_dir_name != slot_id.replace("|", "_"):
            raise RuntimeError(f"reference path does not bind to its slot id: {slot_id}")
        key = (wave, slot_id)
        if key in seen:
            raise RuntimeError(f"duplicate reference audit slot: {key}")
        seen.add(key)
        relative = f"mac-full-development-20260912/paired-waves/{wave}/slots/{slot_dir_name}/worker-result.json"
        archive_path = archive_root / "paired-waves" / wave / "slots" / slot_dir_name / "worker-result.json"
        archived_row = inventory_rows.get(relative)
        if archived_row is None:
            raise RuntimeError(f"archive inventory omits baseline artifact: {relative}")
        byte_hash = digest_file(archive_path)
        size = archive_path.stat().st_size
        if byte_hash != row.get("byte_sha256") or byte_hash != archived_row.get("sha256") or size != archived_row.get("bytes"):
            raise RuntimeError(f"restored baseline bytes do not match both frozen receipts: {relative}")
        expected = fixed_slots[slot_id]
        for field in ("seed", "scenario", "effect_size", "replication"):
            if row.get(field) != expected[field]:
                raise RuntimeError(f"reference audit {field} does not match its fixed slot: {slot_id}")
        extracted = extract_provenance(archive_path, expected)
        if extracted["portable_economic_sha256"] != row.get("portable_economic_sha256"):
            raise RuntimeError(f"archived portable economic hash differs from its audit row: {slot_id}")
        archived_results.append({
            "wave": wave, **expected, "relative_path": relative, "bytes": size,
            "byte_sha256": byte_hash, "portable_economic_sha256": row["portable_economic_sha256"],
            "comparison_sha256": row["comparison_sha256"],
        })
        seed = expected["seed"]
        seed_record = baseline.setdefault(seed, {})
        seed_record[wave] = {
            "relative_path": relative, "byte_sha256": byte_hash,
            "portable_economic_sha256": row["portable_economic_sha256"],
            "comparison_sha256": row["comparison_sha256"],
            "provenance": extracted["provenance"],
        }

    if len(seen) != 16 or any(set(pair) != {"parallel", "serial"} for pair in baseline.values()):
        raise RuntimeError("restored paired-wave inventory is incomplete")
    for seed, pair in baseline.items():
        for field in ("portable_economic_sha256", "comparison_sha256", "provenance"):
            if pair["parallel"][field] != pair["serial"][field]:
                raise RuntimeError(f"baseline serial/parallel {field} mismatch for seed {seed}")

    smoke_base = args.search_root.resolve() / "reference-normalization-smoke"
    smoke_dir = smoke_base
    smoke_receipt_path = smoke_dir.with_suffix(".json")
    attempt = 1
    while smoke_dir.exists() or smoke_receipt_path.exists():
        attempt += 1
        smoke_dir = smoke_base.with_name(f"{smoke_base.name}-attempt-{attempt}")
        smoke_receipt_path = smoke_dir.with_suffix(".json")
    smoke_dir.mkdir(parents=True)
    smoke_seed = min(baseline)
    smoke_entry = baseline[smoke_seed]["parallel"]
    smoke_artifact = archive_root / Path(smoke_entry["relative_path"]).relative_to(
        "mac-full-development-20260912")
    smoke_binding = smoke_dir / "baseline-provenance-binding.json"
    write_json(smoke_binding, smoke_entry["provenance"])
    validator_classpath = harness.compile_strict_validator(
        repo, smoke_dir, args.package.resolve() / "executor.jar", args.java)
    smoke_before = digest_file(smoke_artifact)
    if smoke_before != smoke_entry["byte_sha256"]:
        raise RuntimeError("baseline artifact changed before normalization smoke")
    smoke_command = [args.java, "-Xmx4g", "-XX:ActiveProcessorCount=1", "-cp",
                     str(validator_classpath), "com.tradinganalytics.research.v5.StrictArtifactValidator",
                     "--compare-normalized", str(smoke_artifact), str(smoke_binding),
                     smoke_entry["portable_economic_sha256"]]
    smoke_started_at = utc_now()
    smoke = subprocess.run(smoke_command, cwd=smoke_dir, capture_output=True, text=True)
    (smoke_dir / "stdout.json").write_text(smoke.stdout, encoding="utf-8")
    (smoke_dir / "stderr.log").write_text(smoke.stderr, encoding="utf-8")
    smoke_after = digest_file(smoke_artifact)
    smoke_record = {
        "schema": "mac-throughput-normalization-smoke/1",
        "status": "PASS" if smoke.returncode == 0 and smoke_after == smoke_before else "FAILED",
        "artifact": str(smoke_artifact), "artifact_sha256_before": smoke_before,
        "artifact_sha256_after": smoke_after, "artifact_preserved": smoke_after == smoke_before,
        "java_heap_mib": 4096, "exit_code": smoke.returncode,
        "stdout_path": str(smoke_dir / "stdout.json"), "stderr_path": str(smoke_dir / "stderr.log"),
        "started_at_utc": smoke_started_at, "completed_at_utc": utc_now(),
    }
    if smoke_record["status"] != "PASS":
        write_json(smoke_receipt_path, smoke_record)
        raise RuntimeError("full-size baseline self-normalization smoke failed; see its durable receipt")
    smoke_result = json.loads(smoke.stdout)
    if (smoke_result.get("status") != "PASS"
            or smoke_result.get("candidate_normalized_economic_sha256") != smoke_entry["portable_economic_sha256"]
            or smoke_result.get("reference_economic_sha256") != smoke_entry["portable_economic_sha256"]
            or smoke_result.get("normalized_paths") != NORMALIZED_PROVENANCE_PATHS):
        smoke_record["status"] = "FAILED"
        write_json(smoke_receipt_path, smoke_record)
        raise RuntimeError("baseline normalization smoke did not match the expected frozen economic digest")
    smoke_record["economic_sha256"] = smoke_entry["portable_economic_sha256"]
    write_json(smoke_receipt_path, smoke_record)

    body = {
        "schema": "mac-throughput-reference-capsule/1", "status": "PASS",
        "archive_sha256": ARCHIVE_SHA256,
        "restoration_receipt_sha256": digest_file(args.restoration_receipt.resolve()),
        "archive_inventory_sha256": digest_file(args.archive_inventory.resolve()),
        "reference_audit_path": str(audit_path), "reference_audit_sha256": REFERENCE_AUDIT_SHA256,
        "archive_root": str(archive_root), "archived_results": archived_results,
        "baseline_by_seed": {str(seed): pair for seed, pair in sorted(baseline.items())},
        "comparison_contract": {
            "exact_metrics_and_three_curves_via_frozen_auditor_sha256": REFERENCE_AUDIT_SHA256,
            "allowed_provenance_normalization_paths": NORMALIZED_PROVENANCE_PATHS,
            "raw_artifacts_modified": False,
        },
        "normalization_smoke": {
            "status": "PASS", "receipt": str(smoke_receipt_path),
            "baseline_seed": smoke_seed, "artifact_sha256": smoke_entry["byte_sha256"],
            "economic_sha256": smoke_entry["portable_economic_sha256"],
            "baseline_provenance_extracted_once_per_archived_artifact": True,
        },
        "created_at_utc": utc_now(),
    }
    body["content_sha256"] = canonical_hash(body)
    output = args.search_root.resolve() / "reference-capsule.json"
    if output.exists():
        raise RuntimeError(f"reference capsule already exists; preserving prior receipt: {output}")
    write_json(output, body)
    return {"path": str(output), "content_sha256": body["content_sha256"], "artifacts": 16}


def package_bindings(args, harness) -> tuple[dict, dict, list[dict]]:
    repo = args.repo.resolve()
    package = args.package.resolve()
    manifest_path = args.manifest.resolve()
    if digest_file(manifest_path) != CANDIDATE_MANIFEST_SHA256:
        raise RuntimeError("candidate package manifest hash changed; expected the frozen 6d5e02d package")
    manifest = read_json(manifest_path)
    expected = harness.manifest_bindings(manifest, manifest_path)
    plan, profile, slots = harness.validate_declaration(repo, package, expected)
    dependencies = []
    for receipt in plan.get("supporting_dependency_receipts", []):
        dependencies.append(verify_supporting_dependency_receipt(receipt))
    tooling_paths = {
        "stage_controller": Path(__file__).resolve(),
        "worker_harness": args.runner.resolve(),
        "strict_artifact_validator_source": args.runner.resolve().with_name("StrictArtifactValidator.java"),
        "independent_frozen_auditor": args.audit_script.resolve(),
        "evidence_packager": (ROOT / "package-throughput-evidence.py").resolve(),
    }
    tooling_bindings = {}
    for name, path in tooling_paths.items():
        if not path.is_file():
            raise RuntimeError(f"frozen campaign tool is missing: {name}: {path}")
        tooling_bindings[name] = {"path": str(path), "sha256": digest_file(path), "bytes": path.stat().st_size}
    core_inputs = {
        "baseline": repo / "strategy-research/definitions/fk-deleveraging-absorption/v002.json",
        "controls": repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json",
        "experiment": repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json",
    }
    binding = {
        "schema": "mac-throughput-package-binding/1", "manifest_path": str(manifest_path),
        "manifest_sha256": digest_file(manifest_path),
        **expected.as_json(),
        "executor_jar_bytes": (package / "executor.jar").stat().st_size,
        "plan_file_sha256": digest_file(package / "plan.json"),
        "profile_file_sha256": digest_file(package / "profile.json"),
        "core_input_files": {name: {"path": str(path), "sha256": digest_file(path), "bytes": path.stat().st_size}
                             for name, path in core_inputs.items()},
        "supporting_dependency_receipts": dependencies,
        "tooling_bindings": tooling_bindings,
        "tooling_bindings_sha256": canonical_hash(tooling_bindings),
        "declared_source_identity_matches_plan": (
            plan.get("executor_source_sha256") == expected.source_sha256
            == plan.get("executor_build_input_fingerprint")),
        "planned_full_slots": slots,
        "expected_slot_count": 8,
        "scope": "ENGINEERING_ONLY_NOT_QUALIFICATION",
    }
    if not binding["declared_source_identity_matches_plan"] or len(slots) != 8:
        raise RuntimeError("candidate source identity or eight-seed plan binding failed")
    return binding, plan, slots


def persist_stage_error(args, error: BaseException, status: str = "FAILED") -> None:
    """Make validation failures and interruptions durable without replacing terminal receipts."""
    if not args.stage_name:
        return
    stage_dir = args.search_root.resolve() / "stages" / args.stage_name
    control_path = stage_dir / "stage-control.json"
    if not control_path.exists():
        return
    control = read_json(control_path)
    if control.get("status") not in ("RUNNING", "INTERRUPTING"):
        return
    result_path = stage_dir / "stage-result.json"
    prior = read_json(result_path) if result_path.exists() else {}
    if prior.get("status") in ("CENSORED", "FAILED", "INTERRUPTED"):
        status = prior["status"]
    else:
        write_json(result_path, {
            "schema": "mac-throughput-stage-result/1", "status": status,
            "comparison_eligible": False, "stage_name": args.stage_name,
            "error": str(error), "terminal_at_utc": utc_now(),
        })
    write_json(control_path, {
        **control, "status": status, "completed_at_utc": utc_now(),
        "completed_at_epoch_ns": time.time_ns(), "error": str(error),
    })


def terminate_child_group(process: subprocess.Popen, preferred_signal: int = signal.SIGINT) -> None:
    """Stop the runner and its worker descendants, then wait for durable cleanup."""
    try:
        os.killpg(process.pid, preferred_signal)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=60)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=20)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def verify_runtime_execution(runtime_binding: dict, profile: dict, package_manifest_sha256: str,
                             run_declaration: dict, batch: dict) -> None:
    """Reject any runner receipt that differs from the controller's frozen profile."""
    expected_binding = {
        "path": runtime_binding["path"],
        "content_sha256": runtime_binding["content_sha256"],
        "byte_sha256": runtime_binding["byte_sha256"],
        "profile_id": profile.get("profile_id"),
        "executor_manifest_sha256": package_manifest_sha256,
    }
    expected_settings = (profile["outer_workers"], profile["xmx_mib"],
                         profile["active_processor_count"], profile["gc_collector"])
    declaration_settings = (run_declaration.get("max_parallel_workers"),
                            run_declaration.get("launch_heap_mib"),
                            run_declaration.get("active_processors"),
                            run_declaration.get("jvm_gc"))
    batch_settings = (batch.get("workers"), batch.get("heap_mib"),
                      batch.get("active_processors"), batch.get("jvm_gc"))
    if (run_declaration.get("runtime_profile") != expected_binding
            or batch.get("runtime_profile") != expected_binding
            or declaration_settings != expected_settings or batch_settings != expected_settings):
        raise RuntimeError("runner or batch settings differ from the controller-frozen runtime profile")


def validate_stage(args, harness) -> dict:
    search_root = args.search_root.resolve()
    stage_dir = search_root / "stages" / args.stage_name
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", args.stage_name):
        raise RuntimeError("stage name must be a short filesystem-safe identifier")
    if stage_dir.exists():
        raise RuntimeError(f"stage path already exists; failed and complete evidence is immutable: {stage_dir}")
    binding, plan, slots = package_bindings(args, harness)
    if args.runtime_profile is None:
        raise RuntimeError("--run-stage requires a self-hashed --runtime-profile")
    runtime_path = args.runtime_profile.resolve()
    runtime_binding = harness.runtime_profile_bindings(runtime_path, binding["manifest_sha256"])
    runtime_bytes = runtime_path.read_bytes()
    if hashlib.sha256(runtime_bytes).hexdigest() != runtime_binding["byte_sha256"]:
        raise RuntimeError("runtime profile bytes changed while the controller was freezing them")
    runtime = runtime_binding["profile"]
    workers = runtime["outer_workers"]
    heap_mib = runtime["xmx_mib"]
    active_processors = runtime["active_processor_count"]
    gc_collector = runtime["gc_collector"]
    if workers > 8:
        raise RuntimeError("local runtime profile cannot exceed this fixed eight-job workload")
    reference = read_json(args.reference_capsule.resolve())
    supplied_hash = reference.get("content_sha256")
    reference_body = dict(reference)
    reference_body.pop("content_sha256", None)
    if reference.get("schema") != "mac-throughput-reference-capsule/1" or canonical_hash(reference_body) != supplied_hash:
        raise RuntimeError("reference capsule failed schema or canonical hash validation")
    if reference.get("status") != "PASS" or reference.get("archive_sha256") != ARCHIVE_SHA256:
        raise RuntimeError("reference capsule is not the verified frozen baseline")
    if digest_file(args.manifest.resolve()) != CANDIDATE_MANIFEST_SHA256:
        raise RuntimeError("candidate package changed between binding and stage startup")
    stage_dir.mkdir(parents=True)
    frozen_runtime_path = stage_dir / "runtime-profile.json"
    frozen_runtime_path.write_bytes(runtime_bytes)
    frozen_runtime_path.chmod(0o444)
    frozen_runtime_binding = harness.runtime_profile_bindings(
        frozen_runtime_path, binding["manifest_sha256"], runtime_binding["content_sha256"])
    if frozen_runtime_binding["byte_sha256"] != runtime_binding["byte_sha256"]:
        raise RuntimeError("frozen stage runtime profile differs from the validated input bytes")
    runtime_binding = frozen_runtime_binding
    runtime = runtime_binding["profile"]
    write_json(stage_dir / "package-bindings.json", binding)
    run_root = stage_dir / "run"
    runner = args.runner.resolve()
    declaration = {
        "schema": "mac-throughput-stage-control/1", "status": "RUNNING",
        "stage_name": args.stage_name, "workers": workers, "heap_mib": heap_mib,
        "active_processors": active_processors, "gc_collector": gc_collector,
        "runtime_profile_path": runtime_binding["path"],
        "runtime_profile_sha256": runtime_binding["content_sha256"],
        "runtime_profile_byte_sha256": runtime_binding["byte_sha256"],
        "runtime_profile_id": runtime.get("profile_id"),
        "package_manifest_sha256": binding["manifest_sha256"],
        "tooling_bindings_sha256": binding["tooling_bindings_sha256"],
        "reference_capsule_sha256": supplied_hash, "started_at_utc": utc_now(),
        "started_at_epoch_ns": time.time_ns(), "started_monotonic_ns": time.monotonic_ns(),
        "stage_path": str(stage_dir), "worker_run_path": str(run_root),
    }
    write_json(stage_dir / "stage-control.json", declaration)
    command = [sys.executable, str(runner), "--repo", str(args.repo.resolve()),
               "--run", str(run_root), "--package", str(args.package.resolve()),
               "--manifest", str(args.manifest.resolve()), "--wave", "parallel",
               "--runtime-profile", str(frozen_runtime_path),
               "--expected-runtime-profile-sha256", runtime_binding["content_sha256"],
               "--controller-source", str(Path(__file__).resolve()),
               "--stage-name", args.stage_name,
               "--java", args.java]
    if args.max_batch_seconds is not None:
        command.extend(["--max-batch-seconds", str(args.max_batch_seconds)])
    write_json(stage_dir / "command.json", {"argv": command})
    started = time.monotonic_ns()
    process = None
    old_sigint = signal.getsignal(signal.SIGINT)
    old_sigterm = signal.getsignal(signal.SIGTERM)

    def interrupt_child(signum, _frame):
        raise StageInterrupted(signum)

    with (stage_dir / "runner.stdout.log").open("w", encoding="utf-8") as stdout:
        try:
            signal.signal(signal.SIGINT, interrupt_child)
            signal.signal(signal.SIGTERM, interrupt_child)
            process = subprocess.Popen(command, cwd=args.repo.resolve(), stdout=stdout,
                                       stderr=subprocess.STDOUT, start_new_session=True, text=True)
            write_json(stage_dir / "runner-process.json", {
                "pid": process.pid, "started_at_utc": utc_now(), "started_at_epoch_ns": time.time_ns(),
                "started_monotonic_ns": started,
            })
            return_code = process.wait()
        except StageInterrupted as interrupted:
            if process is not None:
                terminate_child_group(process, interrupted.signum)
            persist_stage_error(args, interrupted, "INTERRUPTED")
            raise
        except BaseException:
            if process is not None and process.poll() is None:
                terminate_child_group(process, signal.SIGTERM)
            raise
        finally:
            signal.signal(signal.SIGINT, old_sigint)
            signal.signal(signal.SIGTERM, old_sigterm)
    elapsed = (time.monotonic_ns() - started) / 1e9
    runner_manifest_path = run_root / "manifest.json"
    runner_manifest = read_json(runner_manifest_path) if runner_manifest_path.exists() else {}
    if return_code != 0 or runner_manifest.get("status") != "STRICT_PASS_PENDING_INDEPENDENT_AUDIT":
        status = runner_manifest.get("status", "FAILED")
        result = {"schema": "mac-throughput-stage-result/1", "status": status,
                  "comparison_eligible": False, "stage_name": args.stage_name,
                  "runner_exit_code": return_code, "elapsed_seconds": elapsed,
                  "runner_manifest": str(runner_manifest_path)}
        write_json(stage_dir / "stage-result.json", result)
        raise RuntimeError(f"stage is {status}; it is preserved but cannot become best")

    integrity = read_json(run_root / "integrity.json")
    if integrity.get("status") != "PASS":
        raise RuntimeError("packaged JAR or frozen inputs changed during stage")
    if digest_file(frozen_runtime_path) != runtime_binding["byte_sha256"]:
        raise RuntimeError("frozen runtime profile bytes changed during stage")
    run_declaration = read_json(run_root / "declaration.json")
    batch = read_json(run_root / "parallel/batch-metrics.json")
    verify_runtime_execution(runtime_binding, runtime, binding["manifest_sha256"], run_declaration, batch)
    strict = read_json_any(run_root / "parallel/strict-validation.json")
    if not isinstance(strict, list):
        raise RuntimeError("strict-validation output must be a JSON array")
    if (batch.get("status") != "WORKERS_COMPLETE" or batch.get("planned_worker_count") != 8
            or batch.get("worker_completion_count") != 8 or batch.get("timing_integrity") != "PASS"):
        raise RuntimeError("stage timing or eight-worker completion is not eligible for comparison")
    expected_inventory = expected_slots(harness)
    expected_ids = set(expected_inventory)
    if set(batch.get("slot_states", {})) != expected_ids or len(strict) != 8:
        raise RuntimeError("stage strict-validation inventory is not exactly the fixed eight seeds")
    strict_by_slot = {row.get("slot_id"): row for row in strict}
    if set(strict_by_slot) != expected_ids or any(row.get("status") != "PASS" for row in strict):
        raise RuntimeError("StrictArtifactValidator did not bind each expected slot exactly once")
    execution_records = read_json_any(run_root / "parallel/execution-records.json")
    if not isinstance(execution_records, list):
        raise RuntimeError("execution-records output must be a JSON array")
    run_rows = {row.get("slot_id"): row for row in execution_records
                if row.get("status") == "PUBLISHED_TEMPORARY"}
    if set(run_rows) != expected_ids:
        raise RuntimeError("completed worker records do not cover all fixed seeds")
    pre_audit_hashes = {}
    normalized_economics = {}
    classpath = (run_root / "validator-classpath.txt").read_text(encoding="utf-8").strip()
    capsule_archive_root = Path(reference["archive_root"])
    for slot_id, expected_slot in expected_inventory.items():
        strict_row = strict_by_slot[slot_id]
        artifact = (run_root / "parallel/slots" / slot_id.replace("|", "_") / "worker-result.json").resolve()
        if Path(strict_row.get("artifact", "")).resolve() != artifact:
            raise RuntimeError(f"strict-validation result points outside its expected slot: {slot_id}")
        if Path(run_rows[slot_id].get("artifact", "")).as_posix() != str(artifact.relative_to(run_root)):
            raise RuntimeError(f"worker execution record points outside its expected slot: {slot_id}")
        artifact_expectations = {**expected_slot, "mode": "FULL"}
        for field, expected_value in artifact_expectations.items():
            if strict_row.get(field) != expected_value:
                raise RuntimeError(f"strict-validated artifact {field} mismatch for {slot_id}")
        raw_before_normalization = digest_file(artifact)
        baseline_entry = reference.get("baseline_by_seed", {}).get(str(expected_slot["seed"]))
        if not isinstance(baseline_entry, dict):
            raise RuntimeError(f"reference capsule lacks seed {expected_slot['seed']}")
        baseline_row = baseline_entry["parallel"]
        baseline_rel = Path(baseline_row["relative_path"])
        baseline_artifact = capsule_archive_root / baseline_rel.relative_to("mac-full-development-20260912")
        baseline_before = digest_file(baseline_artifact)
        if baseline_before != baseline_row.get("byte_sha256"):
            raise RuntimeError(f"frozen reference artifact changed before comparison: {slot_id}")
        binding_path = stage_dir / "reference-bindings" / f"{expected_slot['seed']}.json"
        write_json(binding_path, baseline_row["provenance"])
        normalize_command = [args.java, "-Xmx4g", "-XX:ActiveProcessorCount=1", "-cp", classpath,
                             "com.tradinganalytics.research.v5.StrictArtifactValidator",
                             "--compare-normalized", str(artifact), str(binding_path),
                             baseline_row["portable_economic_sha256"]]
        normalized = subprocess.run(normalize_command, cwd=run_root, capture_output=True, text=True)
        baseline_after = digest_file(baseline_artifact)
        if normalized.returncode != 0:
            raise RuntimeError(f"economic normalization comparison failed for {slot_id}: {normalized.stderr.strip()}")
        if baseline_after != baseline_before:
            raise RuntimeError(f"normalization modified frozen reference bytes for {slot_id}")
        try:
            normalized_record = json.loads(normalized.stdout)
        except json.JSONDecodeError as error:
            raise RuntimeError(f"economic normalization returned invalid JSON for {slot_id}") from error
        if normalized_record.get("status") != "PASS" or normalized_record.get("normalized_paths") != NORMALIZED_PROVENANCE_PATHS:
            raise RuntimeError(f"economic normalization did not pass the exact three-field contract for {slot_id}")
        if digest_file(artifact) != raw_before_normalization:
            raise RuntimeError(f"in-memory provenance normalization changed raw bytes for {slot_id}")
        if normalized_record.get("candidate_portable_economic_sha256_before") != strict_row.get("portable_economic_sha256"):
            raise RuntimeError(f"normalizer input hash differs from strict validation for {slot_id}")
        if normalized_record.get("candidate_normalized_economic_sha256") != baseline_row["portable_economic_sha256"]:
            raise RuntimeError(f"normalized economic digest differs from frozen reference for {slot_id}")
        normalized_economics[slot_id] = normalized_record
        pre_audit_hashes[slot_id] = raw_before_normalization

    auditor = args.audit_script.resolve()
    candidate_paths = [str((run_root / "parallel/slots" / slot_id.replace("|", "_") / "worker-result.json").resolve())
                       for slot_id in sorted(expected_ids)]
    audit_command = [sys.executable, str(auditor), *candidate_paths,
                     "--out", str(stage_dir / "independent-audit.json")]
    with (stage_dir / "independent-audit.stdout.log").open("w", encoding="utf-8") as stdout:
        audited = subprocess.run(audit_command, cwd=stage_dir, stdout=stdout,
                                 stderr=subprocess.STDOUT, text=True)
    if audited.returncode != 0:
        raise RuntimeError("frozen independent portfolio replay failed")
    final_runtime_binding = harness.runtime_profile_bindings(
        frozen_runtime_path, binding["manifest_sha256"], runtime_binding["content_sha256"])
    if final_runtime_binding["byte_sha256"] != runtime_binding["byte_sha256"]:
        raise RuntimeError("frozen runtime profile changed before independent-audit completion")
    for name, record in binding["tooling_bindings"].items():
        path = Path(record["path"])
        if not path.is_file() or path.stat().st_size != record["bytes"] or digest_file(path) != record["sha256"]:
            raise RuntimeError(f"frozen campaign tool changed during stage: {name}")
    independent = read_json(stage_dir / "independent-audit.json")
    if independent.get("status") != "PASS" or independent.get("artifacts_audited") != 8:
        raise RuntimeError("frozen independent auditor did not pass all eight artifacts")
    audit_rows = {row.get("slot_id"): row for row in independent.get("artifacts", [])}
    if set(audit_rows) != expected_ids:
        raise RuntimeError("frozen auditor result does not bind every expected slot exactly once")
    preservation = {}
    reference_by_seed = reference.get("baseline_by_seed", {})
    for slot_id, expected_slot in expected_inventory.items():
        row = audit_rows[slot_id]
        for field in ("seed", "scenario", "effect_size", "replication"):
            if row.get(field) != expected_slot[field]:
                raise RuntimeError(f"independent audit {field} mismatch for {slot_id}")
        artifact = (run_root / "parallel/slots" / slot_id.replace("|", "_") / "worker-result.json").resolve()
        after_audit_hash = digest_file(artifact)
        if after_audit_hash != pre_audit_hashes[slot_id] or after_audit_hash != row.get("byte_sha256"):
            raise RuntimeError(f"frozen auditor or comparison path modified the raw artifact: {slot_id}")
        preservation[slot_id] = {"before_audit_sha256": pre_audit_hashes[slot_id],
                                 "after_audit_sha256": after_audit_hash, "preserved": True}
        baseline = reference_by_seed.get(str(expected_slot["seed"]))
        if not isinstance(baseline, dict) or row.get("comparison_sha256") != baseline["parallel"]["comparison_sha256"]:
            raise RuntimeError(f"exact metrics/three-curve digest differs from reference seed {expected_slot['seed']}")
        if row.get("portable_economic_sha256") != strict_by_slot[slot_id].get("portable_economic_sha256"):
            raise RuntimeError(f"independent audit portable digest differs from strict validation for {slot_id}")
    write_json(stage_dir / "raw-artifact-preservation.json", preservation)
    result = {
        "schema": "mac-throughput-stage-result/1", "status": "PASS",
        "scope": "ENGINEERING_ONLY_NOT_QUALIFICATION", "stage_name": args.stage_name,
        "workers": workers, "heap_mib": heap_mib, "active_processors": active_processors,
        "gc_collector": gc_collector, "runtime_profile_sha256": runtime_binding["content_sha256"],
        "complete_eight_seed_batch": True, "strict_validator": "PASS",
        "independent_frozen_audit": "PASS", "raw_artifacts_preserved": True,
        "per_seed_metrics_and_three_curves_match_reference": True,
        "per_seed_portable_economic_hashes_match_reference": True,
        "worker_batch_wall_seconds": batch["worker_batch_wall_seconds"],
        "worker_batch_utc_elapsed_seconds": batch["worker_batch_utc_elapsed_seconds"],
        "clock_elapsed_delta_seconds": batch["clock_elapsed_delta_seconds"],
        "timing_integrity": batch["timing_integrity"],
        "gc_summary": "run/parallel/gc-summary.json",
        "resource_observations": "run/parallel/resource-measurement.json",
        "host_telemetry": "run/parallel/host-telemetry.jsonl",
        "comparison_eligible": batch["timing_integrity"] == "PASS",
        "normalization_policy": NORMALIZED_PROVENANCE_PATHS,
        "normalization_applied_to_raw_artifacts": False,
        "runtime_profile_path": runtime_binding["path"],
        "runtime_profile_sha256": runtime_binding["content_sha256"],
        "runtime_profile_byte_sha256": runtime_binding["byte_sha256"],
        "runtime_profile_id": runtime.get("profile_id"),
        "tooling_bindings_sha256": binding["tooling_bindings_sha256"],
        "tooling_bindings": binding["tooling_bindings"],
        "normalized_economic_comparisons": normalized_economics,
        "stage_elapsed_seconds": elapsed,
        "package_bindings": "package-bindings.json",
        "reference_capsule_sha256": supplied_hash,
        "independent_audit": "independent-audit.json",
    }
    write_json(stage_dir / "stage-result.json", result)
    control_after = {**declaration, "status": "PASS", "completed_at_utc": utc_now(),
                     "completed_at_epoch_ns": time.time_ns(), "runner_exit_code": return_code,
                     "runner_elapsed_seconds": elapsed}
    write_json(stage_dir / "stage-control.json", control_after)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-reference", action="store_true")
    mode.add_argument("--run-stage", action="store_true")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--search-root", type=Path, default=ROOT)
    parser.add_argument("--package", type=Path, default=ROOT / "candidate-package")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--reference-audit", type=Path, default=DEFAULT_REFERENCE_AUDIT)
    parser.add_argument("--archive-root", type=Path, default=DEFAULT_ARCHIVE_ROOT)
    parser.add_argument("--restoration-receipt", type=Path, default=DEFAULT_RESTORATION)
    parser.add_argument("--archive-inventory", type=Path, default=DEFAULT_INVENTORY)
    parser.add_argument("--reference-capsule", type=Path)
    parser.add_argument("--runner", type=Path, default=ROOT / "harness/run_mac_full_development.py")
    parser.add_argument("--audit-script", type=Path, default=DEFAULT_ARCHIVE_ROOT / "audit/audit-full.py")
    parser.add_argument("--stage-name")
    parser.add_argument("--runtime-profile", type=Path,
                        help="self-hashed local runtime profile consumed by the worker harness")
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--max-batch-seconds", type=float)
    args = parser.parse_args()
    if args.manifest is None:
        args.manifest = args.package / "manifest.json"
    if args.reference_capsule is None:
        args.reference_capsule = args.search_root / "reference-capsule.json"
    harness = load_harness(args.runner.resolve())
    if args.prepare_reference:
        result = prepare_reference(args, harness)
    else:
        if not args.stage_name:
            raise RuntimeError("--run-stage requires --stage-name")
        if args.max_batch_seconds is not None and args.max_batch_seconds <= 0:
            raise RuntimeError("--max-batch-seconds must be positive")
        try:
            result = validate_stage(args, harness)
        except StageInterrupted:
            raise
        except Exception as error:
            persist_stage_error(args, error)
            raise
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except StageInterrupted as error:
        print(f"THROUGHPUT STAGE INTERRUPTED: {error}", file=sys.stderr)
        raise SystemExit(128 + error.signum)
    except Exception as error:
        print(f"THROUGHPUT STAGE FAILED: {error}", file=sys.stderr)
        raise
