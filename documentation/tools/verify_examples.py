#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# https://www.egothor.org
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
# This product includes software developed by Leo Galambos and the Egothor
# team (https://www.egothor.org).

"""Compile/run the manual examples and check their exact source provenance.

Usage from any directory: python3 documentation/tools/verify_examples.py [--integration]
Inputs: repository source tree, example-sources.json, installed JDK 25 and the Gradle wrapper.
Outputs: documentation/examples/build/verification.json, Gradle test results and log.
Exit status is zero only when provenance and every requested check pass. This script never
changes production sources, publishes documentation or invokes the hour-long qualification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET


def _source(root: Path, relative: str) -> Path:
    """Resolve an existing repository source without allowing path traversal."""
    path = (root / relative).resolve()
    path.relative_to(root)
    if not path.is_file():
        raise ValueError(f"Missing source: {relative}")
    return path


def _provenance(root: Path) -> dict[str, str]:
    """Check named source methods and return SHA-256 values for cited current files."""
    catalog = json.loads((root / 'documentation/data/example-sources.json').read_text())
    hashes: dict[str, str] = {}
    for example in catalog['examples']:
        for relative in [example['source'], *example.get('supportingSources', [])]:
            hashes[relative] = hashlib.sha256(_source(root, relative).read_bytes()).hexdigest()
        for basis in example['basis']:
            path = _source(root, basis['source'])
            if not re.search(r'\b' + re.escape(basis['method']) + r'\s*\(', path.read_text()):
                raise ValueError(f"Missing source method: {basis['source']}::{basis['method']}")
            hashes[basis['source']] = hashlib.sha256(path.read_bytes()).hexdigest()
    for proof in catalog['unchangedIntegrationProofs']:
        path = _source(root, proof['source'])
        if not re.search(r'\b' + re.escape(proof['method']) + r'\s*\(', path.read_text()):
            raise ValueError(f"Missing integration method: {proof['source']}::{proof['method']}")
        hashes[proof['source']] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def _results(directory: Path, expected: int) -> dict[str, int]:
    """Reject missing, failing or incomplete JUnit XML evidence."""
    suites = [ET.parse(path).getroot() for path in sorted(directory.glob('TEST-*.xml'))]
    totals = {key: sum(int(suite.get(key, '0')) for suite in suites)
              for key in ['tests', 'failures', 'errors', 'skipped']}
    if totals != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}:
        raise ValueError(f"Unexpected JUnit results in {directory}: {totals}; expected {expected} passing tests")
    return totals


def _environment() -> dict[str, str]:
    """Honor configured Java options and supply proxy properties when only HTTPS_PROXY exists."""
    environment = os.environ.copy()
    options = environment.get('JAVA_TOOL_OPTIONS', '')
    proxy = urllib.parse.urlparse(environment.get('HTTPS_PROXY', environment.get('https_proxy', '')))
    if proxy.hostname and proxy.port and '-Dhttps.proxyHost=' not in options:
        options += f' -Dhttps.proxyHost={proxy.hostname} -Dhttps.proxyPort={proxy.port}'
        options += f' -Dhttp.proxyHost={proxy.hostname} -Dhttp.proxyPort={proxy.port}'
    if '-Dhttp.nonProxyHosts=' not in options:
        options += ' -Dhttp.nonProxyHosts=localhost|127.*|[::1]'
    environment['JAVA_TOOL_OPTIONS'] = options.strip()
    return environment


def _main() -> int:
    """Run documented verification and write a report bound to current example sources."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--integration', action='store_true', help='also run the three real managed integration proofs')
    parser.add_argument('--check-only', action='store_true', help='check source provenance without invoking Gradle')
    parser.add_argument('--offline', action='store_true', help='use only Gradle dependencies already in the local cache')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    hashes = _provenance(root)
    if args.check_only:
        print(f'Provenance OK: {len(hashes)} current source files')
        return 0
    output = root / 'documentation/examples/build'
    output.mkdir(parents=True, exist_ok=True)
    command = [str(root / 'gradlew'), '--no-daemon', '--max-workers=2', '-I',
               str(root / 'documentation/examples/compile.init.gradle'), 'documentationVerify']
    if args.offline:
        command.append('--offline')
    if args.integration:
        command.append('documentationIntegration')
    with (output / 'verification.log').open('w') as log:
        result = subprocess.run(command, cwd=root, env=_environment(), stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((output / 'verification.log').read_text(), file=sys.stderr)
        return result.returncode
    report: dict[str, object] = {'examples': _results(output / 'test-results', 5), 'sources': hashes}
    if args.integration:
        report['unchangedIntegrationProofs'] = _results(root / 'j7m-application-distributed/build/test-results/test', 3)
    if hashes != _provenance(root):
        raise ValueError('Cited sources changed during verification')
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Examples verified; report: {output.relative_to(root)}/verification.json')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(_main())
    except (OSError, ValueError, ET.ParseError) as failure:
        print(f'Example verification failed: {failure}', file=sys.stderr)
        raise SystemExit(1) from failure
