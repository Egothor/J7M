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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.api.InvocationTraceContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.transport.api.InvocationCompletionScope;
import org.j7m.middleware.transport.api.InvocationResult;
import org.j7m.middleware.transport.api.InvocationResultFinalizer;

/**
 * Trusted host ownership for one invocation, independent of its caller's reply future.
 * One serial actor shares CALL values across explicitly submitted work and codec continuations.
 * Arbitrary cancelled application stages retain ownership because cancellation is not exit proof.
 * No application-supplied future marker is consulted. Hidden work on arbitrary executors is outside
 * the tracked-work contract; applications should use the supplied invocation control instead.
 */
public final class ManagedInvocationLifetime implements InvocationCompletionScope {
    private static final ThreadLocal<ManagedInvocationLifetime> CURRENT = new ThreadLocal<>();
    private final ManagedContainer.CallScope callScope;
    private final AutoCloseable retention;
    private final Executor executor;
    private final int maximumTasks;
    private final int maximumPending;
    private final long started;
    private final java.util.function.LongSupplier nanoTime;
    private final long budgetNanos;
    private final SecurityContext security;
    private final InvocationContext context;
    private final Map<String, String> rootTrace;
    private final InvocationControl control = new Control();
    private final Runnable released;
    private final CompletableFuture<Object> logical = new CompletableFuture<>();
    private final CompletionStage<Object> logicalView = logical.minimalCompletionStage();
    private final CompletableFuture<Void> physical = new CompletableFuture<>();
    private final CompletionStage<Void> physicalView = physical.minimalCompletionStage();
    private final ArrayDeque<Task<?>> queue = new ArrayDeque<>();
    private final Map<CompletionStage<?>, Task<?>> ownedStages = new IdentityHashMap<>();
    private final ArrayDeque<RegisteredFinalizer> finalizers = new ArrayDeque<>();
    private final Map<Class<?>, Object> attachments = new java.util.HashMap<>();
    private boolean applicationEntered;
    private boolean rawObserved;
    private InvocationResult rawResult;
    private InvocationResult finalizedResult;
    private MiddlewareException unhandledExecutorFailure;
    private boolean userSealed;
    private boolean finalizersDone;
    private boolean waitingFinalizer;
    private long finalizerGeneration;
    private boolean rootEntered;
    private boolean rootExited;
    private boolean boundaryExited;
    private boolean resultProof;
    private boolean uncertainResultObservation;
    private boolean cancelled;
    private boolean scheduled;
    private boolean releasing;
    private int tasks;
    private int submitted;
    private int contextEntries;

    /**
     * Creates a retained invocation; the executor must enforce host-wide finite admission.
     * @param callScope newly opened call scope owned by this lifetime
     * @param context safe host-created invocation context; its control is replaced by this tracker
     * @param security actual authenticated security view, nullable for anonymous dispatch
     * @param budget original remaining monotonic budget, strictly positive and at most one day
     * @param executor borrowed bounded host executor, never closed by a lifetime
     * @param maximumTasks finite total child/continuation admissions for one invocation
     * @param maximumPending finite queued child/continuation admissions
     * @param released host callback after actual call disposal, invoked once even on disposal failure
     */
    public ManagedInvocationLifetime(final ManagedContainer.CallScope callScope, final InvocationContext context,
            final SecurityContext security, final Duration budget, final Executor executor,
            final int maximumTasks, final int maximumPending, final Runnable released) {
        this(callScope, context, security, budget, executor, maximumTasks, maximumPending, released, System::nanoTime);
    }

    ManagedInvocationLifetime(final ManagedContainer.CallScope callScope, final InvocationContext context,
            final SecurityContext security, final Duration budget, final Executor executor,
            final int maximumTasks, final int maximumPending, final Runnable released, final java.util.function.LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.started = nanoTime.getAsLong();
        this.callScope = Objects.requireNonNull(callScope, "callScope");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofDays(1)) > 0
                || maximumTasks < 1 || maximumTasks > 65536 || maximumPending < 1 || maximumPending > maximumTasks) {
            throw new IllegalArgumentException("INVALID_INVOCATION_CAPACITY");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
        this.released = Objects.requireNonNull(released, "released");
        this.maximumTasks = maximumTasks;
        this.maximumPending = maximumPending;
        this.budgetNanos = budget.toNanos();
        this.security = security;
        this.rootTrace = Map.copyOf(InvocationTraceContext.current());
        this.context = new InvocationContext(context.security(), context.application(), context.correlationId(), control);
        this.retention = callScope.retain();
    }

    static ManagedInvocationLifetime current() { return CURRENT.get(); }

    /**
     * Reports whether the host installed a physical boundary on this thread.
     * This observation exposes no mutable completion scope to application code.
     * @return true inside host-owned root/worker/codec context
     */
    public static boolean active() { return CURRENT.get() != null; }

    /**
     * Returns a read-only physical completion view for trusted host pipeline integration.
     * Neither this view nor the application control exposes boundary exit or disposal authority.
     * @return read-only completion, or null outside an owned context
     */
    public static CompletionStage<Void> currentPhysicalCompletion() {
        final ManagedInvocationLifetime lifetime = CURRENT.get();
        return lifetime == null ? null : lifetime.physicalCompletion();
    }

    Duration remainingBudget() {
        final long elapsed = nanoTime.getAsLong() - started;
        return Duration.ofNanos(Math.max(0L, budgetNanos - Math.max(0L, elapsed)));
    }

    synchronized boolean cancellationRequested() { return cancelled || remainingBudget().isZero(); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void requestCancellation() { cancelled = true; }
    /**
     * {@inheritDoc}
     */
    @Override public CompletionStage<Void> physicalCompletion() { return physicalView; }
    /**
     * {@inheritDoc}
     */
    @Override public CompletionStage<Object> logicalResult() { return logicalView; }

    /**
     * {@inheritDoc}
     */
    @Override public <T> T call(final Callable<T> action) throws Exception {
        Objects.requireNonNull(action, "action");
        MiddlewareException beforeEntry = null;
        synchronized (this) {
            if (rootEntered || rootExited || boundaryExited) { throw new IllegalStateException("INVOCATION_ROOT_CLOSED"); }
            if (cancelled || remainingBudget().isZero()) {
                rootExited = true; resultProof = true; rawObserved = true;
                beforeEntry = MiddlewareException.of(cancelled ? MiddlewareErrorCode.CANCELLED : MiddlewareErrorCode.DEADLINE_EXCEEDED,
                        "Managed invocation was cancelled before entry");
                rawResult = InvocationResult.failure(beforeEntry);
            } else { rootEntered = true; }
        }
        if (beforeEntry != null) { finish(); throw beforeEntry; }
        try {
            final T result;
            try { result = inContext(action); }
            catch (Exception | Error failure) {
                synchronized (this) {
                    resultProof = !uncertainResultObservation; rawObserved = true;
                    rawResult = InvocationResult.failure(failure);
                }
                if (uncertainResultObservation) { logical.completeExceptionally(normalize(failure)); }
                throw failure;
            }
            try { inContext(() -> { observe(result); return null; }); }
            catch (Exception | Error registrationFailure) {
                synchronized (this) { resultProof = false; uncertainResultObservation = true; }
                logical.completeExceptionally(normalize(registrationFailure));
                throw registrationFailure;
            }
            return result;
        } finally {
            synchronized (this) { rootExited = true; }
            schedule(); finish();
        }
    }

    private void observe(final Object result) {
        if (result instanceof CompletionStage<?> stage) {
            stage.whenComplete((value, failure) -> {
                final boolean unknownCancellation;
                synchronized (this) {
                    final Task<?> task = ownedStages.get(stage);
                    unknownCancellation = task == null && cancelledStage(failure);
                    if (task == null) { resultProof = !unknownCancellation; }
                    else { task.rootResult = true; resultProof = task.exited; }
                    rawObserved = true;
                    rawResult = failure == null ? InvocationResult.success(value) : InvocationResult.failure(failure);
                }
                if (unknownCancellation) { logical.completeExceptionally(normalize(failure)); }
                finish();
            });
        } else {
            synchronized (this) { resultProof = true; rawObserved = true; rawResult = InvocationResult.success(result); }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean supportsResultFinalizers() { return true; }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void addResultFinalizer(final InvocationResultFinalizer finalizer) {
        registrationOpen(); Objects.requireNonNull(finalizer, "finalizer");
        if (finalizers.size() >= 64) { throw new RejectedExecutionException("INVOCATION_FINALIZER_CAPACITY"); }
        finalizers.addFirst(new RegisteredFinalizer(finalizer, Map.copyOf(InvocationTraceContext.current())));
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized <T> void putContext(final Class<T> type, final T value) {
        registrationOpen(); Objects.requireNonNull(type, "type"); Objects.requireNonNull(value, "value");
        if (!type.isInstance(value)) { throw new IllegalArgumentException("INVOCATION_CONTEXT_TYPE"); }
        final Object previous = attachments.get(type);
        if (previous != null && previous != value) { throw new IllegalStateException("INVOCATION_CONTEXT_CONFLICT"); }
        if (previous == null && attachments.size() >= 64) { throw new RejectedExecutionException("INVOCATION_CONTEXT_CAPACITY"); }
        attachments.put(type, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized <T> T context(final Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (CURRENT.get() != this || releasing || !attachments.containsKey(type)) {
            throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE");
        }
        return type.cast(attachments.get(type));
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized <T> java.util.Optional<T> findContext(final Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (CURRENT.get() != this || releasing) { throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE"); }
        return java.util.Optional.ofNullable(type.cast(attachments.get(type)));
    }

    /**
     * Reads an exact host-installed attachment inside the current serial CALL context.
     * This read-only bridge supports existing named resource providers without exposing installation
     * or completion authority to application code.
     * @param type exact trusted attachment key
     * @param <T> attachment type
     * @return current invocation's attachment
     * @throws IllegalStateException outside owned context or when the key is absent
     * @throws NullPointerException for a null type
     */
    public static <T> T currentContext(final Class<T> type) {
        Objects.requireNonNull(type, "type");
        final ManagedInvocationLifetime lifetime = CURRENT.get();
        if (lifetime == null) { throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE"); }
        return lifetime.context(type);
    }

    /**
     * Reports whether the current owned context still admits application work and staged effects.
     * Host finalizers and codecs may read attachments after settlement but cannot add USER work.
     * This observation grants neither context installation nor physical-completion authority.
     * @return false outside an owned context or after the atomic USER seal
     */
    public static boolean currentUserWorkOpen() {
        final ManagedInvocationLifetime lifetime = CURRENT.get();
        if (lifetime == null) { return false; }
        synchronized (lifetime) { return !lifetime.userSealed && !lifetime.releasing; }
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void markApplicationEntry() {
        if (applicationEntered || rootExited || userSealed) { throw new IllegalStateException("APPLICATION_ENTRY_CLOSED"); }
        applicationEntered = true;
    }

    private void registrationOpen() {
        if (applicationEntered || rootExited || userSealed || boundaryExited || releasing) {
            throw new IllegalStateException("INVOCATION_REGISTRATION_CLOSED");
        }
    }

    void linkResultWrapper(final CompletionStage<?> wrapper, final CompletionStage<?> source) {
        synchronized (this) {
            final Task<?> owned = ownedStages.get(source);
            if (owned == null) { return; }
            final Task<?> previous = ownedStages.get(wrapper);
            if (previous != null && previous != owned) { throw new IllegalStateException("CONFLICTING_RESULT_PROOF"); }
            if (ownedStages.size() >= maximumTasks + 64) { throw new RejectedExecutionException("INVOCATION_RESULT_CAPACITY"); }
            ownedStages.put(wrapper, owned);
        }
    }

    void resultObservationFailed(final CompletionStage<?> source) {
        synchronized (this) {
            if (!ownedStages.containsKey(source)) { uncertainResultObservation = true; resultProof = false; }
        }
    }

    static boolean cancelledStage(final Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof CancellationException) { return true; }
            // Only JDK wrapper classes may be unwrapped here. A custom Throwable.getCause()
            // override is application code and must not run in this host-only callback.
            if (current.getClass() != java.util.concurrent.CompletionException.class
                    && current.getClass() != java.util.concurrent.ExecutionException.class) { return false; }
            current = current.getCause();
        }
        return false;
    }

    <T> CompletionStage<T> submit(final Callable<T> work) { return enqueue(work, true); }
    Executor contextualExecutor() {
        synchronized (this) { accepting(); }
        return command -> {
            Objects.requireNonNull(command, "command");
            enqueue(() -> { command.run(); return null; }, true, true);
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override public <T> CompletionStage<T> continueInContext(final Callable<T> action) {
        Objects.requireNonNull(action, "action");
        synchronized (this) { if (boundaryExited) { throw new IllegalStateException("INVOCATION_BOUNDARY_CLOSED"); } }
        if (CURRENT.get() == this) {
            final CompletableFuture<T> result = new CompletableFuture<>();
            synchronized (this) { accepting(); }
            try { result.complete(inContext(action, Map.copyOf(InvocationTraceContext.current()))); }
            catch (Exception | Error failure) { result.completeExceptionally(failure); }
            finally { finish(); }
            return result.minimalCompletionStage();
        }
        return enqueue(action, false);
    }

    private <T> CompletionStage<T> enqueue(final Callable<T> work, final boolean child) { return enqueue(work, child, false); }

    private <T> CompletionStage<T> enqueue(final Callable<T> work, final boolean child, final boolean unhandled) {
        Objects.requireNonNull(work, "work");
        final Task<T> task = new Task<>(work, child, null, Map.copyOf(InvocationTraceContext.current()), unhandled);
        synchronized (this) {
            accepting();
            if (child && userSealed) { throw new IllegalStateException("INVOCATION_USER_WORK_SEALED"); }
            if (submitted >= maximumTasks || queue.size() >= maximumPending) {
                throw new RejectedExecutionException("INVOCATION_TASK_CAPACITY");
            }
            submitted++; tasks++;
            queue.addLast(task); ownedStages.put(task.view, task);
        }
        schedule();
        return task.view;
    }

    private void accepting() {
        if (releasing || (boundaryExited && CURRENT.get() != this)) { throw new IllegalStateException("INVOCATION_BOUNDARY_CLOSED"); }
    }

    private void schedule() {
        synchronized (this) {
            if (!rootExited || scheduled || queue.isEmpty()) { return; }
            scheduled = true;
        }
        try { executor.execute(this::drain); }
        catch (RejectedExecutionException failure) {
            final ArrayDeque<Task<?>> rejected;
            synchronized (this) { rejected = new ArrayDeque<>(queue); queue.clear(); scheduled = false; }
            for (Task<?> task : rejected) { reject(task, failure); }
            finish();
        }
    }

    private void reject(final Task<?> task, final RejectedExecutionException failure) {
        recordUnhandled(task, failure);
        task.result.completeExceptionally(failure);
        synchronized (this) { task.exited = true; tasks--; if (task.rootResult) { resultProof = true; } }
        if (task.rejected != null) { task.rejected.accept(failure); }
    }

    private void drain() {
        while (true) {
            final Task<?> task;
            synchronized (this) {
                task = queue.pollFirst();
                if (task == null) { scheduled = false; break; }
            }
            run(task);
        }
        finish();
    }

    private <T> void run(final Task<T> task) {
        try {
            if (task.child && cancellationRequested()) {
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "Managed child was cancelled before entry");
            }
            task.result.complete(inContext(task.work, task.trace));
        }
        catch (Exception | Error failure) { recordUnhandled(task, failure); task.result.completeExceptionally(failure); }
        finally {
            synchronized (this) { task.exited = true; tasks--; if (task.rootResult) { resultProof = true; } }
            finish();
        }
    }

    private void recordUnhandled(final Task<?> task, final Throwable failure) {
        if (task.unhandled) {
            synchronized (this) {
                if (unhandledExecutorFailure == null) { unhandledExecutorFailure = normalize(failure); }
            }
        }
    }

    private <T> T inContext(final Callable<T> work) throws Exception { return inContext(work, rootTrace); }

    private <T> T inContext(final Callable<T> work, final Map<String, String> trace) throws Exception {
        synchronized (this) { contextEntries++; }
        final ManagedInvocationLifetime previous = CURRENT.get();
        try {
            final Outcome<T> outcome = SecurityContexts.call(security, () -> {
                try {
                    return new Outcome<>(callScope.call(() -> {
                        CURRENT.set(this);
                        try (var ignored = J7mContext.open(context); var traceScope = InvocationTraceContext.open(trace)) { return work.call(); }
                        finally { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } }
                    }), null);
                } catch (Exception failure) { return new Outcome<T>(null, failure); }
            });
            if (outcome.failure != null) { throw outcome.failure; }
            return outcome.value;
        } finally { synchronized (this) { contextEntries--; } }
    }

    /**
     * {@inheritDoc}
     */
    @Override public void abortBeforeTarget() {
        synchronized (this) {
            if (rootEntered) { throw new IllegalStateException("INVOCATION_ALREADY_ENTERED"); }
            rootExited = true; resultProof = true; rawObserved = true;
            rawResult = InvocationResult.failure(new IllegalStateException("INVOCATION_ABORTED_BEFORE_ENTRY"));
        }
        finish();
    }

    /**
     * {@inheritDoc}
     */
    @Override public void close() {
        synchronized (this) { boundaryExited = true; }
        finish();
    }

    private void finish() {
        settle();
        synchronized (this) {
            if (releasing || !finalizersDone || !boundaryExited || !rootExited || tasks != 0 || contextEntries != 0 || !resultProof) { return; }
            releasing = true;
        }
        Throwable failure = null;
        try { retention.close(); callScope.close(); }
        catch (Exception | Error cleanup) { failure = new IllegalStateException("INVOCATION_CLEANUP_FAILED"); }
        finally {
            try { released.run(); }
            catch (RuntimeException | Error cleanup) { failure = new IllegalStateException("INVOCATION_RELEASE_FAILED"); }
            synchronized (this) { ownedStages.clear(); attachments.clear(); finalizers.clear(); }
        }
        if (failure == null) { physical.complete(null); } else { physical.completeExceptionally(failure); }
    }

    private void settle() {
        InvocationResult completed = null;
        RegisteredFinalizer next = null;
        InvocationResult input = null;
        long generation = 0;
        synchronized (this) {
            if (!userSealed && rootExited && rawObserved && resultProof && tasks == 0 && contextEntries == 0) {
                userSealed = true;
                finalizedResult = safeResult(rawResult);
                if (finalizedResult.failure() == null && unhandledExecutorFailure != null) {
                    finalizedResult = InvocationResult.failure(unhandledExecutorFailure);
                }
            }
            if (userSealed && !finalizersDone && !waitingFinalizer && tasks == 0 && contextEntries == 0) {
                if (finalizedResult.failure() == null && cancellationRequested()) {
                    finalizedResult = InvocationResult.failure(MiddlewareException.of(
                            remainingBudget().isZero() ? MiddlewareErrorCode.DEADLINE_EXCEEDED : MiddlewareErrorCode.CANCELLED,
                            "Managed work ended after its invocation budget"));
                }
                if (finalizers.isEmpty()) { finalizersDone = true; completed = finalizedResult; }
                else {
                    waitingFinalizer = true; generation = ++finalizerGeneration;
                    next = finalizers.removeFirst(); input = finalizedResult;
                }
            }
        }
        if (completed != null) {
            if (completed.failure() == null) { logical.complete(completed.value()); }
            else {
                final MiddlewareException safe = normalize(completed.failure());
                final Map<String, String> diagnostics = new java.util.HashMap<>();
                diagnostics.put("outcome", "UNKNOWN"); diagnostics.putAll(completed.diagnostics());
                logical.completeExceptionally(new MiddlewareException(new org.j7m.middleware.api.RemoteError(safe.code(),
                        "Managed invocation outcome is unavailable", diagnostics, null, null, false, false)));
            }
        }
        if (next != null) { enqueueFinalizer(next, input, generation); }
    }

    private void enqueueFinalizer(final RegisteredFinalizer finalizer, final InvocationResult input, final long generation) {
        final Task<Void> task = new Task<>(() -> {
            try {
                final CompletionStage<InvocationResult> result = Objects.requireNonNull(finalizer.action().apply(input), "finalizer result");
                result.whenComplete((outcome, failure) -> completeFinalizer(generation, outcome, failure));
            } catch (Exception | Error failure) { completeFinalizer(generation, null, failure); }
            return null;
        }, false, failure -> completeFinalizer(generation, null, failure), finalizer.trace());
        synchronized (this) { tasks++; queue.addLast(task); }
        schedule();
    }

    private void completeFinalizer(final long generation, final InvocationResult outcome, final Throwable failure) {
        synchronized (this) {
            if (!waitingFinalizer || generation != finalizerGeneration) { return; }
            waitingFinalizer = false;
            if (failure != null || outcome == null) { finalizedResult = InvocationResult.failure(normalize(failure)); }
            else if (finalizedResult.failure() == null || outcome.failure() != null) { finalizedResult = safeResult(outcome); }
            // Outer callbacks cannot turn a failed commit/store decision into a false success.
        }
        finish();
    }

    private static InvocationResult safeResult(final InvocationResult outcome) {
        return outcome.failure() == null ? outcome : new InvocationResult(null, normalize(outcome.failure()), outcome.diagnostics());
    }

    private static MiddlewareException normalize(final Throwable failure) {
        Throwable actual = failure;
        for (int depth = 0; actual != null && depth < 16; depth++) {
            final Class<?> type = actual.getClass();
            if (type != java.lang.reflect.InvocationTargetException.class
                    && type != java.util.concurrent.CompletionException.class
                    && type != java.util.concurrent.ExecutionException.class) { break; }
            final Throwable cause = actual.getCause();
            if (cause == null) { break; }
            actual = cause;
        }
        final MiddlewareErrorCode code = cancelledStage(actual) ? MiddlewareErrorCode.CANCELLED
                : actual != null && actual.getClass() == MiddlewareException.class
                        ? ((MiddlewareException) actual).code() : MiddlewareErrorCode.REMOTE_EXCEPTION;
        return new MiddlewareException(new org.j7m.middleware.api.RemoteError(code, "Managed invocation outcome is unavailable",
                Map.of("outcome", "UNKNOWN"), null, null, false, false));
    }

    private record RegisteredFinalizer(InvocationResultFinalizer action, Map<String, String> trace) { }

    private record Outcome<T>(T value, Exception failure) { }

    /**
     * The application capability deliberately implements no host completion interface.
     */
    private final class Control implements InvocationControl {
        @Override public Duration remainingBudget() { return ManagedInvocationLifetime.this.remainingBudget(); }
        @Override public boolean cancellationRequested() { return ManagedInvocationLifetime.this.cancellationRequested(); }
        @Override public <T> CompletionStage<T> submit(final Callable<T> work) { return ManagedInvocationLifetime.this.submit(work); }
        @Override public Executor contextualExecutor() { return ManagedInvocationLifetime.this.contextualExecutor(); }
    }

    private static final class Task<T> {
        private final Callable<T> work;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final CompletionStage<T> view = result.minimalCompletionStage();
        private final boolean child;
        private final boolean unhandled;
        private final Map<String, String> trace;
        private final java.util.function.Consumer<Throwable> rejected;
        private boolean exited;
        private boolean rootResult;
        private Task(final Callable<T> work, final boolean child) { this(work, child, null); }
        private Task(final Callable<T> work, final boolean child, final java.util.function.Consumer<Throwable> rejected) {
            this(work, child, rejected, Map.copyOf(InvocationTraceContext.current()));
        }
        private Task(final Callable<T> work, final boolean child, final java.util.function.Consumer<Throwable> rejected,
                final Map<String, String> trace) { this(work, child, rejected, trace, false); }
        private Task(final Callable<T> work, final boolean child, final java.util.function.Consumer<Throwable> rejected,
                final Map<String, String> trace, final boolean unhandled) {
            this.work = work; this.child = child; this.rejected = rejected; this.trace = trace; this.unhandled = unhandled;
        }
    }
}
