# Nugget Admin Console

## Purpose
Demonstrates an operator-facing Phase 7 nugget administration console workflow using the production `j7m nugget` CLI path against the available local nugget demo fixtures. This directory is an operator runbook and is not a deployable J7M application bundle.

## Architecture flow
safe PIN source -> local/configured discovery -> target selection -> PIN admission -> `info` and `status` -> inspection commands -> one lifecycle operation through the existing demo fixture -> kill-disabled proof -> cleanup.

## Prerequisites
Use Java 25 and the repository Gradle wrapper. The executable validation uses local in-process demo providers, temporary workspaces, and the deterministic nugget names shown below. The copyable `j7m nugget` forms assume an operator environment where the production CLI can discover those fixture targets.

Set the PIN through an environment variable without placing a literal PIN on the command line:

```bash
read -rsp 'J7M demo PIN: ' J7M_DEMO_PIN
export J7M_DEMO_PIN
printf '\n'
```

## Build
Executable validation: `./gradlew validateNuggetAdminConsoleExample --no-daemon`.

## Test
Executable validation: `./gradlew validateNuggetAdminConsoleExample --no-daemon`.

## Package
Not applicable. This example intentionally has no `META-INF/j7m-application.json` and is not a deployable J7M application bundle.

## Verify bundle
Not applicable. There is no console bundle to verify.

## Deploy to local repository
Not applicable for this console runbook. The lifecycle proof below uses the existing deterministic application-manager fixture rather than packaging this directory.

## Start / activate
The console itself has no activation lifecycle. The existing lifecycle fixture proves one dry-run start command through the production CLI form:

```bash
j7m nugget start org.j7m.examples:hello-remote-service:1.0.0 --dry-run --id phase76-lifecycle-nugget --pin-env J7M_DEMO_PIN
```

## Invoke / verify behavior
Run discovery, selected-target status, and representative inspection through the tested command forms:

```bash
j7m nugget discover
j7m nugget info --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget status --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget ps --target phase7-demo --pin-env J7M_DEMO_PIN --wide
j7m nugget objects --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget apps --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget env --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget disk --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget health --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget readiness --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget metrics --target phase7-demo --pin-env J7M_DEMO_PIN
j7m nugget errors --target phase7-demo --pin-env J7M_DEMO_PIN
```

## Status / observability
`status`, `info`, and inspection commands use `--target` with `--pin-env` because the current `NuggetCliConsole` grammar accepts `--target` for read-only inspection flows. `--id` is reserved for `talk` and for lifecycle/control commands that explicitly support it.

## Stop / unload
Not applicable to this console runbook. The lifecycle fixture used by validation drains, stops, unloads, and verifies cleanup for its own temporary application instance.

## Upgrade / rollback, where applicable
Not applicable to this console runbook. Upgrade and rollback remain covered by the existing application upgrade/rollback demos.

## Expected output
`EXPECTED_OUTPUT.txt` contains only stable markers captured by the Phase 7 nugget CLI demo evidence files. `validateNuggetAdminConsoleExample` compares those markers with the actual root build reports before accepting the example.

## Security notes
Use `--pin-env` or an interactive prompt for operator PIN entry. Do not pass clear PIN values on command lines. Discovery records are only routing hints; authentication and authorization still occur through the nugget admin service. `kill-nugget` remains disabled by default and does not provide arbitrary process control:

```bash
j7m nugget kill-nugget phase7-demo --target phase7-demo --pin-env J7M_DEMO_PIN --confirm phase77-confirmation
```

## Cleanup
The validation tasks use temporary directories and close the runtime, admin service, application manager fixture, and CLI client connections deterministically.

## Known limitations
The example is local/provider-backed and operator-only. It does not include script mode, a concrete remote administration transport, HTTP, a browser UI, external control planes, SSH, Docker, Kubernetes, cloud SDKs, arbitrary process management, or a deployable console application.
