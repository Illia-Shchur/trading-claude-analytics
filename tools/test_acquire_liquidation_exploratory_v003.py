import csv
import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from datetime import date, datetime, timezone
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("acquire_liquidation_exploratory_v003.py")
SPEC = importlib.util.spec_from_file_location("liquidation_exploratory_acquisition", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


DAY = date(2022, 8, 11)
METRICS_HEADER = [
    "create_time", "symbol", "sum_open_interest", "sum_open_interest_value",
    "count_toptrader_long_short_ratio", "sum_toptrader_long_short_ratio",
    "count_long_short_ratio", "sum_taker_long_short_vol_ratio",
]


def metric_row(timestamp, symbol="BTCUSDT", oi="10", value="200", extras=None):
    extras = extras or ["1", "1", "1", "1"]
    return [timestamp, symbol, oi, value, *extras]


def write_zip(path: Path, member_name: str, rows: list[list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        content = "\n".join(",".join(row) for row in rows) + "\n"
        zf.writestr(member_name, content)


class ExploratoryAcquisitionNormalizationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_headered_hourly_klines_are_parsed_and_aligned(self):
        archive = self.root / "raw/binance/daily/klines/BTCUSDT/1h/BTCUSDT-1h-2022-08-11.zip"
        header = ["open_time", "open", "high", "low", "close", "volume", "close_time",
                  "quote_volume", "count", "taker_buy_volume", "taker_buy_quote_volume", "ignore"]
        start_ms = int(datetime(2022, 8, 11, tzinfo=timezone.utc).timestamp() * 1000)
        rows = [header]
        for hour in (0, 1):
            open_ms = start_ms + hour * 3_600_000
            rows.append([str(open_ms), "100", "102", "99", "101", "5", str(open_ms + 3_599_999),
                         "500", "10", "2", "200", "0"])
        write_zip(archive, "BTCUSDT-1h-2022-08-11.csv", rows)
        manifest = {"archives": {"klines_1h:BTC:daily:2022-08-11": {"status": "DOWNLOADED"}}}

        coverage = MODULE.write_klines("BTC", DAY, DAY, manifest, self.root)

        self.assertEqual(coverage["observed_unique_timestamps"], 2)
        self.assertEqual(coverage["expected_rows"], 24)
        self.assertEqual(coverage["missing_rows"], 22)
        self.assertEqual(coverage["malformed_archives_or_rows_rejected"], 0)
        with (self.root / "normalized/klines_1h_BTC.csv").open(newline="") as f:
            data = list(csv.DictReader(f))
        self.assertEqual([r["open_time"] for r in data], ["2022-08-11T00:00:00Z", "2022-08-11T01:00:00Z"])

    def test_oi_sorts_rows_rejects_bad_rows_and_classifies_duplicates(self):
        archive = self.root / "raw/binance/daily/metrics/BTCUSDT/BTCUSDT-metrics-2022-08-11.zip"
        ts00 = "2022-08-11 00:00:00"
        ts05 = "2022-08-11 00:05:00"
        ts15 = "2022-08-11 00:15:00"
        rows = [
            METRICS_HEADER,
            metric_row(ts05),
            metric_row(ts00, value="NaN", extras=["inf", "1", "1", "1"]),
            metric_row(ts05),  # Identical duplicate is counted and ignored.
            metric_row(ts15, oi="10"),
            metric_row(ts15, oi="12"),  # Conflicting duplicate removes this timestamp.
            metric_row("2022-08-12 00:00:00"),  # Wrong archive day.
            metric_row("2022-08-11 00:10:00", symbol="ETHUSDT"),
            metric_row("2022-08-11 00:20:00", oi="NaN"),  # Required base OI is invalid.
        ]
        write_zip(archive, "BTCUSDT-metrics-2022-08-11.csv", rows)
        manifest = {"archives": {"oi_5m:BTC:daily:2022-08-11": {"status": "DOWNLOADED"}}}

        coverage = MODULE.write_oi("BTC", DAY, DAY, manifest, self.root)

        self.assertEqual(coverage["observed_unique_timestamps"], 2)
        self.assertEqual(coverage["missing_rows"], 286)
        self.assertEqual(coverage["offday_rows_rejected"], 1)
        self.assertEqual(coverage["malformed_archives_or_rows_rejected"], 2)
        self.assertEqual(coverage["identical_duplicate_rows_ignored"], 1)
        self.assertEqual(coverage["conflicting_duplicate_rows_rejected"], 1)
        self.assertEqual(coverage["invalid_optional_cells_blank"], 2)
        with (self.root / "normalized/oi_5m_BTC.csv").open(newline="") as f:
            data = list(csv.DictReader(f))
        self.assertEqual([r["time"] for r in data], ["2022-08-11T00:00:00Z", "2022-08-11T00:05:00Z"])
        self.assertEqual(data[0]["sum_open_interest_value"], "")
        self.assertEqual(data[0]["count_toptrader_long_short_ratio"], "")

    def test_cached_archive_hash_mismatch_requires_explicit_refresh(self):
        archive = self.root / "BTCUSDT-metrics-2022-08-11.zip"
        write_zip(archive, "data.csv", [["a"], ["1"]])
        task = {"id": "sample", "path": archive}
        prior = {"sha256": "0" * 64}
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            MODULE.check_cached_archive(task, prior, refresh=False)
        self.assertFalse(MODULE.check_cached_archive(task, prior, refresh=True))
        self.assertFalse(archive.exists())

    def test_retained_fred_hash_mismatch_fails_closed(self):
        source = self.root / "fred-sp500.csv"
        source.write_text("observation_date,SP500\n2022-08-01,1\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            MODULE.verify_sha256(source, "f" * 64, "retained FRED source")
        self.assertEqual(MODULE.verify_sha256(source, hashlib.sha256(source.read_bytes()).hexdigest(), "fixture"),
                         hashlib.sha256(source.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
