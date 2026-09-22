import csv
import hashlib
import importlib.util
import json
import tempfile
import unittest
import urllib.request
import zipfile
from datetime import date, datetime, timezone
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("acquire_liquidation_context_v005.py")
SPEC = importlib.util.spec_from_file_location("liquidation_context_acquisition_v005", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def kline_row(instant: str) -> list[str]:
    opened = int(datetime.fromisoformat(instant.replace("Z", "+00:00")).timestamp() * 1000)
    return [str(opened), "1", "2", "0.5", "1.5", "10", str(opened + 3_599_999), "15", "10", "4", "6", "0"]


def make_zip(path: Path, rows: list[list[str]]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    body_path = path.with_suffix(".csv")
    with body_path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.writer(target)
        writer.writerows(rows)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(body_path, arcname=body_path.name)
    body_path.unlink()
    return hashlib.sha256(path.read_bytes()).hexdigest()


class V005ContextAcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_task_plan_fetches_only_april_through_july_for_fixed_universe(self):
        tasks = MODULE.make_archive_tasks(self.root / "out")
        self.assertEqual(len(tasks), 9 * 4)
        self.assertEqual({task["asset"] for task in tasks}, set(MODULE.ASSETS))
        self.assertEqual({task["period"] for task in tasks}, {"2022-04", "2022-05", "2022-06", "2022-07"})
        self.assertFalse(any("2022-08" in task["id"] for task in tasks))

    def test_cache_hit_never_resets_or_invents_archive_retrieval_time(self):
        known = MODULE.with_retrieval_provenance(
            {"status": "CACHED", "attempts": 0, "sha256": "archive-hash"},
            {"sha256": "archive-hash", "retrieved_at": "2026-09-18T11:10:00Z",
             "retrieval_time_status": "PRESERVED_PRIOR_HTTP_RECEIPT"},
            "2026-09-22T00:00:00Z",
        )
        self.assertEqual(known["retrieved_at"], "2026-09-18T11:10:00Z")
        checksum = MODULE.with_checksum_provenance(
            {"status": "VERIFIED", "attempts": 0, "sha256": "sidecar-hash"},
            {"sha256": "sidecar-hash", "retrieved_at": "2026-09-18T11:11:00Z"},
            "2026-09-22T00:00:00Z",
        )
        self.assertEqual(checksum["retrieved_at"], "2026-09-18T11:11:00Z")
        unknown = MODULE.with_retrieval_provenance(
            {"status": "CACHED", "attempts": 0, "sha256": "archive-hash"},
            {}, "2026-09-22T00:00:00Z",
        )
        self.assertIsNone(unknown["retrieved_at"])
        self.assertIn("UNKNOWN_CACHED", unknown["retrieval_time_status"])
        unknown_checksum = MODULE.with_checksum_provenance(
            {"status": "VERIFIED", "attempts": 0, "sha256": "sidecar-hash"}, {}, "2026-09-22T00:00:00Z")
        self.assertIsNone(unknown_checksum["retrieved_at"])

    def test_changed_http_download_gets_new_time_and_keeps_prior_receipt(self):
        result = MODULE.with_retrieval_provenance(
            {"status": "DOWNLOADED", "attempts": 1, "sha256": "new-archive-hash"},
            {"sha256": "old-archive-hash", "retrieved_at": "2026-09-18T11:10:00Z",
             "retrieval_time_status": "CAPTURED_AT_HTTP_RESPONSE"}, "2026-09-22T00:00:00Z")
        self.assertEqual(result["retrieved_at"], "2026-09-22T00:00:00Z")
        self.assertEqual(result["retrieval_history"][0]["sha256"], "old-archive-hash")
        checksum = MODULE.with_checksum_provenance(
            {"status": "VERIFIED", "attempts": 1, "http_status": 200, "sha256": "new-sidecar-hash"},
            {"sha256": "old-sidecar-hash", "retrieved_at": "2026-09-18T11:11:00Z"},
            "2026-09-22T00:00:01Z")
        self.assertEqual(checksum["retrieved_at"], "2026-09-22T00:00:01Z")
        self.assertEqual(checksum["retrieval_history"][0]["sha256"], "old-sidecar-hash")

    def _seed_verified_archive_cache(self, out):
        manifest = {"schema": "liquidation-context-warmup-archive-manifest/1", "archives": {}}
        tasks = MODULE.make_archive_tasks(out)
        for task in tasks:
            path = task["path"]
            digest = make_zip(path, [])
            sidecar_path = Path(str(path) + ".CHECKSUM")
            sidecar_body = f"{digest}  {path.name}\n".encode("ascii")
            sidecar_path.write_bytes(sidecar_body)
            manifest["archives"][task["id"]] = {
                "id": task["id"], "status": "DOWNLOADED", "sha256": digest, "attempts": 1,
                "retrieved_at": "2026-09-18T11:10:00Z",
                "retrieval_time_status": "CAPTURED_AT_HTTP_RESPONSE",
                "published_checksum": {"status": "VERIFIED", "sha256": hashlib.sha256(sidecar_body).hexdigest(),
                                       "published_sha256": digest, "attempts": 1, "http_status": 200,
                                       "retrieved_at": "2026-09-18T11:11:00Z"},
            }
        MODULE.atomic_json(out / "archive-manifest.json", manifest)
        return tasks, manifest

    def test_resume_refetches_missing_sidecar_even_when_receipt_says_verified(self):
        out = self.root / "resume-missing-sidecar"
        with patch.object(MODULE, "ASSETS", ("BTC",)):
            tasks, manifest = self._seed_verified_archive_cache(out)
            missing_task = next(task for task in tasks if task["period"] == "2022-06")
            sidecar = Path(str(missing_task["path"]) + ".CHECKSUM")
            sidecar.unlink()
            expected = f"{manifest['archives'][missing_task['id']]['sha256']}  {missing_task['path'].name}\n".encode("ascii")
            calls = []

            class FakeResponse:
                status = 200
                def __enter__(self): return self
                def __exit__(self, *_): return False
                def read(self): return expected

            def urlopen(request, timeout):
                calls.append(request.full_url)
                return FakeResponse()

            with patch.object(urllib.request, "urlopen", side_effect=urlopen):
                result = MODULE.acquire_archives(out, {"data_freeze_sha256": "retained-test-hash"}, workers=1)

        self.assertEqual(len(calls), 1)
        self.assertTrue(calls[0].endswith("2022-06.zip.CHECKSUM"))
        self.assertEqual(result["published_checksum_status_counts"], {"VERIFIED": 4})
        self.assertTrue(sidecar.is_file())

    def test_resume_fails_closed_on_tampered_cached_sidecar(self):
        out = self.root / "resume-tampered-sidecar"
        with patch.object(MODULE, "ASSETS", ("BTC",)):
            tasks, _ = self._seed_verified_archive_cache(out)
            tampered_task = next(task for task in tasks if task["period"] == "2022-07")
            Path(str(tampered_task["path"]) + ".CHECKSUM").write_text("tampered\n", encoding="ascii")
            with patch.object(urllib.request, "urlopen", side_effect=AssertionError("unexpected network fetch")):
                with self.assertRaisesRegex(RuntimeError, "supplemental Binance inputs failed closed"):
                    MODULE.acquire_archives(out, {"data_freeze_sha256": "retained-test-hash"}, workers=1)

    def test_normalization_keeps_gaps_missing_and_clips_august_at_exclusive_boundary(self):
        out, v004 = self.root / "context", self.root / "v004"
        archive_manifest = {"archives": {}, "reused_v004_august_archives": {}}
        for period in ("2022-04", "2022-05", "2022-06", "2022-07"):
            path = out / "raw/binance/monthly/klines/BTCUSDT/1h" / f"BTCUSDT-1h-{period}.zip"
            rows = []
            if period == "2022-04":
                rows = [kline_row("2022-04-01T00:00:00Z"), kline_row("2022-04-01T02:00:00Z")]
            digest = make_zip(path, rows)
            archive_manifest["archives"][f"klines_1h:BTC:monthly:{period}"] = {
                "status": "DOWNLOADED", "sha256": digest, "retrieved_at": "2026-09-21T00:00:00Z",
            }
        august = v004 / "raw/binance/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-2022-08.zip"
        digest = make_zip(august, [kline_row("2022-08-10T23:00:00Z"), kline_row("2022-08-11T00:00:00Z")])
        archive_manifest["reused_v004_august_archives"]["klines_1h:BTC:monthly:2022-08"] = {
            "status": "REUSED_VERIFIED_V004", "sha256": digest,
            "retrieved_at": None, "retrieval_time_status": "UNKNOWN_CACHED_RECEIPT_DID_NOT_RETAIN_ORIGINAL_FETCH_TIME",
        }

        result = MODULE.normalize_asset("BTC", out, v004, archive_manifest)
        self.assertEqual(result["observed_unique_rows"], 3)
        self.assertEqual(result["missing_rows"], 3165)
        self.assertTrue(result["missing_intervals"])
        self.assertIn("2022-04-01", result["missing_dates"])
        self.assertEqual(result["outside_window_rows_ignored"], 1)
        self.assertFalse(result["zero_fill_applied"])
        with (out / result["path"]).open(encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source)
            self.assertEqual(tuple(reader.fieldnames), MODULE.KLINE_COLUMNS)
            rows = list(reader)
        self.assertEqual([row["open_time"] for row in rows], [
            "2022-04-01T00:00:00Z", "2022-04-01T02:00:00Z", "2022-08-10T23:00:00Z",
        ])

    def test_retained_v004_hash_and_august_sidecar_are_verified_before_reuse(self):
        v004 = self.root / "v004"
        normalized = v004 / "normalized"
        archive_root = v004 / "raw/binance/monthly/klines"
        normalized.mkdir(parents=True)
        (v004 / "raw").mkdir(exist_ok=True)
        files = {"hourly_bars": {}}
        archives = {}
        for asset in MODULE.ASSETS:
            name = f"klines_1h_{asset}.csv"
            (normalized / name).write_text(",".join(MODULE.KLINE_COLUMNS) + "\n", encoding="utf-8")
            files["hourly_bars"][asset] = name
            archive = archive_root / f"{asset}USDT/1h/{asset}USDT-1h-2022-08.zip"
            digest = make_zip(archive, [kline_row("2022-08-01T00:00:00Z")])
            checksum = Path(str(archive) + ".CHECKSUM")
            checksum.write_text(f"{digest}  {archive.name}\n", encoding="ascii")
            archives[f"klines_1h:{asset}:monthly:2022-08"] = {
                "id": f"klines_1h:{asset}:monthly:2022-08", "status": "CACHED", "sha256": digest,
                "published_checksum": {"status": "VERIFIED", "sha256": MODULE.sha256_file(checksum),
                                       "published_sha256": digest},
            }
        (v004 / "input-manifest.json").write_text(json.dumps({"assets": list(MODULE.ASSETS), "files": files}), encoding="utf-8")
        (v004 / "archive-manifest.json").write_text(json.dumps({
            "window_start": "2022-08-11", "window_end_inclusive": "2026-09-19", "archives": archives,
        }), encoding="utf-8")
        (v004 / "coverage.json").write_text("{}\n", encoding="utf-8")
        MODULE._V003.create_data_freeze(v004, "2022-08-11", "2026-09-19")

        identity = MODULE.verify_retained_v004(v004)
        self.assertEqual(identity["assets"], list(MODULE.ASSETS))
        self.assertEqual(len(identity["august_archives"]), 9)
        self.assertEqual(identity["august_archives"]["BTC"]["published_sha256"], archives[
            "klines_1h:BTC:monthly:2022-08"]["sha256"])
        with (normalized / "klines_1h_BTC.csv").open("a", encoding="utf-8") as target:
            target.write("corruption\n")
        with self.assertRaisesRegex(ValueError, "immutable data freeze input changed"):
            MODULE.verify_retained_v004(v004)


if __name__ == "__main__":
    unittest.main()
