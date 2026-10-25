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

import java.util.List;
import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.IsolationAuditEvent;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationExecutionHandle;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationMetricsSnapshot;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.j7m.middleware.api.isolation.IsolationProviderType;

/**
 * Composite first-party isolation provider.
 *
 * <p>The provider delegates {@link IsolationMode#IN_PROCESS_POLICY} work to
 * {@link InProcessIsolationProvider} and {@link IsolationMode#LOCAL_PROCESS}
 * work to {@link LocalProcessIsolationProvider}. It is thread-safe and owns the
 * delegated providers for its lifecycle.</p>
 */
public final class DefaultIsolationProvider extends AbstractIsolationProvider {
    private final InProcessIsolationProvider inProcess;
    private final LocalProcessIsolationProvider localProcess;

    /**
     * Creates a composite provider.
     *
     * @param id provider identifier
     * @param config provider configuration
     */
    public DefaultIsolationProvider(final IsolationProviderId id, final IsolationConfig config) {
        super(id, IsolationProviderType.COMPOSITE, config);
        this.inProcess = new InProcessIsolationProvider(new IsolationProviderId(id.value() + "-in-process"), config);
        this.localProcess = new LocalProcessIsolationProvider(new IsolationProviderId(id.value() + "-local-process"), config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<IsolationMode> supportedModes() {
        return Set.of(IsolationMode.IN_PROCESS_POLICY, IsolationMode.LOCAL_PROCESS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IsolationExecutionHandle start(final IsolationExecutionRequest request) {
        this.ensureOpen();
        if (request.mode() == IsolationMode.IN_PROCESS_POLICY) {
            return this.inProcess.start(request);
        }
        if (request.mode() == IsolationMode.LOCAL_PROCESS) {
            return this.localProcess.start(request);
        }
        throw new IsolationException(MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported isolation mode",
                java.util.Map.of("mode", request.mode().name()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IsolationAuditEvent> auditEvents() {
        final java.util.ArrayList<IsolationAuditEvent> events = new java.util.ArrayList<>();
        events.addAll(this.inProcess.auditEvents());
        events.addAll(this.localProcess.auditEvents());
        return List.copyOf(events);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IsolationMetricsSnapshot metrics() {
        final IsolationMetricsSnapshot base = super.metrics();
        final IsolationMetricsSnapshot left = this.inProcess.metrics();
        final IsolationMetricsSnapshot right = this.localProcess.metrics();
        return new IsolationMetricsSnapshot(
                base.activeExecutions() + left.activeExecutions() + right.activeExecutions(),
                base.completedExecutions() + left.completedExecutions() + right.completedExecutions(),
                base.failedExecutions() + left.failedExecutions() + right.failedExecutions(),
                base.cancelledExecutions() + left.cancelledExecutions() + right.cancelledExecutions(),
                base.timedOutExecutions() + left.timedOutExecutions() + right.timedOutExecutions(),
                base.killedExecutions() + left.killedExecutions() + right.killedExecutions(),
                base.policyAllowed() + left.policyAllowed() + right.policyAllowed(),
                base.policyDenied() + left.policyDenied() + right.policyDenied(),
                base.resourceRejected() + left.resourceRejected() + right.resourceRejected(),
                base.redactionEvents() + left.redactionEvents() + right.redactionEvents());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        super.close();
        this.inProcess.close();
        this.localProcess.close();
    }
}
