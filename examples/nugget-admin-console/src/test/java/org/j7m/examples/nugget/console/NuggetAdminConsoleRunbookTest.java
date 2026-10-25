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

import java.util.ArrayList;
import java.util.List;

/**
 * Smoke checks for the copyable nugget admin console runbook.
 */
public final class NuggetAdminConsoleRunbookTest {
    private static final String PIN_SENTINEL = "740001";

    private NuggetAdminConsoleRunbookTest() {
    }

    /**
     * Executes deterministic runbook checks and prints the command forms that
     * the root validator compares with the README.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        List<List<String>> commands = testedCommands();
        commandsDoNotContainPinMaterial(commands);
        statusCommandUsesCurrentCliGrammar(commands.get(2));
        for (List<String> command : commands) {
            System.out.println(commandLine(command));
        }
    }

    /**
     * Verifies that the runbook never places a PIN value in command arguments.
     *
     * @param commands command argument lists to inspect
     */
    public static void commandsDoNotContainPinMaterial(final List<List<String>> commands) {
        for (List<String> command : commands) {
            if (command.contains(PIN_SENTINEL)) {
                throw new AssertionError("runbook command exposed PIN material");
            }
        }
    }

    private static List<List<String>> testedCommands() {
        List<List<String>> commands = new ArrayList<>(14);
        commands.add(NuggetAdminConsoleRunbook.discoverCommand());
        commands.add(NuggetAdminConsoleRunbook.infoCommand(
                NuggetAdminConsoleRunbook.NUGGET_NAME,
                NuggetAdminConsoleRunbook.PIN_ENVIRONMENT));
        commands.add(NuggetAdminConsoleRunbook.statusCommand(
                NuggetAdminConsoleRunbook.NUGGET_NAME,
                NuggetAdminConsoleRunbook.PIN_ENVIRONMENT));
        commands.addAll(NuggetAdminConsoleRunbook.inspectionCommands(
                NuggetAdminConsoleRunbook.NUGGET_NAME,
                NuggetAdminConsoleRunbook.PIN_ENVIRONMENT));
        commands.add(NuggetAdminConsoleRunbook.lifecycleDryRunCommand(
                NuggetAdminConsoleRunbook.LIFECYCLE_COORDINATE,
                NuggetAdminConsoleRunbook.LIFECYCLE_NUGGET_ID,
                NuggetAdminConsoleRunbook.PIN_ENVIRONMENT));
        commands.add(NuggetAdminConsoleRunbook.killDisabledCommand(
                NuggetAdminConsoleRunbook.NUGGET_NAME,
                NuggetAdminConsoleRunbook.PIN_ENVIRONMENT));
        return List.copyOf(commands);
    }

    private static void statusCommandUsesCurrentCliGrammar(final List<String> status) {
        if (!status.contains("--target")) {
            throw new AssertionError("status command must use --target with the current CLI grammar");
        }
        if (status.contains("--id")) {
            throw new AssertionError("status command must not use --id");
        }
        int pinOption = status.indexOf("--pin-env");
        if (pinOption < 0 || pinOption + 1 >= status.size()) {
            throw new AssertionError("status command must use --pin-env");
        }
        if (!NuggetAdminConsoleRunbook.PIN_ENVIRONMENT.equals(status.get(pinOption + 1))) {
            throw new AssertionError("status command used an unexpected PIN environment variable");
        }
    }

    private static String commandLine(final List<String> command) {
        String line = String.join(" ", command);
        if (line.contains(PIN_SENTINEL)) {
            throw new AssertionError("runbook command exposed PIN material");
        }
        return line;
    }
}
