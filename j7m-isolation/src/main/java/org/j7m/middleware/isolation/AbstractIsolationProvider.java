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

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.EnvironmentPolicy;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.IsolationAuditEvent;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationHealth;
import org.j7m.middleware.api.isolation.IsolationMetricsSnapshot;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicyDecision;
import org.j7m.middleware.api.isolation.IsolationProvider;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.j7m.middleware.api.isolation.IsolationProviderType;
import org.j7m.middleware.api.isolation.IsolationRecoveryReport;
import org.j7m.middleware.api.isolation.IsolationSubject;
import org.j7m.middleware.api.isolation.NetworkEgressPolicy;
import org.j7m.middleware.isolation.internal.IsolationPolicyEvaluator;
import org.j7m.middleware.isolation.internal.IsolationRedactor;

abstract class AbstractIsolationProvider implements IsolationProvider {
    final IsolationProviderId id;
    final IsolationProviderType type;
    final IsolationConfig config;
    final IsolationRedactor redactor;
    final IsolationPolicyEvaluator evaluator;
    final AtomicBoolean closed;
    final AtomicInteger activeExecutions;
    final AtomicLong completedExecutions;
    final AtomicLong failedExecutions;
    final AtomicLong cancelledExecutions;
    final AtomicLong timedOutExecutions;
    final AtomicLong killedExecutions;
    final AtomicLong policyAllowed;
    final AtomicLong policyDenied;
    final AtomicLong resourceRejected;
    final AtomicLong redactionEvents;
    final ArrayDeque<IsolationAuditEvent> auditEvents;
    final IsolationRecoveryReport recoveryReport;

    AbstractIsolationProvider(
            final IsolationProviderId id,
            final IsolationProviderType type,
            final IsolationConfig config) {
        this.id = id;
        this.type = type;
        this.config = config;
        this.redactor = new IsolationRedactor();
        this.evaluator = new IsolationPolicyEvaluator(this.redactor);
        this.closed = new AtomicBoolean(false);
        this.activeExecutions = new AtomicInteger(0);
        this.completedExecutions = new AtomicLong(0L);
        this.failedExecutions = new AtomicLong(0L);
        this.cancelledExecutions = new AtomicLong(0L);
        this.timedOutExecutions = new AtomicLong(0L);
        this.killedExecutions = new AtomicLong(0L);
        this.policyAllowed = new AtomicLong(0L);
        this.policyDenied = new AtomicLong(0L);
        this.resourceRejected = new AtomicLong(0L);
        this.redactionEvents = new AtomicLong(0L);
        this.auditEvents = new ArrayDeque<>();
        this.recoveryReport = new IsolationRecoveryReport(
                Instant.now(this.config.clock()), 0, 0, List.of(), Map.of("state", "clean"));
    }

    @Override
    public final IsolationProviderId id() {
        return this.id;
    }

    @Override
    public final IsolationProviderType type() {
        return this.type;
    }

    @Override
    public abstract Set<IsolationMode> supportedModes();

    @Override
    public final IsolationPolicyDecision evaluateFileSystem(
            final IsolationSubject subject,
            final FileSystemPolicy policy,
            final FileSystemAccess access,
            final Path path) {
        final IsolationPolicyDecision decision = this.evaluator.fileSystem(subject, policy, access, path);
        this.countDecision(decision);
        this.audit(subject, "filesystem." + access.name().toLowerCase(java.util.Locale.ROOT),
                decision.metadata().getOrDefault("path", ""), decision.allowed() ? "ALLOWED" : "DENIED",
                decision.reasonCode(), "");
        return decision;
    }

    @Override
    public final IsolationPolicyDecision evaluateNetworkEgress(
            final IsolationSubject subject,
            final NetworkEgressPolicy policy,
            final URI destination) {
        final IsolationPolicyDecision decision = this.evaluator.network(subject, policy, destination);
        this.countDecision(decision);
        this.audit(subject, "network.egress", String.valueOf(destination), decision.allowed() ? "ALLOWED" : "DENIED",
                decision.reasonCode(), "");
        return decision;
    }

    @Override
    public final IsolationPolicyDecision evaluateEnvironment(
            final IsolationSubject subject,
            final EnvironmentPolicy policy,
            final String name,
            final String value) {
        final IsolationPolicyDecision decision = this.evaluator.environment(subject, policy, name, value);
        this.countDecision(decision);
        if (decision.metadata().containsValue("<redacted>")) {
            this.redactionEvents.incrementAndGet();
        }
        this.audit(subject, "environment.read", name, decision.allowed() ? "ALLOWED" : "DENIED",
                decision.reasonCode(), "");
        return decision;
    }

    @Override
    public IsolationMetricsSnapshot metrics() {
        return new IsolationMetricsSnapshot(this.activeExecutions.get(), this.completedExecutions.get(),
                this.failedExecutions.get(), this.cancelledExecutions.get(), this.timedOutExecutions.get(),
                this.killedExecutions.get(), this.policyAllowed.get(), this.policyDenied.get(),
                this.resourceRejected.get(), this.redactionEvents.get());
    }

    @Override
    public final IsolationHealth health() {
        return new IsolationHealth(!this.closed.get(), this.type, this.closed.get() ? "closed" : "available",
                Map.of("provider", this.id.value()));
    }

    @Override
    public List<IsolationAuditEvent> auditEvents() {
        synchronized (this.auditEvents) {
            return List.copyOf(this.auditEvents);
        }
    }

    @Override
    public final IsolationRecoveryReport recoveryReport() {
        return this.recoveryReport;
    }

    @Override
    public void close() {
        this.closed.set(true);
    }

    final void ensureOpen() {
        if (this.closed.get()) {
            throw new IsolationException(MiddlewareErrorCode.UNAVAILABLE, "isolation provider is closed",
                    Map.of("provider", this.id.value()));
        }
    }

    final void acquireSlot(final IsolationExecutionRequest request) {
        final int active = this.activeExecutions.incrementAndGet();
        if (active > request.budget().maxConcurrentExecutions()) {
            this.activeExecutions.decrementAndGet();
            this.resourceRejected.incrementAndGet();
            this.audit(request.subject(), "resource.concurrent", "provider", "DENIED", "RESOURCE_CONCURRENT_LIMIT", request.correlationId());
            throw new IsolationException(MiddlewareErrorCode.OVERLOADED, "isolation concurrent execution limit exceeded",
                    Map.of("limit", Integer.toString(request.budget().maxConcurrentExecutions())));
        }
    }

    final void releaseSlot() {
        this.activeExecutions.updateAndGet(value -> Math.max(0, value - 1));
    }

    final void audit(
            final IsolationSubject subject,
            final String action,
            final String resource,
            final String result,
            final String reasonCode,
            final String correlationId) {
        final Map<String, String> metadata = subject == null ? Map.of() : this.redactor.redactMap(subject.metadata());
        final IsolationAuditEvent event = new IsolationAuditEvent(Instant.now(this.config.clock()),
                subject == null ? null : subject.id(), subject == null ? "" : subject.principal(), action,
                this.redactor.redactText(resource, null), result, reasonCode, correlationId, metadata);
        synchronized (this.auditEvents) {
            this.auditEvents.addLast(event);
            while (this.auditEvents.size() > this.config.maxAuditEvents()) {
                this.auditEvents.removeFirst();
            }
        }
    }

    final void countDecision(final IsolationPolicyDecision decision) {
        if (decision.allowed()) {
            this.policyAllowed.incrementAndGet();
        } else {
            this.policyDenied.incrementAndGet();
        }
    }

    final List<String> safeCommandList(final List<String> command) {
        final String summary = this.redactor.safeCommand(command);
        final List<String> list = new ArrayList<>();
        list.add(summary);
        return list;
    }
}
