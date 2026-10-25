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
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/**
 * Shared authenticated session setup, bounded separately from established credit-controlled flows.
 */
final class PartitionSessionHandshake {
    private static final Semaphore CAPACITY = new Semaphore(256);
    record Connection(Socket socket, String nonce) { }
    private PartitionSessionHandshake() { }
    static Connection open(List<InetSocketAddress> addresses, String principal, byte[] key, SSLContext tls,
            String operation, byte[] payload, Instant deadline) throws IOException {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new IOException("Session deadline expired");
        long budget = remaining.compareTo(Duration.ofSeconds(30)) > 0 ? 30_000_000_000L : remaining.toNanos();
        long started = System.nanoTime(); IOException last = null;
        for (InetSocketAddress address : addresses) {
                long left = budget - (System.nanoTime() - started); if (left <= 0) break;
                if (!CAPACITY.tryAcquire()) throw new IOException("OVERLOADED: session handshake capacity");
                Socket socket;
                try { socket = tls == null ? new Socket() : tls.getSocketFactory().createSocket(); }
                catch (IOException failed) { CAPACITY.release(); throw failed; }
                var settled = new AtomicBoolean();
                boolean transferred = false;
                Thread watchdog = Thread.ofVirtual().name("partition-session-deadline").inheritInheritableThreadLocals(false).unstarted(() -> {
                    try {
                        try { Thread.sleep(Duration.ofNanos(Math.max(1, budget - (System.nanoTime() - started)))); }
                        catch (InterruptedException finished) { Thread.currentThread().interrupt(); return; }
                        if (settled.compareAndSet(false, true)) socket.close();
                    } catch (IOException terminal) { settled.set(true); }
                    finally { CAPACITY.release(); }
                });
                watchdog.setContextClassLoader(PartitionSessionHandshake.class.getClassLoader()); watchdog.start();
                try {
                    int timeout = (int) Math.max(1, Math.min(5000, left / 1_000_000));
                    socket.connect(address, timeout); socket.setSoTimeout(timeout); socket.setTcpNoDelay(true);
                    if (socket instanceof SSLSocket secure) {
                        SSLParameters parameters = secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); secure.setSSLParameters(parameters); secure.startHandshake();
                    }
                    String nonce = UUID.randomUUID().toString(); long expiry = System.currentTimeMillis() + Math.max(1, (budget - (System.nanoTime() - started)) / 1_000_000);
                    byte[] request = ResourceWire.encode(out -> { out.writeInt(1); out.writeUTF(principal); out.writeUTF(nonce); out.writeLong(expiry); out.writeUTF(operation); PartitionStateMachine.writeBytes(out, payload); });
                    ResourceWire.send(new DataOutputStream(socket.getOutputStream()), request, key);
                    var input = new DataInputStream(socket.getInputStream()); byte[] response = ResourceWire.receive(input); ResourceWire.authenticate(input, response, key);
                    var decoded = PartitionStateMachine.input(response);
                    if (!decoded.readUTF().equals(nonce) || decoded.readInt() != 0 || PartitionStateMachine.bytes(decoded, ResourceWire.MAX_FRAME).length != 0) throw new IOException("Reactive session rejected");
                    PartitionStateMachine.end(decoded);
                    if (budget - (System.nanoTime() - started) <= 0) throw new IOException("Session deadline expired");
                    socket.setSoTimeout(0);
                    if (!settled.compareAndSet(false, true)) throw new IOException("Session deadline expired");
                    transferred = true; return new Connection(socket, nonce);
                } catch (IOException unavailable) { last = unavailable; }
                finally { settled.set(true); watchdog.interrupt(); if (!transferred) socket.close(); }
        }
        throw new IOException("Reactive session unavailable before original deadline", last);
    }
}
