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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.application.api.InvocationContext;
import org.junit.jupiter.api.Test;

class ManagedInvocationSettingsTest {
    @Test void boundedExecutorRejectsOverloadAndUsesHostLoader() throws Exception {
        var settings = new ManagedInvocationSettings(1, 1, 4, 2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inherited = new InheritableThreadLocal<String>(); inherited.set("application-only");
        try (var executor = settings.newExecutor()) {
            executor.execute(() -> {
                assertSame(ManagedInvocationSettings.class.getClassLoader(), Thread.currentThread().getContextClassLoader());
                assertNull(inherited.get());
                entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); fail(failure); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                executor.execute(() -> { });
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("overflow executed")));
                assertEquals(1, executor.getQueue().size());
            } finally { release.countDown(); inherited.remove(); }
        }
    }

    @Test void profilesAndLifetimeRejectInvalidBoundsBeforeRetainingCallOwnership() {
        assertEquals(8, ManagedInvocationSettings.defaults().maximumWorkers());
        assertEquals(256, ManagedInvocationSettings.defaults().maximumQueuedActors());
        assertThrows(IllegalArgumentException.class, () -> new ManagedInvocationSettings(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedInvocationSettings(257, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedInvocationSettings(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedInvocationSettings(1, 1, 1, 2));
        try (var container = ManagedContainer.activate(List.of()); var call = container.openCall()) {
            assertThrows(IllegalArgumentException.class, () -> new ManagedInvocationLifetime(call, InvocationContext.empty(),
                    null, Duration.ZERO, Runnable::run, 1, 1, () -> { }));
        }
        var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of());
        assertEquals(ManagedInvocationSettings.defaults(), options.invocationSettings());
        assertEquals(ManagedInvocationSettings.defaults(), new ManagedHostingOptions(
                ManagedHostBindings.empty(), List.of(), ManagedProbeSettings.defaults()).invocationSettings());
    }
}
