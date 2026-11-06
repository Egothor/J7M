/* Copyright (c) 2026, Leo Galambos and the Egothor team
 * SPDX-License-Identifier: BSD-4-Clause
 * Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
 */
package org.j7m.middleware.deployer.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration failures are rejected before any application can be activated. */
class J7mPlatformTest {
    @TempDir Path directory;

    @Test void mainDescribesTheInstalledCommand() {
        var original = System.out;
        var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output));
            J7mPlatform.main(new String[]{"--help"});
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("--config FILE"));
    }

    @Test void missingRequiredSettingsFailBeforeOpeningTheRuntime() throws Exception {
        Path configuration = directory.resolve("platform.properties");
        Files.writeString(configuration, "runtime.id=test\n");
        var output = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> J7mPlatform.start(configuration, new PrintStream(output)));
        assertEquals("", output.toString());
    }

    @Test void unknownSettingsCannotSilentlyDisableSignatureVerification() throws Exception {
        Path configuration = directory.resolve("platform.properties");
        Files.writeString(configuration, "signature.required=false\n");
        assertThrows(IllegalArgumentException.class, () -> J7mPlatform.start(configuration, System.out));
    }

    @Test void configurationSizeIsBoundedBeforeParsing() throws Exception {
        Path configuration = directory.resolve("platform.properties");
        Files.writeString(configuration, "a".repeat(65537));
        assertThrows(IllegalArgumentException.class, () -> J7mPlatform.start(configuration, System.out));
    }
}
