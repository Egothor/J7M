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

package org.j7m.middleware.integrations;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.consensus.NetworkConsensusNode;
import org.j7m.middleware.distributed.*;

/**
 * Concrete W3C/OpenTelemetry propagation, bounded OTLP/HTTP JSON export and Prometheus scrape.
 * Application operations never wait for the collector. Only fixed operation names and numeric
 * diagnostics are exported: no application payloads, principals, tokens, keys, baggage or vendor
 * tracestate are exported. Instances are thread-safe; span scopes must close on their creating thread.
 */
public final class Observability implements AutoCloseable {
    /**
     * Fixed low-cardinality operation vocabulary.
     */
    public enum Operation {
        /**
         * Remote invocation.
         */ RPC,
        /**
         * Queue publication.
         */ QUEUE_SEND,
        /**
         * Queue processing.
         */ QUEUE_CONSUME,
        /**
         * Durable job submission.
         */ JOB_SUBMIT,
        /**
         * Durable job execution.
         */ JOB_RUN,
        /**
         * Saga activity.
         */ SAGA_STEP,
        /**
         * Database relay.
         */ DB_OUTBOX,
        /**
         * Database consumer.
         */ DB_INBOX,
        /**
         * Fenced cluster lifecycle step.
         */ CLUSTER_STEP,
        /**
         * Optional annotated application work; distinct from middleware RPC.
         */ APPLICATION_HANDLER
    }
    private record Finished(String trace, String span, String parent, Operation operation, long start, long end, boolean error) { }
    private final URI collector;
    private final ArrayBlockingQueue<Finished> pending;
    private final AtomicLong dropped = new AtomicLong(), exported = new AtomicLong(), failures = new AtomicLong();
    private final Map<Operation, AtomicLong> operations = new EnumMap<>(Operation.class);
    private final AtomicLong jobs = new AtomicLong(), sagas = new AtomicLong(), unknownExecutions = new AtomicLong(), activeExecutions = new AtomicLong(), retainedExecutionRequests = new AtomicLong();
    private final AtomicLong quorum = new AtomicLong(), lag = new AtomicLong(), recovery = new AtomicLong();
    private final AtomicLong queueClaims = new AtomicLong(), queueAcks = new AtomicLong(), queueDlq = new AtomicLong(), observerFailures = new AtomicLong();
    private final AtomicLong lease = new AtomicLong(), migration = new AtomicLong(), dedupe = new AtomicLong();
    private final ScheduledExecutorService observers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "j7m-telemetry-observer"));
    private final HttpClient http;
    private final Thread exporter;
    private final HttpServer prometheus;
    private final SecureRandom random = new SecureRandom();
    private final int capacity;
    private volatile boolean closed;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier == null ? null : carrier.get(key); }
    };

    /**
     * Starts a concrete exporter and bounded scrape endpoint.
     * @param collector OTLP traces endpoint including /v1/traces; HTTPS or loopback HTTP
     * @param scrape bind address for /metrics, port zero selects a free port
     * @param capacity maximum queued spans in [1,65536]; one further export batch is bounded to 32
     * @throws IOException for scrape bind failure
     * @throws IllegalArgumentException for unsafe endpoint or invalid bounds
     */
    public Observability(URI collector, InetSocketAddress scrape, int capacity) throws IOException {
        Objects.requireNonNull(collector); Objects.requireNonNull(scrape);
        if (capacity < 1 || capacity > 65536 || collector.getHost() == null || collector.getUserInfo() != null
                || !Set.of("http", "https").contains(collector.getScheme())
                || collector.getScheme().equals("http") && !InetAddress.getByName(collector.getHost()).isLoopbackAddress())
            throw new IllegalArgumentException("Invalid telemetry endpoint/capacity");
        this.collector = collector; this.capacity = capacity; pending = new ArrayBlockingQueue<>(capacity);
        for (Operation operation : Operation.values()) operations.put(operation, new AtomicLong());
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).followRedirects(HttpClient.Redirect.NEVER).build();
        prometheus = HttpServer.create(scrape, 16);
        prometheus.createContext("/metrics", exchange -> {
            if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/metrics")) {
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            byte[] bytes = scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length); try (var body = exchange.getResponseBody()) { body.write(bytes); }
            finally { exchange.close(); }
        });
        prometheus.setExecutor(new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                r -> daemon(r, "j7m-prometheus"), new ThreadPoolExecutor.AbortPolicy()));
        prometheus.start(); exporter = daemon(this::exportLoop, "j7m-otlp-export"); exporter.start();
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }

    /**
     * Captures the current official OpenTelemetry context without baggage.
     * @return bounded W3C carrier
     */
    public TraceCarrier capture() {
        Map<String, String> headers = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, Map::put);
        if (headers.isEmpty()) headers.putAll(org.j7m.middleware.application.api.InvocationTraceContext.current());
        return TraceCarrier.fromMap(headers);
    }
    /**
     * Attaches extracted remote context for callbacks on the current thread.
     * @param carrier validated W3C context
     * @return scope that restores the previous thread context when closed
     */
    public Scope attach(TraceCarrier carrier) {
        var trace = org.j7m.middleware.application.api.InvocationTraceContext.open(carrier.asMap());
        final Scope scope;
        try { scope = context(carrier).makeCurrent(); }
        catch (RuntimeException failure) { trace.close(); throw failure; }
        final Thread owner = Thread.currentThread();
        final var detached = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Trace attachment belongs to creating thread");
            if (detached.compareAndSet(false, true)) { try { scope.close(); } finally { trace.close(); } }
        };
    }
    private Context context(TraceCarrier carrier) { return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier.asMap(), GETTER); }
    /**
     * Starts an interoperable child span using an explicit parent.
     * @param operation fixed non-sensitive operation name
     * @param parent remote/persisted parent carrier, or empty to start a trace
     * @return active span scope; close it to enqueue export
     */
    public SpanScope start(Operation operation, TraceCarrier parent) {
        Objects.requireNonNull(operation); Objects.requireNonNull(parent);
        SpanContext previous = Span.fromContext(context(parent)).getSpanContext();
        String trace = previous.isValid() ? previous.getTraceId() : identifier(16);
        String span = identifier(8); SpanContext child = SpanContext.create(trace, span, TraceFlags.getSampled(), TraceState.getDefault());
        Scope scope = attach(new TraceCarrier("00-" + child.getTraceId() + "-" + child.getSpanId() + "-01", ""));
        return new SpanScope(operation, child, previous.isValid() ? previous.getSpanId() : "", scope);
    }
    /**
     * Starts a child of the current thread's context.
     * @param operation fixed non-sensitive operation
     * @return active span scope
     */
    public SpanScope start(Operation operation) { return start(operation, capture()); }
    /**
     * Creates a thread-safe span lifetime independent of individual context attachment scopes.
     * Each attachment must be closed on its creating thread; end may run on the physical completion worker.
     * @param operation fixed low-cardinality operation
     * @param parent validated parent carrier
     * @return owned span handle; export is nonblocking and bounded when ended
     */
    public DetachedSpan startDetached(Operation operation, TraceCarrier parent) {
        Objects.requireNonNull(operation); Objects.requireNonNull(parent);
        SpanContext previous = Span.fromContext(context(parent)).getSpanContext();
        String trace = previous.isValid() ? previous.getTraceId() : identifier(16);
        SpanContext child = SpanContext.create(trace, identifier(8), TraceFlags.getSampled(), TraceState.getDefault());
        return new DetachedSpan(operation, child, previous.isValid() ? previous.getSpanId() : "");
    }

    /**
     * Thread-safe application span lifetime. It carries no throwable, application value or identity labels.
     */
    public final class DetachedSpan {
        private final Operation operation;
        private final SpanContext span;
        private final String parent;
        private final long start = epochNanos();
        private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean error = new java.util.concurrent.atomic.AtomicBoolean();
        private DetachedSpan(Operation operation, SpanContext span, String parent) { this.operation = operation; this.span = span; this.parent = parent; }
        /**
         * Returns immutable W3C propagation data without vendor state.
         * @return bounded child carrier
         */
        public TraceCarrier carrier() { return new TraceCarrier("00-" + span.getTraceId() + "-" + span.getSpanId() + "-01", ""); }
        /**
         * Attaches this span on the current thread, independently of its eventual end.
         * @return thread-confined idempotent attachment
         */
        public Scope attach() { return Observability.this.attach(carrier()); }
        /**
         * Marks failure without retaining an application exception or message.
         */
        public void failed() { error.set(true); }
        /**
         * Ends once on any host thread and offers export without waiting for the collector.
         */
        public void end() {
            if (!ended.compareAndSet(false, true)) return;
            operations.get(operation).incrementAndGet();
            if (closed || !pending.offer(new Finished(span.getTraceId(), span.getSpanId(), parent, operation, start, epochNanos(), error.get()))) dropped.incrementAndGet();
        }
    }

    private String identifier(int size) { byte[] bytes = new byte[size]; random.nextBytes(bytes);
        boolean nonzero = false; for (byte value : bytes) nonzero |= value != 0; if (!nonzero) bytes[bytes.length - 1] = 1;
        return HexFormat.of().formatHex(bytes); }

    /**
     * Thread-confined span lifetime. Closing is idempotent; errors never include exception text.
     */
    public final class SpanScope implements AutoCloseable {
        private final Operation operation;
        private final SpanContext span;
        private final String parent;
        private final Scope scope;
        private final long start = epochNanos();
        private final Thread owner = Thread.currentThread();
        private boolean done, error;
        private SpanScope(Operation operation, SpanContext span, String parent, Scope scope) { this.operation = operation; this.span = span; this.parent = parent; this.scope = scope; }
        /**
         * Marks the span failed without retaining any throwable or sensitive message.
         */
        public void failed() { error = true; }
        /**
         * Returns this span's propagation metadata.
         * @return bounded W3C child carrier
         */
        public TraceCarrier carrier() { return new TraceCarrier("00-" + span.getTraceId() + "-" + span.getSpanId() + "-01", ""); }
        /**
         * Restores context and performs a nonblocking bounded export offer.
         * @throws IllegalStateException when called from a different thread
         */
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Span scope belongs to creating thread");
            if (done) return; done = true; scope.close(); operations.get(operation).incrementAndGet();
            if (closed || !pending.offer(new Finished(span.getTraceId(), span.getSpanId(), parent, operation, start, epochNanos(), error))) dropped.incrementAndGet();
        }
    }
    private static long epochNanos() { Instant now = Instant.now(); return now.getEpochSecond() * 1_000_000_000L + now.getNano(); }

    /**
     * Supplies automatic trace lifetimes for the production execution RPC/job/saga facades.
     * Configure the same client with {@code () -> capture().asMap()} as its carrier supplier.
     * Business fingerprints exclude propagation metadata, so a new retry span never changes work identity.
     * @return nonblocking observer using only fixed operation names
     */
    public ExecutionClient.Observer executionObserver() {
        return operation -> {
            Operation kind = operation.startsWith("R_") || operation.startsWith("E_") ? Operation.RPC : operation.startsWith("S_") ? Operation.SAGA_STEP
                    : operation.equals("J_SUBMIT") ? Operation.JOB_SUBMIT : Operation.JOB_RUN;
            SpanScope span = start(kind); span.failed();
            return new ExecutionClient.Observation() {
                @Override public void completed(ExecutionResult result) {
                    span.error = Set.of("UNKNOWN", "CONFLICT", "REQUEST_CONFLICT", "OVERLOADED", "UNAVAILABLE", "FAILED", "ERROR", "FENCED").contains(result.status());
                }
                @Override public void close() { span.close(); }
            };
        };
    }
    /**
     * Executes a claimed job callback under the original persisted producer context.
     * This helper does not acknowledge/complete the job: the worker must separately commit its
     * result and protected effects through DurableJobs using the current ownership fence.
     * @param claimed actual durable RUNNING job claim
     * @param callback application job work; payloads and exception messages are never exported
     * @param <T> application result type
     * @return callback result
     * @throws Exception if validation or application execution fails
     */
    public <T> T runJob(ExecutionResult claimed, Callable<T> callback) throws Exception {
        if (!claimed.status().equals("RUNNING") || !claimed.fields().getOrDefault("kind", "JOB").equals("JOB")) throw new IllegalArgumentException("Live JOB claim required");
        try (SpanScope span = start(Operation.JOB_RUN, TraceCarrier.fromMap(claimed.context()))) {
            try { return callback.call(); } catch (Exception failure) { span.failed(); throw failure; }
        }
    }
    /**
     * Polls real scope-authorized execution counters without exporting identities.
     * @param client authenticated execution client
     * @return observation cancellation handle
     */
    public AutoCloseable watchExecution(ExecutionClient client) {
        var task = observers.scheduleWithFixedDelay(() -> {
            try {
                ExecutionResult result = client.statistics(Instant.now().plusMillis(250));
                dedupe.set(Long.parseLong(result.fields().getOrDefault("rpcReplays", "0")));
                jobs.set(Long.parseLong(result.fields().getOrDefault("jobs", "0"))); sagas.set(Long.parseLong(result.fields().getOrDefault("sagas", "0")));
                unknownExecutions.set(Long.parseLong(result.fields().getOrDefault("unknown", "0"))); activeExecutions.set(Long.parseLong(result.fields().getOrDefault("active", "0")));
                retainedExecutionRequests.set(Long.parseLong(result.fields().getOrDefault("retainedRequests", "0")));
            }
            catch (IOException | RuntimeException unavailable) { observerFailures.incrementAndGet(); }
        }, 0, 500, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }
    /**
     * Registers real group progress and periodically tests quorum outside data/scrape paths.
     * @param node hosted consensus group
     * @return handle cancelling observation without closing the node
     */
    public AutoCloseable watchConsensus(NetworkConsensusNode node) {
        if (node.recoveredIndex() > 0) recovery.incrementAndGet();
        ScheduledFuture<?> task = observers.scheduleWithFixedDelay(() -> {
            var state = node.status(); lag.set(Math.max(0, state.commitIndex() - state.appliedIndex()));
            try { node.readIndex(Instant.now().plusMillis(250)); quorum.set(1); }
            catch (IOException | RuntimeException unavailable) { quorum.set(0); observerFailures.incrementAndGet(); }
        }, 0, 500, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }
    /**
     * Registers real lease observations without exporting the resource key or owner.
     * @param endpoint authorized lease endpoint
     * @param key resource key used only for the query, never a metric label
     * @return handle cancelling this observation
     */
    public AutoCloseable watchLease(ResourceEndpoint endpoint, String key) {
        var task = observers.scheduleWithFixedDelay(() -> {
            try { lease.set(endpoint.lease(key, Instant.now().plusMillis(250)) == null ? 0 : 1); }
            catch (IOException | RuntimeException unavailable) { lease.set(0); observerFailures.incrementAndGet(); }
        }, 0, 500, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }
    /**
     * Wraps a real partition transport and observes committed migration/status responses.
     * @param endpoint authorized local or remote partition transport
     * @return instrumented transport with identical mutation semantics
     */
    public PartitionEndpoint observe(PartitionEndpoint endpoint) {
        return new PartitionEndpoint() {
            @Override public PartitionReply execute(long epoch, String requestId, byte[] command, byte[] query, Instant deadline) throws IOException {
                try (SpanScope span = start(Operation.RPC)) {
                    try {
                        PartitionReply result = endpoint.execute(epoch, requestId, command, query, deadline);
                        if (Set.of("MOVED", "FROZEN").contains(result.status())) migration.incrementAndGet();
                        if (!result.status().equals("OK")) span.failed();
                        return result;
                    } catch (IOException | RuntimeException failure) { span.failed(); throw failure; }
                }
            }
            @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException { return endpoint.query(epoch, query, deadline); }
            @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException { return endpoint.status(deadline); }
        };
    }
    void queueClaimed() { queueClaims.incrementAndGet(); }
    void queueAcknowledged() { queueAcks.incrementAndGet(); }
    void queueDeadLetters(int pageSize) { queueDlq.set(pageSize); }
    /**
     * Exposes the actual scrape address.
     * @return bound host and port
     */
    public InetSocketAddress address() { return prometheus.getAddress(); }
    /**
     * Returns bounded exporter occupancy for health diagnostics.
     * @return queued spans, never above configured capacity
     */
    public int buffered() { return pending.size(); }
    /**
     * Returns the configured queue limit.
     * @return maximum queued spans
     */
    public int capacity() { return capacity; }
    /**
     * Returns the cumulative number of spans discarded on overload/collector failure.
     * @return dropped span count
     */
    public long dropped() { return dropped.get(); }
    /**
     * Produces Prometheus text with a fixed vocabulary and no user-controlled labels.
     * @return numeric diagnostic exposition
     */
    public String scrape() {
        StringBuilder text = new StringBuilder();
        operations.forEach((operation, value) -> text.append("j7m_operations_total{operation=\"").append(operation.name().toLowerCase(Locale.ROOT)).append("\"} ").append(value.get()).append('\n'));
        text.append("j7m_telemetry_dropped_total ").append(dropped.get()).append('\n').append("j7m_telemetry_exported_total ").append(exported.get()).append('\n')
                .append("j7m_telemetry_failures_total ").append(failures.get()).append('\n').append("j7m_telemetry_buffered ").append(pending.size()).append('\n')
                .append("j7m_local_leader_quorum_available ").append(quorum.get()).append('\n').append("j7m_apply_lag ").append(lag.get()).append('\n')
                .append("j7m_active_leases ").append(lease.get()).append('\n').append("j7m_dedupe_replays_total ").append(dedupe.get()).append('\n')
                .append("j7m_recovered_groups_total ").append(recovery.get()).append('\n').append("j7m_migration_redirects_total ").append(migration.get()).append('\n');
        text.append("j7m_queue_claims_total ").append(queueClaims.get()).append('\n').append("j7m_queue_acknowledged_total ").append(queueAcks.get()).append('\n')
                .append("j7m_queue_dlq_page_size ").append(queueDlq.get()).append('\n').append("j7m_observer_failures_total ").append(observerFailures.get()).append('\n');
        text.append("j7m_retained_jobs ").append(jobs.get()).append('\n').append("j7m_retained_sagas ").append(sagas.get()).append('\n')
                .append("j7m_execution_unknown ").append(unknownExecutions.get()).append('\n').append("j7m_execution_active ").append(activeExecutions.get()).append('\n')
                .append("j7m_execution_retained_requests ").append(retainedExecutionRequests.get()).append('\n');
        return text.toString();
    }
    private void exportLoop() {
        while (!closed) {
            List<Finished> batch = new ArrayList<>(32);
            try {
                Finished first = pending.poll(100, TimeUnit.MILLISECONDS); if (first == null) continue;
                batch.add(first); pending.drainTo(batch, 31);
                HttpRequest request = HttpRequest.newBuilder(collector).timeout(Duration.ofMillis(500)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(otlp(batch))).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status >= 200 && status < 300) exported.addAndGet(batch.size()); else { failures.incrementAndGet(); dropped.addAndGet(batch.size()); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); if (!batch.isEmpty()) dropped.addAndGet(batch.size()); return; }
            catch (IOException | RuntimeException unavailable) { failures.incrementAndGet(); dropped.addAndGet(batch.size()); }
        }
    }
    private static String otlp(List<Finished> batch) {
        StringJoiner spans = new StringJoiner(",");
        for (Finished span : batch) spans.add("{\"traceId\":\"" + span.trace + "\",\"spanId\":\"" + span.span + "\",\"parentSpanId\":\"" + span.parent
                + "\",\"name\":\"" + span.operation.name().toLowerCase(Locale.ROOT) + "\",\"kind\":1,\"startTimeUnixNano\":\"" + span.start
                + "\",\"endTimeUnixNano\":\"" + span.end + "\",\"status\":{\"code\":" + (span.error ? 2 : 1) + "}}");
        return "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"j7m\"}}]},\"scopeSpans\":[{\"scope\":{\"name\":\"org.j7m.middleware\"},\"spans\":[" + spans + "]}]}]}";
    }
    /**
     * Stops telemetry without waiting for an unavailable collector; queued spans are counted dropped.
     */
    @Override public void close() {
        if (closed) return; closed = true; observers.shutdownNow(); prometheus.stop(0);
        var executor = (ExecutorService) prometheus.getExecutor(); executor.shutdownNow();
        List<Finished> abandoned = new ArrayList<>(capacity); pending.drainTo(abandoned); dropped.addAndGet(abandoned.size());
        exporter.interrupt(); http.shutdownNow();
    }
}
