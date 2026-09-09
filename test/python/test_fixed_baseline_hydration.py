"""Independent transport regressions; all market responses are synthetic, with no network."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("review_hydrator", REPO / "tools/hydrate-binance-1m-windows.py")
HYDRATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HYDRATOR)


class FixedBaselineHydrationReview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="parent-hydration-review-")
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.start = HYDRATOR.milliseconds("2024-01-01T00:00:00Z")
        self.event = {"episode_id": "eth:2024-01-01T00:00:00Z", "asset": "eth", "symbol": "ETHUSDT",
                      "decision_time": "2024-01-01T00:00:00Z"}

    def market(self, missing=()):
        missing = set(missing)
        def response(url):
            query = parse_qs(urlparse(url).query)
            start, end = int(query["startTime"][0]), int(query["endTime"][0])
            rows = []
            for at in range(start, end + 1, HYDRATOR.MINUTE):
                if at not in missing:
                    rows.append([at, "100", "101", "99", "100", "10", at + HYDRATOR.MINUTE - 1])
            return rows, 200
        return response

    def policy(self, start):
        return {("eth", "ETHUSDT"): {"asset": "eth", "symbol": "ETHUSDT", "start_ms": start,
                "end_ms": start + 120 * HYDRATOR.MINUTE, "reason": "NON_TRADING"}}

    def test_a_historical_closure_does_not_reduce_a_different_windows_row_count(self):
        old = HYDRATOR.milliseconds("2021-09-29T07:00:00Z")
        with patch.object(HYDRATOR, "fetch_json", self.market()):
            result = HYDRATOR.hydrate(self.event, self.output, self.policy(old), {})
        self.assertEqual(len(result[2]), 14_400)

    def test_exact_declared_closure_removes_only_its_120_minutes(self):
        close_start = self.start + 2_000 * HYDRATOR.MINUTE
        missing = range(close_start, close_start + 120 * HYDRATOR.MINUTE, HYDRATOR.MINUTE)
        with patch.object(HYDRATOR, "fetch_json", self.market(missing)), patch.object(
                HYDRATOR, "verify_archive_checksum", lambda interval, verified: dict(interval)):
            result = HYDRATOR.hydrate(self.event, self.output, self.policy(close_start), {})
        self.assertEqual(len(result[2]), 14_280)
        self.assertEqual(result[2][1_999]["event_time"], HYDRATOR.iso(close_start - HYDRATOR.MINUTE))
        self.assertEqual(result[2][2_000]["event_time"], HYDRATOR.iso(close_start + 120 * HYDRATOR.MINUTE))

    def test_an_undeclared_gap_still_fails(self):
        with patch.object(HYDRATOR, "fetch_json", self.market([self.start + 50 * HYDRATOR.MINUTE])):
            with self.assertRaises(RuntimeError):
                HYDRATOR.hydrate(self.event, self.output, {}, {})

    def test_a_truncated_existing_file_cannot_be_reused_as_complete(self):
        path = self.output / ("bars-" + hashlib.sha256(self.event["episode_id"].encode()).hexdigest() + ".json")
        path.write_text("[]\n")
        with patch.object(HYDRATOR, "fetch_json", side_effect=AssertionError("invalid immutable cache must reject")):
            with self.assertRaises((RuntimeError, ValueError)):
                HYDRATOR.hydrate(self.event, self.output, {}, {})
        self.assertEqual(path.read_text(), "[]\n")


if __name__ == "__main__":
    unittest.main()
