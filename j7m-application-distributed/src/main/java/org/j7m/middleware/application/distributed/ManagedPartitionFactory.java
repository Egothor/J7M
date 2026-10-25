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
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.annotations.PartitionHandler;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaIndex;
import org.j7m.middleware.codec.api.SchemaIndexes;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.consensus.NetworkConsensusConfig;
import org.j7m.middleware.consensus.NetworkConsensusNode;
import org.j7m.middleware.distributed.PartitionServer;
import org.j7m.middleware.distributed.PartitionStateMachine;

/**
 * Trusted finite host adapter registering real replicated encoded POJO partitions.
 * Install this factory explicitly under hosted kind partition. It does not grant applications
 * topology authority or raw native handles. Pure reducer callbacks do not enter RPC policies or
 * transactions; they must be deterministic and effect-free. Only exact program/schema/configuration
 * restarts are supported. Drain waits for actual native apply and resource work before releasing
 * the borrowed reducer or its classloader. This factory is thread-safe and owns bounded close workers.
 */
public final class ManagedPartitionFactory implements ManagedHostedResources.Factory, AutoCloseable {
    private final Map<String, ManagedPartitionProfile> profiles;
    private final Map<String, HostedPartition> bindings = new java.util.HashMap<>();
    private final ThreadPoolExecutor closers;
    private boolean closed;

    /**
     * Snapshots finite host-approved partition policies without opening storage or starting code.
     * @param profiles policy names to immutable local profiles,1..64 entries
     * @throws IllegalArgumentException for invalid names, duplicate resource directories or capacity
     */
    public ManagedPartitionFactory(final Map<String, ManagedPartitionProfile> profiles) {
        this.profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > 64) throw invalid();
        var directories = new java.util.HashSet<java.nio.file.Path>();
        for (var entry : profiles.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                    || !directories.add(entry.getValue().consensus().directory().toAbsolutePath().normalize())) throw invalid();
        }
        closers = new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), work -> {
            var thread = new Thread(work, "managed-partition-close"); thread.setDaemon(true);
            thread.setContextClassLoader(ManagedPartitionFactory.class.getClassLoader()); return thread;
        });
        closers.allowCoreThreadTimeOut(true);
    }
    /**
     * Returns the actual pure partition capability; application metadata cannot install it.
     * @return immutable annotation capability set
     */
    /**
     * Returns the pure reducer backend capability, independent of hosted invocation callbacks.
     * @return exact partition backend kind
     */
    @Override public Set<String> backendKinds() { return Set.of("partition"); }

    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(PartitionHandler.class, org.j7m.middleware.annotations.ShardKey.class,
                org.j7m.middleware.annotations.OrderedBy.class, org.j7m.middleware.annotations.DataLocality.class); }

    /**
     * Validates exact descriptor, pure component and schema types before component construction.
     * @param feature finite generated declaration
     * @param definition direct generated program
     * @param bundle verified whole-artifact identity and semantic configuration
     * @throws IllegalArgumentException for an unsupported policy, data model or impure component shape
     */
    @Override public void validate(final ManagedHostedFeature feature, final ManagedComponentDefinition<?> definition,
            final ManagedHostedResources.Bundle bundle) {
        var program = program(feature, definition); var profile = profile(feature);
        validateCallbacks(program);
        for (var dependency : program.dependencies()) {
            if (dependency.provider() || !configurationType(dependency.type()) || dependency.attributes().isEmpty()
                    || dependency.attributes().keySet().stream().anyMatch(key -> !key.startsWith("config."))
                    || !dependency.attributes().containsKey("config.key") && !dependency.attributes().containsKey("config.prefix")) throw invalid();
        }
        for (var type : hierarchy(program.type())) {
            for (var method : type.getDeclaredMethods()) {
                for (var annotation : method.getDeclaredAnnotations()) {
                    if (Set.of("Initialize", "Startup", "Drain", "Shutdown", "RemoteMethod", "LeaseGuard", "LeaderTask")
                            .contains(annotation.annotationType().getSimpleName())
                            && annotation.annotationType().getPackageName().equals("org.j7m.middleware.annotations")) throw invalid();
                }
            }
            for (var field : type.getDeclaredFields()) {
                for (var annotation : field.getDeclaredAnnotations()) {
                    if (Set.of("Inject", "ServiceRef", "J7mResource", "CurrentApplication", "CurrentPrincipal", "CurrentInvocation", "ConfigValue")
                            .contains(annotation.annotationType().getSimpleName())
                            && annotation.annotationType().getPackageName().equals("org.j7m.middleware.annotations")) throw invalid();
                }
                if (!Modifier.isStatic(field.getModifiers()) && (!Modifier.isFinal(field.getModifiers())
                        || !configurationType(field.getType()))) throw invalid();
            }
        }
        var schemas = schemas(program.type());
        ManagedPartitionResource.validateData(program, schemas);
        var engine = new PayloadFrameEngine(new DefaultValueCodec(schemas), schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
        for (var type : List.of(program.stateType(), program.commandType(), program.queryType(), program.viewType())) engine.validateDeclaredType(type);
        new PartitionRouting(program, schemas);
        fingerprint(program, profile, bundle, schemas);
    }
    /**
     * Creates one exclusive local resource lifecycle; prepare performs actual durable recovery.
     * @param feature validated generated partition declaration
     * @param definition exact generated definition
     * @param component borrowed stateless reducer
     * @param bundle host-verified artifact/configuration snapshot
     * @param invoker trusted shared activation admission guard; never called from replicated apply
     * @return owned finite lifecycle
     * @throws IllegalArgumentException for unsupported or duplicate registration
     * @throws IllegalStateException if this host factory has closed
     */
    @Override public synchronized ManagedHostedResources.Binding bind(final ManagedHostedFeature feature,
            final ManagedComponentDefinition<?> definition, final Object component,
            final ManagedHostedResources.Bundle bundle, final ManagedHostedResources.Invoker invoker) {
        if (closed) throw new IllegalStateException("Partition factory is closed");
        validate(feature, definition, bundle);
        if (bindings.size() >= 64 || bindings.containsKey(feature.id())) throw invalid();
        var program = program(feature, definition); var profile = profile(feature); var schemas = schemas(program.type());
        String fp = fingerprint(program, profile, bundle, schemas);
        String group = group(profile, fp);
        var resource = new ManagedPartitionResource(program, component, new DefaultValueCodec(schemas), schemas,
                fp, group, profile.resourceScope(), profile.maximumRequests());
        resource.bindAdmission(Objects.requireNonNull(invoker)::admissionOpen);
        var binding = new HostedPartition(feature.id(), profile, resource, group);
        bindings.put(feature.id(), binding); return binding;
    }
    /**
     * Returns finite local bound addresses for trusted host discovery and native status probes.
     * This synchronized metadata snapshot is not a readiness, ownership or quorum assertion.
     * Use an authenticated PartitionClient.status with the original deadline for authoritative
     * group/epoch/phase/logical-position evidence. Empty means not prepared or already released.
     * No node mutex, network call or application callback is entered by this method.
     * @return immutable resource ID to actual bound address map, at most64 entries
     */
    public synchronized Map<String, InetSocketAddress> addresses() {
        var result = new java.util.LinkedHashMap<String, InetSocketAddress>();
        for (var entry : bindings.entrySet()) {
            InetSocketAddress address = entry.getValue().boundAddress();
            if (address != null) result.put(entry.getKey(), address);
        }
        return Map.copyOf(result);
    }
    /**
     * Refuses to discard active resources and releases idle host workers only after all drains.
     * @throws IllegalStateException while an owned binding has not actually drained and closed
     */
    @Override public synchronized void close() {
        if (!bindings.isEmpty()) throw new IllegalStateException("Partition bindings must drain before factory close");
        closed = true; closers.shutdown();
    }
    private ManagedPartitionProfile profile(final ManagedHostedFeature feature) {
        if (!feature.kind().equals("partition") || !feature.attributes().keySet().equals(Set.of("policy"))) throw invalid();
        var profile = profiles.get(feature.attributes().get("policy"));
        if (profile == null) throw invalid(); return profile;
    }
    private static ManagedPartitionDefinition<?> program(final ManagedHostedFeature feature, final ManagedComponentDefinition<?> definition) {
        if (!(definition instanceof ManagedPartitionDefinition<?> program) || !feature.equals(program.partition())
                || !program.hostedFeatures().equals(List.of(feature)) || program.scope() != org.j7m.middleware.annotations.Scope.Kind.ACTIVATION) throw invalid();
        var annotation = program.type().getAnnotation(PartitionHandler.class);
        if (annotation == null || !annotation.resource().equals(feature.id()) || annotation.version() != feature.version()
                || !annotation.policy().equals(feature.attributes().get("policy"))) throw invalid();
        return program;
    }
    private static boolean configurationType(final Class<?> type) {
        return type.isPrimitive() && type != void.class || List.of(String.class, Boolean.class, Character.class, Byte.class,
                Short.class, Integer.class, Long.class, Float.class, Double.class, byte[].class).contains(type)
                || type.isRecord() && (type.isAnnotationPresent(org.j7m.middleware.annotations.SchemaType.class)
                        || type.isAnnotationPresent(org.j7m.middleware.annotations.ConfigProperties.class));
    }
    private static void validateCallbacks(final ManagedPartitionDefinition<?> program) {
        if (!Modifier.isPublic(program.type().getModifiers()) || Modifier.isAbstract(program.type().getModifiers())
                || program.type().isInterface() || program.type().getTypeParameters().length != 0) throw invalid();
        var expected = new java.util.HashSet<org.j7m.middleware.application.api.ManagedMethod>();
        for (String name : List.of("initial", "apply", "query")) {
            var methods = java.util.Arrays.stream(program.type().getMethods()).filter(method -> method.getName().equals(name)).toList();
            if (methods.size() != 1) throw invalid(); var method = methods.getFirst();
            Class<?>[] parameters = switch (name) {
                case "initial" -> new Class<?>[0];
                case "apply" -> new Class<?>[]{program.stateType(), program.commandType()};
                default -> new Class<?>[]{program.stateType(), program.queryType()};
            };
            if (!java.util.Arrays.equals(parameters, method.getParameterTypes()) || Modifier.isStatic(method.getModifiers())
                    || method.getTypeParameters().length != 0
                    || method.getReturnType() != (name.equals("query") ? program.viewType() : program.stateType())) throw invalid();
            expected.add(new org.j7m.middleware.application.api.ManagedMethod(method.getDeclaringClass().getName(), name,
                    java.util.Arrays.stream(parameters).map(Class::getName).toList(), method.getReturnType().getName()));
        }
        if (!Set.copyOf(program.partition().methods()).equals(expected)) throw invalid();
    }
    private static String fingerprint(final ManagedPartitionDefinition<?> program, final ManagedPartitionProfile profile,
            final ManagedHostedResources.Bundle bundle, final SchemaRegistry schemas) {
        return ManagedPartitionFingerprint.compute(bundle, program, schemas, profile.resourceScope(), profile.maximumRequests());
    }
    private static String group(final ManagedPartitionProfile profile, final String fingerprint) {
        var config = profile.consensus();
        return ManagedPartitionFingerprint.groupId(config.groupId(), fingerprint, profile.partition(), profile.initialEpoch(),
                config.initialMembers(), config.maxFrameBytes(), config.maxSnapshotBytes());
    }
    private static List<Class<?>> hierarchy(final Class<?> type) {
        var visited = new java.util.LinkedHashSet<Class<?>>(); var pending = new java.util.ArrayDeque<Class<?>>(); pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> next = pending.removeFirst();
            if (next == Object.class || !visited.add(next)) continue;
            if (visited.size() > 128) throw invalid();
            if (next.getSuperclass() != null) pending.add(next.getSuperclass()); pending.addAll(List.of(next.getInterfaces()));
        }
        return List.copyOf(visited);
    }
    private static SchemaRegistry schemas(final Class<?> type) { return GeneratedDataSchemas.registry(type); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid managed partition program or host policy"); }

    final class HostedPartition implements ManagedHostedResources.Binding {
        private final String id;
        private final ManagedPartitionProfile profile;
        private final ManagedPartitionResource resource;
        private final String group;
        private NetworkConsensusNode node;
        private PartitionServer server;
        private boolean prepared;
        private boolean preparing;
        private boolean armed;
        private volatile boolean stopped;
        private boolean released;
        private CompletableFuture<Void> draining;
        HostedPartition(final String id, final ManagedPartitionProfile profile, final ManagedPartitionResource resource, final String group) {
            this.id = id; this.profile = profile; this.resource = resource; this.group = group;
        }
        @Override public void prepare() throws Exception {
            synchronized (this) {
                if (preparing || prepared || stopped || released) throw new IllegalStateException("Invalid partition preparation state");
                preparing = true;
            }
            Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(ManagedPartitionFactory.class.getClassLoader());
                var original = profile.consensus();
                var config = new NetworkConsensusConfig(original.nodeId(), original.peers(), original.initialMembers(), original.directory(),
                        original.authenticationKey(), original.electionTimeout(), original.heartbeatInterval(), original.maxFrameBytes(),
                        original.maxSnapshotBytes(), original.tlsContext(), group);
                var partition = new PartitionStateMachine(profile.partition(), group, profile.initialEpoch(), resource, profile.authorityKey());
                node = new NetworkConsensusNode(config, partition);
                try {
                    node.start();
                    server = new PartitionServer(profile.serviceAddress(), node, partition, null, profile.principals(), profile.clock(), profile.serviceTls());
                    synchronized (this) { prepared = true; }
                } catch (Exception | Error failure) {
                    try { node.close(); } catch (java.io.IOException closing) { failure.addSuppressed(closing); }
                    node = null; throw failure;
                }
            } finally {
                try { thread.setContextClassLoader(previous); }
                finally { synchronized (this) { preparing = false; } }
            }
        }
        @Override public synchronized void start() {
            if (preparing || !prepared || armed || stopped || released) throw new IllegalStateException("Invalid partition start state"); armed = true;
        }
        @Override public synchronized void commitAdmission() {
            if (preparing || !armed || stopped || released) throw new IllegalStateException("Partition is not armed"); resource.startAdmission();
        }
        @Override public void stopAdmission() { stopped = true; resource.stopAdmission(); }
        @Override public boolean drain(final Duration budget) throws Exception {
            if (budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0) throw invalid();
            stopAdmission();
            CompletableFuture<Void> future;
            synchronized (this) {
                if (preparing) return false;
                if (draining == null) {
                    draining = new CompletableFuture<>();
                    try { closers.execute(this::finish); }
                    catch (java.util.concurrent.RejectedExecutionException overloaded) { draining = null; return false; }
                }
                future = draining;
            }
            try { future.get(budget.toNanos(), TimeUnit.NANOSECONDS); return true; }
            catch (java.util.concurrent.TimeoutException pending) { return false; }
        }
        private void finish() {
            try {
                // Never interrupt a worker that may be running a committed pure apply callback.
                if (node != null) node.close();
                resource.closeAfterNodeExit().toCompletableFuture().join();
                if (server != null) server.close();
                draining.complete(null);
            } catch (Throwable failure) { draining.completeExceptionally(new IllegalStateException("Partition drain failed")); }
        }
        @Override public void close() {
            synchronized (this) {
                if (released) return;
                if (preparing) throw new IllegalStateException("Partition recovery must actually exit before close");
                if (prepared && (draining == null || !draining.isDone() || draining.isCompletedExceptionally())) {
                    throw new IllegalStateException("Partition requires actual drain before close");
                }
                resource.closeAfterNodeExit(); released = true;
            }
            synchronized (ManagedPartitionFactory.this) { bindings.remove(id, this); }
        }
        synchronized InetSocketAddress boundAddress() { return prepared && !released ? server.address() : null; }
        synchronized InetSocketAddress address() {
            if (server == null || released) throw new IllegalStateException("Partition is not prepared"); return server.address();
        }
        String groupId() { return group; }
        synchronized NetworkConsensusNode node() { return node; }
    }
}
