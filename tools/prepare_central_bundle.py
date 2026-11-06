#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
"""Validate release tags and create a signed Sonatype Central Publisher bundle.

CLI: --tag release@VERSION prints VERSION; alternatively --repository DIR
--output ZIP --version VERSION validates the complete J7M publication and writes
its files plus checksums. Invalid input exits nonzero without uploading anything.
"""

import argparse
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", re.ASCII)
# Gradle and the bundle validator consume the same explicit runtime allowlist.
ARTIFACTS = frozenset(line.strip() for line in
                      (Path(__file__).resolve().parents[1] / "gradle/central-publications.txt").read_text().splitlines()
                      if line.strip() and not line.lstrip().startswith("#"))
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def validate_version(version):
    """Return a safe release version, raising ValueError for malformed or snapshot input."""
    if not VERSION.fullmatch(version) or "SNAPSHOT" in version.upper():
        raise ValueError("expected a non-SNAPSHOT version such as 1.2.3 or 1.2.3-rc.1")
    return version


def version_from_tag(tag):
    """Return the version in release@VERSION; raise ValueError for any other tag."""
    if not tag.startswith("release@"):
        raise ValueError("release tag must start with release@")
    return validate_version(tag[len("release@"):])


def prepare_bundle(repository, output, version):
    """Validate all J7M artifacts and write a Central ZIP with checksums.

    Args:
        repository: Path to the Gradle file Maven repository.
        output: Destination ZIP path, created only after validation succeeds.
        version: Non-SNAPSHOT release version shared by all modules.

    Raises:
        ValueError: Incomplete, empty, mismatched or unsigned publication.
        OSError: A publication cannot be read or the ZIP cannot be written.
        ET.ParseError: A POM is malformed.
    """
    validate_version(version)
    repository, output = Path(repository), Path(output)
    poms = sorted(repository.rglob("*.pom"))
    actual = set()
    files = {}
    for pom in poms:
        relative = pom.relative_to(repository)
        artifact = pom.parent.parent.name
        prefix = f"{artifact}-{version}"
        if relative != Path("org/egothor") / artifact / version / f"{prefix}.pom":
            raise ValueError(f"unexpected publication path: {relative}")
        actual.add(artifact)
        root = ET.parse(pom).getroot()
        expected = {"groupId": "org.egothor", "artifactId": artifact, "version": version}
        for field, value in expected.items():
            if root.findtext(f"m:{field}", namespaces=NS) != value:
                raise ValueError(f"incorrect {field} in {relative}")
        for field in ("name", "description", "url", "licenses/m:license/m:name",
                      "licenses/m:license/m:url", "developers/m:developer/m:name", "scm/m:connection", "scm/m:url"):
            if not root.findtext(f"m:{field}", namespaces=NS):
                raise ValueError(f"missing {field} in {relative}")
        for dependency in root.findall("m:dependencies/m:dependency", NS):
            group = dependency.findtext("m:groupId", namespaces=NS)
            dep_artifact = dependency.findtext("m:artifactId", namespaces=NS)
            if dep_artifact and dep_artifact.startswith("j7m-"):
                if dep_artifact not in ARTIFACTS:
                    raise ValueError(f"unpublished J7M dependency {dep_artifact} in {relative}")
                if group != "org.egothor" or dependency.findtext("m:version", namespaces=NS) != version:
                    raise ValueError(f"incorrect J7M dependency in {relative}")
        primary = [pom.parent / f"{prefix}{suffix}" for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")]
        module = pom.parent / f"{prefix}.module"
        if module.exists():
            primary.append(module)
        for path in primary:
            for candidate in (path, Path(str(path) + ".asc")):
                if not candidate.is_file() or candidate.is_symlink() or candidate.stat().st_size == 0:
                    raise ValueError(f"missing or empty artifact/signature: {candidate}")
                files[candidate.relative_to(repository).as_posix()] = candidate.read_bytes()
    if actual != ARTIFACTS:
        raise ValueError(f"publication inventory mismatch: missing={sorted(ARTIFACTS - actual)}, unexpected={sorted(actual - ARTIFACTS)}")
    # Repository-level Maven metadata is for mutable repositories, not Central bundles.
    for name, data in list(files.items()):
        if not name.endswith(".asc"):
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                files[f"{name}.{algorithm}"] = hashlib.new(algorithm, data).hexdigest().encode("ascii")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(files.items()):
            bundle.writestr(name, data)


def main():
    """Run the CLI; argparse reports invalid tags/publications with exit status 2."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version")
    args = parser.parse_args()
    try:
        if args.tag is not None:
            if any((args.repository, args.output, args.version)):
                parser.error("--tag cannot be combined with bundle arguments")
            print(version_from_tag(args.tag))
        elif all((args.repository, args.output, args.version)):
            prepare_bundle(args.repository, args.output, args.version)
        else:
            parser.error("provide --tag or all of --repository, --output and --version")
    except (ValueError, OSError, ET.ParseError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
