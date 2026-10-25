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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotatedReadinessProcessIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(10); }

    @Test void actualAnnotatedReadinessRemovesCandidateAndCanaryHoldsUntilFreshRecovery() throws Exception {
        try (var cluster = new ClusterProcessFixture(directory, 1, true);
             var canary = new CanaryDeployment("annotated", cluster.coordination(), cluster.lifecycle(), cluster.invocations())) {
            await(() -> cluster.lifecycle().inspect(deadline()).nodes().values().stream().allMatch(NodeView::ready));
            canary.configure("initial", 0, new CanaryPolicy(1, "v1", "v2", 1000, 200, .05, 10000, 5000), deadline());
            Path unavailable = Files.writeString(directory.resolve("n3/notready"), "not ready");
            await(() -> !cluster.lifecycle().inspect(deadline()).nodes().get("n3").ready());
            assertThrows(IOException.class, () -> cluster.invocations().get("n3").invoke("denied", "echo", new byte[0], deadline()));
            assertEquals("HOLD", canary.evaluate(deadline()).state());
            assertThrows(IOException.class, () -> canary.configure("unsafe", canary.status(deadline()).version(),
                    new CanaryPolicy(2, "v1", "v2", 2000, 200, .05, 10000, 5000), deadline()));
            assertTrue(new String(canary.invoke("held", "echo", new byte[0], deadline()), StandardCharsets.UTF_8).startsWith("v1:"));
            Files.delete(unavailable);
            await(() -> cluster.lifecycle().inspect(deadline()).nodes().get("n3").ready());
            assertEquals("ACTIVE", canary.configure("recovered", canary.status(deadline()).version(),
                    new CanaryPolicy(2, "v1", "v2", 10000, 200, .05, 10000, 5000), deadline()).state());
            assertTrue(new String(canary.invoke("candidate", "echo", new byte[0], deadline()), StandardCharsets.UTF_8).startsWith("v2:"));
            Path unhealthy = Files.writeString(directory.resolve("n2/unhealthy"), "unhealthy");
            await(() -> !cluster.lifecycle().inspect(deadline()).nodes().get("n2").ready());
            Files.delete(unhealthy);
            await(() -> cluster.lifecycle().inspect(deadline()).nodes().get("n2").ready());
        }
    }

    @Test void nonCooperativeAnnotatedProbeDoesNotBlockHostStatusAndTrafficStaysHeldUntilPhysicalReturn() throws Exception {
        try (var cluster = new ClusterProcessFixture(directory, 0, true); var caller = Executors.newSingleThreadExecutor()) {
            await(() -> cluster.lifecycle().inspect(deadline()).nodes().get("n1").ready());
            Path block = Files.writeString(directory.resolve("n1/block"), "block");
            try {
                await(() -> Files.exists(directory.resolve("n1/probe-entered")));
                await(() -> !cluster.lifecycle().inspect(deadline()).nodes().get("n1").ready());
                var status = caller.submit(() -> cluster.lifecycle().inspect(deadline()));
                assertFalse(status.get(2, TimeUnit.SECONDS).nodes().get("n1").ready(), "status must not wait for application probe completion");
                assertThrows(IOException.class, () -> cluster.invocations().get("n1").invoke("held", "echo", new byte[0], deadline()));
            } finally {
                Files.deleteIfExists(block); Files.writeString(directory.resolve("n1/probe-release"), "release");
            }
            await(() -> cluster.lifecycle().inspect(deadline()).nodes().get("n1").ready());
            assertTrue(new String(cluster.invocations().get("n1").invoke("recovered", "echo", new byte[0], deadline()), StandardCharsets.UTF_8).startsWith("v1:"));
        }
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.call()) {
            if (System.nanoTime() >= end) fail("actual application readiness did not converge");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            if (Thread.interrupted()) throw new InterruptedException("readiness wait interrupted");
        }
    }
}
