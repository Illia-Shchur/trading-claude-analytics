#!/usr/bin/env python3
"""Run the corrected FULL development worker wave on an ineligible Mac.

This is an engineering-only harness.  It records the production FULL
preflight blocker, then invokes the packaged corrected worker CLI directly for
the eight disjoint development seeds.  It never marks a profile qualified,
opens held-out seeds, or invokes a qualification command.

The default package bindings are the original frozen declaration.  A new
package may supply a complete --manifest or all four --expected-* values; the
plan and seed checks below still require the same held-out and development
inventory.  --wave and --single-slot are diagnostic scope controls only.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

GIB = 1024 ** 3
DEFAULT_JAR_SHA256 = "3ff5aa4612053d2483a83b93f12055bddcd45d25edc75c8ca61d1635b89c87cc"
DEFAULT_SOURCE_SHA256 = "c99a335303fa4649c83e445d7480cc2cfcbe879f51c22fc1558627680e55496a"
DEFAULT_PLAN_CONTENT_SHA256 = "e0b2b93c2bfbde064e57408cc5b0f99a8ebfd6584794611b8567a8f5573624b0"
DEFAULT_PROFILE_CONTENT_SHA256 = "e7c41026cdd9e07afd67e3034dbe5490b908207a11beb64db80308971c11f763"
WORKER_RSS_LIMIT = 3 * GIB  # Frozen payload identity; sampled RSS is observational.
WORKER_HEAP = 2 * GIB      # Frozen payload identity; launch Xmx is stage-configurable.
AGGREGATE_WORKER_RSS_LIMIT = 8 * GIB  # Recorded only; never an automatic stop.
DISK_LIMIT = 64 * GIB
MIN_FREE_DISK_BYTES = 10 * GIB
SLOT_WALL_SECONDS = 90 * 60
WAVE_WALL_SECONDS = 6 * 60 * 60
POLL_INTERVAL_SECONDS = 2.0
PROCESS_SAMPLE_INTERVAL_SECONDS = 1.0
DISK_SAMPLE_INTERVAL_SECONDS = 30.0
HOST_SAMPLE_INTERVAL_SECONDS = 10.0
GC_PAUSE_RE = re.compile(
    r"\[info\]\[gc(?:,[^\]]+)?\s*\].*?Pause\s+([A-Za-z]+).*?([0-9]+(?:\.[0-9]+)?)ms"
)
EXPECTED_CELLS = {
    ("NO_EDGE", 0.0),
    ("PLANTED_EDGE", 0.0),
    ("PLANTED_EDGE", 0.02),
    ("PLANTED_EDGE", 0.04),
}
EXPECTED_HELDOUT_SEEDS = {
    ("NO_EDGE", 0.0): tuple(range(110_000_000, 110_000_075)),
    ("PLANTED_EDGE", 0.0): tuple(range(110_100_000, 110_100_075)),
    ("PLANTED_EDGE", 0.02): tuple(range(110_200_000, 110_200_075)),
    ("PLANTED_EDGE", 0.04): tuple(range(110_300_000, 110_300_075)),
}
EXPECTED_DEVELOPMENT_SEEDS = {
    ("NO_EDGE", 0.0): (920_000_000, 920_000_001),
    ("PLANTED_EDGE", 0.0): (920_100_000, 920_100_001),
    ("PLANTED_EDGE", 0.02): (920_200_000, 920_200_001),
    ("PLANTED_EDGE", 0.04): (920_300_000, 920_300_001),
}
EXPECTED_PREFIX_SEEDS = {
    ("NO_EDGE", 0.0): (921_000_000, 921_000_001),
    ("PLANTED_EDGE", 0.0): (921_100_000, 921_100_001),
    ("PLANTED_EDGE", 0.02): (921_200_000, 921_200_001),
    ("PLANTED_EDGE", 0.04): (921_300_000, 921_300_001),
}


@dataclass(frozen=True)
class ExpectedBindings:
    """The four immutable package/plan/profile bindings used by a run."""

    jar_sha256: str
    source_sha256: str
    plan_sha256: str
    profile_sha256: str

    def as_json(self) -> dict[str, str]:
        return {
            "executor_jar_sha256": self.jar_sha256,
            "executor_source_sha256": self.source_sha256,
            "plan_content_sha256": self.plan_sha256,
            "profile_content_sha256": self.profile_sha256,
        }


def canonical_hash(value: object) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path: Path) -> str:
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
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except OSError:
        pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def append_jsonl(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(descriptor, payload)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def command_snapshot(command: list[str]) -> dict[str, object]:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=4)
        return {"exit_code": completed.returncode, "stdout": completed.stdout.strip(),
                "stderr": completed.stderr.strip()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"unavailable": str(error)}


def host_telemetry() -> dict[str, object]:
    return {
        "sampled_at_utc": utc_now(), "sampled_at_epoch_ns": time.time_ns(),
        "memory_pressure": command_snapshot(["memory_pressure", "-Q"]),
        "swap": command_snapshot(["sysctl", "vm.swapusage"]),
        "vm_stat": command_snapshot(["vm_stat"]),
    }


def parse_gc_log(path: Path) -> dict[str, object]:
    total_ms = 0.0
    full_ms = 0.0
    pauses = 0
    full_pauses = 0
    if path.is_file():
        with path.open(encoding="utf-8", errors="replace") as stream:
            for line in stream:
                match = GC_PAUSE_RE.search(line)
                if not match:
                    continue
                pause_ms = float(match.group(2))
                pauses += 1
                total_ms += pause_ms
                if match.group(1).lower() == "full":
                    full_pauses += 1
                    full_ms += pause_ms
    return {"gc_log": str(path), "gc_log_bytes": path.stat().st_size if path.exists() else 0,
            "pause_count": pauses, "pause_seconds": total_ms / 1000.0,
            "full_gc_count": full_pauses, "full_gc_pause_seconds": full_ms / 1000.0}


class DiskMonitorUnavailable(RuntimeError):
    pass


def read_process_table() -> dict[int, tuple[int, int, float]]:
    """One macOS process-table read; values are (parent pid, RSS bytes, CPU percent)."""
    output = subprocess.check_output(
        ["ps", "-axo", "pid=,ppid=,rss=,%cpu="], text=True, stderr=subprocess.DEVNULL, timeout=3
    )
    rows: dict[int, tuple[int, int, float]] = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 4:
            continue
        try:
            rows[int(fields[0])] = (int(fields[1]), int(fields[2]) * 1024, float(fields[3]))
        except ValueError:
            continue
    return rows


def process_tree_metrics(pids: tuple[int, ...], rows: dict[int, tuple[int, int, float]]) -> tuple[dict[int, int], dict[int, float]]:
    active = set(pids)
    rss_by_pid = {pid: 0 for pid in pids}
    cpu_by_pid = {pid: 0.0 for pid in pids}
    for child, (_, rss, cpu) in rows.items():
        owner = child
        seen: set[int] = set()
        while owner not in active and owner in rows and owner not in seen:
            seen.add(owner)
            owner = rows[owner][0]
        if owner in active:
            rss_by_pid[owner] += rss
            cpu_by_pid[owner] += cpu
    return rss_by_pid, cpu_by_pid


def directory_bytes(root: Path) -> int:
    total = 0
    try:
        for path in root.rglob("*"):
            if path.is_file():
                try:
                    total += path.stat().st_size
                except FileNotFoundError:
                    continue
    except OSError:
        return -1
    return total


def terminate(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def require_sha256(value: str, label: str) -> str:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise RuntimeError(f"{label} must be a lowercase SHA-256")
    return value


def manifest_bindings(manifest: dict, path: Path) -> ExpectedBindings:
    """Read a complete four-binding manifest; partial manifests fail closed."""
    candidates = [manifest]
    nested = manifest.get("bindings")
    if isinstance(nested, dict):
        candidates.insert(0, nested)
    aliases = {
        "jar_sha256": ("executor_jar_sha256", "jar_sha256"),
        "source_sha256": ("executor_source_sha256", "source_sha256"),
        "plan_sha256": ("plan_content_sha256", "plan_sha256"),
        "profile_sha256": ("profile_content_sha256", "profile_sha256"),
    }
    for candidate in candidates:
        values: dict[str, str] = {}
        for name, keys in aliases.items():
            for key in keys:
                value = candidate.get(key)
                if isinstance(value, str) and value:
                    values[name] = value
                    break
        if len(values) == len(aliases):
            return ExpectedBindings(
                require_sha256(values["jar_sha256"], "manifest executor JAR hash"),
                require_sha256(values["source_sha256"], "manifest executor source hash"),
                require_sha256(values["plan_sha256"], "manifest plan hash"),
                require_sha256(values["profile_sha256"], "manifest profile hash"),
            )
    raise RuntimeError(f"package manifest must contain all four immutable bindings: {path}")


def resolve_bindings(args: argparse.Namespace, repo: Path) -> ExpectedBindings:
    values = ExpectedBindings(
        DEFAULT_JAR_SHA256, DEFAULT_SOURCE_SHA256,
        DEFAULT_PLAN_CONTENT_SHA256, DEFAULT_PROFILE_CONTENT_SHA256,
    )
    if args.manifest is not None:
        manifest_path = args.manifest.resolve() if args.manifest.is_absolute() else (repo / args.manifest).resolve()
        values = manifest_bindings(read_json(manifest_path), manifest_path)
    explicit = {
        "jar_sha256": args.expected_jar_sha256,
        "source_sha256": args.expected_source_sha256,
        "plan_sha256": args.expected_plan_sha256,
        "profile_sha256": args.expected_profile_sha256,
    }
    if any(value is not None for value in explicit.values()):
        if any(value is None for value in explicit.values()):
            raise RuntimeError("explicit package bindings require all four --expected-* values")
        values = ExpectedBindings(
            require_sha256(explicit["jar_sha256"], "expected executor JAR hash"),
            require_sha256(explicit["source_sha256"], "expected executor source hash"),
            require_sha256(explicit["plan_sha256"], "expected plan hash"),
            require_sha256(explicit["profile_sha256"], "expected profile hash"),
        )
    return values


def validate_declaration(repo: Path, package: Path, expected: ExpectedBindings) -> tuple[dict, dict, list[dict]]:
    plan = read_json(package / "plan.json")
    profile = read_json(package / "profile.json")
    if sha256_file(package / "executor.jar") != expected.jar_sha256:
        raise RuntimeError("preserved executor JAR hash mismatch")
    if plan.get("content_sha256") != expected.plan_sha256 or canonical_hash(
            {key: value for key, value in plan.items() if key != "content_sha256"}) != expected.plan_sha256:
        raise RuntimeError("preserved development plan content hash mismatch")
    if profile.get("content_sha256") != expected.profile_sha256 or canonical_hash(
            {key: value for key, value in profile.items() if key != "content_sha256"}) != expected.profile_sha256:
        raise RuntimeError("preserved profile content hash mismatch")
    if plan.get("executor_identity_sha256") != expected.jar_sha256:
        raise RuntimeError("development plan executor JAR binding mismatch")
    if (plan.get("executor_build_input_fingerprint") != expected.source_sha256
            or plan.get("executor_source_sha256") != expected.source_sha256):
        raise RuntimeError("development plan executor source binding mismatch")
    if plan.get("execution_profile_sha256") != expected.profile_sha256:
        raise RuntimeError("development plan execution profile binding mismatch")
    if profile.get("target_qualified") is not False or profile.get("qualified_for_confirmation") is not False:
        raise RuntimeError("harness requires the recorded unqualified Mac profile")
    if profile.get("effective_workers") != 2:
        raise RuntimeError("harness requires the recorded two-worker local cap")
    if any(plan.get(field) != expected for field, expected in {
        "cell_count": 4, "replications": 75, "episodes_per_replication": 450,
        "cluster_count": 288, "paired_cluster_count": 162,
        "lifecycle_series_per_replication": 900, "horizon_minutes": 14400,
    }.items()):
        raise RuntimeError("frozen FULL statistical geometry changed")
    frozen: set[int] = set()
    frozen_keys: set[tuple[str, float]] = set()
    for cell in plan.get("cells", []):
        key = (cell.get("scenario"), float(cell.get("effect_size")))
        seeds = tuple(int(seed) for seed in cell.get("seeds", []))
        if key not in EXPECTED_HELDOUT_SEEDS or seeds != EXPECTED_HELDOUT_SEEDS[key]:
            raise RuntimeError("held-out confirmation seed inventory changed")
        if any(seed in frozen for seed in seeds):
            raise RuntimeError("held-out confirmation seeds duplicate across cells")
        frozen.update(seeds)
        frozen_keys.add(key)
    if frozen_keys != EXPECTED_CELLS or len(frozen) != 300:
        raise RuntimeError("held-out confirmation inventory is incomplete")
    cells = []
    seen: set[int] = set()
    for cell in plan.get("development_seed_cells", []):
        key = (cell.get("scenario"), float(cell.get("effect_size")))
        seeds = [int(seed) for seed in cell.get("development_seeds", [])]
        if key not in EXPECTED_CELLS or tuple(seeds) != EXPECTED_DEVELOPMENT_SEEDS[key]:
            raise RuntimeError("development seed inventory is not the frozen four cells x two workers")
        if any(seed < 920_000_000 or seed >= 921_000_000 for seed in seeds):
            raise RuntimeError("development seed escaped the 920M namespace")
        for seed in seeds:
            if seed in frozen or seed in seen:
                raise RuntimeError("development seed overlaps or duplicates frozen inventory")
            seen.add(seed)
        cells.append({"scenario": key[0], "effect_size": key[1], "seeds": seeds})
    if { (cell["scenario"], cell["effect_size"]) for cell in cells } != EXPECTED_CELLS or len(seen) != 8:
        raise RuntimeError("development inventory is incomplete")
    prefixes: set[int] = set()
    prefix_keys: set[tuple[str, float]] = set()
    for cell in plan.get("development_prefix_seed_cells", []):
        key = (cell.get("scenario"), float(cell.get("effect_size")))
        seeds = tuple(int(seed) for seed in cell.get("prefix_seeds", []))
        if key not in EXPECTED_PREFIX_SEEDS or seeds != EXPECTED_PREFIX_SEEDS[key]:
            raise RuntimeError("development PREFIX seed inventory changed")
        prefixes.update(seeds)
        prefix_keys.add(key)
    if seen & prefixes or any(seed in frozen for seed in prefixes):
        raise RuntimeError("development inventory overlaps PREFIX or held-out seeds")
    if prefix_keys != EXPECTED_CELLS or len(prefixes) != 8:
        raise RuntimeError("development PREFIX inventory is incomplete")
    slots = []
    for cell in sorted(cells, key=lambda item: (item["scenario"], item["effect_size"])):
        for replication, seed in enumerate(cell["seeds"]):
            slots.append({
                "slot_id": f"FULL|{cell['scenario']}|{cell['effect_size']:g}|{replication}|{seed}",
                "mode": "FULL", "scenario": cell["scenario"],
                "effect_size": cell["effect_size"], "replication": replication,
                "seed": seed,
            })
    return plan, profile, slots


def compile_strict_validator(repo: Path, run_root: Path, jar: Path, java: str) -> Path:
    """Compile the validator only from the exact packaged JAR under test."""
    package_tree = run_root / "packaged-executor"
    package_tree.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(jar) as archive:
        root = package_tree.resolve()
        for member in archive.infolist():
            target = (package_tree / member.filename).resolve()
            if not str(target).startswith(str(root) + os.sep):
                raise RuntimeError(f"packaged JAR member escapes extraction root: {member.filename}")
        archive.extractall(package_tree)
    classes = run_root / "validator-classes"
    classes.mkdir()
    validator_source = run_root / "StrictArtifactValidator.java"
    shutil.copy2(Path(__file__).with_name("StrictArtifactValidator.java"), validator_source)
    java_path = Path(java)
    javac = str(java_path.with_name("javac")) if java_path.is_absolute() else shutil.which("javac")
    if not javac:
        raise RuntimeError("javac is required for the production strict validator bridge")
    javac_version = subprocess.run([javac, "-version"], capture_output=True, text=True)
    if '21.' not in (javac_version.stderr + javac_version.stdout):
        raise RuntimeError("JDK 21 javac is required for the strict validator bridge")
    packaged_classes = package_tree / "BOOT-INF/classes"
    packaged_libs = package_tree / "BOOT-INF/lib/*"
    if not packaged_classes.is_dir():
        raise RuntimeError("packaged JAR has no BOOT-INF/classes")
    compile_classpath = os.pathsep.join([str(packaged_classes), str(packaged_libs)])
    compiled = subprocess.run([javac, "-cp", compile_classpath, "-d", str(classes), str(validator_source)],
                              capture_output=True, text=True, cwd=repo)
    if compiled.returncode != 0:
        raise RuntimeError(f"strict validator compilation failed: {compiled.stderr}")
    runtime_classpath = os.pathsep.join([str(classes), str(packaged_classes), str(packaged_libs)])
    (run_root / "validator-classpath.txt").write_text(runtime_classpath + "\n", encoding="utf-8")
    return Path(runtime_classpath)


def prepare_worker_root(slot_dir: Path, plan: dict) -> tuple[Path, list[dict]]:
    root = slot_dir / "repository"
    root.mkdir(parents=True, exist_ok=True)
    (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    (root / "schemas").mkdir(exist_ok=True)
    dependencies = []
    for dependency in plan["supporting_dependency_receipts"]:
        relative = Path(dependency["relative_path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise RuntimeError(f"dependency path is not repository-relative: {relative}")
        target = root / relative
        if os.path.commonpath((str(root.resolve()), str(target.resolve()))) != str(root.resolve()):
            raise RuntimeError(f"dependency path escapes worker repository: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = base64.b64decode(dependency["data_base64"])
        if hashlib.sha256(payload).hexdigest() != dependency["byte_sha256"] or len(payload) != dependency["bytes"]:
            raise RuntimeError(f"dependency receipt mismatch: {relative}")
        target.write_bytes(payload)
        dependencies.append({
            "relative_path": dependency["relative_path"],
            "byte_sha256": dependency["byte_sha256"],
            "bytes": dependency["bytes"],
            "data_base64": dependency["data_base64"],
        })
    receipts = []
    for dependency in plan["supporting_dependency_receipts"]:
        receipts.append({
            "relative_path": dependency["relative_path"],
            "byte_sha256": dependency["byte_sha256"],
            "bytes": dependency["bytes"],
            "content_sha256": dependency["content_sha256"],
        })
    return root, (dependencies, receipts)


def payload_for(slot: dict, plan: dict, profile: dict, baseline: dict, controls: dict,
                experiment: dict, run_id: str, expected: ExpectedBindings,
                attempt: int = 1) -> dict:
    slot_payload = dict(slot)
    slot_payload["plan_sha256"] = plan["content_sha256"]
    slot_payload["ordinal"] = 0
    return {
        "slot": slot_payload,
        "plan": plan,
        "baseline": baseline,
        "controls": controls,
        "experiment": experiment,
        "corrected_accounting": True,
        "run_id": run_id,
        "executor_identity_sha256": expected.jar_sha256,
        "executor_source_fingerprint": expected.source_sha256,
        "profile_sha256": profile["content_sha256"],
        "attempt": attempt,
        "episodes_per_replication": 450,
        "worker_rss_bytes": WORKER_RSS_LIMIT,
        "worker_heap_bytes": WORKER_HEAP,
        "worker_cpu": 2,
        "max_wall_millis": SLOT_WALL_SECONDS * 1000,
        "supporting_dependencies": plan["supporting_dependency_receipts"],
        "supporting_receipts": [
            {"relative_path": item["relative_path"], "byte_sha256": item["byte_sha256"],
             "bytes": item["bytes"], "content_sha256": item["content_sha256"]}
            for item in plan["supporting_dependency_receipts"]
        ],
    }


class WaveResourceState:
    def __init__(self, run_root: Path, wave: str, stage: dict, slots: list[dict]) -> None:
        self.lock = threading.Lock()
        self.telemetry_lock = threading.Lock()
        self.snapshot_lock = threading.Lock()
        self.active: set[int] = set()
        self.abort = threading.Event()
        self.censored = threading.Event()
        self.run_root = run_root
        self.wave = wave
        self.progress_path = run_root / wave / "live-progress.json"
        self.telemetry_path = run_root / wave / "host-telemetry.jsonl"
        self.started_at_utc = utc_now()
        self.started_at_epoch_ns = time.time_ns()
        self.started_monotonic_ns = time.monotonic_ns()
        self.next_disk_at = 0.0
        self.next_host_at = 0.0
        self.cached_rss: dict[int, int] = {}
        self.cached_cpu: dict[int, float] = {}
        self.cached_aggregate_rss = 0
        self.cached_disk = 0
        self.cached_free_disk = shutil.disk_usage(run_root).free
        self.monitor_error = ""
        self.sampler_stop = threading.Event()
        self.sampler_thread: threading.Thread | None = None
        self.stage = stage
        self.slot_states = {slot["slot_id"]: {"status": "PENDING"} for slot in slots}
        self.max_aggregate_rss = 0
        self.max_worker_rss = 0
        self.rss_samples = 0
        self.max_run_disk = 0
        write_json(self.progress_path, self.progress_value())

    def progress_value(self) -> dict:
        states = list(self.slot_states.values())
        return {
            "schema": "mac-throughput-live-progress/1", "wave": self.wave,
            "stage": self.stage, "started_at_utc": self.started_at_utc,
            "started_at_epoch_ns": self.started_at_epoch_ns,
            "started_monotonic_ns": self.started_monotonic_ns,
            "updated_at_utc": utc_now(), "completed_workers": sum(s["status"] in {
                "PUBLISHED_TEMPORARY", "FAILED", "CENSORED"} for s in states),
            "planned_workers": len(states), "slots": self.slot_states,
            "status": "CENSORED" if self.censored.is_set() else
                      ("ABORTED" if self.abort.is_set() else "RUNNING"),
        }

    def persist_progress(self) -> None:
        with self.lock:
            write_json(self.progress_path, self.progress_value())

    def mark_slot_running(self, slot_id: str, pid: int, record: dict) -> None:
        with self.lock:
            self.active.add(pid)
            self.slot_states[slot_id] = {"status": "RUNNING", "pid": pid,
                                         "started_at_utc": record["started_at_utc"],
                                         "started_at_epoch_ns": record["started_at_epoch_ns"],
                                         "started_monotonic_ns": record["started_monotonic_ns"]}
            write_json(self.progress_path, self.progress_value())

    def start_sampler(self) -> None:
        self._sample_once()
        if self.cached_disk < 0 or self.cached_free_disk < 0:
            raise DiskMonitorUnavailable("RESOURCE_DISK_MONITOR_UNAVAILABLE")
        self.sampler_thread = threading.Thread(target=self._sample_loop, name="mac-throughput-sampler", daemon=True)
        self.sampler_thread.start()

    def stop_sampler(self) -> None:
        self.sampler_stop.set()
        if self.sampler_thread is not None:
            self.sampler_thread.join(timeout=3)

    def _sample_loop(self) -> None:
        while not self.sampler_stop.is_set():
            self._sample_once()
            self.sampler_stop.wait(PROCESS_SAMPLE_INTERVAL_SECONDS)

    def _sample_once(self) -> None:
        now = time.monotonic()
        with self.snapshot_lock:
            with self.lock:
                pids = tuple(self.active)
            rows: dict[int, tuple[int, int, float]] | None = {}
            if pids:
                try:
                    rows = read_process_table()
                except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    rows = None
            if rows is None:
                rss_by_pid = {pid: -1 for pid in pids}
                cpu_by_pid = {pid: -1.0 for pid in pids}
                aggregate = -1
            else:
                rss_by_pid, cpu_by_pid = process_tree_metrics(pids, rows)
                aggregate = sum(rss_by_pid.values())

            if now >= self.next_disk_at:
                disk = directory_bytes(self.run_root)
                try:
                    free_disk = shutil.disk_usage(self.run_root).free
                except OSError:
                    free_disk = -1
                with self.lock:
                    self.cached_disk = disk
                    self.cached_free_disk = free_disk
                    if disk >= 0:
                        self.max_run_disk = max(self.max_run_disk, disk)
                self.next_disk_at = now + DISK_SAMPLE_INTERVAL_SECONDS

            with self.lock:
                self.cached_rss = rss_by_pid
                self.cached_cpu = cpu_by_pid
                self.cached_aggregate_rss = aggregate
                if aggregate >= 0:
                    self.max_aggregate_rss = max(self.max_aggregate_rss, aggregate)
                if pids and aggregate >= 0:
                    self.rss_samples += 1
                disk = self.cached_disk
                free_disk = self.cached_free_disk
                max_rss = self.max_aggregate_rss

            if now >= self.next_host_at:
                sample = host_telemetry()
                sample.update({"aggregate_worker_rss_bytes": aggregate,
                               "workers": {str(pid): {"rss_bytes": rss_by_pid.get(pid, 0),
                                                      "cpu_percent": cpu_by_pid.get(pid, 0.0)}
                                           for pid in pids},
                               "run_directory_bytes": disk,
                               "filesystem_free_bytes": free_disk,
                               "max_aggregate_worker_rss_bytes": max_rss})
                try:
                    append_jsonl(self.telemetry_path, sample)
                except OSError:
                    # Telemetry writes are diagnostic; disk guards above are
                    # refreshed independently and remain fail-closed.
                    pass
                self.next_host_at = now + HOST_SAMPLE_INTERVAL_SECONDS

    def unregister(self, pid: int) -> None:
        with self.lock:
            self.active.discard(pid)

    def complete_slot(self, record: dict) -> None:
        with self.lock:
            self.slot_states[record["slot_id"]] = {
                "status": record["status"], "exit_code": record["exit_code"],
                "finished_at_utc": record["finished_at_utc"],
                "finished_at_epoch_ns": record["finished_at_epoch_ns"],
                "finished_monotonic_ns": record["finished_monotonic_ns"],
                "elapsed_seconds": record["elapsed_seconds"],
                "artifact": record.get("artifact", ""),
                "error": record.get("error", ""),
            }
            write_json(self.progress_path, self.progress_value())

    def sample(self, _run_root: Path, pid: int | None = None) -> tuple[int, int, int, int, float]:
        with self.lock:
            return (self.cached_aggregate_rss, self.cached_disk, self.cached_free_disk,
                    self.cached_rss.get(pid, -1) if pid is not None else -1,
                    self.cached_cpu.get(pid, -1.0) if pid is not None else -1.0)


def run_one(slot: dict, wave: str, run_root: Path, jar: Path, plan: dict, profile: dict,
            baseline: dict, controls: dict, experiment: dict, run_id: str, java: str,
            resource_state: WaveResourceState, expected: ExpectedBindings,
            launch_heap_mib: int, active_processors: int,
            wave_started_monotonic: float, max_batch_seconds: float | None) -> dict:
    slot_dir = run_root / wave / "slots" / slot["slot_id"].replace("|", "_")
    started = time.monotonic()
    started_monotonic_ns = time.monotonic_ns()
    started_epoch_ns = time.time_ns()
    started_utc = utc_now()
    slot_dir.mkdir(parents=True, exist_ok=True)
    payload_path = slot_dir / "payload.json"
    output_path = slot_dir / "worker-result.json"
    stdout_path = slot_dir / "worker.stdout.log"
    max_rss = 0
    max_cpu_percent = 0.0
    max_disk = 0
    min_free_disk = None
    status = "FAILED"
    error = ""
    exit_code = -1
    process: subprocess.Popen[str] | None = None
    try:
        if resource_state.censored.is_set():
            status = "CENSORED"
            error = "PERFORMANCE_TIMEOUT_CENSORED"
            raise RuntimeError(error)
        if resource_state.abort.is_set():
            raise RuntimeError("WAVE_ABORTED_BEFORE_SLOT_START")
        worker_root, _ = prepare_worker_root(slot_dir, plan)
        payload = payload_for(slot, plan, profile, baseline, controls, experiment, run_id, expected)
        write_json(payload_path, payload)
        command = [
            java, "-XX:+ExitOnOutOfMemoryError", "-XX:+UseG1GC",
            f"-Xmx{launch_heap_mib}m", f"-XX:ActiveProcessorCount={active_processors}",
            f"-Xlog:gc*:file={slot_dir / 'gc.log'}:time,uptime,level,tags:filecount=0",
            f"-Djava.io.tmpdir={slot_dir / 'tmp'}", "-jar", str(jar),
            "strategy-research-v5", "operating-characteristics-corrected-parallel-worker", "--internal",
            "--payload", str(payload_path), "--out", str(output_path),
        ]
        (slot_dir / "tmp").mkdir(exist_ok=True)
        environment = dict(os.environ)
        environment["TRADING_ANALYTICS_ROOT"] = str(worker_root)
        with stdout_path.open("w", encoding="utf-8") as stdout:
            process = subprocess.Popen(
                command, cwd=worker_root, env=environment, stdout=stdout, stderr=subprocess.STDOUT,
                start_new_session=True, text=True,
            )
            resource_state.mark_slot_running(slot["slot_id"], process.pid, {
                "started_at_utc": started_utc, "started_at_epoch_ns": started_epoch_ns,
                "started_monotonic_ns": started_monotonic_ns,
            })
            if shutil.disk_usage(run_root).free < MIN_FREE_DISK_BYTES:
                resource_state.abort.set()
                terminate(process)
                error = "RESOURCE_FILESYSTEM_FREE_SPACE_BELOW_RESERVE"
            try:
                while process.poll() is None:
                    elapsed = time.monotonic() - started
                    batch_elapsed = time.monotonic() - wave_started_monotonic
                    aggregate_rss, disk, free_disk, rss, cpu_percent = resource_state.sample(
                        run_root, process.pid)
                    if rss >= 0:
                        max_rss = max(max_rss, rss)
                    if cpu_percent >= 0:
                        max_cpu_percent = max(max_cpu_percent, cpu_percent)
                    max_disk = max(max_disk, disk)
                    min_free_disk = free_disk if min_free_disk is None else min(min_free_disk, free_disk)
                    violation = ""
                    if resource_state.abort.is_set():
                        violation = "WAVE_ABORTED_DUE_TO_SIBLING_FAILURE"
                    elif resource_state.censored.is_set():
                        violation = "PERFORMANCE_TIMEOUT_CENSORED"
                    elif max_batch_seconds is not None and batch_elapsed >= max_batch_seconds:
                        resource_state.censored.set()
                        violation = "PERFORMANCE_TIMEOUT_CENSORED"
                    elif batch_elapsed >= WAVE_WALL_SECONDS:
                        violation = "RESOURCE_BATCH_WALL_DEADLINE_EXCEEDED"
                    elif elapsed > SLOT_WALL_SECONDS:
                        violation = "RESOURCE_SLOT_WALL_DEADLINE_EXCEEDED"
                    elif disk < 0:
                        violation = "RESOURCE_RUN_DISK_USAGE_UNAVAILABLE"
                    elif disk > DISK_LIMIT:
                        violation = "RESOURCE_RUN_DISK_BUDGET_EXCEEDED"
                    elif free_disk < MIN_FREE_DISK_BYTES:
                        violation = "RESOURCE_FILESYSTEM_FREE_SPACE_BELOW_RESERVE"
                    if violation:
                        if violation == "PERFORMANCE_TIMEOUT_CENSORED":
                            pass
                        else:
                            resource_state.abort.set()
                        terminate(process)
                        error = violation
                        break
                    time.sleep(POLL_INTERVAL_SECONDS)
            finally:
                if process.poll() is None:
                    terminate(process)
                resource_state.unregister(process.pid)
            exit_code = process.wait()
        if not error and exit_code == 0 and output_path.is_file():
            status = "PUBLISHED_TEMPORARY"
        elif error == "PERFORMANCE_TIMEOUT_CENSORED":
            status = "CENSORED"
        elif not error:
            error = f"WORKER_EXITED_{exit_code}"
    except Exception as exc:
        if str(exc) == "PERFORMANCE_TIMEOUT_CENSORED":
            status = "CENSORED"
        else:
            resource_state.abort.set()
        error = error or str(exc)
        if process is not None and process.poll() is None:
            terminate(process)
        if process is not None:
            resource_state.unregister(process.pid)
    finally:
        if process is not None and process.poll() is None:
            terminate(process)
            resource_state.unregister(process.pid)
    finished_monotonic_ns = time.monotonic_ns()
    finished_epoch_ns = time.time_ns()
    if status != "PUBLISHED_TEMPORARY" and status != "CENSORED":
        # A nonzero child exit is a wave-wide fatal condition.  This prevents
        # queued slots from launching after a worker has failed.
        resource_state.abort.set()
    record = {
        **slot, "wave": wave, "status": status, "exit_code": exit_code,
        "error": error, "started_at_utc": started_utc, "started_at_epoch_ns": started_epoch_ns,
        "started_monotonic_ns": started_monotonic_ns, "finished_at_utc": utc_now(),
        "finished_at_epoch_ns": finished_epoch_ns, "finished_monotonic_ns": finished_monotonic_ns,
        "elapsed_seconds": (finished_monotonic_ns - started_monotonic_ns) / 1e9,
        "launch_heap_mib": launch_heap_mib, "active_processors": active_processors,
        "max_worker_rss_bytes": max_rss, "max_run_disk_bytes": max_disk,
        "max_worker_cpu_percent": max_cpu_percent,
        "min_filesystem_free_bytes": min_free_disk,
        "rss_is_observational": True, "gc_log": str(slot_dir / "gc.log"),
        "payload": str(payload_path.relative_to(run_root)),
        "artifact": str(output_path.relative_to(run_root)) if output_path.exists() else "",
    }
    write_json(slot_dir / "execution.json", record)
    resource_state.complete_slot(record)
    return record


def strict_validate(run_root: Path, wave: str, plan_path: Path, slots: list[dict], run_id: str,
                    validator_classes: Path, java: str) -> list[dict]:
    records = []
    for slot in slots:
        slot_dir = run_root / wave / "slots" / slot["slot_id"].replace("|", "_")
        artifact = slot_dir / "worker-result.json"
        command = [java, "-cp", str(validator_classes),
                   "com.tradinganalytics.research.v5.StrictArtifactValidator",
                   str(plan_path), str(artifact), run_id]
        completed = subprocess.run(command, text=True, capture_output=True, cwd=run_root)
        if completed.returncode != 0:
            raise RuntimeError(f"strict validator failed for {slot['slot_id']}: {completed.stderr.strip()}")
        try:
            result = json.loads(completed.stdout)
        except json.JSONDecodeError as error:
            raise RuntimeError(f"strict validator returned invalid JSON for {slot['slot_id']}") from error
        if result.get("status") != "PASS":
            raise RuntimeError(f"strict validator did not pass {slot['slot_id']}")
        if result.get("slot_id") != slot["slot_id"]:
            raise RuntimeError(f"strict validator returned a different slot identity for {slot['slot_id']}")
        if Path(result.get("artifact", "")).resolve() != artifact.resolve():
            raise RuntimeError(f"strict validator returned a different artifact path for {slot['slot_id']}")
        records.append(result)
    return records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--package", type=Path,
                        default=Path(".report-run/pr13-final/final-package"))
    parser.add_argument("--manifest", type=Path,
                        help="complete frozen package manifest containing all four expected bindings")
    parser.add_argument("--expected-jar-sha256")
    parser.add_argument("--expected-source-sha256")
    parser.add_argument("--expected-plan-sha256")
    parser.add_argument("--expected-profile-sha256")
    parser.add_argument("--wave", choices=("parallel", "serial"), default="parallel",
                        help="execute one complete eight-seed FULL development batch")
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=4,
                        help="outer worker-process concurrency for this full batch")
    parser.add_argument("--heap-mib", type=int, choices=(512, 640, 768, 896, 1024, 1152, 1280), default=1024,
                        help="actual worker JVM -Xmx; payload/profile identity remains frozen")
    parser.add_argument("--active-processors", type=int, choices=(1,), default=1,
                        help="fixed CPU hint for all comparisons")
    parser.add_argument("--max-batch-seconds", type=float,
                        help="optional performance censor; a timed-out batch is never complete")
    parser.add_argument("--stage-name", default="manual-stage",
                        help="short stage label stored in all checkpoints")
    parser.add_argument("--single-slot", type=int, metavar="SEED",
                        help="execute only this FULL development seed from the frozen eight-slot inventory")
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    args = parser.parse_args()
    if args.wave == "serial" and args.workers != 1:
        raise RuntimeError("serial diagnostic wave requires --workers 1")
    if args.max_batch_seconds is not None and args.max_batch_seconds <= 0:
        raise RuntimeError("--max-batch-seconds must be positive")
    repo = args.repo.resolve()
    run_root = args.run.resolve()
    package = (repo / args.package).resolve() if not args.package.is_absolute() else args.package.resolve()
    expected = resolve_bindings(args, repo)
    waves = (args.wave,)
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True)
    java_version = version.stderr + version.stdout
    if version.returncode != 0 or 'version "21.' not in java_version:
        raise RuntimeError(f"JDK 21 is required; supplied java reported: {java_version.strip()}")
    if run_root.exists() and any(run_root.iterdir()):
        raise RuntimeError(f"run directory must be fresh and empty: {run_root}")
    run_root.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(run_root).free < MIN_FREE_DISK_BYTES:
        raise RuntimeError("throughput run requires at least 10 GiB filesystem free-space reserve")
    plan, profile, slots = validate_declaration(repo, package, expected)
    if args.single_slot is not None:
        raise RuntimeError("throughput stages require all eight frozen FULL development seeds")
    if len(slots) != 8:
        raise RuntimeError("throughput stages require the complete eight-seed FULL development inventory")
    jar = package / "executor.jar"
    baseline = read_json(repo / "strategy-research/definitions/fk-deleveraging-absorption/v002.json")
    controls = read_json(repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json")
    experiment = read_json(repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json")
    plan_path = package / "plan.json"
    profile_path = package / "profile.json"
    integrity_paths = {
        "executor_jar": jar,
        "plan": plan_path,
        "profile": profile_path,
        "baseline": repo / "strategy-research/definitions/fk-deleveraging-absorption/v002.json",
        "controls": repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/controls.json",
        "experiment": repo / "strategy-research/experiments/fk-deleveraging-baseline-v002/experiment.json",
    }
    integrity_before = {name: {"path": str(path), "sha256": sha256_file(path), "bytes": path.stat().st_size}
                        for name, path in integrity_paths.items()}
    binding_manifest = expected.as_json()
    if args.manifest is not None:
        binding_manifest["source"] = str(
            args.manifest.resolve() if args.manifest.is_absolute() else (repo / args.manifest).resolve())
        if any(value is not None for value in (
                args.expected_jar_sha256, args.expected_source_sha256,
                args.expected_plan_sha256, args.expected_profile_sha256)):
            binding_manifest["source"] += " (with explicit command-line overrides)"
    elif any(value is not None for value in (
            args.expected_jar_sha256, args.expected_source_sha256,
            args.expected_plan_sha256, args.expected_profile_sha256)):
        binding_manifest["source"] = "explicit command-line bindings"
    else:
        binding_manifest["source"] = "built-in defaults"
    write_json(run_root / "declaration.json", {
        "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
        "qualification_attempted": False, "heldout_executed": False,
        "activation_authorized": False, "trading_authorized": False,
        **binding_manifest,
        "wave_selection": args.wave,
        "single_slot_seed": args.single_slot,
        "stage_name": args.stage_name,
        "planned_slots": slots, "max_parallel_workers": args.workers,
        "launch_heap_mib": args.heap_mib, "active_processors": args.active_processors,
        "jvm_gc": "G1", "rss_policy": "OBSERVATIONAL_ONLY",
        "slot_wall_seconds": SLOT_WALL_SECONDS, "wave_wall_seconds": WAVE_WALL_SECONDS,
        "worker_payload_rss_bytes": WORKER_RSS_LIMIT, "worker_payload_heap_bytes": WORKER_HEAP,
        "worker_payload_cpu": 2, "run_disk_limit_bytes": DISK_LIMIT,
        "minimum_free_disk_reserve_bytes": MIN_FREE_DISK_BYTES,
        "max_batch_seconds": args.max_batch_seconds,
        "integrity_before": integrity_before,
        "git_status": subprocess.run(["git", "status", "--short"], cwd=repo,
                                      capture_output=True, text=True, check=True).stdout,
    })
    runtime_classpath = compile_strict_validator(repo, run_root, jar, args.java)
    preflight = run_root / "production-full-preflight.json"
    command = [args.java, "-jar", str(jar), "strategy-research-v5",
               "operating-characteristics-corrected-parallel-preflight", "--plan", str(plan_path),
               "--profile", str(profile_path), "--mode", "FULL"]
    completed = subprocess.run(command, cwd=repo, text=True, capture_output=True)
    (run_root / "production-full-preflight.stdout.json").write_text(completed.stdout, encoding="utf-8")
    (run_root / "production-full-preflight.stderr.log").write_text(completed.stderr, encoding="utf-8")
    if completed.returncode != 0:
        raise RuntimeError(f"production FULL preflight command failed: {completed.stderr.strip()}")
    preflight_value = json.loads(completed.stdout)
    write_json(preflight, preflight_value)
    if preflight_value.get("status") != "BLOCKED_RESOURCE" or preflight_value.get("outcomes_opened") is not False:
        raise RuntimeError("production FULL preflight did not fail closed on this Mac")
    wave_records: dict[str, list[dict]] = {}
    wave_ids: dict[str, str] = {}
    active_resource_state: list[WaveResourceState | None] = [None]

    def abort_active_wave(signum: int, _frame: object) -> None:
        state = active_resource_state[0]
        if state is not None:
            state.abort.set()
        else:
            raise KeyboardInterrupt(signal.Signals(signum).name)

    signal.signal(signal.SIGINT, abort_active_wave)
    signal.signal(signal.SIGTERM, abort_active_wave)
    started_all = time.monotonic()
    for wave in waves:
        wave_started = time.monotonic()
        wave_started_monotonic_ns = time.monotonic_ns()
        wave_started_epoch_ns = time.time_ns()
        wave_started_utc = utc_now()
        wave_root = run_root / wave
        wave_root.mkdir(parents=True, exist_ok=True)
        wave_id = str(uuid.uuid4())
        wave_ids[wave] = wave_id
        stage_config = {"stage_name": args.stage_name, "workers": args.workers,
                        "heap_mib": args.heap_mib, "active_processors": args.active_processors,
                        "java": args.java, "jar_sha256": expected.jar_sha256,
                        "source_sha256": expected.source_sha256}
        resource_state = WaveResourceState(run_root, wave, stage_config, slots)
        active_resource_state[0] = resource_state
        resource_state.start_sampler()
        records = []
        try:
            with ThreadPoolExecutor(max_workers=args.workers) as executor:
                futures = [executor.submit(run_one, slot, wave, run_root, jar, plan, profile,
                                           baseline, controls, experiment, wave_id, args.java,
                                           resource_state, expected, args.heap_mib,
                                           args.active_processors, wave_started, args.max_batch_seconds)
                           for slot in slots]
                for future in as_completed(futures):
                    records.append(future.result())
        finally:
            wave_finished_monotonic_ns = time.monotonic_ns()
            wave_finished_epoch_ns = time.time_ns()
            wave_finished_utc = utc_now()
            resource_state.stop_sampler()
        records.sort(key=lambda item: item["slot_id"])
        for record in records:
            gc = parse_gc_log(run_root / record["gc_log"])
            record.update(gc)
            slot_dir = run_root / wave / "slots" / record["slot_id"].replace("|", "_")
            write_json(slot_dir / "execution.json", record)
        total_gc_pause = sum(record["pause_seconds"] for record in records)
        worker_time_sum = sum(record["elapsed_seconds"] for record in records)
        gc_summary = {
            "parsed_after_all_workers_stopped": True,
            "full_gc_count": sum(record["full_gc_count"] for record in records),
            "full_gc_pause_seconds": sum(record["full_gc_pause_seconds"] for record in records),
            "all_gc_pause_seconds_sum_workers": total_gc_pause,
            "aggregate_worker_pause_fraction": total_gc_pause / worker_time_sum if worker_time_sum > 0 else 0.0,
            "per_worker_pause_fraction": {
                record["slot_id"]: record["pause_seconds"] / record["elapsed_seconds"]
                if record["elapsed_seconds"] > 0 else 0.0 for record in records
            },
        }
        write_json(wave_root / "gc-summary.json", gc_summary)
        # Capture the final wave footprint after the last worker has published
        # its artifact, while retaining the peak aggregate RSS sampled during
        # overlap.
        resource_state.sample(run_root)
        write_json(wave_root / "execution-records.json", records)
        active_resource_state[0] = None
        write_json(wave_root / "resource-measurement.json", {
            "rss_is_observational": True,
            "max_aggregate_worker_rss_bytes": resource_state.max_aggregate_rss,
            "max_worker_rss_bytes": max((record["max_worker_rss_bytes"] for record in records), default=0),
            "aggregate_rss_samples": resource_state.rss_samples,
            "max_run_disk_bytes": resource_state.max_run_disk,
            "run_disk_limit_bytes": DISK_LIMIT,
            "minimum_free_disk_reserve_bytes": MIN_FREE_DISK_BYTES,
            "launch_heap_mib": args.heap_mib,
        })
        batch_metrics = {
            "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
            "stage_name": args.stage_name, "workers": args.workers, "heap_mib": args.heap_mib,
            "batch_started_at_utc": wave_started_utc,
            "batch_started_at_epoch_ns": wave_started_epoch_ns,
            "batch_started_monotonic_ns": wave_started_monotonic_ns,
            "batch_finished_at_utc": wave_finished_utc,
            "batch_finished_at_epoch_ns": wave_finished_epoch_ns,
            "batch_finished_monotonic_ns": wave_finished_monotonic_ns,
            "worker_batch_wall_seconds": (wave_finished_monotonic_ns - wave_started_monotonic_ns) / 1e9,
            "worker_batch_utc_elapsed_seconds": (wave_finished_epoch_ns - wave_started_epoch_ns) / 1e9,
            "clock_elapsed_delta_seconds": ((wave_finished_epoch_ns - wave_started_epoch_ns)
                                             - (wave_finished_monotonic_ns - wave_started_monotonic_ns)) / 1e9,
            "timing_integrity": "PASS" if abs(
                ((wave_finished_epoch_ns - wave_started_epoch_ns)
                 - (wave_finished_monotonic_ns - wave_started_monotonic_ns)) / 1e9) <= 2.0 else "INVALID",
            "worker_completion_count": sum(record["status"] == "PUBLISHED_TEMPORARY" for record in records),
            "planned_worker_count": len(slots),
            "slot_states": {record["slot_id"]: record["status"] for record in records},
            "status": "CENSORED" if any(record["status"] == "CENSORED" for record in records)
                      or (args.max_batch_seconds is not None
                          and (wave_finished_monotonic_ns - wave_started_monotonic_ns) / 1e9
                          >= args.max_batch_seconds)
                      else ("WORKERS_COMPLETE" if all(record["status"] == "PUBLISHED_TEMPORARY"
                                                       for record in records) else "FAILED"),
        }
        batch_metrics["gc_summary"] = "gc-summary.json"
        batch_metrics["gc_pause_seconds_sum_workers"] = total_gc_pause
        batch_metrics["full_gc_count_sum_workers"] = gc_summary["full_gc_count"]
        batch_metrics["aggregate_worker_gc_pause_fraction"] = gc_summary["aggregate_worker_pause_fraction"]
        batch_metrics["worker_pause_fractions"] = gc_summary["per_worker_pause_fraction"]
        write_json(wave_root / "batch-metrics.json", batch_metrics)
        write_json(resource_state.progress_path, {
            **resource_state.progress_value(), "status": batch_metrics["status"],
            "batch_metrics": "batch-metrics.json", "execution_records": "execution-records.json"})
        if batch_metrics["status"] == "CENSORED":
            write_json(wave_root / "censored.json", {
                "status": "CENSORED_SLOWER_THAN_REFERENCE_LOWER_BOUND",
                "elapsed_seconds": batch_metrics["worker_batch_wall_seconds"],
                "completed_workers": batch_metrics["worker_completion_count"],
                "remaining_workers": len(slots) - batch_metrics["worker_completion_count"],
                "max_batch_seconds": args.max_batch_seconds,
                "comparison_eligible": False,
            })
            write_json(run_root / "manifest.json", {
                "schema": "mac-throughput-stage/1", "status": "CENSORED",
                "wave": wave, "batch_metrics": f"{wave}/batch-metrics.json",
                "censored": f"{wave}/censored.json", "archive_required": True,
            })
            return 3
        if time.monotonic() - wave_started > WAVE_WALL_SECONDS:
            raise RuntimeError(f"{wave} batch exceeded its six-hour hard wall bound")
        if batch_metrics["status"] != "WORKERS_COMPLETE":
            write_json(run_root / "manifest.json", {
                "schema": "mac-throughput-stage/1", "status": "FAILED",
                "wave": wave, "batch_metrics": f"{wave}/batch-metrics.json",
                "execution_records": f"{wave}/execution-records.json", "archive_required": True,
            })
            raise RuntimeError(f"{wave} full batch has failed worker executions")
        wave_records[wave] = records
        write_json(run_root / "progress.json", {
            "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
            "completed_waves": list(wave_records), "elapsed_seconds": time.monotonic() - started_all,
            "batch_metrics": f"{wave}/batch-metrics.json",
        })
    integrity_after = {name: {"path": str(path), "sha256": sha256_file(path), "bytes": path.stat().st_size}
                       for name, path in integrity_paths.items()}
    if integrity_after != integrity_before:
        raise RuntimeError("packaged executable or frozen inputs changed during waves")
    write_json(run_root / "integrity.json", {"status": "PASS", "before": integrity_before, "after": integrity_after})
    strict_records = {}
    for wave in waves:
        strict_records[wave] = strict_validate(run_root, wave, plan_path, slots, wave_ids[wave],
                                               runtime_classpath, args.java)
        write_json(run_root / wave / "strict-validation.json", strict_records[wave])
    by_wave = {}
    for wave in waves:
        by_wave[wave] = {item["slot_id"]: item for item in strict_records[wave]}
    mismatches = []
    if set(waves) == {"parallel", "serial"}:
        if set(by_wave["parallel"]) != set(by_wave["serial"]):
            raise RuntimeError("serial and parallel slot inventories differ")
        for slot_id in sorted(by_wave["parallel"]):
            left, right = by_wave["parallel"][slot_id], by_wave["serial"][slot_id]
            for field in ("portable_economic_sha256", "corrected_economic_semantic_sha256"):
                if left.get(field) != right.get(field):
                    mismatches.append({"slot_id": slot_id, "field": field,
                                       "parallel": left.get(field), "serial": right.get(field)})
    if mismatches:
        raise RuntimeError(f"serial/parallel economic digest mismatch: {mismatches[:2]}")
    audit = {
        "status": "STRICT_PASS_PENDING_INDEPENDENT_AUDIT", "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
        "qualification_attempted": False, "heldout_executed": False,
        "wave_selection": args.wave, "single_slot_seed": args.single_slot,
        "parallel_slots": len(by_wave.get("parallel", {})), "serial_slots": len(by_wave.get("serial", {})),
        "serial_parallel_economic_digests_equal": True if set(waves) == {"parallel", "serial"} else None,
        "aggregate_max_worker_rss_bytes": max(
            read_json(run_root / wave / "resource-measurement.json")["max_aggregate_worker_rss_bytes"]
            for wave in waves
        ),
        "rss_is_observational": True, "run_disk_limit_bytes": DISK_LIMIT,
        "minimum_free_disk_reserve_bytes": MIN_FREE_DISK_BYTES,
        "workers": args.workers, "launch_heap_mib": args.heap_mib,
        "active_processors": args.active_processors, "elapsed_seconds": time.monotonic() - started_all,
        "production_full_preflight_status": preflight_value["status"],
        "notes": [
            "Direct corrected-worker CLI execution is an engineering diagnostic because the production FULL coordinator gate correctly blocks this Mac.",
            "The frozen plan retains 75 confirmation repetitions per cell; this harness executes only the eight 920M development seeds.",
            "No qualification or confirmation command was invoked.",
        ],
    }
    write_json(run_root / "engineering-validation.json", audit)
    write_json(run_root / "manifest.json", {
        "schema": "mac-corrected-worker-engineering-run/1",
        "status": "STRICT_PASS_PENDING_INDEPENDENT_AUDIT", "wave_run_ids": wave_ids, "declaration": "declaration.json",
        "preflight": "production-full-preflight.json", "waves": list(waves),
        "strict_validation": {wave: f"{wave}/strict-validation.json" for wave in waves},
        "engineering_validation": "engineering-validation.json",
        "worker_batch_metrics": {wave: f"{wave}/batch-metrics.json" for wave in waves},
        "independent_audit_pending": True, "archive_required": True,
    })
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"HARNESS FAILED: {error}", file=sys.stderr)
        raise
