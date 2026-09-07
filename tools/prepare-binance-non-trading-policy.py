#!/usr/bin/env python3
"""Write the frozen Binance spot-closure receipt used by fixed-baseline runs.

This policy covers only the documented Binance spot suspension announced at
2021-09-29 07:00 UTC and the first reopening bar observed at 09:00 UTC in
the public monthly archive. It authorizes a missing physical interval for
lifecycle validation; it never creates bars or fills. Unknown gaps remain
failures.
"""

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

NOTICE = "https://www.binance.com/en/support/announcement/detail/e2f674fc961d48af9b28edd82896607c"
START = int(datetime(2021, 9, 29, 7, tzinfo=timezone.utc).timestamp() * 1000)
END = int(datetime(2021, 9, 29, 9, tzinfo=timezone.utc).timestamp() * 1000)
CHECKSUMS = {
    "aave": ("AAVEUSDT", "aae06b458cc5bafd4ac57c575204640e8c4131c1076aed9bb4d374ae2fa92ed4"),
    "bnb": ("BNBUSDT", "848a9f6d7dbe4e817ee8c2f2ee9c2ef95eb89bb09bf5ffb34e296ad885ff6b0c"),
    "eth": ("ETHUSDT", "5bbfb9f41522b2fd1472e4f4b164a5ba4711b02aab37eebe0b2b25a1286c90a9"),
    "link": ("LINKUSDT", "b9f4dc36fccd646e86bc7fcadc82fb7a8df68752ed5b280925d278d110990a59"),
}


def own_hash(value):
    body = dict(value)
    body.pop("content_sha256", None)
    encoded = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    intervals = []
    for asset, (symbol, archive_sha256) in sorted(CHECKSUMS.items()):
        intervals.append({
            "asset": asset,
            "symbol": symbol,
            "venue": "BINANCE",
            "instrument": "BINANCE_SPOT",
            "start_ms": START,
            "end_ms": END,
            "reason": "NON_TRADING",
            "notice_url": NOTICE,
            "archive_month": "2021-09",
            "archive_checksum_url": (
                f"https://data.binance.vision/data/spot/monthly/klines/{symbol}/1m/"
                f"{symbol}-1m-2021-09.zip.CHECKSUM"
            ),
            "archive_zip_sha256": archive_sha256,
        })
    policy = {
        "schema": "strategy-fixed-baseline-non-trading/2",
        "version": 2,
        "status": "FROZEN",
        "policy_id": "NON_TRADING_CLOSURE_V002_ERRATUM",
        "provenance": "OFFICIAL_BINANCE_NOTICE_AND_PUBLIC_MONTHLY_ARCHIVE_CHECKSUMS",
        "venue": "BINANCE",
        "instrument": "BINANCE_SPOT",
        "asset_scope": "EXPLICIT_INTERVAL_ASSETS_ONLY",
        "frozen_before_outcome_read": False,
        "amendment_scope": "POST_INITIAL_EXPOSURE_BEFORE_RESOLVING_INTERRUPTED_LIFECYCLES",
        "frozen_before_interrupted_outcome_resolution": True,
        "interval_semantics": "[start_ms,end_ms)",
        "missing_bar_policy": "NO_SYNTHETIC_BARS_OR_FILLS;_RESUME_AT_FIRST_REOPENING_BAR",
        "notice_url": NOTICE,
        "notice_statement": "Official notice announces suspension at 07:00 UTC and estimates approximately two hours; the archive shows the first covered reopening bar at 09:00 UTC",
        "intervals": intervals,
    }
    policy["content_sha256"] = own_hash(policy)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(policy, indent=2, ensure_ascii=False) + "\n").encode()
    if args.out.exists() and args.out.read_bytes() != encoded:
        raise SystemExit(f"refusing to overwrite immutable policy with different bytes: {args.out}")
    args.out.write_bytes(encoded)
    print(json.dumps({"path": str(args.out), "content_sha256": policy["content_sha256"],
                      "byte_sha256": hashlib.sha256(encoded).hexdigest()}))


if __name__ == "__main__":
    main()
