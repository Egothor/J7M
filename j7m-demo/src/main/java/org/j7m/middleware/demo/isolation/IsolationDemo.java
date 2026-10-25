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

package org.j7m.middleware.demo.isolation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.isolation.CapturedOutput;
import org.j7m.middleware.api.isolation.EnvironmentPolicy;
import org.j7m.middleware.api.isolation.EnvironmentVariableRule;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.FileSystemRule;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicy;
import org.j7m.middleware.api.isolation.IsolationPolicyDecision;
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
import org.j7m.middleware.isolation.DefaultIsolationProvider;
import org.j7m.middleware.isolation.IsolationConfig;

/**
 * Phase 6.8 first-party isolation provider demo.
 */
public final class IsolationDemo {
    // J7M teaching: this block shows isolation policy metadata without implying a container runtime or OS sandbox guarantee.
    private IsolationDemo() {
    }

    /**
     * Runs either the parent demo or a deterministic child process used by the
     * local process provider.
     *
     * @param args command arguments
     * @throws Exception when the demo cannot create temporary directories or run a child process
     */
    public static void main(final String[] args) throws Exception {
        if (args.length > 0 && "child-secret".equals(args[0])) {
            System.out.println("token=demo-secret-value");
            System.err.println("password=demo-secret-value");
            return;
        }
        if (args.length > 0 && "child-sleep".equals(args[0])) {
            Thread.sleep(5_000L);
            return;
        }
        runParentDemo();
    }

    private static void runParentDemo() throws Exception {
        final Path root = Files.createTempDirectory("j7m-isolation-demo-");
        final Path allowed = Files.createDirectories(root.resolve("allowed"));
        final Path denied = root.resolve("denied");
        final IsolationSubject subject = new IsolationSubject(new IsolationSubjectId("demo-application"),
                "demo-application", "demo-principal", Map.of("purpose", "phase-6.8-demo", "token", "demo-secret-value"));
        final IsolationPolicy policy = policy(allowed);
        final ResourceBudget budget = ResourceBudget.defaults();

        try (DefaultIsolationProvider provider = new DefaultIsolationProvider(new IsolationProviderId("demo-provider"),
                IsolationConfig.defaults(root))) {
            // J7M teaching: in-process policy execution demonstrates the lightweight
            // provider path, while filesystem/network/environment checks remain
            // explicit policy decisions with safe audit evidence.
            final IsolationExecutionRequest inProcess = new IsolationExecutionRequest(subject,
                    IsolationMode.IN_PROCESS_POLICY, null, policy, budget, Duration.ofSeconds(2L), "demo-correlation");
            final IsolationExecutionResult inProcessResult = provider.execute(inProcess);

            final IsolationPolicyDecision filesystemDenied = provider.evaluateFileSystem(subject, policy.fileSystemPolicy(),
                    FileSystemAccess.WRITE, denied.resolve("secret.txt"));
            final IsolationPolicyDecision networkDenied = provider.evaluateNetworkEgress(subject, policy.networkEgressPolicy(),
                    java.net.URI.create("https://example.invalid:443"));

            // J7M teaching: the local process provider captures stdout/stderr and
            // redacts configured sensitive values; it does not require Docker,
            // Kubernetes, or a container runtime.
            final IsolationExecutionResult processResult = provider.execute(new IsolationExecutionRequest(subject,
                    IsolationMode.LOCAL_PROCESS, new IsolationProcessSpec(javaCommand("child-secret"), allowed,
                    Map.of("DEMO_TOKEN", "demo-secret-value")), policy, budget, Duration.ofSeconds(3L), "demo-correlation"));

            // J7M teaching: timeout handling demonstrates process lifecycle cleanup,
            // while resource budget output distinguishes enforced, advisory, and
            // policy-only controls without claiming a full OS sandbox guarantee.
            final IsolationExecutionResult timeoutResult = provider.execute(new IsolationExecutionRequest(subject,
                    IsolationMode.LOCAL_PROCESS, new IsolationProcessSpec(javaCommand("child-sleep"), allowed, Map.of()),
                    policy, budget, Duration.ofMillis(200L), "demo-correlation"));

            System.out.println("J7M Isolation Demo");
            System.out.println("in-process state=" + inProcessResult.state());
            System.out.println("filesystem denied=" + filesystemDenied.reasonCode());
            System.out.println("network denied=" + networkDenied.reasonCode());
            System.out.println("local process state=" + processResult.state() + " exit=" + processResult.exitCode());
            System.out.println("captured stdout=" + safeOutput(processResult.output()));
            System.out.println("timeout state=" + timeoutResult.state());
            System.out.println("resource cpu=" + budget.cpuBudget().level());
            System.out.println("resource queue=" + budget.queueBudget().level());
            System.out.println("metrics=" + provider.metrics());
            System.out.println("audit events=" + provider.auditEvents().size());
            System.out.println("health=" + provider.health().available());
        }
    }

    private static IsolationPolicy policy(final Path allowed) {
        final FileSystemPolicy fileSystem = new FileSystemPolicy(true, List.of(
                new FileSystemRule(FileSystemAccess.READ, allowed, true, true),
                new FileSystemRule(FileSystemAccess.WRITE, allowed, true, true)));
        final NetworkEgressPolicy network = new NetworkEgressPolicy(true, true, true,
                List.of(new NetworkEgressRule("localhost", -1, true)));
        final EnvironmentPolicy environment = new EnvironmentPolicy(true, 128, 1024,
                List.of(new EnvironmentVariableRule("DEMO_TOKEN", true, true)));
        final StreamCapturePolicy capture = new StreamCapturePolicy(256, true, List.of("demo-secret-value"));
        return new IsolationPolicy(fileSystem, network, environment, new StdoutPolicy(capture), new StderrPolicy(capture));
    }

    private static List<String> javaCommand(final String mode) {
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow", "-cp", System.getProperty("java.class.path"),
                IsolationDemo.class.getName(), mode);
    }

    private static String safeOutput(final CapturedOutput output) {
        if (output == null || output.stdout() == null) {
            return "";
        }
        return output.stdout().text();
    }

}
