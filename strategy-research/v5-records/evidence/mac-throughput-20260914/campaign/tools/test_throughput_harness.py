import importlib.util
import base64
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


runner = load("throughput_runner_test", ROOT / "harness/run_mac_full_development.py")
controller = load("throughput_controller_test", ROOT / "run-throughput-stage.py")
packager = load("throughput_evidence_package_test", ROOT / "package-throughput-evidence.py")


class ThroughputHarnessTests(unittest.TestCase):
    def test_controller_rejects_runner_or_batch_settings_that_drift_from_profile(self):
        profile = {"profile_id": "search-4w-1536", "outer_workers": 4, "xmx_mib": 1536,
                   "active_processor_count": 1, "gc_collector": "G1"}
        manifest_sha = "a" * 64
        binding = {"path": "/tmp/frozen-runtime.json", "content_sha256": "b" * 64,
                   "byte_sha256": "c" * 64}
        runner_binding = {**binding, "profile_id": profile["profile_id"],
                          "executor_manifest_sha256": manifest_sha}
        declaration = {"runtime_profile": runner_binding, "max_parallel_workers": 4,
                       "launch_heap_mib": 1536, "active_processors": 1, "jvm_gc": "G1"}
        batch = {"runtime_profile": runner_binding, "workers": 4, "heap_mib": 1536,
                 "active_processors": 1, "jvm_gc": "G1"}
        controller.verify_runtime_execution(binding, profile, manifest_sha, declaration, batch)
        wrong = dict(batch, heap_mib=1280)
        with self.assertRaisesRegex(RuntimeError, "differ from the controller-frozen"):
            controller.verify_runtime_execution(binding, profile, manifest_sha, declaration, wrong)

    def test_local_runtime_profile_binds_worker_heap_cpu_gc_and_package(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "runtime.json"
            value = {
                "schema": "mac-local-engineering-runtime/1", "version": 1,
                "profile_id": "test-4w-1536", "scope": "LOCAL_ENGINEERING_ONLY",
                "selection_status": "CANDIDATE_NOT_SELECTED",
                "executor_manifest_sha256": "a" * 64, "platform": "macOS",
                "host_identity_sha256": "b" * 64, "outer_workers": 4,
                "xmx_mib": 1536, "active_processor_count": 1, "gc_collector": "G1",
                "effective_concurrency_cap": 8, "evidence_references": [],
                "created_at_utc": "2026-09-14T00:00:00Z",
            }
            value["content_sha256"] = runner.canonical_hash(value)
            path.write_text(json.dumps(value), encoding="utf-8")
            bound = runner.runtime_profile_bindings(path, "a" * 64)
            self.assertEqual(bound["profile"]["outer_workers"], 4)
            self.assertEqual(bound["profile"]["xmx_mib"], 1536)
            self.assertEqual(bound["profile"]["active_processor_count"], 1)
            self.assertEqual(bound["profile"]["gc_collector"], "G1")
            self.assertEqual(bound["content_sha256"], value["content_sha256"])
            with self.assertRaisesRegex(RuntimeError, "not bound"):
                runner.runtime_profile_bindings(path, "c" * 64)
            with self.assertRaisesRegex(RuntimeError, "controller's frozen binding"):
                runner.runtime_profile_bindings(path, "a" * 64, "0" * 64)

            value["xmx_mib"] = 2048
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "own-hash"):
                runner.runtime_profile_bindings(path, "a" * 64)

    def test_process_tree_uses_one_snapshot_for_one_or_eight_workers(self):
        rows = {
            100: (1, 10_000, 1.0),
            101: (100, 2_000, 2.0),
            200: (1, 20_000, 3.0),
            201: (200, 4_000, 4.0),
        }
        cases = (
            ((100,), {100: 12_000}, {100: 3.0}),
            ((100, 200, 300, 301, 302, 303, 304, 305),
             {100: 12_000, 200: 24_000}, {100: 3.0, 200: 7.0}),
        )
        for pids, expected, expected_cpu in cases:
            with self.subTest(worker_count=len(pids)):
                rss, cpu = runner.process_tree_metrics(pids, rows)
                self.assertEqual({pid: value for pid, value in rss.items() if value}, expected)
                for pid, value in expected_cpu.items():
                    self.assertEqual(cpu[pid], value)

    def test_sampler_invokes_ps_once_for_any_worker_count_and_keeps_disk_sampling(self):
        slots = [{"slot_id": f"slot-{i}"} for i in range(8)]
        for count in (1, 8):
            with self.subTest(worker_count=count), tempfile.TemporaryDirectory() as temp:
                run_root = Path(temp)
                state = runner.WaveResourceState(run_root, "parallel", {"workers": count}, slots)
                state.active.update(range(100, 100 + count))
                with (patch.object(runner, "read_process_table", return_value={}) as ps,
                      patch.object(runner, "directory_bytes", return_value=12),
                      patch.object(runner.shutil, "disk_usage", return_value=type("Usage", (), {"free": 100})()),
                      patch.object(runner, "host_telemetry", return_value={}),
                      patch.object(runner, "append_jsonl")):
                    state._sample_once()
                self.assertEqual(ps.call_count, 1)
                self.assertEqual(state.cached_disk, 12)
                self.assertEqual(state.cached_free_disk, 100)

    def test_gc_parser_counts_full_and_young_pause_time(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "gc.log"
            path.write_text(
                "[0.100s][info][gc          ] GC(0) Pause Young (Normal) 100M->20M 12.5ms\n"
                "[0.200s][info][gc       ] GC(1) Pause Full (G1 Compaction Pause) 120M->10M 250.0ms\n"
                "[2026-09-13T18:20:44.985+0300][0.141s][info][gc          ] "
                "GC(2) Pause Young (Normal) (G1 Evacuation Pause) 13M->2M(258M) 2.734ms\n"
                "[0.300s][info][gc] GC(2) Pause Young (Normal) 100M->20M 1.5ms\n",
                encoding="utf-8",
            )
            result = runner.parse_gc_log(path)
        self.assertEqual(result["pause_count"], 4)
        self.assertEqual(result["full_gc_count"], 1)
        self.assertAlmostEqual(result["pause_seconds"], 0.266734)
        self.assertAlmostEqual(result["full_gc_pause_seconds"], 0.25)

    def test_process_group_cancellation_stops_child_runner(self):
        process = subprocess.Popen(
            [sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True
        )
        try:
            controller.terminate_child_group(process)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                controller.terminate_child_group(process)

    def test_failure_checkpoint_is_durable_and_does_not_promote_stage(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stage = root / "stages/test-stage"
            stage.mkdir(parents=True)
            controller.write_json(stage / "stage-control.json", {"status": "RUNNING"})
            args = type("Args", (), {"stage_name": "test-stage", "search_root": root})()
            controller.persist_stage_error(args, RuntimeError("validation failed"))
            self.assertEqual(controller.read_json(stage / "stage-control.json")["status"], "FAILED")
            result = controller.read_json(stage / "stage-result.json")
            self.assertEqual(result["status"], "FAILED")
            self.assertFalse(result["comparison_eligible"])

    def test_frozen_support_data_uses_production_own_hash_rule(self):
        plan = json.loads((ROOT / "candidate-package/plan.json").read_text())
        receipts = plan["supporting_dependency_receipts"]
        self.assertEqual(len(receipts), 2)
        verified = [controller.verify_supporting_dependency_receipt(receipt) for receipt in receipts]
        self.assertEqual([row["relative_path"] for row in verified],
                         [row["relative_path"] for row in receipts])

        wrong_hash = dict(receipts[0], content_sha256="0" * 64)
        with self.assertRaisesRegex(RuntimeError, "content binding failed"):
            controller.verify_supporting_dependency_receipt(wrong_hash)

        changed = dict(receipts[0])
        payload = base64.b64decode(changed["data_base64"])
        document = json.loads(payload)
        document["max_concurrent_slots"] += 1
        tampered = json.dumps(document, indent=2, ensure_ascii=False).encode()
        changed.update({
            "data_base64": base64.b64encode(tampered).decode(),
            "bytes": len(tampered), "byte_sha256": hashlib.sha256(tampered).hexdigest(),
        })
        with self.assertRaisesRegex(RuntimeError, "content binding failed"):
            controller.verify_supporting_dependency_receipt(changed)

    def test_evidence_packager_requires_matching_terminal_stage_receipts(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp) / "stage-test"
            stage.mkdir()
            control = {
                "schema": "mac-throughput-stage-control/1",
                "stage_name": stage.name,
                "status": "PASS",
                "runner_exit_code": 0,
            }
            result = {
                "schema": "mac-throughput-stage-result/1",
                "stage_name": stage.name,
                "status": "PASS",
            }
            (stage / "stage-control.json").write_text(json.dumps(control))
            (stage / "stage-result.json").write_text(json.dumps(result))
            self.assertEqual(packager.read_terminal_stage(stage)[1]["status"], "PASS")

            result["status"] = "RUNNING"
            (stage / "stage-result.json").write_text(json.dumps(result))
            with self.assertRaisesRegex(RuntimeError, "recognized terminal"):
                packager.read_terminal_stage(stage)

            result["status"] = "CENSORED"
            (stage / "stage-result.json").write_text(json.dumps(result))
            with self.assertRaisesRegex(RuntimeError, "status mismatch"):
                packager.read_terminal_stage(stage)

    def test_censored_timing_is_only_a_lower_bound(self):
        result = {"status": "CENSORED", "timing_integrity": "PASS"}
        batch = {
            "worker_batch_wall_seconds": 2520.0,
            "worker_completion_count": 6,
            "planned_worker_count": 8,
            "timing_integrity": "PASS",
        }
        self.assertEqual(
            packager.stage_timing_fields(result, batch),
            {"batch_seconds": None, "elapsed_lower_bound_seconds": 2520.0},
        )

    def test_complete_validated_timing_alone_populates_batch_seconds(self):
        result = {
            "status": "PASS",
            "strict_validator": "PASS",
            "independent_frozen_audit": "PASS",
            "complete_eight_seed_batch": True,
            "comparison_eligible": True,
            "timing_integrity": "PASS",
        }
        batch = {
            "worker_batch_wall_seconds": 2070.14,
            "worker_completion_count": 8,
            "planned_worker_count": 8,
            "timing_integrity": "PASS",
        }
        self.assertEqual(
            packager.stage_timing_fields(result, batch),
            {"batch_seconds": 2070.14, "elapsed_lower_bound_seconds": None},
        )
        with self.assertRaisesRegex(RuntimeError, "validated eight-job timing"):
            packager.stage_timing_fields(
                {**result, "independent_frozen_audit": "PENDING"}, batch
            )

    def test_evidence_allowlist_carries_raw_gc_and_real_package_binding_receipts(self):
        self.assertIn("run/parallel/gc-summary.json", packager.STAGE_FILES)
        self.assertIn("independent-real-package-binding.json", packager.STATIC_FILES)
        self.assertIn("harness-tests.json", packager.STATIC_FILES)


if __name__ == "__main__":
    unittest.main()
