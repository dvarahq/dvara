#!/usr/bin/env python3
"""Tests for central-budget.py. Offline: the month's release count comes from
CENTRAL_RELEASES_THIS_MONTH.

  python3 scripts/test_central_budget.py
"""

import importlib.util
import io
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "central_budget", Path(__file__).with_name("central-budget.py"))
budget = importlib.util.module_from_spec(spec)
spec.loader.exec_module(budget)


def bundle(dirpath, files, bytes_each=1024):
    path = Path(dirpath) / "central-bundle.zip"
    with zipfile.ZipFile(path, "w") as z:
        for i in range(files):
            z.writestr(f"com/example/a/1.0.0/a-1.0.0-{i}.jar", b"x" * bytes_each)
    return path


class CheckBundle(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.env = dict(os.environ)
        os.environ.pop("CENTRAL_BUDGET_OVERRIDE", None)
        os.environ["CENTRAL_RELEASES_THIS_MONTH"] = "0"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.env)
        self.dir.cleanup()

    def run_check(self, path):
        out = io.StringIO()
        with redirect_stdout(out):
            code = budget.check_bundle(path, "1.0.0")
        return code, out.getvalue()

    def test_a_release_within_budget_passes(self):
        code, out = self.run_check(bundle(self.dir.name, 132))
        self.assertEqual(code, 0, out)
        self.assertIn("within budget", out)

    def test_too_many_files_is_refused(self):
        code, out = self.run_check(bundle(self.dir.name, budget.MAX_FILES_PER_RELEASE + 1))
        self.assertEqual(code, 1)
        self.assertIn("file budget", out)

    def test_too_many_megabytes_is_refused(self):
        code, out = self.run_check(bundle(self.dir.name, 1, bytes_each=(budget.MAX_MB_PER_RELEASE + 1) * 1024 * 1024))
        self.assertEqual(code, 1)
        self.assertIn("MB budget", out)

    def test_a_month_that_has_used_its_releases_is_refused(self):
        os.environ["CENTRAL_RELEASES_THIS_MONTH"] = str(budget.MAX_RELEASES_PER_MONTH)
        code, out = self.run_check(bundle(self.dir.name, 10))
        self.assertEqual(code, 1)
        self.assertIn("this month already has", out)

    def test_the_last_release_of_the_budget_passes(self):
        os.environ["CENTRAL_RELEASES_THIS_MONTH"] = str(budget.MAX_RELEASES_PER_MONTH - 1)
        code, out = self.run_check(bundle(self.dir.name, 10))
        self.assertEqual(code, 0, out)

    def test_an_override_passes_and_says_why(self):
        os.environ["CENTRAL_RELEASES_THIS_MONTH"] = "9"
        os.environ["CENTRAL_BUDGET_OVERRIDE"] = "security fix"
        code, out = self.run_check(bundle(self.dir.name, 10))
        self.assertEqual(code, 0)
        self.assertIn("passing anyway", out)
        self.assertIn("security fix", out)


if __name__ == "__main__":
    unittest.main()
