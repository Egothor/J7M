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
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.annotations.EventSubscription;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.distributed.*;

/**
 * General lifecycle adapter for generated durable subscription handlers. It borrows authorized
 * endpoints and uses only the trusted hosted invoker for application work. One serial page per
 * binding and a shared bounded worker pool prevent an unbounded delivery backlog. Delivery is at
 * least once; no native exclusive claim or transaction with arbitrary application effects is claimed.
 */
public final class ManagedEventSubscriptionFactory implements ManagedHostedResources.Factory, AutoCloseable {
    /**
     * Safe lifecycle state, containing no application payload or exception text.
     */
    public enum Phase {
        /**
         * Bound but not prepared.
         */ NEW, /**
         * Prepared with no delivery admission.
         */ PREPARED,
        /**
         * Startup succeeded but global activation admission is not committed.
         */ ARMED,
        /**
         * Polling or processing.
         */ RUNNING, /**
         * Admission closed while actual work exits.
         */ DRAINING,
        /**
         * Explicit failure requiring operator attention.
         */ FAILED, /**
         * Fully released.
         */ CLOSED
    }
    /**
     * Safe fixed failure categories; unavailable source is retryable without resetting progress.
     */
    public enum Reason {
        /**
         * No outstanding failure.
         */ NONE, /**
         * Source transport/quorum outcome is uncertain.
         */ SOURCE_UNAVAILABLE,
        /**
         * Retained history no longer covers progress.
         */ CURSOR_EXPIRED,
        /**
         * Durable subscription lease expired.
         */ SUBSCRIPTION_EXPIRED,
        /**
         * Durable identity/configuration conflict.
         */ DEFINITION_CONFLICT,
        /**
         * Native bounded storage cannot accept a new identity.
         */ CAPACITY,
        /**
         * Application failed; no acknowledgement was sent.
         */ HANDLER_FAILED,
        /**
         * Source supplied a malformed or regressing page.
         */ INVALID_PAGE,
        /**
         * Authorization denied.
         */ PERMISSION_DENIED, /**
         * Shared bounded worker admission rejected.
         */ OVERLOADED,
        /**
         * Other fixed protocol rejection; no progress reset.
         */ PROTOCOL_REJECTED
    }
    /**
     * Immutable status for trusted host diagnostics.
     * @param phase actual lifecycle phase
     * @param reason fixed redacted reason
     * @param cursor last authoritatively acknowledged position, or null before initialization
     * @param working whether an actual worker still owns this binding
     */
    public record Status(Phase phase, Reason reason, EventJournal.Cursor cursor, boolean working) { }
    private final Map<String, EventSubscriptionProfile> profiles;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<Subscription> bindings = new HashSet<>();
    private boolean closed;

    /**
     * Creates an explicit bounded host adapter. Endpoints remain borrowed.
     * @param profiles at most64 named immutable source policies
     * @param maximumWorkers fixed worker count in1..16
     * @param maximumQueued bounded shared ready queue in1..64
     * @throws IllegalArgumentException for invalid profiles or capacities
     * @throws NullPointerException for null profile members
     */
    public ManagedEventSubscriptionFactory(Map<String, EventSubscriptionProfile> profiles, int maximumWorkers, int maximumQueued) {
        this.profiles = Map.copyOf(profiles);
        if (this.profiles.size() > 64 || this.profiles.keySet().stream().anyMatch(name -> !identifier(name))
                || maximumWorkers < 1 || maximumWorkers > 16 || maximumQueued < 1 || maximumQueued > 64) {
            throw new IllegalArgumentException("Invalid subscription registry capacity");
        }
        workers = new ThreadPoolExecutor(maximumWorkers, maximumWorkers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maximumQueued), threadFactory("j7m-event-worker"), new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(1, threadFactory("j7m-event-poll"));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private static ThreadFactory threadFactory(String name) {
        return task -> { Thread thread = new Thread(null, task, name, 0, false); thread.setDaemon(true);
            thread.setContextClassLoader(ManagedEventSubscriptionFactory.class.getClassLoader()); return thread; };
    }
    /**
     * {@inheritDoc}
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(EventSubscription.class); }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) {
        Objects.requireNonNull(bundle); profile(feature);
        if (!(definition instanceof ManagedHostedAdapter<?>) || feature.methods().size() != 1
                || !feature.attributes().keySet().equals(Set.of("policy", "filter"))) {
            throw new IllegalArgumentException("Unsupported subscription definition");
        }
        ManagedMethod method = feature.methods().getFirst();
        if (!method.parameterTypes().equals(List.of(ManagedEventDelivery.class.getName()))) {
            throw new IllegalArgumentException("Subscription requires a managed delivery parameter");
        }
        try {
            var actual = definition.type().getMethod(method.name(), ManagedEventDelivery.class);
            if (Modifier.isStatic(actual.getModifiers()) || !actual.getDeclaringClass().getName().equals(method.declaringType())
                    || !actual.getReturnType().getName().equals(method.returnType())) {
                throw new IllegalArgumentException("Invalid subscription callback identity");
            }
            var declared = actual.getAnnotation(EventSubscription.class);
            if (declared == null || !declared.value().equals(feature.id()) || declared.version() != feature.version()
                    || !declared.policy().equals(feature.attributes().get("policy")) || !declared.filter().equals(feature.attributes().get("filter"))) {
                throw new IllegalArgumentException("Subscription descriptor differs from actual annotation");
            }
            if (actual.getReturnType() != void.class
                    && org.j7m.middleware.transport.api.AsyncMethodDescriptor.fromMethod(actual).payloadType() != Void.class) {
                throw new IllegalArgumentException("Subscription callback must return void or CompletionStage<Void>");
            }
        } catch (NoSuchMethodException failure) { throw new IllegalArgumentException("Subscription callback is missing"); }
    }
    private EventSubscriptionProfile profile(ManagedHostedFeature feature) {
        if (!feature.kind().equals("event-subscription") || !identifier(feature.id())) throw new IllegalArgumentException("Invalid subscription feature");
        String filter = feature.attributes().get("filter");
        if (filter == null || !filter.isEmpty() && !identifier(filter)) throw new IllegalArgumentException("Invalid subscription filter");
        var profile = profiles.get(feature.attributes().get("policy"));
        if (profile == null) throw new IllegalArgumentException("Unknown subscription profile");
        return profile;
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized ManagedHostedResources.Binding bind(ManagedHostedFeature feature,
            ManagedComponentDefinition<?> definition, Object component, ManagedHostedResources.Bundle bundle,
            ManagedHostedResources.Invoker invoker) {
        validate(feature, definition, bundle); Objects.requireNonNull(component); Objects.requireNonNull(invoker);
        if (!definition.type().isInstance(component)) throw new IllegalArgumentException("Wrong subscription component type");
        if (closed || bindings.size() >= 64) throw new IllegalStateException("Subscription registry unavailable");
        var profile = profile(feature);
        if (bindings.stream().anyMatch(binding -> binding.feature.id().equals(feature.id())
                && binding.profile.sourceNamespace().equals(profile.sourceNamespace()))) {
            throw new IllegalArgumentException("Duplicate subscription identity");
        }
        var binding = new Subscription(feature, profile, invoker); bindings.add(binding); return binding;
    }
    /**
     * Returns a redacted immutable snapshot for a currently bound identity.
     * @param sourceNamespace exact host source namespace
     * @param id stable subscription identity
     * @return current status
     * @throws IllegalArgumentException if the identity is not bound
     */
    public synchronized Status status(String sourceNamespace, String id) {
        return bindings.stream().filter(binding -> binding.feature.id().equals(id)
                && binding.profile.sourceNamespace().equals(sourceNamespace)).findFirst().orElseThrow(
                        () -> new IllegalArgumentException("Unknown subscription")).status();
    }
    /**
     * Closes idle host executors after every application binding has actually drained and closed.
     * @throws IllegalStateException while an activation still owns a binding
     */
    @Override public synchronized void close() {
        if (closed) return;
        if (!bindings.isEmpty()) throw new IllegalStateException("Subscription bindings must drain before host close");
        closed = true; scheduler.shutdown(); workers.shutdown();
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{1,128}"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 unavailable", impossible); }
    }
    private enum Mutation { SUBSCRIBE, ACK, RESUME }
    private record Pending(Mutation mutation, EventJournal.Cursor cursor, String request, List<String> arguments, boolean advanceSnapshot) { }

    private final class Subscription implements ManagedHostedResources.Binding {
        private final ManagedHostedFeature feature;
        private final EventSubscriptionProfile profile;
        private final ManagedHostedResources.Invoker invoker;
        private final EventSubscriptions source;
        private final String key;
        private final String fingerprint;
        private final Object monitor = new Object();
        private Phase phase = Phase.NEW;
        private Reason reason = Reason.NONE;
        private EventJournal.Cursor cursor;
        private boolean working;
        private boolean initialized;
        private boolean forceSnapshot;
        private boolean activeManifest;
        private long manifestVersion;
        private long renewedAt;
        private Pending pending;
        private String resumeRequest;
        private ScheduledFuture<?> scheduled;

        Subscription(ManagedHostedFeature feature, EventSubscriptionProfile profile, ManagedHostedResources.Invoker invoker) {
            this.feature = feature; this.profile = profile; this.invoker = invoker; source = new EventSubscriptions(profile.source());
            key = "j7m.subscription." + digest(profile.sourceNamespace() + "\0" + feature.id());
            fingerprint = digest(feature.version() + "\0" + feature.attributes().get("filter") + "\0" + feature.methods().getFirst());
        }
        Status status() { synchronized (monitor) { return new Status(phase, reason, cursor, working); } }
        private Instant deadline() { return Instant.now().plus(profile.operationBudget()); }
        private String request(String operation) { return "subscription-" + operation + "-" + UUID.randomUUID(); }
        private void requireOk(ReplicatedResources.Result result) {
            if (!result.status().equals("OK")) throw new Rejected(reason(result.status()));
        }
        @Override public void prepare() throws IOException {
            synchronized (monitor) { if (phase != Phase.NEW) throw new IllegalStateException("Subscription already prepared"); }
            var value = profile.metadata().read(key, deadline());
            if (value == null) {
                var result = profile.metadata().execute("BATCH", "subscription-manifest-" + digest(key + fingerprint),
                        List.of(key, "0", fingerprint + ":PENDING"), deadline());
                if (!Set.of("OK", "CONFLICT").contains(result.status())) requireOk(result);
                value = profile.metadata().read(key, deadline());
            }
            installManifest(value);
            synchronized (monitor) { phase = Phase.PREPARED; }
        }
        private void installManifest(ReplicatedResources.Value value) {
            if (value == null || !Set.of(fingerprint + ":PENDING", fingerprint + ":ACTIVE").contains(value.value())) {
                throw new Rejected(Reason.DEFINITION_CONFLICT);
            }
            manifestVersion = value.version(); activeManifest = value.value().endsWith(":ACTIVE");
        }
        @Override public void start() {
            synchronized (monitor) {
                if (phase != Phase.PREPARED) throw new IllegalStateException("Subscription not prepared");
                phase = Phase.ARMED;
            }
        }
        @Override public void commitAdmission() {
            synchronized (monitor) {
                if (phase != Phase.ARMED) throw new IllegalStateException("Subscription not armed");
                phase = Phase.RUNNING; schedule(Duration.ZERO);
            }
        }
        private void schedule(Duration delay) {
            if (phase != Phase.RUNNING) return;
            scheduled = scheduler.schedule(this::submit, delay.toNanos(), TimeUnit.NANOSECONDS);
        }
        private void submit() {
            synchronized (monitor) {
                scheduled = null;
                if (phase != Phase.RUNNING || working) return;
                working = true;
                try { workers.execute(this::run); }
                catch (RejectedExecutionException overloaded) {
                    working = false; reason = Reason.OVERLOADED; schedule(profile.pollInterval()); monitor.notifyAll();
                }
            }
        }
        private void run() {
            try { step(); synchronized (monitor) { reason = Reason.NONE; } }
            catch (IOException unavailable) { synchronized (monitor) { reason = Reason.SOURCE_UNAVAILABLE; } }
            catch (SecurityException denied) { fail(Reason.PERMISSION_DENIED); }
            catch (Rejected rejected) { fail(rejected.reason); }
            catch (RuntimeException invalid) { fail(Reason.PROTOCOL_REJECTED); }
            finally {
                synchronized (monitor) {
                    working = false; monitor.notifyAll();
                    if (phase == Phase.RUNNING) schedule(profile.pollInterval());
                }
            }
        }
        private void fail(Reason failure) { synchronized (monitor) { reason = failure; if (phase == Phase.RUNNING) phase = Phase.FAILED; } }
        private boolean admitted() { synchronized (monitor) { return phase == Phase.RUNNING && invoker.admissionOpen(); } }
        private void step() throws IOException {
            if (!admitted()) return;
            if (pending != null) { flush(); return; }
            if (!initialized) { initialize(); return; }
            if (!activeManifest) { markActive(); return; }
            EventJournal.Page page;
            try { page = source.poll(feature.id(), profile.maximumPage(), deadline()); }
            catch (IOException failure) {
                Reason expiry = expiry(failure.getMessage());
                if (expiry == null) throw failure;
                expired(expiry); return;
            }
            validatePage(page);
            for (EventJournal.Event event : page.events()) {
                if (!admitted()) return;
                if (event.cursor().sequence() <= cursor.sequence()) continue;
                deliver(new ManagedEventDelivery(ManagedEventDelivery.Kind.EVENT, event.cursor(), event.operation(), event.payload()));
            }
            if (!admitted()) return;
            if (page.through().sequence() > cursor.sequence()) {
                pending = ack(page.through()); flush();
            } else if (System.nanoTime() - renewedAt >= profile.lease().toNanos() / 3) {
                var result = resumeSource();
                if (!result.status().equals("OK")) { Reason expiry = expiry(result.status()); if (expiry != null) { expired(expiry); return; } }
                requireOk(result); renewedAt = System.nanoTime();
            }
        }
        private void initialize() throws IOException {
            if (activeManifest) {
                var result = resumeSource();
                if (result.status().equals("OK")) {
                    cursor = parse(result.value()); renewedAt = System.nanoTime();
                    if (!forceSnapshot && profile.restart() == EventSubscriptionProfile.Restart.RESUME) { initialized = true; return; }
                } else {
                    Reason expiry = expiry(result.status());
                    if (expiry == null) requireOk(result);
                    else if (profile.expiry() == EventSubscriptionProfile.Expiry.FAIL) throw new Rejected(expiry);
                }
            }
            snapshot();
        }
        private ReplicatedResources.Result resumeSource() throws IOException {
            if (resumeRequest == null) resumeRequest = request("renew");
            var result = source.resume(resumeRequest, feature.id(), profile.lease().toMillis(), deadline());
            resumeRequest = null; return result;
        }
        private void snapshot() throws IOException {
            var snapshot = source.snapshotAndTail(deadline());
            if (snapshot.snapshot().length > profile.maximumSnapshotBytes()) throw new Rejected(Reason.CAPACITY);
            if (!admitted()) return;
            deliver(new ManagedEventDelivery(ManagedEventDelivery.Kind.SNAPSHOT, snapshot.cursor(), "", snapshot.snapshot()));
            if (!admitted()) return;
            pending = new Pending(Mutation.SUBSCRIBE, snapshot.cursor(), request("subscribe"), List.of(feature.id(),
                    feature.attributes().get("filter"), snapshot.cursor().partition(), Long.toString(snapshot.cursor().epoch()),
                    Long.toString(snapshot.cursor().sequence()), Long.toString(profile.lease().toMillis())), false);
            flush();
        }
        private Pending ack(EventJournal.Cursor at) {
            return new Pending(Mutation.ACK, at, "subscription-ack-" + digest(key + "\0" + at + "\0" + profile.lease().toMillis()), List.of(feature.id(), at.partition(),
                    Long.toString(at.epoch()), Long.toString(at.sequence()), Long.toString(profile.lease().toMillis())), false);
        }
        private Pending resume(EventJournal.Cursor at, boolean advanceSnapshot) {
            return new Pending(Mutation.RESUME, at, request("confirm"), List.of(feature.id(), Long.toString(profile.lease().toMillis())), advanceSnapshot);
        }
        private void flush() throws IOException {
            Pending operation = pending;
            var result = profile.source().execute(operation.mutation.name(), operation.request, operation.arguments, deadline());
            if (operation.mutation == Mutation.SUBSCRIBE && result.status().equals("SUBSCRIPTION_CONFLICT")) {
                pending = resume(operation.cursor, true); return;
            }
            Reason expiry = expiry(result.status());
            if (expiry != null) { pending = null; expired(expiry); return; }
            requireOk(result);
            if (operation.mutation != Mutation.RESUME) { pending = resume(operation.cursor, false); return; }
            var actual = parse(result.value());
            if (!actual.partition().equals(operation.cursor.partition()) || actual.epoch() < operation.cursor.epoch()) throw new Rejected(Reason.INVALID_PAGE);
            if (actual.sequence() < operation.cursor.sequence()) {
                if (!operation.advanceSnapshot) throw new Rejected(Reason.INVALID_PAGE);
                pending = ack(operation.cursor); return;
            }
            if (actual.sequence() > operation.cursor.sequence()) {
                pending = null; initialized = false; forceSnapshot = true; return;
            }
            synchronized (monitor) { cursor = operation.cursor; }
            renewedAt = System.nanoTime(); initialized = true; forceSnapshot = false; pending = null;
            if (!activeManifest) markActive();
        }
        private void markActive() throws IOException {
            var result = profile.metadata().execute("BATCH", "subscription-active-" + digest(key + fingerprint + manifestVersion),
                    List.of(key, Long.toString(manifestVersion), fingerprint + ":ACTIVE"), deadline());
            if (!Set.of("OK", "CONFLICT").contains(result.status())) requireOk(result);
            installManifest(profile.metadata().read(key, deadline()));
            if (!activeManifest) throw new Rejected(Reason.DEFINITION_CONFLICT);
        }
        private void expired(Reason expiry) throws IOException {
            if (profile.expiry() == EventSubscriptionProfile.Expiry.FAIL) throw new Rejected(expiry);
            initialized = false; forceSnapshot = true; snapshot();
        }
        private void deliver(ManagedEventDelivery delivery) {
            try {
                int inboundBytes = Math.addExact(delivery.payload().length, Math.addExact(32,
                        Math.multiplyExact(2, delivery.operation().length() + delivery.cursor().partition().length())));
                Object result = invoker.invokeDecoded(feature.methods().getFirst(), () -> new Object[]{delivery},
                        new InvocationOptions("subscription-handler-" + digest(key + "\0" + delivery.kind() + "\0" + delivery.cursor()),
                                profile.handlerBudget(), Map.of()), inboundBytes).toCompletableFuture().join();
                if (result != null) throw new Rejected(Reason.HANDLER_FAILED);
            } catch (RuntimeException failure) { throw new Rejected(Reason.HANDLER_FAILED); }
        }
        private void validatePage(EventJournal.Page page) {
            if (page.events().size() > profile.maximumPage() || !page.through().partition().equals(cursor.partition())
                    || page.through().sequence() < cursor.sequence() || page.through().epoch() < cursor.epoch()) throw new Rejected(Reason.INVALID_PAGE);
            long position = cursor.sequence(); long epoch = cursor.epoch();
            for (var event : page.events()) {
                if (event.cursor().sequence() <= cursor.sequence()) continue; // Full cursor identities make an old duplicate harmless to ack.
                if (!event.cursor().partition().equals(cursor.partition()) || event.cursor().sequence() <= position
                        || event.cursor().sequence() > page.through().sequence() || event.cursor().epoch() < epoch
                        || event.cursor().epoch() > page.through().epoch()) throw new Rejected(Reason.INVALID_PAGE);
                position = event.cursor().sequence(); epoch = event.cursor().epoch();
            }
        }
        @Override public void stopAdmission() {
            synchronized (monitor) {
                if (phase == Phase.CLOSED) return;
                phase = Phase.DRAINING;
                if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
            }
        }
        @Override public boolean drain(Duration budget) throws InterruptedException {
            Objects.requireNonNull(budget);
            if (budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid drain budget");
            long end = System.nanoTime() + budget.toNanos();
            stopAdmission();
            synchronized (monitor) {
                if (phase == Phase.CLOSED) return true;
                while (working) {
                    long left = end - System.nanoTime(); if (left <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(monitor, left);
                }
                return true;
            }
        }
        @Override public void close() {
            synchronized (ManagedEventSubscriptionFactory.this) {
                synchronized (monitor) {
                    if (phase == Phase.CLOSED) return;
                    if (working || phase == Phase.RUNNING) throw new IllegalStateException("Subscription must actually drain before close");
                    if (scheduled != null) scheduled.cancel(false);
                    phase = Phase.CLOSED; pending = null;
                }
                bindings.remove(this);
            }
        }
    }
    private static EventJournal.Cursor parse(String value) {
        try {
            int sequence = value.lastIndexOf(':'); int epoch = value.lastIndexOf(':', sequence - 1);
            return new EventJournal.Cursor(value.substring(0, epoch), Long.parseLong(value.substring(epoch + 1, sequence)), Long.parseLong(value.substring(sequence + 1)));
        } catch (RuntimeException invalid) { throw new Rejected(Reason.PROTOCOL_REJECTED); }
    }
    private static Reason expiry(String status) {
        return switch (status == null ? "" : status) {
            case "CURSOR_EXPIRED" -> Reason.CURSOR_EXPIRED; case "SUBSCRIPTION_EXPIRED" -> Reason.SUBSCRIPTION_EXPIRED; default -> null;
        };
    }
    private static Reason reason(String status) {
        return switch (status) {
            case "CAPACITY", "OVERLOADED" -> Reason.CAPACITY;
            case "CONFLICT", "SUBSCRIPTION_CONFLICT" -> Reason.DEFINITION_CONFLICT;
            case "CURSOR_EXPIRED" -> Reason.CURSOR_EXPIRED; case "SUBSCRIPTION_EXPIRED" -> Reason.SUBSCRIPTION_EXPIRED;
            default -> Reason.PROTOCOL_REJECTED;
        };
    }
    private static final class Rejected extends RuntimeException {
        private final Reason reason;
        Rejected(Reason reason) { super("Managed subscription rejected: " + reason); this.reason = reason; }
    }
}
