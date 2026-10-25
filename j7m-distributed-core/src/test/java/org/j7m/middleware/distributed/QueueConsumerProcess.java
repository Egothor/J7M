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
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Independent consumer JVM fixture; output contains only confirmed acknowledgements.
 */
public final class QueueConsumerProcess {
    /**
     * Processes assigned queues until the controller stops it.
     * @param args member id, comma-separated service ports, output file, hold-first-delivery flag
     * @throws Exception on an unrecoverable fixture error
     */
    public static void main(String[] args) throws Exception {
        List<InetSocketAddress> endpoints = new ArrayList<>();
        for (String port : args[1].split(",")) endpoints.add(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)));
        PartitionClient endpoint = new PartitionClient(endpoints, "app", new byte[32]);
        ReplicatedQueue queue = new ReplicatedQueue("queue-partition", "tenant", (p, d) -> new ReplicatedQueue.Route(endpoint, 1));
        String member = args[0], incarnation = UUID.randomUUID().toString(); long sequence = 0; boolean hold = Boolean.parseBoolean(args[3]);
        try (var log = Files.newBufferedWriter(Path.of(args[2]), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (true) {
                List<String> assignment;
                try { assignment = queue.assignment("workers", member, Instant.now().plusSeconds(5)); }
                catch (IOException retry) { Thread.onSpinWait(); continue; }
                if (assignment.isEmpty()) { Thread.onSpinWait(); continue; }
                long generation = Long.parseLong(assignment.getFirst());
                for (String name : assignment.subList(1, assignment.size())) {
                    String request = member + ":" + incarnation + ":claim:" + sequence++;
                    String response = retry(() -> queue.claim(request, name, "workers", member, generation, 60000, Instant.now().plusSeconds(5)));
                    if (!response.startsWith("MESSAGE\t")) continue;
                    String[] delivery = response.split("\t");
                    if (hold) {
                        publishHeld(Path.of(args[2] + ".held"), response, () -> { }); System.out.println("HELD"); System.out.flush();
                        new java.util.concurrent.CountDownLatch(1).await();
                    }
                    String ack = retry(() -> queue.ack(member + ":ack:" + delivery[1] + ":" + delivery[4], name,
                            "workers", member, generation, delivery[1], Long.parseLong(delivery[4]), Instant.now().plusSeconds(5)));
                    if (ack.equals("ACKED")) { log.write(name + "\t" + delivery[1] + "\t" + delivery[3]); log.newLine(); log.flush(); }
                }
            }
        }
    }
    /**
     * Publishes the fixture's complete held-delivery control record.
     * @param path final controller-visible marker
     * @param response complete native MESSAGE record
     * @param beforePublish deterministic test barrier at the publication boundary
     * @throws IOException if fixture publication fails
     */
    static void publishHeld(Path path, String response, Runnable beforePublish) throws IOException {
        Path staged = path.resolveSibling(path.getFileName() + ".next");
        Files.writeString(staged, response);
        beforePublish.run();
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String retry(Operation operation) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L; IOException last = null;
        do { try { return operation.run(); } catch (IOException unavailable) { last = unavailable; Thread.onSpinWait(); } }
        while (System.nanoTime() < end);
        throw new IOException("Consumer recovery exceeded profile bound", last);
    }
    @FunctionalInterface private interface Operation { String run() throws IOException; }
}
