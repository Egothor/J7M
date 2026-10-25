# Copyright (c) 2026, Leo Galambos and the Egothor team
# https://www.egothor.org
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Mutation tests for current documentation boundaries and source provenance."""
from pathlib import Path
import json
import tempfile
import unittest
from scripts.docs import validate_public_documentation as validator


class PublicDocumentationValidatorTest(unittest.TestCase):
    """Each negative fixture changes one otherwise valid current contract."""

    def setUp(self) -> None:
        """Create a minimal public manual without relying on private records."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write('documentation/mkdocs.yml', 'docs_dir: docs\nnav:\n- Home: index.md\ncopyright: Leo Galambos and Egothor\nextra:\n  generator: false\n')
        for name in ['index', 'annotations/index', 'deployment/index', 'security/index', 'reference/index']:
            self.write(f'documentation/docs/{name}.md', '# Reference\n')
        self.write('LICENSE', 'BSD-4-Clause')

    def write(self, name: str, text: str) -> Path:
        """Write fixture data below the temporary checkout."""
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def check(self, command: str) -> list[validator.Finding]:
        """Run one validator against the fixture checkout."""
        return validator.validate_command(self.root, command)

    def test_valid_structure(self) -> None:
        self.assertEqual([], self.check('structure'))

    def test_missing_navigation_page(self) -> None:
        with (self.root / validator.CONFIG).open('a') as out: out.write('- Missing: absent.md\n')
        self.assertTrue(self.check('structure'))

    def test_navigation_traversal(self) -> None:
        with (self.root / validator.CONFIG).open('a') as out: out.write('- Private: ../../private.md\n')
        self.write('private.md', 'private')
        self.assertTrue(self.check('structure'))

    def test_missing_link(self) -> None:
        self.write('documentation/docs/index.md', '[missing](absent.md)')
        self.assertTrue(self.check('links'))

    def test_encoded_traversal_link(self) -> None:
        self.write('documentation/docs/index.md', '[private](%2e%2e/%2e%2e/private.md)')
        self.write('private.md', 'private')
        self.assertTrue(self.check('links'))

    def test_code_links_are_not_navigation(self) -> None:
        self.write('documentation/docs/index.md', '```java\n// [example](not-a-page.md)\n```\n')
        self.assertEqual([], self.check('links'))

    def test_private_link_rejected(self) -> None:
        self.write('documentation/docs/index.md', '[archive](../../INBOX/history.md)')
        self.assertTrue(self.check('boundary'))

    def test_internal_host_rejected(self) -> None:
        self.write('documentation/docs/index.md', 'https://gitea.hq.egothor/source')
        self.assertTrue(self.check('security'))

    def test_authoring_record_rejected(self) -> None:
        self.write('documentation/docs/index.md', 'Codex performed the review.')
        self.assertTrue(self.check('boundary'))

    def test_secret_sentinel_rejected(self) -> None:
        self.write('documentation/docs/index.md', '-----BEGIN PRIVATE KEY-----')
        self.assertTrue(self.check('security'))

    def test_missing_snippet(self) -> None:
        self.write('documentation/docs/index.md', '--8<-- "missing.java"')
        self.assertTrue(self.check('snippets'))

    def test_private_snippet(self) -> None:
        self.write('documentation/docs/index.md', '--8<-- "INBOX/private.java"')
        self.write('INBOX/private.java', 'private')
        self.assertTrue(self.check('snippets'))

    def test_current_snippet(self) -> None:
        self.write('documentation/docs/index.md', '--8<-- "examples/Example.java"')
        self.write('examples/Example.java', 'class Example {}')
        self.assertEqual([], self.check('snippets'))

    def test_project_footer(self) -> None:
        self.assertEqual([], self.check('homepage'))
        self.write('documentation/mkdocs.yml', 'copyright: MkDocs\n')
        self.assertTrue(self.check('homepage'))

    def annotation_fixture(self) -> None:
        """Create one current annotation and its exact source catalog entry."""
        source = 'public @interface Inject {}'
        self.write('j7m-api/src/main/java/org/j7m/middleware/annotations/Inject.java', source)
        self.write('documentation/data/annotation-api.json', json.dumps({'count': 1, 'annotations': [{'name': 'Inject', 'sourceText': source}]}))
        self.write('documentation/docs/annotations/index.md', '## @Inject\n```java\n@Inject Object service;\n```\n')

    def test_current_annotation_inventory(self) -> None:
        self.annotation_fixture()
        self.assertEqual([], self.check('programmer-guides'))

    def test_new_annotation_requires_reference(self) -> None:
        self.annotation_fixture()
        self.write('j7m-api/src/main/java/org/j7m/middleware/annotations/New.java', 'public @interface New {}')
        self.assertTrue(self.check('programmer-guides'))

    def test_stale_annotation_attributes_rejected(self) -> None:
        self.annotation_fixture()
        self.write('j7m-api/src/main/java/org/j7m/middleware/annotations/Inject.java', 'public @interface Inject { boolean refresh(); }')
        self.assertTrue(self.check('programmer-guides'))

    def test_missing_annotation_example_rejected(self) -> None:
        self.annotation_fixture()
        self.write('documentation/docs/annotations/index.md', '# Empty reference')
        self.assertTrue(self.check('programmer-guides'))

    def test_missing_example_provenance_rejected(self) -> None:
        self.assertTrue(self.check('examples'))

    def test_inline_markdown_script_rejected(self) -> None:
        self.write('documentation/docs/index.md', '<script>alert(1)</script>')
        self.assertTrue(self.check('security'))

    def test_active_svg_rejected(self) -> None:
        self.write('documentation/docs/assets/unsafe.svg', '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
        self.assertTrue(self.check('security'))

    def test_remote_svg_resource_rejected(self) -> None:
        self.write('documentation/docs/assets/unsafe.svg', '<svg xmlns="http://www.w3.org/2000/svg"><image href="https://example.com/tracker"/></svg>')
        self.assertTrue(self.check('security'))

    def test_inline_html_script_rejected(self) -> None:
        self.write('documentation/overrides/main.html', '<script>alert(1)</script>')
        self.assertTrue(self.check('security'))

    def test_test_only_reader_verification_rejected(self) -> None:
        self.annotation_fixture()
        self.write('documentation/docs/programming/injection.md', '## Verification and next steps\nSee `ManagedContainerTest` for details.')
        self.assertTrue(self.check('programmer-guides'))

    def test_testing_guide_can_reference_tests(self) -> None:
        self.annotation_fixture()
        self.write('documentation/docs/testing/index.md', '## Verification\nRun ManagedContainerTest.')
        self.assertEqual([], self.check('programmer-guides'))

    def test_reports_are_reproducible(self) -> None:
        self.assertEqual(0, validator.main(['structure', '--root', str(self.root)]))
        output = self.root / 'build/reports/documentation/structure.json'
        baseline = output.read_bytes()
        self.assertEqual(0, validator.main(['structure', '--root', str(self.root)]))
        self.assertEqual(baseline, output.read_bytes())

if __name__ == '__main__':
    unittest.main()
