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
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Shared authenticated bounded push subscription. The server sends a complete committed delivery;
 * requesting credit never performs a client-side claim/fetch. Only one subscriber is supported.
 * Subscriber callbacks are serialized on a dedicated middleware virtual thread. A callback must
 * remain bounded; closing the session closes network I/O but cannot forcibly stop application code.
 * No error means ACK: acknowledgment uses the exact delivery through ReplicatedQueue separately.
 * @param <T> immutable bounded decoded delivery type
 */
final class FlowPushSession<T> implements Flow.Publisher<T>, AutoCloseable {
    private static final System.Logger LOG=System.getLogger(FlowPushSession.class.getName());
    @FunctionalInterface interface Decoder<V> { V read(DataInputStream input) throws IOException; }
    private final Decoder<T> decoder;
    private final int maximum;
    private final Socket socket;
    private final byte[] key;
    private final String nonce;
    private final DataInputStream input;
    private final DataOutputStream output;
    private Flow.Subscriber<? super T> subscriber;
    private long demand, outgoing = 1;
    private int outstanding;
    private volatile boolean closed;

    FlowPushSession(Socket socket, byte[] key, String nonce, Decoder<T> decoder) throws IOException {
        this(socket,key,nonce,decoder,QueueSessionWire.MAX_FRAME);
    }
    FlowPushSession(Socket socket, byte[] key, String nonce, Decoder<T> decoder, int maximum) throws IOException {
        this.decoder=Objects.requireNonNull(decoder);this.maximum=maximum;
        if(maximum!=QueueSessionWire.MAX_FRAME && maximum!=262144)throw new IOException("Session profile bounds");
        this.socket = socket; this.key = key.clone(); this.nonce = nonce;
        input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream());
    }
    /**
     * Registers one serialized subscriber; demand is constrained to four outstanding server credits.
     * @param next nonnull subscriber receiving complete immutable deliveries
     * @throws IllegalStateException if already subscribed or closed
     */
    @Override public synchronized void subscribe(Flow.Subscriber<? super T> next) {
        Objects.requireNonNull(next);
        if (subscriber != null || closed) throw new IllegalStateException("Queue session unavailable");
        subscriber = next;
        try {
            next.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    if (n <= 0) { fail(new IllegalArgumentException("Demand must be positive")); return; }
                    synchronized (FlowPushSession.this) {
                        if (closed) return;
                        demand = n > Long.MAX_VALUE - demand ? Long.MAX_VALUE : demand + n;
                        try { replenish(); } catch (IOException failure) { fail(failure); }
                    }
                }
                @Override public void cancel() { close(); }
            });
        } catch (RuntimeException callbackFailure) { close(); throw callbackFailure; }
        Thread reader = Thread.ofVirtual().name("resource-push-client").inheritInheritableThreadLocals(false).unstarted(this::read);
        reader.setContextClassLoader(FlowPushSession.class.getClassLoader()); reader.start();
    }
    private synchronized void replenish() throws IOException {
        if (closed) return;
        int credit = (int) Math.min(4 - outstanding, demand - outstanding);
        if (credit <= 0) return;
        QueueSessionWire.send(output, key, nonce, outgoing++, maximum, out -> { out.writeUTF("CREDIT"); out.writeInt(credit); });
        outstanding += credit;
    }
    private void read() {
        Thread.currentThread().setContextClassLoader(FlowPushSession.class.getClassLoader());
        try {
            long sequence = 1;
            while (!closed) {
                var in = PartitionStateMachine.input(QueueSessionWire.receive(input, key, nonce, sequence++, maximum));
                if (!in.readUTF().equals("DELIVERY")) throw new IOException("Queue session rejected");
                T delivery = decoder.read(in); PartitionStateMachine.end(in);
                synchronized (this) {
                    if (outstanding <= 0 || demand <= 0) throw new IOException("Delivery without demand");
                    outstanding--; if (demand != Long.MAX_VALUE) demand--;
                }
                subscriber.onNext(delivery);
                replenish();
            }
        } catch (IOException | RuntimeException failure) { if (!closed) fail(failure); }
    }
    private void fail(Throwable failure) {
        Flow.Subscriber<? super T> target;
        synchronized (this) { if (closed) return; target = subscriber; close(); }
        if (target != null) try { target.onError(failure); } catch (RuntimeException ignoredCallback) { LOG.log(System.Logger.Level.DEBUG,"Push subscriber error signal failed ({0})",ignoredCallback.getClass().getName()); }
    }
    /**
     * Reports transport cancellation; it is not proof that an application handler physically exited.
     * @return whether this session's socket is closed
     */
    public boolean isClosed() { return closed; }
    /**
     * Cancels future delivery and closes network resources; unacknowledged durable receipts remain.
     * This method is idempotent and does not interrupt an already running subscriber callback.
     */
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        try { socket.close(); } catch (IOException ignoredClose) { LOG.log(System.Logger.Level.DEBUG,"Terminal push socket close failed ({0})",ignoredClose.getClass().getName()); }
    }
}
