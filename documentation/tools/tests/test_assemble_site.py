# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Verify real filesystem assembly, complete module coverage and failure preservation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('assemble_site', Path(__file__).parents[1] / 'assemble_site.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.site = self.root / 'build/pages'
        self.site.mkdir(parents=True)
        (self.site / 'index.html').write_text('manual')
        (self.root / 'LICENSE').write_text('project license')
        self.module('j7m-api')

    def module(self, name, built=True):
        source = self.root / name / 'src/main/java/Contract.java'
        source.parent.mkdir(parents=True)
        source.write_text('public interface Contract {}')
        if built:
            output = self.root / name / 'build/docs/javadoc'
            output.mkdir(parents=True)
            (output / 'index.html').write_text(name)
            (output / 'Contract.html').write_text('class API')

    def test_complete_site_retains_manual_assets_and_all_module_pages(self):
        self.module('j7m-runtime')
        (self.root / 'j7m-tests/src/test/java').mkdir(parents=True)
        (self.site / 'search').mkdir()
        (self.site / 'search/search_index.json').write_text('{}')
        self.assertEqual(['j7m-api', 'j7m-runtime'], MODULE.assemble_site(self.root, self.site))
        self.assertEqual('manual', (self.site / 'index.html').read_text())
        self.assertTrue((self.site / 'search/search_index.json').is_file())
        self.assertTrue((self.site / 'javadoc/j7m-api/Contract.html').is_file())
        self.assertIn('href="j7m-runtime/"', (self.site / 'javadoc/index.html').read_text())
        self.assertEqual('project license', (self.site / 'javadoc/LICENSE').read_text())
        self.assertTrue((self.site / '.nojekyll').is_file())

    def test_missing_module_fails_before_replacing_previous_api(self):
        MODULE.assemble_site(self.root, self.site)
        self.module('j7m-new', built=False)
        with self.assertRaisesRegex(ValueError, 'Missing Javadoc for j7m-new'):
            MODULE.assemble_site(self.root, self.site)
        self.assertTrue((self.site / 'javadoc/j7m-api/Contract.html').is_file())

    def test_rebuild_removes_retired_module_pages(self):
        MODULE.assemble_site(self.root, self.site)
        stale = self.site / 'javadoc/retired'
        stale.mkdir()
        MODULE.assemble_site(self.root, self.site)
        self.assertFalse(stale.exists())

    def test_rejects_missing_manual_or_license(self):
        for name in ['index.html', 'LICENSE']:
            path = self.site / name if name == 'index.html' else self.root / name
            content = path.read_text()
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'manual and provide LICENSE'):
                MODULE.assemble_site(self.root, self.site)
            path.write_text(content)

    def test_rejects_output_outside_build(self):
        with self.assertRaisesRegex(ValueError, 'below repository build'):
            MODULE.assemble_site(self.root, self.root)

    def test_rejects_symlinked_api_output(self):
        elsewhere = self.root / 'elsewhere'
        elsewhere.mkdir()
        (self.site / 'javadoc').symlink_to(elsewhere, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symbolic link'):
            MODULE.assemble_site(self.root, self.site)


if __name__ == '__main__':
    unittest.main()
