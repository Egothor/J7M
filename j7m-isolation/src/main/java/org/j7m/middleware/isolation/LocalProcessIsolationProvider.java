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

package org.j7m.middleware.isolation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.isolation.CapturedOutput;
import org.j7m.middleware.api.isolation.EnvironmentPolicy;
import org.j7m.middleware.api.isolation.IsolationExecutionHandle;
import org.j7m.middleware.api.isolation.IsolationExecutionId;
import org.j7m.middleware.api.isolation.IsolationExecutionRequest;
import org.j7m.middleware.api.isolation.IsolationExecutionResult;
import org.j7m.middleware.api.isolation.IsolationExecutionState;
import org.j7m.middleware.api.isolation.IsolationExecutionStatus;
import org.j7m.middleware.api.isolation.IsolationException;
import org.j7m.middleware.api.isolation.IsolationMode;
import org.j7m.middleware.api.isolation.IsolationPolicyDecision;
import org.j7m.middleware.api.isolation.IsolationProviderId;
import org.j7m.middleware.api.isolation.IsolationProviderType;
import org.j7m.middleware.api.isolation.RedactedOutput;
import org.j7m.middleware.api.isolation.ResourceBudget;
import org.j7m.middleware.api.isolation.ResourceUsageSnapshot;

/**
 * Local child-process isolation provider.
 *
 * <p>The provider is thread-safe and owns every child process it starts. It
 * validates working-directory, command-line, environment, runtime, stdout, and
 * stderr limits before and during execution. It does not provide a full
 * operating-system sandbox, cgroups, namespaces, Docker, Kubernetes, or OCI
 * runtime behavior.</p>
 */
public final class LocalProcessIsolationProvider extends AbstractIsolationProvider {
    private static final int HARD_MAX_ACTIVE_PROCESSES = 256;
    private static final int HARD_MAX_CAPTURE_WORKERS = HARD_MAX_ACTIVE_PROCESSES * 2;
    private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(2L);
    private final Object lifecycleLock;
    private final Map<IsolationExecutionId, ProcessIsolationExecutionHandle> activeHandles;
    private final int maxActiveProcesses;
    private final int maxCaptureWorkers;
    private int reservedProcesses;
    private int activeCaptureWorkers;

    /**
     * Creates a local process provider.
     *
     * @param id provider identifier
     * @param config provider configuration
     */
    public LocalProcessIsolationProvider(final IsolationProviderId id, final IsolationConfig config) {
        super(id, IsolationProviderType.LOCAL_PROCESS, config);
        this.maxActiveProcesses = HARD_MAX_ACTIVE_PROCESSES;
        this.maxCaptureWorkers = HARD_MAX_CAPTURE_WORKERS;
        this.lifecycleLock = new Object();
        this.activeHandles = new LinkedHashMap<>();
        this.reservedProcesses = 0;
        this.activeCaptureWorkers = 0;
    }

    LocalProcessIsolationProvider(
            final IsolationProviderId id,
            final IsolationConfig config,
            final int maxActiveProcesses,
            final int maxCaptureWorkers) {
        super(id, IsolationProviderType.LOCAL_PROCESS, config);
        if (maxActiveProcesses <= 0 || maxActiveProcesses > HARD_MAX_ACTIVE_PROCESSES
                || maxCaptureWorkers <= 0 || maxCaptureWorkers > HARD_MAX_CAPTURE_WORKERS) {
            throw new IllegalArgumentException("local process isolation hard caps are invalid");
        }
        this.maxActiveProcesses = maxActiveProcesses;
        this.maxCaptureWorkers = maxCaptureWorkers;
        this.lifecycleLock = new Object();
        this.activeHandles = new LinkedHashMap<>();
        this.reservedProcesses = 0;
        this.activeCaptureWorkers = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<IsolationMode> supportedModes() {
        return Set.of(IsolationMode.LOCAL_PROCESS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IsolationExecutionHandle start(final IsolationExecutionRequest request) {
        this.ensureOpen();
        if (request.mode() != IsolationMode.LOCAL_PROCESS) {
            throw new IsolationException(MiddlewareErrorCode.INVALID_ARGUMENT, "unsupported isolation mode",
                    Map.of("mode", request.mode().name()));
        }
        if (request.processSpec() == null) {
            throw new IsolationException(MiddlewareErrorCode.INVALID_ARGUMENT, "process specification is required", Map.of());
        }
        final int commandBytes = String.join(" ", request.processSpec().command()).getBytes(StandardCharsets.UTF_8).length;
        if (commandBytes > request.budget().maxCommandLineBytes()) {
            this.resourceRejected.incrementAndGet();
            this.audit(request.subject(), "resource.command_line", "command", "DENIED",
                    "RESOURCE_COMMAND_LINE_LIMIT", request.correlationId());
            throw new IsolationException(MiddlewareErrorCode.OVERLOADED, "command line exceeds isolation budget",
                    Map.of("limit", Integer.toString(request.budget().maxCommandLineBytes())));
        }
        boolean slotOwned = false;
        ProcessCapacityReservation reservation = null;
        try {
            this.acquireSlot(request);
            slotOwned = true;
            final IsolationPolicyDecision cwdDecision = this.evaluateFileSystem(request.subject(), request.policy().fileSystemPolicy(),
                    org.j7m.middleware.api.isolation.FileSystemAccess.READ, request.processSpec().workingDirectory());
            if (!cwdDecision.allowed()) {
                throw new IsolationException(MiddlewareErrorCode.PERMISSION_DENIED, "working directory denied by filesystem policy",
                        Map.of("reasonCode", cwdDecision.reasonCode()));
            }
            final Map<String, String> environment = this.sanitizedEnvironment(request.subject(), request.policy().environmentPolicy(),
                    request.processSpec().environment(), request.budget(), request.correlationId());
            final IsolationExecutionId executionId = IsolationExecutionId.random();
            final Instant started = Instant.now(this.config.clock());
            this.audit(request.subject(), "isolation.execution.requested", this.redactor.safeCommand(request.processSpec().command()),
                    "ALLOWED", "EXECUTION_REQUESTED", request.correlationId());
            this.audit(request.subject(), "process.starting", this.redactor.safeCommand(request.processSpec().command()),
                    "STARTING", "PROCESS_STARTING", request.correlationId());
            reservation = this.reserveProcessCapacity();
            final ProcessBuilder builder = new ProcessBuilder(request.processSpec().command());
            builder.directory(request.processSpec().workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            final Process process = builder.start();
            this.audit(request.subject(), "process.started", Long.toString(process.pid()), "RUNNING", "PROCESS_STARTED", request.correlationId());
            final ProcessIsolationExecutionHandle handle = new ProcessIsolationExecutionHandle(this, request, executionId, process, started);
            try {
                this.attachReservedHandle(reservation, handle);
                reservation = null;
            } catch (IsolationException exception) {
                handle.cancelFromProviderClose();
                throw exception;
            }
            try {
                handle.startSupervision();
            } catch (RuntimeException exception) {
                handle.cancelFromProviderClose();
                this.unregisterHandle(handle);
                throw new IsolationException(MiddlewareErrorCode.INTERNAL, "process supervision start failed",
                        Map.of("command", this.redactor.safeCommand(request.processSpec().command())));
            }
            slotOwned = false;
            return handle;
        } catch (final IOException e) {
            this.failedExecutions.incrementAndGet();
            this.audit(request.subject(), "process.failed", "start", "FAILED", "PROCESS_START_FAILED", request.correlationId());
            throw new IsolationException(MiddlewareErrorCode.INTERNAL, "process start failed",
                    Map.of("command", this.redactor.safeCommand(request.processSpec().command())));
        } finally {
            if (reservation != null) {
                this.releaseReservation(reservation);
            }
            if (slotOwned) {
                this.releaseSlot();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.closed.compareAndSet(false, true);
        final List<ProcessIsolationExecutionHandle> handles;
        synchronized (this.lifecycleLock) {
            handles = new ArrayList<>(this.activeHandles.values());
        }
        for (ProcessIsolationExecutionHandle handle : handles) {
            handle.cancelFromProviderClose();
        }
        final long deadlineNanos = System.nanoTime() + CLOSE_DRAIN_TIMEOUT.toNanos();
        boolean interrupted = false;
        for (ProcessIsolationExecutionHandle handle : handles) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                boolean completed = handle.awaitClose(Duration.ofNanos(remainingNanos));
                if (!completed) {
                    handle.recordCloseCleanupFailure();
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                handle.recordCloseCleanupFailure();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ProcessCapacityReservation reserveProcessCapacity() {
        synchronized (this.lifecycleLock) {
            if (this.closed.get()) {
                throw new IsolationException(MiddlewareErrorCode.UNAVAILABLE, "isolation provider is closed",
                        Map.of("provider", this.id.value()));
            }
            if (this.activeHandles.size() + this.reservedProcesses >= this.maxActiveProcesses
                    || this.activeCaptureWorkers + 2 > this.maxCaptureWorkers) {
                this.resourceRejected.incrementAndGet();
                throw new IsolationException(MiddlewareErrorCode.OVERLOADED, "local process isolation capacity exceeded",
                        Map.of("limit", Integer.toString(this.maxActiveProcesses)));
            }
            this.reservedProcesses++;
            this.activeCaptureWorkers += 2;
            return new ProcessCapacityReservation();
        }
    }

    private void attachReservedHandle(
            final ProcessCapacityReservation reservation,
            final ProcessIsolationExecutionHandle handle) {
        synchronized (this.lifecycleLock) {
            if (reservation == null || reservation.released || reservation.attached) {
                throw new IsolationException(MiddlewareErrorCode.INTERNAL, "local process isolation reservation is invalid", Map.of());
            }
            this.reservedProcesses = Math.max(0, this.reservedProcesses - 1);
            if (this.closed.get()) {
                this.activeCaptureWorkers = Math.max(0, this.activeCaptureWorkers - 2);
                reservation.released = true;
                throw new IsolationException(MiddlewareErrorCode.UNAVAILABLE, "isolation provider is closed",
                        Map.of("provider", this.id.value()));
            }
            this.activeHandles.put(handle.executionId(), handle);
            reservation.attached = true;
        }
    }

    private void releaseReservation(final ProcessCapacityReservation reservation) {
        synchronized (this.lifecycleLock) {
            if (!reservation.attached && !reservation.released) {
                this.reservedProcesses = Math.max(0, this.reservedProcesses - 1);
                this.activeCaptureWorkers = Math.max(0, this.activeCaptureWorkers - 2);
                reservation.released = true;
            }
        }
    }

    private void unregisterHandle(final ProcessIsolationExecutionHandle handle) {
        synchronized (this.lifecycleLock) {
            if (this.activeHandles.remove(handle.executionId()) != null) {
                this.activeCaptureWorkers = Math.max(0, this.activeCaptureWorkers - 2);
            }
        }
    }

    private static final class ProcessCapacityReservation {
        private boolean attached;
        private boolean released;
    }

    private Map<String, String> sanitizedEnvironment(
            final org.j7m.middleware.api.isolation.IsolationSubject subject,
            final EnvironmentPolicy policy,
            final Map<String, String> requested,
            final ResourceBudget budget,
            final String correlationId) {
        if (requested.size() > budget.maxEnvironmentVariables()) {
            this.resourceRejected.incrementAndGet();
            this.audit(subject, "resource.environment_count", "environment", "DENIED", "RESOURCE_ENVIRONMENT_COUNT_LIMIT", correlationId);
            throw new IsolationException(MiddlewareErrorCode.OVERLOADED, "environment variable count exceeds isolation budget",
                    Map.of("limit", Integer.toString(budget.maxEnvironmentVariables())));
        }
        final LinkedHashMap<String, String> accepted = new LinkedHashMap<>();
        int bytes = 0;
        for (final Map.Entry<String, String> entry : requested.entrySet()) {
            final IsolationPolicyDecision decision = this.evaluateEnvironment(subject, policy, entry.getKey(), entry.getValue());
            if (!decision.allowed()) {
                this.audit(subject, "environment.denied", entry.getKey(), "DENIED", decision.reasonCode(), "");
                continue;
            }
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            bytes += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            final int effectiveLimit = Math.min(policy.maxEnvironmentBytes(), budget.maxEnvironmentBytes());
            if (bytes > effectiveLimit) {
                this.resourceRejected.incrementAndGet();
                this.audit(subject, "resource.environment_size", "environment", "DENIED", "RESOURCE_ENVIRONMENT_SIZE_LIMIT", correlationId);
                throw new IsolationException(MiddlewareErrorCode.OVERLOADED, "environment size exceeds isolation budget",
                        Map.of("limit", Integer.toString(effectiveLimit)));
            }
            accepted.put(entry.getKey(), entry.getValue());
            if (decision.metadata().containsValue("<redacted>")) {
                this.redactionEvents.incrementAndGet();
            }
        }
        return accepted;
    }

    static final class ProcessIsolationExecutionHandle implements IsolationExecutionHandle {
        private final LocalProcessIsolationProvider provider;
        private final IsolationExecutionRequest request;
        private final IsolationExecutionId executionId;
        private final Process process;
        private final Instant startedAt;
        private final CountDownLatch done;
        private final AtomicReference<IsolationExecutionResult> result;
        private final AtomicReference<IsolationExecutionState> state;
        private final AtomicBoolean killedRecorded;
        private final AtomicBoolean cancellationRecorded;
        private final AtomicBoolean closeCleanupFailureRecorded;
        private final LimitedCapture stdout;
        private final LimitedCapture stderr;
        private volatile Thread stdoutThread;
        private volatile Thread stderrThread;
        private volatile Thread supervisorThread;
        private volatile Instant completedAt;

        ProcessIsolationExecutionHandle(
                final LocalProcessIsolationProvider provider,
                final IsolationExecutionRequest request,
                final IsolationExecutionId executionId,
                final Process process,
                final Instant startedAt) {
            this.provider = provider;
            this.request = request;
            this.executionId = executionId;
            this.process = process;
            this.startedAt = startedAt;
            this.done = new CountDownLatch(1);
            this.result = new AtomicReference<>();
            this.state = new AtomicReference<>(IsolationExecutionState.RUNNING);
            this.killedRecorded = new AtomicBoolean(false);
            this.cancellationRecorded = new AtomicBoolean(false);
            this.closeCleanupFailureRecorded = new AtomicBoolean(false);
            this.stdout = new LimitedCapture(Math.min(
                    request.policy().stdoutPolicy().capture().maxBytes(),
                    request.budget().maxStdoutBytes()));
            this.stderr = new LimitedCapture(Math.min(
                    request.policy().stderrPolicy().capture().maxBytes(),
                    request.budget().maxStderrBytes()));
        }

        void startSupervision() {
            final Thread outThread = new Thread(() -> this.stdout.read(this.process.getInputStream()), "j7m-isolation-stdout");
            final Thread errThread = new Thread(() -> this.stderr.read(this.process.getErrorStream()), "j7m-isolation-stderr");
            final Thread supervisor = new Thread(() -> this.supervise(outThread, errThread), "j7m-isolation-supervisor");
            this.stdoutThread = outThread;
            this.stderrThread = errThread;
            this.supervisorThread = supervisor;
            outThread.start();
            errThread.start();
            supervisor.start();
        }

        private void supervise(final Thread outThread, final Thread errThread) {
            IsolationExecutionState finalState = IsolationExecutionState.COMPLETED;
            int exitCode = -1;
            try {
                final long timeoutMillis = this.request.timeout().toMillis();
                final boolean exited = this.process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!exited) {
                    finalState = IsolationExecutionState.TIMED_OUT;
                    if (this.state.compareAndSet(IsolationExecutionState.RUNNING, IsolationExecutionState.TIMED_OUT)) {
                        this.provider.timedOutExecutions.incrementAndGet();
                        this.provider.audit(this.request.subject(), "process.timed_out", Long.toString(this.process.pid()),
                                "TIMED_OUT", "DEADLINE_EXCEEDED", this.request.correlationId());
                    }
                    this.destroyProcess();
                }
                if (this.process.isAlive()) {
                    finalState = IsolationExecutionState.FAILED;
                } else {
                    exitCode = this.process.waitFor();
                }
                if (this.state.get() == IsolationExecutionState.CANCELLED) {
                    finalState = IsolationExecutionState.CANCELLED;
                } else if (this.state.get() == IsolationExecutionState.TIMED_OUT) {
                    finalState = IsolationExecutionState.TIMED_OUT;
                }
                outThread.join(1000L);
                errThread.join(1000L);
                if (finalState == IsolationExecutionState.COMPLETED && exitCode != 0) {
                    finalState = IsolationExecutionState.FAILED;
                    this.provider.failedExecutions.incrementAndGet();
                    this.provider.audit(this.request.subject(), "process.failed", Integer.toString(exitCode),
                            "FAILED", "PROCESS_EXIT_NON_ZERO", this.request.correlationId());
                } else if (finalState == IsolationExecutionState.COMPLETED) {
                    this.provider.completedExecutions.incrementAndGet();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                finalState = IsolationExecutionState.CANCELLED;
                if (this.cancellationRecorded.compareAndSet(false, true)) {
                    this.provider.cancelledExecutions.incrementAndGet();
                }
                this.destroyProcess();
            } finally {
                this.complete(finalState, exitCode);
            }
        }

        private boolean destroyProcess() {
            if (this.process.isAlive()) {
                this.process.destroy();
                try {
                    if (!this.process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                        this.process.destroyForcibly();
                        this.recordKilled();
                        if (!this.process.waitFor(1000L, TimeUnit.MILLISECONDS)) {
                            this.closeProcessStreams();
                            return false;
                        }
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.process.destroyForcibly();
                    this.recordKilled();
                    return false;
                } finally {
                    this.closeProcessStreams();
                }
                return !this.process.isAlive();
            }
            this.closeProcessStreams();
            return true;
        }

        private void closeProcessStreams() {
            try {
                this.process.getOutputStream().close();
            } catch (final IOException ignored) {
                // Best-effort stream close during process termination.
            }
            try {
                this.process.getInputStream().close();
            } catch (final IOException ignored) {
                // Best-effort stream close during process termination.
            }
            try {
                this.process.getErrorStream().close();
            } catch (final IOException ignored) {
                // Best-effort stream close during process termination.
            }
        }

        private void recordKilled() {
            if (this.killedRecorded.compareAndSet(false, true)) {
                this.provider.killedExecutions.incrementAndGet();
                this.provider.audit(this.request.subject(), "process.killed", Long.toString(this.process.pid()),
                        "KILLED", "PROCESS_KILLED", this.request.correlationId());
            }
        }

        private void complete(final IsolationExecutionState finalState, final int exitCode) {
            this.completedAt = Instant.now(this.provider.config.clock());
            this.state.set(finalState);
            final String stdoutText = new String(this.stdout.bytes(), StandardCharsets.UTF_8);
            final String stderrText = new String(this.stderr.bytes(), StandardCharsets.UTF_8);
            final String safeOut = this.provider.redactor.redactText(stdoutText, this.request.policy().stdoutPolicy().capture());
            final String safeErr = this.provider.redactor.redactText(stderrText, this.request.policy().stderrPolicy().capture());
            final boolean outRedacted = !safeOut.equals(stdoutText);
            final boolean errRedacted = !safeErr.equals(stderrText);
            if (outRedacted || errRedacted || this.stdout.truncated() || this.stderr.truncated()) {
                this.provider.redactionEvents.incrementAndGet();
                this.provider.audit(this.request.subject(), "output.redacted", "stdout/stderr", "REDACTED",
                        this.stdout.truncated() || this.stderr.truncated() ? "OUTPUT_TRUNCATED" : "OUTPUT_REDACTED",
                        this.request.correlationId());
            }
            final CapturedOutput output = new CapturedOutput(new RedactedOutput(safeOut, outRedacted),
                    new RedactedOutput(safeErr, errRedacted), this.stdout.observedBytes(), this.stderr.observedBytes(),
                    this.stdout.truncated(), this.stderr.truncated());
            final ResourceUsageSnapshot usage = new ResourceUsageSnapshot(Duration.between(this.startedAt, this.completedAt),
                    this.stdout.observedBytes(), this.stderr.observedBytes(), 0);
            final IsolationExecutionResult finalResult = new IsolationExecutionResult(this.executionId, finalState, exitCode,
                    output, usage, List.of(), Map.of("provider", this.provider.id().value()));
            this.result.set(finalResult);
            this.provider.audit(this.request.subject(), "process.completed", Integer.toString(exitCode), finalState.name(),
                    finalState.name(), this.request.correlationId());
            if (this.process.isAlive()) {
                this.provider.audit(this.request.subject(), "cleanup.failed", Long.toString(this.process.pid()),
                        "DEGRADED", "PROCESS_STILL_ALIVE", this.request.correlationId());
            } else {
                this.provider.audit(this.request.subject(), "cleanup.completed", "process", "CLEANED_UP", "CLEANED_UP",
                        this.request.correlationId());
                this.provider.releaseSlot();
                this.provider.unregisterHandle(this);
            }
            this.done.countDown();
        }

        @Override
        public IsolationExecutionId executionId() {
            return this.executionId;
        }

        @Override
        public IsolationExecutionStatus status() {
            return new IsolationExecutionStatus(this.executionId, this.request.subject().id(), this.request.mode(), this.state.get(),
                    this.startedAt, this.completedAt, this.provider.redactor.safeCommand(this.request.processSpec().command()),
                    Map.of("pid", Long.toString(this.process.pid())));
        }

        @Override
        public IsolationExecutionResult await(final Duration timeout) {
            boolean finished = false;
            try {
                final Duration actualTimeout = timeout == null ? this.request.timeout() : timeout;
                finished = this.done.await(actualTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!finished && this.process.isAlive()) {
                if (this.state.compareAndSet(IsolationExecutionState.RUNNING, IsolationExecutionState.TIMED_OUT)) {
                    this.provider.timedOutExecutions.incrementAndGet();
                    this.provider.audit(this.request.subject(), "process.timed_out", Long.toString(this.process.pid()),
                            "TIMED_OUT", "DEADLINE_EXCEEDED", this.request.correlationId());
                }
                this.destroyProcess();
                try {
                    this.done.await(2L, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return this.result();
        }

        @Override
        public IsolationExecutionResult cancel() {
            this.recordCancellation("process.cancelled", "CANCELLED");
            this.destroyProcess();
            return this.await(Duration.ofSeconds(2L));
        }

        @Override
        public IsolationExecutionResult result() {
            final IsolationExecutionResult current = this.result.get();
            if (current != null) {
                return current;
            }
            return new IsolationExecutionResult(this.executionId, this.state.get(), -1,
                    new CapturedOutput(null, null, 0, 0, false, false),
                    new ResourceUsageSnapshot(Duration.between(this.startedAt, Instant.now(this.provider.config.clock())), 0, 0, 0),
                    List.of(), Map.of("provider", this.provider.id().value()));
        }

        void cancelFromProviderClose() {
            this.recordCancellation("process.provider_close_cancelled", "PROVIDER_CLOSING");
            this.destroyProcess();
        }

        boolean awaitClose(final Duration timeout) throws InterruptedException {
            final long timeoutMillis = Math.max(1L, timeout.toMillis());
            this.done.await(timeoutMillis, TimeUnit.MILLISECONDS);
            this.joinIfAlive(this.supervisorThread, Math.min(250L, timeoutMillis));
            this.joinIfAlive(this.stdoutThread, Math.min(250L, timeoutMillis));
            this.joinIfAlive(this.stderrThread, Math.min(250L, timeoutMillis));
            return this.done.getCount() == 0L
                    && !this.process.isAlive()
                    && !this.isAlive(this.supervisorThread)
                    && !this.isAlive(this.stdoutThread)
                    && !this.isAlive(this.stderrThread);
        }

        void recordCloseCleanupFailure() {
            this.closeProcessStreams();
            this.destroyProcess();
            this.interruptIfAlive(this.supervisorThread);
            this.interruptIfAlive(this.stdoutThread);
            this.interruptIfAlive(this.stderrThread);
            if (this.closeCleanupFailureRecorded.compareAndSet(false, true)) {
                this.provider.audit(this.request.subject(), "cleanup.close_timeout", Long.toString(this.process.pid()),
                        "DEGRADED", "CLOSE_DEADLINE_EXCEEDED", this.request.correlationId());
            }
        }

        private void recordCancellation(final String action, final String reasonCode) {
            final IsolationExecutionState current = this.state.get();
            if (current == IsolationExecutionState.RUNNING
                    && this.state.compareAndSet(IsolationExecutionState.RUNNING, IsolationExecutionState.CANCELLED)
                    && this.cancellationRecorded.compareAndSet(false, true)) {
                this.provider.cancelledExecutions.incrementAndGet();
                this.provider.audit(this.request.subject(), action, Long.toString(this.process.pid()),
                        "CANCELLED", reasonCode, this.request.correlationId());
            }
        }

        private void joinIfAlive(final Thread thread, final long millis) throws InterruptedException {
            if (thread != null && thread.isAlive()) {
                thread.join(millis);
            }
        }

        private boolean isAlive(final Thread thread) {
            return thread != null && thread.isAlive();
        }

        private void interruptIfAlive(final Thread thread) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
    }

    static final class LimitedCapture {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer;
        private int observedBytes;
        private boolean truncated;

        LimitedCapture(final int maxBytes) {
            this.maxBytes = maxBytes;
            this.buffer = new ByteArrayOutputStream(Math.max(32, Math.min(1024, maxBytes)));
        }

        void read(final InputStream stream) {
            final byte[] chunk = new byte[256];
            try (InputStream input = stream) {
                while (true) {
                    final int read = input.read(chunk);
                    if (read < 0) {
                        break;
                    }
                    this.observedBytes += read;
                    final int remaining = this.maxBytes - this.buffer.size();
                    if (remaining > 0) {
                        this.buffer.write(chunk, 0, Math.min(remaining, read));
                    }
                    if (read > remaining) {
                        this.truncated = true;
                    }
                }
            } catch (final IOException e) {
                this.truncated = true;
            }
        }

        byte[] bytes() {
            return this.buffer.toByteArray();
        }

        int observedBytes() {
            return this.observedBytes;
        }

        boolean truncated() {
            return this.truncated;
        }
    }
}
