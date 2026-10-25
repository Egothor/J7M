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

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetAdminField;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind;
import org.j7m.middleware.operations.api.NuggetAdminQuery;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminSortDirection;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationFailureReason;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetConfigurationSummary;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetIdentity;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the deployer CLI local workflow.
 */
final class J7mCliTest {
    @TempDir
    private Path temporaryDirectory;

    /**
     * Verifies package, sign, verify, deploy, plan, start, list, status, stop,
     * and unload for a classpath application.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void classpathLifecycleWorkflowSucceeds() throws Exception {
        Fixture fixture = this.fixture("cli-classpath", false);

        assertEquals(0, this.run(fixture, "package"));
        assertEquals(0, this.run(fixture, "sign"));
        assertEquals(0, this.run(fixture, "verify"));
        assertEquals(0, this.run(fixture, "deploy"));
        assertEquals(0, this.run(fixture, "plan"));
        assertEquals(0, this.run(fixture, "start"));
        assertEquals(0, this.run(fixture, "list"));
        Output status = this.output(fixture, "status");
        assertEquals(0, status.exitCode());
        assertTrue(status.text().contains("UNAVAILABLE"));
        assertTrue(status.text().contains("ApplicationManager"));
        assertFalse(status.text().contains("private"));
        assertEquals(0, this.run(fixture, "stop"));
        assertEquals(0, this.run(fixture, "unload"));
    }

    /**
     * Verifies strict regulated verification requires signing, SBOM, and
     * provenance metadata.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void strictRegulatedWorkflowSucceedsWithMetadataAndRejectsUnsigned() throws Exception {
        Fixture fixture = this.fixture("cli-regulated", true);

        assertEquals(0, this.run(fixture, "package"));
        assertEquals(2, this.run(fixture, "verify", "--profile", "strict-production"));
        assertEquals(0, this.run(fixture, "sign"));
        assertEquals(0, this.run(fixture, "verify", "--profile", "strict-regulated"));
        assertEquals(0, this.run(fixture, "deploy", "--profile", "strict-regulated"));
        assertEquals(0, this.run(fixture, "repository", "status"));
        assertEquals(0, this.run(fixture, "repository", "verify-audit"));
    }

    @Test
    void jpmsPackagingRequiresValidDeclaredModuleDescriptor() throws Exception {
        Fixture fixture = this.jpmsFixture("cli-jpms-valid", "com.example.cli.valid", "com.example.cli.valid");
        assertEquals(0, this.run(fixture, "package"));
        assertTrue(Files.isRegularFile(fixture.bundle()));
    }

    @Test
    void jpmsPackagingFailsWhenDeclaredModuleIsMissing() throws Exception {
        Fixture fixture = this.jpmsFixtureWithoutModule("cli-jpms-missing", "com.example.cli.missing");
        assertEquals(1, this.run(fixture, "package"));
    }

    @Test
    void jpmsPackagingDoesNotResolveMissingDeclaredPathByBasename() throws Exception {
        Fixture fixture = this.jpmsFixtureWithoutModule("cli-jpms-basename", "com.example.cli.basename");
        Files.createDirectories(fixture.project().resolve("elsewhere"));
        createModularJar(fixture.project().resolve("elsewhere").resolve("app.jar"), "com.example.cli.basename");

        assertEquals(1, this.run(fixture, "package"));
        assertFalse(Files.isRegularFile(fixture.bundle()));
    }

    @Test
    void jpmsPackagingRejectsAutomaticModule() throws Exception {
        Fixture fixture = this.jpmsFixtureWithAutomaticModule("cli-jpms-automatic", "com.example.cli.automatic");
        assertEquals(1, this.run(fixture, "package"));
    }

    @Test
    void jpmsPackagingRejectsMismatchedModuleDescriptorName() throws Exception {
        Fixture fixture = this.jpmsFixture("cli-jpms-mismatch", "com.example.cli.expected", "com.example.cli.actual");
        assertEquals(1, this.run(fixture, "package"));
    }

    /**
     * Verifies upgrade and rollback command paths for all local strategies.
     *
     * @throws Exception on fixture setup failure
     */
    @Test
    void upgradeRollbackCommandsSupportAllStrategies() throws Exception {
        Fixture fixture = this.fixture("cli-upgrade", false);

        assertEquals(0, this.run(fixture, "package"));
        assertEquals(0, this.run(fixture, "sign"));
        assertEquals(0, this.run(fixture, "deploy"));
        assertEquals(0, this.run(fixture, "start"));
        String[] strategies = {"stop-start", "rolling", "blue-green", "canary-baseline"};
        for (String strategy : strategies) {
            assertEquals(0, this.run(fixture, "upgrade", "--strategy", strategy, "--to", "org.j7m.cli:cli-upgrade:1.1.0"), strategy);
            assertEquals(0, this.run(fixture, "rollback", "--strategy", strategy), strategy);
        }
    }

    /**
     * Verifies exit code policy and help output.
     */
    @Test
    void invalidCommandsUseInputExitCode() {
        assertEquals(0, J7mCli.run(new String[] {"--help"}));
        assertEquals(1, J7mCli.run(new String[] {"app", "unknown"}));
        assertEquals(1, J7mCli.run(new String[] {"wrong", "package"}));
        assertEquals(1, J7mCli.run(new String[0]));
    }

    /**
     * Verifies the nugget interactive shell starts, prompts on stderr, and
     * exits without contaminating stdout.
     */
    @Test
    void nuggetShellPromptsAndExitsCleanly() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell", "exit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stderr().contains("J7M >"));
        assertFalse(output.stdout().contains("J7M >"));
    }

    /**
     * Verifies one-shot discovery and JSON output do not include prompts.
     */
    @Test
    void nuggetOneShotDiscoveryAndJsonArePromptFree() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));

        NuggetOutput human = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "discover", "");
        assertEquals(0, human.exitCode());
        assertTrue(human.stdout().contains("discovered 1 nugget"));
        assertFalse(human.stdout().contains("J7M >"));

        NuggetOutput json = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "nuggets", "--json", "");
        assertEquals(0, json.exitCode());
        assertTrue(json.stdout().startsWith("{\"version\":1,\"ok\":true"));
        assertFalse(json.stdout().contains("J7M >"));
        assertFalse(json.stderr().contains("J7M >"));
    }

    /**
     * Verifies production-style ServiceLoader acquisition reaches a functional
     * provider and closes the created client.
     *
     * @throws Exception on service-resource setup failure
     */
    @Test
    void nuggetProductionEntryAcquiresProviderAndClosesClient() throws Exception {
        TestNuggetCliProvider.reset();
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        TestNuggetCliProvider.client = client;

        NuggetOutput output = this.productionNugget(
                new String[] {TestNuggetCliProvider.class.getName()},
                "nugget", "discover", "--json");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().startsWith("{\"version\":1,\"ok\":true"));
        assertEquals(1, TestNuggetCliProvider.created);
        assertEquals(1, client.closeCount());
        TestNuggetCliProvider.reset();
    }

    /**
     * Verifies no provider, duplicate providers, and broken providers fail with
     * stable unavailable behavior.
     *
     * @throws Exception on service-resource setup failure
     */
    @Test
    void nuggetProductionProviderFailuresAreUnavailableAndSafe() throws Exception {
        NuggetOutput none = this.productionNugget(new String[0], "nugget", "discover", "--json");
        assertEquals(3, none.exitCode());
        assertTrue(none.stdout().contains("UNAVAILABLE"));

        NuggetOutput humanNone = this.productionNugget(new String[0], "nugget", "discover");
        assertEquals(3, humanNone.exitCode());
        assertTrue(humanNone.stderr().contains("UNAVAILABLE"));

        NuggetOutput duplicate = this.productionNugget(
                new String[] {TestNuggetCliProvider.class.getName(), SecondNuggetCliProvider.class.getName()},
                "nugget", "discover", "--json");
        assertEquals(3, duplicate.exitCode());
        assertTrue(duplicate.stdout().contains("UNAVAILABLE"));

        NuggetOutput broken = this.productionNugget(
                new String[] {BrokenNuggetCliProvider.class.getName()},
                "nugget", "discover", "--json");
        assertEquals(3, broken.exitCode());
        assertTrue(broken.stdout().contains("UNAVAILABLE"));
        assertFalse(broken.stdout().contains("broken provider sentinel"));
    }

    /**
     * Verifies malformed ServiceLoader descriptors and unloadable providers are
     * normalized without exposing implementation details.
     *
     * @throws Exception on service-resource setup failure
     */
    @Test
    void nuggetProductionProviderServiceConfigurationErrorsAreUnavailableAndSafe() throws Exception {
        List<String> providerCases = List.of(
                "not a provider name",
                "org.j7m.middleware.deployer.cli.MissingProvider",
                "java.lang.String",
                ConstructorFailingNuggetCliProvider.class.getName(),
                StaticFailingNuggetCliProvider.class.getName());

        for (String provider : providerCases) {
            NuggetOutput output = this.productionNugget(new String[] {provider}, "nugget", "discover", "--json");
            assertEquals(3, output.exitCode(), provider);
            assertTrue(output.stdout().contains("UNAVAILABLE"), provider);
            assertFalse(output.stdout().contains(provider), provider);
            assertFalse(output.stderr().contains(provider), provider);
            assertFalse(output.stdout().contains("provider sentinel"), provider);
            assertFalse(output.stderr().contains("provider sentinel"), provider);
        }

        NuggetOutput mixed = this.productionNugget(
                new String[] {TestNuggetCliProvider.class.getName(), "org.j7m.middleware.deployer.cli.MissingProvider"},
                "nugget", "discover", "--json");
        assertEquals(3, mixed.exitCode());
        assertTrue(mixed.stdout().contains("UNAVAILABLE"));
        assertFalse(mixed.stdout().contains("MissingProvider"));
    }

    /**
     * Verifies deterministic target selection behavior.
     */
    @Test
    void nuggetTalkSupportsAllUniqueNameExactIdAndAmbiguity() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(
                record("nugget-a", "ABC"),
                record("nugget-b", "DUP"),
                record("nugget-c", "DUP"),
                record("nugget-stale", "OLD", NuggetDiscovery.Status.STALE, NuggetDiscovery.Compatibility.COMPATIBLE)));

        NuggetOutput all = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "talk", "all", "");
        assertEquals(0, all.exitCode());
        assertTrue(all.stdout().contains("target all"));

        NuggetOutput unique = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "talk", "ABC", "");
        assertEquals(0, unique.exitCode());
        assertTrue(unique.stdout().contains("target ABC"));

        NuggetOutput exact = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "talk", "--id", "nugget-a", "");
        assertEquals(0, exact.exitCode());
        assertTrue(exact.stdout().contains("target ABC"));

        NuggetOutput duplicate = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "talk", "DUP", "");
        assertEquals(1, duplicate.exitCode());
        assertTrue(duplicate.stderr().contains("AMBIGUOUS"));

        NuggetOutput stale = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "talk", "OLD", "");
        assertEquals(1, stale.exitCode());
        assertTrue(stale.stderr().contains("STALE") || stale.stderr().contains("NOT_FOUND"));
    }

    /**
     * Verifies target prompt changes after selecting a named nugget.
     */
    @Test
    void nuggetShellPromptUsesSelectedName() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "discover\ntalk ABC\ntarget\nquit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stderr().contains("J7M:ABC >"));
        assertTrue(output.stdout().contains("target ABC"));
    }

    /**
     * Verifies PIN authentication, compact info/status, and cache clear.
     */
    @Test
    void nuggetPinAuthInfoStatusAndClearAreSafe() {
        String secret = "phase74-pin";
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader(secret), Map.of(), "nugget", "shell",
                "discover\ntalk ABC\npin\nauth\ninfo\nstatus\npin clear\nwhoami\nexit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("cached ABC"));
        assertTrue(output.stdout().contains("accepted ABC"));
        assertTrue(output.stdout().contains("ABC nugget-a RUNNING"));
        assertTrue(output.stdout().contains("health HEALTHY readiness READY"));
        assertTrue(output.stdout().contains("admission none"));
        assertFalse(output.stdout().contains(secret));
        assertFalse(output.stderr().contains(secret));
        assertFalse(output.stdout().contains("phase74"));
    }

    /**
     * Verifies wrong, throttled, and unavailable PIN handling.
     */
    @Test
    void nuggetAuthenticationFailuresAreStableAndNonInteractiveDoesNotPrompt() {
        FakeNuggetClient wrong = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput wrongOutput = this.nugget(wrong, pinReader("wrong-pin"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        assertEquals(2, wrongOutput.exitCode());
        assertTrue(wrongOutput.stderr().contains("PIN_REJECTED"));

        FakeNuggetClient throttled = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput throttledOutput = this.nugget(throttled, pinReader("throttle"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        assertEquals(2, throttledOutput.exitCode());
        assertTrue(throttledOutput.stderr().contains("THROTTLED"));

        FakeNuggetClient noPrompt = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput nonInteractive = this.nugget(noPrompt, pinReader("123456"), Map.of(),
                "nugget", "auth", "--target", "ABC", "");
        assertEquals(2, nonInteractive.exitCode());
        assertFalse(nonInteractive.stderr().contains("123456"));
    }

    /**
     * Verifies non-interactive environment PINs are accepted without echoing
     * secret material.
     */
    @Test
    void nuggetAuthCanUseExplicitEnvironmentPinWithoutEcho() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                "nugget", "shell", "talk ABC\nauth --pin-env J7M_TEST_PIN\nexit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("accepted ABC"));
        assertFalse(output.stdout().contains(secret));
        assertFalse(output.stderr().contains(secret));
    }

    /**
     * Verifies one-shot info can authenticate from an explicit safe source and
     * still emits a single JSON envelope.
     */
    @Test
    void nuggetOneShotInfoAuthenticatesFromEnvironment() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "info", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--json"}, "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().startsWith("{\"version\":1,\"ok\":true"));
        assertTrue(output.stdout().contains("nugget-a"));
        assertFalse(output.stdout().contains(secret));
        assertFalse(output.stderr().contains(secret));
    }

    /**
     * Verifies representative CLI query parsing produces the public Phase 7.8
     * query DTO used by future adapters.
     */
    @Test
    void nuggetCliQueryParsingMatchesPublicContractDto() {
        NuggetAdminQuery query = NuggetCliConsole.contractQueryForTesting(List.of(
                "objects",
                "--filter",
                "service=orders",
                "--sort",
                "service",
                "--desc",
                "--limit",
                "3",
                "--offset",
                "2"));

        assertEquals(2L, query.offset());
        assertEquals(3, query.limit());
        assertEquals(NuggetAdminField.SERVICE_NAME, query.filters().get(0).field());
        assertEquals("orders", query.filters().get(0).value());
        assertEquals(NuggetAdminField.SERVICE_NAME, query.sorts().get(0).field());
        assertEquals(NuggetAdminSortDirection.DESC, query.sorts().get(0).direction());
        assertEquals(NuggetAdminField.STABLE_ID, query.sorts().get(1).field());
    }

    /**
     * Verifies unsupported public-contract projection fields are rejected
     * deterministically.
     */
    @Test
    void nuggetCliContractQueryRejectsUnsupportedFields() {
        assertThrows(NuggetCliFailure.class, () -> NuggetCliConsole.contractQueryForTesting(List.of(
                "objects",
                "--sort",
                "providerPrivateField")));
        assertThrows(NuggetCliFailure.class, () -> NuggetCliConsole.contractQueryForTesting(List.of(
                "objects",
                "--filter",
                "providerPrivateField=value")));
    }

    /**
     * Verifies CLI target context uses public target identity semantics rather
     * than a private generation fabrication.
     */
    @Test
    void nuggetCliTargetContextUsesPublicGenerationSemantics() {
        NuggetDiscovery.Record record = record("nugget-a", "ABC");
        NuggetAdminRequestContext context = NuggetCliConsole.contractContextForTesting(
                "shutdown-nugget",
                record,
                NuggetAdminOperationKind.NUGGET_CONTROL,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:02Z"));

        assertEquals("nugget-a", context.target().nuggetId());
        assertEquals("runtime-nugget-a", context.target().runtimeId());
        assertEquals(0L, context.target().generation());
        assertEquals(NuggetAdminOperationKind.NUGGET_CONTROL, context.operationKind());
        assertEquals("j7m-cli", context.metadata().get("client"));
    }

    /**
     * Verifies one-shot environment PIN use is rejected in all-target mode
     * before any client receives credential material.
     */
    @Test
    void nuggetOneShotEnvironmentPinRequiresConcreteTarget() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient infoClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput info = this.nugget(infoClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "info", new String[] {"--pin-env", "J7M_TEST_PIN", "--json"}, "");

        assertEquals(1, info.exitCode());
        assertEquals(0, infoClient.authenticateCount());
        assertTrue(info.stdout().contains("TARGET_REQUIRED"));
        assertFalse(info.stdout().contains(secret));
        assertFalse(info.stderr().contains(secret));

        FakeNuggetClient statusClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput status = this.nugget(statusClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "status", new String[] {"--pin-env", "J7M_TEST_PIN"}, "");

        assertEquals(1, status.exitCode());
        assertEquals(0, statusClient.authenticateCount());
        assertTrue(status.stderr().contains("TARGET_REQUIRED"));
        assertFalse(status.stdout().contains(secret));
        assertFalse(status.stderr().contains(secret));
    }

    /**
     * Verifies Phase 7.5 compact, wide, and JSON {@code ps} inspection over the
     * existing provider seam.
     */
    @Test
    void nuggetPsCompactWideAndJsonInspectApplicationsAndObjects() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient compactClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput compact = this.nugget(compactClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--width", "80"}, "");

        assertEquals(0, compact.exitCode());
        assertTrue(compact.stdout().contains("NUGGET"));
        assertTrue(compact.stdout().contains("KIND"));
        assertTrue(compact.stdout().contains("HEALTH"));
        assertTrue(compact.stdout().contains("orders"));
        assertFalse(compact.stdout().contains("ENDPOINT"));
        assertFalse(compact.stdout().contains(secret));

        FakeNuggetClient wideClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput wide = this.nugget(wideClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--wide", "--json"}, "");

        assertEquals(0, wide.exitCode());
        assertTrue(wide.stdout().contains("\"command\":\"ps\""));
        assertTrue(wide.stdout().contains("orders-1"));
        assertTrue(wide.stdout().contains("orders-object"));
        assertTrue(wide.stdout().contains("\"partial\":\"false\""));
        assertFalse(wide.stdout().contains(secret));
    }

    /**
     * Verifies {@code ps} applies filter, sort, offset, and limit semantics to
     * the merged object/application row set rather than to each server
     * collection independently.
     */
    @Test
    void nuggetPsFiltersSortsAndLimitsMergedRows() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient objectFilterClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput objectFilter = this.nugget(objectFilterClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "visibility=INTERNAL", "--json"}, "");
        assertEquals(0, objectFilter.exitCode());
        assertTrue(objectFilter.stdout().contains("runtime-object"));
        assertFalse(objectFilter.stdout().contains("orders-1"));
        assertFalse(objectFilter.stdout().contains("orders-object"));
        assertTrue(objectFilter.stdout().contains("\"partial\":\"false\""));

        FakeNuggetClient appFilterClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput appFilter = this.nugget(appFilterClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "applicationId=billing", "--json"}, "");
        assertEquals(0, appFilter.exitCode());
        assertTrue(appFilter.stdout().contains("billing-1"));
        assertFalse(appFilter.stdout().contains("orders-object"));

        FakeNuggetClient sortedClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput sorted = this.nugget(sortedClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--sort", "id", "--desc", "--json"}, "");
        assertEquals(0, sorted.exitCode());
        assertTrue(sorted.stdout().indexOf("runtime-object") < sorted.stdout().indexOf("orders-object"));

        FakeNuggetClient limitClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput limited = this.nugget(limitClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--limit", "1", "--json"}, "");
        assertEquals(0, limited.exitCode());
        assertEquals(1, occurrences(limited.stdout(), "\"kind\""));
        assertTrue(limited.stdout().contains("\"partial\":\"true\""));
        assertFalse(limited.stdout().contains(secret));

        FakeNuggetClient offsetClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput offset = this.nugget(offsetClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--sort", "id", "--offset", "1", "--limit", "1", "--json"}, "");
        assertEquals(0, offset.exitCode());
        assertEquals(1, occurrences(offset.stdout(), "\"kind\""));
        assertTrue(offset.stdout().contains("orders-1"));
        assertFalse(offset.stdout().contains("billing-1"));
        assertFalse(offset.stdout().contains("runtime-object"));

        FakeNuggetClient sourceCapClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        sourceCapClient.manyApplications = true;
        NuggetOutput sourceCapped = this.nugget(sourceCapClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "visibility=INTERNAL", "--json"}, "");
        assertEquals(0, sourceCapped.exitCode());
        assertTrue(sourceCapped.stdout().contains("runtime-object"));
        assertFalse(sourceCapped.stdout().contains("bulk-app-"));
        assertTrue(sourceCapped.stdout().contains("\"partial\":\"true\""));
    }

    /**
     * Verifies object and application list/detail commands, filter validation,
     * and ambiguous all-target detail rejection.
     */
    @Test
    void nuggetInspectionListsDetailsAndRejectsInvalidQueries() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient objectsClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput objects = this.nugget(objectsClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "objects", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "visibility=EXPORTED", "--sort", "service"}, "");
        assertEquals(0, objects.exitCode());
        assertTrue(objects.stdout().contains("orders-object"));
        assertFalse(objects.stdout().contains("runtime-object"));

        FakeNuggetClient offsetObjectsClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput offsetObjects = this.nugget(offsetObjectsClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret),
                fixedClock(), "nugget", "objects", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--offset", "1", "--json"}, "");
        assertEquals(0, offsetObjects.exitCode());
        assertTrue(offsetObjects.stdout().contains("runtime-object"));
        assertFalse(offsetObjects.stdout().contains("orders-object"));

        FakeNuggetClient appsClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput apps = this.nugget(appsClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "apps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "state=RUNNING", "--json"}, "");
        assertEquals(0, apps.exitCode());
        assertTrue(apps.stdout().contains("orders-1"));
        assertFalse(apps.stdout().contains("billing-1"));

        FakeNuggetClient detailClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput detail = this.nugget(detailClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "orders-object"}, "");
        assertEquals(0, detail.exitCode());
        assertTrue(detail.stdout().contains("descriptor"));
        assertFalse(detail.stdout().contains(secret));

        FakeNuggetClient invalidSortClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput invalidSort = this.nugget(invalidSortClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "objects", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--sort", "unknown"}, "");
        assertEquals(1, invalidSort.exitCode());
        assertTrue(invalidSort.stderr().contains("INVALID_ARGUMENT"));

        FakeNuggetClient invalidFilterClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput invalidFilter = this.nugget(invalidFilterClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "apps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--filter", "visibility=EXPORTED"}, "");
        assertEquals(1, invalidFilter.exitCode());
        assertTrue(invalidFilter.stderr().contains("INVALID_ARGUMENT"));

        FakeNuggetClient ambiguous = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput allTargetDetail = this.nugget(ambiguous, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "object", new String[] {"orders-object"}, "");
        assertEquals(1, allTargetDetail.exitCode());
        assertTrue(allTargetDetail.stderr().contains("TARGET_REQUIRED"));
    }

    /**
     * Verifies environment, disk, health, readiness, metrics, errors, all-target
     * partial behavior, truncation, and control-character sanitization.
     */
    @Test
    void nuggetInspectionStatusCommandsAreBoundedRedactedAndPartialAware() {
        String secret = "env-pin-sentinel";
        List<NuggetDiscovery.Record> records = List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF"));
        FakeNuggetClient client = new FakeNuggetClient(records);
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                new FixedTicker(1L),
                "nugget", "shell",
                "talk ABC\nauth --pin-env J7M_TEST_PIN\n"
                        + "env --width 160\n"
                        + "disk --width 120\n"
                        + "health --json\n"
                        + "readiness\n"
                        + "metrics\n"
                        + "errors\n"
                        + "talk all\nps --json\nexit\n");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().contains("JVM"));
        assertTrue(output.stdout().contains("ROLE"));
        assertTrue(output.stdout().contains("\"command\":\"health\""));
        assertTrue(output.stdout().contains("runtime.invocations"));
        assertTrue(output.stdout().contains("INTERNAL"));
        assertTrue(output.stdout().contains("NOT_AUTHENTICATED"));
        assertTrue(output.stdout().contains("\"partial\":\"true\""));
        assertFalse(output.stdout().contains(secret));
        assertFalse(output.stdout().contains("\u0000"));
        assertFalse(output.stderr().contains(secret));
    }

    /**
     * Verifies inspection detail and JSON renderers enforce local output caps
     * and report partial output truthfully when nested safe metadata is large.
     */
    @Test
    void nuggetInspectionJsonAndDetailRenderersAreLocallyBounded() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient jsonClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput json = this.nugget(jsonClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json", "large-object"}, "");
        assertEquals(0, json.exitCode());
        assertTrue(json.stdout().getBytes(StandardCharsets.UTF_8).length <= 128 * 1024 + 1);
        assertTrue(json.stdout().contains("\"partial\":\"true\""));
        assertTrue(json.stdout().contains("\"truncated\":\"true\""));
        assertFalse(json.stdout().contains(secret));

        FakeNuggetClient detailClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput detail = this.nugget(detailClient, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "large-object"}, "");
        assertEquals(0, detail.exitCode());
        assertTrue(detail.stdout().getBytes(StandardCharsets.UTF_8).length <= 128 * 1024 + 64);
        assertTrue(detail.stdout().contains("Partial output: true"));
        assertFalse(detail.stdout().contains(secret));
    }

    /**
     * Verifies providers compiled against the earlier CLI connection surface use
     * default inspection failures instead of surfacing {@code AbstractMethodError}.
     */
    @Test
    void nuggetInspectionDefaultsKeepOlderProvidersCompatible() {
        String secret = "env-pin-sentinel";
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.legacyConnection = true;

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", secret), fixedClock(),
                "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN", "--json"}, "");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().contains("UNAVAILABLE"));
        assertFalse(output.stderr().contains("AbstractMethodError"));
        assertFalse(output.stdout().contains(secret));
        assertFalse(output.stderr().contains(secret));
    }

    /**
     * Verifies all-target mode does not broadcast one PIN implicitly.
     */
    @Test
    void nuggetAuthRequiresOneConcreteTarget() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "discover\npin\nauth\nexit\n");

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("TARGET_REQUIRED"));
    }

    /**
     * Verifies blank and oversized PINs are rejected before cache insertion or
     * authentication.
     */
    @Test
    void nuggetRejectedPinsAreNotCachedOrSent() {
        FakeNuggetClient blank = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput blankOutput = this.nugget(blank, pinReader(" \t\n\u2003"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");

        assertEquals(2, blankOutput.exitCode());
        assertEquals(0, blank.authenticateCount());
        assertTrue(blankOutput.stderr().contains("PIN_UNAVAILABLE"));

        String huge = "x".repeat(257);
        FakeNuggetClient hugeEnv = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput hugeOutput = this.nugget(hugeEnv, pinReader("unused"), Map.of("J7M_TEST_PIN", huge), fixedClock(),
                "nugget", "auth", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN"}, "");

        assertEquals(2, hugeOutput.exitCode());
        assertEquals(0, hugeEnv.authenticateCount());
        assertFalse(hugeOutput.stdout().contains(huge));
        assertFalse(hugeOutput.stderr().contains(huge));
    }

    /**
     * Verifies cached credentials expire and no controlling terminal fails
     * without echoing a secret.
     */
    @Test
    void nuggetCredentialCacheExpiresAndNoTerminalFailsSafely() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        StepTicker ticker = new StepTicker(0L, Duration.ofMinutes(16L));
        NuggetOutput expired = this.nugget(client, pinReaderOnce("123456"), Map.of(), fixedClock(), ticker,
                "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        assertEquals(2, expired.exitCode());
        assertTrue(expired.stderr().contains("PIN_UNAVAILABLE"));

        FakeNuggetClient noTerminal = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput unavailable = this.nugget(noTerminal, pinReader(null), Map.of(), "nugget", "shell",
                "talk ABC\npin\nexit\n");
        assertEquals(2, unavailable.exitCode());
        assertTrue(unavailable.stderr().contains("PIN_UNAVAILABLE"));
    }

    /**
     * Verifies wall-clock jumps do not drive credential expiry.
     */
    @Test
    void nuggetCredentialCacheUsesMonotonicTicker() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        StepClock jumpingClock = new StepClock(Instant.parse("2026-01-01T00:00:00Z"), Duration.ofDays(365L));
        FixedTicker ticker = new FixedTicker(100L);
        NuggetOutput output = this.nugget(client, pinReaderOnce("123456"), Map.of(), jumpingClock, ticker,
                "nugget", "shell", "talk ABC\npin\nauth\nexit\n");

        assertEquals(0, output.exitCode());
        assertEquals(1, client.authenticateCount());
    }

    /**
     * Verifies authentication clears the send-path copy without erasing the
     * retained cache entry used by a later authentication command.
     */
    @Test
    void nuggetAuthenticationClearsWorkingPinCopyButRetainsCacheEntry() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReaderOnce("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\npin\nauth\nauth\nexit\n");

        assertEquals(0, output.exitCode());
        assertEquals(2, client.authenticateCount());
        for (char character : client.lastAuthenticationPin()) {
            assertEquals('\0', character);
        }
    }

    /**
     * Verifies expired cached PINs are cleaned by bounded activity that is not a
     * fetch of the same credential.
     */
    @Test
    void nuggetCredentialCacheCleansExpiredEntriesFromIndependentActivity() {
        NuggetCliConsole.CredentialCache cache = new NuggetCliConsole.CredentialCache();
        cache.put("nugget-a", "123456".toCharArray(), 0L);
        char[] stored = cache.storedPinForTests("nugget-a");
        cache.put("nugget-b", "abcdef".toCharArray(), Duration.ofMinutes(16L).toNanos());

        assertFalse(cache.contains("nugget-a"));
        assertTrue(cache.contains("nugget-b"));
        for (char character : stored) {
            assertEquals('\0', character);
        }

        NuggetCliConsole.CredentialCache many = new NuggetCliConsole.CredentialCache();
        for (int index = 0; index < 20; index++) {
            many.put("expired-" + index, "123456".toCharArray(), 0L);
        }
        assertEquals(16, many.cleanup(Duration.ofMinutes(16L).toNanos()));
        assertEquals(4, many.size());
        assertEquals(4, many.cleanup(Duration.ofMinutes(16L).toNanos()));
        assertEquals(0, many.size());

        NuggetCliConsole.CredentialCache bounded = new NuggetCliConsole.CredentialCache();
        for (int index = 0; index < 65; index++) {
            bounded.put("live-" + index, "123456".toCharArray(), 1L);
        }
        assertEquals(64, bounded.size());
        assertTrue(bounded.retainedPinBytes() <= 64 * 256);
        assertThrows(IllegalArgumentException.class, () -> bounded.put("blank", " \t\n\u2003".toCharArray(), 1L));
        assertThrows(IllegalArgumentException.class, () -> bounded.put("huge", "x".repeat(257).toCharArray(), 1L));
        assertEquals(0, bounded.cleanup(2L));
        assertEquals(64, bounded.size());
        bounded.clear();
        assertEquals(0, bounded.size());
        bounded.clear();
        assertEquals(0, bounded.size());
    }

    /**
     * Verifies continued CLI activity cleans expired credentials without relying
     * on a successful same-target credential fetch.
     */
    @Test
    void nuggetCredentialCacheExpiresDuringUnrelatedCommandsAndDiscovery() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents events = new RecordingEvents();
        StepTicker ticker = new StepTicker(0L, Duration.ofMinutes(16L));
        NuggetOutput output = this.nugget(client, pinReaderOnce("123456"), Map.of(), fixedClock(), ticker,
                events, "nugget", "shell", "discover\ntalk ABC\npin\nwhoami\ndiscover\nauth\nexit\n");

        assertEquals(2, output.exitCode());
        assertTrue(events.events().contains("credential-expired"));
        assertTrue(output.stderr().contains("PIN_UNAVAILABLE"));
        assertFalse(output.stdout().contains("123456"));
        assertFalse(output.stderr().contains("123456"));
    }

    /**
     * Verifies authorization denial remains distinct from admission success.
     */
    @Test
    void nuggetInfoAndStatusReportAuthorizationDenialSafely() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.authorized = false;

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nstatus --json\ninfo --json\nexit\n");

        assertEquals(2, output.exitCode());
        assertTrue(output.stdout().contains("NOT_AUTHORIZED"));
        assertFalse(output.stdout().contains("123456"));
        assertFalse(output.stderr().contains("123456"));
    }

    /**
     * Verifies multi-target status isolates failures and returns a partial
     * category when only some targets are admitted.
     */
    @Test
    void nuggetMultiTargetStatusReportsPartialFailure() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\ntalk all\nstatus --json\nexit\n");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().contains("\"partial\":\"true\""));
        assertTrue(output.stdout().contains("NOT_AUTHENTICATED"));
        assertFalse(output.stdout().contains("123456"));
    }

    /**
     * Verifies public JSON discovery output never exposes endpoint local
     * identifiers or secret-like endpoint text.
     */
    @Test
    void nuggetJsonDiscoveryOmitsSecretEndpointLocalId() {
        NuggetDiscovery.Record secretEndpointRecord = new NuggetDiscovery.Record(
                "nugget-a",
                "ABC",
                "runtime-nugget-a",
                NuggetDiscovery.EndpointDescriptor.local("SECRET-SENTINEL-local-token"),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                List.of("admin"),
                "123",
                "LOCAL",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:10:00Z"),
                NuggetDiscovery.Source.LOCAL_RECORD,
                NuggetDiscovery.Status.ACTIVE,
                List.of());
        FakeNuggetClient client = new FakeNuggetClient(List.of(secretEndpointRecord));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "nuggets", "--json", "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("\"endpoint\""));
        assertTrue(output.stdout().contains("\"local\":\"true\""));
        assertFalse(output.stdout().contains("SECRET-SENTINEL"));
        assertFalse(output.stdout().contains("localId"));
    }

    /**
     * Verifies all-target status work is capped before issuing unbounded target
     * work.
     */
    @Test
    void nuggetAllTargetStatusCapsRenderedTargets() {
        List<NuggetDiscovery.Record> records = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            records.add(record("nugget-" + index, "N" + index));
        }
        FakeNuggetClient client = new FakeNuggetClient(records);

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "status", "--json", "");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().contains("TARGET_LIMIT"));
        assertTrue(output.stdout().contains("\"partial\":\"true\""));
        assertFalse(output.stdout().contains("nugget-39"));
        assertTrue(client.connectedIds().isEmpty());
    }

    /**
     * Verifies a blocking target cannot keep an info command beyond the command
     * deadline and is reported as unavailable rather than authorization denial.
     */
    @Test
    void nuggetInfoCommandDeadlineClosesTargetAndShellContinues() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.blockStatus = true;
        client.statusEntered = new CountDownLatch(1);
        client.releaseStatus = new CountDownLatch(1);

        NuggetOutput output = this.nugget(client, pinReaderOnce("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\npin\nauth\ninfo --json\nwhoami\nexit\n");
        client.releaseStatus.countDown();

        assertEquals(3, output.exitCode());
        assertEquals(0L, client.statusEntered.getCount());
        assertEquals(1, client.authenticateCount());
        assertEquals(1, client.statusCallCount());
        assertEquals(1, client.connectionCloseCount());
        assertTrue(output.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(output.stdout().contains("admission accepted"));
        assertTrue(output.stdout().contains("connections 0"));
        assertFalse(output.stdout().contains("123456"));
        assertFalse(output.stderr().contains("123456"));
    }

    /**
     * Verifies command timeout cleanup does not wait behind a provider
     * {@code connect} call that is still blocked.
     *
     * @throws Exception if deterministic latch coordination is interrupted
     */
    @Test
    void nuggetInfoTimeoutCleanupDoesNotBlockBehindStuckConnect() throws Exception {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.blockConnect = true;
        client.connectEntered = new CountDownLatch(1);
        client.releaseConnect = new CountDownLatch(1);
        client.lateConnectionClosed = new CountDownLatch(1);

        NuggetOutput output = this.nugget(client, pinReaderOnce("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\ninfo --json --pin-env J7M_PIN\nwhoami\nexit\n");

        assertEquals(3, output.exitCode());
        assertEquals(0L, client.connectEntered.getCount());
        assertEquals(0, client.statusCallCount());
        assertEquals(1, client.closeCount());
        assertTrue(output.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(output.stdout().contains("connections 0"));

        client.releaseConnect.countDown();
        assertTrue(client.lateConnectionClosed.await(5L, TimeUnit.SECONDS));
        assertEquals(1, client.connectionCloseCount());
        assertFalse(output.stdout().contains("env-pin-sentinel"));
        assertFalse(output.stderr().contains("env-pin-sentinel"));
    }

    /**
     * Verifies object and application detail commands run provider detail calls
     * inside the command deadline and close the selected connection on timeout.
     */
    @Test
    void nuggetDetailCommandsUseCommandDeadlineAndKeepSelectedTarget() {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        objectClient.blockStatus = true;
        objectClient.statusEntered = new CountDownLatch(1);
        objectClient.releaseStatus = new CountDownLatch(1);

        NuggetOutput objectOutput = this.nugget(objectClient, pinReaderOnce("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\npin\nauth\nobject orders-object --json\nwhoami\nexit\n");
        objectClient.releaseStatus.countDown();

        assertEquals(3, objectOutput.exitCode());
        assertEquals(0L, objectClient.statusEntered.getCount());
        assertEquals(1, objectClient.connectionCloseCount());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(objectOutput.stdout().contains("target ABC"));
        assertTrue(objectOutput.stdout().contains("connections 0"));

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        appClient.blockStatus = true;
        appClient.statusEntered = new CountDownLatch(1);
        appClient.releaseStatus = new CountDownLatch(1);

        NuggetOutput appOutput = this.nugget(appClient, pinReaderOnce("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\npin\nauth\napp orders-1 --json\nwhoami\nexit\n");
        appClient.releaseStatus.countDown();

        assertEquals(3, appOutput.exitCode());
        assertEquals(0L, appClient.statusEntered.getCount());
        assertEquals(1, appClient.connectionCloseCount());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(appOutput.stdout().contains("target ABC"));
        assertTrue(appOutput.stdout().contains("connections 0"));
    }

    /**
     * Verifies object and application detail command deadlines include
     * environment-PIN admission and blocked connection establishment before the
     * detail provider is invoked.
     *
     * @throws Exception if deterministic latch coordination is interrupted
     */
    @Test
    void nuggetDetailConnectionDeadlineCoversAdmissionAndProviderIsNotCalled() throws Exception {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        objectClient.blockConnect = true;
        objectClient.connectEntered = new CountDownLatch(1);
        objectClient.releaseConnect = new CountDownLatch(1);
        objectClient.lateConnectionClosed = new CountDownLatch(1);

        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\nobject orders-object --json --pin-env J7M_PIN\nwhoami\nexit\n");

        assertEquals(3, objectOutput.exitCode());
        assertEquals(0L, objectClient.connectEntered.getCount());
        assertEquals(0, objectClient.objectDetailCallCount());
        assertEquals(0, objectClient.authenticateCount());
        assertEquals(1, objectClient.closeCount());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(objectOutput.stdout().contains("target ABC"));
        assertTrue(objectOutput.stdout().contains("connections 0"));
        assertFalse(objectOutput.stdout().contains("descriptor"));
        assertFalse(objectOutput.stdout().contains("env-pin-sentinel"));
        assertFalse(objectOutput.stderr().contains("env-pin-sentinel"));

        objectClient.releaseConnect.countDown();
        assertTrue(objectClient.lateConnectionClosed.await(5L, TimeUnit.SECONDS));
        assertEquals(1, objectClient.connectionCloseCount());

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        appClient.blockConnect = true;
        appClient.connectEntered = new CountDownLatch(1);
        appClient.releaseConnect = new CountDownLatch(1);
        appClient.lateConnectionClosed = new CountDownLatch(1);

        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L),
                "nugget", "shell", "talk ABC\napp orders-1 --json --pin-env J7M_PIN\nwhoami\nexit\n");

        assertEquals(3, appOutput.exitCode());
        assertEquals(0L, appClient.connectEntered.getCount());
        assertEquals(0, appClient.applicationDetailCallCount());
        assertEquals(0, appClient.authenticateCount());
        assertEquals(1, appClient.closeCount());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(appOutput.stdout().contains("target ABC"));
        assertTrue(appOutput.stdout().contains("connections 0"));
        assertFalse(appOutput.stdout().contains("declaredServices"));
        assertFalse(appOutput.stdout().contains("env-pin-sentinel"));
        assertFalse(appOutput.stderr().contains("env-pin-sentinel"));

        appClient.releaseConnect.countDown();
        assertTrue(appClient.lateConnectionClosed.await(5L, TimeUnit.SECONDS));
        assertEquals(1, appClient.connectionCloseCount());
    }

    /**
     * Verifies object and application detail command deadlines include
     * environment-PIN authentication and do not invoke the detail provider after
     * authentication times out.
     */
    @Test
    void nuggetDetailAuthenticationDeadlinePreventsProviderCall() throws Exception {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        objectClient.blockAuthenticate = true;
        objectClient.authenticateEntered = new CountDownLatch(1);
        objectClient.releaseAuthenticate = new CountDownLatch(1);
        objectClient.authenticateReturned = new CountDownLatch(1);
        CountDownLatch objectLateAdmission = new CountDownLatch(1);
        RecordingEvents objectEvents = new RecordingEvents();

        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L),
                admissionWatchingEvents(objectEvents, objectLateAdmission),
                "nugget", "shell", "talk ABC\nobject orders-object --json --pin-env J7M_PIN\nwhoami\nexit\n");

        assertEquals(3, objectOutput.exitCode());
        assertEquals(0L, objectClient.authenticateEntered.getCount());
        assertEquals(1, objectClient.authenticateCount());
        assertEquals(0, objectClient.objectDetailCallCount());
        assertEquals(1, objectClient.connectionCloseCount());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(objectOutput.stdout().contains("target ABC"));
        assertTrue(objectOutput.stdout().contains("connections 0"));
        assertTrue(objectOutput.stdout().contains("admission none"));
        assertFalse(objectOutput.stdout().contains("descriptor"));
        assertFalse(objectOutput.stdout().contains("env-pin-sentinel"));
        assertFalse(objectOutput.stderr().contains("env-pin-sentinel"));

        objectClient.releaseAuthenticate.countDown();
        assertTrue(objectClient.authenticateReturned.await(5L, TimeUnit.SECONDS));
        assertFalse(objectLateAdmission.await(250L, TimeUnit.MILLISECONDS));
        assertFalse(objectEvents.events().contains("auth-accepted"));
        assertEquals(0, objectClient.objectDetailCallCount());

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        appClient.blockAuthenticate = true;
        appClient.authenticateEntered = new CountDownLatch(1);
        appClient.releaseAuthenticate = new CountDownLatch(1);
        appClient.authenticateReturned = new CountDownLatch(1);
        CountDownLatch appLateAdmission = new CountDownLatch(1);
        RecordingEvents appEvents = new RecordingEvents();

        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L),
                admissionWatchingEvents(appEvents, appLateAdmission),
                "nugget", "shell", "talk ABC\napp orders-1 --json --pin-env J7M_PIN\nwhoami\nexit\n");

        assertEquals(3, appOutput.exitCode());
        assertEquals(0L, appClient.authenticateEntered.getCount());
        assertEquals(1, appClient.authenticateCount());
        assertEquals(0, appClient.applicationDetailCallCount());
        assertEquals(1, appClient.connectionCloseCount());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(appOutput.stdout().contains("target ABC"));
        assertTrue(appOutput.stdout().contains("connections 0"));
        assertTrue(appOutput.stdout().contains("admission none"));
        assertFalse(appOutput.stdout().contains("declaredServices"));
        assertFalse(appOutput.stdout().contains("env-pin-sentinel"));
        assertFalse(appOutput.stderr().contains("env-pin-sentinel"));

        appClient.releaseAuthenticate.countDown();
        assertTrue(appClient.authenticateReturned.await(5L, TimeUnit.SECONDS));
        assertFalse(appLateAdmission.await(250L, TimeUnit.MILLISECONDS));
        assertFalse(appEvents.events().contains("auth-accepted"));
        assertEquals(0, appClient.applicationDetailCallCount());
    }

    /**
     * Verifies command-local object and application target resolution is bounded
     * by the detail command deadline and cannot mutate the selected target after
     * late resolver completion.
     *
     * @throws Exception if deterministic latch coordination is interrupted
     */
    @Test
    void nuggetDetailOneShotTargetResolutionTimeoutKeepsSelectedTarget() throws Exception {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        objectClient.blockResolve = true;
        objectClient.blockedResolveValue = "DEF";
        objectClient.resolveEntered = new CountDownLatch(1);
        objectClient.releaseResolve = new CountDownLatch(1);
        objectClient.resolveReturned = new CountDownLatch(1);
        RecordingEvents objectEvents = new RecordingEvents();

        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L), objectEvents,
                "nugget", "shell",
                "talk ABC\nobject --target DEF --json --pin-env J7M_PIN orders-object\nwhoami\nexit\n");

        assertEquals(3, objectOutput.exitCode());
        assertEquals(0L, objectClient.resolveEntered.getCount());
        assertEquals(0, objectClient.authenticateCount());
        assertEquals(0, objectClient.objectDetailCallCount());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(objectOutput.stdout().contains("target ABC"));
        assertFalse(objectOutput.stdout().contains("descriptor"));
        objectClient.releaseResolve.countDown();
        assertTrue(objectClient.resolveReturned.await(5L, TimeUnit.SECONDS));
        assertEquals(1, occurrences(objectEvents.events().toString(), "target-select"));
        assertFalse(objectEvents.text().contains("nugget-b"));

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        appClient.blockResolve = true;
        appClient.blockedResolveValue = "DEF";
        appClient.resolveEntered = new CountDownLatch(1);
        appClient.releaseResolve = new CountDownLatch(1);
        appClient.resolveReturned = new CountDownLatch(1);
        RecordingEvents appEvents = new RecordingEvents();

        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), new FixedTicker(1L), appEvents,
                "nugget", "shell",
                "talk ABC\napp --target DEF --json --pin-env J7M_PIN orders-1\nwhoami\nexit\n");

        assertEquals(3, appOutput.exitCode());
        assertEquals(0L, appClient.resolveEntered.getCount());
        assertEquals(0, appClient.authenticateCount());
        assertEquals(0, appClient.applicationDetailCallCount());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(appOutput.stdout().contains("target ABC"));
        assertFalse(appOutput.stdout().contains("declaredServices"));
        appClient.releaseResolve.countDown();
        assertTrue(appClient.resolveReturned.await(5L, TimeUnit.SECONDS));
        assertEquals(1, occurrences(appEvents.events().toString(), "target-select"));
        assertFalse(appEvents.text().contains("nugget-b"));
    }

    /**
     * Verifies the same absolute detail deadline is checked after target
     * resolution and before connection, authentication, or provider detail work.
     */
    @Test
    void nuggetDetailDeadlineAfterResolutionPreventsNextStage() {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(),
                new StepTicker(1L, Duration.ofSeconds(1L)),
                NuggetCliEventSink.none(),
                "nugget", "object", new String[] {"--target", "ABC", "--json", "--pin-env", "J7M_PIN",
                        "orders-object"}, "");

        assertEquals(3, objectOutput.exitCode());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertEquals(0, objectClient.authenticateCount());
        assertEquals(0, objectClient.objectDetailCallCount());
        assertTrue(objectClient.connectedIds().isEmpty());

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(),
                new StepTicker(1L, Duration.ofSeconds(1L)),
                NuggetCliEventSink.none(),
                "nugget", "app", new String[] {"--target", "ABC", "--json", "--pin-env", "J7M_PIN",
                        "orders-1"}, "");

        assertEquals(3, appOutput.exitCode());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertEquals(0, appClient.authenticateCount());
        assertEquals(0, appClient.applicationDetailCallCount());
        assertTrue(appClient.connectedIds().isEmpty());
    }

    /**
     * Verifies successful command-local object and application detail targets do
     * not replace the shell's selected target.
     */
    @Test
    void nuggetDetailOneShotTargetSuccessKeepsSelectedTarget() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), new FixedTicker(1L),
                "nugget", "shell",
                "talk ABC\n"
                        + "object --target DEF --json --pin-env J7M_PIN orders-object\n"
                        + "app --target DEF --json --pin-env J7M_PIN orders-1\n"
                        + "whoami\nexit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("\"command\":\"object\""));
        assertTrue(output.stdout().contains("\"command\":\"app\""));
        assertTrue(output.stdout().contains("descriptor"));
        assertTrue(output.stdout().contains("declaredServices"));
        assertTrue(output.stdout().contains("target ABC"));
        assertFalse(output.stdout().contains("env-pin-sentinel"));
    }

    /**
     * Verifies object and application detail results are not published after
     * the original command deadline expires between safe map construction and
     * output rendering.
     */
    @Test
    void nuggetDetailPublicationDeadlinePreventsOutput() {
        long expired = Duration.ofSeconds(3L).toNanos();
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        SettableTicker objectTicker = new SettableTicker(1L);
        objectClient.detailPublicationTicker = objectTicker;
        objectClient.detailPublicationExpiryNanos = expired;
        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), objectTicker,
                "nugget", "shell",
                "talk ABC\nauth --pin-env J7M_PIN\nobject orders-object --json\nexit\n");

        assertEquals(3, objectOutput.exitCode());
        assertEquals(1, objectClient.objectDetailCallCount());
        assertTrue(objectOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertFalse(objectOutput.stdout().contains("descriptor"));
        assertFalse(objectOutput.stdout().contains("env-pin-sentinel"));

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        SettableTicker appTicker = new SettableTicker(1L);
        appClient.detailPublicationTicker = appTicker;
        appClient.detailPublicationExpiryNanos = expired;
        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"),
                Map.of("J7M_PIN", "env-pin-sentinel"), fixedClock(), appTicker,
                "nugget", "shell",
                "talk ABC\nauth --pin-env J7M_PIN\napp orders-1 --json\nexit\n");

        assertEquals(3, appOutput.exitCode());
        assertEquals(1, appClient.applicationDetailCallCount());
        assertTrue(appOutput.stdout().contains("DEADLINE_EXCEEDED"));
        assertFalse(appOutput.stdout().contains("declaredServices"));
        assertFalse(appOutput.stdout().contains("env-pin-sentinel"));
    }

    /**
     * Verifies connection cancellation is scoped to the timed-out attempt and
     * does not poison a newer command for the same nugget id.
     *
     * @throws Exception if deterministic latch coordination is interrupted
     */
    @Test
    void nuggetLateCanceledConnectDoesNotPoisonNewerSameIdCommand() throws Exception {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.blockedConnectsRemaining = 1;
        client.connectEntered = new CountDownLatch(1);
        client.releaseConnect = new CountDownLatch(1);
        client.lateConnectionClosed = new CountDownLatch(2);

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), new FixedTicker(1L),
                "nugget", "shell",
                "talk ABC\n"
                        + "info --json --pin-env J7M_PIN\n"
                        + "object orders-object --json --pin-env J7M_PIN\n"
                        + "whoami\nexit\n");

        assertEquals(3, output.exitCode());
        assertEquals(0L, client.connectEntered.getCount());
        assertEquals(1, client.authenticateCount());
        assertEquals(1, client.objectDetailCallCount());
        assertTrue(output.stdout().contains("DEADLINE_EXCEEDED"));
        assertTrue(output.stdout().contains("\"command\":\"object\""));
        assertTrue(output.stdout().contains("descriptor"));
        assertTrue(output.stdout().contains("target ABC"));
        assertTrue(output.stdout().contains("connections 1"));

        client.releaseConnect.countDown();
        assertTrue(client.lateConnectionClosed.await(5L, TimeUnit.SECONDS));
        assertEquals(2, client.connectionCloseCount());
        assertEquals(1, client.authenticateCount());
        assertEquals(1, client.objectDetailCallCount());

        String source = Files.readString(cliConsoleSource(), StandardCharsets.UTF_8);
        assertFalse(source.contains("canceledConnectionIds"));
        assertTrue(source.contains("ConnectionAttempt"));
    }

    /**
     * Verifies detail provider failures are normalized and do not leak raw
     * provider exception text.
     */
    @Test
    void nuggetDetailProviderFailureMapsSafely() {
        FakeNuggetClient objectClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        objectClient.failObjectDetail = true;
        NuggetOutput objectOutput = this.nugget(objectClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json", "orders-object"}, "");

        assertEquals(4, objectOutput.exitCode());
        assertTrue(objectOutput.stdout().contains("\"code\":\"INTERNAL\""));
        assertFalse(objectOutput.stdout().contains("raw object detail sentinel"));
        assertFalse(objectOutput.stderr().contains("raw object detail sentinel"));

        FakeNuggetClient appClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        appClient.failApplicationDetail = true;
        NuggetOutput appOutput = this.nugget(appClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "app", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json", "orders-1"}, "");

        assertEquals(4, appOutput.exitCode());
        assertTrue(appOutput.stdout().contains("\"code\":\"INTERNAL\""));
        assertFalse(appOutput.stdout().contains("raw application detail sentinel"));
        assertFalse(appOutput.stderr().contains("raw application detail sentinel"));
    }

    /**
     * Verifies command work uses bounded executor capacity and rejects excess
     * queued work instead of accumulating an unbounded backlog.
     *
     * @throws Exception if executor cleanup is interrupted
     */
    @Test
    void nuggetCommandExecutorRejectsUnboundedQueuedWork() throws Exception {
        ExecutorService executor = NuggetCliConsole.commandExecutor();
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> accepted = new ArrayList<>();
        try {
            for (int index = 0; index < 64; index++) {
                try {
                    accepted.add(executor.submit(() -> {
                        release.await(1L, TimeUnit.MINUTES);
                        return null;
                    }));
                } catch (RejectedExecutionException exception) {
                    assertTrue(accepted.size() < 64);
                    return;
                }
            }
            throw new AssertionError("command executor accepted an unbounded work backlog");
        } finally {
            release.countDown();
            for (Future<?> future : accepted) {
                future.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    /**
     * Verifies remote insecure endpoint PIN submission is rejected before the
     * client receives the credential.
     */
    @Test
    void nuggetAuthRejectsRemoteInsecureEndpoint() {
        FakeNuggetClient client = new FakeNuggetClient(
                List.of(remoteRecord("nugget-r", "REMOTE")),
                NuggetCliConnectionSecurity.insecure());
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk REMOTE\npin\nauth\nexit\n");

        assertEquals(2, output.exitCode());
        assertTrue(output.stderr().contains("INSECURE_TRANSPORT"));
        assertEquals(0, client.authenticateCount());
    }

    /**
     * Verifies forged discovery metadata cannot authorize PIN submission when the
     * actual connection reports insecure transport state.
     */
    @Test
    void nuggetAuthUsesTrustedConnectionSecurityNotDiscoveryMetadata() {
        FakeNuggetClient client = new FakeNuggetClient(
                List.of(record("nugget-a", "ABC")),
                NuggetCliConnectionSecurity.insecure());
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");

        assertEquals(2, output.exitCode());
        assertTrue(output.stderr().contains("INSECURE_TRANSPORT"));
        assertEquals(0, client.authenticateCount());
        assertFalse(output.stdout().contains("123456"));
        assertFalse(output.stderr().contains("123456"));
    }

    /**
     * Verifies secure remote and explicitly trusted local connections may submit
     * PIN material.
     */
    @Test
    void nuggetAuthAllowsSecureRemoteAndTrustedLocalConnections() {
        FakeNuggetClient remote = new FakeNuggetClient(
                List.of(remoteRecord("nugget-r", "REMOTE")),
                NuggetCliConnectionSecurity.secureRemote("mtls-admin"));
        NuggetOutput remoteOutput = this.nugget(remote, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk REMOTE\npin\nauth\nexit\n");
        assertEquals(0, remoteOutput.exitCode());
        assertEquals(1, remote.authenticateCount());

        FakeNuggetClient local = new FakeNuggetClient(
                List.of(record("nugget-a", "ABC")),
                NuggetCliConnectionSecurity.trustedLocal());
        NuggetOutput localOutput = this.nugget(local, pinReader("123456"), Map.of(), "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        assertEquals(0, localOutput.exitCode());
        assertEquals(1, local.authenticateCount());
    }

    /**
     * Verifies reconnect preserves the exact selected identity.
     */
    @Test
    void nuggetReconnectUsesExactIdentity() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents events = new RecordingEvents();
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), events, "nugget", "shell",
                "talk ABC\npin\nauth\nreconnect\nexit\n");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("connected ABC"));
        assertEquals(List.of("nugget-a", "nugget-a"), client.connectedIds());
        assertTrue(events.events().contains("reconnect-requested"));
        assertTrue(events.events().contains("reconnect-succeeded"));
    }

    /**
     * Verifies reconnect failures emit safe specific events without generic
     * duplicates.
     */
    @Test
    void nuggetReconnectFailuresEmitSafeEvents() {
        FakeNuggetClient notFound = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        notFound.resolutionSequence.add(FakeNuggetClient.resolution(
                NuggetDiscovery.ResolutionStatus.RESOLVED, List.of(record("nugget-a", "ABC"))));
        notFound.resolutionSequence.add(FakeNuggetClient.resolution(NuggetDiscovery.ResolutionStatus.NOT_FOUND, List.of()));
        RecordingEvents notFoundEvents = new RecordingEvents();
        NuggetOutput missing = this.nugget(notFound, pinReader("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                notFoundEvents, "nugget", "shell", "talk ABC\nreconnect\nexit\n");
        assertEquals(3, missing.exitCode());
        assertTrue(notFoundEvents.events().contains("reconnect-requested"));
        assertTrue(notFoundEvents.events().contains("reconnect-rejected"));
        assertFalse(notFoundEvents.events().contains("command-failure"));

        FakeNuggetClient incompatible = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        incompatible.resolutionSequence.add(FakeNuggetClient.resolution(
                NuggetDiscovery.ResolutionStatus.RESOLVED, List.of(record("nugget-a", "ABC"))));
        incompatible.resolutionSequence.add(FakeNuggetClient.resolution(NuggetDiscovery.ResolutionStatus.RESOLVED,
                List.of(record("nugget-a", "ABC", NuggetDiscovery.Status.ACTIVE,
                        NuggetDiscovery.Compatibility.INCOMPATIBLE))));
        RecordingEvents incompatibleEvents = new RecordingEvents();
        NuggetOutput incompatibleOutput = this.nugget(incompatible, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), incompatibleEvents, "nugget", "shell", "talk ABC\nreconnect\nexit\n");
        assertEquals(1, incompatibleOutput.exitCode());
        assertTrue(incompatibleEvents.events().contains("reconnect-rejected"));
        assertFalse(incompatibleEvents.events().contains("command-failure"));

        FakeNuggetClient failed = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        failed.connectFailure = new NuggetCliFailure(3, "TIMEOUT", "connect timeout sentinel");
        RecordingEvents failedEvents = new RecordingEvents();
        NuggetOutput failedOutput = this.nugget(failed, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), failedEvents, "nugget", "shell", "talk ABC\nreconnect\nexit\n");
        assertEquals(3, failedOutput.exitCode());
        assertTrue(failedEvents.events().contains("reconnect-failed"));
        assertFalse(failedEvents.text().contains("connect timeout sentinel"));
    }

    /**
     * Verifies a selected target that disappears after refresh is not silently
     * replaced by another nugget with the same name.
     */
    @Test
    void nuggetDiscoveryRefreshClearsDisappearedTarget() {
        List<NuggetDiscovery.Record> initial = List.of(record("nugget-a", "ABC"));
        FakeNuggetClient client = new FakeNuggetClient(initial);
        client.discoverySequence.add(initial);
        client.discoverySequence.add(List.of(record("nugget-b", "ABC")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                "discover\ntalk ABC\npin\ndiscover\nauth\nexit\n");

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("TARGET_REQUIRED"));
        assertEquals(0, client.authenticateCount());
    }

    /**
     * Verifies malformed command syntax and command bounds.
     */
    @Test
    void nuggetParserRejectsUnknownMalformedAndOversizedCommands() {
        FakeNuggetClient client = new FakeNuggetClient(List.of());
        NuggetOutput unknown = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "unknown", "");
        assertEquals(1, unknown.exitCode());

        NuggetOutput quote = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell", "talk \"abc\nexit\n");
        assertEquals(1, quote.exitCode());
        assertTrue(quote.stderr().contains("malformed quoting"));

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 4100; index++) {
            builder.append('a');
        }
        NuggetOutput longCommand = this.nugget(client, pinReader("123456"), Map.of(), "nugget", "shell",
                builder + "\nexit\n");
        assertEquals(1, longCommand.exitCode());
        assertTrue(longCommand.stderr().contains("too long"));
    }

    /**
     * Verifies command-specific option grammar rejects malformed input before
     * target mutation, authentication, connection, or provider execution.
     */
    @Test
    void nuggetParserRejectsInvalidOptionsBeforeSideEffects() {
        String[][] invalidCommands = {
                {"ps", "--limt", "1"},
                {"discover", "--limit", "1"},
                {"ps", "--limit", "--json"},
                {"ps", "--filter"},
                {"ps", "--limit", "1", "--limit", "2"},
                {"ps", "--sort", "id", "--sort", "state"},
                {"ps", "--target", "ABC", "--target", "DEF"},
                {"auth", "--pin-env", "J7M_TEST_PIN", "--pin-env", "J7M_OTHER_PIN"},
                {"ps", "--json", "--json"},
                {"ps", "--asc", "--desc"},
                {"ps", "--limit", "999999999999"},
                {"ps", "--limit", "501"}
        };
        for (String[] command : invalidCommands) {
            FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
            NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                    fixedClock(), "nugget", command[0], Arrays.copyOfRange(command, 1, command.length), "");
            assertEquals(1, output.exitCode(), Arrays.toString(command));
            assertEquals(0, client.authenticateCount(), Arrays.toString(command));
            assertEquals(0, client.statusCallCount(), Arrays.toString(command));
            assertTrue(client.connectedIds().isEmpty(), Arrays.toString(command));
            assertFalse(output.stderr().contains("env-pin-sentinel"), Arrays.toString(command));
        }

        FakeNuggetClient oneShotClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput oneShot = this.nugget(oneShotClient, pinReader("unused"), Map.of(),
                "nugget", "ps", "--filter", "");
        FakeNuggetClient interactiveClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput interactive = this.nugget(interactiveClient, pinReader("unused"), Map.of(),
                "nugget", "shell", "ps --filter\nexit\n");
        assertEquals(oneShot.exitCode(), interactive.exitCode());
        assertTrue(oneShot.stderr().contains("USAGE"));
        assertTrue(interactive.stderr().contains("USAGE"));

        FakeNuggetClient validClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput validObjectJson = this.nugget(validClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json", "orders-object"}, "");
        assertEquals(0, validObjectJson.exitCode());
        assertTrue(validObjectJson.stdout().contains("\"command\":\"object\""));

        NuggetOutput help = this.nugget(new FakeNuggetClient(List.of()), pinReader("unused"), Map.of(),
                "nugget", "help", "");
        assertEquals(0, help.exitCode());
        assertTrue(help.stdout().contains("--filter key=value"));
        assertTrue(help.stdout().contains("--json"));
    }

    /**
     * Verifies JSON errors are single stdout envelopes and diagnostics stay off
     * stdout.
     */
    @Test
    void nuggetJsonErrorUsesStructuredEnvelope() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(),
                "nugget", "talk", "DUP", "--json", "");

        assertEquals(1, output.exitCode());
        assertTrue(output.stdout().startsWith("{\"version\":1,\"ok\":false"));
        assertFalse(output.stdout().contains("J7M >"));
    }

    /**
     * Verifies malformed provider messages cannot corrupt JSON or leak control
     * characters.
     */
    @Test
    void nuggetJsonHardensProviderMessages() {
        FakeNuggetClient client = new FakeNuggetClient(List.of());
        client.discoveryFailure = new NuggetCliFailure(3, "BAD\"CODE", "bad\u0000message\nwith\\slash\"quote");
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(),
                "nugget", "discover", "--json", "");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().startsWith("{\"version\":1,\"ok\":false"));
        assertFalse(output.stdout().contains("\u0000"));
        assertFalse(output.stdout().contains("\nwith"));
        assertTrue(output.stdout().contains("\\\\slash"));
        assertTrue(output.stdout().contains("\\\"quote"));
    }

    /**
     * Verifies bounded inspection JSON keeps mandatory fields ahead of bulk
     * payload and remains deterministic under truncation.
     */
    @Test
    void nuggetInspectionJsonIsBoundedParseableAndTruthful() {
        FakeNuggetClient hugeClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        hugeClient.hugeApplications = true;
        NuggetOutput first = this.nugget(hugeClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json"}, "");

        FakeNuggetClient repeatedHugeClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        repeatedHugeClient.hugeApplications = true;
        NuggetOutput second = this.nugget(repeatedHugeClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--json"}, "");

        assertEquals(0, first.exitCode());
        assertEquals(first.stdout(), second.stdout());
        assertBoundedJsonEnvelope(first.stdout(), "ps", true, true);
        assertTrue(first.stdout().indexOf("\"partial\":\"true\"") < first.stdout().indexOf("\"targets\""));
        assertTrue(first.stdout().indexOf("\"truncated\":\"true\"") < first.stdout().indexOf("\"targets\""));

        List<NuggetDiscovery.Record> records = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            records.add(record("nugget-" + index, "N" + index));
        }
        FakeNuggetClient allTargetClient = new FakeNuggetClient(records);
        NuggetOutput allTarget = this.nugget(allTargetClient, pinReader("unused"), Map.of(),
                "nugget", "ps", "--json", "");
        assertEquals(3, allTarget.exitCode());
        assertBoundedJsonEnvelope(allTarget.stdout(), "ps", true, false);
        assertTrue(allTarget.stdout().contains("TARGET_LIMIT"));

        FakeNuggetClient partialSource = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        partialSource.manyApplications = true;
        NuggetOutput partial = this.nugget(partialSource, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--filter", "visibility=INTERNAL", "--json"}, "");
        assertEquals(0, partial.exitCode());
        assertBoundedJsonEnvelope(partial.stdout(), "ps", true, false);
    }

    /**
     * Verifies human table rendering uses UTF-8 byte bounds, preserves valid
     * Unicode, sanitizes controls, and reports omitted rows.
     */
    @Test
    void nuggetHumanTablesUseUtf8ByteBudgetAndSanitizeControls() {
        FakeNuggetClient unicodeClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        unicodeClient.unicodeObjects = true;
        NuggetOutput unicode = this.nugget(unicodeClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "objects", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--width", "120"}, "");

        assertEquals(0, unicode.exitCode());
        byte[] bytes = unicode.stdout().getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 128 * 1024 + "Partial output: true\n".getBytes(StandardCharsets.UTF_8).length);
        assertEquals(unicode.stdout(), new String(bytes, StandardCharsets.UTF_8));
        assertTrue(unicode.stdout().contains("latin-é-object"));
        assertTrue(unicode.stdout().contains("greek-Δοκιμή"));
        assertTrue(unicode.stdout().contains("cyrillic-Журнал"));
        assertTrue(unicode.stdout().contains("cjk-存储"));
        assertTrue(unicode.stdout().contains("emoji-😀"));
        assertTrue(unicode.stdout().contains("combining-é"));
        assertFalse(unicode.stdout().contains("\u001B"));

        FakeNuggetClient cappedClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        cappedClient.hugeApplications = true;
        NuggetOutput capped = this.nugget(cappedClient, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "ps", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--width", "240"}, "");

        assertEquals(0, capped.exitCode());
        assertTrue(capped.stdout().getBytes(StandardCharsets.UTF_8).length
                <= 128 * 1024 + "Partial output: true\n".getBytes(StandardCharsets.UTF_8).length);
        assertTrue(capped.stdout().contains("Partial output: true"));
    }

    /**
     * Verifies a single hostile provider field is rendered through bounded
     * incremental human-table sanitization instead of whole-field copies.
     *
     * @throws Exception if the source sentinel cannot be read
     */
    @Test
    void nuggetHumanTablesDoNotSanitizeWholeHostileFields() throws Exception {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.hostileHugeObject = true;

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "objects", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--width", "120"}, "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().getBytes(StandardCharsets.UTF_8).length < 4096);
        assertEquals(output.stdout(), new String(output.stdout().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(output.stdout().contains("..."));
        assertFalse(output.stdout().contains("\u001B"));

        NuggetOutput detail = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "object", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "hostile-detail"}, "");

        assertEquals(0, detail.exitCode());
        assertTrue(detail.stdout().getBytes(StandardCharsets.UTF_8).length
                <= 128 * 1024 + "Partial output: true\n".getBytes(StandardCharsets.UTF_8).length);
        assertEquals(detail.stdout(), new String(detail.stdout().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(detail.stdout().contains("Partial output: true"));
        assertFalse(detail.stdout().contains("\u001B"));

        String source = Files.readString(cliConsoleSource(), StandardCharsets.UTF_8);
        assertFalse(source.contains("display(String.valueOf(row.getOrDefault"));
        assertFalse(source.contains("String text = truncate(value, width);"));
        assertFalse(source.contains(".getBytes(StandardCharsets.UTF_8)"));
        assertFalse(source.contains("display(summary."));
        assertFalse(source.contains("display(entry."));
        assertFalse(source.contains("String.join(\",\", summary.interfaces())"));
        assertFalse(source.contains("canonicalLabels("));
        assertTrue(source.contains("appendCellValue"));
        assertTrue(source.contains("sanitizedCodePointAt"));
    }

    /**
     * Verifies human empty inspection output still exposes partial provider
     * state when filtering removes all rows.
     */
    @Test
    void nuggetHumanErrorsEmptyFilteredPartialShowsPartialNotice() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.partialRecentErrors = true;

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "errors", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--component", "missing"}, "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("No recent errors."));
        assertTrue(output.stdout().contains("Partial output: true"));
    }

    /**
     * Verifies human empty metrics output exposes partial provider state.
     */
    @Test
    void nuggetHumanMetricsEmptyPartialShowsPartialNotice() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.partialMetrics = true;

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "metrics", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN"}, "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("No metrics data."));
        assertTrue(output.stdout().contains("Partial output: true"));
    }

    /**
     * Verifies human metrics label maps are rendered from a bounded map subset
     * without copying or sorting the provider's full key set.
     *
     * @throws Exception if the source sentinel cannot be read
     */
    @Test
    void nuggetHumanMetricsDoNotCopyOversizedLabelMaps() throws Exception {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.hostileHugeLabels = true;

        NuggetOutput output = this.nugget(client, pinReader("unused"), Map.of("J7M_TEST_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "metrics", new String[] {"--target", "ABC", "--pin-env", "J7M_TEST_PIN",
                        "--width", "120"}, "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().getBytes(StandardCharsets.UTF_8).length < 4096);
        assertEquals(output.stdout(), new String(output.stdout().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(output.stdout().contains("..."));
        assertFalse(output.stdout().contains("\u001B"));

        String source = Files.readString(cliConsoleSource(), StandardCharsets.UTF_8);
        assertFalse(source.contains("new ArrayList<>(values.keySet())"));
        assertFalse(source.contains("keys.sort("));
        assertTrue(source.contains("MAX_TABLE_MAP_INSPECTED_ENTRIES"));
        assertTrue(source.contains("mapCellInsertionIndex"));
    }

    /**
     * Verifies safe operational events are emitted without credential material.
     */
    @Test
    void nuggetEventsCoverSessionDiscoveryTargetAuthAndClear() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents events = new RecordingEvents();
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                events, "nugget", "shell", "discover\ntalk ABC\npin\nauth\npin clear\nexit\n");

        assertEquals(0, output.exitCode());
        assertTrue(events.events().contains("session-start"));
        assertTrue(events.events().contains("discovery-refresh"));
        assertTrue(events.events().contains("target-select"));
        assertTrue(events.events().contains("connect"));
        assertTrue(events.events().contains("auth-accepted"));
        assertTrue(events.events().contains("credential-clear"));
        assertTrue(events.events().contains("session-end"));
        assertFalse(events.text().contains("123456"));
    }

    /**
     * Verifies command-failure operational events cover normalized failure
     * categories and remain redacted.
     */
    @Test
    void nuggetCommandFailureEventsAreSafeAndDoNotAlterResults() {
        FakeNuggetClient unavailable = new FakeNuggetClient(List.of());
        RecordingEvents unavailableEvents = new RecordingEvents();
        NuggetOutput providerUnavailable = this.nugget(NuggetCliClient.unavailable(),
                pinReader("123456"), Map.of(), fixedClock(), new FixedTicker(1L), unavailableEvents,
                "nugget", "discover", "");
        assertEquals(3, providerUnavailable.exitCode());
        assertTrue(unavailableEvents.events().contains("command-failure"));
        assertTrue(unavailableEvents.text().contains("UNAVAILABLE"));

        RecordingEvents usageEvents = new RecordingEvents();
        NuggetOutput usage = this.nugget(unavailable, pinReader("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                usageEvents, "nugget", "unknown", "");
        assertEquals(1, usage.exitCode());
        assertTrue(usageEvents.text().contains("usage"));

        FakeNuggetClient denied = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        denied.authorized = false;
        RecordingEvents deniedEvents = new RecordingEvents();
        NuggetOutput deniedOutput = this.nugget(denied, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), deniedEvents, "nugget", "shell",
                "talk ABC\npin\nauth\ninfo\nexit\n");
        assertEquals(2, deniedOutput.exitCode());
        assertTrue(deniedEvents.events().contains("command-failure"));
        assertTrue(deniedEvents.text().contains("DENIED"));

        FakeNuggetClient partial = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));
        RecordingEvents partialEvents = new RecordingEvents();
        NuggetOutput partialOutput = this.nugget(partial, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), partialEvents, "nugget", "shell",
                "talk ABC\npin\nauth\ntalk all\nstatus\nexit\n");
        assertEquals(3, partialOutput.exitCode());
        assertTrue(partialEvents.text().contains("PARTIAL"));

        FakeNuggetClient internal = new FakeNuggetClient(List.of());
        internal.discoveryFailure = new IllegalStateException("raw internal sentinel");
        RecordingEvents internalEvents = new RecordingEvents();
        NuggetOutput internalOutput = this.nugget(internal, pinReader("123456"), Map.of(), fixedClock(),
                new FixedTicker(1L), internalEvents, "nugget", "discover", new String[] {"--json"}, "");
        assertEquals(4, internalOutput.exitCode());
        assertTrue(internalEvents.text().contains("INTERNAL"));
        assertFalse(internalOutput.stdout().contains("raw internal sentinel"));
        assertFalse(internalEvents.text().contains("raw internal sentinel"));

        RecordingEvents rejectedEvents = new RecordingEvents();
        FakeNuggetClient rejected = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetOutput rejectedOutput = this.nugget(rejected, pinReader("wrong-pin"), Map.of(), fixedClock(),
                new FixedTicker(1L), rejectedEvents, "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        assertEquals(2, rejectedOutput.exitCode());
        assertTrue(rejectedEvents.events().contains("auth-rejected"));
        assertFalse(rejectedEvents.events().contains("command-failure"));
        assertFalse(rejectedEvents.text().contains("wrong-pin"));
    }

    /**
     * Verifies command parsing, JSON failures, confirmation input, environment
     * PINs, event metadata, and rejected inline-looking secrets do not retain
     * raw command or secret material.
     */
    @Test
    void nuggetCliSecretLikeCommandInputsAreNotRetainedOrEchoed() {
        String directPin = "direct-pin-sentinel";
        String envPin = "env-pin-sentinel";
        String confirmationSecret = "confirmation-secret-sentinel";
        String inlineSecret = "inline-secret-sentinel";
        String rejectedPin = "rejected-pin-sentinel";

        FakeNuggetClient parserClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents parserEvents = new RecordingEvents();
        NuggetOutput parser = this.nugget(parserClient, pinReader(directPin), Map.of(), fixedClock(), new FixedTicker(1L),
                parserEvents, "nugget", "shell",
                "unknown " + inlineSecret + "\n"
                        + "auth --json " + inlineSecret + "\n"
                        + "talk ABC\npin\nauth\nexit\n");
        String parserText = parser.stdout() + parser.stderr() + parserEvents.text();
        assertFalse(parserText.contains(directPin));
        assertFalse(parserText.contains(inlineSecret));

        FakeNuggetClient confirmClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents confirmEvents = new RecordingEvents();
        NuggetOutput confirmation = this.nugget(confirmClient, pinReader("unused"), Map.of("J7M_PIN", envPin),
                fixedClock(), new FixedTicker(1L), confirmEvents, "nugget", "shutdown-nugget",
                new String[] {"ABC", "--target", "ABC", "--pin-env", "J7M_PIN",
                        "--confirm", confirmationSecret, "--json"}, "");
        String confirmationText = confirmation.stdout() + confirmation.stderr() + confirmEvents.text();
        assertFalse(confirmationText.contains(envPin));
        assertFalse(confirmationText.contains(confirmationSecret));

        FakeNuggetClient rejectedClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        RecordingEvents rejectedEvents = new RecordingEvents();
        NuggetOutput rejected = this.nugget(rejectedClient, pinReader(rejectedPin), Map.of(), fixedClock(),
                new FixedTicker(1L), rejectedEvents, "nugget", "shell",
                "talk ABC\npin\nauth\nexit\n");
        String rejectedText = rejected.stdout() + rejected.stderr() + rejectedEvents.text();
        assertFalse(rejectedText.contains(rejectedPin));
        assertTrue(rejectedEvents.events().contains("auth-rejected"));
    }

    /**
     * Verifies lifecycle start dry-run uses one concrete target and publishes a
     * bounded JSON result without mutating fake CLI-local state.
     */
    @Test
    void nuggetLifecycleStartDryRunUsesProviderResult() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "start",
                new String[] {"org.j7m.test:orders:1.0.0", "--dry-run", "--target", "ABC",
                        "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(0, output.exitCode());
        assertEquals(1, client.lifecycleCallCount());
        assertTrue(output.stdout().contains("\"action\":\"START\""));
        assertTrue(output.stdout().contains("\"dryRun\":\"true\""));
        assertTrue(output.stdout().contains("\"outcome\":\"DRY_RUN\""));
        assertFalse(output.stdout().contains("env-pin-sentinel"));
    }

    /**
     * Verifies all-target lifecycle execution is rejected before provider
     * mutation.
     */
    @Test
    void nuggetLifecycleRejectsAllTargetBeforeMutation() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "drain",
                new String[] {"orders-1", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(1, output.exitCode());
        assertEquals(0, client.lifecycleCallCount());
        assertTrue(output.stdout().contains("TARGET_REQUIRED"));
    }

    /**
     * Verifies a lifecycle one-shot target failure preserves the selected
     * interactive target.
     */
    @Test
    void nuggetLifecycleOneShotFailurePreservesSelectedTarget() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                "nugget", "shell",
                "talk ABC\ndrain orders-1 --target MISSING --pin-env J7M_PIN\ntarget\nexit\n");

        assertEquals(1, output.exitCode());
        assertEquals(0, client.lifecycleCallCount());
        assertTrue(output.stdout().contains("target ABC"));
    }

    /**
     * Verifies old providers surface truthful unavailable lifecycle results.
     */
    @Test
    void nuggetLifecycleLegacyProviderReturnsUnavailable() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.legacyConnection = true;

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(3, output.exitCode());
        assertTrue(output.stdout().contains("UNAVAILABLE"));
        assertTrue(output.stdout().contains("unavailable"));
    }

    /**
     * Verifies shutdown-nugget returns a direct confirmation challenge without
     * leaking the PIN.
     */
    @Test
    void nuggetControlShutdownRequiresConfirmation() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "shutdown-nugget",
                new String[] {"ABC", "--target", "ABC", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(0, output.exitCode());
        assertEquals(1, client.controlCallCount());
        assertTrue(output.stdout().contains("\"action\":\"SHUTDOWN_NUGGET\""));
        assertTrue(output.stdout().contains("\"outcome\":\"CONFIRMATION_REQUIRED\""));
        assertTrue(output.stdout().contains("\"challenge\":\"phase77-confirmation\""));
        assertFalse(output.stdout().contains("env-pin-sentinel"));
    }

    /**
     * Verifies kill-nugget disabled results cannot be bypassed by presenting a
     * confirmation-looking value.
     */
    @Test
    void nuggetControlKillDisabledRejectsConfirmedAttempt() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        client.killDisabled = true;

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "kill-nugget",
                new String[] {"ABC", "--target", "ABC", "--pin-env", "J7M_PIN",
                        "--confirm", "phase77-confirmation", "--json"}, "");

        assertEquals(2, output.exitCode());
        assertEquals(1, client.controlCallCount());
        assertTrue(output.stdout().contains("\"outcome\":\"DISABLED\""));
        assertFalse(output.stdout().contains("env-pin-sentinel"));
    }

    /**
     * Verifies all-target nugget controls are rejected before authentication,
     * connection, or provider control execution.
     */
    @Test
    void nuggetControlRejectsAllTargetBeforeProviderCalls() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC"), record("nugget-b", "DEF")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "drain-nugget",
                new String[] {"ABC", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(1, output.exitCode());
        assertEquals(0, client.authenticateCount);
        assertEquals(0, client.controlCallCount());
        assertTrue(output.stdout().contains("TARGET_REQUIRED"));
    }

    /**
     * Verifies parser failures for nugget controls have no provider side
     * effects.
     */
    @Test
    void nuggetControlParserFailureHasZeroProviderSideEffects() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));

        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "shutdown-nugget",
                new String[] {"ABC", "--target", "ABC", "--id", "nugget-a", "--pin-env", "J7M_PIN"}, "");

        assertEquals(1, output.exitCode());
        assertEquals(0, client.authenticateCount);
        assertEquals(0, client.controlCallCount());
        assertTrue(output.stderr().contains("--target and --id cannot be combined"));
    }

    /**
     * Verifies lifecycle rendering consumes sanitized public lifecycle failure
     * details.
     */
    @Test
    void nuggetLifecycleFailureRenderingUsesSanitizedProjection() {
        FakeNuggetClient jsonClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        jsonClient.maliciousLifecycleFailure = true;

        NuggetOutput json = this.nugget(jsonClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(2, json.exitCode());
        assertTrue(json.stdout().contains("\"code\":\"PERMISSION_DENIED\""));
        assertTrue(json.stdout().contains("\"reason\":\"verification-failed\""));
        assertTrue(json.stdout().contains("\"metadata\":{\"reason\":\"verification-failed\""));
        assertFalse(json.stdout().contains("env-pin-sentinel"));
        assertNoLifecycleProviderSentinels(json.stdout() + json.stderr());

        FakeNuggetClient humanClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        humanClient.maliciousLifecycleFailure = true;
        NuggetOutput human = this.nugget(humanClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN"}, "");

        assertEquals(2, human.exitCode());
        assertTrue(human.stdout().contains("PERMISSION_DENIED"));
        assertTrue(human.stdout().contains("reason verification-failed"));
        assertFalse(human.stdout().contains("env-pin-sentinel"));
        assertNoLifecycleProviderSentinels(human.stdout() + human.stderr());
    }

    /**
     * Verifies lifecycle plan, deployment result, warning, and summary fields are
     * not treated as public merely because they are outside {@code RemoteError}.
     */
    @Test
    void nuggetLifecycleRenderingProjectsNestedProviderText() {
        FakeNuggetClient jsonClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        jsonClient.maliciousLifecyclePublicFields = true;

        NuggetOutput json = this.nugget(jsonClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "start",
                new String[] {"org.j7m.test:orders:1.0.0", "--dry-run", "--target", "ABC",
                        "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(0, json.exitCode());
        assertTrue(json.stdout().contains("\"outcome\":\"DRY_RUN\""));
        assertTrue(json.stdout().contains("detailsOmitted"));
        assertNoLifecycleProviderSentinels(json.stdout() + json.stderr());

        FakeNuggetClient humanClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        humanClient.maliciousLifecyclePublicFields = true;
        NuggetOutput human = this.nugget(humanClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "start",
                new String[] {"org.j7m.test:orders:1.0.0", "--dry-run", "--target", "ABC",
                        "--pin-env", "J7M_PIN"}, "");

        assertEquals(0, human.exitCode());
        assertTrue(human.stdout().contains("DRY_RUN"));
        assertNoLifecycleProviderSentinels(human.stdout() + human.stderr());
    }

    /**
     * Verifies returned failed deployment results cannot leak nested provider
     * failure text through lifecycle JSON or human rendering.
     */
    @Test
    void nuggetLifecycleReturnedFailureRenderingProjectsNestedDeploymentResult() {
        FakeNuggetClient jsonClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        jsonClient.maliciousLifecycleReturnedFailure = true;

        NuggetOutput json = this.nugget(jsonClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN", "--json"}, "");

        assertEquals(3, json.exitCode());
        assertTrue(json.stdout().contains("\"outcome\":\"FAILED\""));
        assertTrue(json.stdout().contains("\"code\":\"UNAVAILABLE\""));
        assertTrue(json.stdout().contains("\"reason\":\"deployer-result-failed\""));
        assertTrue(json.stdout().contains("\"deploymentResult\""));
        assertTrue(json.stdout().contains("\"message\":\"failed\""));
        assertTrue(json.stdout().contains("\"metadata\":{\"action\":\"STOP\",\"previousState\":\"RUNNING\","
                + "\"currentState\":\"FAILED\",\"resultingState\":\"FAILED\",\"reason\":\"deployer-result-failed\","
                + "\"result\":\"FAILED\",\"detailsOmitted\":\"true\"}"));
        assertTrue(json.stdout().contains("\"details\":{\"action\":\"STOP\",\"previousState\":\"RUNNING\","
                + "\"currentState\":\"FAILED\",\"resultingState\":\"FAILED\",\"reason\":\"deployer-result-failed\","
                + "\"result\":\"FAILED\",\"detailsOmitted\":\"true\"}"));
        assertFalse(json.stdout().contains("env-pin-sentinel"));
        assertNoLifecycleProviderSentinels(json.stdout() + json.stderr());

        FakeNuggetClient repeatedJsonClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        repeatedJsonClient.maliciousLifecycleReturnedFailure = true;
        NuggetOutput repeatedJson = this.nugget(
                repeatedJsonClient,
                pinReader("123456"),
                Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(),
                "nugget",
                "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN", "--json"},
                "");
        assertEquals(json.stdout(), repeatedJson.stdout());

        FakeNuggetClient humanClient = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        humanClient.maliciousLifecycleReturnedFailure = true;
        NuggetOutput human = this.nugget(humanClient, pinReader("123456"), Map.of("J7M_PIN", "env-pin-sentinel"),
                fixedClock(), "nugget", "stop",
                new String[] {"orders-1", "--target", "ABC", "--pin-env", "J7M_PIN"}, "");

        assertEquals(3, human.exitCode());
        assertTrue(human.stdout().contains("STOP FAILED failed"));
        assertTrue(human.stdout().contains("UNAVAILABLE"));
        assertTrue(human.stdout().contains("reason deployer-result-failed"));
        assertFalse(human.stdout().contains("env-pin-sentinel"));
        assertNoLifecycleProviderSentinels(human.stdout() + human.stderr());
    }

    /**
     * Verifies event-sink failures are observational and do not alter command
     * output or exit status.
     */
    @Test
    void nuggetEventSinkFailureDoesNotChangeCommandResult() {
        FakeNuggetClient client = new FakeNuggetClient(List.of(record("nugget-a", "ABC")));
        NuggetCliEventSink failingSink = (event, metadata) -> {
            throw new IllegalStateException("event sink sentinel");
        };
        NuggetOutput output = this.nugget(client, pinReader("123456"), Map.of(), fixedClock(), new FixedTicker(1L),
                failingSink, "nugget", "discover", "");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("discovered 1"));
        assertFalse(output.stdout().contains("event sink sentinel"));
        assertFalse(output.stderr().contains("event sink sentinel"));
    }

    private int run(final Fixture fixture, final String command, final String... extra) {
        return this.output(fixture, command, extra).exitCode();
    }

    private Output output(final Fixture fixture, final String command, final String... extra) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            String[] args = args(fixture, command, extra);
            int exit = J7mCli.run(args, output);
            return new Output(exit, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final String first,
            final String second,
            final String input) {
        return this.nugget(client, secretReader, environment, fixedClock(), first, second, new String[0], input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final String first,
            final String second,
            final String third,
            final String input) {
        return this.nugget(client, secretReader, environment, fixedClock(), first, second, new String[] {third}, input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final String first,
            final String second,
            final String third,
            final String fourth,
            final String input) {
        return this.nugget(client, secretReader, environment, fixedClock(), first, second, new String[] {third, fourth}, input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final Clock clock,
            final NuggetCliTicker ticker,
            final String first,
            final String second,
            final String input) {
        return this.nugget(client, secretReader, environment, clock, ticker, NuggetCliEventSink.none(),
                first, second, new String[0], input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final Clock clock,
            final NuggetCliTicker ticker,
            final NuggetCliEventSink eventSink,
            final String first,
            final String second,
            final String input) {
        return this.nugget(client, secretReader, environment, clock, ticker, eventSink,
                first, second, new String[0], input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final Clock clock,
            final String first,
            final String second,
            final String[] rest,
            final String input) {
        return this.nugget(client, secretReader, environment, clock, new FixedTicker(1L),
                NuggetCliEventSink.none(), first, second, rest, input);
    }

    private NuggetOutput nugget(
            final NuggetCliClient client,
            final NuggetCliSecretReader secretReader,
            final Map<String, String> environment,
            final Clock clock,
            final NuggetCliTicker ticker,
            final NuggetCliEventSink eventSink,
            final String first,
            final String second,
            final String[] rest,
            final String input) {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        String[] args = new String[2 + rest.length];
        args[0] = first;
        args[1] = second;
        System.arraycopy(rest, 0, args, 2, rest.length);
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            int exit = J7mCli.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), stdout, stderr,
                    client, secretReader, clock, ticker, eventSink, environment);
            return new NuggetOutput(
                    exit,
                    stdoutBytes.toString(StandardCharsets.UTF_8),
                    stderrBytes.toString(StandardCharsets.UTF_8));
        }
    }

    private static NuggetCliEventSink admissionWatchingEvents(
            final RecordingEvents events,
            final CountDownLatch admission) {
        return (event, metadata) -> {
            events.record(event, metadata);
            if ("auth-accepted".equals(event)) {
                admission.countDown();
            }
        };
    }

    private NuggetOutput productionNugget(final String[] providers, final String... args) throws Exception {
        Path root = Files.createDirectories(this.temporaryDirectory.resolve("services-" + providers.length));
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Path serviceFile = services.resolve(NuggetCliClientProvider.class.getName());
        if (providers.length > 0) {
            Files.writeString(serviceFile, String.join("\n", providers) + "\n", StandardCharsets.UTF_8);
        }
        URL[] urls = {root.toUri().toURL()};
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(urls, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
            try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
                 PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
                int exit = J7mCli.run(args, stdout, stderr);
                return new NuggetOutput(
                        exit,
                        stdoutBytes.toString(StandardCharsets.UTF_8),
                        stderrBytes.toString(StandardCharsets.UTF_8));
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static NuggetCliSecretReader pinReader(final String value) {
        return prompt -> value == null ? null : value.toCharArray();
    }

    private static NuggetCliSecretReader pinReaderOnce(final String value) {
        return new NuggetCliSecretReader() {
            private boolean used;

            @Override
            public char[] readPin(final String prompt) {
                if (this.used || value == null) {
                    return null;
                }
                this.used = true;
                return value.toCharArray();
            }
        };
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static int occurrences(final String text, final String token) {
        int count = 0;
        int offset = 0;
        while (offset < text.length()) {
            int found = text.indexOf(token, offset);
            if (found < 0) {
                return count;
            }
            count++;
            offset = found + token.length();
        }
        return count;
    }

    private static Map<String, String> returnedFailureMetadata(final String action) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("action", action);
        metadata.put("previousState", "RUNNING");
        metadata.put("currentState", "FAILED");
        metadata.put("resultingState", "FAILED");
        metadata.put("reason", "deployer-result-failed");
        metadata.put("result", "FAILED");
        metadata.put("detailsOmitted", "true");
        return metadata;
    }

    private static void assertBoundedJsonEnvelope(
            final String output,
            final String command,
            final boolean partial,
            final boolean truncated) {
        assertTrue(output.startsWith("{\"version\":1,\"ok\":true,\"command\":\"" + command + "\",\"data\":{"));
        assertTrue(output.endsWith("}\n"));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= 128 * 1024 + 1);
        assertTrue(output.contains("\"partial\":\"" + partial + "\""));
        assertTrue(output.contains("\"truncated\":\"" + truncated + "\""));
        assertTrue(output.indexOf("\"partial\"") < output.indexOf("\"targets\""));
        assertTrue(output.indexOf("\"truncated\"") < output.indexOf("\"targets\""));
    }

    private static void assertNoLifecycleProviderSentinels(final String text) {
        assertFalse(text.contains("opaque-secret-sentinel"));
        assertFalse(text.contains("password-sentinel"));
        assertFalse(text.contains("pin-sentinel"));
        assertFalse(text.contains("admission-sentinel"));
        assertFalse(text.contains("credential-sentinel"));
        assertFalse(text.contains("/secret/repository/key.pem"));
        assertFalse(text.contains("manifest-sentinel"));
        assertFalse(text.contains("certificate-sentinel"));
        assertFalse(text.contains("signature-sentinel"));
        assertFalse(text.contains("private-key-sentinel"));
        assertFalse(text.contains("stack-sentinel"));
        assertFalse(text.contains("exception-sentinel"));
        assertFalse(text.contains("payload-sentinel"));
        assertFalse(text.contains("provider raw message sentinel"));
        assertFalse(text.contains("Bearer abc-token"));
        assertFalse(text.contains("returned raw detail sentinel"));
        assertFalse(text.contains("secret-token"));
        assertFalse(text.contains("PRIVATE-KEY"));
        assertFalse(text.contains("IllegalStateException"));
        assertFalse(text.contains("stackTrace"));
        assertFalse(text.contains("configuration-sentinel"));
        assertFalse(text.contains("s-e-c-r-e-t"));
    }

    private static Path cliConsoleSource() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path moduleCandidate = current.resolve(
                    "src/main/java/org/j7m/middleware/deployer/cli/NuggetCliConsole.java");
            if (Files.isRegularFile(moduleCandidate)) {
                return moduleCandidate;
            }
            Path rootCandidate = current.resolve(
                    "j7m-deployer-cli/src/main/java/org/j7m/middleware/deployer/cli/NuggetCliConsole.java");
            if (Files.isRegularFile(rootCandidate)) {
                return rootCandidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("NuggetCliConsole source file was not found");
    }

    private static String[] args(final Fixture fixture, final String command, final String... extra) {
        String[] base;
        if ("repository".equals(command)) {
            base = new String[2 + extra.length];
            base[0] = "app";
            base[1] = "repository";
            System.arraycopy(extra, 0, base, 2, extra.length);
        } else {
            base = new String[] {"app", command};
        }
        String[] required = {
                "--project", fixture.project().toString(),
                "--bundle", fixture.bundle().toString(),
                "--repository", fixture.repository().toString(),
                "--coordinate", fixture.coordinate()
        };
        String[] result = new String[base.length + required.length + ("repository".equals(command) ? 0 : extra.length)];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(required, 0, result, base.length, required.length);
        if (!"repository".equals(command)) {
            System.arraycopy(extra, 0, result, base.length + required.length, extra.length);
        }
        return result;
    }

    private Fixture fixture(final String name, final boolean regulated) throws Exception {
        Path project = this.temporaryDirectory.resolve(name);
        Path metadata = project.resolve("META-INF");
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("j7m-application.json"), manifest(name), StandardCharsets.UTF_8);
        Files.writeString(project.resolve("README.md"), "# " + name + "\n", StandardCharsets.UTF_8);
        if (regulated) {
            Files.writeString(metadata.resolve("sbom.spdx.json"), "{\"spdxVersion\":\"SPDX-2.3\"}\n", StandardCharsets.UTF_8);
            Files.writeString(metadata.resolve("j7m-provenance.json"), "{\"builder\":\"cli-test\"}\n", StandardCharsets.UTF_8);
        }
        return new Fixture(project, project.resolve("build/libs/" + name + ".j7m"),
                project.resolve("build/repository"), "org.j7m.cli:" + name + ":1.0.0");
    }

    private Fixture jpmsFixture(final String name, final String declaredModule, final String actualModule) throws Exception {
        Fixture fixture = this.jpmsFixtureWithoutModule(name, declaredModule);
        createModularJar(fixture.project().resolve("modules").resolve("app.jar"), actualModule);
        return fixture;
    }

    private Fixture jpmsFixtureWithoutModule(final String name, final String declaredModule) throws Exception {
        Path project = this.temporaryDirectory.resolve(name);
        Path metadata = project.resolve("META-INF");
        Files.createDirectories(metadata);
        Files.createDirectories(project.resolve("modules"));
        Files.writeString(metadata.resolve("j7m-application.json"), jpmsManifest(name, declaredModule), StandardCharsets.UTF_8);
        Files.writeString(project.resolve("README.md"), "# " + name + "\n", StandardCharsets.UTF_8);
        return new Fixture(project, project.resolve("build/libs/" + name + ".j7m"),
                project.resolve("build/repository"), "org.j7m.cli:" + name + ":1.0.0");
    }

    private Fixture jpmsFixtureWithAutomaticModule(final String name, final String declaredModule) throws Exception {
        Fixture fixture = this.jpmsFixtureWithoutModule(name, declaredModule);
        createAutomaticJar(fixture.project().resolve("modules").resolve("app.jar"));
        return fixture;
    }

    private static String manifest(final String name) {
        return """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.cli",
                    "name": "%s",
                    "version": "1.0.0",
                    "displayName": "%s",
                    "description": "cli test"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.5.6",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.example.CliApplication"
                  },
                  "services": [
                    {
                      "serviceName": "//apps/cli/%s",
                      "interfaceName": "org.example.CliService",
                      "implementationClass": "org.example.CliServiceImpl",
                      "transport": "grpc",
                      "globallyExported": true
                    }
                  ]
                }
                """.formatted(name, name, name);
    }

    private static String jpmsManifest(final String name, final String moduleName) {
        return """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.cli",
                    "name": "%s",
                    "version": "1.0.0",
                    "displayName": "%s",
                    "description": "cli jpms test"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.5.6",
                    "javaVersion": "25",
                    "moduleMode": "JPMS",
                    "bootstrapModule": "%s",
                    "bootstrapClass": "%s.CliApplication"
                  },
                  "modules": [
                    {
                      "name": "%s",
                      "path": "modules/app.jar"
                    }
                  ],
                  "services": [
                    {
                      "serviceName": "//apps/cli/%s",
                      "interfaceName": "%s.CliService",
                      "implementationClass": "%s.CliServiceImpl",
                      "transport": "grpc",
                      "globallyExported": true
                    }
                  ]
                }
                """.formatted(name, name, moduleName, moduleName, moduleName, name, moduleName, moduleName);
    }

    private static void createModularJar(final Path jar, final String moduleName) throws Exception {
        Path sourceRoot = jar.getParent().resolve("src");
        Path classes = jar.getParent().resolve("classes");
        Path packageDirectory = sourceRoot.resolve(moduleName.replace('.', '/'));
        Files.createDirectories(packageDirectory);
        Files.createDirectories(classes);
        Path moduleInfo = sourceRoot.resolve("module-info.java");
        Files.writeString(moduleInfo, "module " + moduleName + " { exports " + moduleName + "; }\n", StandardCharsets.UTF_8);
        Files.writeString(packageDirectory.resolve("CliApplication.java"),
                "package " + moduleName + "; public final class CliApplication { public CliApplication() { } }\n",
                StandardCharsets.UTF_8);
        Files.writeString(packageDirectory.resolve("CliService.java"),
                "package " + moduleName + "; public interface CliService { String value(); }\n",
                StandardCharsets.UTF_8);
        Files.writeString(packageDirectory.resolve("CliServiceImpl.java"),
                "package " + moduleName + "; public final class CliServiceImpl implements CliService {"
                        + " public String value() { return \"ok\"; } }\n",
                StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("system Java compiler is unavailable");
        }
        int result = compiler.run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                moduleInfo.toString(),
                packageDirectory.resolve("CliApplication.java").toString(),
                packageDirectory.resolve("CliService.java").toString(),
                packageDirectory.resolve("CliServiceImpl.java").toString());
        if (result != 0) {
            throw new IllegalStateException("failed to compile test module");
        }
        writeJar(jar, classes);
    }

    private static void createAutomaticJar(final Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("org/example/Automatic.class"));
            output.write(new byte[] {0, 0, 0, 0});
            output.closeEntry();
        }
    }

    private static void writeJar(final Path jar, final Path classes) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                java.util.stream.Stream<Path> stream = Files.walk(classes)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) {
                    String entryName = classes.relativize(path).toString().replace('\\', '/');
                    output.putNextEntry(new JarEntry(entryName));
                    Files.copy(path, output);
                    output.closeEntry();
                }
            }
        }
    }

    private record Fixture(Path project, Path bundle, Path repository, String coordinate) {
    }

    private record Output(int exitCode, String text) {
    }

    private record NuggetOutput(int exitCode, String stdout, String stderr) {
    }

    private static final class StepClock extends Clock {
        private Instant current;
        private final Duration step;

        private StepClock(final Instant current, final Duration step) {
            this.current = current;
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant result = this.current;
            this.current = this.current.plus(this.step);
            return result;
        }
    }

    private static final class FixedTicker implements NuggetCliTicker {
        private final long value;

        private FixedTicker(final long value) {
            this.value = value;
        }

        @Override
        public long nanoTime() {
            return this.value;
        }
    }

    private static final class StepTicker implements NuggetCliTicker {
        private long current;
        private final long step;

        private StepTicker(final long current, final Duration step) {
            this.current = current;
            this.step = step.toNanos();
        }

        @Override
        public long nanoTime() {
            long result = this.current;
            this.current += this.step;
            return result;
        }
    }

    private static final class SettableTicker implements NuggetCliTicker {
        private long value;

        private SettableTicker(final long value) {
            this.value = value;
        }

        @Override
        public long nanoTime() {
            return this.value;
        }

        private void set(final long nextValue) {
            this.value = nextValue;
        }
    }

    private static final class RecordingEvents implements NuggetCliEventSink {
        private final List<String> events;
        private final List<String> entries;

        private RecordingEvents() {
            this.events = new ArrayList<>();
            this.entries = new ArrayList<>();
        }

        @Override
        public synchronized void record(final String event, final Map<String, String> metadata) {
            this.events.add(event);
            this.entries.add(event + ":" + metadata);
        }

        private synchronized List<String> events() {
            return List.copyOf(this.events);
        }

        private synchronized String text() {
            return this.entries.toString();
        }
    }

    private static NuggetDiscovery.Record record(final String id, final String name) {
        return record(id, name, NuggetDiscovery.Status.ACTIVE, NuggetDiscovery.Compatibility.COMPATIBLE);
    }

    private static NuggetDiscovery.Record record(
            final String id,
            final String name,
            final NuggetDiscovery.Status status,
            final NuggetDiscovery.Compatibility compatibility) {
        return new NuggetDiscovery.Record(
                id,
                name,
                "runtime-" + id,
                NuggetDiscovery.EndpointDescriptor.local("endpoint-" + id),
                1,
                1,
                compatibility,
                List.of("admin"),
                "123",
                "LOCAL",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:10:00Z"),
                NuggetDiscovery.Source.LOCAL_RECORD,
                status,
                List.of());
    }

    private static NuggetDiscovery.Record remoteRecord(final String id, final String name) {
        return new NuggetDiscovery.Record(
                id,
                name,
                "runtime-" + id,
                new NuggetDiscovery.EndpointDescriptor("remote", "tcp", "REMOTE", 7443, ""),
                1,
                1,
                NuggetDiscovery.Compatibility.COMPATIBLE,
                List.of("admin"),
                "",
                "REMOTE",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:10:00Z"),
                NuggetDiscovery.Source.CONFIGURED_TARGET,
                NuggetDiscovery.Status.ACTIVE,
                List.of());
    }

    private static final class FakeNuggetClient implements NuggetCliClient {
        private final List<NuggetDiscovery.Record> records;
        private final NuggetCliConnectionSecurity security;
        private final List<String> connectedIds;
        private final List<List<NuggetDiscovery.Record>> discoverySequence;
        private final List<NuggetDiscovery.Resolution> resolutionSequence;
        private int authenticateCount;
        private int closeCount;
        private int connectionCloseCount;
        private int statusCallCount;
        private int applicationDetailCallCount;
        private int objectDetailCallCount;
        private int lifecycleCallCount;
        private int controlCallCount;
        private boolean authorized;
        private RuntimeException discoveryFailure;
        private RuntimeException connectFailure;
        private boolean legacyConnection;
        private boolean manyApplications;
        private boolean hugeApplications;
        private boolean unicodeObjects;
        private boolean hostileHugeObject;
        private boolean partialMetrics;
        private boolean hostileHugeLabels;
        private boolean partialRecentErrors;
        private boolean failObjectDetail;
        private boolean failApplicationDetail;
        private boolean maliciousLifecycleFailure;
        private boolean maliciousLifecyclePublicFields;
        private boolean maliciousLifecycleReturnedFailure;
        private boolean killDisabled;
        private SettableTicker detailPublicationTicker;
        private long detailPublicationExpiryNanos;
        private char[] lastAuthenticationPin;
        private boolean blockStatus;
        private CountDownLatch statusEntered;
        private CountDownLatch releaseStatus;
        private boolean blockConnect;
        private int blockedConnectsRemaining;
        private CountDownLatch connectEntered;
        private CountDownLatch releaseConnect;
        private CountDownLatch lateConnectionClosed;
        private boolean blockResolve;
        private String blockedResolveValue;
        private CountDownLatch resolveEntered;
        private CountDownLatch releaseResolve;
        private CountDownLatch resolveReturned;
        private boolean blockAuthenticate;
        private CountDownLatch authenticateEntered;
        private CountDownLatch releaseAuthenticate;
        private CountDownLatch authenticateReturned;

        private FakeNuggetClient(final List<NuggetDiscovery.Record> records) {
            this(records, NuggetCliConnectionSecurity.trustedLocal());
        }

        private FakeNuggetClient(
                final List<NuggetDiscovery.Record> records,
                final NuggetCliConnectionSecurity security) {
            this.records = List.copyOf(records);
            this.security = security;
            this.connectedIds = new ArrayList<>();
            this.discoverySequence = new ArrayList<>();
            this.resolutionSequence = new ArrayList<>();
            this.authorized = true;
        }

        @Override
        public NuggetDiscovery.Result discover(final NuggetDiscovery.Query query) {
            if (this.discoveryFailure != null) {
                throw this.discoveryFailure;
            }
            List<NuggetDiscovery.Record> current = this.discoverySequence.isEmpty()
                    ? this.records
                    : this.discoverySequence.remove(0);
            return new NuggetDiscovery.Result(current, current.size(), current.size(),
                    Instant.parse("2026-01-01T00:00:00Z"), false, List.of());
        }

        @Override
        public NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
            this.awaitResolveIfBlocked(selector);
            if (!this.resolutionSequence.isEmpty()) {
                return this.resolutionSequence.remove(0);
            }
            List<NuggetDiscovery.Record> matches = switch (selector.type()) {
                case ALL -> this.records.stream()
                        .filter(FakeNuggetClient::activeCompatible)
                        .toList();
                case EXACT_ID -> this.records.stream()
                        .filter(record -> record.nuggetId().equals(selector.value()))
                        .toList();
                case EXACT_NAME -> this.records.stream()
                        .filter(record -> record.nuggetName().equals(selector.value()))
                        .toList();
                case ENDPOINT_ID -> List.of();
            };
            if (matches.isEmpty()) {
                return resolution(NuggetDiscovery.ResolutionStatus.NOT_FOUND, List.of());
            }
            if (selector.type() == NuggetDiscovery.SelectorType.EXACT_NAME) {
                List<NuggetDiscovery.Record> active = matches.stream()
                        .filter(FakeNuggetClient::activeCompatible)
                        .toList();
                if (active.size() > 1) {
                    return resolution(NuggetDiscovery.ResolutionStatus.AMBIGUOUS, active);
                }
                if (active.size() == 1) {
                    return resolution(NuggetDiscovery.ResolutionStatus.RESOLVED, active);
                }
                if (matches.stream().anyMatch(record -> record.compatibility() == NuggetDiscovery.Compatibility.INCOMPATIBLE)) {
                    return resolution(NuggetDiscovery.ResolutionStatus.INCOMPATIBLE, matches);
                }
                return resolution(NuggetDiscovery.ResolutionStatus.STALE, matches);
            }
            if (matches.size() == 1 && activeCompatible(matches.get(0))) {
                return resolution(NuggetDiscovery.ResolutionStatus.RESOLVED, matches);
            }
            return resolution(NuggetDiscovery.ResolutionStatus.NOT_FOUND, matches);
        }

        @Override
        public NuggetCliConnection connect(final NuggetDiscovery.Record record) {
            if (this.connectFailure != null) {
                throw this.connectFailure;
            }
            this.awaitConnectIfBlocked();
            this.connectedIds.add(record.nuggetId());
            if (this.legacyConnection) {
                return new LegacyConnection(record, this, this.security);
            }
            return new FakeConnection(record, this, this.security);
        }

        @Override
        public void close() {
            this.closeCount++;
        }

        private int authenticateCount() {
            return this.authenticateCount;
        }

        private char[] lastAuthenticationPin() {
            return this.lastAuthenticationPin == null ? new char[0] : this.lastAuthenticationPin;
        }

        private int connectionCloseCount() {
            return this.connectionCloseCount;
        }

        private int statusCallCount() {
            return this.statusCallCount;
        }

        private int applicationDetailCallCount() {
            return this.applicationDetailCallCount;
        }

        private int objectDetailCallCount() {
            return this.objectDetailCallCount;
        }

        private int lifecycleCallCount() {
            return this.lifecycleCallCount;
        }

        private int controlCallCount() {
            return this.controlCallCount;
        }

        private void awaitStatusIfBlocked() {
            if (!this.blockStatus) {
                return;
            }
            CountDownLatch entered = this.statusEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = this.releaseStatus;
            if (release == null) {
                return;
            }
            try {
                release.await(1L, TimeUnit.MINUTES);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitConnectIfBlocked() {
            boolean shouldBlock = this.blockConnect;
            if (!shouldBlock && this.blockedConnectsRemaining > 0) {
                this.blockedConnectsRemaining--;
                shouldBlock = true;
            }
            if (!shouldBlock) {
                return;
            }
            CountDownLatch entered = this.connectEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = this.releaseConnect;
            if (release == null) {
                return;
            }
            boolean released = false;
            boolean interrupted = false;
            while (!released) {
                try {
                    released = release.await(1L, TimeUnit.MINUTES);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitResolveIfBlocked(final NuggetDiscovery.Selector selector) {
            if (!this.blockResolve) {
                return;
            }
            String value = this.blockedResolveValue;
            if (value != null && !value.equals(selector.value())) {
                return;
            }
            CountDownLatch entered = this.resolveEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = this.releaseResolve;
            if (release == null) {
                return;
            }
            try {
                boolean released = false;
                boolean interrupted = false;
                while (!released) {
                    try {
                        released = release.await(1L, TimeUnit.MINUTES);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                CountDownLatch returned = this.resolveReturned;
                if (returned != null) {
                    returned.countDown();
                }
            }
        }

        private void awaitAuthenticateIfBlocked() {
            if (!this.blockAuthenticate) {
                return;
            }
            CountDownLatch entered = this.authenticateEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = this.releaseAuthenticate;
            if (release == null) {
                return;
            }
            boolean released = false;
            boolean interrupted = false;
            while (!released) {
                try {
                    released = release.await(1L, TimeUnit.MINUTES);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void signalAuthenticateReturned() {
            CountDownLatch returned = this.authenticateReturned;
            if (returned != null) {
                returned.countDown();
            }
        }

        private void expirePublicationTickerAfterDetail() {
            SettableTicker ticker = this.detailPublicationTicker;
            if (ticker != null) {
                ticker.set(this.detailPublicationExpiryNanos);
            }
        }

        private List<String> connectedIds() {
            return List.copyOf(this.connectedIds);
        }

        private int closeCount() {
            return this.closeCount;
        }

        private static boolean activeCompatible(final NuggetDiscovery.Record record) {
            return record.status() == NuggetDiscovery.Status.ACTIVE
                    && record.compatibility() == NuggetDiscovery.Compatibility.COMPATIBLE;
        }

        private static NuggetDiscovery.Resolution resolution(
                final NuggetDiscovery.ResolutionStatus status,
                final List<NuggetDiscovery.Record> records) {
            return new NuggetDiscovery.Resolution(status, records, status.name().toLowerCase(java.util.Locale.ROOT),
                    null, Instant.parse("2026-01-01T00:00:00Z"), List.of());
        }
    }

    private static final class LegacyConnection implements NuggetCliConnection {
        private final NuggetDiscovery.Record record;
        private final FakeNuggetClient client;
        private final NuggetCliConnectionSecurity security;

        private LegacyConnection(
                final NuggetDiscovery.Record record,
                final FakeNuggetClient client,
                final NuggetCliConnectionSecurity security) {
            this.record = record;
            this.client = client;
            this.security = security;
        }

        @Override
        public NuggetCliConnectionSecurity security() {
            return this.security;
        }

        @Override
        public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
            return new FakeConnection(this.record, this.client, this.security).authenticate(request, pin);
        }

        @Override
        public NuggetStatus status(final String admissionId) {
            return FakeConnection.statusFor(this.record);
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
            return new FakeConnection(this.record, this.client, this.security).overview(request);
        }

        @Override
        public void close() {
            this.client.connectionCloseCount++;
        }
    }

    private static final class FakeConnection implements NuggetCliConnection {
        private final NuggetDiscovery.Record record;
        private final FakeNuggetClient client;
        private final NuggetCliConnectionSecurity security;

        private FakeConnection(
                final NuggetDiscovery.Record record,
                final FakeNuggetClient client,
                final NuggetCliConnectionSecurity security) {
            this.record = record;
            this.client = client;
            this.security = security;
        }

        @Override
        public NuggetCliConnectionSecurity security() {
            return this.security;
        }

        @Override
        public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
            try {
                this.client.authenticateCount++;
                this.client.lastAuthenticationPin = pin;
                this.client.awaitAuthenticateIfBlocked();
                String value = new String(pin);
                if ("throttle".equals(value)) {
                    return new NuggetAuthenticationResult(NuggetAuthenticationOutcome.THROTTLED,
                            NuggetAuthenticationFailureReason.THROTTLED, this.record.nuggetId(), request.correlationId(),
                            "", Duration.ofSeconds(1L), Instant.parse("2026-01-01T00:00:00Z"), null, Map.of());
                }
                if (!"123456".equals(value) && !"env-pin-sentinel".equals(value) && !"phase74-pin".equals(value)) {
                    return new NuggetAuthenticationResult(NuggetAuthenticationOutcome.REJECTED,
                            NuggetAuthenticationFailureReason.REJECTED, this.record.nuggetId(), request.correlationId(),
                            "", Duration.ZERO, Instant.parse("2026-01-01T00:00:00Z"), null, Map.of());
                }
                return new NuggetAuthenticationResult(NuggetAuthenticationOutcome.ACCEPTED,
                        NuggetAuthenticationFailureReason.NONE, this.record.nuggetId(), request.correlationId(),
                        "admission-" + this.record.nuggetId(), Duration.ZERO,
                        Instant.parse("2026-01-01T00:00:00Z"), null, Map.of());
            } finally {
                this.client.signalAuthenticateReturned();
            }
        }

        @Override
        public NuggetStatus status(final String admissionId) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                throw new NuggetCliFailure(2, "NOT_AUTHORIZED", "not authorized");
            }
            return statusFor(this.record);
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return new NuggetIntrospection.Result<>(true, false, NuggetIntrospection.Category.OVERVIEW,
                        Instant.parse("2026-01-01T00:00:00Z"), false, null, null, List.of(), Map.of());
            }
            NuggetStatus status = statusFor(this.record);
            NuggetIntrospection.HealthSummary health = new NuggetIntrospection.HealthSummary(
                    NuggetIntrospection.HealthState.HEALTHY, List.of(), Instant.parse("2026-01-01T00:00:00Z"), false);
            NuggetIntrospection.ReadinessSummary readiness = new NuggetIntrospection.ReadinessSummary(
                    NuggetIntrospection.ReadinessState.READY, List.of(), Instant.parse("2026-01-01T00:00:00Z"), false);
            NuggetIntrospection.RuntimeSummary runtime = new NuggetIntrospection.RuntimeSummary(
                    status.identity().runtimeIdentity().runtimeId(), status.identity().nuggetId(), NuggetState.RUNNING,
                    Instant.parse("2026-01-01T00:00:00Z"), Duration.ofSeconds(1L), 1, 1, 0, 0L, 0L, 0,
                    Map.of(), Instant.parse("2026-01-01T00:00:00Z"));
            NuggetIntrospection.Overview overview = new NuggetIntrospection.Overview(status, runtime, health, readiness, false, List.of());
            return new NuggetIntrospection.Result<>(true, true, NuggetIntrospection.Category.OVERVIEW,
                    Instant.parse("2026-01-01T00:00:00Z"), false, overview, null, List.of(), Map.of());
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(
                final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.ENVIRONMENT);
            }
            NuggetIntrospection.EnvironmentSummary summary = new NuggetIntrospection.EnvironmentSummary(
                    "25",
                    "OpenJDK",
                    "J7M Test VM",
                    "Linux",
                    "6",
                    "amd64",
                    "12345",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ofSeconds(3L),
                    4,
                    1024L,
                    2048L,
                    4096L,
                    512L,
                    1024L,
                    7,
                    2,
                    9,
                    11L,
                    300,
                    400L,
                    List.of(new NuggetIntrospection.KeyValue("G1", "collections=1")),
                    0.1D,
                    0.2D,
                    100L,
                    "<redacted>",
                    List.of("PATH", "J7M_SAFE"),
                    List.of(new NuggetIntrospection.KeyValue("-Xmx", "<redacted>")),
                    Map.of(),
                    Instant.parse("2026-01-01T00:00:00Z"));
            return success(NuggetIntrospection.Category.ENVIRONMENT, summary);
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.DiskQuery query) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.DISK);
            }
            List<NuggetIntrospection.DiskVolumeSummary> items = List.of(new NuggetIntrospection.DiskVolumeSummary(
                    "repository:1",
                    "repository",
                    List.of("repository"),
                    "<redacted>",
                    1000L,
                    400L,
                    400L,
                    600L,
                    60.0D,
                    NuggetIntrospection.DiskHealth.HEALTHY,
                    false,
                    NuggetIntrospection.Availability.AVAILABLE,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "",
                    Map.of()));
            return success(NuggetIntrospection.Category.DISK, collection(items, query.page()));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.HEALTH);
            }
            return success(NuggetIntrospection.Category.HEALTH, new NuggetIntrospection.HealthSummary(
                    NuggetIntrospection.HealthState.HEALTHY,
                    List.of(component("runtime")),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.READINESS);
            }
            return success(NuggetIntrospection.Category.READINESS, new NuggetIntrospection.ReadinessSummary(
                    NuggetIntrospection.ReadinessState.READY,
                    List.of(component("runtime")),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.RUNTIME);
            }
            NuggetStatus status = statusFor(this.record);
            return success(NuggetIntrospection.Category.RUNTIME, new NuggetIntrospection.RuntimeSummary(
                    status.identity().runtimeIdentity().runtimeId(),
                    status.identity().nuggetId(),
                    NuggetState.RUNNING,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ofSeconds(3L),
                    2,
                    1,
                    1,
                    9L,
                    1L,
                    1,
                    Map.of("mode", "DEV"),
                    Instant.parse("2026-01-01T00:00:00Z")));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.APPLICATIONS);
            }
            List<NuggetIntrospection.ApplicationSummary> values = this.client.hugeApplications
                    ? hugeApplications()
                    : this.client.manyApplications
                    ? manyApplications()
                    : List.of(
                            applicationSummary("orders", "orders-1", "Orders", "RUNNING", 1,
                                    NuggetIntrospection.HealthState.HEALTHY, NuggetIntrospection.ReadinessState.READY, ""),
                            applicationSummary("billing", "billing-1", "Billing", "FAILED", 0,
                                    NuggetIntrospection.HealthState.UNHEALTHY, NuggetIntrospection.ReadinessState.NOT_READY,
                                    "APP_FAILED"));
            return success(NuggetIntrospection.Category.APPLICATIONS, collection(filterApplications(values, query), query));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            this.client.applicationDetailCallCount++;
            this.client.expirePublicationTickerAfterDetail();
            if (this.client.failApplicationDetail) {
                throw new IllegalStateException("raw application detail sentinel");
            }
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> list =
                    applications(request, NuggetIntrospection.Query.all());
            if (!resultAllowed(list)) {
                return denied(NuggetIntrospection.Category.APPLICATIONS);
            }
            for (NuggetIntrospection.ApplicationSummary summary : list.value().items()) {
                if (summary.instanceId().equals(applicationInstanceId) || summary.applicationId().equals(applicationInstanceId)) {
                    return success(NuggetIntrospection.Category.APPLICATIONS, new NuggetIntrospection.ApplicationDetail(
                            summary,
                            List.of(new NuggetIntrospection.ServiceSummary("orders", "com.example.Orders", "<redacted>")),
                            List.of("com.example.orders"),
                            Map.of("read", "true"),
                            "verified",
                            "signed",
                            "present",
                            "present",
                            Map.of("events", 1L)));
                }
            }
            return new NuggetIntrospection.Result<>(true, true, NuggetIntrospection.Category.APPLICATIONS,
                    Instant.parse("2026-01-01T00:00:00Z"), false, null,
                    new org.j7m.middleware.api.RemoteError(org.j7m.middleware.api.MiddlewareErrorCode.NOT_FOUND,
                            "application not found", Map.of(), "", "", false, false),
                    List.of(), Map.of());
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.OBJECTS);
            }
            List<NuggetIntrospection.ObjectSummary> values = this.client.hostileHugeObject
                    ? hostileHugeObjects()
                    : this.client.unicodeObjects
                    ? unicodeObjects()
                    : List.of(
                            objectSummary("orders-object", "orders", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 11L),
                            objectSummary("runtime-object", "runtime", NuggetIntrospection.ObjectVisibility.INTERNAL, "", 2L));
            return success(NuggetIntrospection.Category.OBJECTS, collection(filterObjects(values, query), query));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
                final NuggetIntrospection.Request request,
                final String objectId) {
            this.client.objectDetailCallCount++;
            this.client.expirePublicationTickerAfterDetail();
            if (this.client.failObjectDetail) {
                throw new IllegalStateException("raw object detail sentinel");
            }
            NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> list =
                    objects(request, NuggetIntrospection.Query.all());
            if (!resultAllowed(list)) {
                return denied(NuggetIntrospection.Category.OBJECTS);
            }
            if ("hostile-detail".equals(objectId)) {
                return success(NuggetIntrospection.Category.OBJECTS, new NuggetIntrospection.ObjectDetail(
                        hostileObjectSummary(),
                        largeSafeMap(),
                        largeSafeMap()));
            }
            if ("large-object".equals(objectId)) {
                return success(NuggetIntrospection.Category.OBJECTS, new NuggetIntrospection.ObjectDetail(
                        list.value().items().getFirst(),
                        largeSafeMap(),
                        largeSafeMap()));
            }
            for (NuggetIntrospection.ObjectSummary summary : list.value().items()) {
                if (summary.objectId().equals(objectId) || summary.logicalName().equals(objectId)) {
                    return success(NuggetIntrospection.Category.OBJECTS, new NuggetIntrospection.ObjectDetail(
                            summary,
                            Map.of("descriptor", "safe"),
                            Map.of("annotation", "safe")));
                }
            }
            return new NuggetIntrospection.Result<>(true, true, NuggetIntrospection.Category.OBJECTS,
                    Instant.parse("2026-01-01T00:00:00Z"), false, null,
                    new org.j7m.middleware.api.RemoteError(org.j7m.middleware.api.MiddlewareErrorCode.NOT_FOUND,
                            "object not found", Map.of(), "", "", false, false),
                    List.of(), Map.of());
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(final NuggetIntrospection.Request request) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.METRICS);
            }
            if (this.client.partialMetrics) {
                return new NuggetIntrospection.Result<>(true, true, NuggetIntrospection.Category.METRICS,
                        Instant.parse("2026-01-01T00:00:00Z"), true,
                        new NuggetIntrospection.MetricsSummary(List.of(), Instant.parse("2026-01-01T00:00:00Z"), true),
                        null, List.of(), Map.of());
            }
            if (this.client.hostileHugeLabels) {
                return success(NuggetIntrospection.Category.METRICS, new NuggetIntrospection.MetricsSummary(
                        List.of(new NuggetIntrospection.MetricEntry("runtime.hostile.labels", "count", 1.0D,
                                hostileLabels())),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        false));
            }
            return success(NuggetIntrospection.Category.METRICS, new NuggetIntrospection.MetricsSummary(
                    List.of(new NuggetIntrospection.MetricEntry("runtime.invocations", "count", 9.0D, Map.of("component", "runtime"))),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.RecentErrorQuery query) {
            this.client.statusCallCount++;
            this.client.awaitStatusIfBlocked();
            if (!this.client.authorized) {
                return denied(NuggetIntrospection.Category.ERRORS);
            }
            List<NuggetIntrospection.RecentErrorEntry> values = List.of(new NuggetIntrospection.RecentErrorEntry(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "corr",
                    "runtime",
                    "<redacted>",
                    org.j7m.middleware.api.MiddlewareErrorCode.INTERNAL,
                    "safe failure",
                    false,
                    "",
                    1L,
                    Map.of()));
            List<NuggetIntrospection.RecentErrorEntry> filtered = filterRecentErrors(values, query);
            if (this.client.partialRecentErrors) {
                return new NuggetIntrospection.Result<>(true, true, NuggetIntrospection.Category.ERRORS,
                        Instant.parse("2026-01-01T00:00:00Z"), true, collection(filtered, query.page(), true),
                        null, List.of(), Map.of());
            }
            return success(NuggetIntrospection.Category.ERRORS, collection(filtered, query.page()));
        }

        @Override
        public NuggetApplicationLifecycle.Result applicationLifecycle(final NuggetApplicationLifecycle.Request request) {
            this.client.lifecycleCallCount++;
            if (!this.client.authorized) {
                return new NuggetApplicationLifecycle.Result(
                        "operation-test",
                        request.correlationId(),
                        request.action(),
                        true,
                        false,
                        request.dryRun(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.coordinate(),
                        request.instanceId(),
                        null,
                        null,
                        null,
                        NuggetApplicationLifecycle.Outcome.REJECTED,
                        "not authorized",
                        false,
                        false,
                        true,
                        false,
                        true,
                        "",
                        "denied",
                        null,
                        null,
                        null,
                        List.of("authorization-denied"),
                        new org.j7m.middleware.api.RemoteError(org.j7m.middleware.api.MiddlewareErrorCode.PERMISSION_DENIED,
                                "nugget lifecycle operation denied", Map.of("reason", "authorization-denied"),
                                request.correlationId(), this.record.runtimeId(), false, false),
                        request.requestedAt(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Duration.ofMillis(1L),
                        Map.of("reason", "authorization-denied"));
            }
            if (this.client.maliciousLifecycleFailure) {
                return new NuggetApplicationLifecycle.Result(
                        "operation-test",
                        request.correlationId(),
                        request.action(),
                        true,
                        true,
                        request.dryRun(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.coordinate(),
                        request.instanceId(),
                        ApplicationRunState.RUNNING,
                        ApplicationRunState.RUNNING,
                        ApplicationRunState.RUNNING,
                        NuggetApplicationLifecycle.Outcome.REJECTED,
                        "application lifecycle operation failed",
                        false,
                        false,
                        true,
                        false,
                        true,
                        "",
                        "allowed",
                        null,
                        null,
                        null,
                        List.of("verification-failed"),
                        new org.j7m.middleware.api.RemoteError(
                                org.j7m.middleware.api.MiddlewareErrorCode.PERMISSION_DENIED,
                                "application lifecycle operation failed",
                                Map.of("reason", "verification-failed", "profile", "strict",
                                        "detailsOmitted", "true"),
                                request.correlationId(),
                                this.record.runtimeId(),
                                false,
                                false),
                        request.requestedAt(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Duration.ofMillis(1L),
                        Map.of("reason", "verification-failed"));
            }
            if (this.client.maliciousLifecyclePublicFields) {
                ApplicationDeploymentPlan plan = new ApplicationDeploymentPlan(
                        request.coordinate(),
                        ApplicationRunState.PLANNED,
                        List.of("verify bundle digest", "/secret/repository/key.pem", "stackTrace: line",
                                "Bearer abc-token", "s-e-c-r-e-t"));
                ApplicationDeploymentResult deploymentResult = new ApplicationDeploymentResult(
                        true,
                        ApplicationRunState.PLANNED,
                        "java.lang.IllegalStateException: provider raw message sentinel",
                        List.of("safe-result", "-----BEGIN-PRIVATE-KEY-----", "configuration-sentinel"));
                return new NuggetApplicationLifecycle.Result(
                        "operation-test",
                        request.correlationId(),
                        request.action(),
                        true,
                        true,
                        request.dryRun(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.coordinate(),
                        request.instanceId(),
                        null,
                        ApplicationRunState.PLANNED,
                        ApplicationRunState.PLANNED,
                        NuggetApplicationLifecycle.Outcome.DRY_RUN,
                        "profile=secret-token",
                        true,
                        false,
                        true,
                        false,
                        false,
                        "verification",
                        "allowed",
                        plan,
                        deploymentResult,
                        null,
                        List.of("manifest-sentinel", "plan-rejected"),
                        null,
                        request.requestedAt(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Duration.ofMillis(1L),
                        Map.of("result", NuggetApplicationLifecycle.Outcome.DRY_RUN.name()));
            }
            if (this.client.maliciousLifecycleReturnedFailure) {
                ApplicationDeploymentResult deploymentResult = new ApplicationDeploymentResult(
                        false,
                        ApplicationRunState.FAILED,
                        "java.lang.IllegalStateException: provider raw message sentinel",
                        List.of("safe-result", "-----BEGIN-PRIVATE-KEY-----", "configuration-sentinel"));
                return new NuggetApplicationLifecycle.Result(
                        "operation-test",
                        request.correlationId(),
                        request.action(),
                        true,
                        true,
                        request.dryRun(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.coordinate(),
                        request.instanceId(),
                        ApplicationRunState.RUNNING,
                        ApplicationRunState.FAILED,
                        ApplicationRunState.FAILED,
                        NuggetApplicationLifecycle.Outcome.FAILED,
                        "java.lang.IllegalStateException: provider raw message sentinel",
                        false,
                        false,
                        true,
                        false,
                        true,
                        "",
                        "allowed",
                        null,
                        deploymentResult,
                        null,
                        List.of(),
                        new org.j7m.middleware.api.RemoteError(
                                org.j7m.middleware.api.MiddlewareErrorCode.UNAVAILABLE,
                                "application lifecycle operation was not completed",
                                returnedFailureMetadata(request.action().name()),
                                request.correlationId(),
                                this.record.runtimeId(),
                                false,
                                false),
                        request.requestedAt(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Duration.ofMillis(1L),
                        returnedFailureMetadata(request.action().name()));
            }
            if (request.action() == NuggetApplicationLifecycle.Action.START && request.dryRun()) {
                ApplicationDeploymentPlan plan = new ApplicationDeploymentPlan(
                        request.coordinate(),
                        ApplicationRunState.PLANNED,
                        List.of("verify bundle digest", "create isolated runtime", "start application"));
                return lifecycleResult(request, NuggetApplicationLifecycle.Outcome.DRY_RUN, "application activation plan created",
                        null, plan, null);
            }
            ApplicationRunState state = switch (request.action()) {
                case START -> ApplicationRunState.RUNNING;
                case DRAIN -> ApplicationRunState.DRAINING;
                case STOP -> ApplicationRunState.STOPPED;
                case UNLOAD -> ApplicationRunState.UNLOADED;
            };
            ApplicationDeploymentResult result = new ApplicationDeploymentResult(true, state,
                    "application " + request.action().name().toLowerCase(java.util.Locale.ROOT), List.of("safe-result"));
            return lifecycleResult(request, NuggetApplicationLifecycle.Outcome.COMPLETED, result.message(), result, null, state);
        }

        @Override
        public NuggetControl.Result nuggetControl(final NuggetControl.Request request) {
            this.client.controlCallCount++;
            if (this.client.killDisabled && request.action() == NuggetControl.Action.KILL_NUGGET) {
                return controlResult(request, NuggetControl.Outcome.DISABLED,
                        "nugget control operation is disabled", false, false,
                        new org.j7m.middleware.api.RemoteError(
                                org.j7m.middleware.api.MiddlewareErrorCode.PERMISSION_DENIED,
                                "nugget control operation is disabled",
                                Map.of("reason", "kill-disabled"),
                                request.correlationId(),
                                this.record.runtimeId(),
                                false,
                                false),
                        null);
            }
            if (request.action() == NuggetControl.Action.SHUTDOWN_NUGGET && request.confirmation().isBlank()) {
                NuggetControl.Confirmation confirmation = new NuggetControl.Confirmation(
                        request.operationId(),
                        "phase77-confirmation",
                        Instant.parse("2026-01-01T00:01:00Z"),
                        request.action(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.targetGeneration(),
                        "runtime:j7m-cli",
                        NuggetState.RUNNING);
                return controlResult(request, NuggetControl.Outcome.CONFIRMATION_REQUIRED,
                        "confirmation required for nugget control operation", false, false, null, confirmation);
            }
            NuggetControl.Outcome outcome = request.action() == NuggetControl.Action.DRAIN_NUGGET
                    ? NuggetControl.Outcome.PARTIAL : NuggetControl.Outcome.ACCEPTED;
            return controlResult(request, outcome,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET
                            ? "nugget is draining" : "nugget control accepted",
                    true,
                    request.action() != NuggetControl.Action.DRAIN_NUGGET,
                    null,
                    null);
        }

        @Override
        public void close() {
            this.client.connectionCloseCount++;
            CountDownLatch closed = this.client.lateConnectionClosed;
            if (closed != null) {
                closed.countDown();
            }
        }

        private static NuggetStatus statusFor(final NuggetDiscovery.Record record) {
            NuggetIdentity identity = new NuggetIdentity(
                    record.nuggetId(),
                    record.nuggetName(),
                    new RuntimeIdentity(record.runtimeId(), "default", Map.of()),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "",
                    "LOCAL",
                    "0.7.4",
                    "25",
                    NuggetState.RUNNING,
                    Map.of("admin", "1"),
                    Map.of());
            NuggetConfigurationSummary configuration = new NuggetConfigurationSummary(
                    NuggetAdminState.ENABLED, true, false, NuggetPinSourceType.PROTECTED_VERIFIER_FILE,
                    false, "j7m-pin-v1", Map.of());
            return new NuggetStatus(identity, configuration, Instant.parse("2026-01-01T00:00:00Z"), Map.of());
        }

        private NuggetApplicationLifecycle.Result lifecycleResult(
                final NuggetApplicationLifecycle.Request request,
                final NuggetApplicationLifecycle.Outcome outcome,
                final String text,
                final ApplicationDeploymentResult deploymentResult,
                final ApplicationDeploymentPlan plan,
                final ApplicationRunState state) {
            return new NuggetApplicationLifecycle.Result(
                    "operation-test",
                    request.correlationId(),
                    request.action(),
                    true,
                    true,
                    request.dryRun(),
                    this.record.nuggetId(),
                    this.record.runtimeId(),
                    request.coordinate(),
                    request.instanceId(),
                    request.action() == NuggetApplicationLifecycle.Action.START ? null : ApplicationRunState.RUNNING,
                    state == null ? ApplicationRunState.PLANNED : state,
                    state == null ? ApplicationRunState.PLANNED : state,
                    outcome,
                    text,
                    true,
                    false,
                    true,
                    false,
                    false,
                    plan == null ? "" : plan.state().name(),
                    "allowed",
                    plan,
                    deploymentResult,
                    null,
                    List.of(),
                    null,
                    request.requestedAt(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ofMillis(1L),
                    Map.of("result", outcome.name()));
        }

        private NuggetControl.Result controlResult(
                final NuggetControl.Request request,
                final NuggetControl.Outcome outcome,
                final String text,
                final boolean accepted,
                final boolean inProgress,
                final org.j7m.middleware.api.RemoteError error,
                final NuggetControl.Confirmation confirmation) {
            return new NuggetControl.Result(
                    request.operationId(),
                    request.correlationId(),
                    request.action(),
                    request.mode(),
                    !request.admissionId().isBlank(),
                    error == null || error.code() != org.j7m.middleware.api.MiddlewareErrorCode.PERMISSION_DENIED,
                    this.record.nuggetId(),
                    this.record.runtimeId(),
                    request.targetGeneration(),
                    NuggetState.RUNNING,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET ? NuggetState.DRAINING : NuggetState.RUNNING,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET ? NuggetState.DRAINING : NuggetState.STOPPING,
                    outcome,
                    text,
                    accepted,
                    inProgress,
                    !inProgress,
                    outcome == NuggetControl.Outcome.PARTIAL,
                    inProgress,
                    false,
                    confirmation,
                    outcome == NuggetControl.Outcome.PARTIAL ? List.of("full-work-drain-deferred") : List.of(),
                    error,
                    request.requestedAt(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Duration.ofMillis(1L),
                    error == null ? Map.of("result", outcome.name()) : Map.of("reason", "kill-disabled"));
        }

        private static NuggetIntrospection.ComponentSummary component(final String component) {
            return new NuggetIntrospection.ComponentSummary(
                    component,
                    NuggetIntrospection.Availability.AVAILABLE,
                    NuggetIntrospection.HealthState.HEALTHY,
                    NuggetIntrospection.ReadinessState.READY,
                    true,
                    "ok",
                    Map.of());
        }

        private static List<NuggetIntrospection.ApplicationSummary> manyApplications() {
            List<NuggetIntrospection.ApplicationSummary> values = new ArrayList<>(NuggetIntrospection.MAX_LIMIT + 1);
            for (int index = 0; index <= NuggetIntrospection.MAX_LIMIT; index++) {
                String id = "bulk-app-" + index;
                values.add(applicationSummary(id, id + "-instance", "Bulk " + index, "RUNNING", 0,
                        NuggetIntrospection.HealthState.HEALTHY, NuggetIntrospection.ReadinessState.READY, ""));
            }
            return values;
        }

        private static List<NuggetIntrospection.ApplicationSummary> hugeApplications() {
            List<NuggetIntrospection.ApplicationSummary> values = new ArrayList<>(NuggetIntrospection.MAX_LIMIT + 1);
            String suffix = "-safe-diagnostic-value".repeat(40);
            for (int index = 0; index <= NuggetIntrospection.MAX_LIMIT; index++) {
                String id = "huge-app-" + index + suffix;
                values.add(applicationSummary(id, id + "-instance", "Huge " + index + suffix, "RUNNING", 0,
                        NuggetIntrospection.HealthState.HEALTHY, NuggetIntrospection.ReadinessState.READY, ""));
            }
            return values;
        }

        private static List<NuggetIntrospection.ObjectSummary> unicodeObjects() {
            return List.of(
                    objectSummary("latin-é-object", "latin", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 11L),
                    objectSummary("greek-Δοκιμή-object", "greek", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 12L),
                    objectSummary("cyrillic-Журнал-object", "cyrillic", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 13L),
                    objectSummary("cjk-存储对象", "cjk", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 14L),
                    objectSummary("emoji-\uD83D\uDE00-object", "emoji", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 15L),
                    objectSummary("combining-e\u0301-object", "combining", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 16L),
                    objectSummary("ansi-\u001B[31m-object", "ansi", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 17L));
        }

        private static List<NuggetIntrospection.ObjectSummary> hostileHugeObjects() {
            return List.of(hostileObjectSummary());
        }

        private static NuggetIntrospection.ObjectSummary hostileObjectSummary() {
            String hostile = ("hostile-\u001B[31m-field-\uD83D\uDE00-").repeat(20_000);
            return objectSummary(hostile, "hostile", NuggetIntrospection.ObjectVisibility.EXPORTED, "orders", 19L);
        }

        private static Map<String, String> hostileLabels() {
            Map<String, String> labels = new LinkedHashMap<>();
            String hostile = ("label-value-\u001B[31m-\uD83D\uDE00-").repeat(2_000);
            for (int index = 0; index < 256; index++) {
                labels.put("label-" + (1_000 - index), hostile + index);
            }
            return labels;
        }

        private static NuggetIntrospection.ApplicationSummary applicationSummary(
                final String applicationId,
                final String instanceId,
                final String displayName,
                final String state,
                final int exportedServiceCount,
                final NuggetIntrospection.HealthState health,
                final NuggetIntrospection.ReadinessState readiness,
                final String lastSafeErrorCode) {
            return new NuggetIntrospection.ApplicationSummary(
                    applicationId,
                    "com.example:" + applicationId + ":1.0.0",
                    instanceId,
                    displayName,
                    "1.0.0",
                    state,
                    "activation-" + applicationId,
                    exportedServiceCount,
                    health,
                    readiness,
                    lastSafeErrorCode,
                    Map.of());
        }

        private static NuggetIntrospection.ObjectSummary objectSummary(
                final String id,
                final String service,
                final NuggetIntrospection.ObjectVisibility visibility,
                final String ownerApplication,
                final long generation) {
            return new NuggetIntrospection.ObjectSummary(
                    "nugget-a",
                    id,
                    service + "/" + id,
                    service,
                    "service",
                    visibility,
                    "EXECUTED",
                    visibility == NuggetIntrospection.ObjectVisibility.EXPORTED ? "GLOBAL" : "LOCAL",
                    ownerApplication,
                    List.of("com.example." + service + ".Service"),
                    "default",
                    true,
                    "<redacted>",
                    "local",
                    NuggetIntrospection.HealthState.HEALTHY,
                    NuggetIntrospection.ReadinessState.READY,
                    Instant.parse("2026-01-01T00:10:00Z").toEpochMilli(),
                    generation,
                    9L,
                    1L,
                    Instant.EPOCH,
                    "",
                    false,
                    Map.of());
        }

        private static <T> NuggetIntrospection.Result<T> success(
                final NuggetIntrospection.Category category,
                final T value) {
            return new NuggetIntrospection.Result<>(true, true, category,
                    Instant.parse("2026-01-01T00:00:00Z"), false, value, null, List.of(), Map.of());
        }

        private static <T> NuggetIntrospection.Result<T> denied(final NuggetIntrospection.Category category) {
            return new NuggetIntrospection.Result<>(true, false, category,
                    Instant.parse("2026-01-01T00:00:00Z"), false, null, null, List.of(), Map.of());
        }

        private static boolean resultAllowed(final NuggetIntrospection.Result<?> result) {
            return result.admitted() && result.authorized() && result.value() != null;
        }

        private static <T> NuggetIntrospection.CollectionResult<T> collection(
                final List<T> values,
                final NuggetIntrospection.Query query) {
            return collection(values, query, false);
        }

        private static <T> NuggetIntrospection.CollectionResult<T> collection(
                final List<T> values,
                final NuggetIntrospection.Query query,
                final boolean partial) {
            int from = Math.min(query.offset(), values.size());
            int to = Math.min(from + query.limit(), values.size());
            List<T> selected = values.subList(from, to);
            return new NuggetIntrospection.CollectionResult<>(
                    selected,
                    query.offset(),
                    query.limit(),
                    selected.size(),
                    values.size(),
                    true,
                    query.sortBy(),
                    query.direction(),
                    query.filters(),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    partial,
                    Map.of());
        }

        private static List<NuggetIntrospection.RecentErrorEntry> filterRecentErrors(
                final List<NuggetIntrospection.RecentErrorEntry> values,
                final NuggetIntrospection.RecentErrorQuery query) {
            if (query.component().isBlank()) {
                return values;
            }
            List<NuggetIntrospection.RecentErrorEntry> filtered = new ArrayList<>();
            for (NuggetIntrospection.RecentErrorEntry value : values) {
                if (query.component().equals(value.component())) {
                    filtered.add(value);
                }
            }
            return filtered;
        }

        private static List<NuggetIntrospection.ApplicationSummary> filterApplications(
                final List<NuggetIntrospection.ApplicationSummary> values,
                final NuggetIntrospection.Query query) {
            List<NuggetIntrospection.ApplicationSummary> filtered = new ArrayList<>();
            for (NuggetIntrospection.ApplicationSummary value : values) {
                if (query.filters().get("state") != null && !value.state().equals(query.filters().get("state"))) {
                    continue;
                }
                if (query.filters().get("applicationId") != null
                        && !value.applicationId().equals(query.filters().get("applicationId"))) {
                    continue;
                }
                filtered.add(value);
            }
            return filtered;
        }

        private static List<NuggetIntrospection.ObjectSummary> filterObjects(
                final List<NuggetIntrospection.ObjectSummary> values,
                final NuggetIntrospection.Query query) {
            List<NuggetIntrospection.ObjectSummary> filtered = new ArrayList<>();
            for (NuggetIntrospection.ObjectSummary value : values) {
                if (query.filters().get("visibility") != null
                        && !value.visibility().name().equals(query.filters().get("visibility"))) {
                    continue;
                }
                if (query.filters().get("serviceName") != null
                        && !value.serviceName().equals(query.filters().get("serviceName"))) {
                    continue;
                }
                filtered.add(value);
            }
            return filtered;
        }

        private static Map<String, String> largeSafeMap() {
            Map<String, String> values = new LinkedHashMap<>();
            String safeValue = "safe-diagnostic-value-".repeat(40);
            for (int index = 0; index < 256; index++) {
                values.put("safe-key-" + index, safeValue + index);
            }
            return values;
        }
    }
}
