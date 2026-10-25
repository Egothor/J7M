# Command-Line Reference

The `j7m` launcher has two command families: local application tooling (`app`) and provider-backed Nugget administration (`nugget`). A remote Nugget requires an installed `NuggetCliClientProvider`; command parsing itself does not install a network listener or remote administration protocol.

## Application tools

```text
j7m app package|sign|verify|deploy|plan|start|list|status|stop|unload|upgrade|rollback
j7m app repository status|verify-audit
```

Common options are `--project PATH`, `--bundle PATH`, `--repository PATH`, `--coordinate COORDINATE`, `--profile PROFILE` and `--json`. Signing has `--signing-key ID`; upgrades use `--strategy STRATEGY` and `--to COORDINATE`. `--strict-production` and `--strict-regulated` select the corresponding verification profile. The default profile is development. The local application's CLI lifecycle path must not be confused with a live remote Nugget or fleet controller; use truthful runtime availability results.

## Nugget command families

| Purpose | Commands |
|---|---|
| Discover and select | `discover`, `nuggets`, `talk`, `talk all`, `talk NAME`, `talk --id ID`, `target`, `whoami` |
| Admit/reconnect | `pin`, `pin clear`, `pin clear --all`, `auth`, `reconnect` |
| Inspect | `info`, `status`, `ps`, `ls`, `objects`, `object ID`, `apps`, `app ID` |
| Host diagnostics | `env`, `disk`, `health`, `readiness`, `metrics`, `errors` |
| Application lifecycle | `start COORDINATE`, `drain INSTANCE_ID`, `stop INSTANCE_ID`, `unload INSTANCE_ID` |
| Nugget lifecycle | `drain-nugget NUGGET`, `shutdown-nugget NUGGET`, `kill-nugget NUGGET` |
| Console | `help`, `exit`, `quit` |

Use `j7m nugget help` for the installed parser's command forms. Commands accept only their allowlisted options. Lifecycle one-shot commands support `--target NAME` or `--id ID` (mutually exclusive), `--pin-env NAME`, `--json` and `--width N`; start also supports `--dry-run`. Shutdown/kill use `--confirm CHALLENGE` when the server requests a confirmation. Kill remains disabled unless the host explicitly implements and enables it.

## Target and admission

Names must resolve unambiguously. Exact target identity and runtime generation protect mutations against a restarted or replaced target; prompt text and display names are not authority. All-target inspection can be useful, but an all-target selection must not turn into an unrestricted mass mutation.

```bash
j7m nugget status --target crawler-east --pin-env J7M_ADMIN_PIN --json
j7m nugget start example:crawler:2.0.0 --target crawler-east --pin-env J7M_ADMIN_PIN --dry-run --json
```

These are operator command templates: supply the actual target and a manifest coordinate accepted by its repository. Obtain the secret environment value through a protected provisioning mechanism. `auth` admits the selected target; authorization still applies per request. `reconnect` refreshes the exact selected identity and revalidates connection state. It does not automatically retry an uncertain mutation against a replacement process.

## Queries and bounded output

Collection commands such as `ps`, `ls`, `objects` and `apps` accept `--filter key=value`, `--sort FIELD`, `--asc`/`--desc`, `--limit N`, `--offset N`, `--wide`, `--width N` and `--json` where supported. Disk and recent-error queries have their own public fields (`--role` and `--component`, respectively). Fields are allowlisted for each domain; they are not arbitrary property expressions. Stable identity breaks sort ties.

Paging and source partiality are separate. Inspect returned counts, offset/limit, truncation, partial flags and warnings. A successful first page does not establish that the collection was exhausted, nor that every selected target responded. Authorization-hidden entries must not leak through totals or diagnostics. Fetch additional pages deliberately when a complete authorized inventory is required.

## Streams and exit status

Machine-readable Nugget JSON is written to stdout; safe diagnostics belong to stderr. Keep the streams separate in scripts. Human tables may shorten cells to the terminal width; JSON preserves the bounded public contract rather than the table layout. JSON mode is a presentation contract, not permission to expose secret or hidden fields.

| Exit code | Meaning |
|---|---|
| `0` | Command completed successfully; still inspect partial/truncated result fields. |
| `1` | Usage/input/selection failure. |
| `2` | Admission, authorization or verification/security rejection. |
| `3` | Runtime/lifecycle unavailable or failed operation. |
| `4` | Unexpected internal failure. |

Local `app` commands and provider-backed Nugget commands share these broad categories but have distinct output payloads. Validate the payload family, not just the exit code. See [typed administration](admin-api.md) and [JSON contract](admin-json.md).
