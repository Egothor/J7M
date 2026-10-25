# Build and verify the examples

Run from the repository root. The following command compiles the current project dependencies, invokes the actual J7M annotation processor for the manual examples and executes the adapted examples again:

```shell
python3 documentation/tools/verify_examples.py
```

The equivalent Gradle task is installed only for that invocation:

```shell
./gradlew -I documentation/examples/compile.init.gradle documentationVerify
```

Generated classes and reports remain under `documentation/examples/build/`. The init script does not change the normal root build or install middleware defaults.

## Expected result

All five executable example checks succeed: container, schema, runtime, queue and failover. The verifier writes a machine-readable verification record with counts and source hashes. A passing source test is not used as a substitute for executing its adapted documentation example.

After dependencies have been cached, use the verifier's offline option when appropriate:

```shell
python3 documentation/tools/verify_examples.py --offline
```

Offline dependency resolution does not disable the loopback sockets used by the network example.

## Add actual managed distributed hosting

```shell
python3 documentation/tools/verify_examples.py --integration
```

This also executes selected original generated queue, job and saga integration tests. The host and native backend setup is retained, rather than replaced by a pretend one-line worker. It does not start the opt-in hour-long qualification.

See [verified examples and their limits](../testing/verified-examples.md), then [run the first application component](first-application.md).
