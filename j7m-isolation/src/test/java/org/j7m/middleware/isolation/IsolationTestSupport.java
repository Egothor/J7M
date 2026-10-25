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

package org.j7m.middleware.isolation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.isolation.EnvironmentPolicy;
import org.j7m.middleware.api.isolation.EnvironmentVariableRule;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.FileSystemRule;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicy;
import org.j7m.middleware.api.isolation.IsolationProcessSpec;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.j7m.middleware.api.isolation.IsolationSubject;
import org.j7m.middleware.api.isolation.IsolationSubjectId;
import org.j7m.middleware.api.isolation.NetworkEgressPolicy;
import org.j7m.middleware.api.isolation.NetworkEgressRule;
import org.j7m.middleware.api.isolation.ResourceBudget;
import org.j7m.middleware.api.isolation.StderrPolicy;
import org.j7m.middleware.api.isolation.StdoutPolicy;
import org.j7m.middleware.api.isolation.StreamCapturePolicy;

final class IsolationTestSupport {
    static final String SECRET = "TOKEN=secret-value-987";

    private IsolationTestSupport() {
    }

    static IsolationSubject subject() {
        return new IsolationSubject(new IsolationSubjectId("subject-a"), "app-a", "principal-a",
                Map.of("safe", "visible", "apiKey", "secret-value-987"));
    }

    static IsolationConfig config(final Path directory) {
        return new IsolationConfig(directory, java.time.Clock.systemUTC(), ResourceBudget.defaults(), 256);
    }

    static IsolationPolicy allowingPolicy(final Path directory) {
        return new IsolationPolicy(
                new FileSystemPolicy(true, List.of(
                        new FileSystemRule(FileSystemAccess.READ, directory, true, false),
                        new FileSystemRule(FileSystemAccess.WRITE, directory, true, false))),
                new NetworkEgressPolicy(true, true, true, List.of(new NetworkEgressRule("localhost", -1, true))),
                new EnvironmentPolicy(true, 128, 512, List.of(
                        new EnvironmentVariableRule("SAFE", true, false),
                        new EnvironmentVariableRule("TOKEN", true, true))),
                new StdoutPolicy(new StreamCapturePolicy(32, true, List.of("secret-value-987"))),
                new StderrPolicy(new StreamCapturePolicy(32, true, List.of("secret-value-987"))));
    }

    static List<String> javaCommand(final String mode) {
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        final ArrayList<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(IsolationChild.class.getName());
        command.add(mode);
        return command;
    }

    static IsolationExecutionRequest request(
            final Path directory,
            final List<String> command,
            final Duration timeout) {
        return new IsolationExecutionRequest(subject(), IsolationMode.LOCAL_PROCESS,
                new IsolationProcessSpec(command, directory, Map.of("SAFE", "ok", "TOKEN", SECRET)),
                allowingPolicy(directory), ResourceBudget.defaults(), timeout, "corr-a");
    }

    static LocalProcessIsolationProvider localProvider(final Path directory) {
        return new LocalProcessIsolationProvider(new IsolationProviderId("local-test"), config(directory));
    }

    static InProcessIsolationProvider inProcessProvider(final Path directory) {
        return new InProcessIsolationProvider(new IsolationProviderId("in-process-test"), config(directory));
    }
}
