import subprocess
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from tools.check_new_code_coverage import CoverageError, evaluate


class NewCodeCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.workspace = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=self.workspace, check=True)
        subprocess.run(["git", "config", "user.email", "coverage@example.invalid"], cwd=self.workspace, check=True)
        subprocess.run(["git", "config", "user.name", "Coverage Test"], cwd=self.workspace, check=True)
        (self.workspace / "README").write_text("coverage tests\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def _commit_base(self):
        subprocess.run(["git", "add", "."], cwd=self.workspace, check=True)
        subprocess.run(["git", "commit", "-qm", "base"], cwd=self.workspace, check=True)
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.workspace, text=True).strip()

    def _write_source(self, path="module/src/main/java/example/Thing.java", contents=None):
        target = self.workspace / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents or "class Thing {\n  int value() { return 1; }\n}\n", encoding="utf-8")
        return target

    def _write_report(self, lines, *, source="Thing.java", package="example"):
        xml_lines = []
        for number, covered, missed_branches, covered_branches in lines:
            xml_lines.append(
                f'<line nr="{number}" mi="{0 if covered else 1}" ci="{1 if covered else 0}" '
                f'mb="{missed_branches}" cb="{covered_branches}"/>'
            )
        report = (
            '<report name="test"><package name="%s"><sourcefile name="%s">%s</sourcefile>'
            '</package></report>'
        ) % (package, source, "".join(xml_lines))
        target = self.workspace / "aggregate.xml"
        target.write_text(report, encoding="utf-8")
        return target

    def test_exact_eighty_percent_passes_and_below_boundary_fails(self):
        base = self._commit_base()
        source = self._write_source(
            contents="class Thing {\n  int a() { return 1; }\n  int b() { return 2; }\n  int c() { return 3; }\n  int d() { return 4; }\n}\n"
        )
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0), (4, False, 0, 0), (5, True, 0, 0)])
        exact = evaluate(self.workspace, base, report, minimum=Decimal("80"))
        self.assertEqual(exact.line_status, "passed")
        self.assertEqual(exact.branch_status, "not-applicable")

        report.write_text(report.read_text(encoding="utf-8").replace('nr="4" mi="1" ci="0"', 'nr="4" mi="1" ci="0"').replace('nr="5" mi="0" ci="1"', 'nr="5" mi="1" ci="0"'), encoding="utf-8")
        below = evaluate(self.workspace, base, report, minimum=Decimal("80"))
        self.assertEqual(below.line_status, "failed")
        self.assertFalse(below.passed)

    def test_exact_eighty_branch_boundary_passes(self):
        base = self._commit_base()
        self._write_source().write_text(
            "class Thing {\n  int value() { return 1; }\n  int changed() { return 2; }\n}\n", encoding="utf-8"
        )
        report = self._write_report([(1, True, 0, 0), (2, True, 1, 4), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.branch_total, 5)
        self.assertEqual(result.branch_covered, 4)
        self.assertEqual(result.branch_status, "passed")
        self.assertEqual(result.branch_minimum, Decimal("80"))

    def test_branch_minimum_can_be_lowered_without_lowering_line_minimum(self):
        base = self._commit_base()
        self._write_source().write_text(
            "class Thing {\n  int value() { return 1; }\n  int changed() { return 2; }\n}\n",
            encoding="utf-8",
        )
        report = self._write_report([(1, True, 9, 11), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report, minimum=Decimal("80"), branch_minimum=Decimal("55"))
        self.assertEqual(result.minimum, Decimal("80"))
        self.assertEqual(result.branch_minimum, Decimal("55"))
        self.assertEqual(result.line_status, "passed")
        self.assertEqual(result.branch_status, "passed")
        self.assertEqual(result.as_dict()["minimum_percent"], "80")
        self.assertEqual(result.as_dict()["branch_minimum_percent"], "55")

        inherited = evaluate(self.workspace, base, report, minimum=Decimal("55"))
        self.assertEqual(inherited.branch_minimum, Decimal("55"))
        self.assertEqual(inherited.branch_status, "passed")

    def test_just_below_custom_branch_boundary_fails(self):
        base = self._commit_base()
        self._write_source()
        report = self._write_report([(1, True, 46, 54), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report, minimum=Decimal("80"), branch_minimum=Decimal("55"))
        self.assertEqual(result.branch_total, 100)
        self.assertEqual(result.branch_covered, 54)
        self.assertEqual(result.branch_status, "failed")
        self.assertFalse(result.passed)

    def test_branch_minimum_does_not_mask_independent_line_failure(self):
        base = self._commit_base()
        self._write_source().write_text(
            "class Thing {\n  int value() { return 1; }\n  int changed() { return 2; }\n}\n",
            encoding="utf-8",
        )
        report = self._write_report([(1, True, 0, 20), (2, False, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report, minimum=Decimal("80"), branch_minimum=Decimal("55"))
        self.assertEqual(result.line_status, "failed")
        self.assertEqual(result.branch_status, "passed")
        self.assertFalse(result.passed)

    def test_invalid_branch_minimum_fails_closed(self):
        base = self._commit_base()
        self._write_source()
        report = self._write_report([(1, True, 0, 0)])
        for invalid in (Decimal("-0.01"), Decimal("100.01"), Decimal("NaN"), Decimal("Infinity")):
            with self.assertRaisesRegex(CoverageError, "branch minimum coverage"):
                evaluate(self.workspace, base, report, branch_minimum=invalid)

    def test_zero_branch_and_empty_diff_are_not_applicable(self):
        self._write_source()
        base = self._commit_base()
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.executable_lines, 0)
        self.assertEqual(result.line_status, "not-applicable")
        self.assertEqual(result.branch_status, "not-applicable")

    def test_missing_or_malformed_report_fails_even_for_empty_diff(self):
        self._write_source()
        base = self._commit_base()
        with self.assertRaisesRegex(CoverageError, "missing"):
            evaluate(self.workspace, base, self.workspace / "missing.xml")
        malformed = self.workspace / "malformed.xml"
        malformed.write_text("<report>", encoding="utf-8")
        with self.assertRaisesRegex(CoverageError, "malformed"):
            evaluate(self.workspace, base, malformed)

    def test_missing_base_fails_closed(self):
        self._write_source()
        report = self._write_report([(1, True, 0, 0)])
        with self.assertRaisesRegex(CoverageError, "base"):
            evaluate(self.workspace, "missing-base", report)

    def test_untracked_production_source_is_included(self):
        base = self._commit_base()
        source = self._write_source()
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report)
        self.assertEqual([item.path for item in result.changed_files], [source.relative_to(self.workspace).as_posix()])
        self.assertEqual(result.executable_lines, 3)

    def test_ignored_production_source_is_included(self):
        base = self._commit_base()
        (self.workspace / ".gitignore").write_text("module/src/main/java/\n", encoding="utf-8")
        source = self._write_source()
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report)
        self.assertEqual([item.path for item in result.changed_files], [source.relative_to(self.workspace).as_posix()])

    def test_untracked_source_that_matches_tree_baseline_is_excluded(self):
        source = self._write_source()
        base = self._commit_base()
        subprocess.run(["git", "rm", "--cached", "-q", source.relative_to(self.workspace).as_posix()], cwd=self.workspace, check=True)
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)])
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.changed_files, ())
        self.assertEqual(result.executable_lines, 0)

    def test_source_path_mismatch_fails_closed(self):
        self._write_source()
        base = self._commit_base()
        (self.workspace / "module/src/main/java/example/Thing.java").write_text(
            "class Thing { int changed() { return 2; } }\n", encoding="utf-8"
        )
        report = self._write_report([(1, True, 0, 0)], source="Other.java")
        with self.assertRaisesRegex(CoverageError, "source-path"):
            evaluate(self.workspace, base, report)

    def test_renamed_file_with_edits_assesses_current_file(self):
        old = self._write_source("module/src/main/java/example/OldThing.java", "class OldThing {\n  int value() { return 1; }\n}\n")
        base = self._commit_base()
        new = self.workspace / "module/src/main/java/example/NewThing.java"
        old.rename(new)
        new.write_text("class OldThing {\n  int value() { return 1; }\n  int added() { return 2; }\n}\n", encoding="utf-8")
        subprocess.run(["git", "add", "-A"], cwd=self.workspace, check=True)
        subprocess.run(["git", "commit", "-qm", "rename"], cwd=self.workspace, check=True)
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)], source="NewThing.java")
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.changed_files[0].reason.split(";")[0], "renamed/copy")
        self.assertEqual(result.executable_lines, 1)

    def test_pure_rename_has_no_new_executable_lines(self):
        old = self._write_source("module/src/main/java/example/OldThing.java")
        base = self._commit_base()
        new = self.workspace / "module/src/main/java/example/NewThing.java"
        old.rename(new)
        subprocess.run(["git", "add", "-A"], cwd=self.workspace, check=True)
        subprocess.run(["git", "commit", "-qm", "rename"], cwd=self.workspace, check=True)
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0), (3, True, 0, 0)], source="NewThing.java")
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.executable_lines, 0)
        self.assertEqual(result.line_status, "not-applicable")

    def test_deleted_lines_do_not_become_new_code(self):
        source = self._write_source(contents="class Thing {\n  int a() { return 1; }\n  int removed() { return 2; }\n}\n")
        base = self._commit_base()
        source.write_text("class Thing {\n  int a() { return 1; }\n}\n", encoding="utf-8")
        report = self._write_report([(1, True, 0, 0), (2, True, 0, 0)], source="Thing.java")
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.executable_lines, 0)
        self.assertEqual(result.line_status, "not-applicable")

    def test_packages_with_same_source_name_map_by_package(self):
        self._write_source("module/src/main/java/one/Thing.java", "class Thing { int value() { return 1; } }\n")
        second = self._write_source("module/src/main/java/two/Thing.java", "class Thing { int value() { return 2; } }\n")
        base = self._commit_base()
        second.write_text(second.read_text(encoding="utf-8") + "// changed\n", encoding="utf-8")
        report = self.workspace / "aggregate.xml"
        report.write_text(
            '<report name="test"><package name="one"><sourcefile name="Thing.java"><line nr="1" mi="0" ci="1" mb="0" cb="0"/></sourcefile></package>'
            '<package name="two"><sourcefile name="Thing.java"><line nr="1" mi="1" ci="0" mb="0" cb="0"/></sourcefile></package></report>',
            encoding="utf-8",
        )
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.executable_lines, 0)

    def test_grouped_aggregate_report_maps_module_and_package(self):
        base = self._commit_base()
        source = self._write_source("module/src/main/java/example/Thing.java")
        report = self.workspace / "aggregate.xml"
        report.write_text(
            '<report name="aggregate"><group name="module"><package name="example">'
            '<sourcefile name="Thing.java"><line nr="1" mi="0" ci="1" mb="0" cb="0"/>'
            '<line nr="2" mi="0" ci="1" mb="0" cb="0"/>'
            '<line nr="3" mi="0" ci="1" mb="0" cb="0"/></sourcefile>'
            '</package></group></report>',
            encoding="utf-8",
        )
        result = evaluate(self.workspace, base, report)
        self.assertEqual(result.executable_lines, 3)
        self.assertEqual(result.changed_files[0].path, source.relative_to(self.workspace).as_posix())


if __name__ == "__main__":
    unittest.main()
