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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;

/**
 * Fixed tenant/dependency admission registry. Server bulkheads hold physical permits, while client
 * connection breakers are explicitly validated against the installed client registry. Rate tokens
 * are charged after authorization and before dependency admission, with no refund for later overload.
 * This implementation supports LOCAL rate coordination only and never silently degrades scope.
 */
public final class ManagedResiliencePolicy implements ManagedInvocationPolicy, AutoCloseable {
    private record Key(String tenant, String dependency) { }
    private record Rules(String bulkhead, String rate) { }
    private final Set<String> tenants;
    private final Function<InvocationContext, String> tenantResolver;
    private final Map<String, BulkheadProfile> bulkheads;
    private final Map<String, RateLimitProfile> rates;
    private final Map<Key, ManagedAdmissionController> admissions;
    private final Map<Key, Window> windows;
    private final ManagedDependencyBreaker breaker;
    private final LongSupplier clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates finite server admission and optional client-breaker validation.
     * The resolver receives the actual authenticated host context; application arguments never select a tenant.
     * @param tenants at most64 predefined tenant identities
     * @param tenantResolver trusted authenticated-context mapper; unknown results fail closed
     * @param bulkheads at most64 fixed dependency admission profiles
     * @param rates at most64 LOCAL fixed-window profiles
     * @param breaker trusted matching client registry, or null to reject CircuitBreaker declarations
     */
    public ManagedResiliencePolicy(Set<String> tenants, Function<InvocationContext, String> tenantResolver,
            Map<String, BulkheadProfile> bulkheads, Map<String, RateLimitProfile> rates, ManagedDependencyBreaker breaker) {
        this(tenants, tenantResolver, bulkheads, rates, breaker, System::nanoTime);
    }
    ManagedResiliencePolicy(Set<String> tenants, Function<InvocationContext, String> tenantResolver,
            Map<String, BulkheadProfile> bulkheads, Map<String, RateLimitProfile> rates,
            ManagedDependencyBreaker breaker, LongSupplier clock) {
        this.tenants = Set.copyOf(tenants); this.tenantResolver = Objects.requireNonNull(tenantResolver);
        this.bulkheads = Map.copyOf(bulkheads); this.rates = Map.copyOf(rates); this.breaker = breaker; this.clock = Objects.requireNonNull(clock);
        if (this.tenants.isEmpty() || this.tenants.size() > 64 || this.bulkheads.size() > 64 || this.rates.size() > 64) {
            throw new IllegalArgumentException("Resilience registry capacity");
        }
        this.tenants.forEach(ManagedResiliencePolicy::identifier);
        this.bulkheads.keySet().forEach(ManagedResiliencePolicy::identifier); this.rates.keySet().forEach(ManagedResiliencePolicy::identifier);
        for (RateLimitProfile profile : this.rates.values()) if (profile.scope() != RateLimitProfile.Scope.LOCAL) {
            throw new IllegalArgumentException("Coordinated rate limiter backend is not installed");
        }
        var admission = new HashMap<Key, ManagedAdmissionController>(); var rateWindows = new HashMap<Key, Window>();
        for (String tenant : this.tenants) {
            this.bulkheads.forEach((name, profile) -> admission.put(new Key(tenant, name), new ManagedAdmissionController(
                    profile.maximumConcurrent(), profile.maximumQueued(), Duration.ofSeconds(1))));
            this.rates.forEach((name, profile) -> rateWindows.put(new Key(tenant, name), new Window(profile)));
        }
        this.admissions = Map.copyOf(admission); this.windows = Map.copyOf(rateWindows);
    }
    /**
     * {@inheritDoc}
     */
    @Override public Phase phase() { return Phase.ADMISSION; }
    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() {
        return Set.of(CircuitBreaker.class, Bulkhead.class, RateLimited.class, Backpressure.class);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        rules(contract, implementation, businessInterface);
    }
    private Rules rules(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var bulk = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, Bulkhead.class);
        var back = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, Backpressure.class);
        var rate = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, RateLimited.class);
        var circuit = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, CircuitBreaker.class);
        if (circuit != null) {
            var visible = ManagedPolicyMetadata.scalar(contract, businessInterface, businessInterface, CircuitBreaker.class);
            if (!circuit.equals(visible) || breaker == null) throw new IllegalArgumentException("Client circuit policy is unavailable or not wire-visible");
            breaker.validate(businessInterface);
        }
        String bulkName = bulk == null ? back == null ? null : "default" : identifier(bulk.value());
        if (bulkName != null && !bulkheads.containsKey(bulkName)) throw new IllegalArgumentException("Unknown bulkhead policy");
        if (back != null && back.maxConcurrent() != -1 && (back.maxConcurrent() < 1
                || back.maxConcurrent() != bulkheads.get(bulkName).maximumConcurrent())) {
            throw new IllegalArgumentException("Backpressure declaration differs from actual dependency capacity");
        }
        String rateName = rate == null ? null : rate.value().isEmpty() ? "default" : identifier(rate.value());
        if (rateName != null && !rates.containsKey(rateName)) throw new IllegalArgumentException("Unknown rate policy");
        return new Rules(bulkName, rateName);
    }
    /**
     * {@inheritDoc}
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        Rules rules = rules(contract, implementation, businessInterface);
        boolean asynchronous = CompletionStage.class.isAssignableFrom(ManagedPolicyMetadata.method(contract, businessInterface).getReturnType());
        return new Binding() {
            private final AtomicBoolean detached = new AtomicBoolean();
            @Override public Object invoke(InvocationContext context, Object[] arguments, Proceed next) throws Exception {
                if (closed.get() || detached.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Resilience policy closed");
                if (rules.bulkhead == null && rules.rate == null) return next.invoke();
                String tenant = tenantResolver.apply(context);
                if (tenant == null || !tenants.contains(tenant)) throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Unknown authenticated tenant scope");
                var control = context.control();
                boolean tracked = control != InvocationControl.unavailable();
                if (tracked && (control.cancellationRequested() || control.remainingBudget().isZero())) {
                    throw MiddlewareException.of(control.remainingBudget().isZero() ? MiddlewareErrorCode.DEADLINE_EXCEEDED
                            : MiddlewareErrorCode.CANCELLED, "Resilience caller budget ended");
                }
                CompletionStage<Void> physical = next.physicalCompletion();
                if ((asynchronous || tracked) && rules.bulkhead != null && physical == null) {
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Async bulkhead requires trusted physical completion");
                }
                if (rules.rate != null && !windows.get(new Key(tenant, rules.rate)).admit(clock.getAsLong())) {
                    throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Rate window exhausted");
                }
                if (rules.bulkhead == null) return next.invoke();
                var profile = bulkheads.get(rules.bulkhead);
                Duration wait = profile.maximumWait();
                if (tracked && control.remainingBudget().compareTo(wait) < 0) wait = control.remainingBudget();
                var permit = admissions.get(new Key(tenant, rules.bulkhead)).acquire(0, wait);
                if (tracked && (control.cancellationRequested() || control.remainingBudget().isZero())) {
                    permit.close(); throw MiddlewareException.of(control.remainingBudget().isZero() ? MiddlewareErrorCode.DEADLINE_EXCEEDED
                            : MiddlewareErrorCode.CANCELLED, "Bulkhead budget ended before entry");
                }
                if (physical != null) { physical.whenComplete((ignored, failure) -> permit.close()); return next.invoke(); }
                try (permit) { return next.invoke(); }
            }
            @Override public void close() { detached.set(true); }
        };
    }
    /**
     * Returns actual dependency occupancy for one declared tenant, without dynamic state allocation.
     * @param tenant declared host tenant
     * @param dependency registered dependency name
     * @return immutable physical admission counters
     */
    public ManagedAdmissionController.Status status(String tenant, String dependency) {
        var controller = admissions.get(new Key(tenant, dependency));
        if (controller == null) throw new IllegalArgumentException("Unknown tenant dependency budget");
        return controller.status();
    }
    /**
     * Rejects new/queued calls; running physical permits remain owned until their completion.
     */
    @Override public void close() { if (closed.compareAndSet(false, true)) admissions.values().forEach(ManagedAdmissionController::close); }
    private static String identifier(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid resilience identity");
        return name;
    }
    private static final class Window {
        private final RateLimitProfile profile;
        private long started;
        private long admitted;
        private boolean initialized;
        Window(RateLimitProfile profile) { this.profile = profile; }
        synchronized boolean admit(long now) {
            if (!initialized || now - started >= profile.window().toNanos()) { initialized = true; started = now; admitted = 0; }
            if (admitted >= profile.permits()) return false;
            admitted++; return true;
        }
    }
}
