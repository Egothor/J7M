#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# https://www.egothor.org
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Validate the current public manual and current-source provenance.

No private historical receipts are inputs. Reports go to build/reports/documentation.
Strict MkDocs rendering additionally checks anchors and expanded code snippets.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import importlib.util
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from urllib.parse import unquote, urlsplit

DOCS = Path('documentation/docs')
CONFIG = Path('documentation/mkdocs.yml')
COMMANDS = ('structure', 'links', 'snippets', 'examples', 'security', 'boundary',
            'homepage', 'product-overview', 'programmer-guides', 'tooling')

@dataclass(frozen=True, order=True)
class Finding:
    """A stable repository-relative validation failure."""
    path: str
    line: int
    reason: str

    def render(self) -> str:
        """Return an editor-friendly diagnostic."""
        return f'{self.path}:{self.line}: {self.reason}'


def contained(base: Path, path: Path) -> bool:
    """Test canonical containment, including symlink traversal."""
    return path.resolve().is_relative_to(base.resolve())


def validate_command(root: Path, command: str) -> list[Finding]:
    """Validate one contract, or the complete current documentation contract."""
    if command == 'all':
        return sorted(set(f for name in COMMANDS for f in validate_command(root, name)))
    findings: list[Finding] = []
    def fail(path: Path, reason: str, line: int = 1) -> None:
        findings.append(Finding(str(path.relative_to(root)), line, reason))
    config = root / CONFIG
    text = config.read_text() if config.is_file() else ''
    public = sorted((root / DOCS).rglob('*.md'))
    if command == 'structure':
        for relative in [CONFIG, DOCS / 'index.md', DOCS / 'annotations/index.md',
                         DOCS / 'deployment/index.md', DOCS / 'security/index.md',
                         DOCS / 'reference/index.md', Path('LICENSE')]:
            if not (root / relative).is_file(): fail(root / relative, 'required public documentation file missing')
        for target in re.findall(r'^\s*-\s+[^\n:]+:\s+([^\s]+\.md)\s*$', text, re.M):
            path = root / DOCS / unquote(target)
            if not contained(root / DOCS, path) or not path.is_file(): fail(config, f'invalid navigation target: {target}')
        if not re.search(r'^docs_dir:\s+docs\s*$', text, re.M): fail(config, 'canonical config must use its local docs directory')
    if command == 'links':
        for page in public:
            source = re.sub(r'^```.*?^```\s*$', '', page.read_text(), flags=re.M | re.S)
            for match in re.finditer(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)', source):
                target = unquote(match.group(1).strip('<>'))
                url = urlsplit(target)
                if url.scheme or url.netloc or not url.path: continue
                resolved = (page.parent / url.path).resolve()
                if not contained(root / DOCS, resolved): fail(page, f'link escapes public manual: {target}')
                elif not resolved.exists(): fail(page, f'missing local link: {target}')
    if command == 'snippets':
        for page in public:
            for snippet in re.findall(r'^\s*--8<--\s+[\"\']([^\"\']+)', page.read_text(), re.M):
                path = root / snippet.split(':', 1)[0]
                if not contained(root, path) or not path.is_file(): fail(page, f'missing or escaped snippet source: {snippet}')
                elif any(part in {'INBOX', 'internal'} for part in path.parts): fail(page, 'snippet exposes private source')
    if command == 'examples':
        tool = root / 'documentation/tools/verify_examples.py'
        try:
            spec = importlib.util.spec_from_file_location('j7m_documentation_examples', tool)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module._provenance(root)
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            fail(tool, f'example source provenance failed: {error}')
    if command in {'security', 'boundary'}:
        for path in public + sorted((root / 'documentation/overrides').rglob('*.html')):
            for number, line in enumerate(path.read_text().splitlines(), 1):
                if re.search(r'(?:\.\./|/|\()INBOX/|docs/internal/|gitea\.hq\.egothor', line): fail(path, 'private path or internal host published', number)
                if re.search(r'BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY|AKIA[0-9A-Z]{16}', line): fail(path, 'potential secret published', number)
                if re.search(r'<script\b|\son\w+\s*=', line, re.I): fail(path, 'active inline HTML is prohibited', number)
                if re.search(r'(?i)codex|chatgpt|written by (?:an? )?AI|generated by (?:an? )?AI', line): fail(path, 'internal authoring provenance published', number)
        if 'gitea.hq.egothor' in text: fail(config, 'internal source host must not be published')
        for path in sorted((root / DOCS).rglob('*.svg')):
            try:
                document = ET.parse(path)
                for element in document.iter():
                    if element.tag.rsplit('}', 1)[-1].lower() in {'script', 'foreignobject'}:
                        fail(path, 'active SVG content is prohibited')
                    for key, value in element.attrib.items():
                        if key.lower().startswith('on') or value.lower().startswith(('javascript:', 'http:', 'https:')):
                            fail(path, 'active or remotely loaded SVG resource is prohibited')
            except ET.ParseError as error: fail(path, f'invalid SVG: {error}')
        for path in sorted((root / 'documentation/overrides').rglob('*.html')):
            source = path.read_text()
            if re.search(r'<script\b|\son\w+\s*=', source, re.I): fail(path, 'inline active HTML is prohibited')
    if command == 'homepage':
        home = root / DOCS / 'index.md'
        if home.is_file() and len(re.findall(r'^#\s', home.read_text(), re.M)) > 1: fail(home, 'multiple homepage titles')
        if not all(name in text for name in ['Leo Galambos', 'Egothor', 'generator: false']): fail(config, 'project attribution or generator suppression missing')
    if command == 'product-overview':
        for name in ['overview/architecture.md', 'overview/capabilities.md', 'overview/terminology.md']:
            path = root / DOCS / name
            if not path.is_file() or len(path.read_text().split()) < 100: fail(path, 'substantive architecture, boundaries and terminology required')
    if command == 'programmer-guides':
        for page in public:
            if page.relative_to(root / DOCS).parts[0] not in {'annotations', 'patterns', 'programming'}:
                continue
            for section in re.finditer(r'^##[^\n]*Verification[^\n]*\n(.*?)(?=^## |\Z)', page.read_text(), re.M | re.S | re.I):
                body = section.group(1)
                if re.search(r'(?:/src/test/|\b\w+(?:Test|IT)\b)', body) and '```' not in body:
                    fail(page, 'reader-facing verification must explain practical use inline, not delegate to test references')
        catalog_path = root / 'documentation/data/annotation-api.json'
        try:
            catalog = json.loads(catalog_path.read_text())
            actual = {p.stem: p for p in (root / 'j7m-api/src/main/java/org/j7m/middleware/annotations').glob('*.java') if re.search(r'public\s+@interface\s', p.read_text())}
            recorded = {entry['name']: entry for entry in catalog['annotations']}
            if len(recorded) != len(catalog['annotations']) or set(actual) != set(recorded) or catalog['count'] != len(actual): fail(catalog_path, 'annotation inventory differs from current public source')
            reference = '\n'.join(p.read_text() for p in (root / DOCS / 'annotations').glob('*.md'))
            for name, path in actual.items():
                if recorded.get(name, {}).get('sourceText') != path.read_text(): fail(catalog_path, f'stale annotation source: @{name}')
                section = re.search(r'^## @' + re.escape(name) + r'\b[^\n]*\n(.*?)(?=^## |\Z)', reference, re.M | re.S)
                if not section or '```java' not in section.group(1):
                    fail(catalog_path, f'missing individual worked Java reference: @{name}')
        except (OSError, ValueError, KeyError, TypeError) as error: fail(catalog_path, f'invalid annotation catalog: {error}')
    if command == 'tooling':
        for name in ['documentation/requirements.txt', 'documentation/tools/verify_examples.py', 'documentation/examples/compile.init.gradle', 'documentation/data/example-sources.json', 'gradle/build-logic/root/70-documentation-platform.gradle']:
            if not (root / name).is_file(): fail(root / name, 'required reproducible documentation tooling missing')
        requirements = root / 'documentation/requirements.txt'
        if requirements.is_file():
            for line in requirements.read_text().splitlines():
                if line.strip() and not line.startswith('#') and not re.fullmatch(r'[\w-]+==[\w.]+', line): fail(requirements, 'documentation dependencies must have exact versions')
    return sorted(findings)


def main(argv: list[str]) -> int:
    """Write deterministic validation reports and return a failing exit status."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=(*COMMANDS, 'all'))
    parser.add_argument('--root', default='.')
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    findings = validate_command(root, args.command)
    report = root / 'build/reports/documentation'
    report.mkdir(parents=True, exist_ok=True)
    rendered = [f.render() for f in findings]
    (report / f'{args.command}.json').write_text(json.dumps({'command': args.command, 'ok': not findings, 'findings': rendered}, indent=2) + '\n')
    (report / f'{args.command}.txt').write_text('\n'.join(rendered) + ('\n' if rendered else ''))
    for line in rendered: print(line, file=sys.stderr)
    return int(bool(findings))

if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
