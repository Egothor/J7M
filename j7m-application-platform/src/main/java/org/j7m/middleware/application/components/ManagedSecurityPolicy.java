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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.j7m.middleware.annotations.Audit;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.transport.api.MethodDescriptor;

/**
 * Trusted host-installed managed-entry authorization and payload-free audit policy.
 *
 * <p>Effective interface, implementation and relevant ancestor constraints accumulate; none may
 * weaken another declaration. The authenticated transport SecurityContext is authoritative, never
 * caller metadata or annotation values. Confidential entries require authenticated encrypted
 * transport in every managed profile. The policy borrows its thread-safe authorization policy,
 * audit sink and clock. It installs no Java sandbox, intercepts no ordinary self-invocation and
 * never logs arguments, results, annotation permission values or application exception messages.
 * Application exception messages, details and causes are redacted unconditionally, including
 * asynchronous completion failures; explicit successful return values are not modified.
 * A mandatory audit sink must acknowledge record synchronously; choose a durable sink when the
 * host's audit profile requires durability. A sink failure after dispatch reports outcome UNKNOWN,
 * with retry disabled, because the application effect cannot be undone.
 */
public final class ManagedSecurityPolicy implements ManagedInvocationPolicy {
    private final AuthorizationPolicy authorization;
    private final Map<String, SecurityAuditSink> auditPolicies;
    private final Clock clock;

    private record Permission(String action, String resource) { }
    private record Rules(Set<String> roles, List<Permission> permissions, String trustDomain,
            boolean confidential, Set<String> auditPolicies) { }

    /**
     * Creates an immutable policy factory using borrowed host services.
     * @param authorization thread-safe permission evaluator; never inferred from the application
     * @param audit thread-safe sink whose successful record call acknowledges the configured audit profile
     * @param clock event clock
     * @throws NullPointerException if any argument is null
     */
    public ManagedSecurityPolicy(final AuthorizationPolicy authorization, final SecurityAuditSink audit, final Clock clock) {
        this(authorization, Map.of("default", Objects.requireNonNull(audit)), clock);
    }

    /**
     * Creates a policy factory with explicitly named mandatory audit sinks.
     * All audit policies declared across a method's lineage must be installed and acknowledge
     * the event. The factory does not own or close these thread-safe host sinks.
     * @param authorization thread-safe permission evaluator
     * @param auditPolicies at most 64 named nonnull host sinks, possibly empty to reject Audit usage
     * @param clock event clock
     * @throws NullPointerException if an argument or map member is null
     * @throws IllegalArgumentException if policy names or capacity are invalid
     */
    public ManagedSecurityPolicy(final AuthorizationPolicy authorization,
            final Map<String, ? extends SecurityAuditSink> auditPolicies, final Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.auditPolicies = Map.copyOf(auditPolicies);
        if (this.auditPolicies.size() > 64) {
            throw new IllegalArgumentException("Managed audit policy capacity exceeded");
        }
        this.auditPolicies.keySet().forEach(ManagedSecurityPolicy::identifier);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * {@inheritDoc}
     */
    @Override public Phase phase() { return Phase.SECURITY; }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Class<? extends Annotation>> annotationTypes() {
        return Set.of(RequiresRole.class, RequiresPermission.class, RequiresTrustDomain.class,
                Confidential.class, Audit.class, Sensitive.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        validateAudits(rules(contract, implementation, businessInterface));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Binding bind(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        final Rules rules = rules(contract, implementation, businessInterface);
        validateAudits(rules);
        final AtomicBoolean closed = new AtomicBoolean();
        return new Binding() {
            @Override
            public Object invoke(final InvocationContext context, final Object[] arguments, final Proceed next) throws Exception {
                if (closed.get()) {
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Managed security binding is closed");
                }
                Objects.requireNonNull(context);
                Objects.requireNonNull(next);
                final SecurityContext security = SecurityContexts.current();
                try {
                    authorize(rules, contract, next.target(), security);
                } catch (MiddlewareException denied) {
                    if (!rules.auditPolicies().isEmpty()) {
                        emit(rules, contract, context, security, "DENIED", "AUTHORIZATION_DENIED", false);
                    }
                    throw denied;
                }
                if (!rules.auditPolicies().isEmpty()) {
                    emit(rules, contract, context, security, "START", "BEFORE_DISPATCH", false);
                }
                final boolean deferred = next.supportsResultFinalizers();
                if (deferred && !rules.auditPolicies().isEmpty()) {
                    next.addResultFinalizer(outcome -> {
                        emit(rules, contract, context, security, outcome.failure() == null ? "SUCCESS" : "FAILED",
                                outcome.failure() == null ? "DISPATCH_COMPLETED" : "DISPATCH_FAILED", true);
                        return CompletableFuture.completedFuture(outcome);
                    });
                }
                final Object result;
                try {
                    result = next.invoke();
                } catch (Exception failure) {
                    if (!deferred && !rules.auditPolicies().isEmpty()) {
                        emit(rules, contract, context, security, "FAILED", "DISPATCH_FAILED", true);
                    }
                    throw applicationFailure(failure);
                }
                if (result instanceof CompletionStage<?> stage) {
                    return completion(stage, rules, contract, context, security, deferred);
                }
                if (!deferred && !rules.auditPolicies().isEmpty()) {
                    emit(rules, contract, context, security, "SUCCESS", "DISPATCH_COMPLETED", true);
                }
                return result;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private CompletableFuture<Object> completion(final CompletionStage<?> stage, final Rules rules,
            final ManagedServiceContract contract, final InvocationContext invocation, final SecurityContext security, final boolean deferred) {
        final CompletableFuture<Object> completed = new CompletableFuture<>();
        final ManagedInvocationLifetime lifetime = ManagedInvocationLifetime.current();
        if (lifetime != null) { lifetime.linkResultWrapper(completed, stage); }
        try {
            stage.whenComplete((value, failure) -> {
                try {
                    if (failure != null) {
                        if (!deferred && !rules.auditPolicies().isEmpty()) {
                            emit(rules, contract, invocation, security, "FAILED", "DISPATCH_FAILED", true);
                        }
                        if (ManagedInvocationLifetime.cancelledStage(failure)) {
                            completed.completeExceptionally(new java.util.concurrent.CancellationException("Managed cancellation outcome is unknown"));
                        } else { completed.completeExceptionally(applicationFailure(failure)); }
                    } else {
                        if (!deferred && !rules.auditPolicies().isEmpty()) {
                            emit(rules, contract, invocation, security, "SUCCESS", "DISPATCH_COMPLETED", true);
                        }
                        completed.complete(value);
                    }
                } catch (RuntimeException auditFailure) {
                    completed.completeExceptionally(auditFailure);
                }
            });
        } catch (RuntimeException | Error registrationFailure) {
            if (lifetime != null) { lifetime.resultObservationFailed(stage); }
            throw registrationFailure;
        }
        return completed;
    }

    private static MiddlewareException applicationFailure(final Throwable thrown) {
        final Throwable failure = thrown.getClass() == CompletionException.class && thrown.getCause() != null ? thrown.getCause() : thrown;
        final MiddlewareErrorCode code = failure.getClass() == MiddlewareException.class
                ? ((MiddlewareException) failure).code() : MiddlewareErrorCode.REMOTE_EXCEPTION;
        return new MiddlewareException(new RemoteError(code, "Managed application dispatch failed",
                Map.of("outcome", "UNKNOWN"), null, null, false, false));
    }

    private void authorize(final Rules rules, final ManagedServiceContract contract,
            final ObjectRef target, final SecurityContext context) {
        final boolean protectedEntry = !rules.roles().isEmpty() || !rules.permissions().isEmpty()
                || !rules.trustDomain().isEmpty() || rules.confidential();
        if (protectedEntry && (context == null || !context.authenticated())) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "Managed entry requires authentication");
        }
        if (context == null) {
            return;
        }
        if (!context.roles().containsAll(rules.roles())
                || !rules.trustDomain().isEmpty() && !rules.trustDomain().equals(context.trustDomain().id())) {
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Managed entry authorization denied");
        }
        if (rules.confidential() && context.transportSecurityLevel() != TransportSecurityLevel.AUTHENTICATED_ENCRYPTED) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "Managed entry requires authenticated encrypted transport");
        }
        for (Permission permission : rules.permissions()) {
            if (target == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Trusted invocation target is missing");
            }
            final PermissionRequest request = new PermissionRequest(PermissionAction.INVOKE,
                    permission.resource().isEmpty() ? target.objectId() : permission.resource(), target.registryContext(), target.runtimeId(),
                    target.serviceName(), new MethodDescriptor(contract.method().declaringType(), contract.method().name(),
                            contract.method().parameterTypes(), contract.method().returnType(), null).toString(), target.transport(), "",
                    Map.of("requiredPermission", permission.action(), "requiredResource", permission.resource()));
            final AuthorizationDecision decision;
            try {
                decision = this.authorization.evaluate(context, request);
            } catch (RuntimeException failure) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Managed authorization policy failed");
            }
            if (decision == null || !decision.allowed()) {
                throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Managed permission denied");
            }
        }
    }

    private void emit(final Rules rules, final ManagedServiceContract contract, final InvocationContext invocation,
            final SecurityContext context, final String decision, final String reason, final boolean afterDispatch) {
        try {
            for (String policy : rules.auditPolicies().stream().sorted().toList()) {
                this.auditPolicies.get(policy).record(new AuditEvent(this.clock.instant(), "", context == null ? "anonymous" : context.principal().id(),
                    "managed.invoke", contract.id(), decision, policy, invocation.correlationId(),
                    context == null ? "" : context.invocationId(), "", reason, "", "default",
                    Map.of("contractVersion", Integer.toString(contract.version()), "method", contract.method().name())));
            }
        } catch (RuntimeException failure) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.UNAVAILABLE,
                    afterDispatch ? "Managed audit outcome is UNKNOWN" : "Managed mandatory audit unavailable",
                    Map.of("outcome", afterDispatch ? "UNKNOWN" : "NOT_STARTED", "reason", "AUDIT_UNAVAILABLE"),
                    null, null, false, false));
        }
    }

    private void validateAudits(final Rules rules) {
        if (!this.auditPolicies.keySet().containsAll(rules.auditPolicies())) {
            throw new IllegalArgumentException("Unknown managed audit policy");
        }
    }

    private static Rules rules(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        final List<AnnotatedElement> declarations = ManagedMethodSurface.declarations(contract, implementation, businessInterface);
        final Set<String> roles = new HashSet<>();
        final Set<Permission> permissions = new LinkedHashSet<>();
        final Set<String> domains = new HashSet<>();
        boolean confidential = false;
        final Set<String> audits = new HashSet<>();
        for (AnnotatedElement declaration : declarations) {
            final RequiresRole role = declaration.getDeclaredAnnotation(RequiresRole.class);
            if (role != null) { roles.add(identifier(role.value())); }
            final RequiresPermission permission = declaration.getDeclaredAnnotation(RequiresPermission.class);
            if (permission != null) {
                permissions.add(new Permission(identifier(permission.value()), permission.resource().isEmpty() ? "" : identifier(permission.resource())));
            }
            final RequiresTrustDomain trust = declaration.getDeclaredAnnotation(RequiresTrustDomain.class);
            if (trust != null) { domains.add(identifier(trust.value())); }
            confidential |= declaration.isAnnotationPresent(Confidential.class);
            final Audit audit = declaration.getDeclaredAnnotation(Audit.class);
            if (audit != null) { audits.add(identifier(audit.policy())); }
        }
        if (roles.size() > 64 || permissions.size() > 64 || domains.size() > 1) {
            throw new IllegalArgumentException("Conflicting or excessive managed security constraints");
        }
        return new Rules(Set.copyOf(roles), List.copyOf(permissions), domains.isEmpty() ? "" : domains.iterator().next(), confidential, Set.copyOf(audits));
    }

    private static String identifier(final String value) {
        if (value == null || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid managed security constraint");
        }
        return value;
    }
}
