#!/usr/bin/env python3
"""Build the durable, replayable fixed-baseline physical role bundle.

This is an assembly recipe, not a data generator.  It derives the completed
4h signal role from the pinned Parquet producer, fetches every event in the
retained 130-row inventory as public 1m transport, copies only the compact
typed role receipts, and delegates the final byte/canonical hashes to the
Java ``canonical-hash-batch`` command used by the evaluator.  A fresh output
root must be empty; missing or changed inputs fail before a result can be
assembled.  Historical filter status remains USER_BOUND_RETROSPECTIVE and
the fixed Java runner still enforces its repository-pinned Parquet source.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def run(command: list[str], cwd: Path) -> None:
    print("+ " + " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def java_hashes(repo: Path, paths: list[Path]) -> dict[str, dict]:
    """Use the same canonical Java hash service as the final assembler."""
    with tempfile.NamedTemporaryFile("w", prefix="fixed-bundle-hash-", suffix=".txt", delete=False) as handle:
        list_path = Path(handle.name)
        handle.write("\n".join(str(path.resolve()) for path in paths) + "\n")
    try:
        completed = subprocess.run(
            [str(repo / "bin" / "analytics"), "strategy-research-v5", "canonical-hash-batch",
             "--paths-file", str(list_path), "--include-rows"],
            cwd=repo, check=False, capture_output=True, text=True)
    finally:
        list_path.unlink(missing_ok=True)
    if completed.returncode != 0:
        raise SystemExit("Java canonical hash batch failed: " + completed.stderr.strip())
    result = json.loads(completed.stdout)
    return {row["path"]: row for row in result.get("files", [])}


def refresh_producer_receipt(repo: Path, source: Path, output: Path) -> str:
    """Bind the producer receipt to the bytes just emitted by the Java producer."""
    signal = output / "signal_bars.json"
    if not signal.is_file():
        raise SystemExit("Java producer did not emit signal_bars.json")
    manifest_value = json.loads((output / "parquet-conversion-manifest.json").read_text(encoding="utf-8"))
    rows = json.loads(signal.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise SystemExit("generated signal role is not a non-empty JSON array")
    identities = java_hashes(repo, [signal, output / "parquet-conversion-manifest.json"])
    signal_identity = identities[str(signal.resolve())]
    manifest_identity = identities[str((output / "parquet-conversion-manifest.json").resolve())]
    if manifest_value.get("content_sha256") != manifest_identity["content_sha256"]:
        raise SystemExit("copied Parquet manifest content hash is not self-consistent")
    assets = sorted({str(row.get("asset", "")).lower() for row in rows})
    if not assets or any(not asset for asset in assets):
        raise SystemExit("generated signal role contains an unbound asset")
    counts = {asset: sum(1 for row in rows if str(row.get("asset", "")).lower() == asset) for asset in assets}
    # Coverage is already frozen by the producer's complete-grid validator;
    # retain the source receipt's exact range while replacing only derived
    # byte/content identities and counts from the newly emitted role.
    receipt = json.loads(source.read_text(encoding="utf-8"))
    receipt["source_manifest_sha256"] = manifest_value.get("content_sha256", "")
    receipt["signal_role_content_sha256"] = signal_identity["content_sha256"]
    receipt["signal_role_byte_sha256"] = signal_identity["byte_sha256"]
    receipt["signal_role_bytes"] = signal_identity["bytes"]
    receipt["declared_assets"] = assets
    receipt["asset_counts"] = counts
    required_assets = ["aave", "ada", "bnb", "btc", "eth", "link", "sol", "xrp"]
    if assets != required_assets or any(counts.get(asset) != 10_957 for asset in required_assets):
        raise SystemExit("generated signal role does not cover the frozen eight-asset 10,957-row scope")
    receipt.pop("content_sha256", None)
    temporary = output / ".producer-receipt-unhashed.json"
    temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    receipt_hash = java_hashes(repo, [temporary])[str(temporary.resolve())]["content_sha256"]
    temporary.unlink(missing_ok=True)
    receipt["content_sha256"] = receipt_hash
    (output / "producer-receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt_hash


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", required=True, type=Path,
                        help="retained fixed-baseline inventory with all_event_inventory")
    parser.add_argument("--manifest", required=True, type=Path,
                        help="authoritative Parquet conversion manifest")
    parser.add_argument("--parquet-root", required=True, type=Path)
    parser.add_argument("--template", required=True, type=Path,
                        help="strategy-fixed-baseline-input/1 role template")
    parser.add_argument("--role-receipts", required=True, type=Path,
                        help="durable compact role receipt directory")
    parser.add_argument("--producer-receipt", required=True, type=Path)
    parser.add_argument("--non-trading-policy", required=True, type=Path,
                        help="typed frozen Binance spot closure policy")
    parser.add_argument("--out-root", required=True, type=Path)
    args = parser.parse_args()

    repo = Path(__file__).resolve().parent.parent
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    events = inventory.get("all_event_inventory")
    if not isinstance(events, list) or len(events) != 130:
        raise SystemExit("inventory must retain exactly 130 all_event_inventory rows")
    if not args.manifest.is_file() or not args.parquet_root.is_dir():
        raise SystemExit("authoritative Parquet manifest/root is unavailable")
    if (not args.template.is_file() or not args.role_receipts.is_dir()
            or not args.producer_receipt.is_file() or not args.non_trading_policy.is_file()):
        raise SystemExit("template, compact role receipts, producer receipt, and closure policy are required")

    controls = [row["control"] for row in inventory.get("control_selections", []) if row.get("control")]
    if len(controls) != 3:
        raise SystemExit("inventory must retain exactly three selected controls")
    episodes = events + controls
    if len({row.get("episode_id") for row in episodes}) != 133:
        raise SystemExit("event/control inventory must contain 133 unique episode ids")

    out = args.out_root.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise SystemExit(f"refusing to assemble into a non-empty immutable root: {out}")

    # Keep the transport input generated and removed within the same run.  The
    # retained inventory and hydration manifest are the durable receipts.
    hydration_input = out / ".hydration-input.json"
    hydration_input.write_text(json.dumps({"schema": "fixed-baseline-hydration-input/1",
                                           "setup_events": events,
                                           "control_events": controls}, indent=2) + "\n",
                               encoding="utf-8")
    shutil.copyfile(args.manifest, out / "parquet-conversion-manifest.json")
    for role in sorted(args.role_receipts.glob("*.json")):
        shutil.copyfile(role, out / role.name)
    if args.non_trading_policy.resolve() != (out / "non-trading-intervals.json").resolve():
        shutil.copyfile(args.non_trading_policy, out / "non-trading-intervals.json")

    # Keep the recipe executable from a fresh checkout where Git may not have
    # restored a local executable bit; the shell wrapper remains the single
    # producer entry point.
    run(["bash", str(repo / "tools" / "prepare-fixed-baseline-signal-bars.sh"),
         str(args.manifest), str(args.parquet_root), str(out / "signal_bars.json")], repo)
    producer_hash = refresh_producer_receipt(repo, args.producer_receipt, out)
    run(["python3", str(repo / "tools" / "hydrate-binance-1m-windows.py"),
         "--inventory", str(hydration_input), "--out", str(out),
         "--non-trading-policy", str(out / "non-trading-intervals.json")], repo)
    hydration_input.unlink(missing_ok=True)

    # Bind the generated producer receipt into the durable descriptor before
    # Java recalculates every role.  A relocated fresh bundle gets a new
    # physical-input identity and therefore cannot silently resume a prior
    # exact-root exposure.
    template = json.loads(args.template.read_text(encoding="utf-8"))
    template["producer_receipt"]["content_sha256"] = producer_hash
    template["roles"]["signal_bars"]["producer_receipt_sha256"] = producer_hash
    with tempfile.NamedTemporaryFile("w", prefix="fixed-baseline-template-", suffix=".json", delete=False) as handle:
        temporary_template = Path(handle.name)
        handle.write(json.dumps(template, ensure_ascii=False, indent=2) + "\n")

    assembled = out / "physical-input-replay.json"
    try:
        run(["python3", str(repo / "tools" / "assemble-fixed-baseline-physical-input.py"),
             "--template", str(temporary_template), "--root", str(out), "--out", str(assembled)], repo)
    finally:
        temporary_template.unlink(missing_ok=True)
    print(json.dumps({"root": str(out), "physical_input": str(assembled),
                      "event_count": len(events), "control_count": len(controls),
                      "window_count": len(episodes), "producer_receipt_sha256": producer_hash,
                      "role_receipt_count": len(list(args.role_receipts.glob("*.json")))},
                     separators=(",", ":")))


if __name__ == "__main__":
    main()
