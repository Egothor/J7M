# Operations and Administration

Operate the deployed boundaries explicitly: runtime/application lifecycle, discovery, transports, artifact repositories and durable resource groups. Each has its own authority and recovery state.

Use [inspection](inspection.md) to establish what is actually running, [monitoring](monitoring.md) to identify trends and [runbooks](runbooks.md) to respond to failures. Follow the separate [backup and restore procedure](recovery.md) when retained state must be recovered. Use [capacity planning](capacity.md) before increasing admission or storage limits.

An administrative command is not successful merely because it was submitted. Inspect the typed result, target identity, current generation and outcome. A connection failure during a command may leave an uncertain remote result; repeat only under the operation's documented identity/idempotency contract.

Keep operator records safe and useful: application coordinate, runtime/instance identity, requested action, time, result code, relevant epoch/receipt version and correlation. Do not copy PINs, authentication keys, private paths containing secrets or business payloads into incident notes.

This guide uses the actual headless administration and CLI contracts. It does not imply a browser administration console, a fleet-wide controller or an unrestricted HTTP management endpoint.
