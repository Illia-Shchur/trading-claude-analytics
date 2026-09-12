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
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

GIB = 1024 ** 3
DEFAULT_JAR_SHA256 = "3ff5aa4612053d2483a83b93f12055bddcd45d25edc75c8ca61d1635b89c87cc"
DEFAULT_SOURCE_SHA256 = "c99a335303fa4649c83e445d7480cc2cfcbe879f51c22fc1558627680e55496a"
DEFAULT_PLAN_CONTENT_SHA256 = "e0b2b93c2bfbde064e57408cc5b0f99a8ebfd6584794611b8567a8f5573624b0"
DEFAULT_PROFILE_CONTENT_SHA256 = "e7c41026cdd9e07afd67e3034dbe5490b908207a11beb64db80308971c11f763"
WORKER_RSS_LIMIT = 3 * GIB
WORKER_HEAP = 2 * GIB
AGGREGATE_WORKER_RSS_LIMIT = 8 * GIB
DISK_LIMIT = 20 * GIB
SLOT_WALL_SECONDS = 90 * 60
WAVE_WALL_SECONDS = 6 * 60 * 60
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
    os.replace(temporary, path)


def tree_rss_bytes(pid: int) -> int:
    """Return RSS for a process and descendants using macOS ps output."""
    try:
        output = subprocess.check_output(
            ["ps", "-axo", "pid=,ppid=,rss="], text=True, stderr=subprocess.DEVNULL
        )
    except (OSError, subprocess.CalledProcessError):
        return -1
    rows: dict[int, tuple[int, int]] = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 3:
            continue
        try:
            rows[int(fields[0])] = (int(fields[1]), int(fields[2]) * 1024)
        except ValueError:
            continue
    if pid not in rows:
        return 0
    children: dict[int, list[int]] = {}
    for child, (parent, _) in rows.items():
        children.setdefault(parent, []).append(child)
    total = 0
    pending = [pid]
    seen: set[int] = set()
    while pending:
        current = pending.pop()
        if current in seen:
            continue
        seen.add(current)
        if current in rows:
            total += rows[current][1]
        pending.extend(children.get(current, []))
    return total


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
    for cell in plan.get("cells", []):
        key = (cell.get("scenario"), float(cell.get("effect_size")))
        seeds = tuple(int(seed) for seed in cell.get("seeds", []))
        if key not in EXPECTED_HELDOUT_SEEDS or seeds != EXPECTED_HELDOUT_SEEDS[key]:
            raise RuntimeError("held-out confirmation seed inventory changed")
        if any(seed in frozen for seed in seeds):
            raise RuntimeError("held-out confirmation seeds duplicate across cells")
        frozen.update(seeds)
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
    for cell in plan.get("development_prefix_seed_cells", []):
        key = (cell.get("scenario"), float(cell.get("effect_size")))
        seeds = tuple(int(seed) for seed in cell.get("prefix_seeds", []))
        if key not in EXPECTED_PREFIX_SEEDS or seeds != EXPECTED_PREFIX_SEEDS[key]:
            raise RuntimeError("development PREFIX seed inventory changed")
        prefixes.update(seeds)
    if seen & prefixes or any(seed in frozen for seed in prefixes):
        raise RuntimeError("development inventory overlaps PREFIX or held-out seeds")
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
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.active: set[int] = set()
        self.abort = threading.Event()
        self.max_aggregate_rss = 0
        self.max_run_disk = 0

    def register(self, pid: int) -> None:
        with self.lock:
            self.active.add(pid)

    def unregister(self, pid: int) -> None:
        with self.lock:
            self.active.discard(pid)

    def sample(self, run_root: Path) -> tuple[int, int]:
        with self.lock:
            pids = tuple(self.active)
        aggregate = 0
        for pid in pids:
            rss = tree_rss_bytes(pid)
            if rss < 0:
                return -1, -1
            aggregate += rss
        disk = directory_bytes(run_root)
        with self.lock:
            self.max_aggregate_rss = max(self.max_aggregate_rss, aggregate)
            self.max_run_disk = max(self.max_run_disk, disk)
        return aggregate, disk


def run_one(slot: dict, wave: str, run_root: Path, jar: Path, plan: dict, profile: dict,
            baseline: dict, controls: dict, experiment: dict, run_id: str, java: str,
            resource_state: WaveResourceState, expected: ExpectedBindings) -> dict:
    slot_dir = run_root / wave / "slots" / slot["slot_id"].replace("|", "_")
    started = time.monotonic()
    slot_dir.mkdir(parents=True, exist_ok=True)
    payload_path = slot_dir / "payload.json"
    output_path = slot_dir / "worker-result.json"
    stdout_path = slot_dir / "worker.stdout.log"
    max_rss = 0
    max_disk = 0
    status = "FAILED"
    error = ""
    exit_code = -1
    process: subprocess.Popen[str] | None = None
    try:
        if resource_state.abort.is_set():
            raise RuntimeError("WAVE_ABORTED_BEFORE_SLOT_START")
        worker_root, _ = prepare_worker_root(slot_dir, plan)
        payload = payload_for(slot, plan, profile, baseline, controls, experiment, run_id, expected)
        write_json(payload_path, payload)
        command = [
            java, "-XX:+ExitOnOutOfMemoryError", f"-Xmx{WORKER_HEAP // (1024 * 1024)}m",
            "-XX:ActiveProcessorCount=2", f"-Djava.io.tmpdir={slot_dir / 'tmp'}", "-jar", str(jar),
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
            resource_state.register(process.pid)
            try:
                while process.poll() is None:
                    elapsed = time.monotonic() - started
                    rss = tree_rss_bytes(process.pid)
                    aggregate_rss, disk = resource_state.sample(run_root)
                    max_rss = max(max_rss, rss)
                    max_disk = max(max_disk, disk)
                    violation = ""
                    if resource_state.abort.is_set():
                        violation = "WAVE_ABORTED_DUE_TO_SIBLING_FAILURE"
                    elif elapsed > SLOT_WALL_SECONDS:
                        violation = "RESOURCE_SLOT_WALL_DEADLINE_EXCEEDED"
                    elif rss < 0:
                        violation = "RESOURCE_WORKER_RSS_UNAVAILABLE"
                    elif rss > WORKER_RSS_LIMIT:
                        violation = "RESOURCE_WORKER_RSS_BUDGET_EXCEEDED"
                    elif aggregate_rss < 0:
                        violation = "RESOURCE_AGGREGATE_WORKER_RSS_UNAVAILABLE"
                    elif aggregate_rss > AGGREGATE_WORKER_RSS_LIMIT:
                        violation = "RESOURCE_AGGREGATE_WORKER_RSS_BUDGET_EXCEEDED"
                    elif disk < 0:
                        violation = "RESOURCE_RUN_DISK_USAGE_UNAVAILABLE"
                    elif disk > DISK_LIMIT:
                        violation = "RESOURCE_RUN_DISK_BUDGET_EXCEEDED"
                    if violation:
                        resource_state.abort.set()
                        terminate(process)
                        error = violation
                        break
                    time.sleep(0.5)
            finally:
                if process.poll() is None:
                    terminate(process)
                resource_state.unregister(process.pid)
            exit_code = process.wait()
        if not error and exit_code == 0 and output_path.is_file():
            status = "PUBLISHED_TEMPORARY"
        elif not error:
            error = f"WORKER_EXITED_{exit_code}"
    except Exception as exc:
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
    if status != "PUBLISHED_TEMPORARY":
        # A nonzero child exit is a wave-wide fatal condition.  This prevents
        # queued slots from launching after a worker has failed.
        resource_state.abort.set()
    record = {
        **slot, "wave": wave, "status": status, "exit_code": exit_code,
        "error": error, "elapsed_seconds": time.monotonic() - started,
        "max_worker_rss_bytes": max_rss, "max_run_disk_bytes": max_disk,
        "payload": str(payload_path.relative_to(run_root)),
        "artifact": str(output_path.relative_to(run_root)) if output_path.exists() else "",
    }
    write_json(slot_dir / "execution.json", record)
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
    parser.add_argument("--wave", choices=("parallel", "serial", "both"), default="both",
                        help="worker wave(s) to execute (default: both)")
    parser.add_argument("--single-slot", type=int, metavar="SEED",
                        help="execute only this FULL development seed from the frozen eight-slot inventory")
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    args = parser.parse_args()
    repo = args.repo.resolve()
    run_root = args.run.resolve()
    package = (repo / args.package).resolve() if not args.package.is_absolute() else args.package.resolve()
    expected = resolve_bindings(args, repo)
    waves = ("parallel", "serial") if args.wave == "both" else (args.wave,)
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True)
    java_version = version.stderr + version.stdout
    if version.returncode != 0 or 'version "21.' not in java_version:
        raise RuntimeError(f"JDK 21 is required; supplied java reported: {java_version.strip()}")
    if run_root.exists() and any(run_root.iterdir()):
        raise RuntimeError(f"run directory must be fresh and empty: {run_root}")
    run_root.mkdir(parents=True, exist_ok=True)
    plan, profile, slots = validate_declaration(repo, package, expected)
    if args.single_slot is not None:
        slots = [slot for slot in slots if slot["seed"] == args.single_slot]
        if len(slots) != 1:
            raise RuntimeError(f"--single-slot must name one frozen FULL development seed: {args.single_slot}")
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
        "planned_slots": slots, "max_parallel_workers": 2,
        "slot_wall_seconds": SLOT_WALL_SECONDS, "wave_wall_seconds": WAVE_WALL_SECONDS,
        "worker_rss_limit_bytes": WORKER_RSS_LIMIT, "worker_heap_bytes": WORKER_HEAP,
        "aggregate_worker_rss_limit_bytes": AGGREGATE_WORKER_RSS_LIMIT,
        "disk_limit_bytes": DISK_LIMIT,
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
    for wave, workers in ((name, 2 if name == "parallel" else 1) for name in waves):
        wave_started = time.monotonic()
        wave_root = run_root / wave
        wave_root.mkdir(parents=True, exist_ok=True)
        wave_id = str(uuid.uuid4())
        wave_ids[wave] = wave_id
        resource_state = WaveResourceState()
        active_resource_state[0] = resource_state
        if workers == 1:
            records = [run_one(slot, wave, run_root, jar, plan, profile,
                               baseline, controls, experiment, wave_id, args.java,
                               resource_state, expected) for slot in slots]
        else:
            records = []
            with ThreadPoolExecutor(max_workers=workers) as executor:
                futures = [executor.submit(run_one, slot, wave, run_root, jar, plan, profile,
                                           baseline, controls, experiment, wave_id, args.java,
                                           resource_state, expected)
                           for slot in slots]
                for future in as_completed(futures):
                    records.append(future.result())
            records.sort(key=lambda item: item["slot_id"])
        # Capture the final wave footprint after the last worker has published
        # its artifact, while retaining the peak aggregate RSS sampled during
        # overlap.
        resource_state.sample(run_root)
        write_json(wave_root / "execution-records.json", records)
        if time.monotonic() - wave_started > WAVE_WALL_SECONDS:
            raise RuntimeError(f"{wave} wave exceeded its six-hour harness bound")
        if any(record["status"] != "PUBLISHED_TEMPORARY" for record in records):
            raise RuntimeError(f"{wave} wave has failed worker executions")
        if resource_state.max_aggregate_rss > AGGREGATE_WORKER_RSS_LIMIT:
            raise RuntimeError(f"{wave} wave exceeded aggregate RSS bound")
        if resource_state.max_run_disk > DISK_LIMIT:
            raise RuntimeError(f"{wave} wave exceeded disk bound")
        wave_records[wave] = records
        active_resource_state[0] = None
        write_json(wave_root / "resource-measurement.json", {
            "max_aggregate_worker_rss_bytes": resource_state.max_aggregate_rss,
            "aggregate_worker_rss_limit_bytes": AGGREGATE_WORKER_RSS_LIMIT,
            "max_run_disk_bytes": resource_state.max_run_disk,
            "disk_limit_bytes": DISK_LIMIT,
        })
        write_json(run_root / "progress.json", {
            "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
            "completed_waves": list(wave_records), "elapsed_seconds": time.monotonic() - started_all,
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
        "status": "PASS", "scope": "ENGINEERING_ONLY_MAC_FULL_GEOMETRY_DEVELOPMENT",
        "qualification_attempted": False, "heldout_executed": False,
        "wave_selection": args.wave, "single_slot_seed": args.single_slot,
        "parallel_slots": len(by_wave.get("parallel", {})), "serial_slots": len(by_wave.get("serial", {})),
        "serial_parallel_economic_digests_equal": True if set(waves) == {"parallel", "serial"} else None,
        "aggregate_max_worker_rss_bytes": max(
            read_json(run_root / wave / "resource-measurement.json")["max_aggregate_worker_rss_bytes"]
            for wave in waves
        ),
        "aggregate_worker_rss_limit_bytes": AGGREGATE_WORKER_RSS_LIMIT,
        "disk_limit_bytes": DISK_LIMIT, "elapsed_seconds": time.monotonic() - started_all,
        "production_full_preflight_status": preflight_value["status"],
        "notes": [
            "Direct corrected-worker CLI execution is an engineering diagnostic because the production FULL coordinator gate correctly blocks this Mac.",
            "The frozen plan retains 75 confirmation repetitions per cell; this harness executes only selected 920M development seeds.",
            "No qualification or confirmation command was invoked.",
        ],
    }
    write_json(run_root / "engineering-validation.json", audit)
    write_json(run_root / "manifest.json", {
        "schema": "mac-corrected-worker-engineering-run/1",
        "status": "PASS", "wave_run_ids": wave_ids, "declaration": "declaration.json",
        "preflight": "production-full-preflight.json", "waves": list(waves),
        "strict_validation": {wave: f"{wave}/strict-validation.json" for wave in waves},
        "engineering_validation": "engineering-validation.json", "archive_required": True,
    })
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"HARNESS FAILED: {error}", file=sys.stderr)
        raise
