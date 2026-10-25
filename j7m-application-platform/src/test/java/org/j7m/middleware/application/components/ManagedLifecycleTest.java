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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.application.api.ManagedHostAdapter;
import org.j7m.middleware.application.api.ManagedLifecycle;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedResolver;
import org.junit.jupiter.api.Test;

/**
 * Scope ownership keeps real lifecycle completion distinct from admission shutdown.
 */
class ManagedLifecycleTest {
    static final class Resource implements AutoCloseable {
        final List<String> events = new ArrayList<>();
        public void close() { events.add("close"); }
    }

    @Test
    void activationAndCallLifecyclesAreOwnedOnceAndShutdownPrecedesAutomaticClose() {
        try (var container = ManagedContainer.activate(List.of(index(Scope.Kind.CALL, false)))) {
            container.lifecycle(ManagedLifecycle.INITIALIZE);
            container.lifecycle(ManagedLifecycle.STARTUP);
            Resource resource;
            try (var call = container.openCall()) {
                resource = call.get(Resource.class, "", List.of());
                assertEquals(List.of("INITIALIZE", "STARTUP"), resource.events);
            }
            assertEquals(List.of("INITIALIZE", "STARTUP", "DRAIN", "SHUTDOWN", "close"), resource.events);
            container.lifecycle(ManagedLifecycle.DRAIN);
            assertThrows(ManagedContainer.Failure.class, container::openCall);
        }
    }

    @Test
    void failedStartupShutsDownAndClosesConstructedValuesWithoutRepeatingHooks() {
        var container = ManagedContainer.activate(List.of(index(Scope.Kind.ACTIVATION, true)));
        Resource resource = container.get(Resource.class, "", List.of());
        container.lifecycle(ManagedLifecycle.INITIALIZE);
        assertThrows(ManagedContainer.Failure.class, () -> container.lifecycle(ManagedLifecycle.STARTUP));
        container.close();
        container.close();
        assertEquals(List.of("INITIALIZE", "STARTUP", "DRAIN", "SHUTDOWN", "close"), resource.events);
    }

    @Test
    void drainNeverReportsDisposalWhileARealCallIsStillRunning() throws Exception {
        var container = ManagedContainer.activate(List.of(index(Scope.Kind.CALL, false)));
        container.lifecycle(ManagedLifecycle.STARTUP);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> {
                try (var call = container.openCall()) {
                    return call.call(() -> {
                        Resource resource = call.get(Resource.class, "", List.of());
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        assertFalse(resource.events.contains("close"));
                        return resource;
                    });
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            container.lifecycle(ManagedLifecycle.DRAIN);
            assertEquals("CALL_BUSY", assertThrows(ManagedContainer.Failure.class, container::close).code());
            assertFalse(container.status().closed());
            release.countDown();
            Resource completed = task.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("INITIALIZE", "STARTUP", "DRAIN", "SHUTDOWN", "close"), completed.events);
        } finally { release.countDown(); container.close(); }
    }

    private static ManagedComponentIndex index(Scope.Kind scope, boolean failStartup) {
        return () -> List.of(new ManagedHostAdapter<Resource>() {
            public Class<Resource> type() { return Resource.class; }
            public String name() { return ""; }
            public List<ManagedQualifier> qualifiers() { return List.of(); }
            public Scope.Kind scope() { return scope; }
            public List<ManagedDependency> dependencies() { return List.of(); }
            public Resource create(ManagedResolver resolver) { return new Resource(); }
            public void lifecycle(Resource instance, ManagedLifecycle phase) {
                instance.events.add(phase.name());
                if (failStartup && phase == ManagedLifecycle.STARTUP) throw new IllegalStateException("synthetic application detail");
            }
        });
    }
}
