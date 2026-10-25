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

import java.io.PrintStream;
import java.io.InputStream;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationManifestParser;
import org.j7m.middleware.application.repository.ApplicationManifestValidator;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.JpmsPackagingValidator;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;

/**
 * Command-line entry point for local J7M application and nugget operations.
 *
 * <p>The CLI implements the Phase 5.2-5.6 local developer/operator workflow:
 * package, sign, verify, deploy, plan, start, list, status, stop, unload,
 * upgrade, rollback, repository status, and repository audit verification. It
 * is intentionally local-runtime scoped and does not contact remote fleets,
 * external artifact replication systems, production CAs, KMS, GRC, SIEM, PDP,
 * or vendor services.</p>
 *
 * <p>The {@code nugget} command family is the Phase 7 CLI client for the
 * headless nugget administration contracts. It owns terminal parsing, prompt
 * and target state, local/provider-backed discovery, PIN-source handling,
 * safe status and inspection rendering, application lifecycle requests, and
 * disabled-by-default nugget control requests. Concrete remote administration
 * transports, script mode, and web or HTTP adapters are outside this CLI class
 * and require separate providers over the same public contracts.</p>
 */
public final class J7mCli {
    private static final ApplicationPlatformLimits LIMITS = ApplicationPlatformLimits.defaults();
    private static final List<String> COMMANDS = List.of(
            "package", "sign", "verify", "deploy", "plan", "start", "list", "status",
            "stop", "unload", "upgrade", "rollback");

    private J7mCli() {
    }

    /**
     * Runs the CLI as a Java main class.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        int exit = run(args == null ? new String[0] : args, System.in, System.out, System.err,
                defaultNuggetClient(args), NuggetCliSecretReader.console(), Clock.systemUTC(),
                NuggetCliTicker.system(), NuggetCliEventSink.none(), System.getenv());
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /**
     * Executes a command and writes output to {@link System#out}.
     *
     * @param args command arguments
     * @return process-style exit code
     */
    public static int run(final String[] args) {
        return run(args, System.out);
    }

    /**
     * Executes a command.
     *
     * @param args command arguments
     * @param output destination for safe public output
     * @return {@code 0} success, {@code 1} user input failure, {@code 2}
     * verification/security failure, {@code 3} lifecycle/runtime failure, or
     * {@code 4} unexpected internal failure
     */
    public static int run(final String[] args, final PrintStream output) {
        return run(args, output, output);
    }

    /**
     * Executes a command with separated public output and diagnostics streams.
     *
     * @param args command arguments
     * @param output destination for safe public output
     * @param error destination for safe diagnostics
     * @return process-style exit code
     */
    public static int run(final String[] args, final PrintStream output, final PrintStream error) {
        return run(args, System.in, output, error, defaultNuggetClient(args),
                NuggetCliSecretReader.console(), Clock.systemUTC(), NuggetCliTicker.system(),
                NuggetCliEventSink.none(), System.getenv());
    }

    /**
     * Executes a command with explicit nugget CLI integration seams.
     *
     * <p>This overload is intended for deterministic demos, tests, embedders,
     * and future launchers that already own the input, output, diagnostics,
     * clock, event sink, and transport-neutral nugget client. It does not
     * weaken production provider loading: ordinary command-line execution still
     * obtains nugget clients through {@link NuggetCliClientProvider}. The
     * supplied environment map is a caller-owned snapshot used only for
     * explicitly named PIN sources such as {@code --pin-env}; values are not
     * printed by the CLI and are copied only into shortest-scope credential
     * material.</p>
     *
     * @param args command arguments
     * @param input command input
     * @param output destination for safe command output
     * @param error destination for diagnostics
     * @param nuggetClient transport-neutral nugget client
     * @param secretReader interactive secret reader, or {@code null}
     * @param clock wall-clock source for request metadata
     * @param ticker monotonic time source for deadlines and credential expiry
     * @param eventSink safe CLI event sink
     * @param environment immutable environment snapshot used by explicit
     *        environment-source options
     * @return process-style exit code
     */
    public static int run(
            final String[] args,
            final InputStream input,
            final PrintStream output,
            final PrintStream error,
            final NuggetCliClient nuggetClient,
            final NuggetCliSecretReader secretReader,
            final Clock clock,
            final NuggetCliTicker ticker,
            final NuggetCliEventSink eventSink,
            final Map<String, String> environment) {
        String[] safeArgs = args == null ? new String[0] : args;
        if (safeArgs.length > 0 && "nugget".equals(safeArgs[0])) {
            return NuggetCliConsole.run(safeArgs, input, output, error, nuggetClient, secretReader, clock,
                    ticker, eventSink, environment);
        }
        try {
            if (safeArgs.length == 0) {
                usage(output);
                return 1;
            }
            if ("--help".equals(safeArgs[0]) || "-h".equals(safeArgs[0])) {
                usage(output);
                return 0;
            }
            if (safeArgs.length < 2 || !"app".equals(safeArgs[0])) {
                usage(output);
                return 1;
            }
            Options options = Options.parse(safeArgs);
            if ("repository".equals(safeArgs[1])) {
                return repositoryCommand(safeArgs, options, output);
            }
            if (!COMMANDS.contains(safeArgs[1])) {
                usage(output);
                return 1;
            }
            return switch (safeArgs[1]) {
                case "package" -> packageCommand(options, output);
                case "sign" -> signCommand(options, output);
                case "verify" -> verifyCommand(options, output);
                case "deploy" -> deployCommand(options, output);
                case "plan" -> lifecycleCommand("PLANNED", options, output);
                case "start" -> lifecycleCommand("RUNNING", options, output);
                case "list" -> listCommand(options, output);
                case "status" -> statusCommand(options, output);
                case "stop" -> lifecycleCommand("STOPPED", options, output);
                case "unload" -> lifecycleCommand("UNLOADED", options, output);
                case "upgrade" -> upgradeCommand(options, output);
                case "rollback" -> rollbackCommand(options, output);
                default -> {
                    usage(output);
                    yield 1;
                }
            };
        } catch (VerificationFailure exception) {
            output.println("ERROR verification: verification failed");
            return 2;
        } catch (LifecycleFailure exception) {
            output.println("ERROR lifecycle: lifecycle unavailable");
            return 3;
        } catch (IllegalArgumentException exception) {
            output.println("ERROR input: invalid command input");
            return 1;
        } catch (Exception exception) {
            output.println("ERROR internal: operation failed");
            return 4;
        }
    }

    private static NuggetCliClient defaultNuggetClient(final String[] args) {
        String[] safeArgs = args == null ? new String[0] : args;
        if (safeArgs.length > 0 && "nugget".equals(safeArgs[0])) {
            return NuggetCliClientProviders.load(Thread.currentThread().getContextClassLoader());
        }
        return NuggetCliClient.unavailable();
    }

    private static int packageCommand(final Options options, final PrintStream output) {
        Path manifest = options.manifest();
        ApplicationManifest parsed = parseManifest(manifest);
        ApplicationVerificationResult manifestResult = new ApplicationManifestValidator()
                .validate(parsed, options.policy().signatureRequired());
        if (!manifestResult.accepted()) {
            throw new IllegalArgumentException("manifest rejected");
        }
        List<Path> docs = Files.isRegularFile(options.project.resolve("README.md"))
                ? List.of(options.project.resolve("README.md"))
                : List.of();
        ApplicationBundle bundle = new J7mBundleWriter().write(manifest, options.bundle, List.of(), moduleFiles(parsed, options.project), docs);
        output(options, output, Map.of("command", "package", "bundle", bundle.path().getFileName().toString(), "coordinate", bundle.coordinate().toString()));
        return 0;
    }

    private static int signCommand(final Options options, final PrintStream output) {
        ApplicationBundle bundle = bundle(options);
        new DefaultBundleSigner().sign(bundle);
        output(options, output, Map.of("command", "sign", "bundle", bundle.path().getFileName().toString()));
        return 0;
    }

    private static int verifyCommand(final Options options, final PrintStream output) {
        ApplicationVerificationResult result = new ApplicationBundleVerifier().verify(bundle(options), options.policy());
        if (!result.accepted()) {
            throw new VerificationFailure("bundle verification rejected");
        }
        output(options, output, Map.of("command", "verify", "accepted", "true", "profile", options.profile));
        return 0;
    }

    private static int deployCommand(final Options options, final PrintStream output) {
        verifyCommand(options, new PrintStream(OutputSink.INSTANCE, true, StandardCharsets.UTF_8));
        LocalApplicationRepository repository = repository(options);
        ApplicationDeploymentResult result = repository.publish(bundle(options),
                new RepositoryPublishOptions("cli", options.policy().signatureRequired(), options.profile));
        if (!result.success()) {
            throw new VerificationFailure(result.message());
        }
        output(options, output, Map.of("command", "deploy", "published", "true", "repository", "local-filesystem"));
        return 0;
    }

    private static int lifecycleCommand(final String state, final Options options, final PrintStream output) {
        ApplicationCoordinate coordinate = coordinate(options);
        writeUnavailableState(options, coordinate, options.strategy, coordinate.toString());
        output(options, output, Map.of("command", state.toLowerCase(java.util.Locale.ROOT), "state", "UNAVAILABLE",
                "coordinate", coordinate.toString(), "reason", "local lifecycle requires an active ApplicationManager session"));
        return 0;
    }

    private static int listCommand(final Options options, final PrintStream output) {
        List<String> state = Files.isRegularFile(stateFile(options)) ? readLines(stateFile(options)) : List.of();
        output(options, output, Map.of("command", "list", "entries", Integer.toString(state.size()), "repository", "local-filesystem"));
        return 0;
    }

    private static int statusCommand(final Options options, final PrintStream output) {
        Path state = stateFile(options);
        String text = Files.isRegularFile(state) ? readString(state).trim() : "{\"state\":\"NONE\"}";
        output.println(options.json ? text : "j7m app status " + text);
        return 0;
    }

    private static int upgradeCommand(final Options options, final PrintStream output) {
        if (options.to.isBlank()) {
            throw new IllegalArgumentException("--to is required for upgrade");
        }
        ApplicationCoordinate current = coordinate(options);
        writeUnavailableState(options, ApplicationCoordinate.parse(options.to), options.strategy, current.toString());
        output(options, output, Map.of("command", "upgrade", "state", "UNAVAILABLE",
                "strategy", options.strategy, "from", current.toString(), "to", options.to));
        return 0;
    }

    private static int rollbackCommand(final Options options, final PrintStream output) {
        ApplicationCoordinate target = coordinate(options);
        writeUnavailableState(options, target, options.strategy, target.toString());
        output(options, output, Map.of("command", "rollback", "state", "UNAVAILABLE", "coordinate", target.toString()));
        return 0;
    }

    private static int repositoryCommand(final String[] args, final Options options, final PrintStream output) {
        if (args.length < 3) {
            usage(output);
            return 1;
        }
        LocalApplicationRepository repository = repository(options);
        if ("status".equals(args[2])) {
            output(options, output, Map.of("command", "repository status",
                    "artifacts", Integer.toString(repository.status().artifactCount()),
                    "auditChainValid", repository.status().metadata().getOrDefault("auditChainValid", "false")));
            return 0;
        }
        if ("verify-audit".equals(args[2])) {
            boolean valid = repository.verifyAuditChain();
            if (!valid) {
                throw new VerificationFailure("repository audit chain invalid");
            }
            output(options, output, Map.of("command", "repository verify-audit", "auditChainValid", "true"));
            return 0;
        }
        usage(output);
        return 1;
    }

    private static ApplicationManifest parseManifest(final Path manifest) {
        return new ApplicationManifestParser().parse(readString(manifest));
    }

    private static ApplicationBundle bundle(final Options options) {
        if (!Files.isRegularFile(options.bundle)) {
            throw new IllegalArgumentException("bundle does not exist");
        }
        ApplicationCoordinate coordinate = coordinate(options);
        return new ApplicationBundle(options.bundle, coordinate, new J7mBundleReader().sha256(options.bundle));
    }

    private static ApplicationCoordinate coordinate(final Options options) {
        if (!options.coordinate.isBlank()) {
            return ApplicationCoordinate.parse(options.coordinate);
        }
        return parseManifest(options.manifest()).identity().coordinate();
    }

    private static LocalApplicationRepository repository(final Options options) {
        return new LocalApplicationRepository(options.repository);
    }

    private static List<Path> moduleFiles(final ApplicationManifest manifest, final Path project) {
        if (!"JPMS".equals(manifest.moduleMode())) {
            return List.of();
        }
        return new JpmsPackagingValidator(LIMITS).validateDeclaredModules(project, manifest, List.of());
    }

    private static void writeUnavailableState(
            final Options options,
            final ApplicationCoordinate coordinate,
            final String strategy,
            final String previous) {
        try {
            Files.createDirectories(stateFile(options).getParent());
            Files.writeString(stateFile(options),
                    "{\"state\":\"UNAVAILABLE\",\"coordinate\":\"" + coordinate
                            + "\",\"strategy\":\"" + strategy + "\",\"previous\":\"" + previous
                            + "\",\"reason\":\"local lifecycle requires an active ApplicationManager session"
                            + "\",\"secrets\":\"redacted\"}\n",
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new LifecycleFailure("failed to write local runtime state", exception);
        }
    }

    private static Path stateFile(final Options options) {
        return options.repository.resolve(".cli-runtime-state.json");
    }

    private static String readString(final Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > LIMITS.maxMetadataBytes()) {
                throw new IllegalArgumentException("file exceeds configured metadata limit");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to read " + path.getFileName(), exception);
        }
    }

    private static List<String> readLines(final Path path) {
        try {
            String text = readString(path);
            if (text.isBlank()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            int start = 0;
            while (start <= text.length()) {
                int end = text.indexOf('\n', start);
                String line = end < 0 ? text.substring(start) : text.substring(start, end);
                if (!line.isEmpty()) {
                    if (lines.size() == LIMITS.maxAuditEventsPerPage()) {
                        break;
                    }
                    lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                }
                if (end < 0) {
                    break;
                }
                start = end + 1;
            }
            return List.copyOf(lines);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static void output(final Options options, final PrintStream output, final Map<String, String> values) {
        if (options.json) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(escape(entry.getKey())).append("\":\"")
                        .append(escape(entry.getValue())).append('"');
            }
            builder.append('}');
            output.println(builder);
            return;
        }
        output.println("j7m app " + values);
    }

    private static void usage(final PrintStream output) {
        output.println("usage: j7m app <package|sign|verify|deploy|plan|start|list|status|stop|unload|upgrade|rollback|repository status|repository verify-audit>");
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Options {
        private final Path project;
        private final Path bundle;
        private final Path repository;
        private final String coordinate;
        private final String profile;
        private final String signingKey;
        private final String strategy;
        private final String to;
        private final boolean json;

        private Options(
                final Path project,
                final Path bundle,
                final Path repository,
                final String coordinate,
                final String profile,
                final String signingKey,
                final String strategy,
                final String to,
                final boolean json) {
            this.project = project;
            this.bundle = bundle;
            this.repository = repository;
            this.coordinate = coordinate;
            this.profile = profile;
            this.signingKey = signingKey;
            this.strategy = strategy;
            this.to = to;
            this.json = json;
        }

        private static Options parse(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean json = false;
            int index = 0;
            while (index < args.length) {
                String arg = args[index];
                if ("--json".equals(arg)) {
                    json = true;
                } else if ("--strict-production".equals(arg)) {
                    values.put("--profile", "strict-production");
                } else if ("--strict-regulated".equals(arg)) {
                    values.put("--profile", "strict-regulated");
                } else if (arg.startsWith("--")) {
                    if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                        values.put(arg, "");
                    } else {
                        index++;
                        values.put(arg, args[index]);
                    }
                }
                index++;
            }
            Path project = Path.of(values.getOrDefault("--project", ".")).toAbsolutePath().normalize();
            String profile = values.getOrDefault("--profile", "development");
            Path bundle = values.containsKey("--bundle")
                    ? Path.of(values.get("--bundle")).toAbsolutePath().normalize()
                    : project.resolve("build/libs/" + project.getFileName() + ".j7m").toAbsolutePath().normalize();
            Path repository = values.containsKey("--repository")
                    ? Path.of(values.get("--repository")).toAbsolutePath().normalize()
                    : project.resolve("build/j7m-repository").toAbsolutePath().normalize();
            return new Options(project, bundle, repository,
                    values.getOrDefault("--coordinate", ""),
                    profile,
                    values.getOrDefault("--signing-key", "local-dev-ed25519"),
                    values.getOrDefault("--strategy", "stop-start"),
                    values.getOrDefault("--to", ""),
                    json);
        }

        private Path manifest() {
            Path direct = this.project.resolve("META-INF/j7m-application.json");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path resources = this.project.resolve("src/main/resources/META-INF/j7m-application.json");
            if (Files.isRegularFile(resources)) {
                return resources;
            }
            throw new IllegalArgumentException("manifest not found under project");
        }

        private SignaturePolicy policy() {
            return ApplicationBundleVerifier.policy(this.profile, false);
        }
    }

    private static final class VerificationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private VerificationFailure(final String message) {
            super(message);
        }
    }

    private static final class LifecycleFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private LifecycleFailure(final String message) {
            super(message);
        }

        private LifecycleFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private static final class OutputSink extends java.io.OutputStream {
        private static final OutputSink INSTANCE = new OutputSink();

        @Override
        public void write(final int value) {
            // discard nested command output
        }
    }

}
