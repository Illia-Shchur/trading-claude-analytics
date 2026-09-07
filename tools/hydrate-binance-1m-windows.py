#!/usr/bin/env python3
"""Fetch immutable public Binance 1m windows for admitted fixed-baseline events.

The script is a transport preparer only.  It records the exact REST source and
fails on a missing or non-contiguous bar; it does not claim historical filter
or PIT validity.  The Java fixed runner still verifies every role hash and
executes the production lifecycle before evidence is retained.
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MINUTE = 60_000
HORIZON = 240 * 60 * MINUTE
API = "https://api.binance.com/api/v3/klines"


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def milliseconds(timestamp):
    return int(datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp() * 1000)


def fetch_json(url, retries=7):
    for attempt in range(retries):
        try:
            request = Request(url, headers={"User-Agent": "trading-claude-analytics/fixed-baseline-hydrator"})
            with urlopen(request, timeout=30) as response:
                return json.loads(response.read()), response.status
        except Exception:
            if attempt + 1 == retries:
                raise
            time.sleep(min(30, 2**attempt))


def fetch_text(url, retries=7):
    for attempt in range(retries):
        try:
            request = Request(url, headers={"User-Agent": "trading-claude-analytics/fixed-baseline-hydrator"})
            with urlopen(request, timeout=30) as response:
                return response.read().decode("utf-8"), response.status
        except Exception:
            if attempt + 1 == retries:
                raise
            time.sleep(min(30, 2**attempt))


def load_non_trading_policy(path):
    if path is None:
        return {}
    policy = json.loads(Path(path).read_text())
    if (policy.get("schema") not in {"strategy-fixed-baseline-non-trading/1", "strategy-fixed-baseline-non-trading/2"}
            or policy.get("status") != "FROZEN"
            or policy.get("venue") != "BINANCE"
            or policy.get("instrument") != "BINANCE_SPOT"
            or not isinstance(policy.get("intervals"), list)):
        raise SystemExit("non-trading policy is not a typed frozen Binance spot policy")
    result = {}
    for interval in policy["intervals"]:
        if (not isinstance(interval, dict) or interval.get("reason") != "NON_TRADING"
                or interval.get("venue") != "BINANCE"
                or interval.get("instrument") != "BINANCE_SPOT"
                or not interval.get("asset") or not interval.get("symbol")
                or not isinstance(interval.get("start_ms"), int)
                or not isinstance(interval.get("end_ms"), int)
                or interval["end_ms"] <= interval["start_ms"]
                or interval["start_ms"] % MINUTE or interval["end_ms"] % MINUTE
                or not re.fullmatch(r"[a-f0-9]{64}", interval.get("archive_zip_sha256", ""))
                or not interval.get("archive_checksum_url", "").startswith("https://")):
            raise SystemExit("non-trading policy contains an incomplete or unbound interval")
        result[(interval["asset"].lower(), interval["symbol"].upper())] = interval
    return result


def verify_archive_checksum(interval, verified):
    key = (interval["asset"].lower(), interval["symbol"].upper())
    if key in verified:
        return verified[key]
    body, status = fetch_text(interval["archive_checksum_url"])
    expected = interval["archive_zip_sha256"]
    if status != 200 or not re.search(r"\b" + re.escape(expected) + r"\b", body, re.IGNORECASE):
        raise RuntimeError(f"{interval['asset']}: monthly archive checksum receipt does not contain the frozen ZIP SHA")
    receipt = {"asset": interval["asset"], "symbol": interval["symbol"],
               "archive_month": interval.get("archive_month"),
               "archive_checksum_url": interval["archive_checksum_url"],
               "archive_zip_sha256": expected,
               "checksum_response_sha256": hashlib.sha256(body.encode()).hexdigest()}
    verified[key] = receipt
    return receipt


def closure_for(event, policy):
    interval = policy.get((event["asset"].lower(), event["symbol"].upper()))
    if not interval:
        return None
    start = milliseconds(event["decision_time"])
    end = start + HORIZON
    return interval if interval["start_ms"] < end and interval["end_ms"] > start else None


def expected_rows_for(event, policy):
    interval = closure_for(event, policy)
    if not interval:
        return 14_400
    start = milliseconds(event["decision_time"])
    end = start + HORIZON
    overlap_start = max(start, interval["start_ms"])
    overlap_end = min(end, interval["end_ms"])
    return 14_400 - max(0, (overlap_end - overlap_start) // MINUTE)


def validate_reused_rows(event, rows, policy):
    """Reopen a cached role and prove its grid before accepting reuse."""
    if not isinstance(rows, list) or len(rows) != expected_rows_for(event, policy):
        raise RuntimeError(f"{event['episode_id']}: existing role has the wrong row count")
    interval = closure_for(event, policy)
    cursor = milliseconds(event["decision_time"])
    end = cursor + HORIZON
    for row in rows:
        if not isinstance(row, dict) or row.get("asset", "").lower() != event["asset"].lower() \
                or row.get("symbol", "").upper() != event["symbol"].upper():
            raise RuntimeError(f"{event['episode_id']}: existing role has an unbound asset/symbol")
        if interval and interval["start_ms"] <= cursor < interval["end_ms"]:
            cursor = interval["end_ms"]
        opened = milliseconds(row.get("open_time", row.get("event_time", "")))
        if opened != cursor:
            raise RuntimeError(f"{event['episode_id']}: existing role is not a contiguous declared grid")
        cursor += MINUTE
    if cursor != end:
        raise RuntimeError(f"{event['episode_id']}: existing role does not end at the frozen horizon")


def hydrate(event, output, policy, verified_checksums):
    episode = event["episode_id"]
    start = milliseconds(event["decision_time"])
    end = start + HORIZON
    safe = hashlib.sha256(episode.encode()).hexdigest()
    path = output / f"bars-{safe}.json"
    if path.exists():
        rows = json.loads(path.read_text())
        validate_reused_rows(event, rows, policy)
        return event, path, rows, 0, "REUSED_IMMUTABLE", []

    cursor = start
    rows = []
    requests = 0
    missing_intervals = []
    interval_policy = closure_for(event, policy)
    expected_rows = expected_rows_for(event, policy)
    while cursor < end:
        upper = min(end - 1, cursor + 1000 * MINUTE - 1)
        query = urlencode({"symbol": event["symbol"], "interval": "1m", "startTime": cursor,
                           "endTime": upper, "limit": 1000})
        values, status = fetch_json(API + "?" + query)
        requests += 1
        if status != 200 or not isinstance(values, list):
            raise RuntimeError(f"{episode}: empty/non-200 response at {iso(cursor)}")
        if not values:
            if interval_policy and cursor == interval_policy["start_ms"]:
                missing_intervals.append(verify_archive_checksum(interval_policy, verified_checksums))
                cursor = interval_policy["end_ms"]
                continue
            raise RuntimeError(f"{episode}: empty/non-200 response at {iso(cursor)}")
        expected = cursor
        for value in values:
            if not isinstance(value, list) or len(value) < 7:
                raise RuntimeError(f"{episode}: malformed Binance kline")
            opened = int(value[0])
            if opened != expected:
                if (interval_policy and expected == interval_policy["start_ms"]
                        and opened == interval_policy["end_ms"]):
                    missing_intervals.append(verify_archive_checksum(interval_policy, verified_checksums))
                    expected = opened
                else:
                    raise RuntimeError(f"{episode}: gap/duplicate at {iso(expected)} got {iso(opened)}")
            if interval_policy and interval_policy["start_ms"] <= opened < interval_policy["end_ms"]:
                raise RuntimeError(f"{episode}: physical bars contradict the declared non-trading interval at {iso(opened)}")
            close = int(value[6])
            rows.append({"event_time": iso(opened), "open_time": iso(opened), "close_time": iso(close),
                         "availability_time": iso(close), "asset": event["asset"], "symbol": event["symbol"],
                         "venue": "BINANCE", "instrument": "BINANCE_SPOT", "open": float(value[1]),
                         "high": float(value[2]), "low": float(value[3]), "close": float(value[4]),
                         "volume": float(value[5]), "source": "binance-public-spot-klines/1",
                         "historical_filter_status": "USER_BOUND_RETROSPECTIVE"})
            expected += MINUTE
        cursor = expected
    if len(rows) != expected_rows or cursor != end:
        raise RuntimeError(f"{episode}: expected {expected_rows} physical bars after declared closures, got {len(rows)}")

    payload = json.dumps(rows, indent=2, ensure_ascii=False) + "\n"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(payload)
    return event, path, rows, requests, "FETCHED_HTTP_200", missing_intervals


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, help="fixed-baseline inventory containing setup_events")
    parser.add_argument("--out", required=True, help="immutable role directory")
    parser.add_argument("--non-trading-policy", type=Path,
                        help="typed frozen Binance spot closure policy for declared missing minutes")
    arguments = parser.parse_args()
    inventory = json.loads(Path(arguments.inventory).read_text())
    output = Path(arguments.out)
    output.mkdir(parents=True, exist_ok=True)
    events = list(inventory.get("setup_events", [])) + list(inventory.get("control_events", []))
    if len(events) != len({event.get("episode_id") for event in events}):
        raise SystemExit("hydration inventory contains duplicate episode ids")
    policy = load_non_trading_policy(arguments.non_trading_policy)
    verified_checksums = {}
    # Resolve the four known closure receipts once, before concurrent REST
    # work begins.  This makes the fallback deterministic and keeps every
    # worker on the same typed monthly-archive evidence.
    for interval in sorted(policy.values(), key=lambda row: (row["asset"], row["symbol"])):
        verify_archive_checksum(interval, verified_checksums)
    results, failures = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
        futures = [workers.submit(hydrate, event, output, policy, verified_checksums) for event in events]
        for index, future in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                event, path, rows, requests, status, missing_intervals = future.result()
                results.append({"episode_id": event["episode_id"], "asset": event["asset"],
                                "symbol": event["symbol"], "decision_time": event["decision_time"],
                                "path": path.name, "row_count": len(rows), "request_count": requests,
                                "status": status, "rows_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                "expected_row_count": expected_rows_for(event, policy),
                                "missing_intervals": missing_intervals})
                print(f"[{index}/{len(futures)}] {event['episode_id']} {status}", file=sys.stderr, flush=True)
            except Exception as error:
                failures.append(str(error))
                print(f"[{index}/{len(futures)}] FAILED {error}", file=sys.stderr, flush=True)
    results.sort(key=lambda row: row["episode_id"])
    manifest = {"schema": "fixed-baseline-public-hydration-attempt/1", "source": API,
                "transport": "HTTPS_BINANCE_PUBLIC_REST", "pit_status": "UNVERIFIED_DEVELOPMENT_ONLY",
                "historical_filter_status": "USER_BOUND_RETROSPECTIVE", "horizon_minutes": 14_400,
                "event_count": len(results), "events": results, "failures": failures,
                "closure_checksum_receipts": sorted(verified_checksums.values(), key=lambda row: row["asset"])}
    (output / "hydration-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if failures:
        raise SystemExit("\n".join(failures))
    print(json.dumps({"event_count": len(results), "rows": sum(row["row_count"] for row in results),
                      "out": str(output)}))


if __name__ == "__main__":
    main()
