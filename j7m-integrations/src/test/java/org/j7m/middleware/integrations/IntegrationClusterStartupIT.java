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

import static org.junit.jupiter.api.Assertions.*;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationClusterStartupIT {
    @TempDir Path directory;

    @Test void servicePortsAreAllocatedByChildBindWhileFuturePeerPortsRemainReserved() throws Exception {
        Map<String, Integer> peers = new TreeMap<>();
        IntegrationCluster.BrokerLauncher launcher = (root, arguments) -> {
            assertEquals("0", arguments[4], "A sampled port number is not a listener reservation");
            for (String encoded : arguments[3].split(",")) {
                String[] pair = encoded.split(":"); peers.put(pair[0], Integer.parseInt(pair[1]));
            }
            for (var entry : peers.entrySet()) if (entry.getKey().compareTo(arguments[1]) > 0) {
                assertThrows(BindException.class, () -> {
                    try (var stolen = new ServerSocket(entry.getValue(), 0, InetAddress.getLoopbackAddress())) { assertTrue(stolen.isBound()); }
                }, "A future peer port must remain unavailable to the metrics allocator");
            }
            return IntegrationCluster.child(root, arguments);
        };
        try (var cluster = new IntegrationCluster(directory, false, launcher)) {
            var all = new HashSet<>(peers.values());
            for (int metrics : cluster.metrics) assertTrue(all.add(metrics), "metrics/peer collision");
            for (String service : cluster.ports.split(",")) assertTrue(all.add(Integer.parseInt(service)), "service listener collision");
            assertEquals(9, all.size()); assertEquals(3, cluster.brokers.size());
        }
    }

    @Test void failedSecondBrokerRetainsCauseAndClosesEveryPartiallyStartedChild() throws Exception {
        List<Process> started = new ArrayList<>();
        try (var occupied = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            IntegrationCluster.BrokerLauncher launcher = (root, arguments) -> {
                String[] actual = arguments.clone();
                if (actual[1].equals("b")) actual[4] = Integer.toString(occupied.getLocalPort());
                Process child = IntegrationCluster.child(root, actual); started.add(child); return child;
            };
            try {
                AssertionError failure = assertThrows(AssertionError.class, () -> new IntegrationCluster(directory, false, launcher));
                assertTrue(failure.getMessage().contains("Broker b")); assertTrue(failure.getMessage().contains("BindException"));
                assertEquals(2, started.size()); assertTrue(started.stream().noneMatch(Process::isAlive), "Failed construction owns and must close its partial fleet");
            } finally {
                for (Process child : started) if (child.isAlive()) IntegrationCluster.kill(child);
            }
        }
    }
}
