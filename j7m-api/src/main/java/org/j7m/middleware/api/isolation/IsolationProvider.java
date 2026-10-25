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

package org.j7m.middleware.api.isolation;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * First-party J7M isolation provider SPI.
 *
 * <p>Providers are thread-safe after construction unless documented otherwise.
 * They own any child processes, provider work directories, capture buffers, and
 * admission-control counters they allocate. {@link #close()} stops accepting new
 * work and must clean up provider-owned resources; status methods remain safe
 * and redacted after close.</p>
 *
 * <p>Methods may block for local process launch, process termination, and
 * output-drain completion. Callers should use bounded request timeouts and
 * bounded {@link IsolationExecutionHandle#await(Duration)} calls. Failures map
 * to {@code MiddlewareException} error codes through {@link IsolationException}.
 * Public status and audit data are redacted summaries, never full internal
 * process state or raw command/environment/output secrets.</p>
 *
 * <p>This SPI is not a container runtime, not an OCI runtime, and not a
 * guarantee of kernel-level sandboxing. Implementations must report advisory,
 * policy-only, enforced, and unsupported resource limits truthfully.</p>
 */
public interface IsolationProvider extends AutoCloseable {
    /**
     * Returns the stable provider identifier.
     *
     * @return provider identifier
     */
    IsolationProviderId id();

    /**
     * Returns the provider type.
     *
     * @return provider type
     */
    IsolationProviderType type();

    /**
     * Returns supported execution modes.
     *
     * @return immutable supported mode set
     */
    Set<IsolationMode> supportedModes();

    /**
     * Starts an isolated execution.
     *
     * @param request execution request
     * @return live execution handle
     * @throws IsolationException when the provider is closed, request is invalid, policy denies start, or capacity is exhausted
     */
    IsolationExecutionHandle start(IsolationExecutionRequest request);

    /**
     * Runs an isolated execution to completion using the request timeout.
     *
     * @param request execution request
     * @return terminal result
     * @throws IsolationException when start or execution fails before a result can be produced
     */
    default IsolationExecutionResult execute(final IsolationExecutionRequest request) {
        final IsolationExecutionHandle handle = this.start(request);
        return handle.await(request.timeout());
    }

    /**
     * Evaluates filesystem policy for a normalized path.
     *
     * @param subject subject requesting access
     * @param policy filesystem policy
     * @param access access type
     * @param path requested path
     * @return safe policy decision
     */
    IsolationPolicyDecision evaluateFileSystem(
            IsolationSubject subject,
            FileSystemPolicy policy,
            FileSystemAccess access,
            Path path);

    /**
     * Evaluates network egress policy for a destination URI.
     *
     * @param subject subject requesting egress
     * @param policy network policy
     * @param destination destination URI
     * @return safe policy decision
     */
    IsolationPolicyDecision evaluateNetworkEgress(
            IsolationSubject subject,
            NetworkEgressPolicy policy,
            URI destination);

    /**
     * Evaluates environment policy for one variable.
     *
     * @param subject subject requesting environment access
     * @param policy environment policy
     * @param name variable name
     * @param value variable value
     * @return safe policy decision
     */
    IsolationPolicyDecision evaluateEnvironment(
            IsolationSubject subject,
            EnvironmentPolicy policy,
            String name,
            String value);

    /**
     * Returns provider metrics.
     *
     * @return immutable metrics snapshot
     */
    IsolationMetricsSnapshot metrics();

    /**
     * Returns provider health.
     *
     * @return health summary
     */
    IsolationHealth health();

    /**
     * Returns recent safe audit events.
     *
     * @return immutable audit event list
     */
    List<IsolationAuditEvent> auditEvents();

    /**
     * Returns startup recovery and cleanup summary.
     *
     * @return recovery report
     */
    IsolationRecoveryReport recoveryReport();

    /**
     * Closes this provider.
     */
    @Override
    void close();
}
