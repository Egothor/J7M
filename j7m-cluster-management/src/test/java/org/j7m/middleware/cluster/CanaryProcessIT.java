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

package org.j7m.middleware.cluster;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanaryProcessIT {
    @TempDir Path directory;
    static Instant deadline() { return Instant.now().plusSeconds(10); }
    static CanaryPolicy policy(long revision, int weight) { return new CanaryPolicy(revision, "v1", "v2", weight, 200, .05, 10000, 5000); }

    @Test void actualManagedApplicationTenThousandRequestsHaveStableNinetyTenDistribution() throws Exception {
        try (var cluster = new ClusterProcessFixture(directory, 1);
                var controller = new CanaryDeployment("distribution", cluster.coordination(), cluster.lifecycle(), cluster.invocations())) {
            controller.configure("distribution-policy", 0, policy(1, 1000), deadline());
            int candidate = 0;
            try (var tasks = Executors.newFixedThreadPool(16)) {
                var work = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 10000; i++) {
                    String request = "distribution-" + i;
                    work.add(tasks.submit(() -> {
                        String result = new String(controller.invoke(request, "echo", "data".getBytes(StandardCharsets.UTF_8), deadline()), StandardCharsets.UTF_8);
                        boolean selected = CanaryRouter.bucket("distribution", 1, request) < 1000;
                        assertTrue(result.startsWith(selected ? "v2:" : "v1:"), result);
                        return selected;
                    }));
                }
                for (var result : work) if (result.get(120, TimeUnit.SECONDS)) candidate++;
            }
            assertTrue(candidate >= 700 && candidate <= 1300, "actual candidate responses=" + candidate);
            for (int i = 0; i < 10; i++) {
                String request = "distribution-" + i;
                byte[] first = controller.invoke(request, "echo", "data".getBytes(StandardCharsets.UTF_8), deadline());
                assertArrayEquals(first, controller.invoke(request, "echo", "data".getBytes(StandardCharsets.UTF_8), deadline()));
            }
            assertEquals("ACTIVE", controller.status(deadline()).state());
        }
    }

    @Test void twoHundredActualBadCandidateCallsAutomaticallyRollbackAndDrainWithinSixtySeconds() throws Exception {
        try (var cluster = new ClusterProcessFixture(directory, 1);
                var controller = new CanaryDeployment("bad", cluster.coordination(), cluster.lifecycle(), cluster.invocations())) {
            controller.configure("bad-policy", 0, policy(1, 10000), deadline());
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                String request = "bad-" + i;
                assertThrows(IOException.class, () -> controller.invoke(request, "echo", "FAIL".getBytes(StandardCharsets.UTF_8), deadline()));
            }
            awaitRolledBack(controller, start);
            assertTrue(cluster.lifecycle().inspect(deadline()).nodes().values().stream().allMatch(n -> n.releaseId().equals("v1")));
            assertTrue(new String(controller.invoke("after-rollback", "echo", "compatible".getBytes(StandardCharsets.UTF_8), deadline()), StandardCharsets.UTF_8).startsWith("v1:"));
        }
    }

    @Test void missingProbesHoldWithoutIncreasingExposureAndRestartRetainsPolicy() throws Exception {
        try (var cluster = new ClusterProcessFixture(directory, 1)) {
            var available = new java.util.concurrent.atomic.AtomicBoolean(true);
            CanaryDeployment.ProbeReader probes = revision -> available.get() ? new CanaryDeployment.Probe(Instant.now(), 0, 0, 0) : null;
            try (var controller = new CanaryDeployment("hold", cluster.coordination(), cluster.lifecycle(), cluster.invocations(), probes)) {
                controller.configure("hold-policy", 0, policy(1, 1000), deadline());
                available.set(false); assertEquals("HOLD", controller.evaluate(deadline()).state());
                assertThrows(IOException.class, () -> controller.configure("unsafe-increase", controller.status(deadline()).version(), policy(2, 2000), deadline()));
            }
            try (var resumed = new CanaryDeployment("hold", cluster.coordination(), cluster.lifecycle(), cluster.invocations(), probes)) {
                assertEquals("HOLD", resumed.status(deadline()).state());
                assertEquals(1000, resumed.status(deadline()).policy().canaryWeight());
                assertTrue(new String(resumed.invoke("hold-call", "echo", new byte[0], deadline()), StandardCharsets.UTF_8).startsWith("v1:"));
            }
        }
    }
    private static void awaitRolledBack(CanaryDeployment controller, long start) throws Exception {
        while (System.nanoTime() - start < Duration.ofSeconds(60).toNanos()) {
            if (controller.status(deadline()).state().equals("ROLLED_BACK")) return;
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
        }
        fail("Automatic rollback exceeded 60 seconds: " + controller.status(deadline()));
    }
}
