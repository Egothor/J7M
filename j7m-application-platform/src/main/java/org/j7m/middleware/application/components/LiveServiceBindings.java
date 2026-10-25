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

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.*;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.application.api.*;

/**
 * Trusted-host owner of at most256 live service bindings. Candidate authorization/readiness is
 * mandatory and never inferred solely from metadata. Two bounded daemon workers coalesce callback
 * snapshots; discovery ownership remains with the supplied source. This is target selection, not
 * an invocation retry, transaction, credential carrier or durable deduplication service.
 */
public final class LiveServiceBindings implements AutoCloseable {
    private final BindingCandidateSource source;
    private final BindingConnector connector;
    private final BindingAdmission admission;
    private final ClientCallInterceptor interceptor;
    private final String localRuntime;
    private final Map<String, BindingPolicy> policies;
    private final Supplier<Instant> callerDeadline;
    private final Set<Binding<?>> bindings = new HashSet<>();
    private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                var thread = Thread.ofPlatform().name("j7m-binding-observer").daemon(true).inheritInheritableThreadLocals(false).unstarted(task);
                thread.setContextClassLoader(LiveServiceBindings.class.getClassLoader());
                return thread;
            });
    private final AtomicLong callbackFailures = new AtomicLong();
    private int reservations;
    private boolean closed;
    /**
     * Safe aggregate lifecycle information.
     * @param bindings active or initializing bindings
     * @param queuedCallbacks bounded queued observer tasks
     * @param callbackFailures failed observer invocations
     * @param closed owner lifecycle state
     */
    public record Status(int bindings, int queuedCallbacks, long callbackFailures, boolean closed) { }
    /**
     * Creates a trusted host owner; no allow-all admission or implicit deadline is supplied.
     * @param source borrowed bounded candidate source
     * @param connector borrowed exact-reference connector without HA retry
     * @param admission trusted bounded authorization/readiness check, thread-safe and no principal mutation
     * @param localRuntime local runtime identity for PREFER_LOCAL
     * @param policies immutable named policies, at most256; names are safe identifiers
     * @param callerDeadline current caller absolute deadline; also capped by policy budget
     * @throws IllegalArgumentException for malformed policy names/capacity
     */
    public LiveServiceBindings(BindingCandidateSource source, BindingConnector connector, BindingAdmission admission,
            String localRuntime, Map<String, BindingPolicy> policies, Supplier<Instant> callerDeadline) {
        this(source, connector, admission, localRuntime, policies, callerDeadline, ClientCallInterceptor.singleAttempt());
    }
    /**
     * Creates a binding owner with an explicit trusted dependency-call interceptor.
     * The default constructor retains one attempt; annotations never install this interceptor.
     * @param source borrowed candidate source
     * @param connector borrowed exact-reference read-only connector
     * @param admission trusted authorization/readiness callback
     * @param localRuntime local runtime identity
     * @param policies finite named binding policies
     * @param callerDeadline current caller deadline, also bounded by binding policy
     * @param interceptor trusted host policy; must preserve pinned identity and physical-call safety
     * @throws IllegalArgumentException for invalid policy names/capacity
     */
    public LiveServiceBindings(BindingCandidateSource source, BindingConnector connector, BindingAdmission admission,
            String localRuntime, Map<String, BindingPolicy> policies, Supplier<Instant> callerDeadline, ClientCallInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor);
        this.source = Objects.requireNonNull(source); this.connector = Objects.requireNonNull(connector); this.admission = Objects.requireNonNull(admission);
        this.localRuntime = Objects.requireNonNull(localRuntime); this.callerDeadline = Objects.requireNonNull(callerDeadline);
        if (policies == null || policies.size() > 256) throw new IllegalArgumentException("Invalid binding policy capacity");
        for (String name : policies.keySet()) if (!name.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid binding policy name");
        this.policies = Map.copyOf(policies);
    }
    /**
     * Returns an immutable host policy for adapter prevalidation without discovery or application work.
     * @param name registered policy ID
     * @return immutable policy
     * @throws IllegalArgumentException if the policy is unknown
     */
    public BindingPolicy policy(String name) {
        BindingPolicy policy = policies.get(name); if (policy == null) throw new IllegalArgumentException("UNKNOWN_BINDING_POLICY"); return policy;
    }
    /**
     * Opens a finite live handle. Required policies establish an initial target within budget.
     * OPTIONAL policies allow initial absence and must be consumed through optionalProvider().
     * @param <T> ordinary business contract
     * @param request exact contract/version/qualifier request
     * @param policyName host policy ID
     * @return owned live handle
     * @throws MiddlewareException for unavailable targets or exhausted capacity
     * @throws IllegalArgumentException for unknown policies
     */
    public <T> ServiceBinding<T> open(ServiceBindingRequest<T> request, String policyName) {
        Objects.requireNonNull(request); validateContract(request.type()); BindingPolicy policy = policies.get(policyName);
        if (policy == null) throw new IllegalArgumentException("UNKNOWN_BINDING_POLICY");
        synchronized (this) { requireOpen(); if (reservations >= 256) throw unavailable("BINDING_CAPACITY"); reservations++; }
        Binding<T> binding = null;
        try {
            binding = new Binding<>(request, policy); binding.initialize();
            synchronized (this) { requireOpen(); bindings.add(binding); }
            return binding;
        } catch (RuntimeException | Error error) {
            if (binding != null) binding.release();
            synchronized (this) { reservations--; } throw error;
        }
    }
    /**
     * Returns aggregate counters without candidate, credential or qualifier labels.
     * @return bounded owner status
     */
    public synchronized Status status() {
        return new Status(reservations, callbacks.getQueue().size(), callbackFailures.get(), closed);
    }
    /**
     * Closes handles and revokes queued observers without closing borrowed source/runtime.
     * In-flight calls keep their captured target and are neither replayed nor claimed undone.
     */
    @Override public void close() {
        List<Binding<?>> current;
        synchronized (this) { if (closed) return; closed = true; current = List.copyOf(bindings); }
        for (var binding : current) binding.close(); callbacks.shutdownNow();
    }
    void validateContract(Class<?> contract) {
        interceptor.validate(contract);
        if (contract.getMethods().length > 4096) throw new IllegalArgumentException("Client method capacity");
        for (Method method : contract.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                if (method.isAnnotationPresent(org.j7m.middleware.annotations.Deadline.class)) throw new IllegalArgumentException("Static deadline metadata is unsupported");
                continue;
            }
            deadlineMillis(contract, method);
        }
    }
    private Long deadlineMillis(Class<?> type, Method method) {
        var identity = new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
        Set<Long> values = new HashSet<>();
        for (var declaration : ManagedMethodSurface.declarations(new ManagedServiceContract("client.validation", 1, identity), type, type)) {
            var annotation = declaration.getDeclaredAnnotation(org.j7m.middleware.annotations.Deadline.class);
            if (annotation != null) values.add(annotation.millis());
        }
        if (values.size() > 1 || values.stream().anyMatch(value -> value <= 0)) throw new IllegalArgumentException("Conflicting or invalid client deadline");
        return values.isEmpty() ? null : values.iterator().next();
    }
    private synchronized void requireOpen() { if (closed) throw new IllegalStateException("BINDINGS_CLOSED"); }
    private static MiddlewareException unavailable(String reason) { return MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, reason); }
    private static String identity(ObjectRef ref) { return ref.runtimeId() + '/' + ref.objectId(); }
    private static String incarnation(ObjectRef ref) { return identity(ref) + '/' + ref.generation(); }
    private Instant deadline(BindingPolicy policy) {
        Instant now = Instant.now(), supplied = Objects.requireNonNull(callerDeadline.get());
        Instant maximum = now.plus(policy.invocationTimeout()); return supplied.isBefore(maximum) ? supplied : maximum;
    }
    private final class Binding<T> implements ServiceBinding<T> {
        private final ServiceBindingRequest<T> request;
        private final BindingPolicy policy;
        private final T proxy;
        private final List<Consumer<BindingSnapshot>> listeners = new ArrayList<>();
        private final Map<String, Long> generations = new HashMap<>();
        private record Failure(long generation, long token) { }
        private final Map<String, Failure> failed = new HashMap<>();
        private final Map<String, Long> lastProbe = new HashMap<>();
        private long failureSequence;
        private final AtomicReference<BindingSnapshot> pending = new AtomicReference<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private long deliveredGeneration = -1;
        private List<ObjectRef> candidates = List.of();
        private ObjectRef selected;
        private AutoCloseable subscription;
        private long generation, revision;
        private boolean initialized, closed, authoritative;
        private String state = "INITIALIZING";
        Binding(ServiceBindingRequest<T> request, BindingPolicy policy) {
            this.request = request; this.policy = policy;
            proxy = request.type().cast(Proxy.newProxyInstance(request.type().getClassLoader(), new Class<?>[]{request.type()}, this::invoke));
        }
        void initialize() {
            subscription = source.watch(request, policy, this::observe);
            if (policy.unavailable() != BindingPolicy.Unavailable.OPTIONAL) choose(deadline(policy), false);
            synchronized (this) { initialized = true; }
        }
        private boolean compatible(ObjectRef ref) {
            return ref.generation() > 0 && ref.isLeaseActive(System.currentTimeMillis()) && ref.serviceName().equals(request.serviceName())
                    && ref.registryContext().equals(request.registryContext()) && ref.interfaces().contains(request.type().getName())
                    && request.contractId().equals(ref.metadata().get("j7m.contract.id"))
                    && Integer.toString(request.contractVersion()).equals(ref.metadata().get("j7m.contract.version"))
                    && ManagedQualifier.fingerprint(request.qualifiers()).equals(ref.metadata().get("j7m.qualifier.fingerprint"));
        }
        private boolean admitted(ObjectRef ref) {
            try { return compatible(ref) && admission.allow(request, ref); }
            catch (RuntimeException error) { return false; }
        }
        private void observe(BindingCandidates observation) {
            // Admission callbacks may inspect external host state; never invoke them under binding locks.
            ObjectRef previous; synchronized (this) { previous = selected; }
            boolean previousHealthy = previous != null && admitted(previous);
            List<ObjectRef> eligible = observation.authoritative() ? observation.candidates().stream().filter(this::admitted).toList() : List.of();
            recover(eligible);
            BindingSnapshot update;
            synchronized (this) {
                if (closed || observation.revision() <= revision) return;
                revision = observation.revision();
                if (!observation.authoritative()) { authoritative = false; state = "DISCOVERY_UNKNOWN"; }
                else if (eligible.size() > policy.maxCandidates()) { authoritative = false; state = "CANDIDATE_CAPACITY"; }
                else {
                    authoritative = true; boolean generationCapacity = false;
                    var accepted = new ArrayList<ObjectRef>(); var unique = new HashSet<String>();
                    for (ObjectRef ref : eligible) {
                        String id = identity(ref); Long seen = generations.get(id);
                        if (seen == null && generations.size() >= policy.maxCandidates()) { generationCapacity = true; continue; }
                        if (seen != null && ref.generation() < seen) continue;
                        generations.put(id, ref.generation());
                        Failure failure = failed.get(id);
                        if (failure != null && ref.generation() > failure.generation()) { failed.remove(id); lastProbe.remove(id); }
                        if (unique.add(incarnation(ref))) accepted.add(ref);
                    }
                    candidates = accepted.stream().filter(ref -> ref.generation() == generations.get(identity(ref))).toList();
                    ObjectRef same = selected == null ? null : candidates.stream().filter(ref -> incarnation(ref).equals(incarnation(selected))).findFirst().orElse(null);
                    if (same != null) selected = same;
                    if (!(initialized && request.refresh() == BindingRefresh.NEVER)) {
                        boolean keep = same != null || request.refresh() == BindingRefresh.ON_FAILURE && Objects.equals(previous, selected) && previousHealthy
                                && Objects.equals(generations.get(identity(previous)), previous.generation())
                                && !quarantined(previous);
                        if (!keep || request.refresh() == BindingRefresh.ON_TOPOLOGY_CHANGE) selected = selection(keep ? selected : null);
                    }
                    state = generationCapacity ? "GENERATION_CAPACITY" : selected == null ? "UNAVAILABLE" : "AVAILABLE";
                }
                generation++; update = snapshot(); notifyAll();
            }
            publish(update);
        }
        private boolean quarantined(ObjectRef ref) {
            Failure failure = failed.get(identity(ref));
            return failure != null && failure.generation() == ref.generation();
        }
        private void quarantine(ObjectRef ref) {
            if (!Objects.equals(generations.get(identity(ref)), ref.generation())) return;
            failed.put(identity(ref), new Failure(ref.generation(), ++failureSequence));
            lastProbe.put(identity(ref), System.nanoTime());
        }
        private void recover(List<ObjectRef> eligible) {
            ObjectRef target = null; Failure marker = null;
            synchronized (this) {
                if (closed) return;
                long now = System.nanoTime();
                for (ObjectRef ref : eligible) {
                    if (quarantined(ref) && now - lastProbe.getOrDefault(identity(ref), now) >= TimeUnit.SECONDS.toNanos(1)) {
                        target = ref; marker = failed.get(identity(ref)); lastProbe.put(identity(ref), now); break;
                    }
                }
            }
            if (target == null) return;
            boolean recovered;
            try { recovered = connector.probe(target, request.type(), new ProxyOptions(Duration.ofMillis(50), Map.of())); }
            catch (RuntimeException unavailable) { recovered = false; }
            if (recovered) synchronized (this) {
                if (!closed && Objects.equals(failed.get(identity(target)), marker)) failed.remove(identity(target));
            }
        }
        private ObjectRef selection(ObjectRef previous) {
            List<ObjectRef> available = candidates.stream().filter(ref -> !quarantined(ref)).sorted(Comparator.comparing(LiveServiceBindings::incarnation)).toList();
            if (policy.selection() == BindingPolicy.Selection.STICKY && previous != null && available.contains(previous)) return previous;
            if (policy.selection() == BindingPolicy.Selection.PREFER_LOCAL) for (ObjectRef ref : available) if (ref.runtimeId().equals(localRuntime)) return ref;
            return available.isEmpty() ? null : available.getFirst();
        }
        private ObjectRef choose(Instant absolute, boolean optional) {
            Duration remaining = Duration.between(Instant.now(), absolute);
            if (remaining.isNegative() || remaining.isZero()) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
            if (remaining.compareTo(policy.invocationTimeout()) > 0) remaining = policy.invocationTimeout();
            return choose(absolute, optional, System.nanoTime() + remaining.toNanos());
        }
        private ObjectRef choose(Instant absolute, boolean optional, long deadlineNanos) {
            Instant started = Instant.now();
            long remaining = absolute.isAfter(started) ? Math.min(policy.waitLimit().toNanos(), Duration.between(started, absolute).toNanos()) : 0;
            remaining = Math.min(remaining, deadlineNanos - System.nanoTime());
            long limit = System.nanoTime() + remaining;
            while (true) {
                if (!absolute.isAfter(Instant.now()) || deadlineNanos - System.nanoTime() <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
                ObjectRef target; long observedGeneration;
                synchronized (this) {
                    if (closed) throw new IllegalStateException("BINDING_CLOSED");
                    if (authoritative && selected == null && request.refresh() != BindingRefresh.NEVER) selected = selection(null);
                    target = authoritative ? selected : null; observedGeneration = generation;
                }
                if (target != null && admitted(target)) return target;
                if (target != null && request.refresh() != BindingRefresh.NEVER) {
                    BindingSnapshot update;
                    synchronized (this) {
                        if (!Objects.equals(selected, target)) continue;
                        quarantine(target); selected = selection(null); generation++; update = snapshot(); notifyAll();
                    }
                    publish(update); continue;
                }
                if (optional) return null;
                if (Instant.now().compareTo(absolute) >= 0 || System.nanoTime() >= limit) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
                if (policy.unavailable() != BindingPolicy.Unavailable.WAIT) throw unavailable("NO_ELIGIBLE_TARGET");
                synchronized (this) {
                    if (generation != observedGeneration) continue;
                    long budget = Math.min(limit - System.nanoTime(), Math.max(0, Duration.between(Instant.now(), absolute).toNanos()));
                    if (budget <= 0) continue;
                    try { TimeUnit.NANOSECONDS.timedWait(this, budget); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "BINDING_WAIT_INTERRUPTED"); }
                }
            }
        }
        private Object invoke(Object ignored, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy); case "toString" -> "LiveServiceProxy[stable]"; default -> throw new IllegalStateException("INVALID_PROXY_METHOD");
            };
            Instant started = Instant.now();
            long startedNanos = System.nanoTime();
            Duration budget = policy.invocationTimeout();
            Duration callerBudget = Duration.between(started, deadline(policy));
            if (callerBudget.compareTo(budget) < 0) budget = callerBudget.isNegative() ? Duration.ZERO : callerBudget;
            Long budgetMillis = deadlineMillis(request.type(), method);
            if (budgetMillis != null && Duration.ofMillis(budgetMillis).compareTo(budget) < 0) budget = Duration.ofMillis(budgetMillis);
            Instant absolute = started.plus(budget);
            long localDeadline = startedNanos + budget.toNanos();
            InvocationControl parent = J7mContext.currentInvocation().control();
            if (parent != InvocationControl.unavailable()) {
                if (parent.cancellationRequested()) throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Parent invocation cancellation requested");
                long observed = System.nanoTime();
                Duration parentBudget = Objects.requireNonNull(parent.remainingBudget());
                if (parentBudget.isNegative()) throw new IllegalStateException("Invalid parent invocation budget");
                long left = localDeadline - observed;
                if (left > 0 && parentBudget.compareTo(Duration.ofNanos(left)) < 0) localDeadline = observed + parentBudget.toNanos();
            }
            ObjectRef target = choose(absolute, false, localDeadline);
            var identity = new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                    Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
            var call = new ClientCall(new ManagedServiceContract(request.contractId(), request.contractVersion(), identity), request.type(), method, target, localDeadline);
            return interceptor.invoke(call, new Attempt(target, method, args, localDeadline));
        }
        private final class Attempt implements ClientAttempt {
            private final ObjectRef target;
            private final Method method;
            private final Object[] arguments;
            private final long deadline;
            private boolean entered;
            Attempt(ObjectRef target, Method method, Object[] arguments, long deadline) {
                this.target = target; this.method = method; this.arguments = arguments; this.deadline = deadline;
            }
            @Override public boolean businessEntered() { return entered; }
            @Override public boolean probe() {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
                return connector.probe(target, request.type(), new ProxyOptions(Duration.ofNanos(remaining),
                        Map.of(ProxyOptions.LOCAL_DEADLINE_NANOS, Long.toString(deadline))));
            }
            @Override public Object execute() throws Throwable {
                entered = false;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
                var metadata = new HashMap<>(InvocationReliabilityOptions.defaults().toMetadata());
                metadata.put(ProxyOptions.LOCAL_DEADLINE_NANOS, Long.toString(deadline));
                T concrete;
                try { concrete = request.type().cast(connector.connect(target, request.type(), new ProxyOptions(Duration.ofNanos(remaining), metadata))); }
                catch (RuntimeException failure) { failed(target, failure, true); throw failure; }
                if (deadline - System.nanoTime() <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "BINDING_DEADLINE");
                entered = true;
                try { return method.invoke(concrete, arguments); }
                catch (InvocationTargetException failure) {
                    Throwable cause = failure.getTargetException(); failed(target, cause, false); throw cause;
                }
            }
        }
        private void failed(ObjectRef target, Throwable error, boolean connectionStage) {
            if (!(error instanceof MiddlewareException failure)) return;
            boolean reachability = failure.code() == MiddlewareErrorCode.UNAVAILABLE || failure.code() == MiddlewareErrorCode.TRANSPORT_ERROR || failure.code() == MiddlewareErrorCode.LEASE_EXPIRED;
            if (connectionStage && (failure.code() == MiddlewareErrorCode.NOT_FOUND || failure.code() == MiddlewareErrorCode.DEADLINE_EXCEEDED)) reachability = true;
            if (!reachability || (!connectionStage && !failure.dirtyTarget())) return;
            BindingSnapshot update;
            synchronized (this) {
                if (closed || request.refresh() == BindingRefresh.NEVER || selected == null || !incarnation(selected).equals(incarnation(target))) return;
                quarantine(target); selected = selection(null); state = selected == null ? "UNAVAILABLE" : "AVAILABLE";
                generation++; update = snapshot(); notifyAll();
            }
            publish(update);
        }
        @Override public synchronized T proxy() {
            if (closed) throw new IllegalStateException("BINDING_CLOSED");
            if (policy.unavailable() == BindingPolicy.Unavailable.OPTIONAL) throw new IllegalStateException("OPTIONAL_REQUIRES_PROVIDER");
            return proxy;
        }
        @Override public ManagedProvider<Optional<T>> optionalProvider() {
            return () -> Optional.ofNullable(choose(deadline(policy), true)).map(ref -> proxy);
        }
        @Override public synchronized BindingSnapshot snapshot() {
            return new BindingSnapshot(generation, revision, candidates, Optional.ofNullable(selected), closed ? "CLOSED" : state);
        }
        @Override public synchronized AutoCloseable watch(Consumer<BindingSnapshot> listener) {
            Objects.requireNonNull(listener); if (closed) throw new IllegalStateException("BINDING_CLOSED");
            if (listeners.size() >= 32) throw new IllegalStateException("BINDING_LISTENER_CAPACITY");
            listeners.add(listener); return () -> { synchronized (this) { listeners.remove(listener); } };
        }
        private void publish(BindingSnapshot update) {
            synchronized (this) { if (closed || listeners.isEmpty()) return; }
            pending.accumulateAndGet(update, (previous, next) -> previous != null && previous.generation() > next.generation() ? previous : next);
            if (scheduled.compareAndSet(false, true)) try { callbacks.execute(this::deliver); }
            catch (RejectedExecutionException stopped) { scheduled.set(false); pending.set(null); }
        }
        private void deliver() {
            try {
                BindingSnapshot update;
                while ((update = pending.getAndSet(null)) != null) {
                    List<Consumer<BindingSnapshot>> current;
                    synchronized (this) { if (closed) return; if (update.generation() <= deliveredGeneration) continue; deliveredGeneration = update.generation(); current = List.copyOf(listeners); }
                    for (var listener : current) { synchronized (this) { if (closed) return; } try { listener.accept(update); } catch (RuntimeException error) { callbackFailures.incrementAndGet(); } }
                }
            } finally { scheduled.set(false); BindingSnapshot latest = pending.get(); if (latest != null) publish(latest); }
        }
        void release() {
            AutoCloseable watch;
            synchronized (this) { if (closed) return; closed = true; listeners.clear(); pending.set(null); watch = subscription; notifyAll(); }
            if (watch != null) try { watch.close(); } catch (Exception error) { callbackFailures.incrementAndGet(); }
        }
        @Override public void close() {
            release(); synchronized (LiveServiceBindings.this) { if (bindings.remove(this)) reservations--; }
        }
    }
}
