#!/usr/bin/env python3
# Copyright (c) 2026, Leo Galambos and the Egothor team
# SPDX-License-Identifier: BSD-4-Clause
"""Build a cold external developer project and run an unpacked installed platform.

Inputs are a local Maven validation repository, built starter/platform ZIPs and
an installed Gradle executable. Work and logs live in a temporary directory;
no project dependencies, source checkout classpaths or real credentials are used.
The executable exits nonzero if startup, shutdown, restart or trust checks fail.
"""

import argparse
import hashlib
import shutil
import os
from pathlib import Path
import signal
import re
import subprocess
import tempfile
import time
import zipfile
from urllib.parse import urlparse


def _extract(archive, destination):
    with zipfile.ZipFile(archive) as zipped:
        for entry in zipped.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("archive path escapes extraction directory")
            zipped.extract(entry, destination)
            mode = entry.external_attr >> 16
            if mode:
                target.chmod(mode & 0o777)
    roots = [path for path in destination.iterdir() if path.is_dir()]
    if len(roots) != 1:
        raise ValueError("distribution must contain exactly one root directory")
    return roots[0]


def _run(command, directory, log, environment=None):
    with log.open("w") as output:
        result = subprocess.run(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, timeout=240, env=environment)
    if result.returncode:
        raise AssertionError(f"command failed ({result.returncode}): {log}\n{log.read_text()[-8000:]}")


def _host(command, directory, log, expect_running=True):
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            end = time.monotonic() + 45
            while process.poll() is None and time.monotonic() < end:
                text = log.read_text()
                if "J7M_PLATFORM_RUNNING example:hello:1.0.0" in text:
                    if not expect_running:
                        raise AssertionError("untrusted application was activated")
                    break
                time.sleep(0.05)
            else:
                if not expect_running and process.poll() is not None and process.returncode != 0:
                    if "HELLO_STARTED" in log.read_text():
                        raise AssertionError("rejected application ran startup code")
                    return
                raise AssertionError(f"host did not reach expected state: {log}\n{log.read_text()[-8000:]}")
        finally:
            if process.poll() is None:
                # Gradle cancellation forcibly stops JavaExec children. Signal the
                # actual host so this acceptance test observes its shutdown hook.
                identity = re.search(r"^J7M_PLATFORM_PID ([0-9]+)$", log.read_text(), re.MULTILINE)
                if identity:
                    os.kill(int(identity.group(1)), signal.SIGTERM)
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
    text = log.read_text()
    if "HELLO_STARTED" not in text or "HELLO_STOPPED" not in text or "J7M_PLATFORM_STOPPED" not in text:
        raise AssertionError(f"actual application lifecycle was incomplete: {log}\n{text[-8000:]}")


def main():
    """Run developer and operator acceptance scenarios; raise on any failed assertion."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--starter", required=True, type=Path)
    parser.add_argument("--platform", required=True, type=Path)
    parser.add_argument("--gradle", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--example-output", type=Path)
    parser.add_argument("--example-readme", type=Path)
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix="j7m-consumer-"))
    print(f"Consumer qualification workspace: {work}", flush=True)
    developer = _extract(args.starter.resolve(), work / "developer")
    platform = _extract(args.platform.resolve(), work / "operator")
    if not os.access(developer / "gradlew", os.X_OK):
        raise AssertionError("starter Gradle wrapper is not executable")
    environment = os.environ.copy()
    environment["GRADLE_USER_HOME"] = str(work / "gradle-home")
    proxy_url = urlparse(environment.get("HTTPS_PROXY", ""))
    if proxy_url.hostname:
        environment["GRADLE_OPTS"] = " ".join([environment.get("GRADLE_OPTS", ""),
            f"-Dhttps.proxyHost={proxy_url.hostname}", f"-Dhttps.proxyPort={proxy_url.port or 80}",
            f"-Dhttp.proxyHost={proxy_url.hostname}", f"-Dhttp.proxyPort={proxy_url.port or 80}"])
    distribution = os.environ.get("J7M_CONSUMER_GRADLE_ZIP")
    if distribution:
        wrapper = dict(line.split("=", 1) for line in (developer / "gradle/wrapper/gradle-wrapper.properties").read_text().splitlines()
                       if "=" in line and not line.startswith("#"))
        selected_zip = Path(distribution)
        actual = hashlib.sha256(selected_zip.read_bytes()).hexdigest()
        if actual != wrapper.get("distributionSha256Sum"):
            raise AssertionError("preloaded Gradle distribution does not match the wrapper SHA-256")
        url = wrapper["distributionUrl"].replace("\\:", ":")
        digest = int.from_bytes(hashlib.md5(url.encode()).digest(), "big")
        alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        address = ""
        while digest:
            digest, remainder = divmod(digest, 36)
            address = alphabet[remainder] + address
        filename = url.rsplit("/", 1)[-1]
        cache = Path(environment["GRADLE_USER_HOME"]) / "wrapper/dists" / filename.removesuffix(".zip") / address
        cache.mkdir(parents=True)
        shutil.copyfile(selected_zip, cache / filename)
        print(f"Preloaded only checksum-verified Gradle distribution: {actual}", flush=True)
    _run([str(developer / "gradlew"), "--version"], developer, work / "wrapper-version.log", environment)
    gradle = [str(args.gradle.resolve()), "--gradle-user-home", str(work / "gradle-home"), "--no-daemon", f"-Pj7mVersion={args.version}",
              f"-Pj7mRepository={args.repository.resolve().as_uri()}"]
    # Respect only transport configuration inherited by the build, never replace
    # consumer dependencies with the source repository's implementation outputs.
    proxy = os.environ.get("J7M_CONSUMER_INIT_SCRIPT")
    if proxy:
        gradle += ["-I", proxy]
    _run(gradle + ["clean", "build", "j7mVerify"], developer, work / "developer-build.log")
    with zipfile.ZipFile(developer / "build/libs/hello.jar") as jar:
        if "META-INF/services/org.j7m.middleware.application.api.ManagedComponentIndex" not in jar.namelist():
            raise AssertionError("annotation processor did not generate the application index")
    with zipfile.ZipFile(developer / "build/distributions/hello.j7m") as bundle:
        if any("j7m-api-" in name or "j7m-runtime-" in name for name in bundle.namelist()):
            raise AssertionError("application bundle duplicates platform libraries")
    _host(gradle + ["run"], developer, work / "developer-run.log")
    _host(gradle + ["run"], developer, work / "developer-rerun.log")
    developer_configuration = developer / "conf/developer.properties"
    developer_configuration.write_text(developer_configuration.read_text().replace("Hello from Gradle", "Hello after developer edit"))
    _host(gradle + ["run"], developer, work / "developer-edited-run.log")
    if "HELLO_STARTED Hello after developer edit" not in (work / "developer-edited-run.log").read_text():
        raise AssertionError("developer configuration changes were ignored on a subsequent run")
    (platform / "applications").mkdir(exist_ok=True)
    (platform / "trust").mkdir(exist_ok=True)
    (platform / "applications/hello.j7m").write_bytes((developer / "build/distributions/hello.j7m").read_bytes())
    public_key = platform / "trust/publisher.der"
    public_key.write_bytes((developer / "build/signing/public.der").read_bytes())
    command = [str(platform / "bin/j7m-platform"), "--config", "conf/platform.properties"]
    _host(command, platform, work / "operator-run.log")
    _host(command, platform, work / "operator-restart.log")
    original_key = public_key.read_bytes()
    key_source = work / "WrongKey.java"
    key_source.write_text("""import java.nio.file.*; import java.security.*;
class WrongKey { public static void main(String[] args) throws Exception {
Files.write(Path.of(args[0]), KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
} }""")
    _run(["java", str(key_source), str(public_key)], work, work / "wrong-key-generation.log")
    _host(command, platform, work / "operator-wrong-key.log", expect_running=False)
    public_key.write_bytes(original_key)
    configuration = platform / "conf/platform.properties"
    original_configuration = configuration.read_text()
    invalid_arguments = subprocess.run([str(platform / "bin/j7m-platform")], cwd=platform,
                                       capture_output=True, text=True, timeout=10)
    if invalid_arguments.returncode != 2 or "Usage:" not in invalid_arguments.stderr:
        raise AssertionError("invalid platform arguments did not fail with usage and exit status 2")
    configuration.write_text(original_configuration + "\nsignature.required=false\n")
    _host(command, platform, work / "operator-unknown-setting.log", expect_running=False)
    configuration.write_text(original_configuration)
    application_bundle = platform / "applications/hello.j7m"
    original_bundle = application_bundle.read_bytes()
    with zipfile.ZipFile(application_bundle) as source:
        entries = {name: source.read(name) for name in source.namelist()}
    selected = next(name for name in entries if name.endswith(".jar"))
    entries[selected] = entries[selected] + b"tampered"
    with zipfile.ZipFile(application_bundle, "w") as tampered:
        for name, data in entries.items():
            tampered.writestr(name, data)
    # A fresh repository proves signature/checksum rejection, not only immutable
    # coordinate conflict against the previous successful installation.
    configuration.write_text(original_configuration.replace("../var/repository", "../var/rejected-repository"))
    _host(command, platform, work / "operator-tampered-bundle.log", expect_running=False)
    with zipfile.ZipFile(application_bundle, "w") as unsigned:
        with zipfile.ZipFile(developer / "build/distributions/hello.j7m") as signed:
            for name in signed.namelist():
                if name != "META-INF/j7m-signature.json":
                    unsigned.writestr(name, signed.read(name))
    configuration.write_text(original_configuration.replace("../var/repository", "../var/unsigned-repository"))
    _host(command, platform, work / "operator-unsigned-bundle.log", expect_running=False)
    application_bundle.write_bytes(original_bundle)
    configuration.write_text(original_configuration)
    _host(command, platform, work / "operator-restored-after-rejection.log")
    if args.example_output:
        if not args.example_readme:
            raise ValueError("example release requires its operator README")
        args.example_output.parent.mkdir(parents=True, exist_ok=True)
        prefix = f"j7m-hello-{args.version}/"
        with zipfile.ZipFile(args.example_output, "w", compression=zipfile.ZIP_DEFLATED) as example:
            example.writestr(prefix + "hello.j7m", original_bundle)
            example.writestr(prefix + "publisher.der", original_key)
            example.writestr(prefix + "README.md", args.example_readme.read_bytes())
        print(f"Verified operator example archive: {args.example_output}", flush=True)
    print("External developer build/run and installed operator startup/shutdown/restart/trust checks passed.", flush=True)


if __name__ == "__main__":
    main()
