"""Independent development benchmark observer; command arguments come from JSON."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

config = json.loads(Path(sys.argv[1]).read_text())
output = Path(config["measurement_output"])
output.parent.mkdir(parents=True, exist_ok=True)
started = time.monotonic()
samples = 0
peak_rss = 0
peak_processes = 0
peak_disk = 0
last_disk_sample = 0
probe_errors = []
timed_out = False
with output.with_suffix(".stdout.log").open("wb") as log:
    process = subprocess.Popen(config["argv"], cwd=config["cwd"], stdout=log,
                               stderr=subprocess.STDOUT, start_new_session=True)
    while process.poll() is None:
        try:
            table = subprocess.check_output(["ps", "-axo", "pid=,ppid=,rss="], text=True)
            rows = [tuple(map(int, line.split())) for line in table.splitlines() if line.strip()]
            family = {process.pid}
            while True:
                expanded = family | {pid for pid, parent, rss in rows if parent in family}
                if expanded == family:
                    break
                family = expanded
            selected = [(pid, rss) for pid, parent, rss in rows if pid in family]
            if selected:
                samples += 1
                peak_rss = max(peak_rss, sum(rss for pid, rss in selected) * 1024)
                peak_processes = max(peak_processes, len(selected))
        except Exception as error:
            probe_errors.append(str(error))
        if config.get("sample_tree") and time.monotonic() - last_disk_sample >= 2:
            try:
                size = 0
                for directory, dirs, files in os.walk(config["sample_tree"]):
                    for name in files:
                        try:
                            size += os.stat(os.path.join(directory, name)).st_size
                        except FileNotFoundError:
                            pass
                peak_disk = max(peak_disk, size)
            except Exception as error:
                probe_errors.append("disk: " + str(error))
            last_disk_sample = time.monotonic()
        if time.monotonic() - started > config.get("timeout_seconds", 900):
            timed_out = True
            os.killpg(process.pid, signal.SIGKILL)
            break
        time.sleep(0.5)
    exit_code = process.wait()
measurement = {
    "scope": "LOCAL_DEVELOPMENT_BENCHMARK_ONLY",
    "argv": config["argv"], "cwd": config["cwd"],
    "wall_seconds": time.monotonic() - started,
    "exit_code": exit_code, "timed_out": timed_out,
    "sampled_aggregate_peak_rss_bytes": peak_rss,
    "peak_observed_process_count": peak_processes,
    "sampled_managed_peak_disk_bytes": peak_disk if config.get("sample_tree") else None,
    "rss_samples": samples, "probe_errors": probe_errors,
    "sampling_interval_seconds": 0.5,
    "measurement_limit": "Sampled RSS may miss between-sample peaks; includes coordinator descendants.",
    "logical_cpus": os.cpu_count(),
}
output.write_text(json.dumps(measurement, indent=2) + "\n")
print(json.dumps(measurement, indent=2))
sys.exit(0 if exit_code == 0 and not timed_out and not probe_errors else 1)
