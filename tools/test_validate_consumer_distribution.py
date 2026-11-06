# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
"""Exercise archive safety and executable preservation used by consumer qualification."""

import os
from pathlib import Path
import tempfile
import unittest
import zipfile

from validate_consumer_distribution import _extract


class ConsumerArchiveTest(unittest.TestCase):
    """Downloaded release archives must extract safely and retain working launchers."""

    def test_extract_preserves_executable_wrapper(self):
        """The extracted Unix wrapper can be invoked directly as the guide describes."""
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            archive = root / "starter.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                entry = zipfile.ZipInfo("starter/gradlew")
                entry.external_attr = 0o100755 << 16
                zipped.writestr(entry, "#!/bin/sh\nexit 0\n")
            extracted = _extract(archive, root / "unpacked")
            self.assertEqual("starter", extracted.name)
            self.assertTrue(os.access(extracted / "gradlew", os.X_OK))

    def test_rejects_archive_path_traversal(self):
        """A downloaded archive cannot write outside its selected extraction directory."""
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("../escaped", "bad")
            with self.assertRaisesRegex(ValueError, "escapes"):
                _extract(archive, root / "unpacked")
            self.assertFalse((root / "escaped").exists())

    def test_rejects_ambiguous_distribution_roots(self):
        """Two top-level distribution directories cannot silently select the wrong installation."""
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("first/file", "a")
                zipped.writestr("second/file", "b")
            with self.assertRaisesRegex(ValueError, "exactly one"):
                _extract(archive, root / "unpacked")


if __name__ == "__main__":
    unittest.main()
