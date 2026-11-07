# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
"""Check exhaustive test ownership and exact exclusions in the release plan."""

import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest

from release_test_plan import release_test_matrix

ROOT = Path(__file__).resolve().parents[1]
HEAVY_TESTS = {
    ":j7m-distributed-core:test", ":j7m-integrations:test",
    ":j7m-application-distributed:test", ":j7m-cluster-management:test",
}


class ReleaseTestPlanTest(unittest.TestCase):
    """Every configured module stays tested without duplicating heavy suites."""

    def test_all_configured_test_tasks_have_one_owner(self):
        """Interpreting the selected commands covers the current module inventory once."""
        projects = {":" + name for name in re.findall(r'(?m)^include\s*\(?"([^\"]+)"', (ROOT / "settings.gradle").read_text())}
        # Gradle creates the implicit examples parent, and the root has a check too.
        projects.update({":examples", ""})
        all_tests = {project + ":test" for project in projects}
        selected = []
        for entry in release_test_matrix(ROOT / "settings.gradle")["include"]:
            arguments = shlex.split(entry["tasks"])
            excluded = {arguments[index + 1] for index, arg in enumerate(arguments) if arg == "-x"}
            tasks = all_tests if arguments[0] == "check" else set(arguments)
            selected.append(tasks - excluded)
        self.assertEqual(all_tests, set().union(*selected))
        for index, group in enumerate(selected):
            self.assertTrue(group)
            for other in selected[index + 1:]:
                self.assertFalse(group & other, "A test suite would run on two runners")
        self.assertIn(":j7m-consensus:test", selected[-1])

    def test_every_excluded_task_is_run_on_a_dedicated_runner(self):
        """Only the four named expensive Test tasks move out of the full check."""
        entries = release_test_matrix(ROOT / "settings.gradle")["include"]
        dedicated = {task for entry in entries[:-1] for task in shlex.split(entry["tasks"])}
        remaining = shlex.split(entries[-1]["tasks"])
        excluded = {remaining[index + 1] for index, arg in enumerate(remaining) if arg == "-x"}
        self.assertEqual(HEAVY_TESTS, dedicated)
        self.assertEqual(dedicated, excluded)
        self.assertEqual(1 + 2 * len(HEAVY_TESTS), len(remaining))
        self.assertEqual("check", remaining[0])
        self.assertNotIn("-x test", entries[-1]["tasks"])

    def test_new_modules_remain_in_the_full_check(self):
        """Adding a project needs no allowlist update to retain its test coverage."""
        with tempfile.TemporaryDirectory() as work:
            settings = Path(work) / "settings.gradle"
            settings.write_text((ROOT / "settings.gradle").read_text() + '\ninclude("new-runtime-module")\n')
            matrix = release_test_matrix(settings)
        remaining = matrix["include"][-1]["tasks"]
        self.assertTrue(remaining.startswith("check "))
        self.assertNotIn(":new-runtime-module:test", remaining)
        self.assertEqual({"distributed", "applications", "remaining"}, {entry["shard"] for entry in matrix["include"]})

    def test_missing_dedicated_project_fails_before_exclusion(self):
        """A renamed or deleted heavy project cannot silently lose its tests."""
        with tempfile.TemporaryDirectory() as work:
            settings = Path(work) / "settings.gradle"
            settings.write_text('include("j7m-distributed-core")\n')
            with self.assertRaisesRegex(ValueError, "dedicated test projects"):
                release_test_matrix(settings)

    def test_unsupported_include_fails_closed(self):
        """Computed module names need explicit planner support rather than omission."""
        with tempfile.TemporaryDirectory() as work:
            settings = Path(work) / "settings.gradle"
            settings.write_text((ROOT / "settings.gradle").read_text() + "\ninclude projectVariable\n")
            with self.assertRaisesRegex(ValueError, "unsupported project include"):
                release_test_matrix(settings)

    def test_cli_emits_a_github_matrix(self):
        """The workflow receives valid JSON with all three independently named jobs."""
        result = subprocess.run([sys.executable, str(ROOT / "tools/release_test_plan.py")], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(release_test_matrix(ROOT / "settings.gradle"), json.loads(result.stdout))

    def test_cli_rejects_invalid_settings_without_emitting_a_matrix(self):
        """The planning step fails before producing an incomplete matrix output."""
        with tempfile.TemporaryDirectory() as work:
            settings = Path(work) / "settings.gradle"
            settings.write_text('include("some-other-project")\n')
            result = subprocess.run([sys.executable, str(ROOT / "tools/release_test_plan.py"), "--settings", str(settings)],
                                    capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("dedicated test projects", result.stderr)


if __name__ == "__main__":
    unittest.main()
