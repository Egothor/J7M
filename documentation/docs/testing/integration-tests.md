# Integration testing with actual resources

The examples' tests are adaptations of current project tests and are executed again. The source provenance records the exact classes/methods they use; a successful original test is not taken as proof of an edited snippet.

## Run the documented examples

```shell
python3 documentation/tools/verify_examples.py
```

Use the [verified example inventory](verified-examples.md) to interpret the result. It distinguishes generated DI, schema codecs, real gRPC and a local queue reducer.

## Exercise managed distributed hosting

```shell
python3 documentation/tools/verify_examples.py --integration
```

This includes selected original generated queue, job and saga tests with their real backend setup. They validate exact required policy identity and actual generated callback paths, rather than compiling a descriptive annotation and assuming it runs.

## Match the test to the integration

For discovery and refresh, run `LiveServiceBindingProcessIT` (repository source: `j7m-application-platform/src/test/java/org/j7m/middleware/application/components/LiveServiceBindingProcessIT.java`) and `NetworkBootstrapEndpointResolverTest` (repository source: `j7m-runtime/src/test/java/org/j7m/middleware/registry/bootstrap/NetworkBootstrapEndpointResolverTest.java`). For SQL delivery, use `JdbcQueueProcessIT` (repository source: `j7m-application-distributed/src/test/java/org/j7m/middleware/application/distributed/JdbcQueueProcessIT.java`) and the inbox/outbox tests with actual H2 TCP. For native ownership movement, use the partition/ownership migration tests and inspect which fixture uses multi-voter versus single-voter groups.

Some process tests deliberately kill a frontend or worker. Keep their evidence directories until a failure is understood. A test's timeout must report a failed bound, not be used as proof that an uncooperative application physically exited.

## Minimal assertions for a new handler

Check successful output, invalid data, boundary sizes and policy rejection. Then check an uncertain reply, duplicate identity, ownership change and physical drain. For an external effect, use a real controlled endpoint or faithful local equivalent and assert its durable identity/count. Do not infer nonexecution from a thrown transport exception.

See [failure injection](failure-injection.md) for crash windows and [operational recovery](../operations/recovery.md) for production ownership.
