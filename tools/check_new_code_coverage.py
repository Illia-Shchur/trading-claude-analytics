#!/usr/bin/env python3
"""Gate JaCoCo coverage for executable production Java lines changed in a diff.

The gate deliberately works from the aggregate JaCoCo XML report.  Per-module
reports cannot credit a test in a downstream reactor module against the class
that it exercises.  This script keeps the policy independent of Maven and is
also usable in deterministic unit tests.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Iterable


class CoverageError(RuntimeError):
    """A missing or invalid coverage input must fail the gate closed."""


@dataclass(frozen=True)
class LineCoverage:
    line: int
    covered: bool
    branch_missed: int
    branch_covered: int


@dataclass(frozen=True)
class SourceCoverage:
    """Coverage for a source file, keyed by package-relative source suffix."""

    module: str | None
    suffix: str
    lines: dict[int, LineCoverage]


@dataclass(frozen=True)
class ChangedFile:
    path: str
    line_numbers: frozenset[int] | None
    reason: str

    @property
    def all_executable_lines(self) -> bool:
        return self.line_numbers is None


@dataclass(frozen=True)
class GateResult:
    base: str
    report: str
    changed_files: tuple[ChangedFile, ...]
    executable_lines: int
    covered_lines: int
    branch_total: int
    branch_covered: int
    line_status: str
    branch_status: str
    minimum: Decimal
    branch_minimum: Decimal | None = None

    @property
    def passed(self) -> bool:
        return self.line_status in {"passed", "not-applicable"} and self.branch_status in {
            "passed",
            "not-applicable",
        }

    def as_dict(self) -> dict[str, object]:
        return {
            "base": self.base,
            "coverage_report": self.report,
            "changed_files": [
                {
                    "path": item.path,
                    "lines": "all-executable" if item.all_executable_lines else sorted(item.line_numbers or ()),
                    "reason": item.reason,
                }
                for item in self.changed_files
            ],
            "executable_lines": self.executable_lines,
            "covered_lines": self.covered_lines,
            "line_coverage_percent": _percent(self.covered_lines, self.executable_lines),
            "branch_total": self.branch_total,
            "branch_covered": self.branch_covered,
            "branch_coverage_percent": _percent(self.branch_covered, self.branch_total),
            "line_status": self.line_status,
            "branch_status": self.branch_status,
            "minimum_percent": str(self.minimum),
            "branch_minimum_percent": str(
                self.branch_minimum if self.branch_minimum is not None else self.minimum
            ),
            "passed": self.passed,
        }


PRODUCTION_JAVA = "/src/main/java/"


def _percent(covered: int, total: int) -> str | None:
    if total == 0:
        return None
    return f"{(Decimal(covered) * Decimal(100) / Decimal(total)):.2f}"


def _is_production_java(path: str) -> bool:
    return path.endswith(".java") and PRODUCTION_JAVA in ("/" + path).replace("//", "/")


def _normalise(path: str) -> str:
    return path.replace("\\", "/").lstrip("./")


def _run_git(workspace: Path, *args: str) -> bytes:
    try:
        completed = subprocess.run(
            ["git", "-C", str(workspace), *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError as exc:
        raise CoverageError("git is required to calculate changed production source") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode("utf-8", errors="replace").strip()
        raise CoverageError(f"git {' '.join(args)} failed: {detail}") from exc
    return completed.stdout


def _base_file(workspace: Path, base: str, path: str) -> bytes | None:
    """Read a path from a tree-ish, returning None when it is absent."""

    object_name = f"{base}:{path}"
    try:
        _run_git(workspace, "cat-file", "-e", object_name)
    except CoverageError:
        return None
    return _run_git(workspace, "show", object_name)


def _same_source_content(before: bytes, after: bytes) -> bool:
    """Compare source bytes while ignoring Git's CRLF/LF worktree policy."""

    return before.replace(b"\r\n", b"\n") == after.replace(b"\r\n", b"\n")


def _changed_lines_between(before: bytes | None, after: bytes, path: str) -> frozenset[int]:
    if before is None:
        return frozenset(range(1, len(after.decode("utf-8").splitlines()) + 1))
    # Git may normalize checked-in LF files to CRLF in a Windows worktree.
    # Compare source after CRLF normalization so an unchanged source file is
    # not treated as new code solely because of the worktree's line-ending policy.
    if _same_source_content(before, after):
        return frozenset()
    diff = "\n".join(
        difflib.unified_diff(
            before.decode("utf-8").splitlines(),
            after.decode("utf-8").splitlines(),
            fromfile=path,
            tofile=path,
            n=0,
        )
    )
    return _hunk_line_numbers(diff)


def _changed_lines_against_base(workspace: Path, base: str, path: str) -> frozenset[int]:
    current_path = workspace / path
    return _changed_lines_between(_base_file(workspace, base, path), current_path.read_bytes(), path)


def validate_base(workspace: Path, base: str) -> str:
    if not base or base == "0" * 40:
        raise CoverageError("coverage base is missing or is the all-zero initial revision")
    try:
        resolved = _run_git(workspace, "rev-parse", "--verify", f"{base}^{{tree}}").decode().strip()
    except CoverageError as exc:
        raise CoverageError(f"coverage base is not a resolvable commit/tree: {base}") from exc
    if not resolved:
        raise CoverageError(f"coverage base resolved to an empty tree: {base}")
    return base


def _parse_name_status(raw: bytes) -> list[tuple[str, str | None, str]]:
    """Return (status, old path, current path), preserving renames."""

    fields = raw.decode("utf-8", errors="strict").split("\0")
    records: list[tuple[str, str | None, str]] = []
    index = 0
    while index < len(fields) and fields[index]:
        status = fields[index]
        index += 1
        if status.startswith(("R", "C")):
            if index + 1 >= len(fields) or not fields[index] or not fields[index + 1]:
                raise CoverageError("malformed git rename/copy status output")
            old_path, new_path = fields[index], fields[index + 1]
            index += 2
            records.append((status, old_path, _normalise(new_path)))
        else:
            if index >= len(fields) or not fields[index]:
                raise CoverageError("malformed git name-status output")
            path = _normalise(fields[index])
            index += 1
            records.append((status, None, path))
    return records


def _hunk_line_numbers(diff: str) -> frozenset[int]:
    numbers: set[int] = set()
    for raw_line in diff.splitlines():
        if not raw_line.startswith("@@ "):
            continue
        try:
            new_range = raw_line.split("+")[1].split(" ")[0]
            start_count = new_range.split(",")
            start = int(start_count[0])
            count = int(start_count[1]) if len(start_count) == 2 else 1
        except (IndexError, ValueError) as exc:
            raise CoverageError(f"malformed git hunk header: {raw_line}") from exc
        numbers.update(range(start, start + count))
    return frozenset(numbers)


def collect_changed_files(
    workspace: Path, base: str, *, include_untracked: bool = True
) -> tuple[ChangedFile, ...]:
    """Find current production Java paths and added/modified executable line candidates."""

    validate_base(workspace, base)
    records = _parse_name_status(
        _run_git(workspace, "diff", "--name-status", "--find-renames", "-z", base, "--")
    )
    changed: dict[str, ChangedFile] = {}
    for status, old_path, path in records:
        if status.startswith("D") or not _is_production_java(path):
            continue
        if status.startswith(("R", "C")):
            if old_path is None:
                raise CoverageError(f"rename/copy record has no original path: {path}")
            current_bytes = (workspace / path).read_bytes()
            changed[path] = ChangedFile(
                path,
                _changed_lines_between(_base_file(workspace, base, old_path), current_bytes, path),
                "renamed/copy; assess changed lines",
            )
        elif status.startswith("A"):
            changed[path] = ChangedFile(path, None, "new file; assess all executable lines")
        else:
            diff = _run_git(workspace, "diff", "--unified=0", "--find-renames", "--no-color", base, "--", path)
            changed[path] = ChangedFile(path, _hunk_line_numbers(diff.decode("utf-8", errors="strict")), "added/modified lines")

    if include_untracked:
        # Include ignored worktree files too: an ignored production source must
        # not silently evade the gate merely because it is absent from the
        # index. Generated target paths do not contain /src/main/java/.
        raw_untracked = _run_git(workspace, "ls-files", "--others", "-z", "--", "*.java")
        for path in raw_untracked.decode("utf-8", errors="strict").split("\0"):
            path = _normalise(path)
            if path and _is_production_java(path):
                base_bytes = _base_file(workspace, base, path)
                if base_bytes is None:
                    changed[path] = ChangedFile(path, None, "untracked new file; assess all executable lines")
                elif _same_source_content(base_bytes, (workspace / path).read_bytes()):
                    # The local task baseline is a tree object and may contain
                    # files that are currently untracked in the worktree. They
                    # are baseline content, not new code, when bytes match.
                    continue
                else:
                    changed[path] = ChangedFile(
                        path,
                        _changed_lines_against_base(workspace, base, path),
                        "modified pre-existing untracked file; assess changed lines",
                    )
    return tuple(changed[path] for path in sorted(changed))


def _source_suffix(package_name: str, source_name: str) -> str:
    package_path = package_name.replace(".", "/").strip("/")
    return _normalise(f"{package_path}/{source_name}" if package_path else source_name)


def parse_coverage_report(report_path: Path) -> dict[tuple[str | None, str], SourceCoverage]:
    """Parse strict JaCoCo XML into package-relative source file records."""

    if not report_path.is_file():
        raise CoverageError(f"aggregate JaCoCo XML report is missing: {report_path}")
    try:
        root = ET.parse(report_path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise CoverageError(f"aggregate JaCoCo XML report is malformed: {report_path}") from exc
    if root.tag != "report":
        raise CoverageError(f"aggregate JaCoCo XML root must be <report>, got <{root.tag}>")

    result: dict[tuple[str | None, str], SourceCoverage] = {}
    grouped_packages: list[tuple[str | None, ET.Element]] = []
    grouped_packages.extend((None, package) for package in root.findall("package"))
    for group in root.findall("group"):
        group_name = group.get("name")
        if not group_name:
            raise CoverageError("aggregate JaCoCo XML has a group without a name")
        grouped_packages.extend((group_name, package) for package in group.findall("package"))
    packages = grouped_packages
    if not packages:
        raise CoverageError("aggregate JaCoCo XML contains no packages")
    for module, package in packages:
        package_name = package.get("name")
        if package_name is None:
            raise CoverageError("aggregate JaCoCo XML has a package without a name")
        for sourcefile in package.findall("sourcefile"):
            source_name = sourcefile.get("name")
            if not source_name or "/" in source_name or "\\" in source_name:
                raise CoverageError(f"invalid JaCoCo source file name in package {package_name!r}")
            suffix = _source_suffix(package_name, source_name)
            record_key = (module, suffix)
            if record_key in result:
                raise CoverageError(f"duplicate JaCoCo source file record: {module or '<root>'}:{suffix}")
            lines: dict[int, LineCoverage] = {}
            for line in sourcefile.findall("line"):
                try:
                    number = int(line.attrib["nr"])
                    missed_instructions = int(line.attrib["mi"])
                    covered_instructions = int(line.attrib["ci"])
                    missed_branches = int(line.attrib["mb"])
                    covered_branches = int(line.attrib["cb"])
                except (KeyError, TypeError, ValueError) as exc:
                    raise CoverageError(f"malformed JaCoCo line record in {suffix}") from exc
                if number < 1 or min(missed_instructions, covered_instructions, missed_branches, covered_branches) < 0:
                    raise CoverageError(f"invalid JaCoCo line counters in {suffix}:{number}")
                if number in lines:
                    raise CoverageError(f"duplicate JaCoCo line record in {suffix}:{number}")
                lines[number] = LineCoverage(
                    number,
                    covered_instructions > 0,
                    missed_branches,
                    covered_branches,
                )
            result[record_key] = SourceCoverage(module, suffix, lines)
    return result


def _coverage_for_path(path: str, coverage: dict[tuple[str | None, str], SourceCoverage]) -> SourceCoverage:
    normalized = _normalise(path)
    module = normalized.split("/", 1)[0] if "/" in normalized else None
    matches = [
        source
        for (record_module, suffix), source in coverage.items()
        if (record_module is None or record_module == module)
        and (normalized == suffix or normalized.endswith("/src/main/java/" + suffix))
    ]
    if len(matches) != 1:
        if not matches:
            raise CoverageError(f"changed production source has no JaCoCo source-path match: {path}")
        raise CoverageError(f"changed production source has ambiguous JaCoCo source-path matches: {path}")
    return matches[0]


def _passes(covered: int, total: int, minimum: Decimal) -> str:
    if total == 0:
        return "not-applicable"
    # Compare exact decimal/rational values; never round a failing 79.99% up.
    return "passed" if Decimal(covered) * Decimal(100) >= Decimal(total) * minimum else "failed"


def evaluate(
    workspace: Path,
    base: str,
    report_path: Path,
    *,
    minimum: Decimal = Decimal("80"),
    branch_minimum: Decimal | None = None,
    include_untracked: bool = True,
) -> GateResult:
    if not minimum.is_finite() or minimum < 0 or minimum > 100:
        raise CoverageError("minimum coverage must be a finite percentage from 0 through 100")
    if branch_minimum is None:
        branch_minimum = minimum
    if not branch_minimum.is_finite() or branch_minimum < 0 or branch_minimum > 100:
        raise CoverageError("branch minimum coverage must be a finite percentage from 0 through 100")
    changed_files = collect_changed_files(workspace, base, include_untracked=include_untracked)
    coverage = parse_coverage_report(report_path)
    line_total = line_covered = branch_total = branch_covered = 0
    for changed in changed_files:
        if not (workspace / changed.path).is_file():
            raise CoverageError(f"changed production source is missing from the working tree: {changed.path}")
        source = _coverage_for_path(changed.path, coverage)
        selected = source.lines if changed.all_executable_lines else {
            number: item for number, item in source.lines.items() if number in (changed.line_numbers or ())
        }
        for item in selected.values():
            line_total += 1
            line_covered += int(item.covered)
            branch_total += item.branch_missed + item.branch_covered
            branch_covered += item.branch_covered

    return GateResult(
        base=base,
        report=str(report_path),
        changed_files=changed_files,
        executable_lines=line_total,
        covered_lines=line_covered,
        branch_total=branch_total,
        branch_covered=branch_covered,
        line_status=_passes(line_covered, line_total, minimum),
        branch_status=_passes(branch_covered, branch_total, branch_minimum),
        minimum=minimum,
        branch_minimum=branch_minimum,
    )


def _markdown(result: GateResult) -> str:
    data = result.as_dict()
    outcome = "PASS" if result.passed else "FAIL"
    lines = [
        f"## New production Java coverage: {outcome}",
        "",
        f"Base: `{result.base}`",
        f"Changed production files: `{len(result.changed_files)}`",
        f"Lines: `{data['covered_lines']}/{data['executable_lines']}` ({data['line_coverage_percent'] or 'N/A'}%; {result.line_status})",
        f"Branches: `{data['branch_covered']}/{data['branch_total']}` ({data['branch_coverage_percent'] or 'N/A'}%; {result.branch_status})",
        f"Line minimum: `{result.minimum}%`",
        f"Branch minimum: `{result.branch_minimum if result.branch_minimum is not None else result.minimum}%`",
        "",
    ]
    return "\n".join(lines)


def _write_outputs(result: GateResult, json_path: Path | None, markdown_path: Path | None) -> None:
    if json_path is not None:
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps(result.as_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if markdown_path is not None:
        markdown_path.parent.mkdir(parents=True, exist_ok=True)
        markdown_path.write_text(_markdown(result), encoding="utf-8")


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("COVERAGE_BASE"), help="validated git commit or tree-ish baseline")
    parser.add_argument("--workspace", type=Path, default=Path("."))
    parser.add_argument("--report", type=Path, required=True, help="aggregate JaCoCo XML report")
    parser.add_argument("--minimum", type=Decimal, default=Decimal("80"))
    parser.add_argument(
        "--branch-minimum",
        type=Decimal,
        help="minimum branch coverage percentage; defaults to --minimum",
    )
    parser.add_argument("--summary-json", type=Path)
    parser.add_argument("--summary-markdown", type=Path)
    parser.add_argument("--exclude-untracked", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = _argument_parser().parse_args(argv)
    workspace = args.workspace.resolve()
    try:
        if not args.base:
            raise CoverageError("coverage base is required; refusing to infer a missing/initial baseline")
        result = evaluate(
            workspace,
            args.base,
            (workspace / args.report).resolve() if not args.report.is_absolute() else args.report,
            minimum=args.minimum,
            branch_minimum=args.branch_minimum,
            include_untracked=not args.exclude_untracked,
        )
        _write_outputs(result, args.summary_json, args.summary_markdown)
        print(json.dumps(result.as_dict(), sort_keys=True))
        return 0 if result.passed else 1
    except CoverageError as exc:
        failure = {"passed": False, "error": str(exc)}
        if args.summary_json is not None:
            args.summary_json.parent.mkdir(parents=True, exist_ok=True)
            args.summary_json.write_text(json.dumps(failure, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if args.summary_markdown is not None:
            args.summary_markdown.parent.mkdir(parents=True, exist_ok=True)
            args.summary_markdown.write_text(
                "## New production Java coverage: FAIL\n\n" + f"Error: `{exc}`\n",
                encoding="utf-8",
            )
        print(f"coverage gate error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
