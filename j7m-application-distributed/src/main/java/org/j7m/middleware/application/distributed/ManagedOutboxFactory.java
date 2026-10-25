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
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;

/**
 * Ordinary-interface outbox producers plus automatically generated methodless relay lifecycles.
 * Producer writes share the actual A11 guarded Connection; no queue send occurs in that transaction.
 * One bounded host relay per logical SQL namespace survives individual binding departure. It uses
 * stable native publication IDs, so a crash between send and SQL mark never creates a new message.
 * Connections opened by relays are owned, while factories, codecs, queues and telemetry are borrowed.
 */
public final class ManagedOutboxFactory implements ManagedHostBindings.Factory, ManagedHostedResources.Factory, AutoCloseable {
    /**
     * Finite ordinary producer capability. Each method's policy selects one authorized outbox.
     * @param contract exact ordinary producer interface, or null for trusted symbolic registration
     * @param contractName exact authorized binary name; never used for class loading
     * @param profiles immutable policy-name to exact publication/relay capability, at most64
     */
    public record Registration(Class<?> contract, String contractName, Map<String, OutboxRelayProfile> profiles) {
        /**
         * Copies the registry without constructing values or opening connections.
         */
        public Registration {
            DeliveryIdentities.typeName(contractName); TransactionPolicySupport.names(profiles); profiles = Map.copyOf(profiles);
            if (contract != null && (!contract.isInterface() || !contract.getName().equals(contractName)) || profiles.isEmpty() || profiles.size() > 64) throw new IllegalArgumentException("Invalid outbox producer registration");
        }
        /**
         * Registers a host-visible exact interface identity.
         * @param contract authorized ordinary producer interface
         * @param profiles finite publication capabilities
         */
        public Registration(Class<?> contract, Map<String, OutboxRelayProfile> profiles) { this(Objects.requireNonNull(contract), contract.getName(), profiles); }
        /**
         * Authorizes an application-owned interface by exact binary name. Actual generated
         * dependency type and its entire method/schema contract are validated before construction;
         * no class is loaded from this name and no application marker grants a capability.
         * @param contractName authorized interface binary name
         * @param profiles finite publication capabilities
         */
        public Registration(String contractName, Map<String, OutboxRelayProfile> profiles) { this(null, contractName, profiles); }
        /**
         * Omits connection and policy values from diagnostics.
         * @return fixed safe registration identity
         */
        @Override public String toString() { return "OutboxProducerRegistration[host-owned]"; }
    }
    /**
     * Safe aggregate relay status, without SQL values, payload or exception messages.
     * @param bindings number of bound lifecycle owners
     * @param admitted number of committed local owners
     * @param working actual SQL/send/mark operation still owned
     * @param confirmed count of native sends confirmed and SQL-marked by this host
     * @param unavailable whether the last completed run failed
     */
    public record Status(int bindings, int admitted, boolean working, long confirmed, boolean unavailable) { }
    private final Map<String, Registration> resources;
    private final Map<String, Relay> relays = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private int leases;
    private boolean closed;

    /**
     * Builds finite explicit producer/relay registrations, rejecting conflicting shared SQL scopes.
     * @param resources named resource capabilities, at most256, at most64 distinct SQL namespaces
     * @param maximumWorkers physical relay workers1..16
     * @throws IllegalArgumentException for invalid methods, profiles or conflicting shared capabilities
     */
    public ManagedOutboxFactory(Map<String, Registration> resources, int maximumWorkers) {
        TransactionPolicySupport.names(resources); this.resources = Map.copyOf(resources);
        if (maximumWorkers < 1 || maximumWorkers > 16) throw new IllegalArgumentException("Outbox worker bounds");
        Map<String, OutboxRelayProfile> scopes = new HashMap<>();
        for (Registration registration : this.resources.values()) {
            if (registration.contract() != null) validateContract(registration, registration.contract());
            for (OutboxRelayProfile profile : registration.profiles().values()) {
                OutboxRelayProfile prior = scopes.putIfAbsent(profile.publication().scope(), profile);
                if (prior != null && !same(prior, profile)) throw new IllegalArgumentException("Conflicting shared SQL outbox configuration");
            }
        }
        if (scopes.size() > 64) throw new IllegalArgumentException("Outbox namespace capacity");
        workers = new ThreadPoolExecutor(maximumWorkers, maximumWorkers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), threads("j7m-outbox-relay"), new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, threads("j7m-outbox-timer")); timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private static ThreadFactory threads(String name) {
        return task -> { Thread thread = new Thread(null, task, name, 0, false); thread.setDaemon(true); thread.setContextClassLoader(ManagedOutboxFactory.class.getClassLoader()); return thread; };
    }
    private static boolean same(OutboxRelayProfile a, OutboxRelayProfile b) {
        var x = a.publication(); var y = b.publication();
        return x.namespace().equals(y.namespace()) && x.tenant().equals(y.tenant()) && x.publisherId().equals(y.publisherId()) && x.publisherVersion() == y.publisherVersion()
                && x.jdbcPolicy().equals(y.jdbcPolicy()) && x.jdbcRegistration() == y.jdbcRegistration() && x.codec() == y.codec() && x.intents() == y.intents() && x.telemetry() == y.telemetry()
                && x.intentRetention().equals(y.intentRetention()) && x.payloadRetention().equals(y.payloadRetention()) && a.destination() == b.destination()
                && a.queue().equals(b.queue()) && a.maximumBatch() == b.maximumBatch() && a.interval().equals(b.interval()) && a.operationBudget().equals(b.operationBudget());
    }
    private void validateContract(Registration registration, Class<?> contract) {
        if (!contract.isInterface() || !Modifier.isPublic(contract.getModifiers()) || !contract.getName().equals(registration.contractName())
                || registration.contract() != null && registration.contract() != contract) throw new IllegalArgumentException("Outbox producer identity mismatch");
        Method[] methods = contract.getMethods(); if (methods.length < 1 || methods.length > 64) throw new IllegalArgumentException("Outbox method capacity");
        for (Method method : methods) {
            var marker = method.getAnnotation(OutboxPublisher.class); OutboxRelayProfile relay = marker == null ? null : registration.profiles().get(marker.policy());
            if (relay == null || method.isDefault() || Modifier.isStatic(method.getModifiers()) || method.getReturnType() != long.class || method.getParameterCount() != 3
                    || method.getParameterTypes()[0] != String.class || method.getParameterTypes()[1] != String.class
                    || !marker.value().equals(relay.publication().namespace()) || marker.version() != relay.publication().publisherVersion())
                throw new IllegalArgumentException("Unsupported outbox producer method");
            relay.publication().codec().forDefinition(contract, List.of(method.getParameterTypes()[2])).validateDeclaredType(method.getParameterTypes()[2]);
        }
    }
    /**
     * Declares only the actual outbox annotation capability.
     * @return exact immutable marker set
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(OutboxPublisher.class); }
    /**
     * Declares only the methodless backend kind implemented here; it grants no generic callback bypass.
     * @return outbox-relay backend capability
     */
    @Override public Set<String> backendKinds() { return Set.of("outbox-relay"); }
    /**
     * Validates provider-only source binding without opening SQL or queue connections.
     * @param dependency generated exact resource edge
     * @param scope component capture scope
     * @throws IllegalArgumentException for an unknown resource, direct capture or unsupported refresh
     */
    @Override public void validate(ManagedDependency dependency, Scope.Kind scope) {
        Registration registration = resources.get(dependency.attributes().get("resource.name"));
        if (registration == null || !dependency.provider() || !dependency.qualifiers().isEmpty()
                || !dependency.attributes().keySet().equals(Set.of("resource.name", "binding.refresh", "binding.policy"))
                || !dependency.attributes().get("binding.refresh").equals("NEVER") || !dependency.attributes().get("binding.policy").equals("default"))
            throw new IllegalArgumentException("Unsupported outbox resource binding");
        validateContract(registration, dependency.type());
    }
    /**
     * Creates a revocable ordinary producer; actual enqueue requires this caller's owned JDBC scope.
     * @param dependency validated producer dependency
     * @return owned local binding, never owner of the borrowed backend registrations
     */
    @Override public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) {
        validate(dependency, Scope.Kind.ACTIVATION); Registration registration = resources.get(dependency.attributes().get("resource.name")); var revoked = new AtomicBoolean();
        Map<Method, ManagedPayloadCodec> selectedCodecs = new HashMap<>();
        for (Method method : dependency.type().getMethods()) {
            var profile = registration.profiles().get(method.getAnnotation(OutboxPublisher.class).policy()).publication();
            selectedCodecs.put(method, profile.codec().forDefinition(dependency.type(), List.of(method.getParameterTypes()[2])));
        }
        Map<Method, ManagedPayloadCodec> codecs = Map.copyOf(selectedCodecs);
        Object producer = Proxy.newProxyInstance(dependency.type().getClassLoader(), new Class<?>[]{dependency.type()}, (self, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == arguments[0]; case "hashCode" -> System.identityHashCode(self); case "toString" -> "ManagedOutboxPublisher[stable]";
                default -> throw new IllegalStateException("Unsupported Object method");
            };
            if (revoked.get() || !ManagedInvocationLifetime.currentUserWorkOpen()) throw new IllegalStateException("Outbox scope unavailable");
            var jdbc = ManagedInvocationLifetime.currentContext(ManagedJdbcTransactionContext.class); var marker = method.getAnnotation(OutboxPublisher.class);
            OutboxRelayProfile relay = registration.profiles().get(marker.policy()); OutboxProfile profile = relay.publication();
            try {
                if (!jdbc.profile.equals(profile.jdbcPolicy()) || jdbc.registration != profile.jdbcRegistration()) throw new IllegalStateException("Outbox requires exact JDBC capability");
                InvocationContext invocation = J7mContext.currentInvocation();
                if (invocation.security().principal().id().equals("anonymous")) throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "Outbox requires authenticated caller");
                if (arguments == null || arguments.length != 3 || !(arguments[0] instanceof String id) || !(arguments[1] instanceof String aggregate)) throw new IllegalArgumentException("Outbox identities required");
                DeliveryIdentities.businessKey(id); DeliveryIdentities.businessKey(aggregate);
                OutboxDefinition.ensure(jdbc.connection(), relay);
                byte[] frame = codecs.get(method).encode(arguments[2], method.getParameterTypes()[2]);
                String message = DeliveryIdentities.digest("outbox-publication", profile.scope(), method.getDeclaringClass().getName(), method.getName(),
                        Arrays.stream(method.getParameterTypes()).map(Class::getName).toList().toString(), method.getReturnType().getName(), id);
                String aggregateId = DeliveryIdentities.digest(profile.scope(), aggregate);
                String hash = DeliveryIdentities.digest(relay.queue(), aggregateId, Base64.getEncoder().encodeToString(frame));
                var content = PublicationIntentStore.remember(profile.intents(), profile.intentRetention(), message, hash, frame, invocation);
                TransactionPolicySupport.deadline(invocation); Instant created = Instant.now();
                return profile.delivery().enqueue(jdbc.connection(), message, aggregateId, content, created, created.plus(profile.payloadRetention()));
            } catch (Exception failure) {
                jdbc.failNested();
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Outbox enqueue outcome unavailable");
            }
        });
        return new ManagedHostBindings.BoundValue() {
            @Override public Object get() { if (revoked.get()) throw new IllegalStateException("Outbox binding closed"); return producer; }
            @Override public void close() { revoked.set(true); }
        };
    }
    private OutboxRelayProfile featureProfile(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition) {
        if (!feature.kind().equals("outbox-relay") || !feature.methods().isEmpty()
                || !feature.attributes().keySet().equals(Set.of("resource", "outbox", "policy", "version"))) throw new IllegalArgumentException("Invalid methodless outbox feature");
        Registration registration = resources.get(feature.attributes().get("resource"));
        OutboxRelayProfile profile = registration == null ? null : registration.profiles().get(feature.attributes().get("policy"));
        if (profile == null || !profile.publication().namespace().equals(feature.attributes().get("outbox"))
                || !Integer.toString(profile.publication().publisherVersion()).equals(feature.attributes().get("version")) || feature.version() != profile.publication().publisherVersion()
                || !feature.id().equals(definition.type().getName() + '#' + feature.attributes().get("resource") + '#' + feature.attributes().get("outbox") + '#' + feature.attributes().get("version")))
            throw new IllegalArgumentException("Outbox descriptor/profile mismatch");
        boolean actual = false;
        for (var dependency : definition.dependencies()) if (feature.attributes().get("resource").equals(dependency.attributes().get("resource.name"))) {
            validate(dependency, definition.scope()); actual = true;
        }
        if (!actual) throw new IllegalArgumentException("Outbox feature has no actual selected producer dependency");
        return profile;
    }
    /**
     * Validates an actual methodless generated dependency feature before constructors.
     * @param feature exact finite relay descriptor
     * @param definition owning component definition, including selected dependency sites
     * @param bundle verified immutable bundle context
     * @throws IllegalArgumentException for forged callbacks, resource, profile or duplicate SQL configuration
     */
    @Override public void validate(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) { Objects.requireNonNull(bundle); featureProfile(feature, definition); }
    /**
     * Joins one shared logical relay through an independently owned activation lease.
     * @param feature validated methodless descriptor
     * @param definition generated dependency owner
     * @param component unused and possibly null for a methodless backend; never constructed here
     * @param bundle verified immutable bundle
     * @param invoker actual admission guard; no application callback is invoked by the relay
     * @return owned independent lease whose departure cannot stop another active owner
     */
    @Override public synchronized ManagedHostedResources.Binding bind(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition,
            Object component, ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) {
        validate(feature, definition, bundle); OutboxRelayProfile profile = featureProfile(feature, definition);
        if (closed || leases >= 256) throw new IllegalStateException("Outbox binding capacity");
        Relay relay = relays.computeIfAbsent(profile.publication().scope(), ignored -> new Relay(profile));
        if (!same(relay.profile, profile)) throw new IllegalArgumentException("Conflicting shared outbox profile");
        Lease lease = new Lease(relay, Objects.requireNonNull(invoker)); relay.owners.add(lease); leases++; return lease;
    }
    /**
     * Reads local safe counters without SQL or queue I/O.
     * @param resource exact host resource name
     * @param policy exact publication profile name
     * @return local relay counters, zero when not currently bound
     * @throws IllegalArgumentException for an unknown resource/profile
     */
    public synchronized Status status(String resource, String policy) {
        Registration registration = resources.get(resource); OutboxRelayProfile profile = registration == null ? null : registration.profiles().get(policy);
        if (profile == null) throw new IllegalArgumentException("Unknown outbox resource/profile");
        Relay relay = relays.get(profile.publication().scope());
        return relay == null ? new Status(0, 0, false, 0, false) : new Status(relay.owners.size(), (int) relay.owners.stream().filter(owner -> owner.committed).count(), relay.working || relay.preparing, relay.confirmed, relay.unavailable);
    }
    /**
     * Releases host executors only after every owned lease has actually drained.
     * @throws IllegalStateException while a relay lease or physical operation remains
     */
    @Override public synchronized void close() {
        if (closed) return; if (leases != 0 || relays.values().stream().anyMatch(relay -> relay.working || relay.preparing)) throw new IllegalStateException("Outbox leases must drain first");
        closed = true; timer.shutdown(); workers.shutdown();
    }
    private final class Relay {
        final OutboxRelayProfile profile; final Set<Lease> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean working, preparing, prepared, unavailable; Lease operationOwner, preparingOwner; long confirmed; ScheduledFuture<?> scheduled;
        Relay(OutboxRelayProfile profile) { this.profile = profile; }
        Lease admittedOwner() { return owners.stream().filter(owner -> owner.committed && !owner.closed && owner.invoker.admissionOpen()).findFirst().orElse(null); }
        boolean admitted() { return admittedOwner() != null; }
        void schedule(Duration delay) {
            if (scheduled != null || working || owners.stream().noneMatch(owner -> owner.committed && !owner.closed)) return;
            scheduled = timer.schedule(() -> {
                synchronized (ManagedOutboxFactory.this) {
                    scheduled = null; if (!admitted()) { schedule(profile.interval()); return; }
                    working = true; operationOwner = admittedOwner();
                    try { workers.execute(this::run); }
                    catch (RejectedExecutionException overloaded) { working = false; operationOwner = null; unavailable = true; schedule(profile.interval()); ManagedOutboxFactory.this.notifyAll(); }
                }
            }, delay.toNanos(), TimeUnit.NANOSECONDS);
        }
        Connection connection() throws SQLException {
            Connection connection = profile.publication().jdbcRegistration().connections().open(); boolean owned = false;
            try {
                var metadata = connection.getMetaData();
                if (!metadata.getDatabaseProductName().equals("H2") || !metadata.getDatabaseProductVersion().startsWith("2.3.232")
                        || !metadata.getURL().startsWith("jdbc:h2:tcp://") || !connection.getAutoCommit()) throw new SQLException("Unsupported relay database profile");
                connection.setNetworkTimeout(Runnable::run, (int) Math.max(1, profile.operationBudget().toMillis()));
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); owned = true; return connection;
            } finally { if (!owned) connection.close(); }
        }
        void run() {
            try {
                synchronized (ManagedOutboxFactory.this) { operationOwner = admittedOwner(); if (operationOwner == null) return; }
                int count = profile.publication().delivery().relayOnce(this::connection, profile.destination(), profile.queue(), profile.maximumBatch(), Instant.now().plus(profile.operationBudget()));
                synchronized (ManagedOutboxFactory.this) { confirmed += count; unavailable = false; }
            } catch (Exception failure) { synchronized (ManagedOutboxFactory.this) { unavailable = true; } }
            finally { synchronized (ManagedOutboxFactory.this) { working = false; operationOwner = null; schedule(profile.interval()); ManagedOutboxFactory.this.notifyAll(); } }
        }
    }
    private final class Lease implements ManagedHostedResources.Binding {
        final Relay relay; final ManagedHostedResources.Invoker invoker;
        boolean prepared, armed, committed, closed;
        Lease(Relay relay, ManagedHostedResources.Invoker invoker) { this.relay = relay; this.invoker = invoker; }
        @Override public void prepare() throws Exception {
            synchronized (ManagedOutboxFactory.this) {
                if (closed || prepared) throw new IllegalStateException("Outbox lease unavailable");
                long started = System.nanoTime(), budget = relay.profile.operationBudget().toNanos();
                while (relay.preparing) {
                    long remaining = budget - (System.nanoTime() - started); if (remaining <= 0) throw new SQLException("Outbox preparation is still owned");
                    TimeUnit.NANOSECONDS.timedWait(ManagedOutboxFactory.this, remaining);
                    if (closed) throw new IllegalStateException("Outbox lease closed during preparation");
                }
                if (relay.prepared) { prepared = true; return; }
                relay.preparing = true; relay.preparingOwner = this;
            }
            try {
                try (Connection connection = relay.connection()) {
                    connection.setAutoCommit(false);
                    try { OutboxDefinition.ensure(connection, relay.profile); connection.commit(); }
                    catch (SQLException failed) { try { connection.rollback(); } catch (SQLException cleanup) { failed.addSuppressed(cleanup); } throw failed; }
                    connection.setAutoCommit(true); relay.profile.publication().delivery().pending(connection, 1);
                }
                synchronized (ManagedOutboxFactory.this) { relay.prepared = true; prepared = true; }
            } finally { synchronized (ManagedOutboxFactory.this) { relay.preparing = false; relay.preparingOwner = null; ManagedOutboxFactory.this.notifyAll(); } }
        }
        @Override public void start() { synchronized (ManagedOutboxFactory.this) { if (!prepared || closed || armed) throw new IllegalStateException("Outbox lease not prepared"); armed = true; } }
        @Override public void commitAdmission() { synchronized (ManagedOutboxFactory.this) { if (!armed || closed || committed) throw new IllegalStateException("Outbox lease not armed"); committed = true; relay.schedule(Duration.ZERO); } }
        @Override public void stopAdmission() {
            synchronized (ManagedOutboxFactory.this) {
                committed = false;
                if (relay.operationOwner == this) { Lease replacement = relay.admittedOwner(); if (replacement != null) relay.operationOwner = replacement; }
                if (relay.owners.stream().noneMatch(owner -> owner.committed && !owner.closed) && relay.scheduled != null) { relay.scheduled.cancel(false); relay.scheduled = null; }
            }
        }
        @Override public boolean drain(Duration budget) throws InterruptedException {
            if (budget == null || budget.isNegative() || budget.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid relay drain budget");
            stopAdmission(); long started = System.nanoTime(), nanos = budget.toNanos();
            synchronized (ManagedOutboxFactory.this) {
                while (relay.working && relay.operationOwner == this || relay.preparing && relay.preparingOwner == this) {
                    long remaining = nanos - (System.nanoTime() - started); if (remaining <= 0) return false; TimeUnit.NANOSECONDS.timedWait(ManagedOutboxFactory.this, remaining);
                }
                return true;
            }
        }
        @Override public void close() {
            synchronized (ManagedOutboxFactory.this) {
                if (closed) return; stopAdmission();
                if (relay.working && relay.operationOwner == this || relay.preparing && relay.preparingOwner == this) throw new IllegalStateException("Outbox physical owner must drain");
                closed = true; relay.owners.remove(this); leases--;
                if (relay.owners.isEmpty()) { if (relay.scheduled != null) relay.scheduled.cancel(false); relays.remove(relay.profile.publication().scope(), relay); }
            }
        }
    }
}
