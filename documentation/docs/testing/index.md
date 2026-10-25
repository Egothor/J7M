# Testing distributed applications

Test the boundary that provides the guarantee. A pure reducer unit test, an actual network integration test and a process-kill recovery test answer different questions.

| Level | Useful assertion | What it does not establish |
| --- | --- | --- |
| Unit/schema/processor | Invalid metadata fails; pure state transitions are correct | Network, disk or process recovery |
| Actual hosted integration | Generated adapter, required policy and resource work together | Every deployment topology |
| Multi-JVM process | Ownership, delivery or recovery survives a specific injected failure | Universal external exactly-once behavior |
| Opt-in qualification | Sustained behavior under the defined profile | A different profile or untested implementation |

Start with [the executable manual examples](verified-examples.md), then [integration tests](integration-tests.md) and [failure injection](failure-injection.md).

## Test outcomes, not declarations

A syntactically correct `@DurableJob` method proves neither that a job host was installed nor that recovery works. Test missing-policy rejection before constructors, actual claimed delivery, stale-fence rejection, owned async completion and durable restart separately.

Likewise, a mocked successful queue ACK does not prove the receipt/generation checks of a real endpoint. Run the appropriate native/managed test when modifying that boundary.

For alternative resource implementations, run the relevant [adapter SPI/TCK semantic profile](../programming/adapters.md). Review required scenarios, reference operations, failure-mode outcomes, redaction findings and compatibility limitations. A provider descriptor is a claim; an executed profile provides bounded evidence for that provider and configuration.

## Keep ordinary feedback bounded

Run relevant unit/integration tests for the change, then required static/documentation checks. Long qualification remains explicit opt-in; the manual's normal example verification does not run it. Do not shorten a promised guarantee by quietly deleting failure assertions or replacing actual resources with mocks.
