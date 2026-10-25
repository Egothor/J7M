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

package org.j7m.middleware.tests.fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.J7mApplication;

/**
 * Test application that records lifecycle callbacks for activation rollback
 * assertions.
 */
public final class FailingExportApplication implements J7mApplication {
    /**
     * Creates the test application.
     */
    public FailingExportApplication() {
    }

    @Override
    public void initialize(final ApplicationContext context) {
    }

    @Override
    public void start(final ApplicationContext context) {
        writeMarker("start");
        waitForRelease();
    }

    @Override
    public void drain(final ApplicationContext context) {
    }

    @Override
    public void stop(final ApplicationContext context) {
        writeMarker("stop");
        if (Boolean.getBoolean("j7m.test.exportFailureStopInterrupted")) {
            throw new IllegalStateException("fixture stop cleanup interrupted", new InterruptedException("interrupted"));
        }
        if (Boolean.getBoolean("j7m.test.exportFailureStopFails")) {
            throw new IllegalStateException("fixture stop cleanup failure with PRIVATE detail");
        }
    }

    private static void writeMarker(final String marker) {
        String markerPath = System.getProperty("j7m.test.exportFailureMarker");
        if (markerPath == null || markerPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(markerPath), marker + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write test lifecycle marker", exception);
        }
    }

    private static void waitForRelease() {
        String releasePath = System.getProperty("j7m.test.exportFailureRelease");
        if (releasePath == null || releasePath.isBlank()) {
            return;
        }
        Path release = Path.of(releasePath);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!Files.exists(release)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("test lifecycle release was not observed");
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test lifecycle release interrupted", exception);
            }
        }
    }
}
