#!/usr/bin/env python3
"""Acquire v004 liquidation-study inputs with explicit vintage and secret custody.

Stable Binance archive parsing and normalization are reused from the immutable
v003 acquisition module. Coinalyze's key is read from a mode-0600 file, sent in
an HTTP header, and never placed in a URL, log, receipt, manifest, or data freeze.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
import shutil
import stat
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any


ASSETS = ("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX")
ORIGINAL_ASSETS = ("BTC", "ETH", "SOL", "AAVE")
ADDED_ASSETS = ("UNI", "BNB", "LINK", "ZEC", "TRX")
COINALYZE_BASE_URL = "https://api.coinalyze.net/v1"
COINALYZE_EXCHANGE_CODE = "A"

# Reuse the byte parsers, archive fetcher, and deterministic normalizers that
# produced v003. Only the ordered universe and orchestration differ in v004.
_V003_PATH = Path(__file__).with_name("acquire_liquidation_exploratory_v003.py")
_SPEC = importlib.util.spec_from_file_location("liquidation_exploratory_v003_helpers", _V003_PATH)
if _SPEC is None or _SPEC.loader is None:
    raise ImportError("cannot load the v003 source-normalization helpers")
_V003 = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_V003)
_V003.ASSETS = ASSETS

utcnow = _V003.utcnow
sha256_bytes = _V003.sha256_bytes
sha256_file = _V003.sha256_file
verify_sha256 = _V003.verify_sha256
atomic_json = _V003.atomic_json
month_sequence = _V003.month_sequence
archive_tasks = _V003.archive_tasks
validate_zip = _V003.validate_zip
check_cached_archive = _V003.check_cached_archive
fetch_one = _V003.fetch_one
fetch_published_checksum = _V003.fetch_published_checksum
probe_monthly_metrics = _V003.probe_monthly_metrics
expected_days = _V003.expected_days
write_oi = _V003.write_oi
write_klines = _V003.write_klines
canonical_json_bytes = _V003.canonical_json_bytes
verify_data_freeze = _V003.verify_data_freeze
create_data_freeze = _V003.create_data_freeze
copy_existing_inputs = _V003.copy_existing_inputs


def read_coinalyze_key(path: Path) -> str:
    """Read a private key file without ever including its value in errors."""
    if path.is_symlink() or not path.is_file():
        raise FileNotFoundError("Coinalyze credential file is missing or is a symlink")
    if stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise PermissionError("Coinalyze credential file must have mode 0600")
    key = path.read_text(encoding="utf-8").strip()
    if not key:
        raise ValueError("Coinalyze credential file is empty")
    if any(char.isspace() for char in key):
        raise ValueError("Coinalyze credential must be a single token")
    return key


def coinalyze_get(endpoint: str, params: dict[str, Any], key: str) -> tuple[bytes, int]:
    """Authenticated GET with the secret in a header only; errors are redacted."""
    query = urllib.parse.urlencode(params)
    url = f"{COINALYZE_BASE_URL}/{endpoint}?{query}" if query else f"{COINALYZE_BASE_URL}/{endpoint}"
    request = urllib.request.Request(url, headers={"api_key": key, "User-Agent": "liquidation-exploratory-v004/1.0"})
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.read(), int(response.status)
        except urllib.error.HTTPError as error:
            if error.code == 429 and attempt < 4:
                retry_after = error.headers.get("Retry-After", "1")
                try:
                    delay = max(1.0, min(60.0, float(retry_after)))
                except ValueError:
                    delay = 2.0
                time.sleep(delay)
                continue
            raise RuntimeError(f"Coinalyze {endpoint} failed with HTTP {error.code}") from None
        except Exception as error:
            if attempt == 4:
                raise RuntimeError(f"Coinalyze {endpoint} failed ({type(error).__name__})") from None
            time.sleep((0.2, 0.8, 2.0)[attempt - 1])
    raise RuntimeError(f"Coinalyze {endpoint} failed after retries")


def atomic_bytes(path: Path, body: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_bytes(body)
    os.replace(temp, path)


def _validate_history(raw_body: bytes, symbol: str, asset: str, start: dt.date, end: dt.date):
    payload = json.loads(raw_body)
    if not isinstance(payload, list):
        raise ValueError(f"Coinalyze daily response is not a JSON list for {asset}")
    matching = [item for item in payload if isinstance(item, dict) and item.get("symbol") == symbol]
    if len(matching) > 1:
        raise ValueError(f"Coinalyze returned duplicate symbol objects for {asset}")
    history = matching[0].get("history", []) if matching else []
    if not isinstance(history, list):
        raise ValueError(f"Coinalyze history is not a list for {asset}")
    days: dict[str, tuple[float, float]] = {}
    invalid = outside = identical = conflicts = 0
    conflicted: set[str] = set()
    for item in history:
        if not isinstance(item, dict):
            invalid += 1
            continue
        try:
            stamp = int(item["t"])
            instant = dt.datetime.fromtimestamp(stamp, tz=dt.timezone.utc)
            if instant.time() != dt.time(0):
                raise ValueError("non-midnight timestamp")
            day = instant.date().isoformat()
            if not start <= instant.date() <= end:
                outside += 1
                continue
            long_value, short_value = float(item["l"]), float(item["s"])
            if not (math.isfinite(long_value) and math.isfinite(short_value)
                    and long_value >= 0 and short_value >= 0):
                raise ValueError("invalid value")
        except (KeyError, TypeError, ValueError, OverflowError, OSError):
            invalid += 1
            continue
        values = (long_value, short_value)
        if day in conflicted:
            conflicts += 1
        elif day in days:
            if days[day] == values:
                identical += 1
            else:
                days.pop(day)
                conflicted.add(day)
                conflicts += 1
        else:
            days[day] = values
    rows = {day: {"asset": asset, "symbol": symbol, "day_start_utc": f"{day}T00:00:00Z",
                  "long_liquidations_usd": values[0], "short_liquidations_usd": values[1]}
            for day, values in days.items()}
    expected = [day.isoformat() for day in expected_days(start, end)]
    return rows, {"response_history_rows": len(history), "observed_unique_days": len(days),
                  "first_observed_day": min(days) if days else None,
                  "last_observed_day": max(days) if days else None,
                  "expected_days": len(expected), "missing_days": len(expected) - len(days),
                  "missing_dates": [day for day in expected if day not in days],
                  "duplicate_identical_rows": identical, "conflicting_duplicate_rows": conflicts,
                  "invalid_rows": invalid, "outside_window_rows": outside}


def acquire_coinalyze(out: Path, start: dt.date, end: dt.date, key_path: Path,
                      original_liquidations: Path, refresh: bool = False) -> dict[str, Any]:
    key = read_coinalyze_key(key_path)
    raw_root, receipt_root = out / "raw" / "coinalyze", out / "receipts" / "coinalyze"
    markets_raw, market_status = coinalyze_get("future-markets", {}, key)
    markets = json.loads(markets_raw)
    if not isinstance(markets, list):
        raise ValueError("Coinalyze future-markets response must be a JSON list")
    market_by_asset = {}
    for asset in ASSETS:
        expected = f"{asset}USDT_PERP.{COINALYZE_EXCHANGE_CODE}"
        matches = [market for market in markets if isinstance(market, dict)
                   and market.get("symbol") == expected and market.get("exchange") == COINALYZE_EXCHANGE_CODE
                   and market.get("base_asset") == asset and market.get("quote_asset") == "USDT"
                   and market.get("is_perpetual") is True and market.get("margined") == "STABLE"]
        if len(matches) != 1:
            raise ValueError(f"Coinalyze has no unique Binance USDT perpetual for {asset}")
        market_by_asset[asset] = matches[0]
    markets_path = raw_root / "future-markets.json"
    atomic_bytes(markets_path, markets_raw)
    market_receipt = {"endpoint": "future-markets", "url": f"{COINALYZE_BASE_URL}/future-markets",
                      "authentication": "api_key request header; value omitted", "http_status": market_status,
                      "retrieved_at": utcnow(), "path": markets_path.relative_to(out).as_posix(),
                      "bytes": len(markets_raw), "sha256": sha256_bytes(markets_raw),
                      "binance_usdt_perpetual_markets": market_by_asset}
    atomic_json(receipt_root / "future-markets.json", market_receipt)

    from_ts = int(dt.datetime.combine(start, dt.time(), tzinfo=dt.timezone.utc).timestamp())
    to_ts = int(dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), tzinfo=dt.timezone.utc).timestamp()) - 1
    current_rows: dict[str, dict[str, dict[str, Any]]] = {}
    coverage = {}
    for asset in ASSETS:
        symbol = market_by_asset[asset]["symbol"]
        raw_path = raw_root / "liquidation-history" / f"{asset}.json"
        receipt_path = receipt_root / f"liquidation-history-{asset}.json"
        prior_receipt = None
        if receipt_path.is_file():
            prior_receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if raw_path.is_file() and not refresh:
            raw_body = raw_path.read_bytes()
            prior_hash = prior_receipt.get("sha256") if isinstance(prior_receipt, dict) else None
            if not prior_hash or sha256_bytes(raw_body) != prior_hash:
                raise ValueError(f"cached Coinalyze history has no matching receipt hash for {asset}; use --refresh")
            status = "CACHED"
            original_retrieval = None
            if prior_receipt.get("request_status") != "CACHED":
                original_retrieval = prior_receipt.get("retrieved_at")
            else:
                original_retrieval = prior_receipt.get("source_retrieved_at")
            verified_at = utcnow()
            retrieval_time_status = ("PRESERVED_PRIOR_HTTP_RECEIPT" if original_retrieval
                                     else "UNKNOWN_CACHED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME")
        else:
            raw_body, http_status = coinalyze_get("liquidation-history", {
                "symbols": symbol, "interval": "daily", "from": from_ts, "to": to_ts,
                "convert_to_usd": "true",
            }, key)
            atomic_bytes(raw_path, raw_body)
            status = f"HTTP_{http_status}"
            original_retrieval = utcnow()
            verified_at = original_retrieval
            retrieval_time_status = "CAPTURED_AT_HTTP_RESPONSE"
        rows, counts = _validate_history(raw_body, symbol, asset, start, end)
        current_rows[asset] = rows
        used = asset in ADDED_ASSETS
        receipt = {"endpoint": "liquidation-history", "url": f"{COINALYZE_BASE_URL}/liquidation-history",
                   "authentication": "api_key request header; value omitted", "symbol": symbol,
                   "exchange": "Binance", "interval": "daily", "convert_to_usd": True,
                   "from_inclusive": start.isoformat(), "to_inclusive": end.isoformat(),
                   "retrieved_at": original_retrieval, "verified_at": verified_at,
                   "retrieval_time_status": retrieval_time_status, "request_status": status,
                   "path": raw_path.relative_to(out).as_posix(), "bytes": len(raw_body),
                   "sha256": sha256_bytes(raw_body), **counts,
                   "used_current_response_for_v004_input": used,
                   "input_source": ("CURRENT_COINALYZE_RESPONSE_FOR_OWNER_ADDED_ASSET" if used else
                                    "EXACT_V003_NORMALIZED_ROWS_REUSED; current refresh retained for audit only"),
                   "availability": "Historical publication time unknown; t+48h remains a development assumption",
                   "vintage": "Current retrospective response; revisions unverified"}
        atomic_json(receipt_path, receipt)
        coverage[asset] = receipt

    if original_liquidations.is_symlink() or not original_liquidations.is_file():
        raise FileNotFoundError("v003 normalized daily liquidation source is required for exact original-asset reuse")
    original_bytes = original_liquidations.read_bytes()
    if not original_bytes.endswith(b"\n"):
        raise ValueError("v003 normalized daily liquidation source has no terminal newline")
    fields = ("asset", "symbol", "day_start_utc", "long_liquidations_usd", "short_liquidations_usd")
    original_counts = {}
    with original_liquidations.open("r", encoding="utf-8", newline="") as source_file:
        reader = csv.DictReader(source_file)
        if tuple(reader.fieldnames or ()) != fields:
            raise ValueError("v003 normalized daily liquidation source has an unexpected header")
        for row in reader:
            asset = row.get("asset", "")
            if asset not in ORIGINAL_ASSETS or row.get("symbol") != f"{asset}USDT_PERP.A":
                raise ValueError("v003 normalized daily liquidation source has unexpected asset/symbol data")
            original_counts[asset] = original_counts.get(asset, 0) + 1
    if set(original_counts) != set(ORIGINAL_ASSETS):
        raise ValueError("v003 normalized daily liquidation source does not cover the original four assets")
    normalized_path = out / "normalized" / "daily_liquidations_v004.csv"
    normalized_path.parent.mkdir(parents=True, exist_ok=True)
    normalized_path.write_bytes(original_bytes)
    added_counts = {}
    with normalized_path.open("a", encoding="utf-8", newline="") as target_file:
        writer = csv.DictWriter(target_file, fieldnames=fields)
        for asset in ADDED_ASSETS:
            added_counts[asset] = 0
            for day in sorted(current_rows[asset]):
                writer.writerow(current_rows[asset][day])
                added_counts[asset] += 1
    combined_bytes = normalized_path.read_bytes()
    if not combined_bytes.startswith(original_bytes):
        raise ValueError("v003 daily liquidation rows were not preserved byte-for-byte")
    dataset_receipt = {"schema": "coinalyze-v004-daily-liquidation-acquisition/1",
                       "source": "Coinalyze API", "retrieved_at": utcnow(),
                       "window_start": start.isoformat(), "window_end_inclusive": end.isoformat(),
                       "timezone": "UTC", "markets_receipt": market_receipt,
                       "normalized_path": normalized_path.relative_to(out).as_posix(),
                       "normalized_bytes": len(combined_bytes), "normalized_sha256": sha256_bytes(combined_bytes),
                       "original_v003_path": str(original_liquidations),
                       "original_v003_sha256": sha256_bytes(original_bytes),
                       "original_v003_prefix_preserved_byte_for_byte": True,
                       "original_v003_row_counts_by_asset": original_counts,
                       "added_asset_row_counts": added_counts, "assets": coverage,
                       "source_limitations": [
                           "Current retrospective responses do not prove historical first-publication time or revision history.",
                           "No missing dates were filled as zero and no interpolation was performed.",
                           "Original BTC/ETH/SOL/AAVE normalized rows are byte-reused from v003; only UNI/BNB/LINK/ZEC/TRX rows are added.",
                       ]}
    atomic_json(receipt_root / "daily-liquidations.json", dataset_receipt)
    return dataset_receipt


def reuse_v003_archives(out: Path, repo_root: Path, tasks: list[dict[str, Any]], manifest: dict[str, Any]) -> dict[str, Any]:
    source_root = repo_root / ".research-run" / "liquidation-exploratory-v003"
    source_freeze_sha = verify_data_freeze(source_root)
    source_manifest = json.loads((source_root / "archive-manifest.json").read_text(encoding="utf-8"))
    reused = 0
    for task in tasks:
        if task["asset"] not in ORIGINAL_ASSETS:
            continue
        prior = source_manifest.get("archives", {}).get(task["id"], {})
        source = source_root / "raw" / "binance" / task["relative_url"]
        if prior.get("status") not in {"DOWNLOADED", "CACHED"} or not source.is_file():
            continue
        if sha256_file(source) != prior.get("sha256"):
            raise ValueError(f"v003 raw archive hash differs from its receipt: {task['id']}")
        target = task["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and sha256_file(target) != prior.get("sha256"):
            raise ValueError(f"v004 cached archive differs from its immutable v003 source: {task['id']}; use --refresh")
        if not target.exists():
            shutil.copy2(source, target)
        copied = dict(prior)
        copied.update({"path": str(target), "status": "CACHED", "reused_from": "liquidation-exploratory-v003"})
        checksum = dict(prior.get("published_checksum", {}))
        if checksum.get("path"):
            source_sidecar = source.with_name(source.name + ".CHECKSUM")
            target_sidecar = target.with_name(target.name + ".CHECKSUM")
            if source_sidecar.is_file() and not target_sidecar.exists():
                target_sidecar.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_sidecar, target_sidecar)
            if target_sidecar.is_file():
                checksum["path"] = str(target_sidecar)
        if checksum:
            copied["published_checksum"] = checksum
        manifest.setdefault("archives", {})[task["id"]] = copied
        reused += 1
    receipt = {"schema": "liquidation-exploratory-v003-archive-reuse/1",
               "source_data_freeze_sha256": source_freeze_sha, "archive_count": reused,
               "assets": list(ORIGINAL_ASSETS), "source": "Exact v003 Binance archive bytes copied into v004"}
    atomic_json(out / "receipts" / "reuse-v003-archives.json", receipt)
    return receipt


def write_input_manifest(out: Path) -> dict[str, Any]:
    inputs = {"schema": "liquidation-exploratory-input-manifest/1", "root": "normalized",
              "assets": list(ASSETS),
              "files": {"daily_liquidations": "daily_liquidations_v004.csv",
                        "hourly_bars": {asset: f"klines_1h_{asset}.csv" for asset in ASSETS},
                        "oi_5m": {asset: f"oi_5m_{asset}.csv" for asset in ASSETS},
                        "sp500": "fred-sp500.csv"}}
    for filename in [inputs["files"]["daily_liquidations"], inputs["files"]["sp500"],
                     *inputs["files"]["hourly_bars"].values(), *inputs["files"]["oi_5m"].values()]:
        if not (out / "normalized" / filename).is_file():
            raise FileNotFoundError(f"input manifest points to missing file: normalized/{filename}")
    atomic_json(out / "input-manifest.json", inputs)
    return inputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", default="2022-08-11")
    parser.add_argument("--end", default="2026-09-19")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--run-dir", default=".research-run/liquidation-exploratory-v004")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--coinalyze-key-file", default=None,
                        help="path to private mode-0600 key file; defaults to <run-dir>/secrets/coinalyze.key")
    parser.add_argument("--retry-missing", action="store_true", help="recheck prior HTTP 404s")
    parser.add_argument("--refresh", action="store_true", help="replace cached bytes after explicit review")
    args = parser.parse_args()
    start, end = dt.date.fromisoformat(args.start), dt.date.fromisoformat(args.end)
    if start > end or not 1 <= args.workers <= 48:
        parser.error("invalid date range or worker count (allowed 1..48)")
    out, repo_root = Path(args.run_dir).resolve(), Path(args.repo_root).resolve()
    out.mkdir(parents=True, exist_ok=True)
    freeze_path = out / "data-freeze.json"
    if freeze_path.exists():
        digest = verify_data_freeze(out)
        print(json.dumps({"data_freeze_path": str(freeze_path), "data_freeze_sha256": digest,
                          "status": "verified immutable input freeze; no files rewritten"}, indent=2), flush=True)
        return 0

    manifest_path = out / "archive-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        manifest = {"schema": "liquidation-exploratory-archive-manifest/1", "archives": {}}
    except json.JSONDecodeError as error:
        raise ValueError(f"refusing to replace malformed archive manifest: {error}") from error
    manifest.update({"schema": "liquidation-exploratory-archive-manifest/1",
                     "source": "Binance public data archive", "acquisition_started_at": utcnow(),
                     "window_start": args.start, "window_end_inclusive": args.end,
                     "timezone": "UTC", "worker_limit": args.workers})
    tasks = archive_tasks(start, end, out)
    reused = reuse_v003_archives(out, repo_root, tasks, manifest)
    manifest["reused_v003_archive_count"] = reused["archive_count"]
    copy_receipts = copy_existing_inputs(out, repo_root)
    key_path = Path(args.coinalyze_key_file).resolve() if args.coinalyze_key_file else out / "secrets" / "coinalyze.key"
    liquidation_receipt = acquire_coinalyze(
        out, start, end, key_path, out / "normalized" / "daily_liquidations_v002.csv", args.refresh)
    manifest["source_probe_monthly_metrics"] = probe_monthly_metrics()
    pending = []
    for task in tasks:
        prior = manifest.get("archives", {}).get(task["id"], {})
        if prior.get("status") in {"DOWNLOADED", "CACHED"} and task["path"].exists():
            if check_cached_archive(task, prior, args.refresh):
                continue
        if prior.get("status") == "NOT_FOUND" and not args.retry_missing:
            continue
        pending.append((task, prior))
    print(f"archive tasks={len(tasks)}; reused v003={reused['archive_count']}; pending={len(pending)}; workers={args.workers}", flush=True)
    completed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_one, task, prior, args.retry_missing, args.refresh): task
                   for task, prior in pending}
        for future in as_completed(futures):
            record = future.result()
            manifest.setdefault("archives", {})[record["id"]] = record
            completed += 1
            if completed % 100 == 0 or completed == len(pending):
                manifest["last_progress_at"] = utcnow()
                atomic_json(manifest_path, manifest)
                print(f"archives {completed}/{len(pending)}: {record['id']} {record['status']}", flush=True)
    manifest["acquisition_finished_at"] = utcnow()
    checksum_tasks = []
    for task in tasks:
        record = manifest.get("archives", {}).get(task["id"], {})
        if record.get("status") not in {"DOWNLOADED", "CACHED"}:
            continue
        prior_checksum = record.get("published_checksum", {})
        if prior_checksum.get("status") == "NOT_FOUND" and not args.retry_missing:
            continue
        checksum_tasks.append((task, record))
    checksum_completed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_published_checksum, task, record, args.refresh): (task, record)
                   for task, record in checksum_tasks}
        for future in as_completed(futures):
            task, record = futures[future]
            record["published_checksum"] = future.result()
            checksum_completed += 1
            if checksum_completed % 200 == 0 or checksum_completed == len(checksum_tasks):
                manifest["last_checksum_progress_at"] = utcnow()
                atomic_json(manifest_path, manifest)
                print(f"checksums {checksum_completed}/{len(checksum_tasks)}", flush=True)
    tally: dict[str, int] = {}
    checksum_tally: dict[str, int] = {}
    for record in manifest.get("archives", {}).values():
        tally[record.get("status", "UNKNOWN")] = tally.get(record.get("status", "UNKNOWN"), 0) + 1
        state = record.get("published_checksum", {}).get("status")
        if state:
            checksum_tally[state] = checksum_tally.get(state, 0) + 1
    manifest["status_counts"] = tally
    manifest["published_checksum_status_counts"] = checksum_tally
    atomic_json(manifest_path, manifest)

    coverage: dict[str, Any] = {"schema": "liquidation-exploratory-coverage/1", "acquired_at": utcnow(),
        "window_start": args.start, "window_end_inclusive": args.end, "timezone": "UTC",
        "assets": list(ASSETS), "availability_note": "Current retrospective archive vintage; source hashes do not prove historical publication time.",
        "copied_inputs": copy_receipts, "daily_liquidations": liquidation_receipt,
        "reused_v003_binance_archives": reused, "datasets": {}}
    for asset in ASSETS:
        coverage["datasets"][asset] = {"oi_5m": write_oi(asset, start, end, manifest, out),
                                       "klines_1h": write_klines(asset, start, end, manifest, out)}
    coverage["input_manifest"] = write_input_manifest(out)
    coverage["archive_status_counts"] = tally
    coverage["published_checksum_status_counts"] = checksum_tally
    coverage["archives_without_verified_published_checksum"] = sorted(
        record.get("id") for record in manifest.get("archives", {}).values()
        if record.get("status") in {"DOWNLOADED", "CACHED"}
        and record.get("published_checksum", {}).get("status") != "VERIFIED")
    atomic_json(out / "coverage.json", coverage)
    failures = [record for record in manifest.get("archives", {}).values() if record.get("status") == "ERROR"]
    checksum_failures = [record for record in manifest.get("archives", {}).values()
                         if record.get("published_checksum", {}).get("status") in {"ERROR", "INVALID", "MISMATCH"}]
    if failures or checksum_failures:
        raise RuntimeError(f"source verification failed: archive_errors={len(failures)}, checksum_errors={len(checksum_failures)}; no data freeze written")
    freeze_sha = create_data_freeze(out, args.start, args.end)
    frozen = json.loads(freeze_path.read_text(encoding="utf-8"))
    if any("secret" in Path(relative).parts for relative in frozen.get("files", {})):
        raise ValueError("secret path unexpectedly entered the data freeze")
    print(json.dumps({"run_dir": str(out), "coverage_path": str(out / "coverage.json"),
                      "archive_status_counts": tally, "published_checksum_status_counts": checksum_tally,
                      "errors": len(failures) + len(checksum_failures), "data_freeze_sha256": freeze_sha,
                      "data_freeze_file_count": len(frozen.get("files", {})),
                      "daily_liquidation_rows": sum(liquidation_receipt["added_asset_row_counts"].values())
                                                + sum(liquidation_receipt["original_v003_row_counts_by_asset"].values())}, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
