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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveConfigurationJPMSIT {
    @TempDir Path temporary;

    @Test
    void typedRecordUsesSelectiveExportsInASeparateJvmWithoutOpens() throws Exception {
        var sources = Files.createDirectories(this.temporary.resolve("source/cfg"));
        var output = Files.createDirectories(this.temporary.resolve("classes"));
        Files.writeString(sources.getParent().resolve("module-info.java"), """
                module configuration.consumer {
                    requires org.j7m.api;
                    requires org.j7m.application.platform;
                    exports cfg to org.j7m.application.platform;
                }
                """);
        Files.writeString(sources.resolve("Settings.java"), """
                package cfg;
                @org.j7m.middleware.annotations.ConfigProperties(prefix="settings", refresh=true)
                public record Settings(int count) { }
                """);
        Files.writeString(sources.resolve("Main.java"), """
                package cfg;
                public final class Main {
                    public static void main(String[] args) {
                        try (var owner = new org.j7m.middleware.application.components.LiveConfiguration(
                                1, java.util.Map.of("settings.count", "3"))) {
                            var binding = owner.bindProperties(Settings.class, value -> { });
                            if (binding.get().count() != 3) throw new AssertionError();
                            owner.update(2, java.util.Map.of("settings.count", "4"));
                            if (binding.snapshot().version() != 2 || binding.get().count() != 4) throw new AssertionError();
                            System.out.println("CONFIG_JPMS_OK");
                        }
                    }
                }
                """);
        final String modulePath = modulePath();
        run(List.of(tool("javac"), "-proc:none", "--module-path", modulePath, "-d", output.toString(),
                sources.getParent().resolve("module-info.java").toString(), sources.resolve("Settings.java").toString(),
                sources.resolve("Main.java").toString()));
        String result = run(List.of(tool("java"), "--module-path", output + File.pathSeparator + modulePath,
                "--module", "configuration.consumer/cfg.Main"));
        assertTrue(result.contains("CONFIG_JPMS_OK"), result);
    }

    private static String modulePath() {
        var paths = Arrays.stream(System.getProperty("j7m.artifact.test.classpath").split(File.pathSeparator))
                .map(Path::of).filter(Files::exists).toArray(Path[]::new);
        var finder = ModuleFinder.of(paths);
        return String.join(File.pathSeparator, List.of("org.j7m.api", "org.j7m.application.platform", "org.j7m.runtime",
                "org.j7m.kernel", "org.j7m.durablelog").stream().map(name ->
                Path.of(finder.find(name).orElseThrow().location().orElseThrow()).toString()).toList());
    }

    private static String run(final List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Configuration JPMS process deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }

    private static String tool(final String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
