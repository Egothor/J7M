# Copyright (c) 2026, Leo Galambos and the Egothor team
# https://www.egothor.org
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Negative checks ensure example verification cannot accept missing or failing evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / 'verify_examples.py'
SPEC = importlib.util.spec_from_file_location('verify_examples', MODULE_PATH)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class VerificationEvidenceTest(unittest.TestCase):
    def test_accepts_exact_passing_junit_count(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'TEST-example.xml').write_text('<testsuite tests="4" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(4, VERIFY._results(path, 4)['tests'])

    def test_rejects_missing_failed_and_skipped_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with self.assertRaises(ValueError):
                VERIFY._results(path, 4)
            for values in ['tests="4" failures="1"', 'tests="4" skipped="1"', 'tests="3"', 'tests="4" errors="1"']:
                (path / 'TEST-example.xml').write_text('<testsuite ' + values + '/>')
                with self.assertRaises(ValueError):
                    VERIFY._results(path, 4)

    def test_rejects_source_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                VERIFY._source(Path(directory), '../outside.java')


if __name__ == '__main__':
    unittest.main()
