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

package org.j7m.examples.nugget.console;

import java.util.List;
import java.util.Objects;

/**
 * Copyable operator runbook constants for the J7M nugget administration
 * console example.
 *
 * <p>The type deliberately stores command arguments without PIN values. The
 * production CLI reads admission material from a caller-selected environment
 * variable or an interactive prompt; the runbook records only the safe variable
 * name and deterministic target identifiers used by the local demo. Instances
 * are immutable and thread-safe.</p>
 */
public final class NuggetAdminConsoleRunbook {
    /**
     * Safe environment variable name used by the example commands.
     */
    public static final String PIN_ENVIRONMENT = "J7M_DEMO_PIN";

    /**
     * Deterministic nugget identifier used by the local provider-backed demo.
     */
    public static final String NUGGET_ID = "demo-nugget";

    /**
     * Deterministic nugget name used by the local provider-backed demo.
     */
    public static final String NUGGET_NAME = "phase7-demo";

    /**
     * Deterministic application coordinate used by the existing lifecycle demo
     * fixture.
     */
    public static final String LIFECYCLE_COORDINATE = "org.j7m.examples:hello-remote-service:1.0.0";

    /**
     * Deterministic nugget identifier used by the existing lifecycle demo
     * fixture.
     */
    public static final String LIFECYCLE_NUGGET_ID = "phase76-lifecycle-nugget";

    private NuggetAdminConsoleRunbook() {
    }

    /**
     * Returns the safe production CLI command used to discover local nugget
     * records.
     *
     * @return immutable command arguments
     */
    public static List<String> discoverCommand() {
        return List.of("j7m", "nugget", "discover");
    }

    /**
     * Returns the safe production CLI command used to read selected-nugget
     * information with an environment-sourced PIN.
     *
     * @param nuggetName selected nugget name
     * @param pinEnvironment environment variable name containing the PIN
     * @return immutable command arguments
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is blank
     */
    public static List<String> infoCommand(final String nuggetName, final String pinEnvironment) {
        String safeNuggetName = requireText(nuggetName, "nuggetName");
        String safePinEnvironment = requireText(pinEnvironment, "pinEnvironment");
        return List.of("j7m", "nugget", "info", "--target", safeNuggetName, "--pin-env", safePinEnvironment);
    }

    /**
     * Returns the safe production CLI command used to read selected-nugget
     * status with an environment-sourced PIN.
     *
     * @param nuggetName selected nugget name
     * @param pinEnvironment environment variable name containing the PIN
     * @return immutable command arguments
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is blank
     */
    public static List<String> statusCommand(final String nuggetName, final String pinEnvironment) {
        String safeNuggetName = requireText(nuggetName, "nuggetName");
        String safePinEnvironment = requireText(pinEnvironment, "pinEnvironment");
        return List.of("j7m", "nugget", "status", "--target", safeNuggetName, "--pin-env", safePinEnvironment);
    }

    /**
     * Returns the safe production CLI commands used for representative
     * selected-nugget inspection with an environment-sourced PIN.
     *
     * @param nuggetName selected nugget name
     * @param pinEnvironment environment variable name containing the PIN
     * @return immutable command argument lists
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is blank
     */
    public static List<List<String>> inspectionCommands(final String nuggetName, final String pinEnvironment) {
        String safeNuggetName = requireText(nuggetName, "nuggetName");
        String safePinEnvironment = requireText(pinEnvironment, "pinEnvironment");
        return List.of(
                List.of("j7m", "nugget", "ps", "--target", safeNuggetName, "--pin-env", safePinEnvironment, "--wide"),
                List.of("j7m", "nugget", "objects", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "apps", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "env", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "disk", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "health", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "readiness", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "metrics", "--target", safeNuggetName, "--pin-env", safePinEnvironment),
                List.of("j7m", "nugget", "errors", "--target", safeNuggetName, "--pin-env", safePinEnvironment));
    }

    /**
     * Returns the safe production CLI command used to plan an application start
     * through the verified nugget application-lifecycle path.
     *
     * @param coordinate application coordinate
     * @param nuggetId selected nugget identifier
     * @param pinEnvironment environment variable name containing the PIN
     * @return immutable command arguments
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is blank
     */
    public static List<String> lifecycleDryRunCommand(
            final String coordinate,
            final String nuggetId,
            final String pinEnvironment) {
        String safeCoordinate = requireText(coordinate, "coordinate");
        String safeNuggetId = requireText(nuggetId, "nuggetId");
        String safePinEnvironment = requireText(pinEnvironment, "pinEnvironment");
        return List.of("j7m", "nugget", "start", safeCoordinate, "--dry-run",
                "--id", safeNuggetId, "--pin-env", safePinEnvironment);
    }

    /**
     * Returns the safe production CLI command used to prove that dangerous
     * nugget termination is disabled by default.
     *
     * @param nuggetName selected nugget name
     * @param pinEnvironment environment variable name containing the PIN
     * @return immutable command arguments
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is blank
     */
    public static List<String> killDisabledCommand(final String nuggetName, final String pinEnvironment) {
        String safeNuggetName = requireText(nuggetName, "nuggetName");
        String safePinEnvironment = requireText(pinEnvironment, "pinEnvironment");
        return List.of("j7m", "nugget", "kill-nugget", safeNuggetName, "--target", safeNuggetName,
                "--pin-env", safePinEnvironment, "--confirm", "phase77-confirmation");
    }

    /**
     * Returns expected safe evidence markers printed by the deterministic
     * example smoke flow.
     *
     * @return immutable expected-output markers
     */
    public static List<String> expectedOutputMarkers() {
        return List.of(
                "phase7 nugget cli demo: production-entrypoint",
                "phase7 nugget cli inspection commands: ps objects apps env disk health readiness metrics errors",
                "phase7 nugget cli exit code: 0",
                "phase7 nugget cli all-nugget prompt visible: true",
                "phase7 nugget cli discovery visible: true",
                "phase7 nugget cli all-nugget target visible: true",
                "phase7 nugget cli selected-nugget prompt visible: true",
                "phase7 nugget cli selected-nugget target visible: true",
                "phase7 nugget cli status command visible: true",
                "phase7 nugget cli returned all-nugget mode: true",
                "phase7 nugget cli json envelope: true",
                "phase7 nugget cli ps output: true",
                "phase7 nugget cli no secret leakage: true",
                "phase7.6 nugget cli lifecycle demo: real-application-manager",
                "phase7.6 nugget cli lifecycle discovery/select/auth: true",
                "phase7.6 nugget cli lifecycle start dry-run: true",
                "phase7.6 nugget cli lifecycle start exact coordinate: true",
                "phase7.6 nugget cli lifecycle apps status: true",
                "phase7.6 nugget cli lifecycle drain: true",
                "phase7.6 nugget cli lifecycle stop: true",
                "phase7.6 nugget cli lifecycle service after stop candidates: 0",
                "phase7.6 nugget cli lifecycle unload: true",
                "phase7.6 nugget cli lifecycle audit safe redaction: true",
                "phase7.7 nugget cli kill-disabled demo: public-contracts",
                "phase7.7 nugget cli discovery exact target auth: true",
                "phase7.7 nugget cli drain safe partial: true",
                "phase7.7 nugget cli shutdown confirmation-required: true",
                "phase7.7 nugget cli kill disabled despite confirmation: true",
                "phase7.7 nugget cli no arbitrary process control: true",
                "phase7.7 nugget cli no jvm termination: true",
                "phase7.7 nugget cli audit/output safe: true",
                "phase7 nugget pin failure demo: production-cli-entrypoint",
                "phase7 nugget pin failure cli exit non-success: true",
                "phase7 nugget pin failure cli denial stable: true",
                "phase7 nugget pin failure outcome: REJECTED",
                "phase7 nugget pin failure accepted: false",
                "phase7 nugget pin failure secret printed: false");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
