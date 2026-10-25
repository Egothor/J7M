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

package org.j7m.middleware.distributed;

import java.io.*;
import java.net.Socket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Host-only level-triggered queue dispatcher. Commit callbacks signal only; they never perform I/O.
 */
final class QueuePushHub implements AutoCloseable {
    private final NetworkConsensusNode node;
    private final PartitionStateMachine partition;
    private final QueueStateMachine state;
    private final ReplicatedResourceClient.ClockHealth clock;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Runnable change = this::signal;
    private final Semaphore wake = new Semaphore(0);
    private final java.util.concurrent.atomic.AtomicLong claims = new java.util.concurrent.atomic.AtomicLong(), reads = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger reservedBytes = new AtomicInteger();
    private final Thread dispatcher;
    private volatile boolean closed;
    QueuePushHub(NetworkConsensusNode node, PartitionStateMachine partition, ReplicatedResourceClient.ClockHealth clock) {
        this.node = node; this.partition = partition; this.clock = clock; state = partition.queueResource();
        if (state == null) throw new IllegalArgumentException("Not a queue partition");
        partition.addChangeListener(change);
        dispatcher = worker("queue-push-dispatch", this::run); dispatcher.start();
    }
    private static Thread worker(String name, Runnable action) {
        Thread thread = Thread.ofVirtual().name(name).inheritInheritableThreadLocals(false).unstarted(action);
        thread.setContextClassLoader(QueuePushHub.class.getClassLoader()); return thread;
    }
    private void signal() { if (wake.availablePermits() == 0) wake.release(); }
    void open(Socket socket, String nonce, PartitionServer.Binding binding, byte[] payload, Instant deadline) throws IOException {
        var in = PartitionStateMachine.input(payload);
        long epoch = in.readLong(); String queue = in.readUTF(), group = in.readUTF(), member = in.readUTF(); long visibility = in.readLong(); boolean observational = in.readBoolean(); PartitionStateMachine.end(in);
        QueueStateMachine.validate(new ResourceCommand("Q_CLAIM", binding.scope(), "validation", List.of(queue, group, member, "1", Long.toString(visibility), "0")));
        node.readIndex(deadline);
        if (closed || !Set.of("ACTIVE", "COPYING").contains(partition.status().phase()) || partition.status().epoch() != epoch) throw new IOException("Queue ownership unavailable");
        String identity = binding.scope() + '\0' + queue + '\0' + group + '\0' + member;
        Session session = new Session(identity, socket, nonce, binding, epoch, queue, group, member, visibility, observational);
        synchronized (sessions) {
            if (closed || sessions.size() >= 64 || sessions.putIfAbsent(identity, session) != null) throw new IOException("Queue session capacity/duplicate member");
        }
        try {
            byte[] ready = ResourceWire.encode(out -> { out.writeUTF(nonce); out.writeInt(0); PartitionStateMachine.writeBytes(out, new byte[0]); });
            ResourceWire.send(session.output, ready, binding.key()); socket.setSoTimeout(0);
            session.writer.start(); session.reader.start(); signal();
        } catch (IOException | RuntimeException failure) { session.close(); throw failure; }
    }
    private void run() {
        try {
            long wait = Long.MAX_VALUE;
            while (!closed) {
                if (wait == Long.MAX_VALUE) wake.acquire(); else wake.tryAcquire(Math.max(1, wait), TimeUnit.MILLISECONDS);
                if (closed) break;
                wait = Long.MAX_VALUE;
                for (Session session : sessions.values()) {
                    if (session.closed) continue;
                    var ownership = partition.status();
                    if (ownership.epoch() != session.epoch || !Set.of("ACTIVE", "COPYING").contains(ownership.phase())) { session.close(); continue; }
                    if (!session.hasCredit()) continue;
                    long now = System.currentTimeMillis();
                    try { deliver(session, now); }
                    catch (IOException | RuntimeException unavailable) { session.close(); }
                    long expiry = session.observational ? Long.MAX_VALUE : state.nextExpiry(session.binding.scope(), session.queue, now);
                    if (expiry > now && expiry != Long.MAX_VALUE) wait = Math.min(wait, expiry - now);
                }
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
    private void deliver(Session session, long now) throws IOException {
        boolean pending = session.observational ? state.deadLetterAfter(session.binding.scope(), session.queue, session.lastSequence) : state.pending(session.binding.scope(), session.queue, now);
        String token = state.resumeToken(session.binding.scope(), session.queue, session.group, session.member, now);
        boolean resume = !session.observational && !token.isEmpty() && !session.alreadySent(token);
        if (!pending && !resume || !session.reserve()) return;
        boolean queued = false;
        try {
            Instant deadline = Instant.now().plusSeconds(5);
            var endpoint = new LocalPartitionEndpoint(node, partition, session.binding.scope(), session.binding.maintenance(), clock);
            var facade = new ReplicatedQueue(partition.status().partition(), session.binding.scope(), (p, d) -> new ReplicatedQueue.Route(endpoint, session.epoch));
            if (session.observational) {
                reads.incrementAndGet();
                var query = new ResourceCommand("Q_DLQ_NEXT", session.binding.scope(), "dlq-observation", List.of(session.queue, Long.toString(session.lastSequence)));
                var reply = endpoint.query(session.epoch, query.encode(), deadline);
                if (!reply.status().equals("OK")) throw new IOException("Queue moved");
                String value = new String(reply.payload(), java.nio.charset.StandardCharsets.UTF_8);
                if (!value.isEmpty()) {
                    QueueDelivery d = QueueSessionWire.parse(session.queue, session.group, session.member, 1, value);
                    queued = session.offer(new QueueDelivery(d.queue(), d.group(), d.member(), d.generation(), d.messageId(), d.payload(), d.sequence(), d.receipt(), d.attempt(), true));
                }
                return;
            }
            if (resume) {
                var measured = clock.sample();
                if (!measured.monotonicStable() || measured.uncertainty() < 0 || measured.uncertainty() > 500 || Math.abs((double) measured.time() - measured.reference()) > 500) throw new IOException("Unhealthy queue clock");
                node.appendControl(PartitionStateMachine.mutation(session.epoch, partition.timeCommand(measured.time())), deadline);
                var query = new ResourceCommand("Q_RECEIPT", session.binding.scope(), "resume", List.of(session.queue, session.group, session.member));
                reads.incrementAndGet();
                PartitionReply reply = endpoint.query(session.epoch, query.encode(), deadline);
                if (!reply.status().equals("OK")) throw new IOException("Queue moved");
                String value = new String(reply.payload(), java.nio.charset.StandardCharsets.UTF_8);
                if (!value.isEmpty()) {
                    QueueDelivery delivery = QueueSessionWire.parse(session.queue, session.group, session.member, 1, value);
                    if (!session.same(delivery)) { queued = session.offer(delivery); return; }
                }
                if (!pending) return;
            }
            reads.incrementAndGet();
            List<String> assignment = facade.assignment(session.group, session.member, deadline);
            long generation;
            if (assignment.isEmpty()) {
                String joined = facade.join(UUID.randomUUID().toString(), session.group, session.member, Math.max(1000, session.visibility), deadline);
                if (!joined.startsWith("OK\t")) throw new IOException("Queue membership rejected");
                generation = Long.parseLong(joined.substring(3));
            } else {
                if (!assignment.contains(session.queue)) return;
                generation = Long.parseLong(assignment.getFirst());
            }
            claims.incrementAndGet();
            String claimed = facade.claim(UUID.randomUUID().toString(), session.queue, session.group, session.member, generation, session.visibility, false, deadline);
            if (claimed.startsWith("MESSAGE\t")) queued = session.offer(QueueSessionWire.parse(session.queue, session.group, session.member, generation, claimed));
        } finally { if (!queued) session.release(true); }
    }
    PartitionServer.QueuePushStatistics statistics() { return new PartitionServer.QueuePushStatistics(sessions.size(), reservedBytes.get(), claims.get(), reads.get()); }
    @Override public void close() {
        closed = true; partition.removeChangeListener(change); signal(); dispatcher.interrupt();
        for (Session session : sessions.values()) session.close();
    }
    private final class Session implements AutoCloseable {
        final String identity, nonce, queue, group, member;
        final Socket socket;
        final PartitionServer.Binding binding;
        final long epoch, visibility; final boolean observational;
        volatile long lastSequence;
        final DataInputStream input;
        final DataOutputStream output;
        final ArrayBlockingQueue<QueueDelivery> backlog = new ArrayBlockingQueue<>(4);
        final Thread reader, writer;
        int credits, reserved;
        String last = "";
        volatile boolean closed;
        Session(String identity, Socket socket, String nonce, PartitionServer.Binding binding, long epoch,
                String queue, String group, String member, long visibility, boolean observational) throws IOException {
            this.identity = identity; this.socket = socket; this.nonce = nonce; this.binding = binding; this.epoch = epoch;
            this.queue = queue; this.group = group; this.member = member; this.visibility = visibility; this.observational = observational;
            input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream());
            reader = worker("queue-push-credit", this::read); writer = worker("queue-push-write", this::write);
        }
        synchronized boolean hasCredit() { return !closed && credits > 0; }
        synchronized boolean reserve() {
            if (closed || credits <= 0) return false;
            for (;;) {
                int current = reservedBytes.get(); if (current > 16 * 1024 * 1024 - QueueSessionWire.MAX_FRAME) return false;
                if (reservedBytes.compareAndSet(current, current + QueueSessionWire.MAX_FRAME)) break;
            }
            credits--; reserved++; return true;
        }
        synchronized void release(boolean restoreCredit) {
            reserved--; reservedBytes.addAndGet(-QueueSessionWire.MAX_FRAME);
            if (restoreCredit && !closed) credits++;
            notifyAll();
            if (!restoreCredit) signal();
        }
        synchronized boolean alreadySent(String token) { return last.equals(token); }
        synchronized boolean same(QueueDelivery delivery) { return last.equals(token(delivery)); }
        private String token(QueueDelivery delivery) { return delivery.messageId() + '\0' + delivery.generation() + ':' + delivery.receipt(); }
        synchronized boolean offer(QueueDelivery delivery) {
            if (closed || !backlog.offer(delivery)) return false;
            last = token(delivery); if (observational) lastSequence = delivery.sequence(); return true;
        }
        private void read() {
            try {
                long sequence = 1;
                while (!closed) {
                    var in = PartitionStateMachine.input(QueueSessionWire.receive(input, binding.key(), nonce, sequence++));
                    if (!in.readUTF().equals("CREDIT")) throw new IOException("Invalid queue session operation");
                    int n = in.readInt(); PartitionStateMachine.end(in);
                    synchronized (this) {
                        if (n < 1 || n > 4) throw new IOException("Queue credit bounds");
                        while (!closed && credits + reserved + n > 4 && reserved > 0) wait();
                        if (closed) return;
                        if (credits + reserved + n > 4) throw new IOException("Queue credit bounds");
                        credits += n;
                    }
                    signal();
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); close(); }
            catch (IOException | RuntimeException failure) { close(); }
        }
        private void write() {
            try {
                long sequence = 1;
                while (!closed) {
                    QueueDelivery delivery = backlog.take();
                    try { QueueSessionWire.send(output, binding.key(), nonce, sequence++, out -> { out.writeUTF("DELIVERY"); QueueSessionWire.write(out, delivery); }); }
                    finally { release(false); }
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); close(); }
            catch (IOException | RuntimeException failure) { close(); }
        }
        @Override public void close() {
            synchronized (this) {
                if (closed) return; closed = true; notifyAll();
                while (backlog.poll() != null) release(false);
            }
            sessions.remove(identity, this); reader.interrupt(); writer.interrupt();
            try { socket.close(); } catch (IOException alreadyClosed) {
                System.getLogger(QueuePushHub.class.getName()).log(System.Logger.Level.DEBUG,
                        "Terminal queue session close failed; durable grant retained: {0}", alreadyClosed.getClass().getName());
            }
            signal();
        }
    }
}
