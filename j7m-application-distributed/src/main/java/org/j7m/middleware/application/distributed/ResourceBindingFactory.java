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

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.io.IOException;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostBindings;
import org.j7m.middleware.distributed.*;

/**
 * Trusted optional host adapter for named D resources, separate from ObjectRef business RPC.
 * Raw group resources accept NEVER only: their native leader protocol is intrinsic and unchanged.
 * Partition registrations support authoritative ownership refresh with no same-call replay.
 */
public final class ResourceBindingFactory implements ManagedHostBindings.Factory {
    private interface InvocationValue { Object current() throws Exception; }
    private interface Prepare { Prepared get(BindingRefresh mode) throws Exception; }
    private record Prepared(Object value, AutoCloseable close) { }
    /**
     * Opaque host-owned resource registration, created only through concrete supported factories.
     */
    public static final class Registration {
        private final Class<?> type; private final boolean dynamic; private final Prepare prepare;
        private Registration(Class<?> type, boolean dynamic, Prepare prepare) { this.type = type; this.dynamic = dynamic; this.prepare = prepare; }
    }
    private final Map<String, Registration> resources;
    private final Set<String> policies;
    /**
     * Registers finite explicitly authorized named resource capabilities.
     * @param resources at most256 immutable named registrations
     * @param policies host-approved binding policy IDs, at most256
     * @throws IllegalArgumentException for invalid IDs/capacity
     */
    public ResourceBindingFactory(Map<String, Registration> resources, Set<String> policies) {
        if (resources == null || resources.size() > 256 || policies == null || policies.size() > 256) throw new IllegalArgumentException("Invalid resource binding capacity");
        for (String name : resources.keySet()) if (!name.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid resource binding name");
        for (String name : policies) if (!name.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid resource policy name");
        this.resources = Map.copyOf(resources); this.policies = Set.copyOf(policies);
    }
    /**
     * Registers a provider-only lookup of the current acquired deduplication claim.
     * The returned value is the actual lifetime-bound capability, never a mutable global facade.
     * @return NEVER-only registration requiring an active managed deduplication invocation
     */
    public static Registration deduplicationContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
        return new Registration(ManagedDeduplicationContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Registers provider-only lookup of the actual current native ownership claim.
     * An existing claim object never rebinds to another owner. Lookup outside an owned invocation
     * fails, and refresh modes cannot manufacture a new lease or revive a revoked context.
     * @return NEVER-only invocation capability registration
     */
    public static Registration ownershipContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class);
        return new Registration(ManagedOwnershipContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Supplies the actual current saga callback context through a provider only.
     * @return host registration that cannot capture a context across callbacks
     */
    public static Registration sagaContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedSagaContext.class);
        return new Registration(ManagedSagaContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Supplies the actual current job callback context through a provider only.
     * @return NEVER-only registration that cannot capture or retarget callback ownership
     */
    public static Registration jobContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedJobContext.class);
        return new Registration(ManagedJobContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Registers one host-selected saga client with a revocable binding view.
     * Closing this binding blocks new client operations without closing shared native endpoints.
     * @param client trusted definition/version facade
     * @return NEVER-refresh registration; each operation resolves actual admitted ownership
     * @throws NullPointerException for a missing client
     */
    public static Registration saga(ManagedSagaClient client) {
        Objects.requireNonNull(client);
        return new Registration(ManagedSagaClient.class, false, mode -> {
            var active = new java.util.concurrent.atomic.AtomicBoolean(true);
            return new Prepared(client.scoped(active::get), () -> active.set(false));
        });
    }
    /**
     * Registers provider-only access to the actual current native resource transaction.
     * @return NEVER-only borrowed invocation capability registration
     */
    public static Registration resourceTransactionContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedResourceTransactionContext.class);
        return new Registration(ManagedResourceTransactionContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Registers provider-only access to the actual current local JDBC transaction context.
     * @return NEVER-only borrowed invocation capability registration
     */
    public static Registration jdbcTransactionContext() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class);
        return new Registration(ManagedJdbcTransactionContext.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Registers the current transaction's exact guarded Connection, without a global switching proxy.
     * @return provider-only NEVER registration; lookup requires actual owned USER context
     */
    public static Registration jdbcConnection() {
        InvocationValue lookup = () -> org.j7m.middleware.application.components.ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class).connection();
        return new Registration(java.sql.Connection.class, false, mode -> new Prepared(lookup, () -> { }));
    }
    /**
     * Borrows a typed encoded-program client with its own authoritative per-operation D08 routing.
     * The binding supports NEVER selection because the client already resolves current ownership.
     * Its concrete program/schema contract is validated on every operation; typed query additionally
     * checks the requested Java view type. No generic DI graph extension is needed.
     * Closing the binding revokes its scoped view before further codec/RPC entry, without closing
     * the trusted host's original client, schemas or shared endpoints.
     * @param client prepared thread-safe authorized client for one concrete program and scope
     * @return explicit typed client capability registration
     * @throws NullPointerException for a missing client
     */
    public static Registration partitionProgram(ManagedPartitionClient client) {
        Objects.requireNonNull(client);
        return new Registration(ManagedPartitionClient.class, false, mode -> {
            var active = new java.util.concurrent.atomic.AtomicBoolean(true);
            return new Prepared(client.scoped(active::get), () -> active.set(false));
        });
    }
    /**
     * Borrows a raw group endpoint; native D leader following and durable identities are preserved.
     * @param endpoint already authenticated authorized resource endpoint
     * @return registration supporting NEVER selection
     */
    public static Registration group(ResourceEndpoint endpoint) {
        Objects.requireNonNull(endpoint); return new Registration(ResourceEndpoint.class, false, mode -> new Prepared(endpoint, () -> { }));
    }
    /**
     * Borrows a raw single-owner partition group endpoint; no ownership refresh is claimed.
     * @param endpoint authenticated authorized group endpoint
     * @return registration supporting NEVER selection
     */
    public static Registration partitionGroup(PartitionEndpoint endpoint) {
        Objects.requireNonNull(endpoint); return new Registration(PartitionEndpoint.class, false, mode -> new Prepared(endpoint, () -> { }));
    }
    /**
     * Registers an authoritative partition identity with bounded preparation and trusted clients.
     * @param partition stable partition identity
     * @param topology separately authorized topology read facade
     * @param groups authorized group endpoints
     * @param preparationBudget positive at most30seconds
     * @return registration supporting all three binding modes
     * @throws IllegalArgumentException for invalid preparation budget
     */
    public static Registration partition(String partition, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups, Duration preparationBudget) {
        Objects.requireNonNull(topology); Map<String, PartitionEndpoint> copied = Map.copyOf(groups);
        if (preparationBudget == null || preparationBudget.isZero() || preparationBudget.isNegative() || preparationBudget.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid resource preparation budget");
        return new Registration(PartitionEndpoint.class, true, mode -> {
            var endpoint = new RefreshingPartitionEndpoint(partition, topology, copied, mode, Instant.now().plus(preparationBudget)); return new Prepared(endpoint, endpoint);
        });
    }
    /**
     * Constructs an authorized scope-bound queue client over a fixed owner group.
     * Native leader following and identical-ID MOVED/FROZEN handling remain queue protocol behavior.
     * @param partition stable queue partition
     * @param scope authenticated endpoint tenant scope
     * @param endpoint borrowed authorized group endpoint
     * @param epoch positive fixed ownership epoch
     * @return NEVER-only typed registration; closing revokes its endpoint wrapper
     */
    public static Registration queueGroup(String partition, String scope, PartitionEndpoint endpoint, long epoch) {
        validateTyped(partition, scope); Objects.requireNonNull(endpoint); new ReplicatedQueue.Route(endpoint, epoch);
        return typed(partition, scope, ReplicatedQueue.class, false, mode -> new EndpointLease(endpoint, () -> { }, () -> epoch), Map::of, null);
    }
    /**
     * Constructs an authorized scope-bound execution client over a fixed owner group.
     * @param partition stable execution partition
     * @param scope authenticated endpoint tenant scope
     * @param endpoint borrowed authorized group endpoint
     * @param epoch positive fixed ownership epoch
     * @return NEVER-only typed execution registration
     */
    public static Registration executionGroup(String partition, String scope, PartitionEndpoint endpoint, long epoch) {
        validateTyped(partition, scope); Objects.requireNonNull(endpoint); new ReplicatedQueue.Route(endpoint, epoch);
        return typed(partition, scope, ExecutionClient.class, false, mode -> new EndpointLease(endpoint, () -> { }, () -> epoch), Map::of, null);
    }
    /**
     * Constructs a real queue facade with authoritative owner routing and stable durable identities.
     * Binding refresh never repeats an endpoint invocation. Native queue retries only its documented
     * identical-ID MOVED/FROZEN protocol, never an IOException/UNKNOWN or a new business identity.
     * @param partition stable queue partition
     * @param scope authenticated tenant scope
     * @param topology borrowed authorized topology reader
     * @param groups borrowed authorized group clients
     * @param preparationBudget positive preparation bound at most30seconds
     * @return typed queue registration supporting all refresh modes
     */
    public static Registration queue(String partition, String scope, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups, Duration preparationBudget) {
        validateTyped(partition, scope);
        return typed(partition, scope, ReplicatedQueue.class, true, routed(partition, topology, groups, preparationBudget), Map::of, null);
    }
    /**
     * Constructs a real jobs/sagas/RPC execution client with authoritative owner routing.
     * External effects are never invoked by this factory or client; UNKNOWN needs reconciliation.
     * @param partition stable execution partition
     * @param scope authenticated tenant scope
     * @param topology borrowed authorized topology reader
     * @param groups borrowed authorized group clients
     * @param preparationBudget positive preparation bound at most30seconds
     * @return typed execution registration supporting all refresh modes
     */
    public static Registration execution(String partition, String scope, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups, Duration preparationBudget) {
        validateTyped(partition, scope);
        return typed(partition, scope, ExecutionClient.class, true, routed(partition, topology, groups, preparationBudget), Map::of, null);
    }
    /**
     * Constructs an instrumented typed execution binding without a vendor dependency.
     * @param partition stable execution partition
     * @param scope authenticated tenant scope
     * @param topology borrowed authorized topology reader
     * @param groups borrowed authorized group clients
     * @param preparationBudget positive bounded preparation time
     * @param context calling-thread W3C carrier supplier, never credentials
     * @param observer no-throw execution lifecycle observer
     * @return typed execution registration preserving supplied context/instrumentation hooks
     */
    public static Registration execution(String partition, String scope, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups, Duration preparationBudget,
            Supplier<Map<String, String>> context, ExecutionClient.Observer observer) {
        validateTyped(partition, scope); Objects.requireNonNull(context); Objects.requireNonNull(observer);
        return typed(partition, scope, ExecutionClient.class, true, routed(partition, topology, groups, preparationBudget), context, observer);
    }
    private interface PrepareEndpoint { EndpointLease get(BindingRefresh mode) throws Exception; }
    private record EndpointLease(PartitionEndpoint endpoint, AutoCloseable release, java.util.function.LongSupplier epoch) { }
    private static PrepareEndpoint routed(String partition, PartitionAdminClient topology, Map<String, PartitionEndpoint> groups, Duration budget) {
        Objects.requireNonNull(topology); Map<String, PartitionEndpoint> copied = Map.copyOf(groups);
        if (budget == null || budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid resource preparation budget");
        return mode -> {
            var endpoint = new RefreshingPartitionEndpoint(partition, topology, copied, mode, Instant.now().plus(budget));
            return new EndpointLease(endpoint, endpoint, () -> endpoint.ownership().epoch());
        };
    }
    private static void validateTyped(String partition, String scope) {
        if (partition == null || partition.isBlank() || partition.length() > 256 || scope == null || scope.isBlank() || scope.length() > 512) throw new IllegalArgumentException("Invalid typed resource identity");
    }
    private static Registration typed(String partition, String scope, Class<?> type, boolean dynamic, PrepareEndpoint prepare,
            Supplier<Map<String, String>> context, ExecutionClient.Observer observer) {
        return new Registration(type, dynamic, mode -> {
            EndpointLease lease = prepare.get(mode); var guarded = new RevocableEndpoint(lease.endpoint, lease.release);
            ReplicatedQueue.RouteResolver routes = (ignored, deadline) -> new ReplicatedQueue.Route(guarded, lease.epoch.getAsLong());
            try {
                Object value = type == ReplicatedQueue.class ? new ReplicatedQueue(partition, scope, routes)
                        : observer == null ? new ExecutionClient(partition, scope, routes, context) : new ExecutionClient(partition, scope, routes, context, observer);
                return new Prepared(value, guarded);
            } catch (RuntimeException error) { guarded.close(); throw error; }
        });
    }
    private static final class RevocableEndpoint implements PartitionEndpoint, AutoCloseable {
        private final PartitionEndpoint delegate; private final AutoCloseable release; private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<QueueSession> sessions = new HashSet<>();
        private final Set<ExecutionSession> executions = new HashSet<>();
        RevocableEndpoint(PartitionEndpoint delegate, AutoCloseable release) { this.delegate = delegate; this.release = release; }
        private void open() throws IOException { if (closed.get()) throw new IOException("Typed resource binding closed"); }
        @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) throws IOException { open(); return delegate.execute(epoch, request, command, query, deadline); }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException { open(); return delegate.query(epoch, query, deadline); }
        @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException { open(); return delegate.status(deadline); }
        @Override public QueueSession subscribeQueue(long epoch, String queue, String group, String member, long visibilityMillis, boolean observational, Instant deadline) throws IOException {
            open(); QueueSession session = delegate.subscribeQueue(epoch, queue, group, member, visibilityMillis, observational, deadline);
            synchronized (sessions) {
                sessions.removeIf(QueueSession::isClosed);
                if (closed.get() || sessions.size() >= 64) { session.close(); throw new IOException("Resource push binding unavailable"); }
                sessions.add(session);
            }
            return session;
        }
        @Override public ExecutionSession subscribeExecution(long epoch, String prefix, String kind, String owner, long leaseMillis, Instant deadline) throws IOException {
            open(); ExecutionSession session = delegate.subscribeExecution(epoch, prefix, kind, owner, leaseMillis, deadline);
            synchronized (sessions) {
                executions.removeIf(ExecutionSession::isClosed);
                if (closed.get() || executions.size() >= 64) { session.close(); throw new IOException("Resource execution push binding unavailable"); }
                executions.add(session);
            }
            return session;
        }
        @Override public void close() throws Exception {
            if (closed.compareAndSet(false, true)) {
                synchronized (sessions) { sessions.forEach(QueueSession::close); sessions.clear(); executions.forEach(ExecutionSession::close); executions.clear(); }
                release.close();
            }
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedDependency dependency, Scope.Kind scope) {
        if (!dependency.attributes().keySet().equals(Set.of("resource.name", "binding.refresh", "binding.policy"))) throw new IllegalArgumentException("Unsupported resource metadata");
        String name = dependency.attributes().get("resource.name"); Registration resource = resources.get(name);
        if (resource == null || !resource.type.equals(dependency.type()) || !dependency.qualifiers().isEmpty()
                || !dependency.name().isEmpty() && !dependency.name().equals(name)) throw new IllegalArgumentException("Unsupported resource capability");
        if (!policies.contains(dependency.attributes().get("binding.policy"))) throw new IllegalArgumentException("Unknown resource policy");
        BindingRefresh mode = BindingRefresh.valueOf(dependency.attributes().get("binding.refresh"));
        if (Set.of(ManagedJobContext.class, ManagedSagaContext.class, ManagedOwnershipContext.class, ManagedDeduplicationContext.class, ManagedResourceTransactionContext.class, ManagedJdbcTransactionContext.class, java.sql.Connection.class).contains(resource.type) && !dependency.provider()) throw new IllegalArgumentException("Invocation context requires a provider");
        if (!resource.dynamic && mode != BindingRefresh.NEVER) throw new IllegalArgumentException("Resource ownership refresh unsupported");
    }
    /**
     * {@inheritDoc}
     */
    @Override public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) throws Exception {
        validate(dependency, Scope.Kind.ACTIVATION);
        Registration resource = resources.get(dependency.attributes().get("resource.name"));
        Prepared prepared = resource.prepare.get(BindingRefresh.valueOf(dependency.attributes().get("binding.refresh")));
        var closed = new AtomicBoolean();
        Object proxy = resource.type.isInterface() && !(prepared.value instanceof InvocationValue) ? Proxy.newProxyInstance(resource.type.getClassLoader(), new Class<?>[]{resource.type}, (self, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == arguments[0]; case "hashCode" -> System.identityHashCode(self); case "toString" -> "ManagedResourceProxy[stable]"; default -> throw new IllegalStateException("Invalid resource proxy method");
            };
            if (closed.get()) throw new IllegalStateException("Resource binding closed");
            try { return method.invoke(prepared.value, arguments); }
            catch (InvocationTargetException error) { throw error.getTargetException(); }
        }) : prepared.value;
        return new ManagedHostBindings.BoundValue() {
            @Override public Object get() { if (closed.get()) throw new IllegalStateException("Resource binding closed"); if (prepared.value instanceof InvocationValue current) {
                    try { return current.current(); } catch (Exception unavailable) { throw new IllegalStateException("Invocation resource unavailable"); }
                }
                return proxy; }
            @Override public void close() throws Exception { if (closed.compareAndSet(false, true)) prepared.close.close(); }
        };
    }
}
