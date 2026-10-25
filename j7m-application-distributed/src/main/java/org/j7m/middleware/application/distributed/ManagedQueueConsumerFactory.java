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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.TracedQueue;
import org.j7m.middleware.transport.api.*;

/**
 * Trusted reactive POJO consumer/DLQ lifecycle and its mandatory REPLAY policy. Install this same
 * instance as both hosted factory and invocation policy. SECURITY runs before every claim/replay.
 * Complete server-granted payloads consume bounded credit; no idle client claim polling occurs.
 * Native ACK follows physical success; external UNKNOWN never automatically reenters the body.
 * DLQ observers never ACK/remove/redrive retained poison records. Backends remain borrowed.
 */
public final class ManagedQueueConsumerFactory implements ManagedHostedResources.Factory, ManagedInvocationPolicy, AutoCloseable {
    /**
     * Actual lifecycle state, including physical drain rather than logical reply only.
     */
    public enum Phase {
        /**
         * Bound, no resources started.
         */ NEW, /**
         * Validated and prepared.
         */ PREPARED,
        /**
         * Armed, awaiting shared admission.
         */ ARMED, /**
         * Reactive delivery admitted.
         */ RUNNING,
        /**
         * New work stopped, actual owners may remain.
         */ DRAINING, /**
         * Explicit intervention needed.
         */ FAILED,
        /**
         * Fully drained and released.
         */ CLOSED
    }
    /**
     * Safe fixed reason categories, without application exception or payload text.
     */
    public enum Reason {
        /**
         * Healthy.
         */ NONE, /**
         * Source session unavailable; reconnect is transport-only.
         */ SOURCE_UNAVAILABLE,
        /**
         * Unresolved external outcome; body cannot be retried automatically.
         */ UNKNOWN,
        /**
         * Known durable failure, eligible for bounded native NACK/DLQ.
         */ HANDLER_FAILED,
        /**
         * Receipt or generation no longer owned.
         */ STALE,
        /**
         * Shared finite worker admission denied.
         */ OVERLOADED, /**
         * Durable identity conflicts.
         */ CONFLICT,
        /**
         * Actual source authorization denied.
         */ PERMISSION_DENIED,
        /**
         * Native bounded journal or image admission denied.
         */ CAPACITY, /**
         * Durable guard retention expired.
         */ EXPIRED
    }
    /**
     * Bounded aggregate host diagnostics.
     * @param phase actual binding state
     * @param reason fixed reason without raw exceptions
     * @param working whether an application owner remains
     * @param received complete delivery count, including safe replays
     * @param acknowledged confirmed normal queue ACK count
     */
    public record Status(Phase phase, Reason reason, boolean working, long received, long acknowledged) { }
    private final Map<String, QueueConsumerProfile> profiles;
    private final ThreadPoolExecutor workers, controls;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<Consumer> consumers = new HashSet<>();
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private boolean closed;

    /**
     * Creates a finite trusted worker host without opening sessions.
     * @param profiles at most64 named queue profiles
     * @param maximumWorkers simultaneous physical callbacks, 1..16
     * @throws IllegalArgumentException for invalid registry/capacity
     */
    public ManagedQueueConsumerFactory(Map<String, QueueConsumerProfile> profiles, int maximumWorkers) {
        TransactionPolicySupport.names(profiles); this.profiles = Map.copyOf(profiles);
        if (profiles.size() > 64 || maximumWorkers < 1 || maximumWorkers > 16) throw new IllegalArgumentException("Queue worker capacity");
        workers = pool(maximumWorkers, 64, "j7m-queue-handler"); controls = pool(2, 128, "j7m-queue-control");
        timer = new ScheduledThreadPoolExecutor(1, threads("j7m-queue-timer")); timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private static ThreadFactory threads(String name) {
        return task -> { Thread t = new Thread(null, task, name, 0, false); t.setDaemon(true); t.setContextClassLoader(ManagedQueueConsumerFactory.class.getClassLoader()); return t; };
    }
    private static ThreadPoolExecutor pool(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }
    /**
     * Requires actual installation of this exact host guard, not an application capability marker.
     * @return identity-bound mandatory invocation policy
     */
    public Set<ManagedInvocationPolicy> requiredPolicies() { return Set.of(this); }
    /**
     * Selects durable replay inside security and outside participating transactions.
     * @return fixed REPLAY phase
     */
    @Override public ManagedInvocationPolicy.Phase phase() { return ManagedInvocationPolicy.Phase.REPLAY; }
    /**
     * Declares only the two queue callback capabilities this adapter implements.
     * @return immutable exact marker set
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(DurableQueueConsumer.class, DeadLetterHandler.class); }
    private record Shape(String kind, String id, int version, String queue, String policy, Method method, Class<?> payload, ManagedPayloadCodec codec) { }
    private Shape shape(Method method, Class<?> definition) {
        var consumer = method.getAnnotation(DurableQueueConsumer.class); var dead = method.getAnnotation(DeadLetterHandler.class);
        if (consumer == null && dead == null) return null;
        if (consumer != null && dead != null || Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != 1)
            throw new IllegalArgumentException("Invalid queue callback");
        if (method.getReturnType() != void.class && AsyncMethodDescriptor.fromMethod(method).payloadType() != Void.class) throw new IllegalArgumentException("Queue callback result must be void");
        String kind = consumer == null ? "dead-letter-handler" : "queue-consumer";
        String value = consumer == null ? dead.value() : consumer.value(), queue = consumer == null ? dead.queue() : consumer.queue();
        int version = consumer == null ? dead.version() : consumer.version(); String policy = consumer == null ? dead.policy() : consumer.policy();
        String id = value.isEmpty() ? method.getDeclaringClass().getName() + '#' + method.getName() : value;
        if (id.isBlank() || id.length() > 512 || id.chars().anyMatch(Character::isISOControl) || version < 1) throw new IllegalArgumentException("Invalid logical handler identity");
        QueueConsumerProfile profile = profiles.get(policy);
        if (profile == null || !profile.queues().contains(queue)) throw new IllegalArgumentException("Unknown queue handler profile");
        var codec = profile.codec().forDefinition(definition, List.of(method.getParameterTypes()[0]));
        codec.validateDeclaredType(method.getParameterTypes()[0]);
        if (method.isAnnotationPresent(Deduplicated.class)) throw new IllegalArgumentException("Queue guard is already durable; duplicate RPC guard unsupported");
        if ((profile.strategy() == QueueConsumerProfile.Strategy.INBOX) != method.isAnnotationPresent(InboxHandler.class)
                || profile.strategy() == QueueConsumerProfile.Strategy.INBOX && !method.isAnnotationPresent(JdbcTransaction.class))
            throw new IllegalArgumentException("Inbox queue strategy requires actual inbox/JDBC entry");
        return new Shape(kind, id, version, queue, policy, method, method.getParameterTypes()[0], codec);
    }
    private static ManagedMethod identity(Method method) {
        return new ManagedMethod(method.getDeclaringClass().getName(), method.getName(), Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
    }
    /**
     * Validates a finite generated callback before constructors or native work.
     * @param feature exact hosted feature
     * @param definition generated component plan
     * @param bundle verified immutable bundle context
     * @throws IllegalArgumentException for unsupported callback, queue, profile or descriptor mismatch
     */
    @Override public void validate(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) {
        Objects.requireNonNull(bundle);
        if (!(definition instanceof ManagedHostedAdapter<?>) || feature.methods().size() != 1 || !feature.attributes().keySet().equals(Set.of("queue", "policy"))) throw new IllegalArgumentException("Invalid queue feature");
        ManagedMethod wanted = feature.methods().getFirst(); Method actual = Arrays.stream(definition.type().getMethods()).filter(method -> identity(method).equals(wanted)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Queue callback not public"));
        Shape shape = shape(actual, definition.type());
        if (shape == null || !shape.kind.equals(feature.kind()) || !shape.id.equals(feature.id()) || shape.version != feature.version()
                || !shape.queue.equals(feature.attributes().get("queue")) || !shape.policy.equals(feature.attributes().get("policy"))) throw new IllegalArgumentException("Queue descriptor differs from annotation");
    }
    /**
     * Rejects queue markers outside an actual generated hosted callback.
     * @param contract complete method identity
     * @param implementation actual concrete component
     * @param businessInterface generated hosted class or ordinary business interface
     * @throws IllegalArgumentException for exporting callback metadata as ordinary RPC
     */
    @Override public void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        Method method = ManagedPolicyMetadata.method(contract, businessInterface); Shape shape = shape(method, implementation);
        if (shape != null && (businessInterface != implementation || !contract.id().equals("hosted." + shape.kind + '.' + shape.id) || contract.version() != shape.version))
            throw new IllegalArgumentException("Queue callback requires exact hosted boundary");
    }
    /**
     * Binds the actual trusted per-invocation guard; no session is started by this method.
     * @param contract complete hosted or RPC identity
     * @param implementation concrete component
     * @param businessInterface verified business surface
     * @return owned revocable replay guard
     */
    @Override public ManagedInvocationPolicy.Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        validate(contract, implementation, businessInterface); Shape shape = shape(ManagedPolicyMetadata.method(contract, businessInterface), implementation); var revoked = new AtomicBoolean();
        return new ManagedInvocationPolicy.Binding() {
            @Override public Object invoke(InvocationContext invocation, Object[] arguments, ManagedInvocationPolicy.Proceed next) throws Exception {
                if (revoked.get()) throw new IllegalStateException("Queue policy closed");
                if (shape == null) return next.invoke();
                Ticket ticket = tickets.get(invocation.correlationId());
                if (ticket == null || !ticket.feature.methods().getFirst().equals(contract.method()) || ticket.feature.version() != contract.version()
                        || !ticket.feature.id().equals(shape.id) || !next.supportsResultFinalizers() || !ManagedInvocationLifetime.currentUserWorkOpen()
                        || invocation.security().principal().id().equals("anonymous")) throw new IllegalStateException("Actual queue delivery scope required");
                TransactionPolicySupport.deadline(invocation); ticket.guardEntered = true;
                var context = new ManagedQueueDeliveryContext(ticket.profile, shape.id, shape.version, ticket.delivery);
                next.putContext(ManagedQueueDeliveryContext.class, context);
                if (ticket.profile.strategy() == QueueConsumerProfile.Strategy.INBOX) { ticket.status = "INBOX"; return next.invoke(); }
                String request = DeliveryIdentities.digest("queue-handler", ticket.profile.namespace(), ticket.profile.tenant(), ticket.profile.source().partitionId(), shape.queue, shape.id, Integer.toString(shape.version), ticket.delivery.messageId());
                String hash = DeliveryIdentities.digest(shape.payload.getName(), Base64.getEncoder().encodeToString(ticket.message.payload()));
                Guard guard = new Guard(ticket, invocation, request, hash); next.addResultFinalizer(guard::finish);
                ExecutionResult claim = ticket.profile.deduplication().claim("queue-claim-" + request, request, hash, ticket.profile.retention().toMillis(), true, TransactionPolicySupport.deadline(invocation));
                ticket.status = nativeStatus(claim.status());
                if (claim.status().equals("COMPLETED")) return null;
                if (claim.status().equals("ERROR")) throw TransactionPolicySupport.failure(MiddlewareErrorCode.INVALID_ARGUMENT);
                if (!claim.status().equals("EXTERNAL_IN_PROGRESS") || !"true".equals(claim.fields().get("acquired"))) throw TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE);
                guard.fence = Long.parseLong(claim.fields().get("fence")); if (guard.fence <= 0) throw new IllegalStateException("Invalid queue fence");
                guard.acquired = true; guard.context = new ManagedDeduplicationContext(request, guard.fence, invocation.control());
                next.putContext(ManagedDeduplicationContext.class, guard.context); TransactionPolicySupport.deadline(invocation);
                return next.invoke();
            }
            @Override public void close() { revoked.set(true); }
        };
    }
    private static String nativeStatus(String status) { return Set.of("COMPLETED", "ERROR", "CONFLICT", "EXPIRED", "OVERLOADED", "EXTERNAL_IN_PROGRESS").contains(status) ? status : "UNKNOWN"; }
    private static final Set<MiddlewareErrorCode> KNOWN = Set.of(MiddlewareErrorCode.INVALID_ARGUMENT, MiddlewareErrorCode.NOT_FOUND, MiddlewareErrorCode.PERMISSION_DENIED, MiddlewareErrorCode.UNAUTHENTICATED);
    private static final class Ticket {
        final QueueConsumerProfile profile; final ManagedHostedFeature feature; final QueueDelivery delivery; final TracedQueue.Message message;
        volatile String status = "UNKNOWN"; volatile boolean guardEntered, decodeFailed;
        Ticket(QueueConsumerProfile profile, ManagedHostedFeature feature, QueueDelivery delivery, TracedQueue.Message message) { this.profile = profile; this.feature = feature; this.delivery = delivery; this.message = message; }
    }
    private static final class Guard {
        final Ticket ticket; final InvocationContext invocation; final String request, hash;
        long fence; boolean acquired; ManagedDeduplicationContext context;
        Guard(Ticket ticket, InvocationContext invocation, String request, String hash) { this.ticket = ticket; this.invocation = invocation; this.request = request; this.hash = hash; }
        CompletionStage<InvocationResult> finish(InvocationResult result) {
            if (!acquired) return CompletableFuture.completedFuture(new InvocationResult(result.value(), result.failure(), Map.of("status", ticket.status.equals("EXTERNAL_IN_PROGRESS") ? "UNKNOWN" : ticket.status)));
            try {
                List<String> effects = context.sealAndSnapshot(); Throwable failure = result.failure();
                if (failure != null && (failure.getClass() != MiddlewareException.class || !KNOWN.contains(((MiddlewareException) failure).code()))) return unknown();
                Instant deadline = TransactionPolicySupport.deadline(invocation);
                String error = failure == null ? "" : ((MiddlewareException) failure).code().name();
                ExecutionResult saved = ticket.profile.deduplication().complete("queue-complete-" + request, request, hash, fence, "", error, failure == null ? effects : List.of(), deadline);
                ticket.status = nativeStatus(saved.status());
                if (!ticket.status.equals(failure == null ? "COMPLETED" : "ERROR")) return unknown();
                return CompletableFuture.completedFuture(new InvocationResult(null, failure, Map.of("status", ticket.status)));
            } catch (Exception uncertain) { return unknown(); }
        }
        private CompletionStage<InvocationResult> unknown() {
            ticket.status = "UNKNOWN";
            try { ticket.profile.deduplication().unknown("queue-unknown-" + request, request, hash, fence, Instant.now().plus(ticket.profile.operationBudget())); }
            catch (Exception unresolved) {
                // A failed best-effort marker cannot establish an external outcome or authorize replay.
                return CompletableFuture.completedFuture(TransactionPolicySupport.unknown("UNKNOWN"));
            }
            return CompletableFuture.completedFuture(TransactionPolicySupport.unknown("UNKNOWN"));
        }
    }
    /**
     * Creates a prepared lifecycle using only the actual trusted Invoker for application work.
     * @param feature validated finite descriptor
     * @param definition generated component
     * @param component activation-owned instance, never closed by this factory
     * @param bundle verified immutable bundle
     * @param invoker actual host policy and physical completion boundary
     * @return owned lifecycle; start/commitAdmission control external delivery
     */
    @Override public synchronized ManagedHostedResources.Binding bind(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition,
            Object component, ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) {
        validate(feature, definition, bundle);
        if (!definition.type().isInstance(component) || closed || consumers.size() >= 64) throw new IllegalStateException("Queue binding capacity");
        QueueConsumerProfile profile = profiles.get(feature.attributes().get("policy"));
        if (consumers.stream().anyMatch(c -> c.profile.namespace().equals(profile.namespace()) && c.profile.tenant().equals(profile.tenant()) && c.feature.id().equals(feature.id()) && c.feature.kind().equals(feature.kind()) && c.feature.attributes().get("queue").equals(feature.attributes().get("queue")))) throw new IllegalArgumentException("Duplicate queue handler identity");
        var consumer = new Consumer(feature, profile, Objects.requireNonNull(invoker), definition.type()); consumers.add(consumer); return consumer;
    }
    /**
     * Reads safe local status without queue queries or message labels.
     * @param namespace exact host logical namespace
     * @param handler stable feature identity
     * @return immutable status
     * @throws IllegalArgumentException if unbound
     */
    public synchronized Status status(String namespace, String handler) {
        var matches = consumers.stream().filter(c -> c.profile.namespace().equals(namespace) && c.feature.id().equals(handler)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous queue handler"); return matches.getFirst().status();
    }
    /**
     * Reads one exact tenant/queue-scoped logical handler status without network I/O.
     * @param namespace host logical namespace
     * @param tenant exact source tenant
     * @param queue exact source queue
     * @param handler stable feature identity
     * @return safe local status
     * @throws IllegalArgumentException for an unknown or ambiguous kind binding
     */
    public synchronized Status status(String namespace, String tenant, String queue, String handler) {
        var matches = consumers.stream().filter(c -> c.profile.namespace().equals(namespace) && c.profile.tenant().equals(tenant)
                && c.feature.id().equals(handler) && c.feature.attributes().get("queue").equals(queue)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous queue handler"); return matches.getFirst().status();
    }
    /**
     * Closes shared host executors only after all actual application bindings were drained/released.
     * @throws IllegalStateException if any binding remains owned
     */
    @Override public synchronized void close() {
        if (closed) return; if (!consumers.isEmpty()) throw new IllegalStateException("Queue bindings must drain before host close");
        closed = true; timer.shutdown(); workers.shutdown(); controls.shutdown();
    }
    private final class Consumer implements ManagedHostedResources.Binding, Flow.Subscriber<QueueDelivery> {
        final ManagedHostedFeature feature; final QueueConsumerProfile profile; final ManagedHostedResources.Invoker invoker; final Class<?> payload; final ManagedPayloadCodec codec;
        final Object lock = new Object();
        Phase phase = Phase.NEW; Reason reason = Reason.NONE; boolean working, connecting; int activeControls; long received, acknowledged;
        QueueSession session; Flow.Subscription subscription; ScheduledFuture<?> reconnect, renewal;
        Consumer(ManagedHostedFeature feature, QueueConsumerProfile profile, ManagedHostedResources.Invoker invoker, Class<?> type) {
            this.feature = feature; this.profile = profile; this.invoker = invoker;
            var method = Arrays.stream(type.getMethods()).filter(m -> identity(m).equals(feature.methods().getFirst())).findFirst().orElseThrow();
            var shape = shape(method, type); payload = shape.payload; codec = shape.codec;
        }
        Status status() { synchronized (lock) { return new Status(phase, reason, working, received, acknowledged); } }
        Instant deadline() { return Instant.now().plus(profile.operationBudget()); }
        boolean observational() { return feature.kind().equals("dead-letter-handler"); }
        @Override public void prepare() { synchronized (lock) { if (phase != Phase.NEW) throw new IllegalStateException("Queue already prepared"); phase = Phase.PREPARED; } }
        @Override public void start() { synchronized (lock) { if (phase != Phase.PREPARED) throw new IllegalStateException("Queue not prepared"); phase = Phase.ARMED; } }
        @Override public void commitAdmission() { synchronized (lock) { if (phase != Phase.ARMED) throw new IllegalStateException("Queue not armed"); phase = Phase.RUNNING; schedule(Duration.ZERO); } }
        void schedule(Duration delay) {
            if (phase != Phase.RUNNING || connecting || reconnect != null) return;
            reconnect = timer.schedule(() -> { synchronized (lock) {
                reconnect = null; if (phase != Phase.RUNNING) return;
                if (!invoker.admissionOpen()) { schedule(Duration.ofMillis(1)); return; }
                connecting = true;
                try { controls.execute(this::connect); } catch (RejectedExecutionException overloaded) { connecting = false; reason = Reason.OVERLOADED; schedule(Duration.ofMillis(100)); lock.notifyAll(); }
            } }, delay.toNanos(), TimeUnit.NANOSECONDS);
        }
        void connect() {
            QueueSession opened = null;
            try {
                String queue = feature.attributes().get("queue");
                opened = observational() ? profile.source().observeDeadLetters(queue, profile.member(), deadline())
                        : profile.source().subscribe(queue, profile.group(), profile.member(), profile.visibility().toMillis(), deadline());
                synchronized (lock) {
                    if (phase != Phase.RUNNING) { opened.close(); return; }
                    session = opened; reason = Reason.NONE; opened.subscribe(this);
                }
            } catch (SecurityException denied) { fail(Reason.PERMISSION_DENIED); }
            catch (IOException | RuntimeException unavailable) { synchronized (lock) { reason = Reason.SOURCE_UNAVAILABLE; } }
            finally { synchronized (lock) {
                connecting = false;
                if (opened == null || opened.isClosed()) schedule(Duration.ofMillis(100));
                lock.notifyAll();
            } }
        }
        @Override public void onSubscribe(Flow.Subscription value) {
            synchronized (lock) { subscription = value; if (phase == Phase.RUNNING && !working) value.request(1); else value.cancel(); }
        }
        @Override public void onNext(QueueDelivery delivery) {
            synchronized (lock) {
                if (phase != Phase.RUNNING) return;
                if (working || delivery.observational() != observational() || !delivery.queue().equals(feature.attributes().get("queue"))) { fail(Reason.UNKNOWN); return; }
                working = true; received++;
                try { workers.execute(() -> handle(delivery)); }
                catch (RejectedExecutionException overloaded) { working = false; fail(Reason.OVERLOADED); lock.notifyAll(); }
            }
        }
        @Override public void onError(Throwable failure) {
            synchronized (lock) { session = null; subscription = null; if (phase == Phase.RUNNING) { reason = Reason.SOURCE_UNAVAILABLE; if (!working) schedule(Duration.ofMillis(100)); } }
        }
        @Override public void onComplete() { onError(new IOException("Source session closed")); }
        void fail(Reason failure) {
            synchronized (lock) { reason = failure; if (phase == Phase.RUNNING) phase = Phase.FAILED; if (session != null) session.close(); session = null; subscription = null; }
        }
        private void handle(QueueDelivery delivery) {
            Ticket ticket = null; String ticketId = UUID.randomUUID().toString();
            try {
                synchronized (lock) { if (phase != Phase.RUNNING) return; }
                if (!observational()) {
                    String renewed = profile.source().renewVisibility(UUID.randomUUID().toString(), delivery, profile.visibility().toMillis(), deadline());
                    if (!renewed.startsWith("RENEWED")) { fail(disposition(renewed)); return; }
                }
                TracedQueue.Message message;
                try { message = TracedQueue.decode(delivery.payload()); }
                catch (IOException | RuntimeException poison) { reject(delivery); return; }
                ticket = new Ticket(profile, feature, delivery, message);
                if (tickets.size() >= 64 || tickets.putIfAbsent(ticketId, ticket) != null) { fail(Reason.OVERLOADED); return; }
                long started = System.nanoTime();
                if (!observational()) renew(delivery, started);
                boolean success;
                try (var trace = InvocationTraceContext.open(message.context().asMap())) {
                    try {
                        Ticket ownedTicket = ticket;
                        awaitPhysical(invoker.invokeDecoded(feature.methods().getFirst(), () -> {
                            try { return new Object[]{codec.decode(message.payload(), payload)}; }
                            catch (RuntimeException invalid) { ownedTicket.decodeFailed = true; throw invalid; }
                        }, new InvocationOptions(ticketId, profile.handlerBudget(), message.context().asMap()), delivery.payload().length)); success = true;
                    } catch (ExecutionException failed) {
                        // Only the privately established guard status below can authorize ACK/NACK.
                        success = false;
                    }
                }
                if (!ticket.guardEntered) { if (ticket.decodeFailed) reject(delivery); else fail(Reason.UNKNOWN); return; }
                if (success && (ticket.status.equals("COMPLETED") || ticket.status.equals("INBOX"))) {
                    if (!observational()) acknowledge(delivery);
                } else if (ticket.status.equals("ERROR") || ticket.profile.strategy() == QueueConsumerProfile.Strategy.INBOX) reject(delivery);
                else fail(ticket.status.equals("CONFLICT") ? Reason.CONFLICT : ticket.status.equals("EXPIRED") ? Reason.EXPIRED : ticket.status.equals("OVERLOADED") ? Reason.CAPACITY : Reason.UNKNOWN);
            } catch (IOException protocolUncertain) { unavailable(); }
            catch (RuntimeException unknown) { fail(Reason.UNKNOWN); }
            finally {
                tickets.remove(ticketId);
                synchronized (lock) {
                    if (renewal != null) renewal.cancel(false); renewal = null;
                    working = false;
                    if (phase == Phase.RUNNING) { if (session == null || session.isClosed()) schedule(Duration.ofMillis(100)); else if (subscription != null) subscription.request(1); }
                    lock.notifyAll();
                }
            }
        }
        private Reason disposition(String result) {
            return result.equals("OVERLOADED") ? Reason.CAPACITY : result.startsWith("STALE") ? Reason.STALE : Reason.UNKNOWN;
        }
        private void unavailable() {
            synchronized (lock) {
                reason = Reason.SOURCE_UNAVAILABLE;
                if (session != null) session.close(); session = null; subscription = null;
            }
        }
        private void awaitPhysical(CompletionStage<Object> stage) throws ExecutionException {
            boolean interrupted = false; CompletableFuture<Object> physical = stage.toCompletableFuture();
            try {
                for (;;) try { physical.get(); return; }
                catch (InterruptedException stopped) { interrupted = true; }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        private void acknowledge(QueueDelivery delivery) throws IOException {
            String request = "queue-ack-" + DeliveryIdentities.digest(profile.tenant(), delivery.queue(), delivery.group(), delivery.member(), Long.toString(delivery.generation()), delivery.messageId(), Long.toString(delivery.receipt()));
            String result = profile.source().ack(request, delivery.queue(), delivery.group(), delivery.member(), delivery.generation(), delivery.messageId(), delivery.receipt(), deadline());
            if (!result.equals("ACKED")) { fail(disposition(result)); return; }
            synchronized (lock) { acknowledged++; reason = Reason.NONE; }
        }
        private void reject(QueueDelivery delivery) throws IOException {
            if (observational()) { fail(Reason.HANDLER_FAILED); return; }
            String request = "queue-nack-" + DeliveryIdentities.digest(profile.tenant(), delivery.queue(), delivery.messageId(), delivery.group(), delivery.member(), Long.toString(delivery.generation()), Long.toString(delivery.receipt()));
            String result = profile.source().nack(request, delivery.queue(), delivery.group(), delivery.member(), delivery.generation(), delivery.messageId(), delivery.receipt(), deadline());
            if (!result.equals("NACKED")) fail(disposition(result)); else synchronized (lock) { reason = Reason.HANDLER_FAILED; }
        }
        private void renew(QueueDelivery delivery, long started) {
            synchronized (lock) {
                renewal = timer.schedule(() -> {
                    synchronized (lock) { if (!working || phase != Phase.RUNNING) return; }
                    long remaining = profile.handlerBudget().toNanos() - (System.nanoTime() - started); if (remaining <= 0) return;
                    synchronized (lock) { if (!working || phase != Phase.RUNNING) return; activeControls++; }
                    try { controls.execute(() -> {
                        try {
                            long actualRemaining = profile.handlerBudget().toNanos() - (System.nanoTime() - started);
                            if (actualRemaining <= 0) return;
                            long budget = Math.min(actualRemaining, profile.operationBudget().toNanos());
                            String result = profile.source().renewVisibility(UUID.randomUUID().toString(), delivery, profile.visibility().toMillis(), Instant.now().plusNanos(budget));
                            if (!result.startsWith("RENEWED")) fail(disposition(result)); else renew(delivery, started);
                        } catch (IOException | RuntimeException uncertain) { fail(Reason.UNKNOWN); }
                        finally { synchronized (lock) { activeControls--; lock.notifyAll(); } }
                    }); } catch (RejectedExecutionException overloaded) { synchronized (lock) { activeControls--; lock.notifyAll(); } fail(Reason.OVERLOADED); }
                }, profile.visibility().toNanos() / 3, TimeUnit.NANOSECONDS);
            }
        }
        @Override public void stopAdmission() {
            synchronized (lock) {
                if (phase == Phase.CLOSED) return; phase = Phase.DRAINING;
                if (reconnect != null) reconnect.cancel(false); reconnect = null;
                if (renewal != null) renewal.cancel(false); renewal = null;
                if (session != null) session.close(); session = null; subscription = null;
            }
        }
        @Override public boolean drain(Duration budget) throws InterruptedException {
            if (budget == null || budget.isNegative() || budget.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid queue drain budget");
            stopAdmission(); long started = System.nanoTime(), nanos = budget.toNanos();
            synchronized (lock) {
                while (working || connecting || activeControls > 0) { long remaining = nanos - (System.nanoTime() - started); if (remaining <= 0) return false; TimeUnit.NANOSECONDS.timedWait(lock, remaining); }
                return true;
            }
        }
        @Override public void close() {
            synchronized (lock) { if (phase == Phase.CLOSED) return; if (working || connecting || activeControls > 0) throw new IllegalStateException("Queue binding must physically drain"); stopAdmission(); phase = Phase.CLOSED; }
            synchronized (ManagedQueueConsumerFactory.this) { consumers.remove(this); }
        }
    }
}
