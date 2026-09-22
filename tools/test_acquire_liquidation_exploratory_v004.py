import csv
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError


MODULE_PATH = Path(__file__).with_name("acquire_liquidation_exploratory_v004.py")
SPEC = importlib.util.spec_from_file_location("liquidation_exploratory_acquisition_v004", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    status = 200

    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return self.body


def utc_epoch(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp())


class V004AcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_v004_asset_order_and_manifest_match_frozen_universe(self):
        self.assertEqual(MODULE.ASSETS, ("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX"))
        for filename in ["daily_liquidations_v004.csv", "fred-sp500.csv"]:
            path = self.root / "normalized" / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n", encoding="utf-8")
        for asset in MODULE.ASSETS:
            for prefix in ("klines_1h", "oi_5m"):
                path = self.root / "normalized" / f"{prefix}_{asset}.csv"
                path.write_text("fixture\n", encoding="utf-8")
        manifest = MODULE.write_input_manifest(self.root)
        self.assertEqual(manifest["assets"], list(MODULE.ASSETS))
        self.assertEqual(list(manifest["files"]["hourly_bars"]), list(MODULE.ASSETS))
        self.assertEqual(manifest["files"]["daily_liquidations"], "daily_liquidations_v004.csv")

    def test_key_file_must_be_private_and_is_read_without_printing(self):
        secret = "fixture-only-key-value"
        path = self.root / "secrets" / "coinalyze.key"
        path.parent.mkdir(parents=True)
        path.write_text(secret + "\n", encoding="utf-8")
        path.chmod(0o600)
        self.assertEqual(MODULE.read_coinalyze_key(path), secret)
        path.chmod(0o644)
        with self.assertRaises(PermissionError):
            MODULE.read_coinalyze_key(path)

    def test_api_key_is_sent_only_as_header_and_is_redacted_from_errors(self):
        secret = "fixture-only-key-value"
        captured = {}

        def success(request, timeout):
            captured["request"] = request
            return FakeResponse(b"[]")

        with patch.object(MODULE.urllib.request, "urlopen", side_effect=success):
            body, status = MODULE.coinalyze_get("future-markets", {"scope": "test"}, secret)
        self.assertEqual((body, status), (b"[]", 200))
        request = captured["request"]
        self.assertNotIn(secret, request.full_url)
        self.assertTrue(any(name.lower() == "api_key" and value == secret for name, value in request.header_items()))

        def unauthorized(request, timeout):
            raise HTTPError(request.full_url, 401, "Unauthorized", {}, None)

        with patch.object(MODULE.urllib.request, "urlopen", side_effect=unauthorized):
            with self.assertRaises(RuntimeError) as error:
                MODULE.coinalyze_get("future-markets", {}, secret)
        self.assertNotIn(secret, str(error.exception))

    def test_added_asset_pull_preserves_v003_bytes_and_keeps_gaps_missing(self):
        out = self.root / "run"
        old = self.root / "v003-daily.csv"
        fields = ("asset", "symbol", "day_start_utc", "long_liquidations_usd", "short_liquidations_usd")
        old_lines = [",".join(fields) + "\r\n"]
        for asset in MODULE.ORIGINAL_ASSETS:
            old_lines.append(f"{asset},{asset}USDT_PERP.A,2022-08-11T00:00:00Z,1.25,2.5\r\n")
            old_lines.append(f"{asset},{asset}USDT_PERP.A,2022-08-12T00:00:00Z,3.75,4.5\r\n")
        original_bytes = "".join(old_lines).encode("utf-8")
        old.write_bytes(original_bytes)
        key_path = out / "secrets" / "coinalyze.key"
        key_path.parent.mkdir(parents=True)
        key_path.write_text("fixture-only-key-value\n", encoding="utf-8")
        key_path.chmod(0o600)
        market_list = [{"symbol": f"{asset}USDT_PERP.A", "exchange": "A", "base_asset": asset,
                        "quote_asset": "USDT", "is_perpetual": True, "margined": "STABLE"}
                       for asset in MODULE.ASSETS]

        def api_response(endpoint, params, key):
            self.assertNotIn(key, json.dumps(params))
            if endpoint == "future-markets":
                return json.dumps(market_list).encode(), 200
            symbol = params["symbols"]
            asset = symbol.removesuffix("USDT_PERP.A")
            history = [{"t": utc_epoch("2022-08-11"), "l": 0, "s": 2.0}]
            if asset != "ZEC":
                history.append({"t": utc_epoch("2022-08-12"), "l": 1.0, "s": 3.0})
            return json.dumps([{"symbol": symbol, "history": history}]).encode(), 200

        with patch.object(MODULE, "coinalyze_get", side_effect=api_response):
            receipt = MODULE.acquire_coinalyze(out, date(2022, 8, 11), date(2022, 8, 12), key_path, old)

        normalized = out / "normalized" / "daily_liquidations_v004.csv"
        combined = normalized.read_bytes()
        self.assertTrue(combined.startswith(original_bytes))
        self.assertEqual(receipt["original_v003_sha256"], hashlib.sha256(original_bytes).hexdigest())
        self.assertTrue(receipt["original_v003_prefix_preserved_byte_for_byte"])
        self.assertFalse(receipt["assets"]["BTC"]["used_current_response_for_v004_input"])
        self.assertTrue(receipt["assets"]["UNI"]["used_current_response_for_v004_input"])
        self.assertEqual(receipt["assets"]["ZEC"]["missing_dates"], ["2022-08-12"])
        self.assertEqual(receipt["added_asset_row_counts"]["ZEC"], 1)
        with normalized.open(newline="", encoding="utf-8") as source:
            data = list(csv.DictReader(source))
        self.assertEqual(len([row for row in data if row["asset"] == "BTC"]), 2)
        self.assertEqual(len([row for row in data if row["asset"] == "ZEC"]), 1)
        self.assertFalse(any(row["asset"] == "ZEC" and row["day_start_utc"].startswith("2022-08-12") for row in data))

        for name in ["archive-manifest.json", "input-manifest.json", "coverage.json"]:
            (out / name).write_text("{}\n", encoding="utf-8")
        freeze_sha = MODULE.create_data_freeze(out, "2022-08-11", "2022-08-12")
        freeze = json.loads((out / "data-freeze.json").read_text(encoding="utf-8"))
        self.assertEqual(MODULE.verify_data_freeze(out), freeze_sha)
        self.assertFalse(any("secrets" in Path(relative).parts for relative in freeze["files"]))
        for relative in freeze["files"]:
            self.assertNotIn(b"fixture-only-key-value", (out / relative).read_bytes())

    def _seed_cached_coinalyze_run(self, out, old, *, corrupt_asset=None):
        key_path = out / "secrets" / "coinalyze.key"
        key_path.parent.mkdir(parents=True, exist_ok=True)
        key_path.write_text("fixture-only-key-value\n", encoding="utf-8")
        key_path.chmod(0o600)
        old_rows = ["asset,symbol,day_start_utc,long_liquidations_usd,short_liquidations_usd\n"]
        old_rows.extend(
            f"{asset},{asset}USDT_PERP.A,2022-08-11T00:00:00Z,1.0,2.0\n"
            for asset in MODULE.ORIGINAL_ASSETS
        )
        old.write_text("".join(old_rows), encoding="utf-8")
        raw_root = out / "raw" / "coinalyze" / "liquidation-history"
        receipt_root = out / "receipts" / "coinalyze"
        raw_root.mkdir(parents=True, exist_ok=True)
        receipt_root.mkdir(parents=True, exist_ok=True)
        markets = [{"symbol": f"{asset}USDT_PERP.A", "exchange": "A", "base_asset": asset,
                    "quote_asset": "USDT", "is_perpetual": True, "margined": "STABLE"}
                   for asset in MODULE.ASSETS]
        retrieved_at = "2026-09-18T12:34:56Z"
        for asset in MODULE.ASSETS:
            body = json.dumps([{"symbol": f"{asset}USDT_PERP.A", "history": [
                {"t": utc_epoch("2022-08-11"), "l": 1.0, "s": 2.0}]}]).encode("utf-8")
            (raw_root / f"{asset}.json").write_bytes(body)
            receipt = {"sha256": hashlib.sha256(body).hexdigest(), "request_status": "HTTP_200",
                       "retrieved_at": retrieved_at}
            (receipt_root / f"liquidation-history-{asset}.json").write_text(
                json.dumps(receipt), encoding="utf-8")
            if asset == corrupt_asset:
                (raw_root / f"{asset}.json").write_bytes(body + b" ")
        return key_path, markets, retrieved_at

    def test_cached_coinalyze_response_requires_receipt_hash_and_preserves_fetch_time(self):
        out, old = self.root / "cached-run", self.root / "old.csv"
        key_path, markets, retrieved_at = self._seed_cached_coinalyze_run(out, old)

        def markets_only(endpoint, params, key):
            self.assertEqual(endpoint, "future-markets")
            self.assertEqual(key, "fixture-only-key-value")
            return json.dumps(markets).encode(), 200

        with patch.object(MODULE, "coinalyze_get", side_effect=markets_only):
            result = MODULE.acquire_coinalyze(out, date(2022, 8, 11), date(2022, 8, 11), key_path, old)

        for asset in MODULE.ASSETS:
            receipt = result["assets"][asset]
            self.assertEqual(receipt["request_status"], "CACHED")
            self.assertEqual(receipt["retrieved_at"], retrieved_at)
            self.assertEqual(receipt["retrieval_time_status"], "PRESERVED_PRIOR_HTTP_RECEIPT")
            self.assertTrue(receipt["verified_at"])

    def test_cached_coinalyze_response_fails_closed_on_raw_hash_mismatch(self):
        out, old = self.root / "mismatched-run", self.root / "old.csv"
        key_path, markets, _ = self._seed_cached_coinalyze_run(out, old, corrupt_asset="BTC")

        def markets_only(endpoint, params, key):
            self.assertEqual(endpoint, "future-markets")
            return json.dumps(markets).encode(), 200

        with patch.object(MODULE, "coinalyze_get", side_effect=markets_only):
            with self.assertRaisesRegex(ValueError, "no matching receipt hash for BTC"):
                MODULE.acquire_coinalyze(out, date(2022, 8, 11), date(2022, 8, 11), key_path, old)


if __name__ == "__main__":
    unittest.main()
