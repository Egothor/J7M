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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Checks the public operations manual against implemented administration
 * families and the provider, authorization and uncertain-outcome boundaries.
 */
public final class NuggetPhase7DocumentationConsistencyTest {
    private static final Path ROOT = repositoryRoot();

    @Test
    void architectureDocumentReflectsImplementedOperationsSurface() throws IOException {
        String architecture = read("documentation/docs/overview/architecture.md");
        String inspection = read("documentation/docs/operations/inspection.md");
        String contract = read("documentation/docs/reference/admin-api.md");

        assertContains(architecture, "A Nugget is the host");
        assertContains(architecture, "ApplicationManager");
        assertContains(inspection, "provider-backed `nugget` command family");
        assertContains(inspection, "concrete connection/discovery provider must be installed");
        assertContains(contract, "Discovery | `NuggetDiscoveryService`");
        assertContains(contract, "Admission | `authenticate(NuggetAuthenticationRequest, char[])`");
        assertContains(contract, "Introspection | `NuggetIntrospection`");
        assertContains(contract, "Application lifecycle | `NuggetApplicationLifecycle`");
        assertContains(contract, "Nugget control | `NuggetControl`");
        assertContains(contract, "A timeout after acceptance can leave an effect in progress or uncertain");
        assertContains(contract, "Preserve its operation identity and reconcile/status-check it");
    }

    @Test
    void operationsDocumentsPreserveProviderAndTransportBoundaries() throws IOException {
        String admin = read("documentation/docs/reference/admin-api.md");
        String cli = read("documentation/docs/reference/cli.md");
        String json = read("documentation/docs/reference/admin-json.md");
        String operations = read("documentation/docs/operations/index.md");

        assertContains(admin, "transport-neutral administration");
        assertContains(admin, "It starts no listener and grants no authorization");
        assertContains(admin, "read-only introspection");
        assertContains(admin, "Servers still enforce their own deadlines");
        assertContains(admin, "do not silently replay a lifecycle mutation against a new target generation");
        assertContains(cli, "installed `NuggetCliClientProvider`");
        assertContains(cli, "does not install a network listener or remote administration protocol");
        assertContains(cli, "start COORDINATE");
        assertContains(cli, "shutdown-nugget NUGGET");
        assertContains(cli, "kill-nugget NUGGET");
        assertContains(cli, "Kill remains disabled unless the host explicitly implements and enables it");
        assertContains(cli, "does not automatically retry an uncertain mutation against a replacement process");
        assertContains(cli, "--wide");
        assertContains(cli, "--dry-run");
        assertContains(json, "Extra properties and unsupported variants are rejected");
        assertContains(json, "does not infer an HTTP route, listener, authentication header, browser session");
        assertContains(operations, "does not imply a browser administration console, a fleet-wide controller");
    }

    @Test
    void phaseSevenDocumentsAndEvidenceDoNotMakeForbiddenHardeningClaims() throws IOException {
        String text = read("documentation/docs/reference/admin-api.md")
                + read("documentation/docs/reference/cli.md")
                + read("documentation/docs/security/administrative-pin.md")
                + read("documentation/docs/security/isolation-providers.md")
                + read("j7m-tests/build.gradle");

        assertStaleClaimAbsent(text, "PIN admission is complete authorization");
        assertStaleClaimAbsent(text, "PIN admission provides complete authorization");
        assertStaleClaimAbsent(text, "arbitrary OS process kill is supported");
        assertStaleClaimAbsent(text, "arbitrary shell execution is supported");
        assertStaleClaimAbsent(text, "active web API is implemented");
        assertStaleClaimAbsent(text, "browser console is implemented");
        assertStaleClaimAbsent(text, "external control plane integration is implemented");
        assertStaleClaimAbsent(text, "generic exactly-once distributed execution");
        assertStaleClaimAbsent(text, "distributed ACID transactions are provided");
        assertStaleClaimAbsent(text, "certified sandbox");
        assertStaleClaimAbsent(text, "complete sandboxing is provided");
        assertStaleClaimAbsent(text, "sensitive environment values are safe to expose");
        assertStaleClaimAbsent(text, "sensitive JVM argument values are safe to expose");
    }

    private static String read(final String relativePath) throws IOException {
        Path path = ROOT.resolve(relativePath).normalize();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("README.md"))
                    && Files.isDirectory(current.resolve("documentation/docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }

    private static void assertContains(final String text, final String expected) {
        assertTrue(text.contains(expected), "missing documentation text: " + expected);
    }

    private static void assertStaleClaimAbsent(final String text, final String staleClaim) {
        assertFalse(text.contains(staleClaim), "stale documentation claim returned: " + staleClaim);
    }
}
