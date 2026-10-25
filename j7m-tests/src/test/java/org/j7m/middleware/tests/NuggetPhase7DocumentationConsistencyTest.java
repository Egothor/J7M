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
 * Deterministic documentation consistency checks for the implemented
 * Phase 7.0-7.11 nugget operations-plane surface.
 */
public final class NuggetPhase7DocumentationConsistencyTest {
    private static final Path ROOT = repositoryRoot();

    @Test
    void architectureDocumentReflectsImplementedPhaseSevenSurface() throws IOException {
        String architecture = read("docs/internal/03000 - Architecture Decisions/phase-7-nugget-operations-plane.md");

        assertContains(architecture, "Current Implementation Status Through Phase 7.11");
        assertContains(architecture, "Public operations contracts | Implemented");
        assertContains(architecture, "Headless in-process admin runtime | Implemented");
        assertContains(architecture, "PIN admission and verifier ownership | Implemented");
        assertContains(architecture, "Local and configured discovery | Implemented");
        assertContains(architecture, "CLI nugget console | Implemented");
        assertContains(architecture, "Expanded Phase 7.5 inspection surface | Implemented-ready-for-terminal-verification");
        assertContains(architecture, "Application lifecycle mutations | Implemented-ready-for-terminal-verification");
        assertContains(architecture, "Nugget shutdown and kill controls | Implemented-ready-for-Phase-7.7-focused-review");
        assertContains(architecture, "Admin API contract for future Web/API | Implemented-ready-for-Phase-7.8-focused-review");
        assertContains(architecture, "server-owned callback timeout monitoring");
        assertContains(architecture, "retained timeout/unknown replay status");
        assertContains(architecture, "Phase 7.9 security hardening | Implemented-ready-for-Phase-7.9-focused-review");
        assertContains(architecture, "Phase 7.10 demos, examples, docs, and evidence | Implemented-ready-for-Phase-7.10-focused-review");
        assertContains(architecture, "Phase 7.11 final operations-plane acceptance evidence | Implemented-ready-for-owner-commit-and-final-review");
        assertContains(architecture, "Script mode, remote transport, web/API adapter, and deep invocation drain gate | Deferred");
        assertContains(architecture, "Phase 7.0-7.11 Availability Confirmation");

        assertStaleClaimAbsent(architecture, "without starting the runtime endpoint");
        assertStaleClaimAbsent(architecture, "PIN runtime, discovery runtime");
        assertStaleClaimAbsent(architecture, "It is not an interactive distributed operations shell yet");
        assertStaleClaimAbsent(architecture, "Phase 7.0 does not implement the protocol");
        assertStaleClaimAbsent(architecture, "no Phase 7 production API, runtime, discovery, or CLI implementation exists yet");
        assertStaleClaimAbsent(architecture, "Phase 7.0 does not implement the nugget admin endpoint");
    }

    @Test
    void operationsDocumentsKeepRemoteTransportAndLaterFeaturesDeferred() throws IOException {
        String admin = read("docs/internal/01000 - Normative Specifications/nugget-admin.md");
        String cli = read("docs/internal/01000 - Normative Specifications/j7m-cli-console.md");

        assertContains(admin, "in-process headless admission");
        assertContains(admin, "read-only introspection");
        assertContains(admin, "Phase 7.3 headless");
        assertContains(admin, "local/configured nugget discovery");
        assertContains(admin, "Phase 7.7 nugget controls");
        assertContains(admin, "Phase 7.8 adds additive public admin-contract primitives");
        assertContains(admin, "server-owned control execution budgets");
        assertContains(admin, "Replay with the same operation id returns the retained");
        assertContains(admin, "without canceling, interrupting, or awaiting the current callback's own future");
        assertContains(admin, "self-aware cleanup does not suppress the");
        assertContains(admin, "network administration protocol");
        assertContains(admin, "remain future Phase 7 work");

        assertContains(cli, "interactive nugget shell");
        assertContains(cli, "one-shot nugget commands");
        assertContains(cli, "does");
        assertContains(cli, "not implement a concrete network administration");
        assertContains(cli, "Phase 7.7 shell commands");
        assertContains(cli, "representative inspection");
        assertContains(cli, "ps --wide");
        assertContains(cli, "start COORDINATE [--dry-run]");
        assertContains(cli, "shutdown-nugget NUGGET");
        assertContains(cli, "kill-nugget NUGGET");
        assertContains(cli, "Script or command-stream mode is not implemented");
        assertContains(cli, "does not persist command history");

        String contract = read("docs/internal/01000 - Normative Specifications/nugget-admin-api-contract.md");
        String json = read("docs/internal/01000 - Normative Specifications/nugget-admin-json.md");
        String resourceMap = read("docs/internal/03000 - Architecture Decisions/nugget-web-api-resource-map.md");

        assertContains(contract, "unsupported request protocol version");
        assertContains(json, "Unknown fields");
        assertContains(resourceMap, "/nuggets/{id}/objects/{objectId}");
    }

    @Test
    void phaseSevenDocumentsAndEvidenceDoNotMakeForbiddenHardeningClaims() throws IOException {
        String text = read("docs/internal/01000 - Normative Specifications/nugget-admin.md")
                + read("docs/internal/01000 - Normative Specifications/j7m-cli-console.md")
                + read("docs/internal/06000 - Security Reviews/nugget-pin-security.md")
                + read("docs/internal/02000 - Milestone Plans/J7M_Phase_7_Nugget_Operations_Plane_Milestones.md")
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
                    && Files.isDirectory(current.resolve("docs"))) {
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
