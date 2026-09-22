#!/usr/bin/env python3
"""Acquire supplemental Binance hourly bars for v005 daily context warmup.

This is a narrow, historical-only input builder. It reads the immutable v004
freeze, reuses its checksum-verified August 2022 monthly klines, downloads only
April through July 2022, and writes supplemental hourly bars under the ignored
.research-run/liquidation-context-v005/ tree. It does not calculate indicators
or trading outcomes.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import importlib.util
import json
import math
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any


ASSETS = ("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX")
WARMUP_START = dt.date(2022, 4, 1)
WARMUP_END_EXCLUSIVE = dt.date(2022, 8, 11)
DECISION_START = "2022-11-11"
DECISION_END_EXCLUSIVE = "2026-07-15"
RETAINED_START = "2022-08-11"
RETAINED_END_EXCLUSIVE = "2026-09-20"
BASE_URL = "https://data.binance.vision/data/futures/um"
SCHEMA = "liquidation-context-warmup/1"

_V003_PATH = Path(__file__).with_name("acquire_liquidation_exploratory_v003.py")
_SPEC = importlib.util.spec_from_file_location("liquidation_context_v005_binance_helpers", _V003_PATH)
if _SPEC is None or _SPEC.loader is None:
    raise ImportError("cannot load the v003 Binance archive helpers")
_V003 = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_V003)

KLINE_COLUMNS = _V003.KLINE_COLUMNS
archive_csv = _V003.archive_csv
atomic_json = _V003.atomic_json
sha256_bytes = _V003.sha256_bytes
sha256_file = _V003.sha256_file
validate_zip = _V003.validate_zip
fetch_one = _V003.fetch_one
fetch_published_checksum = _V003.fetch_published_checksum
contiguous_gaps = _V003.contiguous_gaps
to_iso_ms = _V003.to_iso_ms
utcnow = _V003.utcnow


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def month_sequence(start: dt.date, end_exclusive: dt.date):
    year, month = start.year, start.month
    last = end_exclusive - dt.timedelta(days=1)
    while (year, month) <= (last.year, last.month):
        yield year, month
        if month == 12:
            year, month = year + 1, 1
        else:
            month += 1


def verify_retained_v004(v004_dir: Path) -> dict[str, Any]:
    """Verify the full frozen v004 tree and select its immutable hourly inputs."""
    freeze_path = v004_dir / "data-freeze.json"
    freeze = json.loads(freeze_path.read_text(encoding="utf-8"))
    _V003.verify_data_freeze(v004_dir)
    input_manifest_path = v004_dir / "input-manifest.json"
    input_manifest = json.loads(input_manifest_path.read_text(encoding="utf-8"))
    if input_manifest.get("assets") != list(ASSETS):
        raise ValueError("retained v004 input manifest does not match the fixed nine-asset universe")
    archive_manifest_path = v004_dir / "archive-manifest.json"
    archive_manifest = json.loads(archive_manifest_path.read_text(encoding="utf-8"))
    if archive_manifest.get("window_start") != RETAINED_START or archive_manifest.get("window_end_inclusive") != "2026-09-19":
        raise ValueError("retained v004 hourly source window differs from the expected frozen window")
    frozen_files = freeze.get("files", {})
    references: dict[str, Any] = {}
    for asset in ASSETS:
        normalized_rel = f"normalized/klines_1h_{asset}.csv"
        normalized_record = frozen_files.get(normalized_rel)
        if not normalized_record:
            raise ValueError(f"retained v004 freeze omits {normalized_rel}")
        normalized_path = v004_dir / normalized_rel
        if normalized_path.stat().st_size != normalized_record["bytes"] or sha256_file(normalized_path) != normalized_record["sha256"]:
            raise ValueError(f"retained v004 hourly source changed: {normalized_rel}")
        archive_key = f"klines_1h:{asset}:monthly:2022-08"
        archive_record = archive_manifest.get("archives", {}).get(archive_key)
        if not isinstance(archive_record, dict) or archive_record.get("status") not in {"DOWNLOADED", "CACHED"}:
            raise ValueError(f"retained v004 has no usable August 2022 Binance archive for {asset}")
        if archive_record.get("published_checksum", {}).get("status") != "VERIFIED":
            raise ValueError(f"retained August 2022 archive checksum is not verified for {asset}")
        archive_rel = Path("raw/binance/monthly/klines") / f"{asset}USDT" / "1h" / f"{asset}USDT-1h-2022-08.zip"
        checksum_rel = Path(str(archive_rel) + ".CHECKSUM")
        archive_path, checksum_path = v004_dir / archive_rel, v004_dir / checksum_rel
        for rel, path, expected_sha in (
            (archive_rel.as_posix(), archive_path, archive_record.get("sha256")),
            (checksum_rel.as_posix(), checksum_path, archive_record["published_checksum"].get("sha256")),
        ):
            frozen = frozen_files.get(rel)
            if not frozen or not path.is_file() or path.is_symlink():
                raise ValueError(f"retained v004 freeze omits August source bytes: {rel}")
            actual = sha256_file(path)
            if actual != frozen.get("sha256") or actual != expected_sha:
                raise ValueError(f"retained August source hash mismatch: {rel}")
        text = checksum_path.read_text(encoding="utf-8", errors="replace")
        published = re.search(r"\b([0-9a-fA-F]{64})\b", text)
        if not published or published.group(1).lower() != archive_record["sha256"].lower():
            raise ValueError(f"retained August published checksum no longer matches for {asset}")
        valid, _ = validate_zip(archive_path)
        if not valid:
            raise ValueError(f"retained August source is not a valid ZIP for {asset}")
        references[asset] = {
            "archive_manifest_key": archive_key,
            "archive_path_from_v004": archive_rel.as_posix(),
            "archive_sha256": archive_record["sha256"],
            "archive_bytes": archive_path.stat().st_size,
            "published_checksum_path_from_v004": checksum_rel.as_posix(),
            "published_checksum_file_sha256": sha256_file(checksum_path),
            "published_sha256": published.group(1).lower(),
            "prior_status": archive_record.get("status"),
            "retrieved_at": archive_record.get("retrieved_at"),
            "retrieval_time_status": archive_record.get("retrieval_time_status") or "UNKNOWN_RETAINED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME",
            "normalized_v004_path": normalized_rel,
            "normalized_v004_sha256": normalized_record["sha256"],
        }
    return {
        "directory": str(v004_dir),
        "data_freeze_sha256": sha256_file(freeze_path),
        "input_manifest_sha256": sha256_file(input_manifest_path),
        "archive_manifest_sha256": sha256_file(archive_manifest_path),
        "data_freeze_content_sha256": freeze.get("content_sha256"),
        "window_start": RETAINED_START,
        "window_end_exclusive": RETAINED_END_EXCLUSIVE,
        "assets": list(ASSETS),
        "august_archives": references,
    }


def make_archive_tasks(out: Path) -> list[dict[str, Any]]:
    """Only fresh archives needed from April through July; August is reused."""
    tasks = []
    for asset in ASSETS:
        symbol = asset + "USDT"
        for year, month in month_sequence(WARMUP_START, WARMUP_END_EXCLUSIVE):
            period = f"{year:04d}-{month:02d}"
            if period == "2022-08":
                continue
            rel = f"monthly/klines/{symbol}/1h/{symbol}-1h-{period}.zip"
            tasks.append({
                "id": f"klines_1h:{asset}:monthly:{period}", "kind": "klines_1h",
                "asset": asset, "period": period, "cadence": "monthly", "relative_url": rel,
                "url": f"{BASE_URL}/{rel}", "path": out / "raw" / "binance" / rel,
            })
    return tasks


def with_retrieval_provenance(result: dict[str, Any], previous: dict[str, Any], now: str) -> dict[str, Any]:
    """Preserve time only for same-byte cache reuse; retain lineage on a new download."""
    record = dict(result)
    prior_time = previous.get("retrieved_at")
    prior_hash = previous.get("sha256")
    result_hash = record.get("sha256")
    cache_reuse = result.get("attempts", 0) == 0 and prior_hash and prior_hash == result_hash
    if cache_reuse and prior_time:
        record["retrieved_at"] = prior_time
        record["retrieval_time_status"] = previous.get("retrieval_time_status", "PRESERVED_PRIOR_HTTP_RECEIPT")
        record["retrieval_history"] = list(previous.get("retrieval_history", []))
    elif result.get("status") == "DOWNLOADED" and result.get("attempts", 0) > 0:
        record["retrieved_at"] = now
        record["retrieval_time_status"] = "CAPTURED_AT_HTTP_RESPONSE_APPROXIMATED_AFTER_DOWNLOAD"
        history = list(previous.get("retrieval_history", []))
        if prior_time:
            history.append({"sha256": prior_hash, "retrieved_at": prior_time,
                            "retrieval_time_status": previous.get("retrieval_time_status")})
        record["retrieval_history"] = history
    else:
        record["retrieved_at"] = None
        record["retrieval_time_status"] = "UNKNOWN_CACHED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME"
        record["retrieval_history"] = list(previous.get("retrieval_history", []))
    return record


def with_checksum_provenance(result: dict[str, Any], previous: dict[str, Any], now: str) -> dict[str, Any]:
    """Apply the same timestamp and history rules to a published CHECKSUM sidecar."""
    record = dict(result)
    prior_time = previous.get("retrieved_at")
    prior_hash = previous.get("sha256")
    result_hash = record.get("sha256")
    cache_reuse = record.get("attempts", 0) == 0 and prior_hash and prior_hash == result_hash
    if cache_reuse and prior_time:
        record["retrieved_at"] = prior_time
        record["retrieval_time_status"] = previous.get("retrieval_time_status", "PRESERVED_PRIOR_HTTP_RECEIPT")
        record["retrieval_history"] = list(previous.get("retrieval_history", []))
    elif record.get("attempts", 0) > 0 and record.get("http_status") == 200:
        record["retrieved_at"] = now
        record["retrieval_time_status"] = "CAPTURED_AT_HTTP_RESPONSE_APPROXIMATED_AFTER_DOWNLOAD"
        history = list(previous.get("retrieval_history", []))
        if prior_time:
            history.append({"sha256": prior_hash, "retrieved_at": prior_time,
                            "retrieval_time_status": previous.get("retrieval_time_status")})
        record["retrieval_history"] = history
    else:
        record["retrieved_at"] = None
        record["retrieval_time_status"] = "UNKNOWN_CACHED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME"
        record["retrieval_history"] = list(previous.get("retrieval_history", []))
    return record


def acquire_archives(out: Path, v004: dict[str, Any], workers: int = 8) -> dict[str, Any]:
    manifest_path = out / "archive-manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("schema") != "liquidation-context-warmup-archive-manifest/1":
            raise ValueError("refusing to replace an unsupported warmup archive manifest")
    else:
        manifest = {"schema": "liquidation-context-warmup-archive-manifest/1", "archives": {}}
    manifest.update({"source": "Binance public data archive", "warmup_start": WARMUP_START.isoformat(),
                     "warmup_end_exclusive": WARMUP_END_EXCLUSIVE.isoformat(), "assets": list(ASSETS),
                     "retained_v004_data_freeze_sha256": v004["data_freeze_sha256"]})
    atomic_json(manifest_path, manifest)
    tasks = make_archive_tasks(out)
    pending = []
    for task in tasks:
        prior = manifest.get("archives", {}).get(task["id"], {})
        path = task["path"]
        if path.is_file():
            valid, _ = validate_zip(path)
            actual = sha256_file(path)
            expected = prior.get("sha256")
            if not valid:
                path.unlink()
            elif expected and actual != expected:
                raise ValueError(f"cached supplemental archive SHA-256 mismatch for {task['id']}")
            elif prior.get("published_checksum", {}).get("status") == "MISMATCH":
                raise ValueError(f"supplemental archive has a prior checksum mismatch: {task['id']}")
        if not path.is_file() or prior.get("status") not in {"DOWNLOADED", "CACHED"}:
            pending.append((task, prior))
    print(f"supplemental archive tasks={len(tasks)}; pending={len(pending)}; workers={workers}", flush=True)
    completed = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_one, task, prior, False, False): (task, prior) for task, prior in pending}
        for future in as_completed(futures):
            task, prior = futures[future]
            result = future.result()
            manifest.setdefault("archives", {})[task["id"]] = with_retrieval_provenance(result, prior, utcnow())
            completed += 1
            atomic_json(manifest_path, manifest)
            print(f"archives {completed}/{len(pending)}: {task['id']} {result.get('status')}", flush=True)
    checksum_tasks = []
    for task in tasks:
        record = manifest.get("archives", {}).get(task["id"], {})
        if record.get("status") not in {"DOWNLOADED", "CACHED"}:
            continue
        checksum_tasks.append((task, record))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_published_checksum, task, record, False): (task, record)
                   for task, record in checksum_tasks}
        for future in as_completed(futures):
            task, record = futures[future]
            prior = manifest.get("archives", {}).get(task["id"], {})
            checksum = future.result()
            wrapped = dict(record)
            wrapped["published_checksum"] = with_checksum_provenance(
                checksum, prior.get("published_checksum", {}), utcnow())
            manifest["archives"][task["id"]] = wrapped
            atomic_json(manifest_path, manifest)
    for record in manifest.get("archives", {}).values():
        checksum = record.get("published_checksum", {})
        checksum.setdefault("retrieved_at", None)
        checksum.setdefault("retrieval_time_status", "UNKNOWN_CACHED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME")
    tally: dict[str, int] = {}
    checksum_tally: dict[str, int] = {}
    for record in manifest.get("archives", {}).values():
        status = record.get("status", "UNKNOWN")
        tally[status] = tally.get(status, 0) + 1
        checksum_status = record.get("published_checksum", {}).get("status", "NO_CHECKSUM")
        checksum_tally[checksum_status] = checksum_tally.get(checksum_status, 0) + 1
    manifest["status_counts"] = tally
    manifest["published_checksum_status_counts"] = checksum_tally
    manifest["updated_at"] = utcnow()
    atomic_json(manifest_path, manifest)
    errors = [record for record in manifest["archives"].values()
              if (record.get("status") not in {"DOWNLOADED", "CACHED"}
                  or record.get("published_checksum", {}).get("status") != "VERIFIED")]
    if errors:
        raise RuntimeError(f"supplemental Binance inputs failed closed: {len(errors)} archive/checksum errors; see {manifest_path}")
    return manifest


def add_reused_august_manifest(manifest: dict[str, Any], v004: dict[str, Any]) -> None:
    reused = {}
    for asset in ASSETS:
        item = v004["august_archives"][asset]
        reused[f"klines_1h:{asset}:monthly:2022-08"] = {
            "id": f"klines_1h:{asset}:monthly:2022-08", "asset": asset, "period": "2022-08",
            "status": "REUSED_VERIFIED_V004", "url": f"{BASE_URL}/monthly/klines/{asset}USDT/1h/{asset}USDT-1h-2022-08.zip",
            "source_run": "liquidation-exploratory-v004", "sha256": item["archive_sha256"],
            "bytes": item["archive_bytes"], **item,
        }
    manifest["reused_v004_august_archives"] = reused


def normalize_asset(asset: str, out: Path, v004_dir: Path, archive_manifest: dict[str, Any]) -> dict[str, Any]:
    rows: dict[dt.datetime, dict[str, str]] = {}
    conflicting: set[dt.datetime] = set()
    duplicate_identical = duplicate_conflicting = off_period = outside_window = malformed = 0
    sources = []
    for year, month in month_sequence(WARMUP_START, WARMUP_END_EXCLUSIVE):
        period = f"{year:04d}-{month:02d}"
        key = f"klines_1h:{asset}:monthly:{period}"
        if period == "2022-08":
            archive = v004_dir / "raw" / "binance" / "monthly" / "klines" / f"{asset}USDT" / "1h" / f"{asset}USDT-1h-{period}.zip"
            rec = archive_manifest.get("reused_v004_august_archives", {}).get(key, {})
            source_kind = "RETAINED_V004_CHECKSUM_VERIFIED"
        else:
            archive = out / "raw" / "binance" / "monthly" / "klines" / f"{asset}USDT" / "1h" / f"{asset}USDT-1h-{period}.zip"
            rec = archive_manifest.get("archives", {}).get(key, {})
            source_kind = "SUPPLEMENTAL_CHECKSUM_VERIFIED"
        if rec.get("status") not in {"DOWNLOADED", "CACHED", "REUSED_VERIFIED_V004"}:
            continue
        if not archive.is_file() or sha256_file(archive) != rec.get("sha256"):
            raise ValueError(f"archive hash changed before normalization: {key}")
        valid, _ = validate_zip(archive)
        if not valid:
            raise ValueError(f"archive failed ZIP integrity validation before normalization: {key}")
        sources.append({"manifest_key": key, "kind": source_kind, "path": str(archive),
                        "sha256": sha256_file(archive), "bytes": archive.stat().st_size,
                        "retrieved_at": rec.get("retrieved_at"),
                        "retrieval_time_status": rec.get("retrieval_time_status")})
        try:
            for row in archive_csv(archive):
                if row and row[0].strip().lower() in {"open_time", "open time"}:
                    continue
                if len(row) < 11:
                    malformed += 1
                    continue
                try:
                    open_ms = int(row[0])
                    if open_ms % 3_600_000:
                        raise ValueError("open timestamp is not hour aligned")
                    stamp = dt.datetime.fromtimestamp(open_ms / 1000, tz=dt.timezone.utc)
                except (ValueError, OverflowError, OSError):
                    malformed += 1
                    continue
                if stamp.strftime("%Y-%m") != period:
                    off_period += 1
                    continue
                if stamp.date() < WARMUP_START or stamp.date() >= WARMUP_END_EXCLUSIVE:
                    outside_window += 1
                    continue
                try:
                    o, h, low, close = (float(row[i]) for i in (1, 2, 3, 4))
                    volume = float(row[5])
                    if (not all(math.isfinite(value) and value > 0 for value in (o, h, low, close))
                            or h < max(o, close) or low > min(o, close) or h < low
                            or not math.isfinite(volume) or volume < 0):
                        raise ValueError("invalid OHLC or base volume")
                except (ValueError, IndexError):
                    malformed += 1
                    continue
                normalized = {
                    "open_time": stamp.isoformat(timespec="seconds").replace("+00:00", "Z"),
                    "symbol": asset + "USDT", "open": row[1], "high": row[2], "low": row[3],
                    "close": row[4], "base_volume": row[5],
                    "close_time": to_iso_ms(row[6]) if len(row) > 6 else "",
                    "quote_volume": row[7] if len(row) > 7 else "",
                    "trade_count": row[8] if len(row) > 8 else "",
                    "taker_buy_base_volume": row[9] if len(row) > 9 else "",
                    "taker_buy_quote_volume": row[10] if len(row) > 10 else "",
                }
                if stamp in conflicting:
                    duplicate_conflicting += 1
                    continue
                prior = rows.get(stamp)
                if prior is None:
                    rows[stamp] = normalized
                elif prior == normalized:
                    duplicate_identical += 1
                else:
                    duplicate_conflicting += 1
                    conflicting.add(stamp)
                    rows.pop(stamp, None)
        except Exception as error:
            malformed += 1
            raise ValueError(f"cannot normalize {key}: {type(error).__name__}") from None

    path = out / "normalized" / f"klines_1h_{asset}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=KLINE_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows[stamp] for stamp in sorted(rows))
    expected_start = dt.datetime.combine(WARMUP_START, dt.time(), tzinfo=dt.timezone.utc)
    expected_end = dt.datetime.combine(WARMUP_END_EXCLUSIVE, dt.time(), tzinfo=dt.timezone.utc)
    expected_total = int((expected_end - expected_start).total_seconds() // 3600)
    times = set(rows)
    missing_intervals = contiguous_gaps(times, expected_start, expected_end, dt.timedelta(hours=1))
    per_day = []
    missing_dates = []
    day = WARMUP_START
    while day < WARMUP_END_EXCLUSIVE:
        day_start = dt.datetime.combine(day, dt.time(), tzinfo=dt.timezone.utc)
        day_end = day_start + dt.timedelta(days=1)
        observed = sum(day_start <= stamp < day_end for stamp in times)
        missing = 24 - observed
        if missing:
            missing_dates.append(day.isoformat())
        per_day.append({"date": day.isoformat(), "expected_rows": 24, "observed_unique_rows": observed,
                        "missing_rows": missing})
        day += dt.timedelta(days=1)
    return {
        "path": path.relative_to(out).as_posix(), "sha256": sha256_file(path), "bytes": path.stat().st_size,
        "requested_start": WARMUP_START.isoformat(), "requested_end_exclusive": WARMUP_END_EXCLUSIVE.isoformat(),
        "expected_rows": expected_total, "observed_unique_rows": len(times), "missing_rows": expected_total - len(times),
        "missing_dates": missing_dates, "per_day": per_day, "missing_intervals": missing_intervals,
        "duplicate_identical_rows_ignored": duplicate_identical,
        "duplicate_conflicting_rows_rejected": duplicate_conflicting,
        "off_period_rows_rejected": off_period, "outside_window_rows_ignored": outside_window,
        "malformed_archives_or_rows_rejected": malformed, "source_archives": sources,
        "zero_fill_applied": False,
    }


def create_freeze(out: Path, retained: dict[str, Any]) -> str:
    freeze_path = out / "data-freeze.json"
    if freeze_path.exists():
        return verify_context_freeze(out, Path(retained["directory"]))
    files: dict[str, Any] = {}
    for root in (out / "raw", out / "normalized"):
        if root.exists():
            for path in sorted(root.rglob("*")):
                if path.is_symlink():
                    raise ValueError(f"symlink in context freeze tree: {path}")
                if path.is_file():
                    files[path.relative_to(out).as_posix()] = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
    for name in ("archive-manifest.json", "coverage.json", "context-warmup-manifest.json"):
        path = out / name
        if not path.is_file():
            raise FileNotFoundError(f"required context manifest missing: {path}")
        files[name] = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
    body = {
        "schema": "liquidation-context-warmup-freeze/1", "created_at": utcnow(),
        "warmup_start": WARMUP_START.isoformat(), "warmup_end_exclusive": WARMUP_END_EXCLUSIVE.isoformat(),
        "decision_start": DECISION_START, "decision_end_exclusive": DECISION_END_EXCLUSIVE,
        "assets": list(ASSETS), "retained_v004": retained, "files": files,
    }
    body["content_sha256"] = hashlib.sha256(canonical_json_bytes(body)).hexdigest()
    atomic_json(freeze_path, body)
    return sha256_file(freeze_path)


def verify_context_freeze(out: Path, v004_dir: Path) -> str:
    freeze_path = out / "data-freeze.json"
    freeze = json.loads(freeze_path.read_text(encoding="utf-8"))
    if freeze.get("schema") != "liquidation-context-warmup-freeze/1":
        raise ValueError("unsupported v005 context warmup freeze")
    body = dict(freeze)
    content_hash = body.pop("content_sha256", None)
    if not content_hash or hashlib.sha256(canonical_json_bytes(body)).hexdigest() != content_hash:
        raise ValueError("v005 context warmup freeze content hash mismatch")
    current_retained = verify_retained_v004(v004_dir)
    retained = freeze.get("retained_v004", {})
    for key in ("data_freeze_sha256", "input_manifest_sha256", "archive_manifest_sha256", "data_freeze_content_sha256"):
        if current_retained.get(key) != retained.get(key):
            raise ValueError(f"retained v004 source identity changed: {key}")
    for relative, record in freeze.get("files", {}).items():
        rel = Path(relative)
        if rel.is_absolute() or ".." in rel.parts:
            raise ValueError(f"context freeze path escapes run root: {relative}")
        path = out / rel
        if path.is_symlink() or not path.is_file() or path.stat().st_size != record.get("bytes") or sha256_file(path) != record.get("sha256"):
            raise ValueError(f"immutable context warmup input changed: {relative}")
    return sha256_file(freeze_path)


def run(out: Path, v004_dir: Path, workers: int = 8) -> dict[str, Any]:
    if not 1 <= workers <= 32:
        raise ValueError("workers must be between 1 and 32")
    out.mkdir(parents=True, exist_ok=True)
    if (out / "data-freeze.json").exists():
        digest = verify_context_freeze(out, v004_dir)
        return {"status": "VERIFIED_EXISTING_FREEZE", "data_freeze_sha256": digest,
                "data_freeze_path": str(out / "data-freeze.json")}
    retained = verify_retained_v004(v004_dir)
    archive_manifest = acquire_archives(out, retained, workers)
    add_reused_august_manifest(archive_manifest, retained)
    atomic_json(out / "archive-manifest.json", archive_manifest)
    per_asset = {asset: normalize_asset(asset, out, v004_dir, archive_manifest) for asset in ASSETS}
    coverage = {
        "schema": "liquidation-context-warmup-coverage/1", "generated_at": utcnow(),
        "source": "Binance public USDT-M perpetual monthly klines with published .CHECKSUM sidecars",
        "warmup_start": WARMUP_START.isoformat(), "warmup_end_exclusive": WARMUP_END_EXCLUSIVE.isoformat(),
        "decision_window_start": DECISION_START, "decision_window_end_exclusive": DECISION_END_EXCLUSIVE,
        "assets": list(ASSETS), "assets_with_gaps": [asset for asset in ASSETS if per_asset[asset]["missing_rows"]],
        "asset_coverage": per_asset,
        "availability_note": "Current retrospective archive bytes; checksum validates bytes, not original publication time or historical vintage.",
        "missingness_policy": "Missing bars remain missing; no zero-fill, interpolation, or retrofill.",
    }
    atomic_json(out / "coverage.json", coverage)
    manifest = {
        "schema": SCHEMA, "generated_at": utcnow(), "assets": list(ASSETS),
        "decision_window": {"start_inclusive": DECISION_START, "end_exclusive": DECISION_END_EXCLUSIVE},
        "warmup_window": {"start_inclusive": WARMUP_START.isoformat(), "end_exclusive": WARMUP_END_EXCLUSIVE.isoformat()},
        "retained_hourly_window": {"start_inclusive": RETAINED_START, "end_exclusive": RETAINED_END_EXCLUSIVE},
        "source": {"venue": "Binance USDⓈ-M", "market": "USDT perpetual", "interval": "1h",
                   "base_url": BASE_URL, "vintage": "current retrospective archive; publication-time vintage unknown",
                   "published_checksum_sidecars_required": True, "api_key_required": False},
        "retained_v004": retained, "files": {asset: f"normalized/klines_1h_{asset}.csv" for asset in ASSETS},
        "coverage_path": "coverage.json", "archive_manifest_path": "archive-manifest.json",
        "outcome_calculation_performed": False,
    }
    atomic_json(out / "context-warmup-manifest.json", manifest)
    # Re-check v004 at the freeze boundary so the manifest records unchanged sources.
    retained_after = verify_retained_v004(v004_dir)
    if retained_after != retained:
        raise ValueError("retained v004 source changed during supplemental acquisition")
    digest = create_freeze(out, retained)
    return {"status": "FROZEN", "run_dir": str(out), "data_freeze_path": str(out / "data-freeze.json"),
            "data_freeze_sha256": digest, "assets": list(ASSETS),
            "observed_rows": {asset: per_asset[asset]["observed_unique_rows"] for asset in ASSETS},
            "missing_rows": {asset: per_asset[asset]["missing_rows"] for asset in ASSETS},
            "supplemental_archive_status_counts": archive_manifest.get("status_counts", {}),
            "checksum_status_counts": archive_manifest.get("published_checksum_status_counts", {})}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", default=".research-run/liquidation-context-v005")
    parser.add_argument("--v004-dir", default=".research-run/liquidation-exploratory-v004")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    result = run(Path(args.run_dir).resolve(), Path(args.v004_dir).resolve(), args.workers)
    print(json.dumps(result, indent=2, sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
