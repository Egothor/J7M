/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.deployer.cli;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.operations.api.NuggetAdminContract;
import org.j7m.middleware.operations.api.NuggetAdminField;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind;
import org.j7m.middleware.operations.api.NuggetAdminQuery;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminSortDirection;
import org.j7m.middleware.operations.api.NuggetAdminTarget;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.j7m.middleware.security.api.SecurityContext;

final class NuggetCliConsole {
    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 1;
    private static final int EXIT_DENIED = 2;
    private static final int EXIT_UNAVAILABLE = 3;
    private static final int EXIT_INTERNAL = 4;
    private static final int MAX_COMMAND_LENGTH = 4096;
    private static final int MAX_TOKENS = 64;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MAX_CACHE_CLEANUP_SCAN = 16;
    private static final int MAX_PIN_BYTES = 256;
    private static final int MAX_CACHE_TOTAL_PIN_BYTES = MAX_CACHE_ENTRIES * MAX_PIN_BYTES;
    private static final int MAX_INFO_TARGETS = 32;
    private static final int MAX_INSPECTION_TARGETS = 32;
    private static final int MAX_RENDERED_ROWS = 500;
    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private static final int MAX_DETAIL_DEPTH = 8;
    private static final int MAX_DETAIL_ITEMS = 64;
    private static final int MAX_JSON_ITEMS = 500;
    private static final int MAX_JSON_MAP_ENTRIES = 64;
    private static final int MAX_JSON_STRING_CHARS = 512;
    private static final int MAX_LIFECYCLE_TEXT_ITEMS = 16;
    private static final int MAX_LIFECYCLE_TEXT_UTF8_BYTES = 96;
    private static final int MAX_TABLE_MAP_INSPECTED_ENTRIES = 64;
    private static final int MAX_TABLE_MAP_RENDERED_ENTRIES = 16;
    private static final int MAX_TABLE_MAP_KEY_COMPARE_CHARS = 256;
    private static final int DEFAULT_TERMINAL_WIDTH = 80;
    private static final int MIN_TERMINAL_WIDTH = 40;
    private static final int MAX_TERMINAL_WIDTH = 240;
    private static final int COMMAND_WORKERS = 4;
    private static final int COMMAND_QUEUE_CAPACITY = COMMAND_WORKERS;
    private static final Duration CACHE_TTL = Duration.ofMinutes(15L);
    private static final Duration COMMAND_DEADLINE = Duration.ofSeconds(2L);
    private static final String PARTIAL_NOTICE = "Partial output: true\n";
    private static final String HUMAN_TRUNCATION_MARKER = "...";

    private NuggetCliConsole() {
    }

    static int run(
            final String[] args,
            final InputStream input,
            final PrintStream output,
            final PrintStream error,
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Clock clock,
            final NuggetCliTicker ticker,
            final NuggetCliEventSink eventSink,
            final Map<String, String> environment) {
        try (NuggetSession session = new NuggetSession(
                Objects.requireNonNullElse(client, NuggetCliClient.unavailable()),
                secretReader,
                clock == null ? Clock.systemUTC() : clock,
                ticker == null ? NuggetCliTicker.system() : ticker,
                eventSink == null ? NuggetCliEventSink.none() : eventSink,
                environment == null ? Map.of() : Map.copyOf(environment))) {
            List<String> tokens = new ArrayList<>(Arrays.asList(args));
            if (!tokens.isEmpty()) {
                tokens.remove(0);
            }
            if (tokens.isEmpty() || "shell".equals(tokens.get(0)) || "console".equals(tokens.get(0))) {
                return shell(input, output, error, session);
            }
            return execute(tokens, output, error, session, false);
        } catch (NuggetCliFailure failure) {
            error.println("ERROR " + failure.code() + ": " + safe(failure.getMessage()));
            return failure.exitCode();
        } catch (RuntimeException exception) {
            error.println("ERROR INTERNAL: " + safe(exception.getMessage()));
            return EXIT_INTERNAL;
        }
    }

    private static int shell(
            final InputStream input,
            final PrintStream output,
            final PrintStream error,
            final NuggetSession session) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        int exitCode = EXIT_OK;
        try {
            while (true) {
                error.print(session.prompt());
                error.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                session.cleanupCredentials();
                List<String> tokens = tokenize(line);
                if (tokens.isEmpty()) {
                    continue;
                }
                String command = tokens.get(0);
                if ("exit".equals(command) || "quit".equals(command)) {
                    break;
                }
                int commandExit = execute(tokens, output, error, session, true);
                if (commandExit != EXIT_OK) {
                    exitCode = commandExit;
                }
            }
            return exitCode;
        } catch (NuggetCliFailure failure) {
            error.println("ERROR " + failure.code() + ": " + safe(failure.getMessage()));
            return failure.exitCode();
        } catch (Exception exception) {
            error.println("ERROR INTERNAL: " + safe(exception.getMessage()));
            return EXIT_INTERNAL;
        }
    }

    private static int execute(
            final List<String> rawTokens,
            final PrintStream output,
            final PrintStream error,
            final NuggetSession session,
            final boolean interactive) {
        session.cleanupCredentials();
        String commandCategory = commandCategory(rawTokens);
        CommandLine commandLine = null;
        try {
            commandLine = CommandLine.parse(rawTokens);
            commandCategory = commandLine.command();
            validateCommandLine(commandLine);
            boolean scopedDeadlineCommand = detailCommand(commandLine)
                    || lifecycleCommand(commandLine)
                    || nuggetControlCommand(commandLine);
            long scopedDeadlineNanos = scopedDeadlineCommand ? session.commandDeadlineNanos() : 0L;
            if (!scopedDeadlineCommand) {
                applyOneShotTarget(session, commandLine);
            }
            return switch (commandLine.command()) {
                case "help" -> help(output, commandLine.json());
                case "discover" -> discover(output, error, session, commandLine.json());
                case "nuggets" -> nuggets(output, session, commandLine.json());
                case "talk" -> talk(output, session, commandLine, interactive);
                case "target" -> target(output, session, commandLine.json());
                case "whoami" -> whoami(output, session, commandLine.json());
                case "pin" -> pin(output, session, commandLine, interactive);
                case "auth" -> auth(output, session, commandLine, interactive);
                case "reconnect" -> reconnect(output, session, commandLine.json());
                case "info" -> info(output, session, commandLine, false);
                case "status" -> info(output, session, commandLine, true);
                case "ps" -> ps(output, session, commandLine);
                case "ls" -> objects(output, session, commandLine, true);
                case "objects" -> objects(output, session, commandLine, false);
                case "object" -> scopedDetail(output, session, commandLine, scopedDeadlineNanos, true);
                case "apps" -> applications(output, session, commandLine, false);
                case "app" -> scopedDetail(output, session, commandLine, scopedDeadlineNanos, false);
                case "start", "stop", "drain", "unload" -> scopedLifecycle(output, session, commandLine, scopedDeadlineNanos);
                case "drain-nugget", "shutdown-nugget", "kill-nugget" ->
                        scopedNuggetControl(output, session, commandLine, scopedDeadlineNanos);
                case "env" -> simpleInspection(output, session, commandLine, InspectionKind.ENVIRONMENT);
                case "disk" -> disk(output, session, commandLine);
                case "health" -> simpleInspection(output, session, commandLine, InspectionKind.HEALTH);
                case "readiness" -> simpleInspection(output, session, commandLine, InspectionKind.READINESS);
                case "metrics" -> simpleInspection(output, session, commandLine, InspectionKind.METRICS);
                case "errors" -> errors(output, session, commandLine);
                case "exit", "quit" -> EXIT_OK;
                default -> throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unknown nugget command");
            };
        } catch (NuggetCliFailure failure) {
            recordCommandFailure(session, commandCategory, failure);
            if (commandLine != null && commandLine.json()) {
                jsonError(output, commandLine.command(), failure.code(), failure.getMessage());
            } else {
                error.println("ERROR " + failure.code() + ": " + safe(failure.getMessage()));
            }
            return failure.exitCode();
        } catch (RuntimeException exception) {
            NuggetCliFailure failure = new NuggetCliFailure(EXIT_INTERNAL, "INTERNAL", "internal failure");
            recordCommandFailure(session, commandCategory, failure);
            if (commandLine != null && commandLine.json()) {
                jsonError(output, commandLine.command(), failure.code(), failure.getMessage());
            } else {
                error.println("ERROR " + failure.code() + ": " + safe(failure.getMessage()));
            }
            return failure.exitCode();
        } finally {
            session.cleanupCredentials();
        }
    }

    private static String commandCategory(final List<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return "unknown";
        }
        String candidate = safe(rawTokens.get(0)).toLowerCase(Locale.ROOT);
        return switch (candidate) {
            case "help", "discover", "nuggets", "talk", "target", "whoami", "pin",
                    "auth", "reconnect", "info", "status", "ps", "ls", "objects", "object",
                    "apps", "app", "start", "stop", "drain", "unload",
                    "drain-nugget", "shutdown-nugget", "kill-nugget",
                    "env", "disk", "health", "readiness", "metrics", "errors",
                    "exit", "quit" -> candidate;
            default -> "unknown";
        };
    }

    private static boolean detailCommand(final CommandLine commandLine) {
        return "object".equals(commandLine.command()) || "app".equals(commandLine.command());
    }

    private static boolean lifecycleCommand(final CommandLine commandLine) {
        return switch (commandLine.command()) {
            case "start", "stop", "drain", "unload" -> true;
            default -> false;
        };
    }

    private static boolean nuggetControlCommand(final CommandLine commandLine) {
        return switch (commandLine.command()) {
            case "drain-nugget", "shutdown-nugget", "kill-nugget" -> true;
            default -> false;
        };
    }

    private static void recordCommandFailure(
            final NuggetSession session,
            final String command,
            final NuggetCliFailure failure) {
        if (specificFailureEvent(command, failure.code())) {
            return;
        }
        session.recordEvent("command-failure", Map.of(
                "command", command,
                "code", safeCode(failure.code()),
                "category", failureCategory(failure)));
    }

    private static boolean specificFailureEvent(final String command, final String code) {
        if ("reconnect".equals(command)) {
            return true;
        }
        return "PIN_REJECTED".equals(code) || "THROTTLED".equals(code);
    }

    private static String failureCategory(final NuggetCliFailure failure) {
        return switch (failure.exitCode()) {
            case EXIT_USAGE -> "usage";
            case EXIT_DENIED -> "denied";
            case EXIT_UNAVAILABLE -> "unavailable";
            case EXIT_INTERNAL -> "internal";
            default -> "unknown";
        };
    }

    private static String safeCode(final String code) {
        String value = safe(code).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return "UNKNOWN";
        }
        StringBuilder builder = new StringBuilder(Math.min(32, value.length()));
        for (int index = 0; index < value.length() && builder.length() < 32; index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_') {
                builder.append(character);
            }
        }
        return builder.isEmpty() ? "UNKNOWN" : builder.toString();
    }

    private static void applyOneShotTarget(final NuggetSession session, final CommandLine commandLine) {
        if ("talk".equals(commandLine.command()) || "discover".equals(commandLine.command())
                || "nuggets".equals(commandLine.command()) || "help".equals(commandLine.command())) {
            return;
        }
        String id = commandLine.option("--id");
        String target = commandLine.option("--target");
        if (id.isBlank() && target.isBlank()) {
            return;
        }
        NuggetDiscovery.Selector selector = id.isBlank()
                ? new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, target, "cli-target")
                : new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, id, "cli-target");
        NuggetDiscovery.Resolution resolution = session.resolve(selector);
        if (resolution.status() != NuggetDiscovery.ResolutionStatus.RESOLVED || resolution.records().size() != 1) {
            throw new NuggetCliFailure(EXIT_USAGE, resolution.status().name(), safeReason(resolution));
        }
        NuggetDiscovery.Record record = resolution.records().get(0);
        requireSelectable(record);
        session.selectOne(record);
    }

    private static boolean hasOneShotTarget(final CommandLine commandLine) {
        return !commandLine.option("--id").isBlank() || !commandLine.option("--target").isBlank();
    }

    private static int scopedDetail(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos,
            final boolean objectDetail) {
        if (!hasOneShotTarget(commandLine)) {
            return objectDetail
                    ? object(output, session, commandLine, deadlineNanos)
                    : application(output, session, commandLine, deadlineNanos);
        }
        TargetSelection previousSelection = session.targetSelection();
        try {
            applyOneShotTargetWithinDeadline(session, commandLine, deadlineNanos);
            return objectDetail
                    ? object(output, session, commandLine, deadlineNanos)
                    : application(output, session, commandLine, deadlineNanos);
        } finally {
            session.restoreTargetSelection(previousSelection);
        }
    }

    private static void applyOneShotTargetWithinDeadline(
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        String id = commandLine.option("--id");
        String target = commandLine.option("--target");
        if (id.isBlank() && target.isBlank()) {
            return;
        }
        NuggetDiscovery.Selector selector = id.isBlank()
                ? new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, target, "cli-target")
                : new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, id, "cli-target");
        NuggetDiscovery.Resolution resolution = session.callWithinCommandDeadline(() -> session.resolve(selector), deadlineNanos);
        session.requireCommandDeadlineActive(deadlineNanos);
        if (resolution.status() != NuggetDiscovery.ResolutionStatus.RESOLVED || resolution.records().size() != 1) {
            throw new NuggetCliFailure(EXIT_USAGE, resolution.status().name(), safeReason(resolution));
        }
        NuggetDiscovery.Record record = resolution.records().get(0);
        requireSelectable(record);
        session.requireCommandDeadlineActive(deadlineNanos);
        session.selectOne(record);
    }

    private static int help(final PrintStream output, final boolean json) {
        if (json) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("commands", List.of("help", "exit", "quit", "discover", "nuggets", "talk",
                    "target", "whoami", "pin", "auth", "reconnect", "info", "status",
                    "ps", "ls", "objects", "object", "apps", "app", "env", "disk",
                    "health", "readiness", "metrics", "errors", "start", "stop", "drain", "unload",
                    "drain-nugget", "shutdown-nugget", "kill-nugget"));
            jsonOk(output, "help", values);
            return EXIT_OK;
        }
        output.println("j7m nugget commands:");
        output.println("  discover");
        output.println("  nuggets");
        output.println("  talk | talk all | talk NAME | talk --id ID");
        output.println("  target | whoami");
        output.println("  pin | pin clear | pin clear --all");
        output.println("  auth [--pin-env NAME]");
        output.println("  reconnect | info | status");
        output.println("  ps [--wide] [--filter key=value] [--sort FIELD] [--limit N] [--json]");
        output.println("  ls | objects | object ID");
        output.println("  apps | app ID");
        output.println("  start COORDINATE [--dry-run] [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  drain INSTANCE_ID [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  stop INSTANCE_ID [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  unload INSTANCE_ID [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  drain-nugget NUGGET [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  shutdown-nugget NUGGET [--confirm CHALLENGE] [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  kill-nugget NUGGET [--confirm CHALLENGE] [--json] [--target NAME|--id ID] [--pin-env NAME] [--width N]");
        output.println("  env | disk | health | readiness | metrics | errors");
        output.println("  exit | quit");
        return EXIT_OK;
    }

    private static int discover(
            final PrintStream output,
            final PrintStream error,
            final NuggetSession session,
            final boolean json) {
        NuggetDiscovery.Result result = session.discover();
        if (json) {
            jsonOk(output, "discover", discoveryMap(result));
            return EXIT_OK;
        }
        output.println("discovered " + result.returned() + " nugget(s)");
        for (String warning : result.warnings()) {
            error.println("warning: " + safe(warning));
        }
        return EXIT_OK;
    }

    private static int nuggets(final PrintStream output, final NuggetSession session, final boolean json) {
        NuggetDiscovery.Result result = session.ensureDiscovery();
        if (json) {
            jsonOk(output, "nuggets", discoveryMap(result));
            return EXIT_OK;
        }
        if (result.records().isEmpty()) {
            output.println("No nuggets discovered.");
            return EXIT_OK;
        }
        output.printf(Locale.ROOT, "%-18s %-14s %-12s %-18s %-14s %-10s %-24s%n",
                "NAME", "ID", "STATUS", "SOURCE", "PROTOCOL", "AUTH", "LAST SEEN");
        for (NuggetDiscovery.Record record : orderedRecords(result.records())) {
            output.printf(Locale.ROOT, "%-18s %-14s %-12s %-18s %-14s %-10s %-24s%n",
                    displayName(record),
                    shortId(record.nuggetId()),
                    record.status().name(),
                    record.source().name(),
                    protocol(record),
                    "none",
                    record.lastSeenAt().toString());
        }
        return EXIT_OK;
    }

    private static int talk(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean interactive) {
        List<String> arguments = commandLine.arguments();
        if (arguments.isEmpty() || (arguments.size() == 1 && "all".equals(arguments.get(0)))) {
            String id = commandLine.option("--id");
            if (!id.isBlank()) {
                NuggetDiscovery.Resolution resolution = session.resolve(new NuggetDiscovery.Selector(
                        NuggetDiscovery.SelectorType.EXACT_ID, id, "cli-talk"));
                if (resolution.status() != NuggetDiscovery.ResolutionStatus.RESOLVED || resolution.records().size() != 1) {
                    throw new NuggetCliFailure(EXIT_USAGE, resolution.status().name(), safeReason(resolution));
                }
                NuggetDiscovery.Record record = resolution.records().get(0);
                requireSelectable(record);
                session.selectOne(record);
                if (!interactive || commandLine.json()) {
                    writeTarget(output, session, commandLine.json(), "talk");
                }
                return EXIT_OK;
            }
            NuggetDiscovery.Resolution resolution = session.resolve(new NuggetDiscovery.Selector(
                    NuggetDiscovery.SelectorType.ALL, "", "cli-talk"));
            session.selectAll(resolution.records());
            if (!interactive || commandLine.json()) {
                writeTarget(output, session, commandLine.json(), "talk");
            }
            return EXIT_OK;
        }
        NuggetDiscovery.Selector selector;
        if (arguments.size() == 2 && "--id".equals(arguments.get(0))) {
            selector = new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_ID, arguments.get(1), "cli-talk");
        } else if (arguments.size() == 1) {
            selector = new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.EXACT_NAME, arguments.get(0), "cli-talk");
        } else {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "talk accepts all, NAME, or --id ID");
        }
        NuggetDiscovery.Resolution resolution = session.resolve(selector);
        if (resolution.status() != NuggetDiscovery.ResolutionStatus.RESOLVED || resolution.records().size() != 1) {
            throw new NuggetCliFailure(EXIT_USAGE, resolution.status().name(), safeReason(resolution));
        }
        NuggetDiscovery.Record record = resolution.records().get(0);
        requireSelectable(record);
        session.selectOne(record);
        if (!interactive || commandLine.json()) {
            writeTarget(output, session, commandLine.json(), "talk");
        }
        return EXIT_OK;
    }

    private static int target(final PrintStream output, final NuggetSession session, final boolean json) {
        writeTarget(output, session, json, "target");
        return EXIT_OK;
    }

    private static int whoami(final PrintStream output, final NuggetSession session, final boolean json) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("principal", "cli-local");
        values.put("target", session.targetName());
        values.put("admitted", Boolean.toString(!session.currentAdmissionId().isBlank()));
        values.put("connections", Integer.toString(session.connectionCount()));
        if (json) {
            jsonOk(output, "whoami", values);
            return EXIT_OK;
        }
        output.println("principal cli-local");
        output.println("target " + session.targetName());
        output.println("admission " + (session.currentAdmissionId().isBlank() ? "none" : "accepted"));
        output.println("connections " + session.connectionCount());
        return EXIT_OK;
    }

    private static int pin(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean interactive) {
        List<String> arguments = commandLine.arguments();
        if (!arguments.isEmpty() && "clear".equals(arguments.get(0))) {
            if (!commandLine.option("--all").isBlank() || commandLine.options().containsKey("--all")) {
                session.clearCredentials();
                outputMessage(output, commandLine.json(), "pin", "cleared", "all");
                return EXIT_OK;
            }
            session.clearCurrentCredential();
            outputMessage(output, commandLine.json(), "pin", "cleared", session.targetName());
            return EXIT_OK;
        }
        NuggetDiscovery.Record record = session.requireSingleTarget();
        char[] pin = session.readInteractivePin("PIN for " + displayName(record) + ": ", interactive);
        if (pin.length == 0) {
            throw new NuggetCliFailure(EXIT_DENIED, "PIN_UNAVAILABLE", "secure PIN input is unavailable");
        }
        try {
            session.putCredential(record.nuggetId(), pin);
            outputMessage(output, commandLine.json(), "pin", "cached", displayName(record));
            return EXIT_OK;
        } finally {
            clear(pin);
        }
    }

    private static int auth(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean interactive) {
        NuggetDiscovery.Record record = session.requireSingleTarget();
        char[] pin = session.credential(record.nuggetId());
        if (pin.length == 0) {
            String envName = commandLine.option("--pin-env");
            if (!envName.isBlank()) {
                pin = session.pinFromEnvironment(envName);
            } else if (interactive) {
                pin = session.readInteractivePin("PIN for " + displayName(record) + ": ", true);
            }
        }
        if (pin.length == 0) {
            throw new NuggetCliFailure(EXIT_DENIED, "PIN_UNAVAILABLE", "PIN is required");
        }
        try {
            if (validatedPinUtf8Bytes(pin) < 0) {
                session.clearCredential(record.nuggetId());
                throw new NuggetCliFailure(EXIT_DENIED, "PIN_UNAVAILABLE", "PIN is invalid");
            }
            NuggetCliConnection connection = session.connect(record);
            requireCredentialTransport(connection);
            NuggetAuthenticationRequest request = NuggetAuthenticationRequest.of(
                    "cli-auth",
                    authSource(connection),
                    record.endpoint().toString(),
                    connection.security().locality() == NuggetCliConnectionSecurity.Locality.TRUSTED_LOCAL,
                    session.now());
            NuggetAuthenticationResult result = connection.authenticate(request, pin);
            if (result.outcome() == NuggetAuthenticationOutcome.THROTTLED) {
                session.recordEvent("auth-throttled", Map.of("target", shortId(record.nuggetId())));
                throw new NuggetCliFailure(EXIT_DENIED, "THROTTLED", "PIN admission throttled");
            }
            if (!result.accepted()) {
                session.clearCredential(record.nuggetId());
                session.recordEvent("auth-rejected", Map.of("target", shortId(record.nuggetId())));
                throw new NuggetCliFailure(EXIT_DENIED, "PIN_REJECTED", "PIN not accepted");
            }
            session.admit(record.nuggetId(), result.admissionId());
            outputMessage(output, commandLine.json(), "auth", "accepted", displayName(record));
            return EXIT_OK;
        } finally {
            clear(pin);
        }
    }

    private static int reconnect(final PrintStream output, final NuggetSession session, final boolean json) {
        NuggetDiscovery.Record current = session.requireSingleTarget();
        String safeTarget = shortId(current.nuggetId());
        session.recordEvent("reconnect-requested", Map.of("target", safeTarget));
        NuggetDiscovery.Resolution resolution = session.resolve(new NuggetDiscovery.Selector(
                NuggetDiscovery.SelectorType.EXACT_ID, current.nuggetId(), "cli-reconnect"));
        if (resolution.status() != NuggetDiscovery.ResolutionStatus.RESOLVED || resolution.records().size() != 1) {
            session.disconnect(current.nuggetId());
            session.clearCredential(current.nuggetId());
            session.recordEvent("reconnect-rejected", Map.of(
                    "target", safeTarget,
                    "code", safeCode(resolution.status().name()),
                    "category", "not-resolved"));
            throw new NuggetCliFailure(EXIT_UNAVAILABLE, resolution.status().name(), "selected nugget is not available");
        }
        NuggetDiscovery.Record refreshed = resolution.records().get(0);
        if (refreshed.status() != NuggetDiscovery.Status.ACTIVE
                || refreshed.compatibility() != NuggetDiscovery.Compatibility.COMPATIBLE) {
            session.recordEvent("reconnect-rejected", Map.of(
                    "target", safeTarget,
                    "code", "NOT_SELECTABLE",
                    "category", "not-selectable"));
            throw new NuggetCliFailure(EXIT_USAGE, "NOT_SELECTABLE", "target is not active and compatible");
        }
        session.selectOne(refreshed);
        session.disconnect(refreshed.nuggetId());
        try {
            session.connect(refreshed);
        } catch (NuggetCliFailure failure) {
            session.recordEvent("reconnect-failed", Map.of(
                    "target", safeTarget,
                    "code", safeCode(failure.code()),
                    "category", failureCategory(failure)));
            throw failure;
        } catch (RuntimeException exception) {
            session.recordEvent("reconnect-failed", Map.of(
                    "target", safeTarget,
                    "code", "INTERNAL",
                    "category", "internal"));
            throw exception;
        }
        session.recordEvent("reconnect-succeeded", Map.of("target", safeTarget));
        outputMessage(output, json, "reconnect", "connected", displayName(refreshed));
        return EXIT_OK;
    }

    private static int info(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean overview) {
        List<NuggetDiscovery.Record> requestedRecords = session.targetRecords();
        if (requestedRecords.isEmpty()) {
            throw new NuggetCliFailure(EXIT_UNAVAILABLE, "NO_TARGET", "no active target selected");
        }
        String pinEnvironment = commandLine.option("--pin-env");
        if (!pinEnvironment.isBlank() && !session.hasSingleConcreteTarget()) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        List<NuggetDiscovery.Record> records = requestedRecords.stream()
                .limit(MAX_INFO_TARGETS)
                .toList();
        List<Map<String, Object>> results = new ArrayList<>();
        boolean cappedTargets = requestedRecords.size() > records.size();
        int failures = cappedTargets ? 1 : 0;
        int unavailableFailures = cappedTargets ? 1 : 0;
        if (cappedTargets) {
            results.add(targetErrorMap("TARGET_LIMIT", "TARGET_LIMIT"));
        }
        long deadlineNanos = session.commandDeadlineNanos();
        for (NuggetDiscovery.Record record : records) {
            try {
                results.add(session.callWithinCommandDeadline(
                        record,
                        () -> targetInfo(session, record, pinEnvironment, overview),
                        deadlineNanos));
            } catch (NuggetCliFailure failure) {
                failures++;
                if (failure.exitCode() == EXIT_UNAVAILABLE) {
                    unavailableFailures++;
                }
                results.add(targetErrorMap(record, failure.code()));
            }
        }
        if (commandLine.json()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("targets", results);
            values.put("partial", Boolean.toString(cappedTargets || (failures > 0 && failures < records.size())));
            jsonOk(output, overview ? "status" : "info", values);
        } else {
            for (Map<String, Object> result : results) {
                output.println(result.get("name") + " " + result.get("id") + " " + result.get("state"));
                if (overview) {
                    output.println("  health " + result.get("health") + " readiness " + result.get("readiness"));
                }
            }
        }
        if (failures == 0) {
            return EXIT_OK;
        }
        boolean allFailed = failures >= records.size() && requestedRecords.size() <= records.size();
        int exit = allFailed && unavailableFailures == 0 ? EXIT_DENIED : EXIT_UNAVAILABLE;
        String code = exit == EXIT_DENIED ? "DENIED" : (allFailed ? "UNAVAILABLE" : "PARTIAL");
        session.recordEvent("command-failure", Map.of(
                "command", overview ? "status" : "info",
                "code", code,
                "category", exit == EXIT_DENIED ? "denied" : "partial",
                "partial", Boolean.toString(cappedTargets || (exit == EXIT_UNAVAILABLE && !allFailed))));
        return exit;
    }

    private static int ps(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine) {
        PsQueryPlan psQuery = psQueryPlan(commandLine);
        NuggetIntrospection.Query sourceQuery = new NuggetIntrospection.Query(
                0,
                NuggetIntrospection.MAX_LIMIT,
                "id",
                NuggetIntrospection.SortDirection.ASC,
                Map.of());
        InspectionBatch batch = collectInspection(session, commandLine, "ps", record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetCliConnection connection = session.connect(record);
            NuggetIntrospection.Request request = session.request(admission, "cli-ps");
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications =
                    connection.applications(request, sourceQuery);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects =
                    connection.objects(request, sourceQuery);
            List<Map<String, Object>> rows = new ArrayList<>(NuggetIntrospection.MAX_LIMIT * 2);
            SourceAppendResult applicationRows = appendApplicationRows(rows, record, applications);
            SourceAppendResult objectRows = appendObjectRows(rows, record, objects);
            if (!applicationRows.ok() || !objectRows.ok()) {
                return targetErrorMap(record, resultCode(applications, objects));
            }
            PsRows psRows = applyPsQuery(rows, psQuery);
            Map<String, Object> values = targetBase(record);
            values.put("rows", psRows.rows());
            values.put("returned", Integer.toString(psRows.rows().size()));
            values.put("totalAvailable", Integer.toString(psRows.totalAvailable()));
            values.put("partial", Boolean.toString(applications.partial() || objects.partial()
                    || applications.value().partial() || objects.value().partial()
                    || sourcePartial(applications.value()) || sourcePartial(objects.value())
                    || applicationRows.partial() || objectRows.partial() || psRows.partial()));
            return values;
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, "ps", batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No J7M applications or objects.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, psColumns(commandLine), rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static int objects(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean concise) {
        QueryPlan query = queryPlan(commandLine, QueryDomain.OBJECTS);
        InspectionBatch batch = collectInspection(session, commandLine, concise ? "ls" : "objects", record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> result =
                    session.connect(record).objects(session.request(admission, "cli-objects"), query.query());
            if (!resultOk(result) || result.value() == null) {
                return targetErrorMap(record, resultCode(result));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NuggetIntrospection.ObjectSummary summary : result.value().items()) {
                rows.add(objectRow(record, summary));
            }
            Map<String, Object> values = targetBase(record);
            values.put("rows", rows);
            values.put("returned", Integer.toString(result.value().returned()));
            values.put("totalAvailable", Integer.toString(result.value().totalAvailable()));
            values.put("partial", Boolean.toString(result.partial() || result.value().partial()));
            return values;
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, concise ? "ls" : "objects", batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No objects.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, concise ? objectColumns(false) : objectColumns(commandLine.wide()),
                    rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static int applications(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final boolean unused) {
        QueryPlan query = queryPlan(commandLine, QueryDomain.APPLICATIONS);
        InspectionBatch batch = collectInspection(session, commandLine, "apps", record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> result =
                    session.connect(record).applications(session.request(admission, "cli-apps"), query.query());
            if (!resultOk(result) || result.value() == null) {
                return targetErrorMap(record, resultCode(result));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NuggetIntrospection.ApplicationSummary summary : result.value().items()) {
                rows.add(applicationRow(record, summary));
            }
            Map<String, Object> values = targetBase(record);
            values.put("rows", rows);
            values.put("returned", Integer.toString(result.value().returned()));
            values.put("totalAvailable", Integer.toString(result.value().totalAvailable()));
            values.put("partial", Boolean.toString(result.partial() || result.value().partial()));
            return values;
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, "apps", batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No applications.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, applicationColumns(commandLine.wide()), rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static int object(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        String id = requiredDetailId(commandLine, "object");
        NuggetDiscovery.Record record = session.requireSingleTarget();
        if (session.allTarget) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        Map<String, Object> values = session.callWithinCommandDeadline(record, guard -> {
            String admission = admittedForInspection(session, record, commandLine, guard);
            guard.requireActive(session, record);
            NuggetCliConnection connection = session.connect(record);
            guard.requireActive(session, record);
            NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> result =
                    connection.object(session.request(admission, "cli-object"), id);
            if (!resultOk(result) || result.value() == null) {
                throw new NuggetCliFailure(result != null && result.admitted() && !result.authorized() ? EXIT_DENIED : EXIT_UNAVAILABLE,
                        resultCode(result), "object detail unavailable");
            }
            return objectDetailMap(record, result.value());
        }, deadlineNanos);
        session.requireCommandDeadlineActive(deadlineNanos);
        if (commandLine.json()) {
            jsonOkBounded(output, "object", values, false);
        } else {
            RenderResult rendered = renderDetail(output, values);
            writePartialNotice(output, rendered.partial());
        }
        return EXIT_OK;
    }

    private static int application(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        String id = requiredDetailId(commandLine, "app");
        NuggetDiscovery.Record record = session.requireSingleTarget();
        if (session.allTarget) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        Map<String, Object> values = session.callWithinCommandDeadline(record, guard -> {
            String admission = admittedForInspection(session, record, commandLine, guard);
            guard.requireActive(session, record);
            NuggetCliConnection connection = session.connect(record);
            guard.requireActive(session, record);
            NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> result =
                    connection.application(session.request(admission, "cli-app"), id);
            if (!resultOk(result) || result.value() == null) {
                throw new NuggetCliFailure(result != null && result.admitted() && !result.authorized() ? EXIT_DENIED : EXIT_UNAVAILABLE,
                        resultCode(result), "application detail unavailable");
            }
            return applicationDetailMap(record, result.value());
        }, deadlineNanos);
        session.requireCommandDeadlineActive(deadlineNanos);
        if (commandLine.json()) {
            jsonOkBounded(output, "app", values, false);
        } else {
            RenderResult rendered = renderDetail(output, values);
            writePartialNotice(output, rendered.partial());
        }
        return EXIT_OK;
    }

    private static int scopedLifecycle(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        if (!hasOneShotTarget(commandLine)) {
            return lifecycle(output, session, commandLine, deadlineNanos);
        }
        TargetSelection previousSelection = session.targetSelection();
        try {
            applyOneShotTargetWithinDeadline(session, commandLine, deadlineNanos);
            return lifecycle(output, session, commandLine, deadlineNanos);
        } finally {
            session.restoreTargetSelection(previousSelection);
        }
    }

    private static int lifecycle(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        NuggetDiscovery.Record record = session.requireSingleTarget();
        if (session.allTarget) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        NuggetApplicationLifecycle.Result result = session.callWithinCommandDeadline(record, guard -> {
            String admission = admittedForInspection(session, record, commandLine, guard);
            guard.requireActive(session, record);
            NuggetCliConnection connection = session.connect(record);
            guard.requireActive(session, record);
            NuggetApplicationLifecycle.Request request = lifecycleRequest(session, record, commandLine, admission, deadlineNanos);
            NuggetApplicationLifecycle.Result lifecycleResult = connection.applicationLifecycle(request);
            guard.requireActive(session, record);
            return lifecycleResult;
        }, deadlineNanos);
        session.requireCommandDeadlineActive(deadlineNanos);
        Map<String, Object> values = lifecycleResultMap(record, result);
        if (commandLine.json()) {
            jsonOkBounded(output, commandLine.command(), values, result.partial());
        } else {
            writeLifecycleHuman(output, values, result);
        }
        return lifecycleExitCode(result);
    }

    private static NuggetApplicationLifecycle.Request lifecycleRequest(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final CommandLine commandLine,
            final String admission,
            final long deadlineNanos) {
        NuggetApplicationLifecycle.Action action = lifecycleAction(commandLine.command());
        ApplicationCoordinate coordinate = action == NuggetApplicationLifecycle.Action.START
                ? ApplicationCoordinate.parse(commandLine.arguments().get(0)) : null;
        ApplicationInstanceId instanceId = action == NuggetApplicationLifecycle.Action.START
                ? null : new ApplicationInstanceId(commandLine.arguments().get(0));
        NuggetAdminRequestContext context = contractContext(
                commandLine.command(),
                record,
                NuggetAdminOperationKind.APPLICATION_LIFECYCLE,
                session.now(),
                session.deadlineInstant(deadlineNanos));
        return new NuggetApplicationLifecycle.Request(
                admission,
                SecurityContext.system("j7m-cli"),
                context.correlationId(),
                action,
                coordinate,
                instanceId,
                commandLine.options().containsKey("--dry-run") || commandLine.options().containsKey("--plan"),
                context.requestedAt(),
                context.clientDeadlineAt(),
                contractMetadata(context));
    }

    private static NuggetApplicationLifecycle.Action lifecycleAction(final String command) {
        return switch (command) {
            case "start" -> NuggetApplicationLifecycle.Action.START;
            case "drain" -> NuggetApplicationLifecycle.Action.DRAIN;
            case "stop" -> NuggetApplicationLifecycle.Action.STOP;
            case "unload" -> NuggetApplicationLifecycle.Action.UNLOAD;
            default -> throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unknown lifecycle command");
        };
    }

    private static int lifecycleExitCode(final NuggetApplicationLifecycle.Result result) {
        if (result.error() == null && result.accepted()) {
            return EXIT_OK;
        }
        if (!result.admitted() || !result.authorized()) {
            return EXIT_DENIED;
        }
        if (result.error() != null && (result.error().code().name().equals("UNAVAILABLE")
                || result.error().code().name().equals("DEADLINE_EXCEEDED"))) {
            return EXIT_UNAVAILABLE;
        }
        return EXIT_DENIED;
    }

    private static int scopedNuggetControl(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        if (!hasOneShotTarget(commandLine)) {
            return nuggetControl(output, session, commandLine, deadlineNanos);
        }
        TargetSelection previousSelection = session.targetSelection();
        try {
            applyOneShotTargetWithinDeadline(session, commandLine, deadlineNanos);
            return nuggetControl(output, session, commandLine, deadlineNanos);
        } finally {
            session.restoreTargetSelection(previousSelection);
        }
    }

    private static int nuggetControl(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final long deadlineNanos) {
        NuggetDiscovery.Record record = session.requireSingleTarget();
        if (session.allTarget) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        requirePositionalNuggetMatches(record, commandLine.arguments().get(0));
        NuggetControl.Result result = session.callWithinCommandDeadline(record, guard -> {
            String admission = admittedForInspection(session, record, commandLine, guard);
            guard.requireActive(session, record);
            NuggetCliConnection connection = session.connect(record);
            guard.requireActive(session, record);
            NuggetControl.Request request = nuggetControlRequest(session, record, commandLine, admission, deadlineNanos);
            NuggetControl.Result controlResult = connection.nuggetControl(request);
            guard.requireActive(session, record);
            return controlResult;
        }, deadlineNanos);
        session.requireCommandDeadlineActive(deadlineNanos);
        Map<String, Object> values = nuggetControlResultMap(record, result);
        if (commandLine.json()) {
            jsonOkBounded(output, commandLine.command(), values, result.partial());
        } else {
            writeNuggetControlHuman(output, values, result);
        }
        return nuggetControlExitCode(result);
    }

    private static void requirePositionalNuggetMatches(final NuggetDiscovery.Record record, final String value) {
        String target = value == null ? "" : value;
        if (target.isBlank()) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "nugget target is required");
        }
        if (!target.equals(record.nuggetId()) && !target.equals(record.nuggetName()) && !target.equals(displayName(record))) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_MISMATCH", "positional nugget does not match selected target");
        }
    }

    private static NuggetControl.Request nuggetControlRequest(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final CommandLine commandLine,
            final String admission,
            final long deadlineNanos) {
        NuggetControl.Action action = nuggetControlAction(commandLine.command());
        NuggetControl.Mode mode = action == NuggetControl.Action.KILL_NUGGET
                ? NuggetControl.Mode.FORCED : NuggetControl.Mode.GRACEFUL;
        String operationId = "cli-" + commandLine.command() + "-" + shortId(record.nuggetId());
        NuggetAdminRequestContext context = contractContext(
                commandLine.command(),
                record,
                NuggetAdminOperationKind.NUGGET_CONTROL,
                session.now(),
                session.deadlineInstant(deadlineNanos));
        return new NuggetControl.Request(
                admission,
                SecurityContext.system("j7m-cli"),
                operationId,
                context.correlationId(),
                action,
                mode,
                context.target().nuggetId(),
                context.target().runtimeId(),
                context.target().generation(),
                commandLine.option("--confirm"),
                context.requestedAt(),
                context.clientDeadlineAt(),
                contractMetadata(context));
    }

    private static NuggetControl.Action nuggetControlAction(final String command) {
        return switch (command) {
            case "drain-nugget" -> NuggetControl.Action.DRAIN_NUGGET;
            case "shutdown-nugget" -> NuggetControl.Action.SHUTDOWN_NUGGET;
            case "kill-nugget" -> NuggetControl.Action.KILL_NUGGET;
            default -> throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unknown nugget control command");
        };
    }

    private static int nuggetControlExitCode(final NuggetControl.Result result) {
        if (result.error() == null
                && (result.accepted() || result.outcome() == NuggetControl.Outcome.CONFIRMATION_REQUIRED)) {
            return EXIT_OK;
        }
        if (!result.admitted() || !result.authorized() || result.outcome() == NuggetControl.Outcome.DISABLED) {
            return EXIT_DENIED;
        }
        if (result.error() != null && (result.error().code().name().equals("UNAVAILABLE")
                || result.error().code().name().equals("DEADLINE_EXCEEDED")
                || result.error().code().name().equals("OVERLOADED"))) {
            return EXIT_UNAVAILABLE;
        }
        return EXIT_DENIED;
    }

    private static int disk(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine) {
        QueryPlan query = queryPlan(commandLine, QueryDomain.DISK);
        String role = commandLine.option("--role");
        InspectionBatch batch = collectInspection(session, commandLine, "disk", record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> result =
                    session.connect(record).disk(session.request(admission, "cli-disk"),
                            new NuggetIntrospection.DiskQuery(role, query.query()));
            if (!resultOk(result) || result.value() == null) {
                return targetErrorMap(record, resultCode(result));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NuggetIntrospection.DiskVolumeSummary summary : result.value().items()) {
                rows.add(diskRow(record, summary));
            }
            Map<String, Object> values = targetBase(record);
            values.put("rows", rows);
            values.put("partial", Boolean.toString(result.partial() || result.value().partial()));
            return values;
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, "disk", batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No disk roots.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, diskColumns(), rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static int errors(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine) {
        QueryPlan query = queryPlan(commandLine, QueryDomain.ERRORS);
        String component = commandLine.option("--component");
        InspectionBatch batch = collectInspection(session, commandLine, "errors", record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> result =
                    session.connect(record).recentErrors(session.request(admission, "cli-errors"),
                            new NuggetIntrospection.RecentErrorQuery(component, query.query()));
            if (!resultOk(result) || result.value() == null) {
                return targetErrorMap(record, resultCode(result));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NuggetIntrospection.RecentErrorEntry entry : result.value().items()) {
                rows.add(errorRow(record, entry));
            }
            Map<String, Object> values = targetBase(record);
            values.put("rows", rows);
            values.put("partial", Boolean.toString(result.partial() || result.value().partial()));
            return values;
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, "errors", batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No recent errors.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, errorColumns(), rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static int simpleInspection(
            final PrintStream output,
            final NuggetSession session,
            final CommandLine commandLine,
            final InspectionKind kind) {
        InspectionBatch batch = collectInspection(session, commandLine, kind.command(), record -> {
            String admission = admittedForInspection(session, record, commandLine);
            NuggetCliConnection connection = session.connect(record);
            NuggetIntrospection.Request request = session.request(admission, "cli-" + kind.command());
            return switch (kind) {
                case ENVIRONMENT -> environmentTarget(record, connection.environment(request));
                case HEALTH -> componentTarget(record, connection.health(request), "health");
                case READINESS -> componentTarget(record, connection.readiness(request), "readiness");
                case METRICS -> metricsTarget(record, connection.metrics(request));
            };
        });
        if (commandLine.json()) {
            return writeInspectionJson(output, kind.command(), batch);
        }
        RowsResult rows = flattenRows(batch.targets());
        if (rows.rows().isEmpty()) {
            output.println("No " + kind.command() + " data.");
            writePartialNotice(output, batch.partial() || rows.partial());
        } else {
            RenderResult rendered = renderTable(output, kind.columns(), rows.rows(), width(commandLine));
            writePartialNotice(output, batch.partial() || rows.partial() || rendered.partial());
        }
        return batch.exitCode();
    }

    private static Map<String, Object> targetInfo(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final String pinEnvironment,
            final boolean overview) {
        String admission = session.admissionId(record.nuggetId());
        if (admission.isBlank() && !pinEnvironment.isBlank()) {
            admission = authenticateFromEnvironment(session, record, pinEnvironment);
        }
        if (admission.isBlank()) {
            throw new NuggetCliFailure(EXIT_DENIED, "NOT_AUTHENTICATED", "PIN admission required");
        }
        NuggetCliConnection connection = session.connect(record);
        if (overview) {
            NuggetIntrospection.Result<NuggetIntrospection.Overview> status = connection.overview(session.request(admission));
            return overviewMap(record, status);
        }
        return statusMap(record, connection.status(admission));
    }

    private static InspectionBatch collectInspection(
            final NuggetSession session,
            final CommandLine commandLine,
            final String command,
            final TargetCall call) {
        List<NuggetDiscovery.Record> requestedRecords = session.targetRecords();
        if (requestedRecords.isEmpty()) {
            throw new NuggetCliFailure(EXIT_UNAVAILABLE, "NO_TARGET", "no active target selected");
        }
        String pinEnvironment = commandLine.option("--pin-env");
        if (!pinEnvironment.isBlank() && !session.hasSingleConcreteTarget()) {
            throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
        }
        List<NuggetDiscovery.Record> records = requestedRecords.stream()
                .limit(MAX_INSPECTION_TARGETS)
                .toList();
        List<Map<String, Object>> results = new ArrayList<>();
        boolean cappedTargets = requestedRecords.size() > records.size();
        int failures = cappedTargets ? 1 : 0;
        int unavailableFailures = cappedTargets ? 1 : 0;
        if (cappedTargets) {
            results.add(targetErrorMap("TARGET_LIMIT", "TARGET_LIMIT"));
        }
        long deadlineNanos = session.commandDeadlineNanos();
        for (NuggetDiscovery.Record record : records) {
            try {
                Map<String, Object> result = session.callWithinCommandDeadline(record, () -> call.call(record), deadlineNanos);
                if (targetFailed(result)) {
                    failures++;
                    if (targetUnavailable(result)) {
                        unavailableFailures++;
                    }
                }
                results.add(result);
            } catch (NuggetCliFailure failure) {
                failures++;
                if (failure.exitCode() == EXIT_UNAVAILABLE) {
                    unavailableFailures++;
                }
                results.add(targetErrorMap(record, failure.code()));
            }
        }
        int exitCode = EXIT_OK;
        if (failures > 0) {
            boolean allFailed = failures >= records.size() && requestedRecords.size() <= records.size();
            exitCode = allFailed && unavailableFailures == 0 ? EXIT_DENIED : EXIT_UNAVAILABLE;
            String code = exitCode == EXIT_DENIED ? "DENIED" : (allFailed ? "UNAVAILABLE" : "PARTIAL");
            session.recordEvent("command-failure", Map.of(
                    "command", command,
                    "code", code,
                    "category", exitCode == EXIT_DENIED ? "denied" : "partial",
                    "partial", Boolean.toString(cappedTargets || (exitCode == EXIT_UNAVAILABLE && !allFailed))));
        }
        boolean partial = cappedTargets || (failures > 0 && failures < records.size());
        for (Map<String, Object> result : results) {
            if (Boolean.parseBoolean(String.valueOf(result.getOrDefault("partial", "false")))) {
                partial = true;
                break;
            }
        }
        return new InspectionBatch(List.copyOf(results), exitCode, partial);
    }

    private static boolean targetFailed(final Map<String, Object> target) {
        return !"OK".equals(target.get("state"));
    }

    private static boolean targetUnavailable(final Map<String, Object> target) {
        Object state = target.get("state");
        return state == null || (!"NOT_AUTHENTICATED".equals(state) && !"NOT_AUTHORIZED".equals(state));
    }

    private static String admittedForInspection(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final CommandLine commandLine) {
        return admittedForInspection(session, record, commandLine, null);
    }

    private static String admittedForInspection(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final CommandLine commandLine,
            final CommandDeadlineGuard guard) {
        String admission = session.admissionId(record.nuggetId());
        if (admission.isBlank() && !commandLine.option("--pin-env").isBlank()) {
            admission = authenticateFromEnvironment(session, record, commandLine.option("--pin-env"), guard);
        }
        if (admission.isBlank()) {
            throw new NuggetCliFailure(EXIT_DENIED, "NOT_AUTHENTICATED", "PIN admission required");
        }
        return admission;
    }

    private static int writeInspectionJson(
            final PrintStream output,
            final String command,
            final InspectionBatch batch) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("targets", batch.targets());
        jsonOkBounded(output, command, values, batch.partial());
        return batch.exitCode();
    }

    private static boolean sourcePartial(final NuggetIntrospection.CollectionResult<?> result) {
        return result.returned() < result.totalAvailable() && result.totalAvailableAuthorized();
    }

    private static SourceAppendResult appendApplicationRows(
            final List<Map<String, Object>> rows,
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> result) {
        if (!resultOk(result) || result.value() == null) {
            return new SourceAppendResult(false, false);
        }
        int appended = 0;
        boolean partial = false;
        for (NuggetIntrospection.ApplicationSummary summary : result.value().items()) {
            if (appended >= NuggetIntrospection.MAX_LIMIT) {
                partial = true;
                break;
            }
            rows.add(psApplicationRow(record, summary));
            appended++;
        }
        return new SourceAppendResult(true, partial);
    }

    private static SourceAppendResult appendObjectRows(
            final List<Map<String, Object>> rows,
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> result) {
        if (!resultOk(result) || result.value() == null) {
            return new SourceAppendResult(false, false);
        }
        int appended = 0;
        boolean partial = false;
        for (NuggetIntrospection.ObjectSummary summary : result.value().items()) {
            if (appended >= NuggetIntrospection.MAX_LIMIT) {
                partial = true;
                break;
            }
            rows.add(psObjectRow(record, summary));
            appended++;
        }
        return new SourceAppendResult(true, partial);
    }

    private static boolean resultOk(final NuggetIntrospection.Result<?> result) {
        return result != null && result.admitted() && result.authorized() && result.error() == null;
    }

    private static String resultCode(final NuggetIntrospection.Result<?> result) {
        if (result == null) {
            return "UNAVAILABLE";
        }
        if (!result.admitted()) {
            return "NOT_AUTHENTICATED";
        }
        if (!result.authorized()) {
            return "NOT_AUTHORIZED";
        }
        return result.error() == null ? "UNAVAILABLE" : result.error().code().name();
    }

    private static String resultCode(
            final NuggetIntrospection.Result<?> first,
            final NuggetIntrospection.Result<?> second) {
        if (!resultOk(first)) {
            return resultCode(first);
        }
        return resultCode(second);
    }

    private static QueryPlan queryPlan(final CommandLine commandLine, final QueryDomain domain) {
        int limit = boundedInteger(commandLine.option("--limit"), NuggetIntrospection.DEFAULT_LIMIT, 1, NuggetIntrospection.MAX_LIMIT, "limit");
        int offset = boundedInteger(commandLine.option("--offset"), 0, 0, Integer.MAX_VALUE, "offset");
        String sort = querySort(commandLine.option("--sort"), domain);
        NuggetIntrospection.SortDirection direction = commandLine.options().containsKey("--desc")
                ? NuggetIntrospection.SortDirection.DESC : NuggetIntrospection.SortDirection.ASC;
        if (commandLine.options().containsKey("--asc")) {
            direction = NuggetIntrospection.SortDirection.ASC;
        }
        Map<String, String> filters = queryFilters(commandLine.option("--filter"), domain);
        NuggetAdminQuery contractQuery = contractQuery(offset, limit, sort, direction, filters);
        return new QueryPlan(new NuggetIntrospection.Query(offset, limit, sort, direction, filters), contractQuery);
    }

    private static PsQueryPlan psQueryPlan(final CommandLine commandLine) {
        int limit = boundedInteger(commandLine.option("--limit"), NuggetIntrospection.DEFAULT_LIMIT, 1, NuggetIntrospection.MAX_LIMIT, "limit");
        int offset = boundedInteger(commandLine.option("--offset"), 0, 0, Integer.MAX_VALUE, "offset");
        String sort = querySort(commandLine.option("--sort"), QueryDomain.PS);
        NuggetIntrospection.SortDirection direction = commandLine.options().containsKey("--desc")
                ? NuggetIntrospection.SortDirection.DESC : NuggetIntrospection.SortDirection.ASC;
        if (commandLine.options().containsKey("--asc")) {
            direction = NuggetIntrospection.SortDirection.ASC;
        }
        Map<String, String> filters = queryFilters(commandLine.option("--filter"), QueryDomain.PS);
        return new PsQueryPlan(offset, limit, sort, direction, filters, contractQuery(offset, limit, sort, direction, filters));
    }

    static NuggetAdminQuery contractQueryForTesting(final List<String> rawTokens) {
        CommandLine commandLine = CommandLine.parse(rawTokens);
        validateCommandLine(commandLine);
        return switch (commandLine.command()) {
            case "ps" -> psQueryPlan(commandLine).contractQuery();
            case "ls", "objects" -> queryPlan(commandLine, QueryDomain.OBJECTS).contractQuery();
            case "apps" -> queryPlan(commandLine, QueryDomain.APPLICATIONS).contractQuery();
            case "disk" -> queryPlan(commandLine, QueryDomain.DISK).contractQuery();
            case "errors" -> queryPlan(commandLine, QueryDomain.ERRORS).contractQuery();
            default -> throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "command does not use a public query contract");
        };
    }

    static NuggetAdminRequestContext contractContextForTesting(
            final String command,
            final NuggetDiscovery.Record record,
            final NuggetAdminOperationKind operationKind,
            final Instant requestedAt,
            final Instant deadlineAt) {
        return contractContext(command, record, operationKind, requestedAt, deadlineAt);
    }

    private static NuggetAdminRequestContext contractContext(
            final String command,
            final NuggetDiscovery.Record record,
            final NuggetAdminOperationKind operationKind,
            final Instant requestedAt,
            final Instant deadlineAt) {
        NuggetAdminTarget target = null;
        if (record != null && !record.nuggetId().isBlank() && !record.runtimeId().isBlank()) {
            target = new NuggetAdminTarget(
                    record.nuggetId(),
                    record.runtimeId(),
                    0L,
                    record.endpoint().toString(),
                    "j7m-cli");
        }
        Instant safeRequestedAt = requestedAt == null ? Instant.EPOCH : requestedAt;
        Instant safeDeadlineAt = deadlineAt == null ? safeRequestedAt.plus(COMMAND_DEADLINE) : deadlineAt;
        Duration budget = Duration.between(safeRequestedAt, safeDeadlineAt);
        if (budget.isNegative() || budget.isZero()) {
            budget = Duration.ZERO;
        }
        return new NuggetAdminRequestContext(
                NuggetAdminContract.PROTOCOL_VERSION,
                "cli-" + safe(command),
                "cli-" + safe(command),
                target,
                operationKind,
                safeRequestedAt,
                budget.compareTo(NuggetAdminContract.MAX_CLIENT_BUDGET) > 0
                        ? NuggetAdminContract.MAX_CLIENT_BUDGET : budget,
                safeDeadlineAt,
                Map.of("client", "j7m-cli"));
    }

    private static Map<String, String> contractMetadata(final NuggetAdminRequestContext context) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("client", "j7m-cli");
        metadata.put("adminProtocolVersion", Integer.toString(context.protocolVersion()));
        metadata.put("adminJsonFormat", NuggetAdminContract.JSON_FORMAT_NAME);
        metadata.put("adminJsonFormatVersion", Integer.toString(NuggetAdminContract.JSON_FORMAT_VERSION));
        metadata.put("adminOperationKind", context.operationKind().name());
        return metadata;
    }

    private static NuggetAdminQuery contractQuery(
            final int offset,
            final int limit,
            final String sort,
            final NuggetIntrospection.SortDirection direction,
            final Map<String, String> filters) {
        List<NuggetAdminQuery.Filter> contractFilters = new ArrayList<>();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            contractFilters.add(new NuggetAdminQuery.Filter(contractField(entry.getKey()), entry.getValue()));
        }
        NuggetAdminSortDirection contractDirection = direction == NuggetIntrospection.SortDirection.DESC
                ? NuggetAdminSortDirection.DESC : NuggetAdminSortDirection.ASC;
        return new NuggetAdminQuery(
                offset,
                limit,
                contractFilters,
                List.of(new NuggetAdminQuery.Sort(contractField(sort), contractDirection)));
    }

    private static NuggetAdminField contractField(final String value) {
        return switch (value) {
            case "nuggetId" -> NuggetAdminField.NUGGET_ID;
            case "applicationId" -> NuggetAdminField.APPLICATION_ID;
            case "coordinate" -> NuggetAdminField.COORDINATE;
            case "instanceId" -> NuggetAdminField.INSTANCE_ID;
            case "serviceName" -> NuggetAdminField.SERVICE_NAME;
            case "objectKind", "kind" -> NuggetAdminField.OBJECT_KIND;
            case "visibility" -> NuggetAdminField.VISIBILITY;
            case "state", "lifecycleState" -> NuggetAdminField.STATE;
            case "health" -> NuggetAdminField.HEALTH;
            case "readiness" -> NuggetAdminField.READINESS;
            case "transportId" -> NuggetAdminField.TRANSPORT_ID;
            case "role" -> NuggetAdminField.ROLE;
            case "rootId" -> NuggetAdminField.ROOT_ID;
            case "timestamp" -> NuggetAdminField.TIMESTAMP;
            case "id" -> NuggetAdminField.STABLE_ID;
            default -> throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "unsupported public contract field");
        };
    }

    private static String querySort(final String value, final QueryDomain domain) {
        String candidate = value == null || value.isBlank() ? domain.defaultSort() : value;
        String normalized = switch (candidate) {
            case "id" -> "id";
            case "app", "application", "applicationId" -> "applicationId";
            case "instance", "instanceId" -> "instanceId";
            case "coordinate" -> "coordinate";
            case "state" -> "state";
            case "service", "serviceName" -> "serviceName";
            case "visibility", "vis" -> "visibility";
            case "transport", "transportId" -> "transportId";
            case "root", "rootId" -> "rootId";
            case "time", "timestamp" -> "timestamp";
            default -> candidate;
        };
        if (!domain.sorts().contains(normalized)) {
            throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "unsupported sort field");
        }
        return normalized;
    }

    private static Map<String, String> queryFilters(final String option, final QueryDomain domain) {
        if (option == null || option.isBlank()) {
            return Map.of();
        }
        Map<String, String> filters = new LinkedHashMap<>();
        String[] pairs = option.split(",");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) {
                throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "filter must use key=value");
            }
            String key = queryFilterKey(pair.substring(0, separator), domain);
            String value = pair.substring(separator + 1);
            filters.put(key, value);
        }
        return filters;
    }

    private static PsRows applyPsQuery(
            final List<Map<String, Object>> sourceRows,
            final PsQueryPlan query) {
        List<Map<String, Object>> filtered = new ArrayList<>(Math.min(sourceRows.size(), MAX_RENDERED_ROWS));
        for (Map<String, Object> row : sourceRows) {
            if (psMatches(row, query.filters())) {
                filtered.add(row);
            }
        }
        filtered.sort(psComparator(query.sortBy(), query.direction()));
        int totalAvailable = filtered.size();
        int from = Math.min(query.offset(), totalAvailable);
        long requestedEnd = (long) from + (long) query.limit();
        int to = requestedEnd > Integer.MAX_VALUE ? totalAvailable : Math.min(totalAvailable, (int) requestedEnd);
        List<Map<String, Object>> selected = new ArrayList<>(Math.min(to - from, MAX_RENDERED_ROWS));
        boolean partial = totalAvailable > to;
        for (int index = from; index < to && selected.size() < MAX_RENDERED_ROWS; index++) {
            selected.add(filtered.get(index));
        }
        if (to - from > selected.size()) {
            partial = true;
        }
        return new PsRows(List.copyOf(selected), totalAvailable, partial);
    }

    private static boolean psMatches(
            final Map<String, Object> row,
            final Map<String, String> filters) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String actual = psRowValue(row, filter.getKey());
            if (!filter.getValue().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static Comparator<Map<String, Object>> psComparator(
            final String sortBy,
            final NuggetIntrospection.SortDirection direction) {
        Comparator<Map<String, Object>> primary = Comparator.comparing(row -> psRowValue(row, sortBy));
        if (direction == NuggetIntrospection.SortDirection.DESC) {
            primary = primary.reversed();
        }
        return primary
                .thenComparing(row -> psRowValue(row, "nugget"))
                .thenComparing(row -> psRowValue(row, "kind"))
                .thenComparing(row -> psRowValue(row, "id"));
    }

    private static String psRowValue(final Map<String, Object> row, final String field) {
        String key = switch (field) {
            case "applicationId" -> "applicationId";
            case "instanceId" -> "instanceId";
            case "coordinate" -> "coordinate";
            case "state" -> "state";
            case "nuggetId" -> "nugget";
            case "serviceName" -> "service";
            case "objectKind" -> "kind";
            case "visibility" -> "visibility";
            case "lifecycleState" -> "state";
            case "health" -> "health";
            case "interfaceName" -> "interfaces";
            case "registryContext" -> "registry";
            case "transportId" -> "transport";
            case "kind" -> "kind";
            case "app" -> "app";
            case "id" -> "id";
            default -> field;
        };
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String queryFilterKey(final String value, final QueryDomain domain) {
        String normalized = switch (value) {
            case "app", "application", "applicationId" -> "applicationId";
            case "instance", "instanceId" -> "instanceId";
            case "coordinate" -> "coordinate";
            case "state" -> "state";
            case "nugget", "nuggetId" -> "nuggetId";
            case "service", "serviceName" -> "serviceName";
            case "kind", "objectKind" -> "objectKind";
            case "visibility", "vis" -> "visibility";
            case "health" -> "health";
            case "interface", "interfaceName" -> "interfaceName";
            case "registry", "registryContext" -> "registryContext";
            case "transport", "transportId" -> "transportId";
            case "root", "rootId" -> "rootId";
            case "role" -> "role";
            default -> value;
        };
        if (!domain.filters().contains(normalized)) {
            throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "unsupported filter field");
        }
        return normalized;
    }

    private static int boundedInteger(
            final String value,
            final int defaultValue,
            final int minimum,
            final int maximum,
            final String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", name + " outside allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", name + " must be an integer");
        }
    }

    private static int width(final CommandLine commandLine) {
        return boundedInteger(commandLine.option("--width"), DEFAULT_TERMINAL_WIDTH, MIN_TERMINAL_WIDTH, MAX_TERMINAL_WIDTH, "width");
    }

    private static void validateCommandLine(final CommandLine commandLine) {
        switch (commandLine.command()) {
            case "help", "discover", "nuggets", "target", "whoami", "reconnect" -> requireArgumentCount(commandLine, 0, 0);
            case "talk" -> validateTalkArguments(commandLine);
            case "pin" -> validatePinArguments(commandLine);
            case "auth", "info", "status" -> requireArgumentCount(commandLine, 0, 0);
            case "env", "health", "readiness", "metrics" -> {
                requireArgumentCount(commandLine, 0, 0);
                width(commandLine);
            }
            case "ps" -> {
                requireArgumentCount(commandLine, 0, 0);
                psQueryPlan(commandLine);
                width(commandLine);
            }
            case "ls", "objects" -> {
                requireArgumentCount(commandLine, 0, 0);
                queryPlan(commandLine, QueryDomain.OBJECTS);
                width(commandLine);
            }
            case "apps" -> {
                requireArgumentCount(commandLine, 0, 0);
                queryPlan(commandLine, QueryDomain.APPLICATIONS);
                width(commandLine);
            }
            case "object", "app" -> requireArgumentCount(commandLine, 1, 1);
            case "start" -> validateStartArguments(commandLine);
            case "stop", "drain", "unload" -> validateInstanceLifecycleArguments(commandLine);
            case "drain-nugget", "shutdown-nugget", "kill-nugget" -> validateNuggetControlArguments(commandLine);
            case "disk" -> {
                requireArgumentCount(commandLine, 0, 0);
                queryPlan(commandLine, QueryDomain.DISK);
                width(commandLine);
            }
            case "errors" -> {
                requireArgumentCount(commandLine, 0, 0);
                queryPlan(commandLine, QueryDomain.ERRORS);
                width(commandLine);
            }
            case "exit", "quit" -> requireArgumentCount(commandLine, 0, 0);
            default -> {
                if (!commandLine.options().isEmpty()) {
                    throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unknown nugget command");
                }
            }
        }
    }

    private static void validateTalkArguments(final CommandLine commandLine) {
        if (!commandLine.option("--target").isBlank()) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unsupported option for command");
        }
        if (!commandLine.option("--id").isBlank() && !commandLine.arguments().isEmpty()) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "talk accepts --id or one target name");
        }
        requireArgumentCount(commandLine, 0, 1);
    }

    private static void validatePinArguments(final CommandLine commandLine) {
        if (commandLine.options().containsKey("--all")
                && (commandLine.arguments().size() != 1 || !"clear".equals(commandLine.arguments().get(0)))) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "--all is only valid with pin clear");
        }
        requireArgumentCount(commandLine, 0, 1);
    }

    private static void validateStartArguments(final CommandLine commandLine) {
        validateTargetOptions(commandLine);
        if (commandLine.options().containsKey("--dry-run") && commandLine.options().containsKey("--plan")) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "--dry-run and --plan cannot be combined");
        }
        requireArgumentCount(commandLine, 1, 1);
        try {
            ApplicationCoordinate.parse(commandLine.arguments().get(0));
        } catch (IllegalArgumentException exception) {
            throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "application coordinate must use group:name:version");
        }
        width(commandLine);
    }

    private static void validateInstanceLifecycleArguments(final CommandLine commandLine) {
        validateTargetOptions(commandLine);
        requireArgumentCount(commandLine, 1, 1);
        new ApplicationInstanceId(commandLine.arguments().get(0));
        width(commandLine);
    }

    private static void validateNuggetControlArguments(final CommandLine commandLine) {
        validateTargetOptions(commandLine);
        requireArgumentCount(commandLine, 1, 1);
        if ("drain-nugget".equals(commandLine.command()) && commandLine.options().containsKey("--confirm")) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "--confirm is not valid for drain-nugget");
        }
        width(commandLine);
    }

    private static void validateTargetOptions(final CommandLine commandLine) {
        if (!commandLine.option("--target").isBlank() && !commandLine.option("--id").isBlank()) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "--target and --id cannot be combined");
        }
    }

    private static void requireArgumentCount(
            final CommandLine commandLine,
            final int minimum,
            final int maximum) {
        int count = commandLine.arguments().size();
        if (count < minimum || count > maximum) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "invalid argument count for command");
        }
    }

    private static Map<String, Object> targetBase(final NuggetDiscovery.Record record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", displayName(record));
        values.put("id", record.nuggetId());
        values.put("state", "OK");
        return values;
    }

    private static RowsResult flattenRows(final List<Map<String, Object>> targets) {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean partial = false;
        for (Map<String, Object> target : targets) {
            if (Boolean.parseBoolean(String.valueOf(target.getOrDefault("partial", "false")))) {
                partial = true;
            }
            Object nested = target.get("rows");
            if (nested instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && rows.size() < MAX_RENDERED_ROWS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            row.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        rows.add(row);
                    } else if (rows.size() >= MAX_RENDERED_ROWS) {
                        partial = true;
                        break;
                    }
                }
            } else if (!"OK".equals(target.get("state")) && rows.size() < MAX_RENDERED_ROWS) {
                rows.add(target);
            } else if (!"OK".equals(target.get("state"))) {
                partial = true;
            }
        }
        return new RowsResult(List.copyOf(rows), partial);
    }

    private static Map<String, Object> psApplicationRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ApplicationSummary summary) {
        Map<String, Object> row = applicationRow(record, summary);
        row.put("kind", "APP");
        row.put("visibility", "-");
        row.put("app", summary.displayName());
        row.put("service", Integer.toString(summary.exportedServiceCount()));
        row.put("id", summary.instanceId());
        row.put("invocations", "-");
        row.put("endpoint", "-");
        row.put("transport", "-");
        row.put("registry", "-");
        row.put("generation", "-");
        row.put("lease", "-");
        row.put("interfaces", "-");
        row.put("owner", summary.applicationId());
        return row;
    }

    private static Map<String, Object> psObjectRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ObjectSummary summary) {
        Map<String, Object> row = objectRow(record, summary);
        row.put("app", summary.ownerApplication());
        row.put("id", summary.objectId());
        row.put("invocations", Long.toString(summary.invocationCount()));
        row.put("lastError", summary.lastSafeErrorCode());
        row.put("endpoint", summary.endpointSummary());
        row.put("owner", summary.ownerApplication());
        return row;
    }

    private static Map<String, Object> applicationRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ApplicationSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nugget", displayName(record));
        row.put("kind", "APP");
        row.put("applicationId", summary.applicationId());
        row.put("coordinate", summary.coordinate());
        row.put("instanceId", summary.instanceId());
        row.put("name", summary.displayName());
        row.put("version", summary.version());
        row.put("state", summary.state());
        row.put("activationId", summary.activationId());
        row.put("services", Integer.toString(summary.exportedServiceCount()));
        row.put("health", summary.health().name());
        row.put("readiness", summary.readiness().name());
        row.put("lastError", summary.lastSafeErrorCode());
        return row;
    }

    private static Map<String, Object> objectRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ObjectSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nugget", displayName(record));
        row.put("kind", summary.kind());
        row.put("visibility", summary.visibility().name());
        row.put("state", summary.lifecycleState());
        row.put("registration", summary.registrationState());
        row.put("service", summary.serviceName());
        row.put("objectId", summary.objectId());
        row.put("logicalName", summary.logicalName());
        row.put("health", summary.health().name());
        row.put("readiness", summary.readiness().name());
        row.put("transport", summary.transportId());
        row.put("registry", summary.registryContext());
        row.put("generation", Long.toString(summary.generation()));
        row.put("lease", Long.toString(summary.leaseExpiryMillis()));
        row.put("interfaces", summary.interfaces());
        row.put("endpoint", summary.endpointSummary());
        row.put("invocations", Long.toString(summary.invocationCount()));
        row.put("failures", Long.toString(summary.failureCount()));
        row.put("lastError", summary.lastSafeErrorCode());
        row.put("owner", summary.ownerApplication());
        return row;
    }

    private static Map<String, Object> diskRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.DiskVolumeSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nugget", displayName(record));
        row.put("role", summary.role());
        row.put("root", summary.rootId());
        row.put("path", summary.pathSummary());
        row.put("total", bytes(summary.totalBytes()));
        row.put("used", bytes(summary.usedBytes()));
        row.put("available", bytes(summary.availableBytes()));
        row.put("percent", percent(summary.percentUsed()));
        row.put("health", summary.health().name());
        row.put("availability", summary.availability().name());
        row.put("error", summary.safeErrorCode());
        return row;
    }

    private static Map<String, Object> errorRow(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.RecentErrorEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nugget", displayName(record));
        row.put("time", entry.timestamp().toString());
        row.put("component", entry.component());
        row.put("code", entry.errorCode().name());
        row.put("message", entry.safeMessage());
        row.put("resource", entry.resourceSummary());
        row.put("retryable", Boolean.toString(entry.retryable()));
        row.put("count", Long.toString(entry.recurrenceCount()));
        return row;
    }

    private static Map<String, Object> environmentTarget(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> result) {
        if (!resultOk(result) || result.value() == null) {
            return targetErrorMap(record, resultCode(result));
        }
        NuggetIntrospection.EnvironmentSummary value = result.value();
        Map<String, Object> row = targetBase(record);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("nugget", displayName(record));
        environment.put("jvm", value.jvmVersion());
        environment.put("vendor", value.jvmVendor());
        environment.put("os", value.osName());
        environment.put("arch", value.architecture());
        environment.put("pid", value.processId());
        environment.put("heap", bytes(value.heapUsedBytes()));
        environment.put("threads", Integer.toString(value.liveThreadCount()));
        environment.put("classes", Integer.toString(value.loadedClassCount()));
        environment.put("envNames", Integer.toString(value.environmentVariableNames().size()));
        rows.add(environment);
        row.put("rows", rows);
        row.put("partial", Boolean.toString(result.partial()));
        return row;
    }

    private static Map<String, Object> componentTarget(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<?> result,
            final String field) {
        if (!resultOk(result) || result.value() == null) {
            return targetErrorMap(record, resultCode(result));
        }
        List<NuggetIntrospection.ComponentSummary> components;
        String state;
        boolean partial;
        if (result.value() instanceof NuggetIntrospection.HealthSummary health) {
            state = health.state().name();
            components = health.components();
            partial = health.partial();
        } else if (result.value() instanceof NuggetIntrospection.ReadinessSummary readiness) {
            state = readiness.state().name();
            components = readiness.components();
            partial = readiness.partial();
        } else {
            return targetErrorMap(record, "UNAVAILABLE");
        }
        Map<String, Object> row = targetBase(record);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (components.isEmpty()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("nugget", displayName(record));
            summary.put("component", "overall");
            summary.put(field, state);
            summary.put("availability", "-");
            summary.put("reason", "-");
            rows.add(summary);
        } else {
            for (NuggetIntrospection.ComponentSummary component : components) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("nugget", displayName(record));
                summary.put("component", component.component());
                summary.put(field, state);
                summary.put("health", component.health().name());
                summary.put("readiness", component.readiness().name());
                summary.put("availability", component.availability().name());
                summary.put("reason", component.safeReason());
                rows.add(summary);
            }
        }
        row.put("rows", rows);
        row.put("partial", Boolean.toString(result.partial() || partial));
        return row;
    }

    private static Map<String, Object> metricsTarget(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> result) {
        if (!resultOk(result) || result.value() == null) {
            return targetErrorMap(record, resultCode(result));
        }
        Map<String, Object> target = targetBase(record);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NuggetIntrospection.MetricEntry metric : result.value().metrics()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nugget", displayName(record));
            row.put("metric", metric.id());
            row.put("value", Double.toString(metric.value()));
            row.put("unit", metric.unit());
            row.put("labels", metric.labels());
            rows.add(row);
        }
        target.put("rows", rows);
        target.put("partial", Boolean.toString(result.partial() || result.value().partial()));
        return target;
    }

    private static Map<String, Object> objectDetailMap(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ObjectDetail detail) {
        Map<String, Object> values = objectRow(record, detail.summary());
        values.put("descriptorMetadata", detail.descriptorMetadata());
        values.put("annotationMetadata", detail.annotationMetadata());
        return values;
    }

    private static Map<String, Object> applicationDetailMap(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.ApplicationDetail detail) {
        Map<String, Object> values = applicationRow(record, detail.summary());
        values.put("declaredServices", serviceRows(detail.declaredServices()));
        values.put("modules", detail.moduleNames());
        values.put("capabilities", detail.capabilities());
        values.put("verification", detail.verificationStatus());
        values.put("signature", detail.signatureStatus());
        values.put("sbom", detail.sbomStatus());
        values.put("provenance", detail.provenanceStatus());
        values.put("auditCounters", detail.auditCounters());
        return values;
    }

    private static Map<String, Object> lifecycleResultMap(
            final NuggetDiscovery.Record record,
            final NuggetApplicationLifecycle.Result result) {
        Map<String, Object> values = targetBase(record);
        values.put("operationId", result.operationId());
        values.put("correlationId", result.correlationId());
        values.put("action", result.action().name());
        values.put("admitted", Boolean.toString(result.admitted()));
        values.put("authorized", Boolean.toString(result.authorized()));
        values.put("dryRun", Boolean.toString(result.dryRun()));
        values.put("runtimeId", result.runtimeId());
        values.put("coordinate", result.coordinate() == null ? "" : result.coordinate().toString());
        values.put("instanceId", result.instanceId() == null ? "" : result.instanceId().value());
        values.put("previousState", result.previousState() == null ? "" : result.previousState().name());
        values.put("currentState", result.currentState() == null ? "" : result.currentState().name());
        values.put("resultingState", result.resultingState() == null ? "" : result.resultingState().name());
        values.put("outcome", result.outcome().name());
        values.put("outcomeText", safeLifecycleOutcomeText(result));
        values.put("accepted", Boolean.toString(result.accepted()));
        values.put("inProgress", Boolean.toString(result.inProgress()));
        values.put("completed", Boolean.toString(result.completed()));
        values.put("partial", Boolean.toString(result.partial()));
        values.put("outcomeUnknown", Boolean.toString(result.outcomeUnknown()));
        values.put("verificationSummary", safeLifecycleText(result.verificationSummary(), "verification"));
        values.put("authorizationSummary", safeLifecycleText(result.authorizationSummary(), "authorization"));
        values.put("warnings", safeLifecycleList(result.warnings()));
        values.put("elapsedMillis", Long.toString(result.elapsed().toMillis()));
        values.put("metadata", result.metadata());
        if (result.plan() != null) {
            values.put("plan", lifecyclePlanMap(result.plan()));
        }
        if (result.deploymentResult() != null) {
            values.put("deploymentResult", lifecycleDeploymentResultMap(result.deploymentResult()));
        }
        if (result.activation() != null) {
            values.put("activation", lifecycleActivationMap(result.activation()));
        }
        if (result.error() != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", result.error().code().name());
            error.put("message", safe(result.error().message()));
            error.put("details", result.error().details());
            values.put("error", error);
            values.put("state", result.error().code().name());
        }
        return values;
    }

    private static Map<String, Object> nuggetControlResultMap(
            final NuggetDiscovery.Record record,
            final NuggetControl.Result result) {
        Map<String, Object> values = targetBase(record);
        values.put("operationId", result.operationId());
        values.put("correlationId", result.correlationId());
        values.put("action", result.action().name());
        values.put("mode", result.mode().name());
        values.put("admitted", Boolean.toString(result.admitted()));
        values.put("authorized", Boolean.toString(result.authorized()));
        values.put("runtimeId", result.targetRuntimeId());
        values.put("generation", Long.toString(result.targetGeneration()));
        values.put("previousState", result.previousState().name());
        values.put("currentState", result.currentState().name());
        values.put("resultingState", result.resultingState().name());
        values.put("outcome", result.outcome().name());
        values.put("outcomeText", safeLifecycleText(result.outcomeText(), "control"));
        values.put("accepted", Boolean.toString(result.accepted()));
        values.put("inProgress", Boolean.toString(result.inProgress()));
        values.put("completed", Boolean.toString(result.completed()));
        values.put("partial", Boolean.toString(result.partial()));
        values.put("outcomeUnknown", Boolean.toString(result.outcomeUnknown()));
        values.put("cleanupPartial", Boolean.toString(result.cleanupPartial()));
        values.put("warnings", safeLifecycleList(result.warnings()));
        values.put("elapsedMillis", Long.toString(result.elapsed().toMillis()));
        values.put("metadata", result.metadata());
        if (result.confirmation() != null && result.outcome() == NuggetControl.Outcome.CONFIRMATION_REQUIRED) {
            Map<String, Object> confirmation = new LinkedHashMap<>();
            confirmation.put("operationId", result.confirmation().operationId());
            confirmation.put("challenge", result.confirmation().challenge());
            confirmation.put("expiresAt", result.confirmation().expiresAt().toString());
            values.put("confirmation", confirmation);
        }
        if (result.error() != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", result.error().code().name());
            error.put("message", safe(result.error().message()));
            error.put("details", result.error().details());
            values.put("error", error);
            values.put("state", result.error().code().name());
        }
        return values;
    }

    private static Map<String, Object> lifecyclePlanMap(final org.j7m.middleware.application.api.ApplicationDeploymentPlan plan) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("coordinate", plan.coordinate().toString());
        values.put("state", plan.state().name());
        values.put("steps", safeLifecycleList(plan.steps()));
        return values;
    }

    private static Map<String, Object> lifecycleDeploymentResultMap(
            final org.j7m.middleware.application.api.ApplicationDeploymentResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("success", Boolean.toString(result.success()));
        values.put("state", result.state().name());
        values.put("message", safeLifecycleText(result.message(), result.success() ? "completed" : "failed"));
        values.put("details", safeLifecycleList(result.details()));
        return values;
    }

    private static Map<String, Object> lifecycleActivationMap(
            final org.j7m.middleware.application.api.ApplicationActivation activation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("instanceId", activation.instanceIdentity().instanceId().value());
        values.put("coordinate", activation.instanceIdentity().application().coordinate().toString());
        values.put("state", activation.state().name());
        values.put("exportedServices", Integer.toString(activation.exportedServices().size()));
        return values;
    }

    private static void writeLifecycleHuman(
            final PrintStream output,
            final Map<String, Object> values,
            final NuggetApplicationLifecycle.Result result) {
        output.println(values.get("action") + " " + values.get("outcome") + " " + values.get("outcomeText"));
        String coordinate = String.valueOf(values.get("coordinate"));
        String instanceId = String.valueOf(values.get("instanceId"));
        if (!coordinate.isBlank()) {
            output.println("  coordinate " + coordinate);
        }
        if (!instanceId.isBlank()) {
            output.println("  instance " + instanceId);
        }
        output.println("  state " + values.get("previousState") + " -> " + values.get("resultingState"));
        output.println("  target " + values.get("name") + " " + shortId(String.valueOf(values.get("id"))));
        if (result.error() != null) {
            output.println("  error " + result.error().code().name() + " " + safe(result.error().message()));
            String reason = result.error().details().get("reason");
            if (reason != null && !reason.isBlank()) {
                output.println("  reason " + safe(reason));
            }
        }
        writePartialNotice(output, result.partial());
    }

    private static void writeNuggetControlHuman(
            final PrintStream output,
            final Map<String, Object> values,
            final NuggetControl.Result result) {
        output.println(values.get("action") + " " + values.get("outcome") + " " + values.get("outcomeText"));
        output.println("  target " + values.get("name") + " " + shortId(String.valueOf(values.get("id"))));
        output.println("  state " + values.get("previousState") + " -> " + values.get("resultingState"));
        if (result.confirmation() != null && result.outcome() == NuggetControl.Outcome.CONFIRMATION_REQUIRED) {
            output.println("  operation " + result.confirmation().operationId());
            output.println("  confirm " + result.confirmation().challenge());
            output.println("  expires " + result.confirmation().expiresAt());
        }
        if (result.error() != null) {
            output.println("  error " + result.error().code().name() + " " + safe(result.error().message()));
            String reason = result.error().details().get("reason");
            if (reason != null && !reason.isBlank()) {
                output.println("  reason " + safe(reason));
            }
        }
        writePartialNotice(output, result.partial());
    }

    private static List<Map<String, Object>> serviceRows(final List<NuggetIntrospection.ServiceSummary> services) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NuggetIntrospection.ServiceSummary service : services) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service.serviceName());
            row.put("interface", service.interfaceName());
            row.put("implementation", service.implementationSummary());
            rows.add(row);
        }
        return rows;
    }

    private static List<Column> psColumns(final CommandLine commandLine) {
        if (commandLine.wide() || width(commandLine) >= 100) {
            return List.of(
                    new Column("NUGGET", "nugget", 10),
                    new Column("KIND", "kind", 7),
                    new Column("VIS", "visibility", 8),
                    new Column("STATE", "state", 10),
                    new Column("APP", "app", 14),
                    new Column("SERVICE", "service", 16),
                    new Column("ID", "id", 14),
                    new Column("HEALTH", "health", 9),
                    new Column("READINESS", "readiness", 10),
                    new Column("INV", "invocations", 8),
                    new Column("ERR", "lastError", 10),
                    new Column("TRANSPORT", "transport", 9),
                    new Column("REGISTRY", "registry", 9),
                    new Column("GEN", "generation", 6),
                    new Column("LEASE", "lease", 12),
                    new Column("INTERFACES", "interfaces", 24),
                    new Column("OWNER", "owner", 14),
                    new Column("ENDPOINT", "endpoint", 20));
        }
        return List.of(
                new Column("NUGGET", "nugget", 8),
                new Column("KIND", "kind", 5),
                new Column("VIS", "visibility", 4),
                new Column("STATE", "state", 8),
                new Column("APP", "app", 8),
                new Column("SERVICE", "service", 9),
                new Column("ID", "id", 10),
                new Column("HEALTH", "health", 7));
    }

    private static List<Column> objectColumns(final boolean wide) {
        if (wide) {
            return List.of(
                    new Column("NUGGET", "nugget", 10),
                    new Column("VIS", "visibility", 8),
                    new Column("STATE", "state", 10),
                    new Column("SERVICE", "service", 18),
                    new Column("OBJECT", "objectId", 16),
                    new Column("HEALTH", "health", 9),
                    new Column("READINESS", "readiness", 10),
                    new Column("TRANSPORT", "transport", 10),
                    new Column("REGISTRY", "registry", 10),
                    new Column("GEN", "generation", 8),
                    new Column("INTERFACES", "interfaces", 28),
                    new Column("ENDPOINT", "endpoint", 24));
        }
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("VIS", "visibility", 8),
                new Column("STATE", "state", 10),
                new Column("SERVICE", "service", 16),
                new Column("OBJECT", "objectId", 18),
                new Column("HEALTH", "health", 9));
    }

    private static List<Column> applicationColumns(final boolean wide) {
        if (wide) {
            return List.of(
                    new Column("NUGGET", "nugget", 10),
                    new Column("APP", "applicationId", 18),
                    new Column("INSTANCE", "instanceId", 16),
                    new Column("NAME", "name", 16),
                    new Column("VERSION", "version", 10),
                    new Column("STATE", "state", 10),
                    new Column("SERVICES", "services", 8),
                    new Column("HEALTH", "health", 9),
                    new Column("READINESS", "readiness", 10),
                    new Column("LAST_ERROR", "lastError", 14));
        }
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("APP", "applicationId", 18),
                new Column("INSTANCE", "instanceId", 16),
                new Column("STATE", "state", 10),
                new Column("SERVICES", "services", 8),
                new Column("HEALTH", "health", 9));
    }

    private static List<Column> diskColumns() {
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("ROLE", "role", 14),
                new Column("ROOT", "root", 16),
                new Column("TOTAL", "total", 10),
                new Column("USED", "used", 10),
                new Column("AVAIL", "available", 10),
                new Column("USE%", "percent", 6),
                new Column("HEALTH", "health", 10),
                new Column("PATH", "path", 20));
    }

    private static List<Column> errorColumns() {
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("TIME", "time", 20),
                new Column("COMPONENT", "component", 14),
                new Column("CODE", "code", 18),
                new Column("COUNT", "count", 6),
                new Column("MESSAGE", "message", 30));
    }

    private static List<Column> environmentColumns() {
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("JVM", "jvm", 12),
                new Column("VENDOR", "vendor", 12),
                new Column("OS", "os", 14),
                new Column("ARCH", "arch", 10),
                new Column("PID", "pid", 8),
                new Column("HEAP", "heap", 10),
                new Column("THREADS", "threads", 8),
                new Column("ENV", "envNames", 6));
    }

    private static List<Column> componentColumns(final String stateColumn) {
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("COMPONENT", "component", 24),
                new Column(stateColumn.toUpperCase(Locale.ROOT), stateColumn, 10),
                new Column("HEALTH", "health", 10),
                new Column("READINESS", "readiness", 10),
                new Column("AVAIL", "availability", 12),
                new Column("REASON", "reason", 20));
    }

    private static List<Column> metricColumns() {
        return List.of(
                new Column("NUGGET", "nugget", 10),
                new Column("METRIC", "metric", 32),
                new Column("VALUE", "value", 12),
                new Column("UNIT", "unit", 12),
                new Column("LABELS", "labels", 24));
    }

    private static RenderResult renderTable(
            final PrintStream output,
            final List<Column> requestedColumns,
            final List<Map<String, Object>> rows,
            final int width) {
        List<Column> columns = fitColumns(requestedColumns, width);
        StringBuilder builder = new StringBuilder(Math.min(MAX_OUTPUT_BYTES, Math.max(256, rows.size() * 64)));
        RenderBudget budget = new RenderBudget(Math.max(0, MAX_OUTPUT_BYTES - asciiBytes(PARTIAL_NOTICE)));
        appendRow(builder, budget, columns, headerMap(columns));
        int rendered = 0;
        boolean partial = false;
        for (Map<String, Object> row : rows) {
            if (rendered >= MAX_RENDERED_ROWS || !appendRow(builder, budget, columns, row)) {
                partial = true;
                break;
            }
            rendered++;
        }
        output.print(builder.toString());
        return new RenderResult(partial || rendered < rows.size());
    }

    private static RenderResult renderDetail(final PrintStream output, final Map<String, Object> values) {
        StringBuilder builder = new StringBuilder(512);
        RenderBudget budget = new RenderBudget(MAX_OUTPUT_BYTES);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!appendBounded(builder, budget, entry.getKey(), 16)
                    || !appendAscii(builder, budget, " ", 16)
                    || !appendDetailValue(builder, budget, entry.getValue(), 0)
                    || !appendAscii(builder, budget, "\n", 0)) {
                break;
            }
        }
        output.print(builder.toString());
        return new RenderResult(budget.partial());
    }

    private static boolean appendDetailValue(
            final StringBuilder builder,
            final RenderBudget budget,
            final Object value,
            final int depth) {
        if (depth >= MAX_DETAIL_DEPTH) {
            budget.markPartial();
            return appendAscii(builder, budget, "<truncated>", 0);
        }
        if (value instanceof List<?> list) {
            return appendDetailList(builder, budget, list, depth);
        }
        if (value instanceof Map<?, ?> map) {
            return appendDetailMap(builder, budget, map, depth);
        }
        return appendBounded(builder, budget, String.valueOf(value), 0);
    }

    private static boolean appendDetailList(
            final StringBuilder builder,
            final RenderBudget budget,
            final List<?> list,
            final int depth) {
        if (!appendAscii(builder, budget, "[", 1)) {
            return false;
        }
        int count = 0;
        for (Object item : list) {
            if (count >= MAX_DETAIL_ITEMS) {
                budget.markPartial();
                break;
            }
            if (count > 0 && !appendAscii(builder, budget, ", ", 1)) {
                return false;
            }
            if (!appendDetailValue(builder, budget, item, depth + 1)) {
                return false;
            }
            count++;
        }
        return appendAscii(builder, budget, "]", 0);
    }

    private static boolean appendDetailMap(
            final StringBuilder builder,
            final RenderBudget budget,
            final Map<?, ?> map,
            final int depth) {
        if (!appendAscii(builder, budget, "{", 1)) {
            return false;
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_DETAIL_ITEMS) {
                budget.markPartial();
                break;
            }
            if (count > 0 && !appendAscii(builder, budget, ", ", 1)) {
                return false;
            }
            if (!appendBounded(builder, budget, String.valueOf(entry.getKey()), 4)
                    || !appendAscii(builder, budget, "=", 4)
                    || !appendDetailValue(builder, budget, entry.getValue(), depth + 1)) {
                return false;
            }
            count++;
        }
        return appendAscii(builder, budget, "}", 0);
    }

    private static boolean appendBounded(
            final StringBuilder builder,
            final RenderBudget budget,
            final String value,
            final int reserveBytes) {
        return appendDisplayBounded(builder, budget, value, "-", reserveBytes);
    }

    private static boolean appendDisplayBounded(
            final StringBuilder builder,
            final RenderBudget budget,
            final String value,
            final String fallback,
            final int reserveBytes) {
        if (value == null || value.isEmpty()) {
            return appendAscii(builder, budget, fallback, reserveBytes);
        }
        boolean started = false;
        for (int index = 0; index < value.length(); index = nextCodePointIndex(value, index)) {
            int codePoint = sanitizedCodePointAt(value, index);
            if (!started && Character.isWhitespace(codePoint)) {
                continue;
            }
            started = true;
            if (!appendCodePointBounded(builder, budget, codePoint,
                    reserveBytes + asciiBytes(HUMAN_TRUNCATION_MARKER))) {
                appendAscii(builder, budget, HUMAN_TRUNCATION_MARKER, reserveBytes);
                budget.markPartial();
                return false;
            }
        }
        if (!started) {
            return appendAscii(builder, budget, fallback, reserveBytes);
        }
        return true;
    }

    private static boolean appendCodePointBounded(
            final StringBuilder builder,
            final RenderBudget budget,
            final int codePoint,
            final int reserveBytes) {
        int bytes = utf8Bytes(codePoint);
        if (!budget.tryConsume(bytes, reserveBytes)) {
            budget.markPartial();
            return false;
        }
        appendCodePoint(builder, codePoint);
        return true;
    }

    private static void writePartialNotice(final PrintStream output, final boolean partial) {
        if (partial) {
            output.print(PARTIAL_NOTICE);
        }
    }

    private static Map<String, Object> headerMap(final List<Column> columns) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (Column column : columns) {
            headers.put(column.key(), column.header());
        }
        return headers;
    }

    private static List<Column> fitColumns(final List<Column> columns, final int width) {
        List<Column> accepted = new ArrayList<>();
        int used = 0;
        for (Column column : columns) {
            int next = used == 0 ? column.width() : used + 1 + column.width();
            if (next <= width || accepted.isEmpty()) {
                accepted.add(column);
                used = next;
            }
        }
        return List.copyOf(accepted);
    }

    private static boolean appendRow(
            final StringBuilder builder,
            final RenderBudget budget,
            final List<Column> columns,
            final Map<String, Object> row) {
        StringBuilder rowBuilder = new StringBuilder(Math.max(32, columns.size() * 16));
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                rowBuilder.append(' ');
            }
            Column column = columns.get(index);
            appendCell(rowBuilder, row.getOrDefault(column.key(), "-"), column.width());
        }
        rowBuilder.append('\n');
        String rowText = rowBuilder.toString();
        int bytes = asciiBytes(rowText);
        if (!budget.tryConsume(bytes, 0)) {
            budget.markPartial();
            return false;
        }
        builder.append(rowText);
        return true;
    }

    private static void appendCell(final StringBuilder builder, final Object value, final int width) {
        int start = builder.length();
        boolean truncated = appendCellValue(builder, value, width);
        if (truncated) {
            if (width <= HUMAN_TRUNCATION_MARKER.length()) {
                builder.setLength(start);
                for (int index = 0; index < width; index++) {
                    builder.append('.');
                }
            } else {
                int end = builder.offsetByCodePoints(start, width - HUMAN_TRUNCATION_MARKER.length());
                builder.setLength(end);
                builder.append(HUMAN_TRUNCATION_MARKER);
            }
        }
        int codePoints = builder.codePointCount(start, builder.length());
        for (int index = codePoints; index < width; index++) {
            builder.append(' ');
        }
    }

    private static boolean appendCellValue(final StringBuilder builder, final Object value, final int width) {
        if (value instanceof List<?> list) {
            return appendCellListValue(builder, list, width);
        }
        if (value instanceof Map<?, ?> map) {
            return appendCellMapValue(builder, map, width);
        }
        return appendCellText(builder, value == null ? null : String.valueOf(value), width);
    }

    private static boolean appendCellListValue(final StringBuilder builder, final List<?> values, final int width) {
        if (values == null || values.isEmpty()) {
            return appendCellText(builder, null, width);
        }
        int start = builder.length();
        boolean first = true;
        for (Object value : values) {
            int used = builder.codePointCount(start, builder.length());
            if (!first) {
                if (used >= width) {
                    return true;
                }
                builder.append(',');
                used++;
            }
            boolean truncated = appendCellText(builder, value == null ? null : String.valueOf(value), width - used);
            if (truncated) {
                return true;
            }
            first = false;
        }
        return false;
    }

    private static boolean appendCellMapValue(final StringBuilder builder, final Map<?, ?> values, final int width) {
        if (values == null || values.isEmpty()) {
            return appendCellText(builder, null, width);
        }
        List<MapCellEntry> selected = new ArrayList<>(MAX_TABLE_MAP_RENDERED_ENTRIES);
        int inspected = 0;
        boolean omitted = false;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (inspected >= MAX_TABLE_MAP_INSPECTED_ENTRIES) {
                omitted = true;
                break;
            }
            inspected++;
            MapCellEntry candidate = new MapCellEntry(entry.getKey(), entry.getValue());
            int insertionIndex = mapCellInsertionIndex(selected, candidate.key());
            if (selected.size() < MAX_TABLE_MAP_RENDERED_ENTRIES) {
                selected.add(insertionIndex, candidate);
            } else if (insertionIndex < MAX_TABLE_MAP_RENDERED_ENTRIES) {
                selected.add(insertionIndex, candidate);
                selected.remove(MAX_TABLE_MAP_RENDERED_ENTRIES);
                omitted = true;
            } else {
                omitted = true;
            }
        }
        int start = builder.length();
        boolean first = true;
        for (MapCellEntry entry : selected) {
            int used = builder.codePointCount(start, builder.length());
            if (!first) {
                if (used >= width) {
                    return true;
                }
                builder.append(',');
                used++;
            }
            boolean keyTruncated = appendCellText(builder,
                    entry.key() == null ? null : String.valueOf(entry.key()), width - used);
            if (keyTruncated) {
                return true;
            }
            used = builder.codePointCount(start, builder.length());
            if (used >= width) {
                return true;
            }
            builder.append('=');
            used++;
            boolean valueTruncated = appendCellText(builder,
                    entry.value() == null ? null : String.valueOf(entry.value()), width - used);
            if (valueTruncated) {
                return true;
            }
            first = false;
        }
        return omitted;
    }

    private static int mapCellInsertionIndex(final List<MapCellEntry> selected, final Object key) {
        int index = 0;
        while (index < selected.size() && compareMapCellKeys(selected.get(index).key(), key) <= 0) {
            index++;
        }
        return index;
    }

    private static int compareMapCellKeys(final Object left, final Object right) {
        String leftText = String.valueOf(left);
        String rightText = String.valueOf(right);
        int leftLength = leftText.length();
        int rightLength = rightText.length();
        int limit = Math.min(Math.min(leftLength, rightLength), MAX_TABLE_MAP_KEY_COMPARE_CHARS);
        for (int index = 0; index < limit; index++) {
            int difference = leftText.charAt(index) - rightText.charAt(index);
            if (difference != 0) {
                return difference;
            }
        }
        if (leftLength == rightLength || limit == MAX_TABLE_MAP_KEY_COMPARE_CHARS) {
            return 0;
        }
        return Integer.compare(leftLength, rightLength);
    }

    private static boolean appendCellText(final StringBuilder builder, final String value, final int width) {
        if (width <= 0) {
            return value != null && !value.isEmpty();
        }
        if (value == null || value.isEmpty()) {
            builder.append('-');
            return false;
        }
        int appended = 0;
        boolean started = false;
        for (int index = 0; index < value.length(); index = nextCodePointIndex(value, index)) {
            int codePoint = sanitizedCodePointAt(value, index);
            if (!started && Character.isWhitespace(codePoint)) {
                continue;
            }
            started = true;
            if (appended >= width) {
                return true;
            }
            appendCodePoint(builder, codePoint);
            appended++;
        }
        if (!started) {
            builder.append('-');
        }
        return false;
    }

    private static String requiredDetailId(final CommandLine commandLine, final String command) {
        List<String> arguments = commandLine.arguments();
        if (arguments.size() != 1) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", command + " requires exactly one id");
        }
        String id = display(arguments.get(0), "");
        if (id.isBlank() || id.length() > 256) {
            throw new NuggetCliFailure(EXIT_USAGE, "INVALID_ARGUMENT", "id is outside allowed bounds");
        }
        return id;
    }

    private static String display(final String value, final String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    builder.append(character).append(value.charAt(++index));
                } else {
                    builder.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(character)) {
                builder.append('\uFFFD');
            } else {
                builder.append(Character.isISOControl(character) ? ' ' : character);
            }
        }
        String result = builder.toString().trim();
        return result.isBlank() ? fallback : result;
    }

    private static int sanitizedCodePointAt(final String value, final int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)) {
            if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                return Character.toCodePoint(character, value.charAt(index + 1));
            }
            return '\uFFFD';
        }
        if (Character.isLowSurrogate(character)) {
            return '\uFFFD';
        }
        return Character.isISOControl(character) ? ' ' : character;
    }

    private static int nextCodePointIndex(final String value, final int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)
                && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1))) {
            return index + 2;
        }
        return index + 1;
    }

    private static void appendCodePoint(final StringBuilder builder, final int codePoint) {
        if (Character.isBmpCodePoint(codePoint)) {
            builder.append((char) codePoint);
        } else {
            builder.append(Character.highSurrogate(codePoint));
            builder.append(Character.lowSurrogate(codePoint));
        }
    }

    private static String bytes(final long value) {
        if (value < 0L) {
            return "-";
        }
        return Long.toString(value);
    }

    private static String percent(final double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            return "-";
        }
        long tenths = Math.round(value * 10.0D);
        return Long.toString(tenths / 10L) + "." + Long.toString(Math.abs(tenths % 10L));
    }

    private static String authenticateFromEnvironment(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final String envName) {
        return authenticateFromEnvironment(session, record, envName, null);
    }

    private static String authenticateFromEnvironment(
            final NuggetSession session,
            final NuggetDiscovery.Record record,
            final String envName,
            final CommandDeadlineGuard guard) {
        char[] pin = session.pinFromEnvironment(envName);
        if (pin == null || pin.length == 0) {
            return "";
        }
        try {
            if (validatedPinUtf8Bytes(pin) < 0) {
                return "";
            }
            NuggetCliConnection connection = session.connect(record);
            requireActive(guard, session, record);
            requireCredentialTransport(connection);
            NuggetAuthenticationRequest request = NuggetAuthenticationRequest.of(
                    "cli-auth",
                    authSource(connection),
                    record.endpoint().toString(),
                    connection.security().locality() == NuggetCliConnectionSecurity.Locality.TRUSTED_LOCAL,
                    session.now());
            NuggetAuthenticationResult result = connection.authenticate(request, pin);
            requireActive(guard, session, record);
            if (!result.accepted()) {
                return "";
            }
            requireActive(guard, session, record);
            session.admitIfActive(record, result.admissionId(), guard);
            return result.admissionId();
        } finally {
            clear(pin);
        }
    }

    private static void requireActive(
            final CommandDeadlineGuard guard,
            final NuggetSession session,
            final NuggetDiscovery.Record record) {
        if (guard != null) {
            guard.requireActive(session, record);
        }
    }

    private static void writeTarget(
            final PrintStream output,
            final NuggetSession session,
            final boolean json,
            final String command) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("target", session.targetName());
        values.put("count", Integer.toString(session.targetRecords().size()));
        if (json) {
            jsonOk(output, command, values);
            return;
        }
        output.println("target " + session.targetName());
    }

    private static void outputMessage(
            final PrintStream output,
            final boolean json,
            final String command,
            final String status,
            final String target) {
        if (json) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", status);
            values.put("target", target);
            jsonOk(output, command, values);
            return;
        }
        output.println(status + " " + target);
    }

    private static void requireSelectable(final NuggetDiscovery.Record record) {
        if (record.status() != NuggetDiscovery.Status.ACTIVE
                || record.compatibility() != NuggetDiscovery.Compatibility.COMPATIBLE) {
            throw new NuggetCliFailure(EXIT_USAGE, "NOT_SELECTABLE", "target is not active and compatible");
        }
    }

    private static void requireCredentialTransport(final NuggetCliConnection connection) {
        NuggetCliConnectionSecurity security = connection.security();
        if (security == null || !security.permitsCredentialSubmission()) {
            throw new NuggetCliFailure(EXIT_DENIED, "INSECURE_TRANSPORT", "remote PIN transport is not accepted");
        }
    }

    private static NuggetAuthenticationSource authSource(final NuggetCliConnection connection) {
        return connection.security().locality() == NuggetCliConnectionSecurity.Locality.TRUSTED_LOCAL
                ? NuggetAuthenticationSource.LOCAL
                : NuggetAuthenticationSource.REMOTE;
    }

    private static Map<String, Object> discoveryMap(final NuggetDiscovery.Result result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("returned", Integer.toString(result.returned()));
        values.put("totalAvailable", Integer.toString(result.totalAvailable()));
        values.put("partial", Boolean.toString(result.partial()));
        List<Map<String, Object>> records = new ArrayList<>();
        for (NuggetDiscovery.Record record : orderedRecords(result.records())) {
            records.add(recordMap(record));
        }
        values.put("records", records);
        return values;
    }

    private static Map<String, Object> recordMap(final NuggetDiscovery.Record record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", displayName(record));
        values.put("id", record.nuggetId());
        values.put("status", record.status().name());
        values.put("source", record.source().name());
        values.put("protocol", protocol(record));
        values.put("compatibility", record.compatibility().name());
        values.put("lastSeenAt", record.lastSeenAt().toString());
        values.put("endpoint", endpointMap(record.endpoint()));
        return values;
    }

    private static Map<String, Object> endpointMap(final NuggetDiscovery.EndpointDescriptor endpoint) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("transport", safe(endpoint.transport()));
        values.put("scheme", safe(endpoint.scheme()));
        if (!endpoint.host().isBlank()) {
            values.put("host", safe(endpoint.host()));
        }
        if (endpoint.port() >= 0) {
            values.put("port", Integer.toString(endpoint.port()));
        }
        values.put("local", Boolean.toString(endpoint.host().isBlank() && endpoint.port() < 0));
        return values;
    }

    private static Map<String, Object> statusMap(final NuggetDiscovery.Record record, final NuggetStatus status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", status.identity().nuggetName());
        values.put("id", status.identity().nuggetId());
        values.put("runtimeId", status.identity().runtimeIdentity().runtimeId());
        values.put("state", status.identity().state().name());
        values.put("admin", status.configuration().adminState().name());
        values.put("source", record.source().name());
        return values;
    }

    private static Map<String, Object> overviewMap(
            final NuggetDiscovery.Record record,
            final NuggetIntrospection.Result<NuggetIntrospection.Overview> result) {
        if (!result.admitted() || !result.authorized() || result.value() == null) {
            return targetErrorMap(record, result.admitted() ? "NOT_AUTHORIZED" : "NOT_AUTHENTICATED");
        }
        Map<String, Object> values = statusMap(record, result.value().status());
        values.put("health", result.value().health() == null ? "UNKNOWN" : result.value().health().state().name());
        values.put("readiness", result.value().readiness() == null ? "UNKNOWN" : result.value().readiness().state().name());
        values.put("partial", Boolean.toString(result.partial() || result.value().partial()));
        return values;
    }

    private static Map<String, Object> targetErrorMap(final NuggetDiscovery.Record record, final String code) {
        return targetErrorMap(displayName(record), record.nuggetId(), code);
    }

    private static Map<String, Object> targetErrorMap(final String name, final String code) {
        return targetErrorMap(name, "", code);
    }

    private static Map<String, Object> targetErrorMap(final String name, final String id, final String code) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("id", id);
        values.put("state", code);
        return values;
    }

    private static List<NuggetDiscovery.Record> orderedRecords(final List<NuggetDiscovery.Record> records) {
        return records.stream()
                .sorted(Comparator.comparing(NuggetDiscovery.Record::nuggetName)
                        .thenComparing(NuggetDiscovery.Record::nuggetId)
                        .thenComparing(record -> endpointSortKey(record.endpoint())))
                .toList();
    }

    private static String endpointSortKey(final NuggetDiscovery.EndpointDescriptor endpoint) {
        return endpoint.transport() + "|" + endpoint.scheme() + "|" + endpoint.host() + "|" + endpoint.port();
    }

    private static String protocol(final NuggetDiscovery.Record record) {
        return record.protocolMinimum() + "-" + record.protocolMaximum() + "/" + record.compatibility().name();
    }

    private static String displayName(final NuggetDiscovery.Record record) {
        return record.nuggetName().isBlank() ? shortId(record.nuggetId()) : record.nuggetName();
    }

    private static String shortId(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String safeReason(final NuggetDiscovery.Resolution resolution) {
        if (!resolution.safeReason().isBlank()) {
            return resolution.safeReason();
        }
        return resolution.status().name().toLowerCase(Locale.ROOT);
    }

    private static List<String> tokenize(final String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        if (line.length() > MAX_COMMAND_LENGTH) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "command is too long");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                addToken(tokens, current);
            } else {
                current.append(c);
            }
        }
        if (escaping || quote != 0) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "malformed quoting");
        }
        addToken(tokens, current);
        if (tokens.size() > MAX_TOKENS) {
            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "too many command tokens");
        }
        return List.copyOf(tokens);
    }

    private static void addToken(final List<String> tokens, final StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static void jsonOk(final PrintStream output, final String command, final Map<String, Object> data) {
        output.println("{\"version\":1,\"ok\":true,\"command\":\"" + json(command) + "\",\"data\":"
                + jsonValue(data) + "}");
    }

    private static void jsonOkBounded(
            final PrintStream output,
            final String command,
            final Map<String, Object> data,
            final boolean partial) {
        String prefix = "{\"version\":1,\"ok\":true,\"command\":\"" + json(command) + "\",\"data\":";
        String suffix = "}";
        int dataBudget = Math.max(2, MAX_OUTPUT_BYTES - asciiBytes(prefix) - asciiBytes(suffix) - 1);
        Map<String, Object> values = boundedEnvelopeData(data, partial, false);
        EncodedJson encoded = boundedJsonValue(values, dataBudget);
        if (encoded.partial()) {
            values = boundedEnvelopeData(data, true, true);
            encoded = boundedJsonValue(values, dataBudget);
        }
        output.println(prefix + encoded.text() + suffix);
    }

    private static Map<String, Object> boundedEnvelopeData(
            final Map<String, Object> data,
            final boolean partial,
            final boolean truncated) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("partial", Boolean.toString(partial));
        values.put("truncated", Boolean.toString(truncated));
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!"partial".equals(key) && !"truncated".equals(key)) {
                    values.put(key, entry.getValue());
                }
            }
        }
        return values;
    }

    private static void jsonError(final PrintStream output, final String command, final String code, final String message) {
        output.println("{\"version\":1,\"ok\":false,\"command\":\"" + json(command)
                + "\",\"error\":{\"code\":\"" + json(code) + "\",\"message\":\"" + json(safe(message)) + "\"}}");
    }

    private static String jsonValue(final Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(json(String.valueOf(entry.getKey()))).append("\":")
                        .append(jsonValue(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(jsonValue(item));
            }
            return builder.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + json(String.valueOf(value)) + "\"";
    }

    private static String json(final String value) {
        String text = safe(value);
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\\') {
                builder.append("\\\\");
            } else if (character == '"') {
                builder.append("\\\"");
            } else if (character < ' ') {
                builder.append("\\u");
                String hex = Integer.toHexString(character);
                for (int padding = hex.length(); padding < 4; padding++) {
                    builder.append('0');
                }
                builder.append(hex);
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static EncodedJson boundedJsonValue(final Object value, final int maxBytes) {
        return boundedJsonValue(value, maxBytes, 0);
    }

    private static EncodedJson boundedJsonValue(final Object value, final int maxBytes, final int depth) {
        StringBuilder builder = new StringBuilder(Math.min(maxBytes, 1024));
        RenderBudget budget = new RenderBudget(maxBytes);
        appendBoundedJsonValue(builder, budget, value, depth);
        return new EncodedJson(builder.toString(), budget.partial());
    }

    private static boolean appendBoundedJsonValue(
            final StringBuilder builder,
            final RenderBudget budget,
            final Object value,
            final int depth) {
        if (depth >= MAX_DETAIL_DEPTH) {
            budget.markPartial();
            return appendJsonString(builder, budget, "<truncated>", 0);
        }
        if (value instanceof Map<?, ?> map) {
            return appendBoundedJsonMap(builder, budget, map, depth);
        }
        if (value instanceof List<?> list) {
            return appendBoundedJsonList(builder, budget, list, depth);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return appendAscii(builder, budget, String.valueOf(value), 0);
        }
        return appendJsonString(builder, budget, String.valueOf(value), 0);
    }

    private static boolean appendBoundedJsonMap(
            final StringBuilder builder,
            final RenderBudget budget,
            final Map<?, ?> map,
            final int depth) {
        if (!appendAscii(builder, budget, "{", 1)) {
            return false;
        }
        int count = 0;
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (budget.remaining() < 8) {
                budget.markPartial();
                break;
            }
            if (count >= MAX_JSON_MAP_ENTRIES) {
                budget.markPartial();
                break;
            }
            EncodedJson key = boundedJsonString(String.valueOf(entry.getKey()), Math.max(2,
                    Math.min(MAX_JSON_STRING_CHARS, budget.remaining() - 4)));
            EncodedJson encodedValue = boundedJsonValue(entry.getValue(), Math.max(2, budget.remaining() - 4), depth + 1);
            int needed = (first ? 0 : 1) + key.bytes() + 1 + encodedValue.bytes();
            if (!budget.tryConsume(needed, 1)) {
                budget.markPartial();
                break;
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(key.text()).append(':').append(encodedValue.text());
            if (key.partial() || encodedValue.partial()) {
                budget.markPartial();
            }
            count++;
        }
        return appendAscii(builder, budget, "}", 0);
    }

    private static boolean appendBoundedJsonList(
            final StringBuilder builder,
            final RenderBudget budget,
            final List<?> list,
            final int depth) {
        if (!appendAscii(builder, budget, "[", 1)) {
            return false;
        }
        int count = 0;
        boolean first = true;
        for (Object item : list) {
            if (budget.remaining() < 4) {
                budget.markPartial();
                break;
            }
            if (count >= MAX_JSON_ITEMS) {
                budget.markPartial();
                break;
            }
            EncodedJson encoded = boundedJsonValue(item, Math.max(2, budget.remaining() - 2), depth + 1);
            int needed = (first ? 0 : 1) + encoded.bytes();
            if (!budget.tryConsume(needed, 1)) {
                budget.markPartial();
                break;
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(encoded.text());
            if (encoded.partial()) {
                budget.markPartial();
            }
            count++;
        }
        return appendAscii(builder, budget, "]", 0);
    }

    private static EncodedJson boundedJsonString(final String value, final int maxBytes) {
        StringBuilder builder = new StringBuilder(Math.min(maxBytes, 128));
        RenderBudget budget = new RenderBudget(maxBytes);
        appendJsonString(builder, budget, value, 0);
        return new EncodedJson(builder.toString(), budget.partial());
    }

    private static boolean appendJsonString(
            final StringBuilder builder,
            final RenderBudget budget,
            final String value,
            final int reserveBytes) {
        if (!appendAscii(builder, budget, "\"", reserveBytes + 1)) {
            return false;
        }
        String text = value == null ? "" : value;
        int appended = 0;
        for (int index = 0; index < text.length(); index++) {
            if (appended >= MAX_JSON_STRING_CHARS) {
                budget.markPartial();
                break;
            }
            char character = text.charAt(index);
            String escaped = jsonEscaped(character);
            if (!appendAscii(builder, budget, escaped, reserveBytes + 1)) {
                budget.markPartial();
                break;
            }
            appended++;
        }
        return appendAscii(builder, budget, "\"", reserveBytes);
    }

    private static String jsonEscaped(final char character) {
        if (character == '\\') {
            return "\\\\";
        }
        if (character == '"') {
            return "\\\"";
        }
        if (character < ' ' || character > '~') {
            String hex = Integer.toHexString(character);
            StringBuilder builder = new StringBuilder(6);
            builder.append("\\u");
            for (int padding = hex.length(); padding < 4; padding++) {
                builder.append('0');
            }
            return builder.append(hex).toString();
        }
        return Character.toString(character);
    }

    private static boolean appendAscii(
            final StringBuilder builder,
            final RenderBudget budget,
            final String value,
            final int reserveBytes) {
        int bytes = asciiBytes(value);
        if (!budget.tryConsume(bytes, reserveBytes)) {
            budget.markPartial();
            return false;
        }
        builder.append(value);
        return true;
    }

    private static int asciiBytes(final String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7F) {
                bytes++;
            } else if (character <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static int utf8Bytes(final int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static String safeLifecycleOutcomeText(final NuggetApplicationLifecycle.Result result) {
        String text = safeLifecycleText(result.outcomeText(), "");
        if (!text.isEmpty()) {
            return text;
        }
        return switch (result.outcome()) {
            case COMPLETED -> "completed";
            case DRY_RUN -> "dry-run";
            case ACCEPTED -> "accepted";
            case ALREADY_DONE -> "already-done";
            case REJECTED -> "rejected";
            case UNAVAILABLE -> "unavailable";
            case DEADLINE_EXCEEDED -> "deadline-exceeded";
            case FAILED -> "failed";
            case UNKNOWN -> "unknown";
        };
    }

    private static List<String> safeLifecycleList(final List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> retained = new ArrayList<>(MAX_LIFECYCLE_TEXT_ITEMS);
        boolean omitted = false;
        int examined = 0;
        for (String value : values) {
            if (examined >= MAX_LIFECYCLE_TEXT_ITEMS) {
                omitted = true;
                break;
            }
            examined++;
            String safe = safeLifecycleText(value, "");
            if (safe.isEmpty()) {
                omitted = true;
                continue;
            }
            retained.add(safe);
        }
        if (omitted && retained.size() < MAX_LIFECYCLE_TEXT_ITEMS) {
            retained.add("detailsOmitted");
        }
        return retained.isEmpty() ? List.of() : List.copyOf(retained);
    }

    private static String safeLifecycleText(final String value, final String fallback) {
        String token = boundedLifecycleText(value);
        if (token.isEmpty() || sensitiveLifecycleValue(value, token)) {
            return fallback;
        }
        String lower = lowerAscii(token);
        return switch (lower) {
            case "allowed",
                    "authorization",
                    "authorization-denied",
                    "cancelled",
                    "checksum",
                    "cleanup",
                    "compatibility",
                    "completed",
                    "deadline-exceeded",
                    "denied",
                    "deployer-result-failed",
                    "dry-run",
                    "failed",
                    "feature-gate",
                    "invalid-lifecycle-state",
                    "invalid-request",
                    "isolation",
                    "lifecycle",
                    "lifecycle-failed",
                    "not-found",
                    "permission-or-verification-denied",
                    "plan",
                    "plan-rejected",
                    "policy",
                    "provider",
                    "provider-unavailable",
                    "read-only",
                    "repository",
                    "resource",
                    "safe-result",
                    "security",
                    "signature",
                    "start-disabled",
                    "strict",
                    "unavailable",
                    "verification",
                    "verification-failed",
                    "verify" -> lower;
            case "application activation plan created" -> "activation-plan-created";
            case "application start" -> "application-start";
            case "application drain" -> "application-drain";
            case "application stop" -> "application-stop";
            case "application unload" -> "application-unload";
            case "verify bundle digest" -> "verify";
            case "create isolated runtime" -> "isolation";
            case "start application" -> "start";
            case "true", "false" -> lower;
            case "start", "drain", "stop", "unload" -> upperAscii(lower);
            case "packaged",
                    "verified",
                    "deployed",
                    "planned",
                    "loading",
                    "loaded",
                    "starting",
                    "running",
                    "draining",
                    "stopping",
                    "stopped",
                    "unloading",
                    "unloaded",
                    "rejected" -> upperAscii(lower);
            default -> fallback;
        };
    }

    private static String boundedLifecycleText(final String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(MAX_LIFECYCLE_TEXT_UTF8_BYTES);
        int bytes = 0;
        boolean previousSeparator = false;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!safeLifecycleCodePoint(codePoint)) {
                return "";
            }
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > MAX_LIFECYCLE_TEXT_UTF8_BYTES) {
                return "";
            }
            if (codePoint == ' ') {
                if (!builder.isEmpty() && !previousSeparator) {
                    builder.append(' ');
                    previousSeparator = true;
                }
            } else {
                builder.appendCodePoint(codePoint);
                previousSeparator = false;
            }
            bytes += nextBytes;
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == ' ') {
            builder.setLength(length - 1);
        }
        return builder.isEmpty() ? "" : builder.toString();
    }

    private static boolean safeLifecycleCodePoint(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '-'
                || codePoint == ':'
                || codePoint == ' ';
    }

    private static boolean sensitiveLifecycleValue(final String rawValue, final String token) {
        String normalized = normalizedLifecycleSensitivity(rawValue);
        return normalized.isEmpty()
                || normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("cookie")
                || normalized.contains("session")
                || normalized.contains("privatekey")
                || normalized.contains("beginprivatekey")
                || normalized.contains("begincertificate")
                || normalized.contains("stacktrace")
                || normalized.contains("exception")
                || normalized.contains("throwable")
                || normalized.contains("payload")
                || normalized.contains("configuration")
                || normalized.contains("manifest")
                || normalized.contains("repositorypath")
                || normalized.contains("classloader")
                || normalized.contains("modulelayer")
                || token.contains("..")
                || token.contains("://")
                || token.contains("\\\\")
                || windowsDrive(token);
    }

    private static String normalizedLifecycleSensitivity(final String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(MAX_LIFECYCLE_TEXT_UTF8_BYTES);
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)
                    || (Character.isHighSurrogate(current)
                    && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                return "";
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            int nextBytes = utf8Bytes(codePoint);
            if (bytes + nextBytes > MAX_LIFECYCLE_TEXT_UTF8_BYTES) {
                return "";
            }
            bytes += nextBytes;
            if (codePoint >= 'A' && codePoint <= 'Z') {
                builder.append((char) (codePoint + ('a' - 'A')));
            } else if ((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9')) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }

    private static boolean windowsDrive(final String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }

    private static String lowerAscii(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= 'A' && value <= 'Z') {
                builder.append((char) (value + ('a' - 'A')));
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static String upperAscii(final String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= 'a' && value <= 'z') {
                builder.append((char) (value - ('a' - 'A')));
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static String safe(final String value) {
        if (value == null || value.isBlank()) {
            return "operation failed";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            builder.append(Character.isISOControl(character) ? ' ' : character);
        }
        String result = builder.toString().trim();
        return result.isBlank() ? "operation failed" : result;
    }

    private static void clear(final char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static long deadline(final long nowNanos, final Duration ttl) {
        long delta = ttl.toNanos();
        long deadline = nowNanos + delta;
        if (((nowNanos ^ deadline) & (delta ^ deadline)) < 0L) {
            return Long.MAX_VALUE;
        }
        return deadline;
    }

    private static ThreadFactory commandThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "j7m-cli-command-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static ExecutorService commandExecutor() {
        return new ThreadPoolExecutor(
                COMMAND_WORKERS,
                COMMAND_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(COMMAND_QUEUE_CAPACITY),
                commandThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static int validatedPinUtf8Bytes(final char[] pin) {
        if (pin == null || pin.length == 0) {
            return -1;
        }
        return validatedPinUtf8Bytes(new CharArraySequence(pin));
    }

    private static int validatedPinUtf8Bytes(final CharSequence value) {
        if (value == null || value.length() == 0) {
            return -1;
        }
        int bytes = 0;
        boolean nonBlank = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                nonBlank = true;
            }
            int codePoint;
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return -1;
                }
                codePoint = Character.toCodePoint(character, value.charAt(++index));
            } else if (Character.isLowSurrogate(character)) {
                return -1;
            } else {
                codePoint = character;
            }
            if (codePoint <= 0x7F) {
                bytes++;
            } else if (codePoint <= 0x7FF) {
                bytes += 2;
            } else if (codePoint <= 0xFFFF) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            if (bytes > MAX_PIN_BYTES) {
                return -1;
            }
        }
        return nonBlank ? bytes : -1;
    }

    private record CommandLine(String command, List<String> arguments, Map<String, String> options, boolean json) {
        private static CommandLine parse(final List<String> tokens) {
            if (tokens == null || tokens.isEmpty()) {
                throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "missing nugget command");
            }
            String command = tokens.get(0);
            if (command.startsWith("--")) {
                throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "missing nugget command");
            }
            boolean json = false;
            List<String> positional = new ArrayList<>();
            Map<String, String> options = new LinkedHashMap<>();
            int index = 1;
            while (index < tokens.size()) {
                String token = tokens.get(index);
                if ("--json".equals(token)) {
                    rejectDuplicate(options, token);
                    json = true;
                    options.put(token, "true");
                } else if (token.startsWith("--")) {
                    rejectDuplicate(options, token);
                    if (booleanOption(command, token)) {
                        options.put(token, "true");
                    } else if (valueOption(command, token)) {
                        if (index + 1 >= tokens.size() || tokens.get(index + 1).startsWith("--")) {
                            throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "missing value for option");
                        }
                        index++;
                        options.put(token, tokens.get(index));
                    } else {
                        throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "unsupported option for command");
                    }
                } else {
                    positional.add(token);
                }
                index++;
            }
            if (options.containsKey("--asc") && options.containsKey("--desc")) {
                throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "--asc and --desc cannot be combined");
            }
            return new CommandLine(command, List.copyOf(positional),
                    Map.copyOf(options), json);
        }

        private static void rejectDuplicate(final Map<String, String> options, final String token) {
            if (options.containsKey(token)) {
                throw new NuggetCliFailure(EXIT_USAGE, "USAGE", "duplicate option");
            }
        }

        private static boolean booleanOption(final String command, final String token) {
            Set<String> options = switch (command) {
                case "help", "discover", "nuggets", "target", "whoami", "reconnect",
                        "auth", "info", "status", "object", "app", "env", "health",
                        "readiness", "metrics", "errors", "disk" -> Set.of("--json");
                case "talk" -> Set.of("--json");
                case "pin" -> Set.of("--json", "--all");
                case "ps", "ls", "objects", "apps" -> Set.of("--json", "--wide", "--asc", "--desc");
                case "start" -> Set.of("--json", "--dry-run", "--plan");
                case "stop", "drain", "unload" -> Set.of("--json");
                case "drain-nugget", "shutdown-nugget", "kill-nugget" -> Set.of("--json");
                default -> Set.of();
            };
            if (options.contains(token)) {
                return true;
            }
            return queryBooleanOption(command, token);
        }

        private static boolean queryBooleanOption(final String command, final String token) {
            if (!("--asc".equals(token) || "--desc".equals(token))) {
                return false;
            }
            return switch (command) {
                case "disk", "errors" -> true;
                default -> false;
            };
        }

        private static boolean valueOption(final String command, final String token) {
            Set<String> options = switch (command) {
                case "talk" -> Set.of("--id");
                case "auth", "info", "status", "env", "health", "readiness", "metrics",
                        "ps", "ls", "objects", "apps", "object", "app", "disk", "errors" ->
                        Set.of("--target", "--pin-env");
                case "start", "stop", "drain", "unload" -> Set.of("--target", "--id", "--pin-env", "--width");
                case "drain-nugget" -> Set.of("--target", "--id", "--pin-env", "--width");
                case "shutdown-nugget", "kill-nugget" -> Set.of("--target", "--id", "--pin-env", "--width", "--confirm");
                default -> Set.of();
            };
            if (options.contains(token)) {
                return true;
            }
            return queryValueOption(command, token);
        }

        private static boolean queryValueOption(final String command, final String token) {
            Set<String> options = switch (command) {
                case "ps", "ls", "objects", "apps" -> Set.of("--filter", "--sort", "--limit", "--offset", "--width");
                case "env", "health", "readiness", "metrics" -> Set.of("--width");
                case "disk" -> Set.of("--filter", "--sort", "--limit", "--offset", "--width", "--role");
                case "errors" -> Set.of("--sort", "--limit", "--offset", "--width", "--component");
                default -> Set.of();
            };
            return options.contains(token);
        }

        private String option(final String name) {
            return this.options.getOrDefault(name, "");
        }

        private boolean wide() {
            return this.options.containsKey("--wide");
        }
    }

    private record InspectionBatch(List<Map<String, Object>> targets, int exitCode, boolean partial) {
    }

    private record QueryPlan(NuggetIntrospection.Query query, NuggetAdminQuery contractQuery) {
    }

    private record PsQueryPlan(
            int offset,
            int limit,
            String sortBy,
            NuggetIntrospection.SortDirection direction,
            Map<String, String> filters,
            NuggetAdminQuery contractQuery) {
    }

    private record PsRows(List<Map<String, Object>> rows, int totalAvailable, boolean partial) {
    }

    private record SourceAppendResult(boolean ok, boolean partial) {
    }

    private record RowsResult(List<Map<String, Object>> rows, boolean partial) {
    }

    private record RenderResult(boolean partial) {
    }

    private record EncodedJson(String text, boolean partial) {
        private int bytes() {
            return asciiBytes(this.text);
        }
    }

    private static final class RenderBudget {
        private int remaining;
        private boolean partial;

        private RenderBudget(final int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private boolean tryConsume(final int bytes, final int reserveBytes) {
            if (bytes < 0 || reserveBytes < 0 || bytes > this.remaining - reserveBytes) {
                return false;
            }
            this.remaining -= bytes;
            return true;
        }

        private int remaining() {
            return this.remaining;
        }

        private void markPartial() {
            this.partial = true;
        }

        private boolean partial() {
            return this.partial;
        }
    }

    private record Column(String header, String key, int width) {
    }

    private record MapCellEntry(Object key, Object value) {
    }

    private record TargetSelection(List<NuggetDiscovery.Record> records, boolean allTarget) {
    }

    private record ConnectionAttempt(String nuggetId, long generation) {
    }

    @FunctionalInterface
    private interface TargetCall {
        Map<String, Object> call(NuggetDiscovery.Record record);
    }

    private enum QueryDomain {
        APPLICATIONS("id", Set.of("id", "state", "coordinate"), Set.of("applicationId", "state", "coordinate")),
        OBJECTS("id", Set.of("id", "serviceName", "visibility"), Set.of(
                "nuggetId", "serviceName", "objectKind", "visibility", "lifecycleState", "health",
                "interfaceName", "registryContext")),
        PS("id", Set.of("id", "kind", "state", "applicationId", "instanceId", "coordinate", "serviceName",
                "visibility", "health", "objectKind", "lifecycleState", "interfaceName", "registryContext",
                "transportId", "nuggetId"), Set.of("kind", "applicationId", "instanceId", "coordinate", "state",
                "nuggetId", "serviceName", "objectKind", "visibility", "lifecycleState", "health",
                "interfaceName", "registryContext", "transportId")),
        DISK("rootId", Set.of("id", "rootId", "role"), Set.of("rootId", "role")),
        ERRORS("timestamp", Set.of("timestamp", "id"), Set.of()),
        TRANSPORTS("transportId", Set.of("transportId"), Set.of("transportId"));

        private final String defaultSort;
        private final Set<String> sorts;
        private final Set<String> filters;

        QueryDomain(final String defaultSort, final Set<String> sorts, final Set<String> filters) {
            this.defaultSort = defaultSort;
            this.sorts = sorts;
            this.filters = filters;
        }

        private String defaultSort() {
            return this.defaultSort;
        }

        private Set<String> sorts() {
            return this.sorts;
        }

        private Set<String> filters() {
            return this.filters;
        }
    }

    private enum InspectionKind {
        ENVIRONMENT("env", environmentColumns()),
        HEALTH("health", componentColumns("health")),
        READINESS("readiness", componentColumns("readiness")),
        METRICS("metrics", metricColumns());

        private final String command;
        private final List<Column> columns;

        InspectionKind(final String command, final List<Column> columns) {
            this.command = command;
            this.columns = columns;
        }

        private String command() {
            return this.command;
        }

        private List<Column> columns() {
            return this.columns;
        }
    }

    @FunctionalInterface
    private interface DeadlineCall<T> {
        T call(CommandDeadlineGuard guard) throws Exception;
    }

    private static final class CommandDeadlineGuard {
        private final NuggetCliTicker ticker;
        private final long deadlineNanos;
        private boolean canceled;

        private CommandDeadlineGuard(final NuggetCliTicker ticker, final long deadlineNanos) {
            this.ticker = ticker;
            this.deadlineNanos = deadlineNanos;
        }

        private synchronized void cancel() {
            this.canceled = true;
        }

        private void requireActive(
                final NuggetSession session,
                final NuggetDiscovery.Record record) {
            boolean inactive;
            synchronized (this) {
                inactive = !this.activeLocked();
                if (inactive) {
                    this.canceled = true;
                }
            }
            if (inactive) {
                session.cancelTarget(record.nuggetId());
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            }
        }

        private boolean activeLocked() {
            return !this.canceled && this.deadlineNanos - this.ticker.nanoTime() > 0L;
        }

        private void cancelLocked() {
            this.canceled = true;
        }
    }

    private static final class NuggetSession implements AutoCloseable {
        private final NuggetCliClient client;
        private final NuggetCliSecretReader secretReader;
        private final Clock clock;
        private final NuggetCliTicker ticker;
        private final NuggetCliEventSink eventSink;
        private final Map<String, String> environment;
        private final CredentialCache credentials;
        private final Map<String, NuggetCliConnection> connections;
        private final Set<ConnectionAttempt> activeConnectionAttempts;
        private final Set<ConnectionAttempt> canceledConnectionAttempts;
        private final Map<String, String> admissions;
        private final ExecutorService commandExecutor;
        private NuggetDiscovery.Result discovery;
        private List<NuggetDiscovery.Record> targetRecords;
        private long nextConnectionAttempt;
        private boolean allTarget;
        private boolean closed;

        private NuggetSession(
                final NuggetCliClient client,
                final NuggetCliSecretReader secretReader,
                final Clock clock,
                final NuggetCliTicker ticker,
                final NuggetCliEventSink eventSink,
                final Map<String, String> environment) {
            this.client = client;
            this.secretReader = secretReader;
            this.clock = clock;
            this.ticker = ticker;
            this.eventSink = eventSink;
            this.environment = environment;
            this.credentials = new CredentialCache();
            this.connections = new LinkedHashMap<>();
            this.activeConnectionAttempts = new HashSet<>();
            this.canceledConnectionAttempts = new HashSet<>();
            this.admissions = new LinkedHashMap<>();
            this.commandExecutor = commandExecutor();
            this.targetRecords = List.of();
            this.allTarget = true;
            this.recordEvent("session-start", Map.of("mode", "nugget"));
        }

        private Instant now() {
            return Instant.now(this.clock);
        }

        private long commandDeadlineNanos() {
            return deadline(this.ticker.nanoTime(), COMMAND_DEADLINE);
        }

        private Instant deadlineInstant(final long deadlineNanos) {
            long remainingNanos = deadlineNanos - this.ticker.nanoTime();
            if (remainingNanos <= 0L) {
                return this.now();
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            if (remainingMillis <= 0L) {
                remainingMillis = 1L;
            }
            return this.now().plusMillis(Math.min(remainingMillis, COMMAND_DEADLINE.toMillis()));
        }

        private void requireCommandDeadlineActive(final long deadlineNanos) {
            if (deadlineNanos - this.ticker.nanoTime() <= 0L) {
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            }
        }

        private <T> T callWithinCommandDeadline(
                final Callable<T> task,
                final long deadlineNanos) {
            long now = this.ticker.nanoTime();
            long remaining = deadlineNanos - now;
            if (remaining <= 0L) {
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            }
            Future<T> future;
            try {
                future = this.commandExecutor.submit(task);
            } catch (RejectedExecutionException exception) {
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command work capacity exhausted");
            }
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command interrupted");
            } catch (java.util.concurrent.ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof NuggetCliFailure failure) {
                    throw failure;
                }
                throw new NuggetCliFailure(EXIT_INTERNAL, "INTERNAL", "internal failure");
            }
        }

        private <T> T callWithinCommandDeadline(
                final NuggetDiscovery.Record record,
                final Callable<T> task,
                final long deadlineNanos) {
            return this.callWithinCommandDeadline(record, ignored -> task.call(), deadlineNanos);
        }

        private <T> T callWithinCommandDeadline(
                final NuggetDiscovery.Record record,
                final DeadlineCall<T> task,
                final long deadlineNanos) {
            long now = this.ticker.nanoTime();
            long remaining = deadlineNanos - now;
            if (remaining <= 0L) {
                this.cancelTarget(record.nuggetId());
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            }
            CommandDeadlineGuard guard = new CommandDeadlineGuard(this.ticker, deadlineNanos);
            Future<T> future;
            try {
                future = this.commandExecutor.submit(() -> task.call(guard));
            } catch (RejectedExecutionException exception) {
                guard.cancel();
                this.cancelTarget(record.nuggetId());
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command work capacity exhausted");
            }
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                guard.cancel();
                future.cancel(true);
                this.cancelTarget(record.nuggetId());
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                guard.cancel();
                future.cancel(true);
                this.cancelTarget(record.nuggetId());
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NuggetCliFailure failure) {
                    throw failure;
                }
                throw new NuggetCliFailure(EXIT_INTERNAL, "INTERNAL", "internal failure");
            }
        }

        private String prompt() {
            if (this.allTarget || this.targetRecords.isEmpty()) {
                return "J7M > ";
            }
            return "J7M:" + displayName(this.targetRecords.get(0)) + " > ";
        }

        private NuggetDiscovery.Result discover() {
            this.recordEvent("discovery-refresh", Map.of("target", this.targetName()));
            this.discovery = this.client.discover(new NuggetDiscovery.Query(true, NuggetDiscovery.DEFAULT_LIMIT, "cli-discover"));
            if (this.allTarget) {
                this.targetRecords = activeCompatible(this.discovery.records());
            } else if (!this.targetRecords.isEmpty()) {
                String selectedId = this.targetRecords.get(0).nuggetId();
                List<NuggetDiscovery.Record> matches = activeCompatible(this.discovery.records()).stream()
                        .filter(record -> record.nuggetId().equals(selectedId))
                        .toList();
                if (matches.size() == 1) {
                    this.targetRecords = matches;
                } else {
                    this.disconnect(selectedId);
                    this.clearCredential(selectedId);
                    this.targetRecords = List.of();
                }
            }
            this.cleanupCredentials();
            return this.discovery;
        }

        private NuggetDiscovery.Result ensureDiscovery() {
            return this.discovery == null ? this.discover() : this.discovery;
        }

        private NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
            NuggetDiscovery.Resolution resolution = this.client.resolve(selector);
            if (selector.type() == NuggetDiscovery.SelectorType.ALL && resolution.records().isEmpty()) {
                List<NuggetDiscovery.Record> active = activeCompatible(this.ensureDiscovery().records());
                return new NuggetDiscovery.Resolution(
                        active.isEmpty() ? NuggetDiscovery.ResolutionStatus.NOT_FOUND : NuggetDiscovery.ResolutionStatus.RESOLVED,
                        active,
                        active.isEmpty() ? "not-found" : "resolved",
                        null,
                        this.now(),
                        List.of());
            }
            return resolution;
        }

        private void selectAll(final List<NuggetDiscovery.Record> records) {
            this.allTarget = true;
            this.targetRecords = activeCompatible(records);
            this.recordEvent("target-select", Map.of("target", "all"));
        }

        private void selectOne(final NuggetDiscovery.Record record) {
            this.allTarget = false;
            this.targetRecords = List.of(record);
            this.recordEvent("target-select", Map.of("target", shortId(record.nuggetId())));
        }

        private TargetSelection targetSelection() {
            return new TargetSelection(List.copyOf(this.targetRecords), this.allTarget);
        }

        private void restoreTargetSelection(final TargetSelection selection) {
            this.targetRecords = selection.records();
            this.allTarget = selection.allTarget();
        }

        private NuggetDiscovery.Record requireSingleTarget() {
            if (this.allTarget || this.targetRecords.size() != 1) {
                throw new NuggetCliFailure(EXIT_USAGE, "TARGET_REQUIRED", "select one target first");
            }
            return this.targetRecords.get(0);
        }

        private boolean hasSingleConcreteTarget() {
            return !this.allTarget && this.targetRecords.size() == 1;
        }

        private String targetName() {
            if (this.allTarget) {
                return "all";
            }
            if (this.targetRecords.isEmpty()) {
                return "none";
            }
            return displayName(this.targetRecords.get(0));
        }

        private List<NuggetDiscovery.Record> targetRecords() {
            if (this.allTarget && this.targetRecords.isEmpty()) {
                return activeCompatible(this.ensureDiscovery().records());
            }
            return this.targetRecords;
        }

        private char[] readInteractivePin(final String prompt, final boolean interactive) {
            if (!interactive || this.secretReader == null) {
                return new char[0];
            }
            char[] value = this.secretReader.readPin(prompt);
            if (validatedPinUtf8Bytes(value) < 0) {
                clear(value);
                return new char[0];
            }
            return value;
        }

        private char[] pinFromEnvironment(final String name) {
            if (!validEnvironmentName(name)) {
                return new char[0];
            }
            String value = this.environment.get(name);
            if (validatedPinUtf8Bytes(value) < 0) {
                return new char[0];
            }
            return value.toCharArray();
        }

        private void putCredential(final String nuggetId, final char[] pin) {
            this.cleanupCredentials();
            if (validatedPinUtf8Bytes(pin) < 0) {
                throw new NuggetCliFailure(EXIT_DENIED, "PIN_UNAVAILABLE", "PIN is invalid");
            }
            this.credentials.put(nuggetId, pin, this.ticker.nanoTime());
        }

        private char[] credential(final String nuggetId) {
            this.cleanupCredentials();
            return this.credentials.get(nuggetId, this.ticker.nanoTime());
        }

        private void clearCurrentCredential() {
            NuggetDiscovery.Record record = this.requireSingleTarget();
            this.clearCredential(record.nuggetId());
        }

        private void clearCredential(final String nuggetId) {
            this.cleanupCredentials();
            this.credentials.remove(nuggetId);
            synchronized (this) {
                this.admissions.remove(nuggetId);
            }
            this.recordEvent("credential-clear", Map.of("target", shortId(nuggetId)));
        }

        private void clearCredentials() {
            this.cleanupCredentials();
            this.credentials.clear();
            synchronized (this) {
                this.admissions.clear();
            }
            this.recordEvent("credential-clear", Map.of("target", "all"));
        }

        private void cleanupCredentials() {
            int removed = this.credentials.cleanup(this.ticker.nanoTime());
            if (removed > 0) {
                this.recordEvent("credential-expired", Map.of("category", "expired"));
            }
        }

        private NuggetCliConnection connect(final NuggetDiscovery.Record record) {
            String nuggetId = record.nuggetId();
            ConnectionAttempt attempt;
            synchronized (this) {
                if (this.closed) {
                    throw new NuggetCliFailure(EXIT_UNAVAILABLE, "CLOSED", "nugget session closed");
                }
                NuggetCliConnection existing = this.connections.get(nuggetId);
                if (existing != null) {
                    return existing;
                }
                attempt = new ConnectionAttempt(nuggetId, ++this.nextConnectionAttempt);
                this.activeConnectionAttempts.add(attempt);
            }
            this.recordEvent("connect", Map.of("target", shortId(nuggetId)));
            NuggetCliConnection connection;
            try {
                connection = this.client.connect(record);
            } catch (RuntimeException exception) {
                synchronized (this) {
                    this.activeConnectionAttempts.remove(attempt);
                    this.canceledConnectionAttempts.remove(attempt);
                }
                throw exception;
            }
            NuggetCliConnection duplicate = null;
            boolean closeNewConnection = false;
            synchronized (this) {
                this.activeConnectionAttempts.remove(attempt);
                boolean canceled = this.canceledConnectionAttempts.remove(attempt);
                if (this.closed || canceled) {
                    closeNewConnection = true;
                } else {
                    duplicate = this.connections.get(nuggetId);
                    if (duplicate == null) {
                        this.connections.put(nuggetId, connection);
                    } else {
                        closeNewConnection = true;
                    }
                }
            }
            if (closeNewConnection) {
                closeConnection(connection);
                if (duplicate != null) {
                    return duplicate;
                }
                throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "connection canceled");
            }
            return connection;
        }

        private synchronized void disconnect(final String nuggetId) {
            NuggetCliConnection connection = this.connections.remove(nuggetId);
            closeConnection(connection);
        }

        private void cancelTarget(final String nuggetId) {
            NuggetCliConnection connection;
            synchronized (this) {
                for (ConnectionAttempt attempt : this.activeConnectionAttempts) {
                    if (attempt.nuggetId().equals(nuggetId)) {
                        this.canceledConnectionAttempts.add(attempt);
                    }
                }
                connection = this.connections.remove(nuggetId);
            }
            closeConnection(connection);
        }

        private void admit(final String nuggetId, final String admissionId) {
            synchronized (this) {
                this.admissions.put(nuggetId, admissionId == null ? "" : admissionId);
            }
            this.recordEvent("auth-accepted", Map.of("target", shortId(nuggetId)));
        }

        private void admitIfActive(
                final NuggetDiscovery.Record record,
                final String admissionId,
                final CommandDeadlineGuard guard) {
            if (guard == null) {
                this.admit(record.nuggetId(), admissionId);
                return;
            }
            synchronized (guard) {
                if (guard.activeLocked()) {
                    synchronized (this) {
                        this.admissions.put(record.nuggetId(), admissionId == null ? "" : admissionId);
                    }
                    this.recordEvent("auth-accepted", Map.of("target", shortId(record.nuggetId())));
                    return;
                }
                guard.cancelLocked();
            }
            this.cancelTarget(record.nuggetId());
            throw new NuggetCliFailure(EXIT_UNAVAILABLE, "DEADLINE_EXCEEDED", "command deadline exceeded");
        }

        private synchronized String admissionId(final String nuggetId) {
            return this.admissions.getOrDefault(nuggetId, "");
        }

        private synchronized String currentAdmissionId() {
            if (this.allTarget || this.targetRecords.isEmpty()) {
                return "";
            }
            return this.admissions.getOrDefault(this.targetRecords.get(0).nuggetId(), "");
        }

        private synchronized int connectionCount() {
            return this.connections.size();
        }

        private NuggetIntrospection.Request request(final String admissionId) {
            return request(admissionId, "cli-status");
        }

        private NuggetIntrospection.Request request(final String admissionId, final String correlationId) {
            return new NuggetIntrospection.Request(
                    admissionId,
                    SecurityContext.system("j7m-cli"),
                    correlationId,
                    this.now(),
                    Map.of("client", "j7m-cli"));
        }

        @Override
        public void close() {
            this.cleanupCredentials();
            this.recordEvent("session-end", Map.of("mode", "nugget"));
            this.clearCredentials();
            this.commandExecutor.shutdownNow();
            List<NuggetCliConnection> ownedConnections;
            synchronized (this) {
                this.closed = true;
                this.canceledConnectionAttempts.addAll(this.activeConnectionAttempts);
                ownedConnections = new ArrayList<>(this.connections.values());
                this.connections.clear();
            }
            for (NuggetCliConnection connection : ownedConnections) {
                closeConnection(connection);
            }
            this.client.close();
        }

        private static void closeConnection(final NuggetCliConnection connection) {
            if (connection != null) {
                connection.close();
            }
        }

        private void recordEvent(final String event, final Map<String, String> metadata) {
            try {
                this.eventSink.record(event, metadata);
            } catch (RuntimeException exception) {
                // CLI event sinks are observational and must not affect command results.
                return;
            }
        }

        private static boolean validEnvironmentName(final String name) {
            if (name == null || name.isBlank() || name.length() > 64) {
                return false;
            }
            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                if (!(character == '_' || Character.isLetterOrDigit(character))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static List<NuggetDiscovery.Record> activeCompatible(final List<NuggetDiscovery.Record> records) {
        return orderedRecords(records == null ? List.of() : records).stream()
                .filter(record -> record.status() == NuggetDiscovery.Status.ACTIVE)
                .filter(record -> record.compatibility() == NuggetDiscovery.Compatibility.COMPATIBLE)
                .toList();
    }

    static final class CredentialCache {
        private final LinkedHashMap<String, Entry> values;
        private int cleanupCursor;
        private int retainedPinBytes;

        CredentialCache() {
            this.values = new LinkedHashMap<>();
        }

        void put(final String nuggetId, final char[] pin, final long nowNanos) {
            this.cleanup(nowNanos);
            this.remove(nuggetId);
            int pinBytes = validatedPinUtf8Bytes(pin);
            if (pinBytes < 0) {
                throw new IllegalArgumentException("PIN is invalid");
            }
            while (this.values.size() >= MAX_CACHE_ENTRIES || this.retainedPinBytes + pinBytes > MAX_CACHE_TOTAL_PIN_BYTES) {
                String first = this.values.keySet().iterator().next();
                this.remove(first);
            }
            this.values.put(nuggetId, new Entry(Arrays.copyOf(pin, pin.length), pinBytes, deadline(nowNanos, CACHE_TTL)));
            this.retainedPinBytes += pinBytes;
        }

        char[] get(final String nuggetId, final long nowNanos) {
            this.cleanup(nowNanos);
            Entry entry = this.values.get(nuggetId);
            if (entry == null) {
                return new char[0];
            }
            if (expired(nowNanos, entry.expiresAtNanos())) {
                this.remove(nuggetId);
                return new char[0];
            }
            return Arrays.copyOf(entry.pin(), entry.pin().length);
        }

        void remove(final String nuggetId) {
            Entry removed = this.values.remove(nuggetId);
            if (removed != null) {
                this.retainedPinBytes = Math.max(0, this.retainedPinBytes - removed.pinBytes());
                NuggetCliConsole.clear(removed.pin());
            }
            if (this.cleanupCursor > this.values.size()) {
                this.cleanupCursor = 0;
            }
        }

        void clear() {
            for (Entry entry : this.values.values()) {
                NuggetCliConsole.clear(entry.pin());
            }
            this.values.clear();
            this.cleanupCursor = 0;
            this.retainedPinBytes = 0;
        }

        int cleanup(final long nowNanos) {
            if (this.values.isEmpty()) {
                this.cleanupCursor = 0;
                return 0;
            }
            List<String> keys = new ArrayList<>(this.values.keySet());
            int start = this.cleanupCursor >= keys.size() ? 0 : this.cleanupCursor;
            int scanned = Math.min(MAX_CACHE_CLEANUP_SCAN, keys.size());
            List<String> expired = new ArrayList<>();
            for (int offset = 0; offset < scanned; offset++) {
                String key = keys.get((start + offset) % keys.size());
                Entry entry = this.values.get(key);
                if (entry != null && expired(nowNanos, entry.expiresAtNanos())) {
                    expired.add(key);
                }
            }
            int removed = expired.size();
            for (String key : expired) {
                this.remove(key);
            }
            if (this.values.isEmpty()) {
                this.cleanupCursor = 0;
            } else {
                this.cleanupCursor = (start + scanned) % this.values.size();
            }
            return removed;
        }

        int size() {
            return this.values.size();
        }

        int retainedPinBytes() {
            return this.retainedPinBytes;
        }

        boolean contains(final String nuggetId) {
            return this.values.containsKey(nuggetId);
        }

        char[] storedPinForTests(final String nuggetId) {
            Entry entry = this.values.get(nuggetId);
            return entry == null ? new char[0] : entry.pin();
        }

        private static long deadline(final long nowNanos, final Duration ttl) {
            return NuggetCliConsole.deadline(nowNanos, ttl);
        }

        private static boolean expired(final long nowNanos, final long deadlineNanos) {
            return nowNanos - deadlineNanos >= 0L;
        }
    }

    private record Entry(char[] pin, int pinBytes, long expiresAtNanos) {
    }

    private static final class CharArraySequence implements CharSequence {
        private final char[] value;

        private CharArraySequence(final char[] value) {
            this.value = value == null ? new char[0] : value;
        }

        @Override
        public int length() {
            return this.value.length;
        }

        @Override
        public char charAt(final int index) {
            return this.value[index];
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            StringBuilder builder = new StringBuilder(end - start);
            for (int index = start; index < end; index++) {
                builder.append(this.value[index]);
            }
            return builder.toString();
        }

        @Override
        public String toString() {
            return "<redacted>";
        }
    }
}
