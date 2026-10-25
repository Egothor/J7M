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

package org.j7m.middleware.scheduler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.security.api.ApplicationContextId;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.ResourcePolicyAction;
import org.j7m.middleware.security.api.ResourcePolicyDecision;
import org.j7m.middleware.security.api.ResourcePolicyRequest;
import org.j7m.middleware.security.api.ResourceQuota;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.workload.api.AsyncResult;
import org.j7m.middleware.workload.api.CandidateInvoker;
import org.j7m.middleware.workload.api.FanOutRequest;
import org.j7m.middleware.workload.api.FanOutResult;
import org.j7m.middleware.workload.api.FencingToken;
import org.j7m.middleware.workload.api.LeaseOwner;
import org.j7m.middleware.workload.api.ScatterGatherRequest;
import org.j7m.middleware.workload.api.ScatterGatherResult;
import org.j7m.middleware.workload.api.StreamEmitter;
import org.j7m.middleware.workload.api.StreamHandle;
import org.j7m.middleware.workload.api.StreamProducer;
import org.j7m.middleware.workload.api.TaskCancellationToken;
import org.j7m.middleware.workload.api.TaskCheckpoint;
import org.j7m.middleware.workload.api.TaskDescriptor;
import org.j7m.middleware.workload.api.TaskFailure;
import org.j7m.middleware.workload.api.TaskId;
import org.j7m.middleware.workload.api.TaskLease;
import org.j7m.middleware.workload.api.TaskProgress;
import org.j7m.middleware.workload.api.TaskRef;
import org.j7m.middleware.workload.api.TaskResult;
import org.j7m.middleware.workload.api.TaskState;
import org.j7m.middleware.workload.api.TaskStatus;
import org.j7m.middleware.workload.api.TaskSubmission;
import org.j7m.middleware.workload.api.WorkloadContext;
import org.j7m.middleware.workload.api.WorkloadExecutor;
import org.j7m.middleware.workload.api.WorkloadHandle;
import org.j7m.middleware.workload.api.WorkloadScheduler;

/**
 * Bounded in-memory scheduler used as the Phase 4 baseline implementation.
 *
 * <p>The scheduler is thread-safe and owns its virtual-thread executor. It is
 * intentionally non-durable: tasks, leases, checkpoints, and results disappear
 * when the scheduler is closed or the JVM exits. The implementation enforces
 * authorization, resource policy, queue limits, concurrency limits, lease
 * ownership, and fencing tokens before handler execution.</p>
 */
public final class InMemoryWorkloadScheduler implements WorkloadScheduler {
    private static final DefaultValueCodec STREAM_CODEC = new DefaultValueCodec();
    private static final String RELIABILITY_PREFIX = "j7m.reliability.";
    private static final String IDEMPOTENT_METADATA = RELIABILITY_PREFIX + "idempotent";
    private static final long EXECUTOR_SHUTDOWN_MILLIS = 2_000L;

    private final InMemorySchedulerOptions options;
    private final ConcurrentMap<TaskId, TaskSlot> tasks;
    private final ExecutorService executor;
    private final ExecutorService fanOutExecutor;
    private final Semaphore taskPermits;
    private final Semaphore executionPermits;
    private final Semaphore fanOutPermits;
    private final AtomicBoolean closed;
    private final AtomicBoolean cleanupCompleted;
    private final AtomicLong tokenSequence;
    private final AtomicLong submitted;
    private final AtomicLong completed;
    private final AtomicLong failed;
    private final AtomicLong cancelled;
    private final AtomicLong fencingRejected;
    private final AtomicLong overloadedRejected;
    private final AtomicLong resourceDenied;
    private final AtomicLong asyncStarted;
    private final AtomicLong oneWayStarted;
    private final AtomicLong streamsStarted;
    private final AtomicLong streamCancelled;
    private final AtomicLong fanOutCalls;
    private final AtomicLong scatterGatherCalls;

    /**
     * Creates a scheduler.
     *
     * @param options scheduler options
     */
    public InMemoryWorkloadScheduler(final InMemorySchedulerOptions options) {
        this(options, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Creates a scheduler using a caller-supplied worker executor.
     *
     * <p>The scheduler owns the supplied executor and closes it from
     * {@link #close()}. This constructor exists for deterministic lifecycle and
     * rejection testing; production callers should normally use
     * {@link #InMemoryWorkloadScheduler(InMemorySchedulerOptions)}.</p>
     *
     * @param options scheduler options
     * @param workerExecutor lifecycle-owned worker executor
     */
    public InMemoryWorkloadScheduler(final InMemorySchedulerOptions options, final ExecutorService workerExecutor) {
        this.options = options == null ? InMemorySchedulerOptions.defaults("scheduler", "runtime") : options;
        this.tasks = new ConcurrentHashMap<>();
        this.executor = workerExecutor == null ? Executors.newVirtualThreadPerTaskExecutor() : workerExecutor;
        this.fanOutExecutor = new ThreadPoolExecutor(1, Math.max(1, this.options.maxConcurrentExecutions()),
                30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(this.options.maxConcurrentExecutions()));
        this.taskPermits = new Semaphore(this.options.maxTasks());
        this.executionPermits = new Semaphore(this.options.maxConcurrentExecutions());
        this.fanOutPermits = new Semaphore(this.options.maxConcurrentExecutions());
        this.closed = new AtomicBoolean();
        this.cleanupCompleted = new AtomicBoolean();
        this.tokenSequence = new AtomicLong();
        this.submitted = new AtomicLong();
        this.completed = new AtomicLong();
        this.failed = new AtomicLong();
        this.cancelled = new AtomicLong();
        this.fencingRejected = new AtomicLong();
        this.overloadedRejected = new AtomicLong();
        this.resourceDenied = new AtomicLong();
        this.asyncStarted = new AtomicLong();
        this.oneWayStarted = new AtomicLong();
        this.streamsStarted = new AtomicLong();
        this.streamCancelled = new AtomicLong();
        this.fanOutCalls = new AtomicLong();
        this.scatterGatherCalls = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String schedulerId() {
        return this.options.schedulerId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkloadHandle submit(final TaskSubmission submission) {
        this.ensureOpen();
        TaskSubmission safeSubmission = requireSubmission(submission);
        SecurityContext admissionContext = this.requireCallerContext("workload submission");
        this.validateSubmission(safeSubmission);
        this.authorize(admissionContext, PermissionAction.LOAD, "task-submit", safeSubmission.descriptor().taskName());
        this.resource(admissionContext, ResourcePolicyAction.LOAD, "task-submit", safeSubmission.descriptor().taskName());
        if (!this.taskPermits.tryAcquire()) {
            this.overloadedRejected.incrementAndGet();
            this.audit(admissionContext, "load", "task-submit", "denied", "scheduler-overloaded", Map.of());
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "scheduler task limit exceeded");
        }
        TaskId taskId = TaskId.random();
        TaskRef ref = new TaskRef(taskId, this.options.schedulerId(), safeSubmission.descriptor().taskName(), safeSubmission.metadata());
        TaskSlot slot = new TaskSlot(ref, safeSubmission, admissionContext, this.now(), this.deadlineAt(safeSubmission));
        TaskSlot previous = this.tasks.putIfAbsent(taskId, slot);
        if (previous != null) {
            this.taskPermits.release();
            throw MiddlewareException.of(MiddlewareErrorCode.INTERNAL, "scheduler task id collision");
        }
        this.trimTerminalHistory();
        this.submitted.incrementAndGet();
        this.audit(admissionContext, "load", ref.taskId().value(), "allowed", "task-submitted", Map.of("taskName", safeSubmission.descriptor().taskName()));
        return new SchedulerWorkloadHandle(this, ref);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskLease acquireLease(final TaskId taskId, final LeaseOwner owner, final Duration duration) {
        TaskSlot slot = this.requireSlot(taskId);
        synchronized (slot) {
            Instant now = this.now();
            if (slot.lease != null && slot.lease.isLiveAt(now) && !sameOwner(slot.lease.owner(), owner)) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "task already leased");
            }
            TaskLease lease = new TaskLease(taskId, owner, now.plus(durationOrDefault(duration)), new FencingToken(this.tokenSequence.incrementAndGet()));
            slot.lease = lease;
            slot.state = TaskState.LEASED;
            slot.updatedAt = now;
            return lease;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskLease renewLease(final TaskId taskId, final LeaseOwner owner, final FencingToken token, final Duration duration) {
        TaskSlot slot = this.requireSlot(taskId);
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            TaskLease lease = new TaskLease(taskId, owner, this.now().plus(durationOrDefault(duration)), token);
            slot.lease = lease;
            slot.updatedAt = this.now();
            return lease;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseLease(final TaskId taskId, final LeaseOwner owner, final FencingToken token) {
        TaskSlot slot = this.requireSlot(taskId);
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            slot.lease = null;
            if (slot.state == TaskState.LEASED) {
                slot.state = TaskState.QUEUED;
            }
            slot.updatedAt = this.now();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportProgress(final TaskId taskId, final LeaseOwner owner, final FencingToken token, final TaskProgress progress) {
        TaskSlot slot = this.requireSlot(taskId);
        TaskProgress safeProgress = this.validatedProgress(progress);
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            this.requireNonTerminal(slot.state, "progress");
            if (safeProgress.percent() < slot.progress.percent()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task progress must be monotonic");
            }
            slot.progress = safeProgress;
            slot.updatedAt = this.now();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskCheckpoint checkpoint(final TaskId taskId, final LeaseOwner owner, final FencingToken token, final Map<String, String> metadata) {
        TaskSlot slot = this.requireSlot(taskId);
        Map<String, String> safeMetadata = this.redactedMetadata(metadata, this.options.maxCheckpointMetadataBytes());
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            this.requireNonTerminal(slot.state, "checkpoint");
            slot.state = TaskState.CHECKPOINTING;
            TaskCheckpoint checkpoint = new TaskCheckpoint(taskId, slot.nextCheckpointVersion++, this.now(), safeMetadata);
            slot.checkpoint = checkpoint;
            slot.updatedAt = this.now();
            return checkpoint;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void complete(final TaskId taskId, final LeaseOwner owner, final FencingToken token, final Object result) {
        TaskSlot slot = this.requireSlot(taskId);
        Object safeResult = this.validatedResult(result, slot.submission.options().maxResultBytes());
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            this.requireNonTerminal(slot.state, "completion");
            slot.result = new TaskResult(safeResult, this.now(), Map.of());
            slot.state = TaskState.COMPLETED;
            slot.updatedAt = this.now();
            this.releaseTaskPermit(slot);
            this.completed.incrementAndGet();
            this.audit(slot.admissionContext, "invoke", taskId.value(), "allowed", "task-completed", Map.of());
        }
        this.trimTerminalHistory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fail(final TaskId taskId, final LeaseOwner owner, final FencingToken token, final TaskFailure failure) {
        TaskSlot slot = this.requireSlot(taskId);
        TaskFailure safeFailure = this.redactedFailure(failure);
        synchronized (slot) {
            this.requireValidFence(slot, owner, token);
            this.requireNonTerminal(slot.state, "failure");
            slot.failure = safeFailure;
            slot.state = TaskState.FAILED;
            slot.updatedAt = this.now();
            this.releaseTaskPermit(slot);
            this.failed.incrementAndGet();
        }
        this.trimTerminalHistory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancel(final TaskId taskId) {
        TaskSlot slot = this.requireSlot(taskId);
        SecurityContext caller = this.requireCallerContext("workload cancellation");
        this.authorize(caller, PermissionAction.KILL, "task-cancel", taskId.value());
        synchronized (slot) {
            if (terminal(slot.state)) {
                return;
            }
            slot.cancelled.set(true);
            slot.state = slot.state == TaskState.RUNNING ? TaskState.CANCELLING : TaskState.CANCELLED;
            slot.updatedAt = this.now();
            if (slot.state == TaskState.CANCELLED) {
                this.releaseTaskPermit(slot);
                this.cancelled.incrementAndGet();
            }
        }
        this.trimTerminalHistory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskStatus status(final TaskId taskId) {
        TaskSlot slot = this.requireSlot(taskId);
        synchronized (slot) {
            return slot.status();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TaskStatus> listTasks() {
        List<TaskStatus> statuses = new ArrayList<>();
        for (TaskSlot slot : this.tasks.values()) {
            synchronized (slot) {
                statuses.add(slot.status());
            }
        }
        statuses.sort(Comparator.comparing(status -> status.ref().taskId().value()));
        return List.copyOf(statuses);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncResult<Object> submitAsync(final TaskSubmission submission, final WorkloadExecutor workloadExecutor) {
        this.asyncStarted.incrementAndGet();
        if (workloadExecutor == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "workload executor is required");
        }
        WorkloadHandle handle = this.submit(submission);
        LeaseOwner owner = owner("async");
        boolean permitAcquired = false;
        TaskLease lease = null;
        try {
            if (!this.tryAcquireExecution(handle.ref())) {
                this.markExecutionAdmissionFailed(handle.ref(), owner, null, MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded");
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded");
            }
            permitAcquired = true;
            lease = this.acquireLease(handle.ref().taskId(), owner, this.options.defaultLeaseDuration());
            TaskLease submittedLease = lease;
            Future<Object> future = this.executor.submit(() -> this.executeOwnedWithPermit(handle.ref(), owner, submittedLease, workloadExecutor));
            permitAcquired = false;
            return new SchedulerAsyncResult(handle.ref(), future, this);
        } catch (RuntimeException exception) {
            if (permitAcquired) {
                this.cleanupFailedPostPermitAdmission(handle.ref(), owner, lease, admissionCode(exception), "scheduler execution admission failed");
            }
            throw admissionException(exception, "scheduler execution admission failed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskRef submitOneWay(final TaskSubmission submission, final WorkloadExecutor workloadExecutor) {
        this.oneWayStarted.incrementAndGet();
        if (workloadExecutor == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "workload executor is required");
        }
        WorkloadHandle handle = this.submit(submission);
        LeaseOwner owner = owner("one-way");
        boolean permitAcquired = false;
        TaskLease lease = null;
        try {
            if (!this.tryAcquireExecution(handle.ref())) {
                this.markExecutionAdmissionFailed(handle.ref(), owner, null, MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded");
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded");
            }
            permitAcquired = true;
            lease = this.acquireLease(handle.ref().taskId(), owner, this.options.defaultLeaseDuration());
            TaskLease submittedLease = lease;
            this.executor.submit(() -> {
                try {
                    this.executeOwnedWithPermit(handle.ref(), owner, submittedLease, workloadExecutor);
                } catch (RuntimeException exception) {
                    return null;
                }
                return null;
            });
            permitAcquired = false;
            return handle.ref();
        } catch (RuntimeException exception) {
            if (permitAcquired) {
                this.cleanupFailedPostPermitAdmission(handle.ref(), owner, lease, admissionCode(exception), "scheduler execution admission failed");
            }
            throw admissionException(exception, "scheduler execution admission failed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> StreamHandle<T> stream(final TaskSubmission submission, final StreamProducer<T> producer) {
        this.streamsStarted.incrementAndGet();
        if (producer == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "stream producer is required");
        }
        WorkloadHandle handle = this.submit(submission);
        LeaseOwner owner = owner("stream");
        if (!this.tryAcquireExecution(handle.ref())) {
            this.markExecutionAdmissionFailed(handle.ref(), owner, null, MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded");
            QueueStreamState<T> failedState = new QueueStreamState<>(this.options.streamBufferSize(), this.options.maxStreamItemBytes(),
                    this.streamQueueByteLimit(), this.options.clock(), this.now());
            failedState.fail(MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "scheduler execution limit exceeded"));
            return new QueueStreamHandle<>(failedState, CompletableFuture.failedFuture(MiddlewareException.of(MiddlewareErrorCode.OVERLOADED,
                    "scheduler execution limit exceeded")), this.streamCancelled);
        }
        TaskSlot streamSlot = this.requireSlot(handle.ref().taskId());
        QueueStreamState<T> streamState = new QueueStreamState<>(this.options.streamBufferSize(), this.options.maxStreamItemBytes(),
                this.streamQueueByteLimit(), this.options.clock(), streamSlot.deadlineAt);
        TaskLease lease = null;
        Future<?> future;
        try {
            lease = this.acquireLease(handle.ref().taskId(), owner, this.options.defaultLeaseDuration());
            TaskLease submittedLease = lease;
            future = this.executor.submit(() -> {
                try {
                    TaskSlot slot = this.requireSlot(handle.ref().taskId());
                    SecurityContexts.call(slot.admissionContext, () -> {
                        synchronized (slot) {
                            this.requireValidFence(slot, owner, submittedLease.fencingToken());
                            this.requireLiveDeadline(slot);
                            if (slot.cancelled.get()) {
                                slot.state = TaskState.CANCELLED;
                                slot.updatedAt = this.now();
                                this.releaseTaskPermit(slot);
                                return null;
                            }
                            slot.state = TaskState.RUNNING;
                            slot.updatedAt = this.now();
                        }
                        WorkloadContext context = new WorkloadContext(slot.admissionContext, handle.ref(), submittedLease, slot.submission.metadata());
                        producer.produce(context, token(handle.ref().taskId(), streamState.cancelled), new QueueStreamEmitter<>(streamState));
                        this.complete(handle.ref().taskId(), owner, submittedLease.fencingToken(), "stream-complete");
                        streamState.complete();
                        return null;
                    });
                } catch (Exception exception) {
                    streamState.fail(streamException(exception));
                    this.markFailed(handle.ref(), owner, submittedLease, exception);
                } finally {
                    this.executionPermits.release();
                }
                return null;
            });
        } catch (RuntimeException exception) {
            this.cleanupFailedPostPermitAdmission(handle.ref(), owner, lease, admissionCode(exception), "scheduler stream admission failed");
            throw admissionException(exception, "scheduler stream admission failed");
        }
        return new QueueStreamHandle<>(streamState, future, this.streamCancelled);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FanOutResult fanOut(final FanOutRequest request, final CandidateInvoker invoker) {
        this.fanOutCalls.incrementAndGet();
        this.ensureOpen();
        if (request == null || invoker == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "fan-out request and invoker are required");
        }
        SecurityContext caller = this.requireCallerContext("fan-out");
        this.authorize(caller, PermissionAction.INVOKE, "fan-out", "");
        this.resource(caller, ResourcePolicyAction.INVOKE, "fan-out", "");
        this.validateFanOutMetadata(request.metadata());
        List<Object> successes = new ArrayList<>();
        List<TaskFailure> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Instant stopAt = this.now().plus(request.deadline());
        int limit = this.candidateLimit(request.candidates().size(), request.maxCandidates());
        int candidateOverflow = Math.max(0, request.candidates().size() - limit);
        int skippedOverflow = 0;
        Set<String> seenTargets = new HashSet<>();
        boolean idempotent = Boolean.parseBoolean(request.metadata().getOrDefault(IDEMPOTENT_METADATA, "false"));
        int retainedBytes = 0;
        for (int index = 0; index < limit; index++) {
            ObjectRef candidate = request.candidates().get(index);
            String candidateId = candidate == null ? "" : candidate.objectId();
            if (candidate == null || !this.now().isBefore(stopAt)) {
                skippedOverflow += this.addRetained(skipped, candidateId);
                continue;
            }
            if (!idempotent && !seenTargets.add(candidate.objectId())) {
                skippedOverflow += this.addRetained(skipped, candidate.objectId());
                continue;
            }
            try {
                Object value = this.invokeCandidate(caller, invoker, candidate, stopAt, request.perTargetTimeout());
                int valueBytes = estimatedObjectBytes(value);
                if (successes.size() >= this.options.maxRetainedFanOutResults()
                        || retainedBytes + valueBytes > this.options.maxAggregateResultBytes()) {
                    skippedOverflow += this.addRetained(skipped, candidate.objectId());
                } else {
                    successes.add(value);
                    retainedBytes += valueBytes;
                }
            } catch (Exception exception) {
                if (failures.size() < this.options.maxRetainedFanOutResults()) {
                    failures.add(new TaskFailure(code(exception), "candidate invocation failed", true, this.now(),
                            Map.of("candidate", candidate.objectId())));
                }
            }
        }
        return new FanOutResult(successes, failures, skipped, !failures.isEmpty() || !skipped.isEmpty() || candidateOverflow > 0 || skippedOverflow > 0,
                resultMetadata("fan-out", candidateOverflow, skippedOverflow));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ScatterGatherResult scatterGather(final ScatterGatherRequest request, final CandidateInvoker invoker) {
        this.scatterGatherCalls.incrementAndGet();
        this.ensureOpen();
        if (request == null || invoker == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "scatter-gather request and invoker are required");
        }
        SecurityContext caller = this.requireCallerContext("scatter-gather");
        this.authorize(caller, PermissionAction.INVOKE, "scatter-gather", "");
        this.resource(caller, ResourcePolicyAction.INVOKE, "scatter-gather", "");
        this.validateFanOutMetadata(request.metadata());
        List<Object> successes = new ArrayList<>();
        List<TaskFailure> failures = new ArrayList<>();
        List<String> timeouts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Instant stopAt = this.now().plus(request.deadline());
        int limit = this.candidateLimit(request.candidates().size(), request.maxCandidates());
        int candidateOverflow = Math.max(0, request.candidates().size() - limit);
        int skippedOverflow = 0;
        Set<String> seenTargets = new HashSet<>();
        boolean idempotent = Boolean.parseBoolean(request.metadata().getOrDefault(IDEMPOTENT_METADATA, "false"));
        int retainedBytes = 0;
        for (int index = 0; index < limit; index++) {
            ObjectRef candidate = request.candidates().get(index);
            String candidateId = candidate == null ? "" : candidate.objectId();
            if (candidate == null) {
                skippedOverflow += this.addRetained(skipped, candidateId);
                continue;
            }
            if (!idempotent && !seenTargets.add(candidate.objectId())) {
                skippedOverflow += this.addRetained(skipped, candidate.objectId());
                continue;
            }
            if (!this.now().isBefore(stopAt)) {
                timeouts.add(candidate.objectId());
                continue;
            }
            try {
                Object value = this.invokeCandidate(caller, invoker, candidate, stopAt, request.deadline());
                int valueBytes = estimatedObjectBytes(value);
                if (successes.size() >= this.options.maxRetainedFanOutResults()
                        || retainedBytes + valueBytes > this.options.maxAggregateResultBytes()) {
                    skippedOverflow += this.addRetained(skipped, candidate.objectId());
                } else {
                    successes.add(value);
                    retainedBytes += valueBytes;
                }
            } catch (TimeoutException exception) {
                timeouts.add(candidate.objectId());
            } catch (Exception exception) {
                if (failures.size() < this.options.maxRetainedFanOutResults()) {
                    failures.add(new TaskFailure(code(exception), "candidate invocation failed", true, this.now(),
                            Map.of("candidate", candidate.objectId())));
                }
                if (!request.allowPartialResults()) {
                    break;
                }
            }
        }
        boolean partial = !failures.isEmpty() || !timeouts.isEmpty() || !skipped.isEmpty() || candidateOverflow > 0 || skippedOverflow > 0;
        return new ScatterGatherResult(successes, failures, timeouts, skipped, partial,
                resultMetadata("scatter-gather", candidateOverflow, skippedOverflow));
    }

    /**
     * Drains the scheduler by rejecting new work while allowing current tasks to
     * observe cancellation policy through normal handles.
     */
    public void drain() {
        this.closed.set(true);
        SecurityContext caller = this.context();
        this.audit(caller, "admin", this.options.schedulerId(), "allowed", "scheduler-drain", Map.of());
    }

    /**
     * Returns a metrics snapshot.
     *
     * @return metrics snapshot
     */
    public WorkloadMetricsSnapshot metrics() {
        long running = this.listTasks().stream().filter(status -> status.state() == TaskState.RUNNING).count();
        return new WorkloadMetricsSnapshot(this.submitted.get(), running, this.completed.get(), this.failed.get(), this.cancelled.get(),
                this.fencingRejected.get(), this.overloadedRejected.get(), this.resourceDenied.get(), this.asyncStarted.get(),
                this.oneWayStarted.get(), this.streamsStarted.get(), this.streamCancelled.get(), this.fanOutCalls.get(),
                this.scatterGatherCalls.get(), Map.of("schedulerId", this.options.schedulerId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed.set(true);
        if (this.cleanupCompleted.compareAndSet(false, true)) {
            this.shutdownOwnedExecutor(this.executor);
            this.shutdownOwnedExecutor(this.fanOutExecutor);
            this.tasks.clear();
            this.taskPermits.drainPermits();
            this.taskPermits.release(this.options.maxTasks());
            this.executionPermits.drainPermits();
            this.executionPermits.release(this.options.maxConcurrentExecutions());
            this.fanOutPermits.drainPermits();
            this.fanOutPermits.release(this.options.maxConcurrentExecutions());
        }
    }

    private Object executeOwnedWithPermit(final TaskRef ref, final LeaseOwner owner, final TaskLease lease, final WorkloadExecutor workloadExecutor) throws Exception {
        TaskSlot slot = this.requireSlot(ref.taskId());
        try {
            return SecurityContexts.call(slot.admissionContext, () -> {
                this.resource(slot.admissionContext, ResourcePolicyAction.INVOKE, "task-execute", ref.serviceName());
                synchronized (slot) {
                    this.requireValidFence(slot, owner, lease.fencingToken());
                    this.requireLiveDeadline(slot);
                    if (slot.cancelled.get()) {
                        slot.state = TaskState.CANCELLED;
                        slot.updatedAt = this.now();
                        return null;
                    }
                    slot.state = TaskState.RUNNING;
                    slot.updatedAt = this.now();
                }
                WorkloadContext context = new WorkloadContext(slot.admissionContext, ref, lease, slot.submission.metadata());
                Object value = workloadExecutor.execute(context, token(ref.taskId(), slot.cancelled));
                synchronized (slot) {
            if (slot.cancelled.get()) {
                slot.state = TaskState.CANCELLED;
                slot.updatedAt = this.now();
                this.releaseTaskPermit(slot);
                return value;
            }
                }
                this.complete(ref.taskId(), owner, lease.fencingToken(), value);
                return value;
            });
        } catch (Exception exception) {
            this.markFailed(ref, owner, lease, exception);
            throw exception;
        } finally {
            this.executionPermits.release();
        }
    }

    private boolean tryAcquireExecution(final TaskRef ref) {
        boolean acquired = this.executionPermits.tryAcquire();
        if (!acquired) {
            this.overloadedRejected.incrementAndGet();
            this.audit(this.context(), "invoke", ref.taskId().value(), "denied", "scheduler-execution-limit", Map.of());
        }
        return acquired;
    }

    private void cleanupFailedPostPermitAdmission(
            final TaskRef ref,
            final LeaseOwner owner,
            final TaskLease lease,
            final MiddlewareErrorCode code,
            final String message) {
        try {
            this.markExecutionAdmissionFailed(ref, owner, lease, code, message);
        } finally {
            this.executionPermits.release();
        }
    }

    private void markExecutionAdmissionFailed(
            final TaskRef ref,
            final LeaseOwner owner,
            final TaskLease lease,
            final MiddlewareErrorCode code,
            final String message) {
        TaskSlot slot = this.requireSlot(ref.taskId());
        synchronized (slot) {
            if (terminal(slot.state)) {
                return;
            }
            if (lease != null && slot.lease != null && sameOwner(slot.lease.owner(), owner)
                    && slot.lease.fencingToken().equals(lease.fencingToken())) {
                slot.lease = null;
            }
            slot.failure = new TaskFailure(code, message, true, this.now(), Map.of());
            slot.state = TaskState.FAILED;
            slot.updatedAt = this.now();
            this.releaseTaskPermit(slot);
            this.failed.incrementAndGet();
        }
        this.trimTerminalHistory();
    }

    private void markFailed(final TaskRef ref, final LeaseOwner owner, final TaskLease lease, final Exception exception) {
        TaskSlot slot = this.requireSlot(ref.taskId());
        synchronized (slot) {
            if (terminal(slot.state)) {
                return;
            }
            slot.failure = new TaskFailure(code(exception), "task execution failed", true, this.now(), Map.of());
            slot.state = TaskState.FAILED;
            slot.updatedAt = this.now();
            this.releaseTaskPermit(slot);
            this.failed.incrementAndGet();
        }
        this.trimTerminalHistory();
    }

    private static MiddlewareErrorCode code(final Exception exception) {
        if (exception instanceof MiddlewareException middlewareException) {
            return middlewareException.code();
        }
        return MiddlewareErrorCode.REMOTE_EXCEPTION;
    }

    private static MiddlewareException streamException(final Exception exception) {
        if (exception instanceof MiddlewareException middlewareException) {
            return middlewareException;
        }
        return MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "stream producer failed");
    }

    private static MiddlewareErrorCode admissionCode(final RuntimeException exception) {
        if (exception instanceof MiddlewareException middlewareException) {
            return middlewareException.code();
        }
        if (exception instanceof RejectedExecutionException) {
            return MiddlewareErrorCode.UNAVAILABLE;
        }
        return MiddlewareErrorCode.INTERNAL;
    }

    private static MiddlewareException admissionException(final RuntimeException exception, final String message) {
        if (exception instanceof MiddlewareException middlewareException) {
            return middlewareException;
        }
        return MiddlewareException.of(admissionCode(exception), message);
    }

    private void requireValidFence(final TaskSlot slot, final LeaseOwner owner, final FencingToken token) {
        if (slot.lease == null || !slot.lease.isLiveAt(this.now()) || !sameOwner(slot.lease.owner(), owner)
                || !slot.lease.fencingToken().equals(token)) {
            if (!terminal(slot.state)) {
                slot.state = TaskState.FENCED;
            }
            slot.updatedAt = this.now();
            this.fencingRejected.incrementAndGet();
            this.audit(slot.admissionContext, "invoke", slot.ref.taskId().value(), "denied", "stale-fencing-token", Map.of());
            throw MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN, "stale fencing token");
        }
    }

    private void requireNonTerminal(final TaskState state, final String operation) {
        if (terminal(state)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "cannot update " + operation + " after task reached a terminal state");
        }
    }

    private void requireLiveDeadline(final TaskSlot slot) {
        if (!this.now().isBefore(slot.deadlineAt)) {
            slot.state = TaskState.EXPIRED;
            slot.updatedAt = this.now();
            this.releaseTaskPermit(slot);
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "task deadline expired before execution");
        }
    }

    private static boolean terminal(final TaskState state) {
        return state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED || state == TaskState.EXPIRED;
    }

    private TaskSlot requireSlot(final TaskId taskId) {
        TaskSlot slot = this.tasks.get(taskId);
        if (slot == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "task not found");
        }
        return slot;
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "scheduler is closed or draining");
        }
    }

    private void authorize(final SecurityContext context, final PermissionAction action, final String resource, final String serviceName) {
        AuthorizationDecision decision = this.options.security().policy().evaluate(context,
                new PermissionRequest(action, resource, this.options.registryContext(), this.options.runtimeId(), serviceName, "",
                        "scheduler", "", Map.of("schedulerId", this.options.schedulerId())));
        if (!decision.allowed()) {
            MiddlewareErrorCode code = decision.authenticatedRequired() ? MiddlewareErrorCode.UNAUTHENTICATED : MiddlewareErrorCode.PERMISSION_DENIED;
            this.audit(context, action.wireName(), resource, "denied", decision.reason(), Map.of("policyId", decision.policyId()));
            throw MiddlewareException.of(code, "workload operation denied");
        }
    }

    private void resource(final SecurityContext context, final ResourcePolicyAction action, final String objectId, final String serviceName) {
        ResourcePolicyDecision decision = this.options.security().resourcePolicy().evaluate(context,
                new ResourcePolicyRequest(action, new ApplicationContextId(this.options.registryContext()), this.options.runtimeId(), serviceName,
                        objectId, "", new ResourceQuota(0, this.options.maxConcurrentExecutions(), this.options.maxTasks(), 0),
                        new ResourceUsageSnapshot(this.tasks.size(), activeExecutions(), 0, 0, 0),
                        Map.of("schedulerId", this.options.schedulerId())));
        if (!decision.allowed()) {
            this.resourceDenied.incrementAndGet();
            MiddlewareErrorCode code = decision.overloaded() ? MiddlewareErrorCode.OVERLOADED : MiddlewareErrorCode.PERMISSION_DENIED;
            this.audit(context, action.name().toLowerCase(java.util.Locale.ROOT), objectId, "denied", decision.reason(), Map.of("policyId", decision.policyId()));
            throw MiddlewareException.of(code, "workload resource policy denied");
        }
    }

    private int activeExecutions() {
        return this.options.maxConcurrentExecutions() - this.executionPermits.availablePermits();
    }

    private SecurityContext context() {
        SecurityContext context = SecurityContexts.current();
        return context == null ? SecurityContext.system(this.options.runtimeId()) : context;
    }

    private SecurityContext requireCallerContext(final String operation) {
        SecurityContext context = SecurityContexts.current();
        if (context == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, operation + " requires an installed caller security context");
        }
        return context;
    }

    private void audit(final SecurityContext context, final String action, final String resource, final String decision, final String reason, final Map<String, String> metadata) {
        this.options.security().auditSink().record(new AuditEvent(this.now(), this.options.runtimeId(), context.principal().id(), action, resource,
                decision, this.options.security().policy().id(), context.correlationId(), context.invocationId(), context.remoteEndpoint(), reason,
                "scheduler", this.options.registryContext(), metadata));
    }

    private static TaskSubmission requireSubmission(final TaskSubmission submission) {
        if (submission == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task submission is required");
        }
        return submission;
    }

    private void validateSubmission(final TaskSubmission submission) {
        this.validateMetadata(submission.metadata(), this.options.maxCheckpointMetadataBytes());
        this.validateMetadata(submission.options().metadata(), this.options.maxCheckpointMetadataBytes());
        int requestedPayloadLimit = submission.options().maxPayloadBytes();
        int payloadLimit = requestedPayloadLimit <= 0
                ? this.options.defaultMaxPayloadBytes()
                : Math.min(requestedPayloadLimit, this.options.defaultMaxPayloadBytes());
        if (estimatedSubmissionPayloadBytes(submission.descriptor().payload()) > payloadLimit) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task payload exceeds configured limit");
        }
    }

    private Instant deadlineAt(final TaskSubmission submission) {
        Duration deadline = submission.options().deadline();
        if (deadline.isNegative() || deadline.isZero()) {
            return this.now();
        }
        return this.now().plus(deadline);
    }

    private TaskProgress validatedProgress(final TaskProgress progress) {
        if (progress == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task progress is required");
        }
        if (progress.percent() < 0.0D || progress.percent() > 100.0D) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task progress percent must be between 0 and 100");
        }
        if (utf8Bytes(progress.message()) > this.options.maxProgressMessageBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task progress message exceeds configured limit");
        }
        Map<String, String> metadata = this.redactedMetadata(progress.metadata(), this.options.maxCheckpointMetadataBytes());
        String message = this.options.security().redactor().redact("progress.message", progress.message());
        return new TaskProgress(progress.percent(), message, progress.updatedAt(), metadata);
    }

    private Object validatedResult(final Object result, final int requestedLimit) {
        int limit = requestedLimit <= 0 ? this.options.maxAggregateResultBytes() : Math.min(requestedLimit, this.options.maxAggregateResultBytes());
        if (estimatedObjectBytes(result) > limit) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "task result exceeds configured retention limit");
        }
        if (result instanceof String text) {
            return this.options.security().redactor().redact("task.result", text);
        }
        return result;
    }

    private TaskFailure redactedFailure(final TaskFailure failure) {
        if (failure == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task failure is required");
        }
        return new TaskFailure(failure.code(), this.options.security().redactor().redact("task.failure", failure.message()),
                failure.retryable(), failure.failedAt(), this.redactedMetadata(failure.metadata(), this.options.maxCheckpointMetadataBytes()));
    }

    private Map<String, String> redactedMetadata(final Map<String, String> metadata, final int maxAggregateBytes) {
        this.validateMetadata(metadata, maxAggregateBytes);
        return this.options.security().redactor().redactMetadata(metadata == null ? Map.of() : metadata);
    }

    private void validateFanOutMetadata(final Map<String, String> metadata) {
        this.validateMetadata(metadata, this.options.maxCheckpointMetadataBytes());
    }

    private void validateMetadata(final Map<String, String> metadata, final int maxAggregateBytes) {
        Map<String, String> safe = metadata == null ? Map.of() : metadata;
        if (safe.size() > this.options.maxMetadataEntries()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "metadata entry limit exceeded");
        }
        int aggregate = 0;
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            int keyBytes = utf8Bytes(entry.getKey());
            int valueBytes = utf8Bytes(entry.getValue());
            if (keyBytes > this.options.maxMetadataValueBytes() || valueBytes > this.options.maxMetadataValueBytes()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "metadata string limit exceeded");
            }
            aggregate += keyBytes + valueBytes;
            if (aggregate > maxAggregateBytes) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "metadata aggregate limit exceeded");
            }
        }
    }

    private int candidateLimit(final int candidateCount, final int requestedMaxCandidates) {
        int requestLimit = requestedMaxCandidates <= 0 ? candidateCount : requestedMaxCandidates;
        return Math.min(Math.min(candidateCount, requestLimit), this.options.maxFanOutTargets());
    }

    private int addRetained(final List<String> retained, final String value) {
        if (retained.size() >= this.options.maxRetainedFanOutResults()) {
            return 1;
        }
        retained.add(value == null ? "" : value);
        return 0;
    }

    private static Map<String, String> resultMetadata(final String mode, final int candidateOverflow, final int skippedOverflow) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode);
        if (candidateOverflow > 0) {
            metadata.put("candidateOverflow", Integer.toString(candidateOverflow));
        }
        if (skippedOverflow > 0) {
            metadata.put("skippedOverflow", Integer.toString(skippedOverflow));
        }
        return Map.copyOf(metadata);
    }

    private Object invokeCandidate(
            final SecurityContext caller,
            final CandidateInvoker invoker,
            final ObjectRef candidate,
            final Instant stopAt,
            final Duration perTargetTimeout) throws Exception {
        if (!this.now().isBefore(stopAt)) {
            throw new TimeoutException("fan-out deadline expired");
        }
        this.requireEligibleCandidate(candidate);
        Duration remaining = Duration.between(this.now(), stopAt);
        Duration timeout = perTargetTimeout == null || perTargetTimeout.isNegative() || perTargetTimeout.isZero()
                ? remaining
                : min(remaining, perTargetTimeout);
        if (!this.fanOutPermits.tryAcquire()) {
            this.overloadedRejected.incrementAndGet();
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "fan-out concurrency limit exceeded");
        }
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        Future<Object> future;
        try {
            future = this.fanOutExecutor.submit(() -> {
                started.set(true);
                try {
                    return SecurityContexts.call(caller, () -> invoker.invoke(candidate));
                } finally {
                    this.releaseFanOutPermit(released);
                }
            });
        } catch (RejectedExecutionException exception) {
            this.releaseFanOutPermit(released);
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "fan-out executor is closed");
        }
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            boolean cancelled = future.cancel(true);
            if (cancelled && !started.get()) {
                this.releaseFanOutPermit(released);
            }
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            boolean cancelled = future.cancel(true);
            if (cancelled && !started.get()) {
                this.releaseFanOutPermit(released);
            }
            throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "fan-out invocation interrupted");
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new RuntimeException(cause);
        }
    }

    private void requireEligibleCandidate(final ObjectRef candidate) {
        if (candidate == null
                || candidate.generation() <= 0L
                || !candidate.isLeaseActive(this.options.clock().millis())
                || !"trusted".equals(candidate.metadata().get("trustStatus"))
                || this.positiveLong(candidate.metadata().get("fencingToken")) <= 0L
                || this.positiveLong(candidate.metadata().get("topologyGeneration")) <= 0L
                || this.deadCandidate(candidate)) {
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "fan-out candidate is not trusted and eligible");
        }
    }

    private boolean deadCandidate(final ObjectRef candidate) {
        String state = candidate.metadata().get("state");
        if (state == null || state.isBlank()) {
            return false;
        }
        return "dead".equalsIgnoreCase(state) || "stale".equalsIgnoreCase(state) || "expired".equalsIgnoreCase(state);
    }

    private long positiveLong(final String value) {
        if (value == null || value.isBlank()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : -1L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private void releaseFanOutPermit(final AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            this.fanOutPermits.release();
        }
    }

    private static Duration min(final Duration left, final Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static int estimatedObjectBytes(final Object value) {
        if (value == null) {
            return 0;
        }
        EncodedValue encodedValue = STREAM_CODEC.encode(value, value.getClass(), null);
        return encodedValueBytes(encodedValue);
    }

    private static int estimatedSubmissionPayloadBytes(final Object value) {
        try {
            return estimatedObjectBytes(value);
        } catch (MiddlewareException exception) {
            if (exception.code() == MiddlewareErrorCode.CODEC_ERROR) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "task payload exceeds configured limit");
            }
            throw exception;
        }
    }

    private int streamQueueByteLimit() {
        long limit = (long) this.options.streamBufferSize() * (long) this.options.maxStreamItemBytes();
        return limit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, limit);
    }

    private static int encodedValueBytes(final EncodedValue value) {
        if (value == null) {
            return 0;
        }
        long total = utf8Bytes(value.kind()) + utf8Bytes(value.typeName()) + metadataBytes(value.metadata()) + rawEncodedBytes(value.value());
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static int rawEncodedBytes(final Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof EncodedValue encodedValue) {
            return encodedValueBytes(encodedValue);
        }
        if (raw instanceof String text) {
            return utf8Bytes(text);
        }
        if (raw instanceof Character) {
            return Character.BYTES;
        }
        if (raw instanceof Boolean) {
            return 1;
        }
        if (raw instanceof Byte) {
            return Byte.BYTES;
        }
        if (raw instanceof Short) {
            return Short.BYTES;
        }
        if (raw instanceof Integer || raw instanceof Float) {
            return Integer.BYTES;
        }
        if (raw instanceof Long || raw instanceof Double) {
            return Long.BYTES;
        }
        if (raw instanceof byte[] bytes) {
            return bytes.length;
        }
        if (raw instanceof ObjectRef ref) {
            return objectRefBytes(ref);
        }
        if (raw instanceof List<?> list) {
            long total = 0L;
            for (Object item : list) {
                total += rawEncodedBytes(item);
                if (total > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return (int) total;
        }
        if (raw instanceof Map<?, ?> map) {
            long total = 0L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    total += utf8Bytes(key);
                }
                total += rawEncodedBytes(entry.getValue());
                if (total > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return (int) total;
        }
        return 0;
    }

    private static int objectRefBytes(final ObjectRef ref) {
        return utf8Bytes(ref.objectId()) + utf8Bytes(ref.serviceName()) + utf8Bytes(ref.runtimeId())
                + utf8Bytes(ref.registryContext()) + utf8Bytes(ref.transport()) + utf8Bytes(ref.endpoint())
                + metadataBytes(ref.metadata());
    }

    private static int metadataBytes(final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            total += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private static int utf8Bytes(final String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private Instant now() {
        return this.options.clock().instant();
    }

    private void releaseTaskPermit(final TaskSlot slot) {
        if (!slot.taskPermitReleased) {
            slot.taskPermitReleased = true;
            this.taskPermits.release();
        }
    }

    private void trimTerminalHistory() {
        if (this.tasks.size() <= this.options.maxTasks()) {
            return;
        }
        List<TaskSlot> terminalSlots = new ArrayList<>();
        for (TaskSlot slot : this.tasks.values()) {
            synchronized (slot) {
                if (terminal(slot.state)) {
                    terminalSlots.add(slot);
                }
            }
        }
        terminalSlots.sort(Comparator
                .comparingInt((TaskSlot slot) -> terminalEvictionRank(slot.state))
                .thenComparing(slot -> slot.updatedAt)
                .thenComparing(slot -> slot.ref.taskId().value()));
        for (TaskSlot slot : terminalSlots) {
            if (this.tasks.size() <= this.options.maxTasks()) {
                return;
            }
            synchronized (slot) {
                if (terminal(slot.state)) {
                    this.tasks.remove(slot.ref.taskId(), slot);
                }
            }
        }
    }

    private static int terminalEvictionRank(final TaskState state) {
        if (state == TaskState.EXPIRED) {
            return 0;
        }
        if (state == TaskState.CANCELLED) {
            return 1;
        }
        if (state == TaskState.FAILED) {
            return 2;
        }
        return 3;
    }

    private void shutdownOwnedExecutor(final ExecutorService service) {
        service.shutdownNow();
        try {
            service.awaitTermination(EXECUTOR_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration durationOrDefault(final Duration duration) {
        return duration == null ? this.options.defaultLeaseDuration() : duration;
    }

    private static LeaseOwner owner(final String worker) {
        return new LeaseOwner("scheduler", worker, Map.of());
    }

    private static boolean sameOwner(final LeaseOwner left, final LeaseOwner right) {
        if (left == null || right == null) {
            return false;
        }
        return left.runtimeId().equals(right.runtimeId()) && left.workerId().equals(right.workerId());
    }

    private static TaskCancellationToken token(final TaskId taskId, final AtomicBoolean cancelled) {
        return new TaskCancellationToken() {
            @Override
            public TaskId taskId() {
                return taskId;
            }

            @Override
            public boolean cancellationRequested() {
                return cancelled.get();
            }
        };
    }

    private static final class TaskSlot {
        private final TaskRef ref;
        private final TaskSubmission submission;
        private final SecurityContext admissionContext;
        private final AtomicBoolean cancelled;
        private final Instant deadlineAt;
        private TaskState state;
        private TaskLease lease;
        private TaskProgress progress;
        private TaskResult result;
        private TaskFailure failure;
        private TaskCheckpoint checkpoint;
        private Instant updatedAt;
        private long nextCheckpointVersion;
        private boolean taskPermitReleased;

        private TaskSlot(final TaskRef ref, final TaskSubmission submission, final SecurityContext admissionContext, final Instant now, final Instant deadlineAt) {
            this.ref = ref;
            this.submission = submission;
            this.admissionContext = admissionContext;
            this.cancelled = new AtomicBoolean();
            this.deadlineAt = deadlineAt;
            this.state = TaskState.QUEUED;
            this.progress = new TaskProgress(0.0D, "queued", now, Map.of());
            this.updatedAt = now;
            this.nextCheckpointVersion = 1L;
        }

        private TaskStatus status() {
            return new TaskStatus(this.ref, this.state, this.progress, this.lease, this.result, this.failure, this.checkpoint, this.updatedAt,
                    Map.of("taskName", this.submission.descriptor().taskName(), "handler", this.submission.descriptor().handlerName()));
        }
    }

    private static final class SchedulerWorkloadHandle implements WorkloadHandle {
        private final InMemoryWorkloadScheduler scheduler;
        private final TaskRef ref;

        private SchedulerWorkloadHandle(final InMemoryWorkloadScheduler scheduler, final TaskRef ref) {
            this.scheduler = scheduler;
            this.ref = ref;
        }

        @Override
        public TaskRef ref() {
            return this.ref;
        }

        @Override
        public TaskStatus status() {
            return this.scheduler.status(this.ref.taskId());
        }

        @Override
        public void cancel() {
            this.scheduler.cancel(this.ref.taskId());
        }
    }

    private static final class SchedulerAsyncResult implements AsyncResult<Object> {
        private final TaskRef ref;
        private final Future<Object> future;
        private final InMemoryWorkloadScheduler scheduler;

        private SchedulerAsyncResult(final TaskRef ref, final Future<Object> future, final InMemoryWorkloadScheduler scheduler) {
            this.ref = ref;
            this.future = future;
            this.scheduler = scheduler;
        }

        @Override
        public TaskRef ref() {
            return this.ref;
        }

        @Override
        public Object await(final Duration timeout) {
            try {
                return this.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "async result deadline exceeded");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "async result wait interrupted");
            } catch (java.util.concurrent.ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof MiddlewareException middlewareException) {
                    throw middlewareException;
                }
                throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "async execution failed");
            }
        }

        @Override
        public void cancel() {
            this.scheduler.cancel(this.ref.taskId());
        }

        @Override
        public boolean done() {
            return this.future.isDone();
        }
    }

    private static final class QueueStreamEmitter<T> implements StreamEmitter<T> {
        private final QueueStreamState<T> state;

        private QueueStreamEmitter(final QueueStreamState<T> state) {
            this.state = state;
        }

        @Override
        public void emit(final T item, final Duration timeout) {
            this.state.emit(item, timeout);
        }
    }

    private static final class QueueStreamHandle<T> implements StreamHandle<T> {
        private final QueueStreamState<T> state;
        private final Future<?> future;
        private final AtomicLong cancellationCounter;

        private QueueStreamHandle(
                final QueueStreamState<T> state,
                final Future<?> future,
                final AtomicLong cancellationCounter) {
            this.state = state;
            this.future = future;
            this.cancellationCounter = cancellationCounter;
        }

        @Override
        public Optional<T> poll(final Duration timeout) {
            return this.state.poll(timeout);
        }

        @Override
        public void cancel() {
            if (this.state.cancel()) {
                this.cancellationCounter.incrementAndGet();
            }
            this.future.cancel(true);
        }

        @Override
        public boolean closed() {
            return this.state.closed();
        }

        @Override
        public void close() {
            this.cancel();
        }
    }

    private static final class QueueStreamState<T> {
        private final ArrayBlockingQueue<QueuedStreamItem<T>> queue;
        private final AtomicReference<StreamTerminal> terminal;
        private final AtomicBoolean closed;
        private final AtomicBoolean cancelled;
        private final AtomicLong queuedBytes;
        private final Object stateGate;
        private final int maxItemBytes;
        private final int maxQueueBytes;
        private final Clock clock;
        private final Instant deadlineAt;

        private QueueStreamState(
                final int itemCapacity,
                final int maxItemBytes,
                final int maxQueueBytes,
                final Clock clock,
                final Instant deadlineAt) {
            this.queue = new ArrayBlockingQueue<>(Math.max(1, itemCapacity));
            this.terminal = new AtomicReference<>();
            this.closed = new AtomicBoolean();
            this.cancelled = new AtomicBoolean();
            this.queuedBytes = new AtomicLong();
            this.stateGate = new Object();
            this.maxItemBytes = Math.max(1, maxItemBytes);
            this.maxQueueBytes = Math.max(1, maxQueueBytes);
            this.clock = clock;
            this.deadlineAt = deadlineAt;
        }

        private void emit(final T item, final Duration timeout) {
            int bytes = estimatedObjectBytes(item);
            if (bytes > this.maxItemBytes) {
                MiddlewareException failure = MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "stream item exceeds configured limit");
                this.fail(failure);
                throw failure;
            }
            boolean reserved = false;
            try {
                Duration wait = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
                long remainingNanos = Math.max(1L, wait.toNanos());
                long stopAt = System.nanoTime() + remainingNanos;
                synchronized (this.stateGate) {
                    this.requireWritable();
                    this.reserve(bytes);
                    reserved = true;
                }
                while (true) {
                    synchronized (this.stateGate) {
                        this.requireWritable();
                        if (this.queue.offer(new QueuedStreamItem<>(item, bytes))) {
                            reserved = false;
                            return;
                        }
                    }
                    remainingNanos = stopAt - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        MiddlewareException failure = MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "stream backpressure capacity exceeded");
                        synchronized (this.stateGate) {
                            if (reserved) {
                                this.release(bytes);
                                reserved = false;
                            }
                            this.failUnderGate(failure);
                        }
                        throw failure;
                    }
                    LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(1L), remainingNanos));
                    if (Thread.interrupted()) {
                        synchronized (this.stateGate) {
                            if (reserved) {
                                this.release(bytes);
                                reserved = false;
                            }
                            this.cancelUnderGate();
                        }
                        Thread.currentThread().interrupt();
                        throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream emit interrupted");
                    }
                }
            } catch (RuntimeException exception) {
                if (reserved) {
                    synchronized (this.stateGate) {
                        this.release(bytes);
                    }
                }
                throw exception;
            }
        }

        private Optional<T> poll(final Duration timeout) {
            if (this.closed.get() && this.queue.isEmpty()) {
                return Optional.empty();
            }
            try {
                Duration wait = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
                QueuedStreamItem<T> item = this.queue.poll(Math.max(1L, wait.toMillis()), TimeUnit.MILLISECONDS);
                if (item != null) {
                    this.release(item.bytes());
                    return Optional.ofNullable(item.value());
                }
                StreamTerminal currentTerminal = this.terminal.get();
                if (currentTerminal == null) {
                    return Optional.empty();
                }
                this.closed.set(true);
                if (currentTerminal.failure() != null) {
                    throw currentTerminal.failure();
                }
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream poll interrupted");
            }
        }

        private void complete() {
            synchronized (this.stateGate) {
                this.terminal.compareAndSet(null, new StreamTerminal(null));
            }
        }

        private void fail(final MiddlewareException failure) {
            synchronized (this.stateGate) {
                this.failUnderGate(failure);
            }
        }

        private boolean cancel() {
            synchronized (this.stateGate) {
                return this.cancelUnderGate();
            }
        }

        private boolean closed() {
            return this.closed.get();
        }

        private void requireWritable() {
            StreamTerminal currentTerminal = this.terminal.get();
            if (currentTerminal != null) {
                if (currentTerminal.failure() != null) {
                    throw currentTerminal.failure();
                }
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream is already complete");
            }
            if (this.cancelled.get() || this.closed.get()) {
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream is cancelled");
            }
            if (this.deadlineAt != null && !this.clock.instant().isBefore(this.deadlineAt)) {
                MiddlewareException failure = MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "stream deadline expired");
                this.failUnderGate(failure);
                throw failure;
            }
        }

        private void failUnderGate(final MiddlewareException failure) {
            this.terminal.compareAndSet(null, new StreamTerminal(failure));
        }

        private boolean cancelUnderGate() {
            boolean changed = this.cancelled.compareAndSet(false, true);
            MiddlewareException failure = MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream cancelled");
            this.terminal.compareAndSet(null, new StreamTerminal(failure));
            this.clearQueue();
            this.closed.set(true);
            return changed;
        }

        private void reserve(final int bytes) {
            while (true) {
                long current = this.queuedBytes.get();
                long next = current + bytes;
                if (next > this.maxQueueBytes) {
                    MiddlewareException failure = MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "stream queued byte capacity exceeded");
                    this.fail(failure);
                    throw failure;
                }
                if (this.queuedBytes.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        private void release(final int bytes) {
            this.queuedBytes.addAndGet(-bytes);
        }

        private void clearQueue() {
            QueuedStreamItem<T> item = this.queue.poll();
            while (item != null) {
                this.release(item.bytes());
                item = this.queue.poll();
            }
        }
    }

    private record QueuedStreamItem<T>(T value, int bytes) {
    }

    private record StreamTerminal(MiddlewareException failure) {
    }
}
