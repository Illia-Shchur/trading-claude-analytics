#!/usr/bin/env python3
"""Run one isolated FULL development worker with JFR; never a timing stage."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path


def load_harness(path: Path):
    spec = importlib.util.spec_from_file_location("throughput_harness", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load frozen throughput harness: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=920_000_000)
    parser.add_argument("--heap-mib", type=int, default=1536)
    parser.add_argument("--jfr-settings", default="profile",
                        help="JFR settings name or path to a JFC file")
    args = parser.parse_args()

    repo, package, run_root = args.repo.resolve(), args.package.resolve(), args.run.resolve()
    java, manifest = args.java.resolve(), args.manifest.resolve()
    if run_root.exists() and any(run_root.iterdir()):
        raise RuntimeError(f"profile output directory must be fresh and empty: {run_root}")
    run_root.mkdir(parents=True, exist_ok=True)

    h = load_harness(args.harness.resolve())
    bindings_args = argparse.Namespace(
        manifest=manifest, expected_jar_sha256=None, expected_source_sha256=None,
        expected_plan_sha256=None, expected_profile_sha256=None,
    )
    expected = h.resolve_bindings(bindings_args, repo)
    plan, profile, slots = h.validate_declaration(repo, package, expected)
    matches = [slot for slot in slots if slot["seed"] == args.seed]
    if len(matches) != 1:
        raise RuntimeError(f"seed must identify one frozen FULL development slot: {args.seed}")
    slot = matches[0]

    baseline = h.read_json(repo / "strategy-research/definitions/fk-deleveraging-absorption/v002.json")
    controls = h.read_json(repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json")
    experiment = h.read_json(repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json")
    slot_dir = run_root / "parallel" / "slots" / slot["slot_id"].replace("|", "_")
    slot_dir.mkdir(parents=True, exist_ok=False)
    worker_root, _ = h.prepare_worker_root(slot_dir, plan)
    payload_path = slot_dir / "payload.json"
    output_path = slot_dir / "worker-result.json"
    profile_path = run_root / "prechange-worker.jfr"
    gc_path = slot_dir / "gc.log"
    tmp_path = slot_dir / "tmp"
    tmp_path.mkdir()
    payload = h.payload_for(slot, plan, profile, baseline, controls, experiment,
                            str(uuid.uuid4()), expected)
    h.write_json(payload_path, payload)

    command = [
        str(java), "-XX:+ExitOnOutOfMemoryError", "-XX:+UseG1GC",
        f"-Xmx{args.heap_mib}m", "-XX:ActiveProcessorCount=1",
        f"-XX:StartFlightRecording=filename={profile_path},settings={args.jfr_settings},maxsize=1g,dumponexit=true",
        f"-Xlog:gc*:file={gc_path}:time,uptime,level,tags:filecount=0",
        f"-Djava.io.tmpdir={tmp_path}", "-jar", str(package / "executor.jar"),
        "strategy-research-v5", "operating-characteristics-corrected-parallel-worker", "--internal",
        "--payload", str(payload_path), "--out", str(output_path),
    ]
    environment = dict(os.environ)
    environment["TRADING_ANALYTICS_ROOT"] = str(worker_root)
    log_path = slot_dir / "worker.stdout.log"
    started_wall = time.time_ns()
    started_mono = time.monotonic_ns()
    with log_path.open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, cwd=worker_root, env=environment,
                                   stdout=log, stderr=subprocess.STDOUT, check=False)
    finished_mono = time.monotonic_ns()
    finished_wall = time.time_ns()

    result = {
        "schema": "calculation-performance-prechange-jfr-profile/1",
        "scope": "DIAGNOSTIC_ONLY_NOT_TIMING_EVIDENCE_NOT_QUALIFICATION",
        "status": "PASS" if completed.returncode == 0 and output_path.is_file() and profile_path.is_file() else "FAIL",
        "seed": args.seed, "slot_id": slot["slot_id"], "heap_mib": args.heap_mib,
        "active_processors": 1, "gc": "G1", "java": str(java),
        "java_version": h.command_snapshot([str(java), "-version"]),
        "started_at_epoch_ns": started_wall, "finished_at_epoch_ns": finished_wall,
        "elapsed_monotonic_seconds": (finished_mono - started_mono) / 1_000_000_000,
        "exit_code": completed.returncode, "command": command,
        "executor_jar_sha256": h.sha256_file(package / "executor.jar"),
        "manifest_sha256": h.sha256_file(manifest),
        "source_sha256": expected.source_sha256, "plan_sha256": expected.plan_sha256,
        "profile_sha256": expected.profile_sha256,
        "jfr_settings": args.jfr_settings,
        "jfr_settings_sha256": h.sha256_file(Path(args.jfr_settings)) if Path(args.jfr_settings).is_file() else None,
        "artifacts": {
            "jfr": {"path": str(profile_path), "bytes": profile_path.stat().st_size if profile_path.exists() else 0},
            "worker_result": {"path": str(output_path), "bytes": output_path.stat().st_size if output_path.exists() else 0},
            "stdout": {"path": str(log_path), "bytes": log_path.stat().st_size},
            "gc_log": {"path": str(gc_path), "bytes": gc_path.stat().st_size if gc_path.exists() else 0},
        },
    }
    result_path = run_root / "profile-result.json"
    h.write_json(result_path, result)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
