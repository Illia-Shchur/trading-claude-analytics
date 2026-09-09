import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("hydrate-binance-1m-windows.py")
SPEC = importlib.util.spec_from_file_location("hydrate_binance_windows", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


POLICY = {
    ("eth", "ETHUSDT"): {
        "asset": "eth", "symbol": "ETHUSDT", "start_ms": 1632898800000,
        "end_ms": 1632906000000,
    }
}


def event(timestamp):
    return {"episode_id": "eth:" + timestamp, "asset": "eth", "symbol": "ETHUSDT",
            "decision_time": timestamp}


class HydrationGridTest(unittest.TestCase):
    def test_closure_only_applies_when_horizon_overlaps(self):
        self.assertIsNone(MODULE.closure_for(event("2021-09-30T07:00:00Z"), POLICY))
        self.assertEqual(MODULE.expected_rows_for(event("2021-09-30T07:00:00Z"), POLICY), 14_400)
        self.assertIsNotNone(MODULE.closure_for(event("2021-09-28T07:00:00Z"), POLICY))
        self.assertEqual(MODULE.expected_rows_for(event("2021-09-28T07:00:00Z"), POLICY), 14_280)

    def test_reused_role_is_reopened_before_acceptance(self):
        with self.assertRaisesRegex(RuntimeError, "wrong row count"):
            MODULE.validate_reused_rows(event("2021-09-30T07:00:00Z"), [], POLICY)

    def test_unknown_gap_has_no_policy_exception(self):
        no_policy = {}
        self.assertIsNone(MODULE.closure_for(event("2021-09-29T07:00:00Z"), no_policy))
        self.assertEqual(MODULE.expected_rows_for(event("2021-09-29T07:00:00Z"), no_policy), 14_400)


if __name__ == "__main__":
    unittest.main()
