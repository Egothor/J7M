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

package org.j7m.middleware.application.distributed;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * General D05/D06 ownership interceptor with finite host tenant/profile inventory. It acquires a
 * real lease before body entry and supplies a per-claim provider context. Leadership is on-demand;
 * no scheduler or arbitrary-effect fencing is implied. Renewal workers never run application code.
 */
public final class ManagedOwnershipPolicy implements ManagedInvocationPolicy, AutoCloseable {
    private record Rule(String profile, int key, Class<?> type) { }
    /**
     * Safe aggregate host diagnostics without keys, identities or application values.
     * @param activeClaims actual invocation-owned claims, at most64
     * @param queuedRenewals bounded queued maintenance tasks
     * @param closing whether new invocations are refused
     */
    public record Status(int activeClaims, int queuedRenewals, boolean closing) { }
    private final Map<String, ManagedOwnershipProfile> profiles;
    private final Set<String> tenants;
    private final Function<InvocationContext, String> tenantResolver;
    private final Semaphore capacity = new Semaphore(64);
    private final Set<ManagedOwnershipContext> contexts = ConcurrentHashMap.newKeySet();
    private final Map<ManagedOwnershipContext, Renewal> maintenance = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final LongSupplier clock;

    /**
     * Installs immutable trusted profiles with finite authenticated tenant mapping.
     * @param profiles at most64 host policy names
     * @param tenants at most64 known tenant identities
     * @param tenantResolver maps the actual authenticated/host-bound context, never application arguments
     * @throws IllegalArgumentException for invalid identities/capacities
     * @throws NullPointerException for null configuration
     */
    public ManagedOwnershipPolicy(Map<String, ManagedOwnershipProfile> profiles, Set<String> tenants,
            Function<InvocationContext, String> tenantResolver) {
        this(profiles, tenants, tenantResolver, System::nanoTime);
    }
    ManagedOwnershipPolicy(Map<String, ManagedOwnershipProfile> profiles, Set<String> tenants,
            Function<InvocationContext, String> tenantResolver, LongSupplier clock) {
        this.profiles = Map.copyOf(profiles); this.tenants = Set.copyOf(tenants);
        this.tenantResolver = Objects.requireNonNull(tenantResolver); this.clock = Objects.requireNonNull(clock);
        if (this.profiles.size() > 64 || this.tenants.isEmpty() || this.tenants.size() > 64
                || this.profiles.keySet().stream().anyMatch(name -> !identifier(name)) || this.tenants.stream().anyMatch(name -> !identifier(name))) {
            throw new IllegalArgumentException("Invalid ownership inventory");
        }
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), threadFactory("j7m-ownership-renew"), new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(1, threadFactory("j7m-ownership-clock")); scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private static ThreadFactory threadFactory(String name) {
        return task -> { Thread thread = new Thread(null, task, name, 0, false); thread.setDaemon(true); thread.setContextClassLoader(ManagedOwnershipPolicy.class.getClassLoader()); return thread; };
    }
    /**
     * {@inheritDoc}
     */
    @Override public Phase phase() { return Phase.OWNERSHIP; }
    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(LeaseGuard.class, LeaderTask.class, ShardKey.class); }
    /**
     * {@inheritDoc}
     */
    @Override public void validateComponent(Class<?> component, boolean exported) {
        if (component.isAnnotationPresent(PartitionHandler.class)) {
            for (var element : declarations(component)) if (element.isAnnotationPresent(LeaseGuard.class) || element.isAnnotationPresent(LeaderTask.class)) {
                throw new IllegalArgumentException("Pure reducers cannot acquire invocation ownership");
            }
            return; // The actual partition factory validates its separate ShardKey extraction profile.
        }
        ManagedInvocationPolicy.super.validateComponent(component, exported);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validateExposure(Class<?> component, Map<ManagedServiceContract, Class<?>> exports) {
        if (component.isAnnotationPresent(PartitionHandler.class)) { validateComponent(component, false); return; }
        ManagedInvocationPolicy.super.validateExposure(component, exports);
    }
    private static List<java.lang.reflect.AnnotatedElement> declarations(Class<?> type) {
        var elements = new ArrayList<java.lang.reflect.AnnotatedElement>(); var seen = new HashSet<Class<?>>(); var pending = new ArrayDeque<Class<?>>(); pending.add(type);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst(); if (!seen.add(current) || current == Object.class) continue;
            if (seen.size() > 256) throw new IllegalArgumentException("Ownership hierarchy capacity");
            var methods = current.getDeclaredMethods();
            if ((long) elements.size() + methods.length + 1 > 4096) throw new IllegalArgumentException("Ownership method capacity");
            elements.add(current); elements.addAll(List.of(methods));
            if (current.getSuperclass() != null) pending.add(current.getSuperclass()); pending.addAll(List.of(current.getInterfaces()));
        }
        return elements;
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) { rule(contract, implementation, businessInterface); }
    private Rule rule(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        var guard = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, LeaseGuard.class);
        var leader = ManagedPolicyMetadata.scalar(contract, implementation, businessInterface, LeaderTask.class);
        String profile = guard == null ? leader == null ? null : leader.value() : guard.value();
        if (guard != null && leader != null && !guard.value().equals(leader.value())) throw new IllegalArgumentException("Conflicting ownership policies");
        var method = ManagedPolicyMetadata.method(contract, businessInterface); int key = -1;
        for (var element : ManagedPolicyMetadata.declarations(contract, implementation, businessInterface)) {
            if (element.isAnnotationPresent(ShardKey.class)) throw new IllegalArgumentException("Ownership supports only a scalar ShardKey parameter");
            if (element instanceof Method declaration) {
                for (int index = 0; index < declaration.getParameterCount(); index++) {
                    var annotation = declaration.getParameters()[index].getAnnotation(ShardKey.class);
                    if (annotation == null) continue;
                    if (!annotation.value().isEmpty() || key != -1 && key != index) throw new IllegalArgumentException("Unsupported ownership key extraction");
                    key = index;
                }
            }
        }
        if (profile == null) {
            if (key >= 0) throw new IllegalArgumentException("ShardKey has no actual ownership or partition boundary");
            return null;
        }
        if (!identifier(profile) || !profiles.containsKey(profile)) throw new IllegalArgumentException("Unknown ownership profile");
        Class<?> type = key < 0 ? null : method.getParameterTypes()[key];
        if (type != null && !scalarType(type)) throw new IllegalArgumentException("Ownership key must be a supported scalar");
        return new Rule(profile, key, type);
    }
    /**
     * {@inheritDoc}
     */
    @Override public Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        Rule rule = rule(contract, implementation, businessInterface); var open = new AtomicBoolean(true);
        return new Binding() {
            public Object invoke(InvocationContext context, Object[] arguments, Proceed next) throws Exception {
                if (closing.get() || !open.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Ownership policy is closed");
                if (rule == null) return next.invoke();
                if (!next.supportsResultFinalizers() || !ManagedInvocationLifetime.currentUserWorkOpen()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Ownership requires an actual owned invocation");
                String tenant = tenantResolver.apply(context);
                if (!tenants.contains(tenant)) throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "Ownership tenant is unavailable");
                var profile = profiles.get(rule.profile); String scalar = rule.key < 0 ? "fixed" : scalar(rule.type, arguments[rule.key]);
                String namespace = OwnershipData.hash(profile.namespace(), tenant, scalar);
                String leaseKey = "ownership.lease." + namespace; String dataPrefix = "ownership.data." + namespace + ".";
                var existing = next.findContext(ManagedOwnershipContext.class);
                if (existing.isPresent()) {
                    if (!existing.orElseThrow().matches(ManagedOwnershipPolicy.this, rule.profile, leaseKey) || existing.orElseThrow().revoked()) {
                        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Nested ownership scope differs");
                    }
                    return next.invoke();
                }
                synchronized (capacity) {
                    if (closing.get()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Ownership policy is closed");
                    if (!capacity.tryAcquire()) throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Ownership claim capacity");
                }
                ManagedOwnershipContext owned = null; boolean registered = false;
                try {
                    Duration remaining = context.control().remainingBudget();
                    if (remaining.isZero()) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Ownership admission budget expired");
                    var session = profile.backend().open(Instant.now().plus(remaining.compareTo(profile.operationBudget()) < 0 ? remaining : profile.operationBudget()));
                    owned = new ManagedOwnershipContext(ManagedOwnershipPolicy.this, rule.profile, profile, session, context.control(), leaseKey, dataPrefix,
                            () -> !closing.get() && open.get(), ManagedOwnershipPolicy.this::schedule, ManagedOwnershipPolicy.this::cancel,
                            ManagedOwnershipPolicy.this::disposed, clock);
                    contexts.add(owned); maintenance.put(owned, new Renewal(owned, profile.renewEvery().toNanos()));
                    next.putContext(ManagedOwnershipContext.class, owned); next.addResultFinalizer(owned::finish); registered = true;
                    owned.acquire(); return next.invoke();
                } catch (Exception | Error failure) {
                    if (!registered) {
                        if (owned == null) capacity.release();
                        else owned.finish(new InvocationResult(null, failure));
                    }
                    throw failure;
                }
            }
            public void close() { open.set(false); }
        };
    }
    private void schedule(ManagedOwnershipContext context) {
        var job = maintenance.get(context); if (job != null && !context.revoked()) job.schedule();
    }
    private void cancel(ManagedOwnershipContext context) {
        var job = maintenance.get(context); if (job != null) job.cancel();
    }
    private void disposed(ManagedOwnershipContext context) {
        maintenance.remove(context); if (contexts.remove(context)) capacity.release();
    }
    /**
     * The job monitor protects only scheduling metadata, never native operations or CALL access.
     */
    private final class Renewal implements Runnable {
        private final ManagedOwnershipContext context;
        private final long interval;
        private ScheduledFuture<?> timer;
        private boolean cancelled;
        Renewal(ManagedOwnershipContext context, long interval) { this.context = context; this.interval = interval; }
        synchronized void schedule() {
            if (cancelled || timer != null) return;
            try { timer = scheduler.schedule(this::dispatch, interval, TimeUnit.NANOSECONDS); }
            catch (RejectedExecutionException unavailable) { context.renewalOverloaded(); }
        }
        private synchronized void dispatch() {
            timer = null; if (cancelled) return;
            try { workers.execute(this); }
            catch (RejectedExecutionException overloaded) { context.renewalOverloaded(); }
        }
        public void run() {
            context.renew();
            // Scope/context inspection happens outside the job monitor, avoiding lock inversion.
            if (!context.revoked()) schedule();
        }
        synchronized void cancel() {
            cancelled = true; if (timer != null) { timer.cancel(false); timer = null; }
            workers.remove(this);
        }
    }
    /**
     * Returns finite aggregate maintenance state.
     * @return immutable redacted counters
     */
    public Status status() { return new Status(contexts.size(), workers.getQueue().size(), closing.get()); }
    /**
     * Stops new admission and closes executors only after actual invocation finalizers disposed all
     * claims. In-flight USER work is never declared physically complete by policy close.
     * @throws IllegalStateException while actual claims still belong to invocations
     */
    @Override public synchronized void close() {
        synchronized (capacity) {
            closing.set(true);
            if (capacity.availablePermits() != 64) throw new IllegalStateException("Actual ownership invocations must drain before host close");
            scheduler.shutdownNow(); workers.shutdownNow();
        }
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{1,128}"); }
    private static boolean scalarType(Class<?> type) {
        return type == String.class || type == UUID.class || type == boolean.class || type == Boolean.class || type == char.class || type == Character.class
                || type == byte.class || type == Byte.class || type == short.class || type == Short.class || type == int.class || type == Integer.class
                || type == long.class || type == Long.class || type == float.class || type == Float.class || type == double.class || type == Double.class;
    }
    private static String scalar(Class<?> type, Object value) {
        if (value == null) throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Ownership key is null");
        String encoded;
        if (value instanceof String text && type == String.class) { if (text.length() > 1024) throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Ownership key bound"); encoded = text; }
        else if (value instanceof UUID uuid && type == UUID.class) encoded = Long.toHexString(uuid.getMostSignificantBits()) + ":" + Long.toHexString(uuid.getLeastSignificantBits());
        else if (value instanceof Float number && (type == Float.class || type == float.class)) encoded = Integer.toHexString(Float.floatToIntBits(number));
        else if (value instanceof Double number && (type == Double.class || type == double.class)) encoded = Long.toHexString(Double.doubleToLongBits(number));
        else if (value instanceof Boolean bool && (type == Boolean.class || type == boolean.class)) encoded = bool ? "true" : "false";
        else if (value instanceof Character character && (type == Character.class || type == char.class)) encoded = String.valueOf(character.charValue());
        else if (value instanceof Byte number && (type == Byte.class || type == byte.class)) encoded = Byte.toString(number);
        else if (value instanceof Short number && (type == Short.class || type == short.class)) encoded = Short.toString(number);
        else if (value instanceof Integer number && (type == Integer.class || type == int.class)) encoded = Integer.toString(number);
        else if (value instanceof Long number && (type == Long.class || type == long.class)) encoded = Long.toString(number);
        else throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Ownership key type differs");
        return OwnershipData.hash(type.getName(), encoded);
    }
}
