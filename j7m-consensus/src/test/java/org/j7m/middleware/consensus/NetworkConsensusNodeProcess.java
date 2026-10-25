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

package org.j7m.middleware.consensus;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Child JVM fixture. Stdout carries correlated control replies only.
 */
public final class NetworkConsensusNodeProcess {
    private NetworkConsensusNodeProcess() { }
    public static void main(String[] args) throws Exception {
        String id = args[0]; Map<String, InetSocketAddress> peers = new HashMap<>();
        for (String peer : args[2].split(",")) { String[] pair = peer.split(":"); peers.put(pair[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(pair[1]))); }
        Counter state = new Counter();
        var config = new NetworkConsensusConfig(id, peers, Set.of(args[3].split(",")), Path.of(args[1]),
                new byte[32], Duration.ofMillis(600), Duration.ofMillis(60), 1 << 20, 64 << 20, null, "test-cluster");
        try (NetworkConsensusNode node = new NetworkConsensusNode(config, state); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var armed = new java.util.concurrent.atomic.AtomicReference<String>();
            node.faultInjector(point -> {
                String target = armed.get();
                if (point.equals(target) && armed.compareAndSet(target, null)) {
                    synchronized (System.out) { System.out.println("FAULT " + point); System.out.flush(); }
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            node.start(); System.out.println("READY"); System.out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in)); String line;
            while ((line = input.readLine()) != null) {
                String command = line;
                workers.submit(() -> {
                    String[] parts = command.split(" "); String result;
                    try {
                        result = switch (parts[1]) {
                            case "ARM" -> { armed.set(parts[2]); yield "ARMED"; }
                            case "STATUS" -> { var s = node.status(); yield s.leaderId() + "," + s.term() + "," + s.commitIndex() + "," + s.appliedIndex() + "," + s.configEpoch() + "," + String.join(";", new TreeSet<>(s.members())) + "," + s.jointMembers().size(); }
                            case "APPEND" -> Long.toString(node.append(parts[2], ByteBuffer.allocate(8).putLong(Long.parseLong(parts[3])).array(), Instant.now().plusSeconds(15)));
                            case "READ" -> Long.toString(node.readIndex(Instant.now().plusSeconds(3)));
                            case "CONFIG" -> Long.toString(node.reconfigure(Long.parseLong(parts[2]), Set.of(parts[3].split(",")), Instant.now().plusSeconds(15)));
                            case "SNAPSHOT" -> Long.toString(node.snapshot());
                            case "STATE" -> state.text();
                            default -> throw new IllegalArgumentException("unknown control");
                        };
                        synchronized (System.out) { System.out.println(parts[0] + " OK " + result); System.out.flush(); }
                    } catch (Throwable e) { synchronized (System.out) { System.out.println(parts[0] + " ERR " + e.getMessage()); System.out.flush(); } }
                });
            }
        }
    }
    static final class Counter implements ReplicatedStateMachine {
        long count, sum, last;
        @Override public synchronized void apply(long index, byte[] command) {
            if (index <= last) throw new IllegalStateException("duplicate apply");
            count++; sum += ByteBuffer.wrap(command).getLong(); last = index;
        }
        @Override public synchronized byte[] snapshot() { return ByteBuffer.allocate(24).putLong(count).putLong(sum).putLong(last).array(); }
        @Override public synchronized void restore(byte[] bytes) {
            if (bytes.length != 24) throw new IllegalArgumentException("invalid counter snapshot");
            ByteBuffer buffer = ByteBuffer.wrap(bytes); count = buffer.getLong(); sum = buffer.getLong(); last = buffer.getLong();
        }
        synchronized String text() { return count + "," + sum + "," + last; }
    }
}
