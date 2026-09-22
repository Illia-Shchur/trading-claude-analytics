#!/usr/bin/env python3
"""Resumable public Binance archive acquisition for liquidation exploration.

Downloads USDT-M hourly klines from monthly archives (plus daily archives for
the unfinished end month) and five-minute futures metrics from daily archives.
All physical bytes, receipts, normalized tables, and missingness reports live in
the gitignored .research-run/liquidation-exploratory-v003/ tree.

This captures the current public archive vintage. It does not establish the
original publication time or historical vintage of any archive row.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import math
import os
import re
import shutil
import time
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any


ASSETS = ("BTC", "ETH", "SOL", "AAVE")
BASE_URL = "https://data.binance.vision/data/futures/um"
METRIC_COLUMNS = (
    "count_toptrader_long_short_ratio",
    "sum_toptrader_long_short_ratio",
    "count_long_short_ratio",
    "sum_taker_long_short_vol_ratio",
)
KLINE_COLUMNS = (
    "open_time", "symbol", "open", "high", "low", "close", "base_volume",
    "close_time", "quote_volume", "trade_count", "taker_buy_base_volume",
    "taker_buy_quote_volume",
)
OI_COLUMNS = ("time", "symbol", "sum_open_interest", "sum_open_interest_value", *METRIC_COLUMNS)


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify_sha256(path: Path, expected: str, description: str) -> str:
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError(f"{description} checksum mismatch: expected {expected}, found {actual}")
    return actual


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def month_sequence(start: dt.date, end: dt.date):
    y, m = start.year, start.month
    while (y, m) <= (end.year, end.month):
        yield y, m
        if m == 12:
            y, m = y + 1, 1
        else:
            m += 1


def archive_tasks(start: dt.date, end: dt.date, out: Path):
    tasks = []
    for asset in ASSETS:
        symbol = asset + "USDT"
        for y, m in month_sequence(start, end):
            month = f"{y:04d}-{m:02d}"
            # Complete months before end month use monthly archives. For the end
            # month, daily archives avoid downloading unneeded future dates.
            if (y, m) != (end.year, end.month):
                rel = f"monthly/klines/{symbol}/1h/{symbol}-1h-{month}.zip"
                tasks.append(task("klines_1h", asset, month, "monthly", rel, out))
        if (start.year, start.month) == (end.year, end.month):
            daily_start = start
        else:
            daily_start = dt.date(end.year, end.month, 1)
        d = daily_start
        while d <= end:
            rel = f"daily/klines/{symbol}/1h/{symbol}-1h-{d.isoformat()}.zip"
            tasks.append(task("klines_1h", asset, d.isoformat(), "daily", rel, out))
            d += dt.timedelta(days=1)
        d = start
        while d <= end:
            rel = f"daily/metrics/{symbol}/{symbol}-metrics-{d.isoformat()}.zip"
            tasks.append(task("oi_5m", asset, d.isoformat(), "daily", rel, out))
            d += dt.timedelta(days=1)
    return tasks


def task(kind: str, asset: str, period: str, cadence: str, rel: str, out: Path):
    return {
        "id": f"{kind}:{asset}:{cadence}:{period}",
        "kind": kind,
        "asset": asset,
        "period": period,
        "cadence": cadence,
        "relative_url": rel,
        "url": f"{BASE_URL}/{rel}",
        "path": out / "raw" / "binance" / rel,
    }


def validate_zip(path: Path) -> tuple[bool, str | None]:
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            if len(names) != 1:
                return False, f"expected one CSV member, found {len(names)}"
            if zf.testzip() is not None:
                return False, "zip CRC error"
            return True, names[0]
    except Exception as e:
        return False, str(e)


def check_cached_archive(t: dict[str, Any], previous: dict[str, Any], refresh: bool) -> bool:
    path: Path = t["path"]
    if not path.is_file():
        return False
    ok, _ = validate_zip(path)
    if not ok:
        path.unlink(missing_ok=True)
        return False
    actual = sha256_file(path)
    expected = previous.get("sha256")
    if expected and actual != expected:
        if not refresh:
            raise ValueError(f"cached archive SHA-256 mismatch for {t['id']}: receipt={expected}, bytes={actual}; pass --refresh to replace")
        path.unlink(missing_ok=True)
        return False
    return True


def fetch_one(t: dict[str, Any], previous: dict[str, Any], retry_missing: bool, refresh: bool) -> dict[str, Any]:
    path: Path = t["path"]
    if path.is_file():
        if check_cached_archive(t, previous, refresh):
            _, member = validate_zip(path)
            return {"id": t["id"], "status": "DOWNLOADED" if previous.get("status") == "DOWNLOADED" else "CACHED",
                    "url": t["url"], "path": str(path), "bytes": path.stat().st_size,
                    "sha256": sha256_file(path), "member": member, "attempts": 0}
    if previous.get("status") == "NOT_FOUND" and not retry_missing:
        return previous

    path.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(t["url"], headers={"User-Agent": "liquidation-exploratory-v003/1.0"})
    last_error = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                body = response.read()
                status_code = response.status
            temp = path.with_suffix(path.suffix + ".part")
            temp.write_bytes(body)
            ok, member = validate_zip(temp)
            if not ok:
                temp.unlink(missing_ok=True)
                raise ValueError(f"invalid ZIP: {member}")
            os.replace(temp, path)
            return {"id": t["id"], "status": "DOWNLOADED", "http_status": status_code,
                    "url": t["url"], "path": str(path), "bytes": len(body),
                    "sha256": sha256_bytes(body), "member": member, "attempts": attempt}
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"id": t["id"], "status": "NOT_FOUND", "http_status": 404,
                        "url": t["url"], "attempts": attempt, "checked_at": utcnow()}
            last_error = f"HTTP {e.code}: {e.reason}"
        except Exception as e:
            last_error = f"{type(e).__name__}: {e}"
        if attempt < 4:
            time.sleep((0.2, 0.8, 2.0)[attempt - 1])
    return {"id": t["id"], "status": "ERROR", "url": t["url"], "attempts": 4,
            "error": last_error or "unknown error", "checked_at": utcnow()}


def fetch_published_checksum(t: dict[str, Any], record: dict[str, Any], refresh: bool) -> dict[str, Any]:
    archive_hash = record.get("sha256")
    if not archive_hash:
        return {"status": "UNVERIFIED_NO_ARCHIVE_HASH"}
    path: Path = t["path"].with_name(t["path"].name + ".CHECKSUM")
    url = t["url"] + ".CHECKSUM"
    prior = record.get("published_checksum", {})
    if path.is_file() and prior.get("sha256"):
        actual_sidecar_hash = sha256_file(path)
        if actual_sidecar_hash != prior["sha256"]:
            if not refresh:
                return {"status": "ERROR", "url": url, "error": "cached CHECKSUM sidecar SHA-256 differs from receipt"}
            path.unlink(missing_ok=True)
    if path.is_file():
        body = path.read_bytes()
        match = re.search(rb"\b([0-9a-fA-F]{64})\b", body)
        if match is not None:
            expected = match.group(1).decode("ascii").lower()
            return {"status": "VERIFIED" if expected == archive_hash.lower() else "MISMATCH",
                    "url": url, "path": str(path), "bytes": len(body), "sha256": sha256_bytes(body),
                    "published_sha256": expected, "archive_sha256": archive_hash, "attempts": 0}
    req = urllib.request.Request(url, headers={"User-Agent": "liquidation-exploratory-v003/1.0"})
    last_error = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                body = response.read()
                http_status = response.status
            match = re.search(rb"\b([0-9a-fA-F]{64})\b", body)
            if match is None:
                return {"status": "INVALID", "url": url, "http_status": http_status,
                        "bytes": len(body), "body_preview": body[:200].decode("utf-8", "replace"), "attempts": attempt}
            expected = match.group(1).decode("ascii").lower()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(body)
            return {"status": "VERIFIED" if expected == archive_hash.lower() else "MISMATCH",
                    "url": url, "http_status": http_status, "path": str(path), "bytes": len(body),
                    "sha256": sha256_bytes(body), "published_sha256": expected,
                    "archive_sha256": archive_hash, "attempts": attempt}
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {"status": "NOT_FOUND", "url": url, "http_status": 404, "attempts": attempt}
            last_error = f"HTTP {e.code}: {e.reason}"
        except Exception as e:
            last_error = f"{type(e).__name__}: {e}"
        if attempt < 4:
            time.sleep((0.2, 0.8, 2.0)[attempt - 1])
    return {"status": "ERROR", "url": url, "attempts": 4, "error": last_error or "unknown error"}


def probe_monthly_metrics() -> dict[str, Any]:
    url = f"{BASE_URL}/monthly/metrics/BTCUSDT/BTCUSDT-metrics-2023-01.zip"
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "liquidation-exploratory-v003/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return {"url": url, "http_status": response.status, "content_length": response.headers.get("Content-Length")}
    except urllib.error.HTTPError as e:
        return {"url": url, "http_status": e.code, "detail": str(e.reason)}
    except Exception as e:
        return {"url": url, "error": f"{type(e).__name__}: {e}"}


def archive_csv(path: Path):
    with zipfile.ZipFile(path) as zf:
        name = zf.namelist()[0]
        with zf.open(name) as raw:
            text = io.TextIOWrapper(raw, encoding="utf-8", newline="")
            yield from csv.reader(text)


def to_iso_ms(raw: str) -> str:
    ms = int(raw)
    return dt.datetime.fromtimestamp(ms / 1000, tz=dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def expected_days(start: dt.date, end: dt.date):
    d = start
    while d <= end:
        yield d
        d += dt.timedelta(days=1)


def contiguous_gaps(times: set[dt.datetime], start: dt.datetime, end_exclusive: dt.datetime, step: dt.timedelta):
    gaps = []
    current = start
    gap_start = None
    gap_count = 0
    while current < end_exclusive:
        if current not in times:
            if gap_start is None:
                gap_start = current
            gap_count += 1
        elif gap_start is not None:
            gaps.append({"start": gap_start.isoformat().replace("+00:00", "Z"), "count": gap_count,
                         "end_exclusive": current.isoformat().replace("+00:00", "Z")})
            gap_start, gap_count = None, 0
        current += step
    if gap_start is not None:
        gaps.append({"start": gap_start.isoformat().replace("+00:00", "Z"), "count": gap_count,
                     "end_exclusive": end_exclusive.isoformat().replace("+00:00", "Z")})
    return gaps


def write_oi(asset: str, start: dt.date, end: dt.date, manifest: dict[str, Any], out: Path):
    path = out / "normalized" / f"oi_5m_{asset}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.with_suffix(".csv.gz").unlink(missing_ok=True)
    day_times: dict[str, set[dt.datetime]] = {}
    conflicting: dict[str, set[dt.datetime]] = {}
    duplicate_identical = duplicate_conflicting = offday = malformed = invalid_optional_cells = 0
    with path.open("w", encoding="utf-8", newline="") as normalized_file:
        writer = csv.DictWriter(normalized_file, fieldnames=OI_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for day in expected_days(start, end):
            key = f"oi_5m:{asset}:daily:{day.isoformat()}"
            rec = manifest.get("archives", {}).get(key, {})
            archive = out / "raw" / "binance" / "daily" / "metrics" / f"{asset}USDT" / f"{asset}USDT-metrics-{day.isoformat()}.zip"
            if not archive.exists() or rec.get("status") not in {"DOWNLOADED", "CACHED"}:
                continue
            rows_for_day: dict[dt.datetime, list[str]] = {}
            conflicts_for_day: set[dt.datetime] = set()
            try:
                rows = iter(archive_csv(archive))
                header = next(rows, None)
                expected_header = ["create_time", "symbol", "sum_open_interest", "sum_open_interest_value", *METRIC_COLUMNS]
                if header != expected_header:
                    malformed += 1
                    continue
                for row in rows:
                    if len(row) < len(expected_header):
                        malformed += 1
                        continue
                    try:
                        dt_value = dt.datetime.strptime(row[0], "%Y-%m-%d %H:%M:%S").replace(tzinfo=dt.timezone.utc)
                    except ValueError:
                        malformed += 1
                        continue
                    if dt_value.date() != day:
                        offday += 1
                        continue
                    if dt_value.minute % 5 != 0 or dt_value.second != 0 or dt_value.microsecond != 0:
                        malformed += 1
                        continue
                    if row[1].strip() != asset + "USDT":
                        malformed += 1
                        continue
                    try:
                        oi_base = float(row[2])
                        if not math.isfinite(oi_base) or oi_base <= 0:
                            raise ValueError("non-finite or non-positive base open interest")
                    except ValueError:
                        malformed += 1
                        continue
                    clean_row = row[:len(expected_header)]
                    for column_index in range(3, len(expected_header)):
                        value = clean_row[column_index].strip()
                        if not value:
                            continue
                        try:
                            valid_optional = math.isfinite(float(value))
                        except ValueError:
                            valid_optional = False
                        if not valid_optional:
                            clean_row[column_index] = ""
                            invalid_optional_cells += 1
                    if dt_value in conflicts_for_day:
                        duplicate_conflicting += 1
                        continue
                    prior = rows_for_day.get(dt_value)
                    if prior is None:
                        rows_for_day[dt_value] = clean_row
                    elif prior == clean_row:
                        duplicate_identical += 1
                    else:
                        duplicate_conflicting += 1
                        conflicts_for_day.add(dt_value)
                        rows_for_day.pop(dt_value, None)
            except Exception as e:
                malformed += 1
                manifest.setdefault("normalization_errors", []).append({"archive": str(archive), "error": f"{type(e).__name__}: {e}"})
            for dt_value in sorted(rows_for_day):
                row = rows_for_day[dt_value]
                timestamp = dt_value.isoformat(timespec="seconds").replace("+00:00", "Z")
                writer.writerow({"time": timestamp, "symbol": row[1], "sum_open_interest": row[2],
                                 "sum_open_interest_value": row[3], **dict(zip(METRIC_COLUMNS, row[4:8]))})
            day_times[day.isoformat()] = set(rows_for_day)
            conflicting[day.isoformat()] = conflicts_for_day
    expected_total = sum(288 for _ in expected_days(start, end))
    observed_unique = sum(len(ts) for ts in day_times.values())
    missing_dates = []
    missing_rows = []
    missing_intervals = []
    for day in expected_days(start, end):
        k = day.isoformat()
        times = day_times.get(k, set())
        expected_for_day = 288
        absent = expected_for_day - len(times)
        if absent > 0:
            missing_dates.append(k)
            missing_rows.append({"date": k, "expected_rows": expected_for_day, "observed_unique_rows": len(times), "missing_rows": absent,
                                "conflicting_duplicate_timestamps": len(conflicting.get(k, set()))})
        missing_intervals.extend(contiguous_gaps(
            times,
            dt.datetime.combine(day, dt.time(), tzinfo=dt.timezone.utc),
            dt.datetime.combine(day + dt.timedelta(days=1), dt.time(), tzinfo=dt.timezone.utc),
            dt.timedelta(minutes=5),
        ))
    return {"path": str(path), "sha256": sha256_file(path), "expected_rows": expected_total,
            "observed_rows": observed_unique, "observed_unique_timestamps": observed_unique,
            "missing_rows": expected_total - observed_unique, "identical_duplicate_rows_ignored": duplicate_identical,
            "conflicting_duplicate_rows_rejected": duplicate_conflicting, "offday_rows_rejected": offday,
            "malformed_archives_or_rows_rejected": malformed, "invalid_optional_cells_blank": invalid_optional_cells,
            "missing_days": len(missing_dates),
            "missing_dates": missing_dates, "per_day": missing_rows, "missing_intervals": missing_intervals}


def month_bounds(y: int, m: int):
    start = dt.datetime(y, m, 1, tzinfo=dt.timezone.utc)
    if m == 12:
        end = dt.datetime(y + 1, 1, 1, tzinfo=dt.timezone.utc)
    else:
        end = dt.datetime(y, m + 1, 1, tzinfo=dt.timezone.utc)
    return start, end


def write_klines(asset: str, start: dt.date, end: dt.date, manifest: dict[str, Any], out: Path):
    path = out / "normalized" / f"klines_1h_{asset}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.with_suffix(".csv.gz").unlink(missing_ok=True)
    # A month has at most 744 bars, so retain normalized rows until duplicate
    # conflicts are resolved, then emit one chronologically sorted file.
    rows_by_month: dict[str, dict[dt.datetime, dict[str, str]]] = {}
    conflicts_by_month: dict[str, set[dt.datetime]] = {}
    duplicate_identical = duplicate_conflicting = off_period = outside_window = malformed = 0
    for y, m in month_sequence(start, end):
        month = f"{y:04d}-{m:02d}"
        if (y, m) == (end.year, end.month):
            daily_start = max(dt.date(y, m, 1), start)
            periods = [d.isoformat() for d in expected_days(daily_start, end)]
            cadence = "daily"
        else:
            periods = [month]
            cadence = "monthly"
        for period in periods:
            key = f"klines_1h:{asset}:{cadence}:{period}"
            rec = manifest.get("archives", {}).get(key, {})
            archive = out / "raw" / "binance" / cadence / "klines" / f"{asset}USDT" / "1h" / f"{asset}USDT-1h-{period}.zip"
            if not archive.exists() or rec.get("status") not in {"DOWNLOADED", "CACHED"}:
                continue
            rows = rows_by_month.setdefault(month, {})
            conflicts = conflicts_by_month.setdefault(month, set())
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
                            raise ValueError("open timestamp is not hourly aligned")
                        stamp = dt.datetime.fromtimestamp(open_ms / 1000, tz=dt.timezone.utc)
                    except (ValueError, OverflowError, OSError):
                        malformed += 1
                        continue
                    if cadence == "monthly" and stamp.strftime("%Y-%m") != period:
                        off_period += 1
                        continue
                    if cadence == "daily" and stamp.date().isoformat() != period:
                        off_period += 1
                        continue
                    if stamp.date() < start or stamp.date() > end:
                        outside_window += 1
                        continue
                    try:
                        o, h, l, c = (float(row[i]) for i in (1, 2, 3, 4))
                        base_volume = float(row[5])
                        if (not all(math.isfinite(v) and v > 0 for v in (o, h, l, c))
                                or h < max(o, c) or l > min(o, c) or h < l
                                or not math.isfinite(base_volume) or base_volume < 0):
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
                    if stamp in conflicts:
                        duplicate_conflicting += 1
                        continue
                    prior = rows.get(stamp)
                    if prior is None:
                        rows[stamp] = normalized
                    elif prior == normalized:
                        duplicate_identical += 1
                    else:
                        duplicate_conflicting += 1
                        conflicts.add(stamp)
                        rows.pop(stamp, None)
            except Exception as e:
                malformed += 1
                manifest.setdefault("normalization_errors", []).append({"archive": str(archive), "error": f"{type(e).__name__}: {e}"})
    by_month: dict[str, set[dt.datetime]] = {month: set(rows) for month, rows in rows_by_month.items()}
    with path.open("w", encoding="utf-8", newline="") as normalized_file:
        writer = csv.DictWriter(normalized_file, fieldnames=KLINE_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for month in sorted(rows_by_month):
            for stamp in sorted(rows_by_month[month]):
                writer.writerow(rows_by_month[month][stamp])
    expected_total = 0
    missing_dates = []
    missing_rows = []
    missing_intervals = []
    for month_y, month_m in month_sequence(start, end):
        month = f"{month_y:04d}-{month_m:02d}"
        first, end_exclusive = month_bounds(month_y, month_m)
        window_start = max(first, dt.datetime.combine(start, dt.time(), tzinfo=dt.timezone.utc))
        window_end = min(end_exclusive, dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), tzinfo=dt.timezone.utc))
        slots = int((window_end - window_start).total_seconds() // 3600)
        expected_total += slots
        times = by_month.get(month, set())
        missing_intervals.extend(contiguous_gaps(times, window_start, window_end, dt.timedelta(hours=1)))
        day = window_start.date()
        while day < window_end.date() or (day == window_end.date() and window_end.hour > 0):
            ds = dt.datetime.combine(day, dt.time(), tzinfo=dt.timezone.utc)
            de = ds + dt.timedelta(days=1)
            a, b = max(ds, window_start), min(de, window_end)
            expected = int((b - a).total_seconds() // 3600)
            unique = sum(1 for x in times if a <= x < b)
            absent = expected - unique
            if absent > 0:
                missing_dates.append(day.isoformat())
            missing_rows.append({"date": day.isoformat(), "expected_rows": expected, "observed_unique_rows": unique, "missing_rows": absent,
                                "conflicting_duplicate_timestamps": sum(1 for x in conflicts_by_month.get(month, set()) if a <= x < b)})
            day += dt.timedelta(days=1)
    observed_unique = sum(len(x) for x in by_month.values())
    return {"path": str(path), "sha256": sha256_file(path), "expected_rows": expected_total,
            "observed_rows": observed_unique, "observed_unique_timestamps": observed_unique,
            "missing_rows": expected_total - observed_unique, "identical_duplicate_rows_ignored": duplicate_identical,
            "conflicting_duplicate_rows_rejected": duplicate_conflicting, "off_period_rows_rejected": off_period,
            "outside_window_rows_ignored": outside_window, "malformed_archives_or_rows_rejected": malformed,
            "missing_days": len(missing_dates),
            "missing_dates": missing_dates, "per_day": missing_rows, "missing_intervals": missing_intervals}


def copy_existing_inputs(out: Path, repo_root: Path):
    source = repo_root / ".research-run/liquidation-daily-stress-v002/acquisition/coinalyze-daily-acquisition.json"
    fred_source = repo_root / ".research-run/liquidation-daily-stress-v002/implementation-review/sp500-probe/fred-sp500.csv"
    fred_receipt_source = repo_root / ".research-run/liquidation-daily-stress-v002/implementation-review/sp500-probe/receipt.json"
    target_dir = out / "raw" / "v002"
    target_dir.mkdir(parents=True, exist_ok=True)
    liq_target = target_dir / source.name
    shutil.copy2(source, liq_target)
    liq = json.loads(liq_target.read_text(encoding="utf-8"))
    liq_rows = liq.get("rows", [])
    normalized_target = out / "normalized" / "daily_liquidations_v002.csv"
    normalized_target.parent.mkdir(parents=True, exist_ok=True)
    fields = ("asset", "symbol", "day_start_utc", "long_liquidations_usd", "short_liquidations_usd")
    with normalized_target.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows({k: row.get(k, "") for k in fields} for row in liq_rows)
    liq_receipt = {
        "source_path": str(source), "copied_path": str(liq_target), "source_bytes": source.stat().st_size,
        "source_sha256": sha256_file(source), "copied_sha256": sha256_file(liq_target),
        "schema": liq.get("schema"), "source": liq.get("source"), "captured_at": liq.get("captured_at"),
        "rows": len(liq_rows), "content_sha256": liq.get("content_sha256"),
        "pit_verified": liq.get("pit_verified"), "source_vintage_status": liq.get("source_vintage_status"),
        "normalized_path": str(normalized_target), "normalized_sha256": sha256_file(normalized_target),
        "normalization": "Copied row fields as-is; no gap filling or value transformations.",
    }
    atomic_json(out / "receipts" / "daily_liquidations_v002.json", liq_receipt)

    fred_target = out / "raw" / "fred" / "fred-sp500.csv"
    fred_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(fred_source, fred_target)
    fred_original_receipt = json.loads(fred_receipt_source.read_text(encoding="utf-8"))
    source_hash = verify_sha256(fred_source, fred_original_receipt.get("sha256", ""), "retained FRED source")
    if sha256_file(fred_target) != source_hash:
        raise ValueError("FRED byte-for-byte copy checksum mismatch")
    normalized_fred_target = out / "normalized" / "fred-sp500.csv"
    normalized_fred_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(fred_target, normalized_fred_target)
    if sha256_file(normalized_fred_target) != source_hash:
        raise ValueError("normalized FRED byte-for-byte copy checksum mismatch")
    receipt = {
        **fred_original_receipt,
        "source_receipt_path": str(fred_receipt_source),
        "source_path": str(fred_source),
        "copied_path": str(fred_target),
        "source_sha256_verified": True,
        "copied_sha256": sha256_file(fred_target),
        "normalized_path": str(normalized_fred_target),
        "normalized_sha256": sha256_file(normalized_fred_target),
        "normalization": "Byte-for-byte copy of retained source CSV.",
    }
    atomic_json(out / "receipts" / "fred_sp500.json", receipt)
    return {"daily_liquidations": liq_receipt, "sp500": receipt}


def write_input_manifest(out: Path) -> dict[str, Any]:
    inputs = {
        "schema": "liquidation-exploratory-input-manifest/1",
        "root": "normalized",
        "files": {
            "daily_liquidations": "daily_liquidations_v002.csv",
            "hourly_bars": {asset: f"klines_1h_{asset}.csv" for asset in ASSETS},
            "oi_5m": {asset: f"oi_5m_{asset}.csv" for asset in ASSETS},
            "sp500": "fred-sp500.csv",
        },
    }
    for filename in [inputs["files"]["daily_liquidations"], inputs["files"]["sp500"],
                     *inputs["files"]["hourly_bars"].values(), *inputs["files"]["oi_5m"].values()]:
        if not (out / "normalized" / filename).is_file():
            raise FileNotFoundError(f"input manifest points to missing file: normalized/{filename}")
    atomic_json(out / "input-manifest.json", inputs)
    return inputs


def canonical_json_bytes(value: Any) -> bytes:
    # Freeze objects contain only strings, booleans, integers, arrays and maps;
    # compact sorted-key JSON matches JsonHashes.canonicalSha256 for this shape.
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def verify_data_freeze(out: Path) -> str:
    path = out / "data-freeze.json"
    freeze = json.loads(path.read_text(encoding="utf-8"))
    if freeze.get("schema") != "liquidation-exploratory-input-freeze/1":
        raise ValueError(f"unsupported data freeze schema in {path}")
    body = dict(freeze)
    stored_content_hash = body.pop("content_sha256", None)
    if not stored_content_hash or hashlib.sha256(canonical_json_bytes(body)).hexdigest() != stored_content_hash:
        raise ValueError(f"data freeze content_sha256 mismatch in {path}")
    for relative, record in freeze.get("files", {}).items():
        relpath = Path(relative)
        if relpath.is_absolute() or ".." in relpath.parts:
            raise ValueError(f"data freeze path escapes run root: {relative}")
        physical = out / relpath
        if physical.is_symlink() or not physical.is_file() or physical.stat().st_size != record.get("bytes") or sha256_file(physical) != record.get("sha256"):
            raise ValueError(f"immutable data freeze input changed: {relative}")
    return sha256_file(path)


def create_data_freeze(out: Path, start: str, end: str) -> str:
    path = out / "data-freeze.json"
    if path.exists():
        return verify_data_freeze(out)
    files: dict[str, dict[str, Any]] = {}
    roots = (out / "raw", out / "normalized", out / "receipts")
    for root in roots:
        if not root.exists():
            continue
        for physical in sorted(root.rglob("*")):
            if physical.is_symlink():
                raise ValueError(f"symlink in input freeze tree: {physical}")
            if physical.is_file():
                relative = physical.relative_to(out).as_posix()
                files[relative] = {"bytes": physical.stat().st_size, "sha256": sha256_file(physical)}
    for name in ("archive-manifest.json", "input-manifest.json", "coverage.json"):
        physical = out / name
        if not physical.is_file():
            raise FileNotFoundError(f"required acquisition manifest missing: {physical}")
        files[name] = {"bytes": physical.stat().st_size, "sha256": sha256_file(physical)}
    freeze = {
        "schema": "liquidation-exploratory-input-freeze/1",
        "created_at": utcnow(),
        "window_start": start,
        "window_end_inclusive": end,
        "files": files,
    }
    freeze["content_sha256"] = hashlib.sha256(canonical_json_bytes(freeze)).hexdigest()
    atomic_json(path, freeze)
    return sha256_file(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", default="2022-08-11")
    parser.add_argument("--end", default="2026-09-19")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--run-dir", default=".research-run/liquidation-exploratory-v003")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--retry-missing", action="store_true", help="recheck prior HTTP 404s")
    parser.add_argument("--refresh", action="store_true", help="replace cached archive or checksum bytes whose prior receipt hash differs")
    args = parser.parse_args()
    start, end = dt.date.fromisoformat(args.start), dt.date.fromisoformat(args.end)
    if start > end or args.workers < 1 or args.workers > 48:
        parser.error("invalid date range or worker count (allowed 1..48)")
    out = Path(args.run_dir).resolve()
    repo_root = Path(args.repo_root).resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "archive-manifest.json"
    if (out / "data-freeze.json").exists():
        freeze_sha = verify_data_freeze(out)
        print(json.dumps({"data_freeze_path": str(out / "data-freeze.json"), "data_freeze_sha256": freeze_sha,
                          "status": "verified immutable input freeze; no files rewritten"}, indent=2), flush=True)
        return 0
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        manifest = {"schema": "liquidation-exploratory-archive-manifest/1", "archives": {}}
    except json.JSONDecodeError as e:
        raise ValueError(f"refusing to replace malformed archive manifest at {manifest_path}: {e}") from e
    manifest.update({"schema": "liquidation-exploratory-archive-manifest/1", "source": "Binance public data archive",
                    "acquisition_started_at": utcnow(), "window_start": args.start,
                    "window_end_inclusive": args.end, "timezone": "UTC", "worker_limit": args.workers})
    manifest["source_probe_monthly_metrics"] = probe_monthly_metrics()
    tasks = archive_tasks(start, end, out)
    pending = []
    for t in tasks:
        prior = manifest.get("archives", {}).get(t["id"], {})
        if prior.get("status") in {"DOWNLOADED", "CACHED"} and t["path"].exists():
            if check_cached_archive(t, prior, args.refresh):
                continue
        if prior.get("status") == "NOT_FOUND" and not args.retry_missing:
            continue
        pending.append((t, prior))
    total = len(tasks)
    print(f"Archive tasks={total}; pending={len(pending)}; workers={args.workers}; monthly metrics probe={manifest['source_probe_monthly_metrics']}", flush=True)
    completed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_one, t, prior, args.retry_missing, args.refresh): t for t, prior in pending}
        for fut in as_completed(futures):
            result = fut.result()
            manifest.setdefault("archives", {})[result["id"]] = result
            completed += 1
            if completed % 50 == 0 or completed == len(pending):
                manifest["last_progress_at"] = utcnow()
                atomic_json(manifest_path, manifest)
                print(f"Completed {completed}/{len(pending)}: {result['id']} {result['status']}", flush=True)
    manifest["acquisition_finished_at"] = utcnow()
    # Add explicit records for unchanged-but-present source bytes loaded from prior runs.
    for t in tasks:
        if t["id"] not in manifest.get("archives", {}) and t["path"].exists():
            ok, member = validate_zip(t["path"])
            if ok:
                manifest.setdefault("archives", {})[t["id"]] = {
                    "id": t["id"], "status": "CACHED", "url": t["url"], "path": str(t["path"]),
                    "bytes": t["path"].stat().st_size, "sha256": sha256_file(t["path"]), "member": member, "attempts": 0,
                }

    checksum_tasks = []
    for t in tasks:
        record = manifest.get("archives", {}).get(t["id"], {})
        if record.get("status") not in {"DOWNLOADED", "CACHED"}:
            continue
        previous_checksum = record.get("published_checksum", {})
        if previous_checksum.get("status") == "NOT_FOUND" and not args.retry_missing:
            continue
        checksum_tasks.append((t, record))
    print(f"Published checksum tasks={len(checksum_tasks)}; workers={args.workers}", flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_published_checksum, t, record, args.refresh): (t, record)
                   for t, record in checksum_tasks}
        for index, future in enumerate(as_completed(futures), 1):
            t, old_record = futures[future]
            checksum = future.result()
            manifest.setdefault("archives", {}).setdefault(t["id"], old_record)["published_checksum"] = checksum
            if index % 100 == 0 or index == len(futures):
                manifest["last_checksum_progress_at"] = utcnow()
                atomic_json(manifest_path, manifest)
                print(f"Verified CHECKSUM sidecars {index}/{len(futures)}: {t['id']} {checksum['status']}", flush=True)
    atomic_json(manifest_path, manifest)

    copied = copy_existing_inputs(out, repo_root)
    coverage = {
        "schema": "liquidation-exploratory-coverage/1", "acquired_at": utcnow(),
        "window_start": args.start, "window_end_inclusive": args.end, "timezone": "UTC",
        "availability_note": "Current retrospective Binance/FRED archive bytes. Checksums bind acquired bytes, not first-publication time or past vintage.",
        "monthly_metrics_probe": manifest.get("source_probe_monthly_metrics"),
        "copied_inputs": copied,
        "datasets": {},
    }
    for asset in ASSETS:
        coverage["datasets"][asset] = {
            "oi_5m": write_oi(asset, start, end, manifest, out),
            "klines_1h": write_klines(asset, start, end, manifest, out),
        }
    coverage["input_manifest"] = write_input_manifest(out)
    failures = [x for x in manifest.get("archives", {}).values() if x.get("status") == "ERROR"]
    tally = {}
    for rec in manifest.get("archives", {}).values():
        tally[rec.get("status", "UNKNOWN")] = tally.get(rec.get("status", "UNKNOWN"), 0) + 1
    checksum_tally = {}
    for rec in manifest.get("archives", {}).values():
        checksum_status = rec.get("published_checksum", {}).get("status")
        if checksum_status:
            checksum_tally[checksum_status] = checksum_tally.get(checksum_status, 0) + 1
    manifest["status_counts"] = tally
    manifest["published_checksum_status_counts"] = checksum_tally
    atomic_json(manifest_path, manifest)
    coverage["archive_status_counts"] = tally
    coverage["published_checksum_status_counts"] = checksum_tally
    coverage["archives_without_verified_published_checksum"] = sorted(
        rec.get("id") for rec in manifest.get("archives", {}).values()
        if rec.get("status") in {"DOWNLOADED", "CACHED"}
        and rec.get("published_checksum", {}).get("status") != "VERIFIED"
    )
    atomic_json(out / "coverage.json", coverage)
    checksum_failures = [rec for rec in manifest.get("archives", {}).values()
                         if rec.get("published_checksum", {}).get("status") in {"ERROR", "INVALID", "MISMATCH"}]
    if failures or checksum_failures:
        raise RuntimeError(f"source verification failed: archive_errors={len(failures)}, checksum_errors={len(checksum_failures)}; no immutable data freeze written")
    data_freeze = create_data_freeze(out, args.start, args.end)
    print(json.dumps({"run_dir": str(out), "coverage_path": str(out / "coverage.json"),
                      "archive_manifest_path": str(manifest_path), "status_counts": tally,
                      "published_checksum_status_counts": checksum_tally,
                      "errors": len(failures) + len(checksum_failures),
                      "data_freeze_path": str(out / "data-freeze.json"),
                      "data_freeze_sha256": data_freeze,
                      "assets": {a: {k: {n: v[k] for n in ("expected_rows", "observed_rows", "missing_rows", "missing_days", "identical_duplicate_rows_ignored", "conflicting_duplicate_rows_rejected") if n in v}
                                     for k, v in coverage["datasets"][a].items()} for a in ASSETS}}, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
