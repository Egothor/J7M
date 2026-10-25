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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.CapturedOutput;
import org.j7m.middleware.api.isolation.IsolationExecutionHandle;
import org.j7m.middleware.api.isolation.IsolationExecutionId;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationExecutionState;
import org.j7m.middleware.api.isolation.IsolationExecutionStatus;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.j7m.middleware.api.isolation.IsolationProviderType;
import org.j7m.middleware.api.isolation.RedactedOutput;
import org.j7m.middleware.api.isolation.ResourceUsageSnapshot;

/**
 * In-process isolation provider for J7M-controlled policy capabilities.
 *
 * <p>The provider is thread-safe. It does not create an operating-system
 * sandbox and does not intercept arbitrary JVM file, network, or environment
 * APIs. It enforces decisions for requests routed through this provider and
 * reports thread, CPU, and memory limits according to their advertised
 * enforcement levels.</p>
 */
public final class InProcessIsolationProvider extends AbstractIsolationProvider {
    /**
     * Creates an in-process provider.
     *
     * @param id provider identifier
     * @param config provider configuration
     */
    public InProcessIsolationProvider(final IsolationProviderId id, final IsolationConfig config) {
        super(id, IsolationProviderType.IN_PROCESS_POLICY, config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<IsolationMode> supportedModes() {
        return Set.of(IsolationMode.IN_PROCESS_POLICY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IsolationExecutionHandle start(final IsolationExecutionRequest request) {
        this.ensureOpen();
        if (request.mode() != IsolationMode.IN_PROCESS_POLICY) {
            throw new IsolationException(MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported isolation mode",
                    Map.of("mode", request.mode().name()));
        }
        this.acquireSlot(request);
        final IsolationExecutionId id = IsolationExecutionId.random();
        final Instant now = Instant.now(this.config.clock());
        this.audit(request.subject(), "isolation.execution.requested", "in-process", "ALLOWED", "EXECUTION_REQUESTED", request.correlationId());
        this.audit(request.subject(), "policy.allowed", "in-process", "ALLOWED", "IN_PROCESS_POLICY_ALLOWED", request.correlationId());
        final IsolationExecutionStatus status = new IsolationExecutionStatus(id, request.subject().id(), request.mode(),
                IsolationExecutionState.COMPLETED, now, now, "in-process-policy", Map.of("enforcement", "POLICY_ONLY"));
        final IsolationExecutionResult result = new IsolationExecutionResult(id, IsolationExecutionState.COMPLETED, 0,
                new CapturedOutput(new RedactedOutput("", false), new RedactedOutput("", false), 0, 0, false, false),
                new ResourceUsageSnapshot(Duration.ZERO, 0, 0, 0), List.of(), Map.of("provider", this.id().value()));
        this.completedExecutions.incrementAndGet();
        this.releaseSlot();
        this.audit(request.subject(), "isolation.execution.completed", "in-process", "COMPLETED", "COMPLETED", request.correlationId());
        return new CompletedIsolationExecutionHandle(status, result);
    }
}
