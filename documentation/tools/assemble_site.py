#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Combine a built MkDocs site and all module Javadocs into one Pages artifact.

Usage: python3 documentation/tools/assemble_site.py [--root REPO] [--site BUILD_DIR]
Inputs: LICENSE, module src/main/java trees and module build/docs/javadoc outputs.
Output: BUILD_DIR/javadoc with a module index and a copy of the project license.
Exit status: zero on success; nonzero on invalid input or filesystem failure.
"""
import argparse
from html import escape
from pathlib import Path
import shutil


def assemble_site(root: Path, site: Path) -> list[str]:
    """Install complete module API documentation beneath an existing manual.

    Args:
        root: Repository directory containing LICENSE and j7m-* modules.
        site: Built MkDocs output directory, contained under root/build.

    Returns:
        Sorted names of the Java modules included in the publication.

    Raises:
        ValueError: If the output escapes build, the manual/license is absent,
            or a Java module lacks its generated Javadoc index.
        OSError: If reading or copying the publication fails.
    """
    root, site = root.resolve(), site.resolve()
    if not site.is_relative_to(root / 'build') or site == root / 'build':
        raise ValueError('Publication output must be a directory below repository build/')
    if not (site / 'index.html').is_file() or not (root / 'LICENSE').is_file():
        raise ValueError('Build the MkDocs manual and provide LICENSE before assembly')
    modules = sorted(p for p in root.glob('j7m-*') if p.is_dir()
                     and any((p / 'src/main/java').rglob('*.java')))
    if not modules:
        raise ValueError('No Java modules found')
    for module in modules:
        if not (module / 'build/docs/javadoc/index.html').is_file():
            raise ValueError(f'Missing Javadoc for {module.name}; run ./gradlew javadoc')
    target = site / 'javadoc'
    if target.is_symlink():
        raise ValueError('Javadoc output must not be a symbolic link')
    if target.exists():
        shutil.rmtree(target)
    target.mkdir()
    for module in modules:
        shutil.copytree(module / 'build/docs/javadoc', target / module.name)
    links = '\n'.join(f'<li><a href="{escape(p.name)}/">{escape(p.name)}</a></li>' for p in modules)
    (target / 'index.html').write_text(f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>J7M Java API Reference</title>
<style>body{{max-width:68rem;margin:2rem auto;padding:0 1rem;font:16px/1.6 system-ui;color:#172637}}a{{color:#075e91}}ul{{columns:18rem}}footer{{border-top:1px solid #ccd5dd;margin-top:2rem;padding-top:1rem;font-size:.85rem}}</style>
</head><body><nav><a href="../">J7M documentation</a></nav>
<h1>Java API Reference</h1><p>Select a module for its classes, interfaces and method contracts.</p>
<ul>{links}</ul><footer>Copyright © 2026 Leo Galambos and the Egothor team.<br>
This product includes software developed by Leo Galambos and the Egothor team
(<a href="https://www.egothor.org">https://www.egothor.org</a>).<br>
<a href="LICENSE">BSD 4-Clause License</a></footer></body></html>
''', encoding='utf-8')
    shutil.copyfile(root / 'LICENSE', target / 'LICENSE')
    (site / '.nojekyll').touch()
    return [module.name for module in modules]


def main() -> None:
    """Parse publication paths and assemble the site, propagating build failures."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--site', type=Path, default=Path('build/pages'))
    args = parser.parse_args()
    modules = assemble_site(args.root, args.site)
    print(f'Assembled manual and Javadoc for {len(modules)} modules in {args.site}')


if __name__ == '__main__':
    main()
