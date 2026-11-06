# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
# Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
"""Exercise release input validation and Central bundles against real temporary files."""

import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import re
import unittest
import zipfile

from prepare_central_bundle import ARTIFACTS, prepare_bundle, validate_version, version_from_tag


class ReleaseTagTest(unittest.TestCase):
    """Tags form a safe, exact version source for Gradle and the repository paths."""

    def test_validates_version_boundaries_directly(self):
        """Version validation accepts the lowest stable value and rejects unsafe input."""
        self.assertEqual("0.0.0", validate_version("0.0.0"))
        self.assertEqual("1.2.3-rc.1", validate_version("1.2.3-rc.1"))
        for version in ("", "0.0", "1.2.3-SNAPSHOT", "1.2.3\n", "../1.2.3"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                validate_version(version)

    def test_accepts_release_and_prerelease(self):
        """Stable and prerelease versions retain their exact spelling."""
        for version in ("0.0.0", "1.2.3", "12.34.56-rc.1", "1.0.0-beta-2"):
            with self.subTest(version=version):
                self.assertEqual(version, version_from_tag("release@" + version))

    def test_rejects_malformed_and_snapshot_tags(self):
        """Invalid versions cannot become file paths or workflow output directives."""
        for tag in ("", "v1.2.3", "release@", "release@1.2", "release@1.2.3-SNAPSHOT",
                    "release@1.2.3-snapshot", "release@1.2.3/../../x", "release@1.2.3\nother=x",
                    "release@1.2.3+build", "release@1.2.3-"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version_from_tag(tag)

    def test_cli_prints_only_version_and_fails_on_invalid_input(self):
        """The command produces one GITHUB_OUTPUT-safe value and rejects invalid input."""
        script = str(Path(__file__).with_name("prepare_central_bundle.py"))
        result = subprocess.run([sys.executable, script, "--tag", "release@1.2.3"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode)
        self.assertEqual("1.2.3\n", result.stdout)
        result = subprocess.run([sys.executable, script, "--tag", "release@1.2.3-SNAPSHOT"], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)


class PublicationScopeTest(unittest.TestCase):
    """The release allowlist covers middleware use without development/test tools."""

    def test_includes_runtime_features_and_excludes_build_and_test_tools(self):
        """The intended runtime feature set has seventeen separately consumable modules."""
        self.assertEqual(17, len(ARTIFACTS))
        self.assertTrue({"j7m-api", "j7m-kernel", "j7m-runtime", "j7m-application-platform",
                         "j7m-transport-grpc", "j7m-transport-rmi", "j7m-adapter-spi",
                         "j7m-application-distributed", "j7m-cluster-management"}.issubset(ARTIFACTS))
        excluded = {"j7m-annotation-processor", "j7m-deployer-cli", "j7m-gradle-plugin",
                    "j7m-adapter-tck", "j7m-testkit", "j7m-examples", "j7m-demo", "j7m-tests"}
        self.assertFalse(ARTIFACTS & excluded)

    def test_runtime_project_dependencies_are_in_published_inventory(self):
        """The current Gradle project dependencies form a closed runtime publication set."""
        root = Path(__file__).resolve().parents[1]
        pattern = re.compile(r"^\s*(?:api|implementation|runtimeOnly)\s+project\(['\"]:([^'\"]+)['\"]\)", re.MULTILINE)
        for artifact in ARTIFACTS:
            build = root / artifact / "build.gradle"
            dependencies = set(pattern.findall(build.read_text())) if build.exists() else set()
            with self.subTest(artifact=artifact):
                self.assertFalse(dependencies - ARTIFACTS, f"unpublished runtime dependencies: {dependencies - ARTIFACTS}")


class CentralBundleTest(unittest.TestCase):
    """Real repository fixtures cover bundle contents and release-blocking failures."""

    def setUp(self):
        """Create a complete publication with explicitly synthetic signature fixtures."""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repository = Path(self.temp.name) / "repository"
        self.output = Path(self.temp.name) / "bundle.zip"
        for artifact in ARTIFACTS:
            directory = self.repository / "org/egothor" / artifact / "1.2.3"
            directory.mkdir(parents=True)
            prefix = directory / f"{artifact}-1.2.3"
            pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<groupId>org.egothor</groupId><artifactId>{artifact}</artifactId><version>1.2.3</version>
<name>J7M</name><description>Middleware</description><url>https://www.egothor.org</url>
<licenses><license><name>BSD 4-Clause</name><url>https://www.egothor.org</url></license></licenses>
<developers><developer><name>Leo Galambos</name></developer></developers>
<scm><connection>scm:git:https://github.com/Egothor/J7M.git</connection><url>https://github.com/Egothor/J7M</url></scm>
</project>'''
            for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar", ".module"):
                path = Path(str(prefix) + suffix)
                path.write_text(pom if suffix == ".pom" else "artifact fixture")
                Path(str(path) + ".asc").write_text("synthetic signature fixture")
        self.pom = next(self.repository.rglob("*.pom"))

    def test_bundle_has_coordinates_signatures_and_correct_checksums(self):
        """ZIP keeps Maven paths and signatures and hashes primary artifacts only."""
        metadata = self.pom.parent.parent / "maven-metadata.xml"
        metadata.write_text("mutable repository metadata")
        prepare_bundle(self.repository, self.output, "1.2.3")
        with zipfile.ZipFile(self.output) as bundle:
            name = self.pom.relative_to(self.repository).as_posix()
            self.assertEqual(self.pom.read_bytes(), bundle.read(name))
            self.assertIn(name + ".asc", bundle.namelist())
            self.assertEqual(hashlib.sha1(self.pom.read_bytes()).hexdigest().encode(), bundle.read(name + ".sha1"))
            self.assertEqual(hashlib.md5(self.pom.read_bytes()).hexdigest().encode(), bundle.read(name + ".md5"))
            self.assertFalse(any("maven-metadata" in item for item in bundle.namelist()))
            self.assertFalse(any(".asc.sha1" in item for item in bundle.namelist()))

    def test_rejects_missing_signature(self):
        """Unsigned publications fail before a bundle is created."""
        Path(str(self.pom) + ".asc").unlink()
        with self.assertRaisesRegex(ValueError, "signature"):
            prepare_bundle(self.repository, self.output, "1.2.3")
        self.assertFalse(self.output.exists())

    def test_rejects_missing_sources_and_empty_signature(self):
        """Binary-only jars and empty signature placeholders cannot pass staging."""
        sources = self.pom.with_name(self.pom.stem + "-sources.jar")
        saved = sources.read_bytes()
        sources.unlink()
        with self.assertRaisesRegex(ValueError, "artifact/signature"):
            prepare_bundle(self.repository, self.output, "1.2.3")
        sources.write_bytes(saved)
        Path(str(self.pom) + ".asc").write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "artifact/signature"):
            prepare_bundle(self.repository, self.output, "1.2.3")

    def test_rejects_wrong_internal_dependency_coordinates(self):
        """Published modules must depend on the same release in the owned namespace."""
        original = self.pom.read_text()
        for group, version in (("org.j7m", "1.2.3"), ("org.egothor", "0.1.0-SNAPSHOT")):
            dependency = f"<dependencies><dependency><groupId>{group}</groupId><artifactId>j7m-api</artifactId><version>{version}</version></dependency></dependencies>"
            self.pom.write_text(original.replace("</project>", dependency + "</project>"))
            with self.assertRaisesRegex(ValueError, "J7M dependency"):
                prepare_bundle(self.repository, self.output, "1.2.3")

    def test_rejects_dependency_on_unpublished_tools(self):
        """A runtime artifact cannot silently require an excluded tool or test library."""
        original = self.pom.read_text()
        for artifact in ("j7m-annotation-processor", "j7m-deployer-cli", "j7m-gradle-plugin",
                         "j7m-adapter-tck", "j7m-testkit"):
            dependency = f"<dependencies><dependency><groupId>org.egothor</groupId><artifactId>{artifact}</artifactId><version>1.2.3</version></dependency></dependencies>"
            self.pom.write_text(original.replace("</project>", dependency + "</project>"))
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, "unpublished J7M dependency"):
                prepare_bundle(self.repository, self.output, "1.2.3")
            self.assertFalse(self.output.exists())

    def test_rejects_missing_module(self):
        """A successful subset of Gradle publications cannot be released alone."""
        self.pom.unlink()
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            prepare_bundle(self.repository, self.output, "1.2.3")

    def test_rejects_wrong_coordinate_and_missing_metadata(self):
        """POM namespace and Central-required metadata are mandatory."""
        original = self.pom.read_text()
        for before, after in (("<groupId>org.egothor", "<groupId>org.j7m"),
                              ("<name>J7M</name>", "")):
            self.pom.write_text(original.replace(before, after))
            with self.assertRaises(ValueError):
                prepare_bundle(self.repository, self.output, "1.2.3")

    def test_rejects_empty_repository_and_wrong_version(self):
        """Empty or stale staging directories cannot produce a release bundle."""
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            prepare_bundle(Path(self.temp.name) / "missing", self.output, "1.2.3")
        with self.assertRaisesRegex(ValueError, "path"):
            prepare_bundle(self.repository, self.output, "2.0.0")


if __name__ == "__main__":
    unittest.main()
