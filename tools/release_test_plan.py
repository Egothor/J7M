#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
"""Print the release test matrix; every ordinary Gradle test belongs to one runner.

CLI: release_test_plan.py [--settings PATH] emits a compact GitHub matrix JSON
object. Missing dedicated projects or unsupported include declarations fail
before any test can be excluded from the remaining runner.
"""

import argparse
import json
from pathlib import Path
import re

DEDICATED_SHARDS = (
    ("distributed", ("j7m-distributed-core", "j7m-integrations")),
    ("applications", ("j7m-application-distributed", "j7m-cluster-management")),
)


def release_test_matrix(settings):
    """Return three disjoint test commands for the projects declared in settings.

    Args:
        settings: Path to this repository's Groovy settings.gradle file.

    Returns:
        GitHub matrix with two explicit test selections and a remaining check.

    Raises:
        ValueError: A dedicated project is missing or an include cannot be read.
        OSError: The settings file cannot be read.
    """
    projects = set()
    for declaration in re.findall(r"(?m)^\s*include(?:\s+|\s*\()([^\n]+)", Path(settings).read_text()):
        names = re.findall(r"[\"']([^\"']+)[\"']", declaration)
        if not names or any(not re.fullmatch(r":?[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*", name) for name in names):
            raise ValueError("unsupported project include declaration")
        projects.update(name.lstrip(":") for name in names)
    claimed = [project for _, group in DEDICATED_SHARDS for project in group]
    if len(set(claimed)) != len(claimed) or not set(claimed).issubset(projects):
        raise ValueError("dedicated test projects must exist and belong to exactly one shard")
    entries = [{"shard": name, "tasks": " ".join(f":{project}:test" for project in group)}
               for name, group in DEDICATED_SHARDS]
    excluded = " ".join(f"-x :{project}:test" for project in claimed)
    entries.append({"shard": "remaining", "tasks": f"check {excluded}"})
    return {"include": entries}


def main():
    """Print the matrix; invalid settings exit nonzero before a workflow can run."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, default=Path(__file__).resolve().parents[1] / "settings.gradle")
    args = parser.parse_args()
    try:
        print(json.dumps(release_test_matrix(args.settings), separators=(",", ":")))
    except (ValueError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
