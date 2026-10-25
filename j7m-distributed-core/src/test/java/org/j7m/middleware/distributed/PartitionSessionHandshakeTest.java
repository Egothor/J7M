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

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PartitionSessionHandshakeTest {
    @Test void authenticatedTransferKeepsTheSocketOpenAndForgedResponseNeverTransfersIt() throws Exception {
        for (boolean corrupt : List.of(false, true)) try (var listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress()); var server = Executors.newSingleThreadExecutor()) {
            var release = new CountDownLatch(1);
            var response = server.submit(() -> {
                try (var socket = listener.accept()) {
                    var input = new DataInputStream(socket.getInputStream()); byte[] command = ResourceWire.receive(input); ResourceWire.authenticate(input, command, new byte[32]);
                    var decoded = PartitionStateMachine.input(command); assertEquals(1, decoded.readInt()); assertEquals("app", decoded.readUTF()); String nonce = decoded.readUTF();
                    byte[] frame = ResourceWire.encode(out -> { out.writeUTF(nonce); out.writeInt(0); PartitionStateMachine.writeBytes(out, new byte[0]); });
                    byte[] key = new byte[32]; if (corrupt) Arrays.fill(key, (byte) 1);
                    ResourceWire.send(new DataOutputStream(socket.getOutputStream()), frame, key);
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                }
                return null;
            });
            var addresses = List.of(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
            try {
                if (corrupt) assertThrows(IOException.class, () -> PartitionSessionHandshake.open(addresses, "app", new byte[32], null, "QUEUE_SESSION", new byte[0], Instant.now().plusSeconds(2)));
                else {
                    var connection = PartitionSessionHandshake.open(addresses, "app", new byte[32], null, "QUEUE_SESSION", new byte[0], Instant.now().plusSeconds(2));
                    try (var socket = connection.socket()) { assertFalse(socket.isClosed()); assertFalse(connection.nonce().isEmpty()); assertEquals(0, socket.getSoTimeout()); }
                }
            } finally { release.countDown(); response.get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void originalDeadlineBoundsBlackholedPlainAndTlsHandshakesWithoutAnotherAttemptBudget() throws Exception {
        for (boolean tls : List.of(false, true)) try (var listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress()); var server = Executors.newSingleThreadExecutor()) {
            var release = new CountDownLatch(1); var accepted = new CountDownLatch(1);
            var stalled = server.submit(() -> { try (var socket = listener.accept()) { accepted.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); } return null; });
            try {
                long start = System.nanoTime();
                assertThrows(IOException.class, () -> PartitionSessionHandshake.open(List.of(new InetSocketAddress("127.0.0.1", listener.getLocalPort())),
                        "app", new byte[32], tls ? SSLContext.getDefault() : null, "QUEUE_SESSION", new byte[0], Instant.now().plusMillis(100)));
                assertTrue(accepted.await(1, TimeUnit.SECONDS)); assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
                assertThrows(IOException.class, () -> PartitionSessionHandshake.open(List.of(), "app", new byte[32], null, "QUEUE_SESSION", new byte[0], Instant.EPOCH));
            } finally { release.countDown(); stalled.get(3, TimeUnit.SECONDS); }
        }
    }
}
