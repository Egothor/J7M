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
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;

class RemoteProxyA07BudgetTest {
    public interface Parent { @Deadline(millis=500) String run(String input); }
    public interface Child extends Parent { @Override String run(String input); }
    public interface Conflicting extends Parent { @Override @Deadline(millis=600) String run(String input); }
    public interface OneWayParent { @OneWay @Deadline(millis=500) void send(String input); }
    public interface OneWayChild extends OneWayParent { @Override void send(String input); }
    static final class Boundary {
        final AtomicInteger encodes = new AtomicInteger(), deliveries = new AtomicInteger(), releases = new AtomicInteger();
        final AtomicReference<InvocationOptions> received = new AtomicReference<>();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final RuntimeTransportContext context = (RuntimeTransportContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RuntimeTransportContext.class}, (proxy, method, args) -> switch (method.getName()) {
            case "encodeValue" -> { encodes.incrementAndGet(); yield new EncodedValue("scalar", String.class.getName(), args[0], Map.of()); }
            case "releaseInvocationCallbacks" -> { releases.incrementAndGet(); yield null; }
            case "executeOneWay" -> { assertTrue(queued.compareAndSet(null, (Runnable) args[0])); yield null; }
            default -> throw new AssertionError("Unexpected context operation " + method.getName());
        });
        final TransportProvider provider = (TransportProvider) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TransportProvider.class}, (proxy, method, args) -> {
            assertEquals("invoke", method.getName()); deliveries.incrementAndGet(); received.set((InvocationOptions) args[3]); return "ok";
        });
        <T> T proxy(Class<T> type, ProxyOptions options) {
            var ref = new ObjectRef("object", "service/object", "service", "runtime", "tenant", List.of(type.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new RemoteProxyInvocationHandler(ref, type, options, context, provider)));
        }
    }
    @Test void expiredLocalDeadlineRejectsBeforeApplicationEncodingOrDelivery() {
        var boundary = new Boundary();
        var proxy = boundary.proxy(Child.class, new ProxyOptions(Duration.ofSeconds(1), Map.of(ProxyOptions.LOCAL_DEADLINE_NANOS, Long.toString(System.nanoTime() - 1))));
        assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, () -> proxy.run("input")).code());
        assertEquals(0, boundary.encodes.get()); assertEquals(0, boundary.deliveries.get());
    }
    @Test void expiredParentEpochDeadlineAlsoRejectsBeforeApplicationEncoding() {
        var boundary = new Boundary();
        var proxy = boundary.proxy(Child.class, new ProxyOptions(Duration.ofSeconds(1), Map.of("j7m.reliability.deadlineEpochMillis", "1")));
        assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, () -> proxy.run("input")).code());
        assertEquals(0, boundary.encodes.get()); assertEquals(0, boundary.deliveries.get());
    }
    @Test void inheritedDeadlineClampsLongerParentMetadataWithoutOverflowOrLocalKeyLeak() {
        var boundary = new Boundary();
        var parent = new SecurityContext(null, "parent", null, null, "", "", "", true, null, Set.of(),
                Map.of("j7m.reliability.deadlineEpochMillis", Long.toString(Long.MAX_VALUE), ProxyOptions.LOCAL_DEADLINE_NANOS, "must-not-cross-wire"), Instant.now());
        var proxy = boundary.proxy(Child.class, new ProxyOptions(Duration.ofSeconds(5), Map.of()));
        assertEquals("ok", SecurityContexts.call(parent, () -> proxy.run("input")));
        assertEquals(1, boundary.encodes.get()); assertEquals(1, boundary.deliveries.get());
        assertTrue(boundary.received.get().deadline().compareTo(Duration.ofMillis(500)) <= 0);
        assertFalse(boundary.received.get().metadata().containsKey(ProxyOptions.LOCAL_DEADLINE_NANOS));
    }
    @Test void conflictingInheritedDeadlineRejectsBeforeEncoding() {
        var boundary = new Boundary(); var proxy = boundary.proxy(Conflicting.class, ProxyOptions.defaults());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> proxy.run("input")).code());
        assertEquals(0, boundary.encodes.get()); assertEquals(0, boundary.deliveries.get());
    }
    @Test void inheritedOneWayAcknowledgesOnlyLocalEnqueueAndExpiredQueuedWorkNeverDelivers() {
        var boundary = new Boundary(); long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        var proxy = boundary.proxy(OneWayChild.class, new ProxyOptions(Duration.ofSeconds(1), Map.of(ProxyOptions.LOCAL_DEADLINE_NANOS, Long.toString(deadline))));
        proxy.send("input"); assertNotNull(boundary.queued.get()); assertEquals(0, boundary.deliveries.get());
        while (deadline - System.nanoTime() >= 0) java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1, deadline - System.nanoTime()));
        assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class, () -> boundary.queued.get().run()).code());
        assertEquals(0, boundary.deliveries.get()); assertEquals(1, boundary.releases.get());
    }
}
