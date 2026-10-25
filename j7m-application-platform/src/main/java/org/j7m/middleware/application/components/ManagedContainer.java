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

import java.util.*;
import java.util.concurrent.Callable;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.annotations.ConfigProperties;
import org.j7m.middleware.application.api.*;

/**
 * Opt-in application-owned container for finite generated component indexes. The full graph is
 * validated before any product is created; activation products are then eagerly constructed.
 * This API does not deploy applications, scan classpaths, invoke lifecycle hooks, refresh routes
 * or change the legacy injector. Owned AutoCloseable values close in reverse creation order.
 * A failed construction invalidates and disposes its resolving scope. Call scopes are explicit;
 * async propagation and hosting integration are separate APIs.
 */
public final class ManagedContainer implements AutoCloseable {
    private static final int MAX_DEFINITIONS = 1024;
    private record Key(Class<?> type, String name, Set<ManagedQualifier> qualifiers) { }
    private record Plan(Key key, Scope.Kind scope, List<ManagedDependency> dependencies,
            boolean owned, ManagedComponentDefinition<?> definition) { }
    private static final class LifecycleEntry {
        final ManagedHostAdapter<Object> adapter;
        final Object instance;
        boolean initialized, started, drained, stopped;
        LifecycleEntry(ManagedHostAdapter<Object> adapter, Object instance) { this.adapter = adapter; this.instance = instance; }
    }
    private static final class Context {
        final Map<Plan, Object> cache = new HashMap<>();
        final List<Object> owned = new ArrayList<>();
        final List<LifecycleEntry> lifecycle = new ArrayList<>();
        boolean closed;
    }
    /**
     * Bounded aggregate lifecycle counters without component names or configuration values.
     * @param definitions finite registered definitions
     * @param activeCalls currently open call scopes
     * @param ownedInstances live uniquely owned products
     * @param created successful product creations, including borrowed values
     * @param failures construction/disposal failures
     * @param closed whether the activation has closed
     */
    public record Status(int definitions, int activeCalls, int ownedInstances, long created, long failures, boolean closed) { }
    /**
     * Safe graph/lifecycle failure without application exception messages or qualifier values.
     */
    public static final class Failure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /**
         * Stable non-sensitive failure category.
         * @serial bounded category without application details
         */
        private final String code;
        /**
         * Safe immutable graph evidence; never contains a retained application Throwable.
         * @serial redacted structured failure metadata
         */
        private final InjectionDiagnostic diagnostic;
        private Failure(String code) { this(code, List.of()); }
        private Failure(String code, List<String> path) {
            super(code); this.code = code;
            var bounded = path.stream().limit(32).map(value -> value.length() > 256 ? value.substring(0, 256) : value).toList();
            String graph = String.join(" -> ", bounded); if (graph.length() > 4096) graph = graph.substring(0, 4096);
            diagnostic = new InjectionDiagnostic(java.time.Instant.EPOCH, bounded.isEmpty() ? "" : bounded.getFirst(),
                    "resolve", "FAILED", code, graph.isEmpty() ? Map.of() : Map.of("graphPath", graph));
        }
        /**
         * Returns the stable failure category.
         * @return bounded code without payloads
         */
        public String code() { return code; }
        /**
         * Returns safe structured evidence from the actual failed graph/resolution path.
         * Paths contain at most32 class names and4096 characters; no qualifier/config values or
         * application exception messages are retained. Epoch denotes an unspecified event time.
         * @return immutable redacted diagnostic with the same stable category as code()
         */
        public InjectionDiagnostic diagnostic() { return diagnostic; }
    }
    private final List<Plan> plans;
    private final Context activation = new Context();
    private final List<CallScope> calls = new ArrayList<>();
    private final IdentityHashMap<Object, Context> owners = new IdentityHashMap<>();
    private final ThreadLocal<CallScope> current = new ThreadLocal<>();
    private final int capacity;
    private final LiveConfiguration configuration;
    private final Map<ManagedDependency, ConfigBinding<?>> configurationBindings = new HashMap<>();
    private final ManagedHostBindings hostRegistry;
    private final Map<ManagedDependency, ManagedHostBindings.BoundValue> hostValues = new LinkedHashMap<>();
    private Thread cleanupThread;
    private int lifecyclePhase;
    private long created, failures;

    private ManagedContainer(Collection<? extends ManagedComponentIndex> indexes, int capacity, LiveConfiguration configuration, ManagedHostBindings hostRegistry) {
        if (capacity < 1 || capacity > 1_000_000) throw fail("INVALID_CAPACITY");
        this.capacity = capacity; this.configuration = configuration; this.hostRegistry = Objects.requireNonNull(hostRegistry); plans = snapshot(indexes); validate();
        try { bindConfiguration(); bindHosts(); for (Plan plan : plans) if (plan.scope == Scope.Kind.ACTIVATION) resolve(plan, activation); }
        catch (RuntimeException | Error error) { dispose(activation, error); detachConfiguration(); throw error; }
    }
    /**
     * Validates and starts a finite activation with a4096-instance ownership/call limit.
     * @param indexes explicit generated indexes, never a classpath scan
     * @return active managed container
     * @throws IllegalStateException for invalid graphs or construction failure
     */
    public static ManagedContainer activate(Collection<? extends ManagedComponentIndex> indexes) {
        return activate(indexes, 4096);
    }
    /**
     * Starts a finite activation with explicit ownership and open-call capacity.
     * @param indexes explicit version1 indexes
     * @param capacity maximum live owned values and separately maximum open call scopes,1..1000000
     * @return active container
     * @throws IllegalStateException for unsupported metadata, invalid graph or failed creation
     */
    public static ManagedContainer activate(Collection<? extends ManagedComponentIndex> indexes, int capacity) {
        return new ManagedContainer(indexes, capacity, null, ManagedHostBindings.empty());
    }
    /**
     * Starts a finite activation with explicit host-owned configuration.
     * The container owns only its binding registrations, never the configuration owner.
     * @param indexes explicit version1 indexes
     * @param configuration borrowed configuration owner
     * @return active container with4096-instance capacity
     * @throws IllegalStateException for invalid graphs/configuration or construction failure
     */
    public static ManagedContainer activate(Collection<? extends ManagedComponentIndex> indexes, LiveConfiguration configuration) {
        return activate(indexes, 4096, configuration);
    }
    /**
     * Starts a bounded activation with configuration bindings validated before constructors.
     * @param indexes explicit version1 indexes
     * @param capacity maximum owned instances and separately open calls
     * @param configuration borrowed nonnull host configuration
     * @return active managed container
     * @throws IllegalStateException for invalid configuration, metadata or graph
     */
    public static ManagedContainer activate(Collection<? extends ManagedComponentIndex> indexes, int capacity, LiveConfiguration configuration) {
        return new ManagedContainer(indexes, capacity, Objects.requireNonNull(configuration), ManagedHostBindings.empty());
    }
    /**
     * Resolves an activation/prototype root; CALL roots require an explicit call scope.
     * @param <T> requested contract
     * @param type required contract
     * @param name exact name, never an unnamed fallback
     * @param qualifiers exact qualifiers
     * @return selected product
     * @throws IllegalStateException for invalid selection, scope or failed construction
     */
    public synchronized <T> T get(Class<T> type, String name, List<ManagedQualifier> qualifiers) {
        open(); return root(type, name, qualifiers, activation);
    }
    /**
     * Validates and starts an activation with explicitly granted host integration capabilities.
     * @param indexes finite trusted generated definitions
     * @param capacity maximum owned instances and concurrent call scopes
     * @param configuration optional live configuration, required by config edges
     * @param bindings explicit immutable host capability registry, never application supplied
     * @return started activation; host bindings are owned and closed on failure or shutdown
     * @throws IllegalStateException for invalid graph, metadata, binding or construction failure
     */
    public static ManagedContainer activate(Collection<? extends ManagedComponentIndex> indexes, int capacity,
            LiveConfiguration configuration, ManagedHostBindings bindings) {
        return new ManagedContainer(indexes, capacity, configuration, bindings);
    }

    /**
     * Creates a lazy root lookup. CALL values require CallScope.call to be active on this thread.
     * @param <T> required contract
     * @param type required contract
     * @param name exact name
     * @param qualifiers exact qualifiers
     * @return provider which never captures an old call scope
     * @throws IllegalStateException if selection is missing/ambiguous or the container is closed
     */
    public synchronized <T> ManagedProvider<T> provider(Class<T> type, String name, List<ManagedQualifier> qualifiers) {
        open(); Plan plan = select(key(type, name, qualifiers)); return () -> provided(type, plan);
    }
    /**
     * Opens a detached explicit call scope. Use call() to make it visible to lazy providers.
     * @return new independently owned scope
     * @throws IllegalStateException if closed or the bounded call capacity is exhausted
     */
    public synchronized CallScope openCall() {
        open(); if (lifecyclePhase >= 3) throw fail("CALL_DRAINING"); if (calls.size() >= capacity) throw fail("CALL_CAPACITY");
        var scope = new CallScope(); calls.add(scope); return scope;
    }
    /**
     * Returns aggregate state without sensitive component metadata.
     * @return immutable lifecycle counters
     */
    public synchronized Status status() { return new Status(plans.size(), calls.size(), owners.size(), created, failures, activation.closed); }

    /**
     * Runs managed lifecycle phases over owned generated host adapters.
     * Initialization/startup follow dependency construction order; drain/shutdown use reverse order.
     * Newly created CALL/PROTOTYPE values inherit the activation's initialized/started phases and
     * are shut down when their actual owning scope closes. Failed hooks never cause repeat cleanup.
     * @param phase requested phase, never null
     * @throws IllegalStateException on application hook failure or shutdown while calls are running
     */
    public synchronized void lifecycle(ManagedLifecycle phase) {
        Objects.requireNonNull(phase); if (phase == ManagedLifecycle.SHUTDOWN && lifecyclePhase == 4) return; open();
        if (phase == ManagedLifecycle.SHUTDOWN) {
            for (CallScope call : calls) if (call.depth != 0 || call.retentions != 0) throw fail("CALL_BUSY");
        }
        int requested = switch (phase) { case INITIALIZE -> 1; case STARTUP -> 2; case DRAIN -> 3; case SHUTDOWN -> 4; };
        lifecyclePhase = Math.max(lifecyclePhase, requested);
        Failure aggregate = null;
        List<LifecycleEntry> entries = phase == ManagedLifecycle.DRAIN || phase == ManagedLifecycle.SHUTDOWN
                ? List.copyOf(activation.lifecycle).reversed() : List.copyOf(activation.lifecycle);
        for (LifecycleEntry entry : entries) {
            try { invokeLifecycle(entry, phase); }
            catch (Exception error) {
                failures++; if (aggregate == null) aggregate = fail("LIFECYCLE_FAILED");
                aggregate.addSuppressed(fail("COMPONENT_LIFECYCLE_FAILED"));
                if (phase == ManagedLifecycle.INITIALIZE || phase == ManagedLifecycle.STARTUP) break;
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private static void invokeLifecycle(LifecycleEntry entry, ManagedLifecycle phase) throws Exception {
        switch (phase) {
            case INITIALIZE -> {
                if (!entry.initialized) { entry.initialized = true; entry.adapter.lifecycle(entry.instance, phase); }
            }
            case STARTUP -> {
                invokeLifecycle(entry, ManagedLifecycle.INITIALIZE);
                if (!entry.started) { entry.started = true; entry.adapter.lifecycle(entry.instance, phase); }
            }
            case DRAIN -> {
                if (entry.initialized && !entry.drained) { entry.drained = true; entry.adapter.lifecycle(entry.instance, phase); }
            }
            case SHUTDOWN -> {
                if (entry.initialized && !entry.stopped) { entry.stopped = true; entry.adapter.lifecycle(entry.instance, phase); }
            }
        }
    }

    /**
     * Explicit call ownership boundary, shared neither implicitly nor through captured providers.
     * A scope may move between threads serially but concurrent use is rejected. Closing a running
     * scope is rejected rather than reporting false completion. Returned raw values must not be
     * used after their owning scope closes.
     */
    public final class CallScope implements AutoCloseable {
        private final Context context = new Context();
        private Thread thread;
        private int depth;
        private int retentions;
        private CallScope() { }
        /**
         * Resolves a product within this call's ownership, making providers available during creation.
         * @param <T> requested contract
         * @param type required contract
         * @param name exact name
         * @param qualifiers exact qualifiers
         * @return call/activation/prototype product
         * @throws IllegalStateException for closed/concurrent use or failed resolution
         */
        public <T> T get(Class<T> type, String name, List<ManagedQualifier> qualifiers) {
            try { return call(() -> { synchronized (ManagedContainer.this) { return root(type, name, qualifiers, context); } }); }
            catch (RuntimeException error) { throw error; }
            catch (Exception impossible) { throw fail("CALL_RESOLUTION_FAILED"); }
        }
        /**
         * Runs code with this explicit call visible to providers; restores prior thread state.
         * No new thread or async continuation inherits this scope automatically.
         * @param <T> result type
         * @param work synchronous caller-owned work
         * @return work result
         * @throws Exception the work's exception; callback failures do not imply an external undo
         * @throws IllegalStateException for invalid scope ownership
         */
        public <T> T call(Callable<T> work) throws Exception {
            Objects.requireNonNull(work);
            synchronized (ManagedContainer.this) {
                open(); if (context.closed) throw fail("CALL_CLOSED");
                if (thread != null && thread != Thread.currentThread()) throw fail("CALL_CONCURRENT_USE");
                if (current.get() != null && current.get() != this) throw fail("NESTED_CALL_SCOPE");
                thread = Thread.currentThread(); depth++; current.set(this);
            }
            try { return work.call(); }
            finally { synchronized (ManagedContainer.this) { if (--depth == 0) { current.remove(); thread = null; } } }
        }
        /**
         * Retains this call across idle asynchronous handoffs. A retained scope cannot be disposed.
         * The host closes the token only after actual workers and context finally blocks have exited.
         * @return idempotent ownership token
         * @throws IllegalStateException when closed or the container's per-call retention capacity is full
         */
        public AutoCloseable retain() {
            synchronized (ManagedContainer.this) {
                open(); if (context.closed) throw fail("CALL_CLOSED");
                if (retentions >= capacity) throw fail("CALL_RETENTION_CAPACITY");
                retentions++;
            }
            return new AutoCloseable() {
                private boolean released;
                @Override public void close() {
                    synchronized (ManagedContainer.this) {
                        if (!released) { released = true; retentions--; }
                    }
                }
            };
        }
        /**
         * Disposes owned call values once in reverse creation order; borrowed values remain live.
         * @throws IllegalStateException if running or if any disposer fails; remaining disposers still run
         */
        @Override public void close() {
            synchronized (ManagedContainer.this) {
                if (depth != 0 || retentions != 0) throw fail("CALL_BUSY");
                if (context.closed) { calls.remove(this); return; }
                Failure disposal = dispose(context, null); calls.remove(this); if (disposal != null) throw disposal;
            }
        }
    }
    /**
     * Closes idle call scopes and then activation products. Running calls must drain explicitly.
     * All remaining disposers run even if one fails. Repeated close never repeats disposal.
     * @throws IllegalStateException if calls are running or any disposer fails
     */
    @Override public synchronized void close() {
        if (activation.closed) return;
        for (CallScope call : calls) if (call.depth != 0 || call.retentions != 0) throw fail("CALL_BUSY");
        Failure aggregate = null;
        for (CallScope call : List.copyOf(calls).reversed()) {
            Failure error = dispose(call.context, null); if (error != null) { if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(error); }
        }
        calls.clear(); Failure error = dispose(activation, null); detachConfiguration();
        if (error != null) { if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(error); }
        if (aggregate != null) throw aggregate;
    }
    private void open() { if (activation.closed) throw fail("CONTAINER_CLOSED"); if (lifecyclePhase >= 4) throw fail("CONTAINER_STOPPING"); }
    private static Failure fail(String code) { return new Failure(code); }
    private static Failure located(Failure failure, List<String> path) {
        var result = new Failure(failure.code(), path);
        for (var suppressed : failure.getSuppressed()) result.addSuppressed(suppressed);
        return result;
    }
    private static Key key(Class<?> type, String name, List<ManagedQualifier> qualifiers) {
        var checked = new ManagedDependency(type, name, qualifiers, false); return new Key(checked.type(), checked.name(), Set.copyOf(checked.qualifiers()));
    }
    private static List<Plan> snapshot(Collection<? extends ManagedComponentIndex> indexes) {
        Objects.requireNonNull(indexes); if (indexes.size() > MAX_DEFINITIONS) throw fail("INDEX_CAPACITY");
        var result = new ArrayList<Plan>(); var keys = new HashSet<Key>();
        for (var index : indexes) {
            if (index.formatVersion() != 1) throw fail("UNSUPPORTED_INDEX_FORMAT");
            if (index.methods().size() > 4096) throw fail("METHOD_CAPACITY");
            var methods = new HashSet<ManagedMethod>();
            for (var method : index.methods()) if (!methods.add(Objects.requireNonNull(method))) throw fail("DUPLICATE_METHOD");
            for (var definition : List.copyOf(index.components())) {
                if (result.size() >= MAX_DEFINITIONS) throw fail("DEFINITION_CAPACITY");
                Key key = key(definition.type(), definition.name(), definition.qualifiers());
                if (!keys.add(key)) throw fail("DUPLICATE_BINDING");
                var dependencies = List.copyOf(definition.dependencies());
                if (dependencies.size() > 256) throw fail("DEPENDENCY_CAPACITY");
                result.add(new Plan(key, Objects.requireNonNull(definition.scope()), dependencies, definition.owned(), definition));
            }
        }
        return List.copyOf(result);
    }
    private Plan select(Key requested) {
        Plan found = null;
        for (Plan plan : plans) if (requested.type.isAssignableFrom(plan.key.type) && requested.name.equals(plan.key.name) && requested.qualifiers.equals(plan.key.qualifiers)) {
            if (found != null) throw fail("AMBIGUOUS_DEPENDENCY"); found = plan;
        }
        if (found == null) throw fail("MISSING_DEPENDENCY"); return found;
    }
    private void validate() {
        var edges = new HashMap<Plan, List<Plan>>();
        for (Plan plan : plans) {
            var targets = new ArrayList<Plan>();
            for (var dependency : plan.dependencies) {
                if (dependency.attributes().isEmpty()) {
                    try { targets.add(select(key(dependency.type(), dependency.name(), dependency.qualifiers()))); }
                    catch (Failure failure) { throw located(failure, List.of(plan.key.type.getName(), dependency.type().getName())); }
                }
                else if (configurationEdge(dependency)) validateConfiguration(dependency);
                else {
                    if (!hostRegistry.matches(dependency)) throw fail("UNSUPPORTED_DEPENDENCY_METADATA");
                    try { hostRegistry.select(dependency).validate(dependency, plan.scope); }
                    catch (RuntimeException error) { throw fail("INVALID_HOST_BINDING"); }
                }
            }
            edges.put(plan, targets);
        }
        var done = new HashSet<Plan>(); var visiting = new HashSet<Plan>();
        for (Plan plan : plans) cycle(plan, edges, visiting, done, new ArrayList<>());
        for (Plan plan : plans) if (plan.scope == Scope.Kind.ACTIVATION) scopeCheck(plan, new HashSet<>(), new ArrayList<>());
    }
    private static void cycle(Plan plan, Map<Plan, List<Plan>> edges, Set<Plan> visiting, Set<Plan> done, List<String> path) {
        if (done.contains(plan)) return;
        path.add(plan.key.type.getName());
        if (!visiting.add(plan)) throw located(fail("DEPENDENCY_CYCLE"), path);
        for (Plan next : edges.get(plan)) cycle(next, edges, visiting, done, path);
        path.removeLast();
        visiting.remove(plan); done.add(plan);
    }
    private void scopeCheck(Plan plan, Set<Plan> checked, List<String> path) {
        if (!checked.add(plan)) return;
        path.add(plan.key.type.getName());
        if (plan.scope == Scope.Kind.CALL) throw located(fail("SCOPE_CAPTURE"), path);
        for (var dependency : plan.dependencies) if (!dependency.provider() && dependency.attributes().isEmpty()) scopeCheck(select(key(dependency.type(), dependency.name(), dependency.qualifiers())), checked, path);
        path.removeLast();
    }
    private <T> T root(Class<T> type, String name, List<ManagedQualifier> qualifiers, Context context) {
        Plan plan = select(key(type, name, qualifiers));
        if (plan.scope == Scope.Kind.CALL && context == activation) throw fail("NO_ACTIVE_CALL");
        try { return type.cast(resolve(plan, context)); }
        catch (RuntimeException | Error error) { dispose(context, error); throw error; }
    }
    private synchronized <T> T provided(Class<T> type, Plan plan) {
        if (cleanupThread == Thread.currentThread() && activation.closed) {
            Object value = activation.cache.get(plan);
            if (value == null || plan.scope == Scope.Kind.PROTOTYPE) throw fail("SCOPE_CLOSED");
            return type.cast(value);
        }
        open(); CallScope call = current.get(); Context context = call == null ? activation : call.context;
        if (context.closed) throw fail("CALL_CLOSED");
        if (plan.scope == Scope.Kind.CALL && call == null) throw fail("NO_ACTIVE_CALL");
        try { return type.cast(resolve(plan, context)); }
        catch (RuntimeException | Error error) { dispose(context, error); throw error; }
    }
    private Object resolve(Plan plan, Context caller) {
        Context owner = plan.scope == Scope.Kind.ACTIVATION ? activation : caller;
        if (owner.closed) throw fail("SCOPE_CLOSED");
        if (plan.scope == Scope.Kind.CALL && owner == activation) throw fail("NO_ACTIVE_CALL");
        if (plan.scope != Scope.Kind.PROTOTYPE && owner.cache.containsKey(plan)) return owner.cache.get(plan);
        var resolver = new Resolver(plan, owner);
        try {
            Object instance = plan.definition.create(resolver);
            if (instance == null || !plan.key.type.isInstance(instance)) throw fail("INVALID_PRODUCT");
            if (plan.owned) {
                Context existing = owners.get(instance);
                if (existing != null && existing != owner) throw fail("CROSS_SCOPE_OWNERSHIP");
                if (existing == null) {
                    if (owners.size() >= capacity) { closeRejected(instance); throw fail("INSTANCE_CAPACITY"); }
                    owners.put(instance, owner); owner.owned.add(instance);
                }
            }
            inject(plan.definition, instance, resolver);
            registerLifecycle(plan, instance, owner);
            created++; if (plan.scope != Scope.Kind.PROTOTYPE) owner.cache.put(plan, instance); return instance;
        } catch (Exception error) {
            failures++;
            if (error instanceof Failure failure) {
                String nested = failure.diagnostic().metadata().getOrDefault("graphPath", "");
                var path = new ArrayList<String>(); path.add(plan.key.type.getName());
                if (!nested.isEmpty()) path.addAll(Arrays.asList(nested.split(" -> ", 32)));
                throw located(failure, path);
            }
            throw located(fail("CONSTRUCTION_FAILED"), List.of(plan.key.type.getName()));
        }
        finally { resolver.active = false; }
    }
    @SuppressWarnings("unchecked")
    private void registerLifecycle(Plan plan, Object instance, Context owner) throws Exception {
        if (!plan.owned || !(plan.definition instanceof ManagedHostAdapter<?> adapter)) return;
        for (LifecycleEntry existing : owner.lifecycle) if (existing.instance == instance) return;
        LifecycleEntry entry = new LifecycleEntry((ManagedHostAdapter<Object>) adapter, instance);
        owner.lifecycle.add(entry);
        if (lifecyclePhase >= 1) invokeLifecycle(entry, ManagedLifecycle.INITIALIZE);
        if (lifecyclePhase >= 2) invokeLifecycle(entry, ManagedLifecycle.STARTUP);
        if (lifecyclePhase >= 3) invokeLifecycle(entry, ManagedLifecycle.DRAIN);
    }
    private void closeRejected(Object instance) {
        if (instance instanceof AutoCloseable closeable) try { closeable.close(); } catch (Exception error) { failures++; }
    }
    @SuppressWarnings("unchecked")
    private static <T> void inject(ManagedComponentDefinition<T> definition, Object instance, ManagedResolver resolver) throws Exception { definition.inject((T) instance, resolver); }
    private final class Resolver implements ManagedResolver {
        private final Plan plan; private final Context context;
        private final Thread creator = Thread.currentThread();
        private boolean active = true;
        Resolver(Plan plan, Context context) { this.plan = plan; this.context = context; }
        private void declared(ManagedDependency edge) {
            if (!active || creator != Thread.currentThread() || context.closed) throw fail("RESOLVER_INACTIVE");
            if (!plan.dependencies.contains(edge)) throw fail("UNDECLARED_DEPENDENCY");
        }
        @Override public <T> T resolve(Class<T> type, String name, List<ManagedQualifier> qualifiers) {
            var edge = new ManagedDependency(type, name, qualifiers, false); declared(edge); return type.cast(ManagedContainer.this.resolve(select(key(type, name, qualifiers)), context));
        }
        @Override public <T> ManagedProvider<T> provider(Class<T> type, String name, List<ManagedQualifier> qualifiers) {
            var edge = new ManagedDependency(type, name, qualifiers, true); declared(edge); Plan target = select(key(type, name, qualifiers)); return () -> provided(type, target);
        }
        @Override public <T> T resolve(ManagedDependency edge) {
            declared(edge); if (edge.attributes().isEmpty()) return ManagedResolver.super.resolve(edge);
            if (edge.provider()) throw fail("DEPENDENCY_KIND_MISMATCH"); return externalValue(edge);
        }
        @Override public <T> ManagedProvider<T> provider(ManagedDependency edge) {
            declared(edge); if (edge.attributes().isEmpty()) return ManagedResolver.super.provider(edge);
            if (!edge.provider()) throw fail("DEPENDENCY_KIND_MISMATCH");
            return () -> { synchronized (ManagedContainer.this) {
                if (cleanupThread != Thread.currentThread()) open();
                return externalValue(edge);
            } };
        }
    }
    private void validateConfiguration(ManagedDependency edge) {
        Set<String> keys = edge.attributes().keySet();
        if (!keys.equals(Set.of("config.key", "config.default", "config.required", "config.refresh"))
                && !keys.equals(Set.of("config.prefix", "config.refresh"))) throw fail("UNSUPPORTED_DEPENDENCY_METADATA");
        if (configuration == null) throw fail("CONFIGURATION_REQUIRED");
        if (!edge.name().isEmpty() || !edge.qualifiers().isEmpty()) throw fail("UNSUPPORTED_CONFIG_QUALIFIER");
        boolean refresh = flag(edge, "config.refresh");
        if (refresh && !edge.provider()) throw fail("REFRESH_REQUIRES_PROVIDER");
        if (keys.contains("config.required")) flag(edge, "config.required");
        else {
            ConfigProperties properties = edge.type().getAnnotation(ConfigProperties.class);
            if (properties == null || !properties.prefix().equals(edge.attributes().get("config.prefix"))
                    || properties.refresh() != refresh) throw fail("CONFIGURATION_METADATA_MISMATCH");
        }
    }
    private static boolean flag(ManagedDependency edge, String key) {
        String value = edge.attributes().get(key);
        if (!"true".equals(value) && !"false".equals(value)) throw fail("INVALID_CONFIGURATION_FLAG");
        return Boolean.parseBoolean(value);
    }
    private void bindConfiguration() {
        for (Plan plan : plans) for (var edge : plan.dependencies) if (configurationEdge(edge) && !configurationBindings.containsKey(edge)) {
            try {
                ConfigBinding<?> binding = edge.attributes().containsKey("config.key")
                        ? configuration.bindValue(edge.type(), edge.attributes().get("config.key"), flag(edge, "config.required"),
                                edge.attributes().get("config.default"), flag(edge, "config.refresh"), value -> { })
                        : configuration.bindProperties(edge.type(), value -> { });
                configurationBindings.put(edge, binding);
            } catch (RuntimeException error) { throw fail("CONFIGURATION_BINDING_FAILED"); }
        }
    }
    private static boolean configurationEdge(ManagedDependency edge) {
        return edge.attributes().containsKey("config.key") || edge.attributes().containsKey("config.prefix");
    }
    private void bindHosts() {
        Set<ManagedHostBindings.BoundValue> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Plan plan : plans) for (var edge : plan.dependencies) {
            if (edge.attributes().isEmpty() || configurationEdge(edge) || hostValues.containsKey(edge)) continue;
            if (hostValues.size() >= MAX_DEFINITIONS) throw fail("HOST_BINDING_CAPACITY");
            try {
                ManagedHostBindings.BoundValue binding = Objects.requireNonNull(hostRegistry.select(edge).bind(edge));
                if (!owned.add(binding)) throw fail("HOST_BINDING_ALIAS");
                hostValues.put(edge, binding);
            } catch (Exception error) { throw fail("HOST_BINDING_FAILED"); }
        }
    }
    @SuppressWarnings("unchecked")
    private <T> T externalValue(ManagedDependency edge) {
        if (configurationEdge(edge)) return configurationValue(edge);
        try {
            Object value = hostValues.get(edge).get();
            boolean optionalService = edge.provider() && edge.attributes().containsKey("service.name")
                    && "true".equals(edge.attributes().get("binding.optional"));
            if (optionalService) {
                if (!(value instanceof Optional<?> optional) || optional.isPresent() && !edge.type().isInstance(optional.get())) {
                    throw fail("INVALID_HOST_VALUE");
                }
            } else if (value == null || !edge.type().isInstance(value)) throw fail("INVALID_HOST_VALUE");
            return (T) value;
        } catch (RuntimeException error) { throw fail("HOST_VALUE_UNAVAILABLE"); }
    }
    private Failure detachHosts() {
        Failure aggregate = null;
        var values = List.copyOf(hostValues.values()).reversed(); hostValues.clear();
        for (ManagedHostBindings.BoundValue value : values) {
            try { value.close(); }
            catch (Exception error) {
                failures++; if (aggregate == null) aggregate = fail("HOST_BINDING_DISPOSAL_FAILED");
                aggregate.addSuppressed(fail("HOST_BINDING_CLOSE_FAILED"));
            }
        }
        return aggregate;
    }
    @SuppressWarnings("unchecked")
    private <T> T configurationValue(ManagedDependency edge) { return (T) configurationBindings.get(edge).get(); }
    private void detachConfiguration() {
        for (ConfigBinding<?> binding : configurationBindings.values()) binding.close();
        configurationBindings.clear();
    }
    private Failure dispose(Context context, Throwable original) {
        if (context.closed) return null; context.closed = true;
        Thread previousCleanup = cleanupThread; cleanupThread = Thread.currentThread();
        try {
        Failure aggregate = null;
        for (LifecycleEntry entry : context.lifecycle.reversed()) {
            try { invokeLifecycle(entry, ManagedLifecycle.DRAIN); }
            catch (Exception error) { failures++; if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(fail("DRAIN_FAILED")); }
            try { invokeLifecycle(entry, ManagedLifecycle.SHUTDOWN); }
            catch (Exception error) { failures++; if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(fail("SHUTDOWN_FAILED")); }
        }
        context.lifecycle.clear();
        for (Object instance : context.owned.reversed()) {
            owners.remove(instance);
            if (instance instanceof AutoCloseable closeable) try { closeable.close(); }
            catch (Exception error) { failures++; if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(fail("PRODUCT_DISPOSAL_FAILED")); }
        }
        context.owned.clear(); context.cache.clear();
        if (context == activation) {
            detachConfiguration(); Failure hostFailure = detachHosts();
            if (hostFailure != null) { if (aggregate == null) aggregate = fail("DISPOSAL_FAILED"); aggregate.addSuppressed(hostFailure); }
        }
        if (original != null && aggregate != null) original.addSuppressed(aggregate);
        return aggregate;
        } finally { cleanupThread = previousCleanup; }
    }
}
