#!/usr/bin/env python3
"""Assemble a hash-bound fixed-baseline physical input from durable role files.

The template is a versioned contract receipt, not a source of numeric values.
Every role is reopened from ``--root`` and its byte/JCS canonical hashes are
recomputed by the repository's Java ``JsonHashes`` implementation. The script
refuses an existing output whose bytes differ and never creates labels, bars,
fills, or producer claims.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

def descriptor(root: Path, raw: dict[str, Any], role: str, hashes: dict[str, dict[str, Any]]) -> dict[str, Any]:
    relative = raw.get("path")
    if not isinstance(relative, str) or not relative:
        raise SystemExit(f"{role}: missing role path")
    path = (root / relative).resolve()
    if not path.is_file() or os.path.commonpath((str(root.resolve()), str(path))) != str(root.resolve()):
        raise SystemExit(f"{role}: role path is missing or escapes --root: {relative}")
    raw_bytes = path.read_bytes()
    try:
        value = json.loads(raw_bytes)
    except json.JSONDecodeError as error:
        raise SystemExit(f"{role}: role is not JSON: {error}") from error
    result = dict(raw)
    identity = hashes[str(path)]
    result["byte_sha256"] = identity["byte_sha256"]
    result["bytes"] = identity["bytes"]
    result["content_sha256"] = identity["content_sha256"]
    if isinstance(value, list) or "rows_sha256" in raw:
        result["rows_sha256"] = identity["rows_sha256"]
    return result


def java_hashes(repo_root: Path, paths: list[Path]) -> dict[str, dict[str, Any]]:
    with tempfile.NamedTemporaryFile("w", prefix="fixed-baseline-hash-input-", suffix=".txt", delete=False) as handle:
        list_path = Path(handle.name)
        handle.write("\n".join(str(path) for path in paths) + "\n")
    command = [str(repo_root / "bin" / "analytics"), "strategy-research-v5", "canonical-hash-batch",
               "--paths-file", str(list_path), "--include-rows"]
    completed = subprocess.run(command, cwd=repo_root, check=False, capture_output=True, text=True)
    try:
        list_path.unlink()
    except OSError:
        pass
    if completed.returncode != 0:
        raise SystemExit(f"Java canonical hash batch failed: {completed.stderr.strip()}")
    try:
        result = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise SystemExit(f"Java canonical hash batch returned invalid JSON: {error}") from error
    return {row["path"]: row for row in result.get("files", [])}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True, type=Path, help="durable strategy-fixed-baseline-input/1 template")
    parser.add_argument("--root", required=True, type=Path, help="directory containing every role file")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    template = json.loads(args.template.read_text())
    if template.get("schema") != "strategy-fixed-baseline-input/1":
        raise SystemExit("template is not strategy-fixed-baseline-input/1")
    if template.get("status") != "AUTHORITATIVE_PHYSICAL":
        raise SystemExit("template is not authoritative physical input")
    roles = template.get("roles")
    if not isinstance(roles, dict) or "signal_bars" not in roles:
        raise SystemExit("template lacks signal_bars role")
    paths = [(root / raw.get("path", "")).resolve() for raw in roles.values()]
    producer = template.get("producer_receipt")
    if not isinstance(producer, dict):
        raise SystemExit("template lacks producer_receipt descriptor")
    paths.append((root / producer.get("path", "")).resolve())
    repo_root = Path(__file__).resolve().parent.parent
    hashes = java_hashes(repo_root, paths)
    if any(str(path) not in hashes for path in paths):
        raise SystemExit("Java canonical hash batch omitted a role path")
    assembled = dict(template)
    assembled["root"] = str(root)
    assembled["roles"] = {role: descriptor(root, raw, role, hashes) for role, raw in sorted(roles.items())}
    assembled["producer_receipt"] = descriptor(root, producer, "producer_receipt", hashes)
    assembled.pop("content_sha256", None)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".json", prefix="fixed-input-", dir=args.out.parent,
                                    delete=False, encoding="utf-8") as temporary:
        assembled_path = Path(temporary.name)
        temporary.write(json.dumps(assembled, ensure_ascii=False, indent=2) + "\n")
    try:
        input_hash = java_hashes(repo_root, [assembled_path.resolve()])[str(assembled_path.resolve())]["content_sha256"]
    finally:
        assembled_path.unlink(missing_ok=True)
    assembled["content_sha256"] = input_hash
    encoded = json.dumps(assembled, ensure_ascii=False, indent=2) + "\n"
    if args.out.exists():
        if args.out.read_bytes() != encoded.encode():
            raise SystemExit(f"refusing to overwrite different immutable output: {args.out}")
    else:
        args.out.write_text(encoded)
    print(json.dumps({"out": str(args.out), "root": str(root), "content_sha256": assembled["content_sha256"],
                      "role_count": len(assembled["roles"])}, separators=(",", ":")))


if __name__ == "__main__":
    main()
