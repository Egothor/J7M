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
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.BindingRefresh;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.application.api.BindingPolicy;
import org.junit.jupiter.api.Test;

class LiveBindingWorkerOwnershipTest {
    @Test void observerWorkerDoesNotRetainApplicationLoaderAfterBindingRevocation() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var inherited = new InheritableThreadLocal<ClassLoader>();
        var inheritedValue = new AtomicReference<ClassLoader>();
        try (var caller = new URLClassLoader(new URL[0], previous)) {
            Thread.currentThread().setContextClassLoader(caller); inherited.set(caller);
            var source = new LiveServiceBindingsTest.Source(LiveServiceBindingsTest.ref("a", 1));
            var worker = new AtomicReference<Thread>(); var done = new CountDownLatch(1);
            try (var owner = LiveServiceBindingsTest.owner(source, LiveServiceBindingsTest.policy(BindingPolicy.Selection.LOWEST_ID, BindingPolicy.Unavailable.FAIL_FAST),
                    (ref, type, options) -> (LiveServiceBindingsTest.Business) ref::runtimeId, (request, ref) -> true)) {
                try (var binding = owner.open(LiveServiceBindingsTest.request(BindingRefresh.ON_FAILURE), "policy");
                     var watch = binding.watch(snapshot -> { worker.set(Thread.currentThread()); inheritedValue.set(inherited.get()); done.countDown(); })) {
                    source.push(2, LiveServiceBindingsTest.ref("a", 1)); assertTrue(done.await(2, TimeUnit.SECONDS));
                }
                assertEquals(0, owner.status().bindings());
                assertSame(LiveServiceBindings.class.getClassLoader(), worker.get().getContextClassLoader());
                assertNotSame(caller, worker.get().getContextClassLoader());
                assertNull(inheritedValue.get());
            }
        } finally { inherited.remove(); Thread.currentThread().setContextClassLoader(previous); }
    }
    @Test void discoveryWorkerDoesNotRetainApplicationLoaderAfterWatchRevocation() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var inherited = new InheritableThreadLocal<ClassLoader>();
        var inheritedValue = new AtomicReference<ClassLoader>();
        try (var caller = new URLClassLoader(new URL[0], previous)) {
            Thread.currentThread().setContextClassLoader(caller); inherited.set(caller);
            var worker = new AtomicReference<Thread>(); var done = new CountDownLatch(1);
            var runtime = (MiddlewareRuntime) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MiddlewareRuntime.class}, (proxy, method, arguments) -> {
                assertEquals("list", method.getName());
                if (Thread.currentThread().getName().equals("j7m-binding-discovery")) { worker.set(Thread.currentThread()); inheritedValue.set(inherited.get()); done.countDown(); }
                return List.of(LiveServiceBindingsTest.ref("a", 1));
            });
            try (var source = new PolledBindingCandidateSource(runtime)) {
                try (var watch = source.watch(LiveServiceBindingsTest.request(BindingRefresh.NEVER), LiveServiceBindingsTest.policy(BindingPolicy.Selection.STICKY, BindingPolicy.Unavailable.FAIL_FAST), snapshot -> assertTrue(snapshot.authoritative()))) {
                    assertTrue(done.await(2, TimeUnit.SECONDS));
                }
                assertEquals(0, source.status().subscriptions());
                assertSame(PolledBindingCandidateSource.class.getClassLoader(), worker.get().getContextClassLoader());
                assertNotSame(caller, worker.get().getContextClassLoader());
                assertNull(inheritedValue.get());
            }
        } finally { inherited.remove(); Thread.currentThread().setContextClassLoader(previous); }
    }
}
