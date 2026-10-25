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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.junit.jupiter.api.Test;

class OwnershipResourceBindingsTest {
    private static ManagedDependency dependency(boolean provider, String refresh) {
        return new ManagedDependency(ManagedOwnershipContext.class, "", List.of(), provider,
                Map.of("resource.name", "ownership", "binding.refresh", refresh, "binding.policy", "default"));
    }
    @Test void providerFindsActualClaimPerCallAndCannotRetargetAnOldClaimOrInventRefresh() throws Exception {
        var factory = new ResourceBindingFactory(Map.of("ownership", ResourceBindingFactory.ownershipContext()), Set.of("default"));
        var edge = dependency(true, "NEVER"); factory.validate(edge, Scope.Kind.ACTIVATION);
        assertThrows(IllegalArgumentException.class, () -> factory.validate(dependency(false, "NEVER"), Scope.Kind.ACTIVATION));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(dependency(true, "ON_FAILURE"), Scope.Kind.ACTIVATION));
        var endpoint = new OwnershipEndpoint(); var first = new AtomicReference<ManagedOwnershipContext>();
        try (var resource = factory.bind(edge); var policy = ManagedOwnershipContextTest.policy(endpoint);
                var guard = policy.bind(TransactionInvocation.contract(ManagedOwnershipContextTest.Service.class), ManagedOwnershipContextTest.Worker.class, ManagedOwnershipContextTest.Service.class)) {
            assertThrows(IllegalStateException.class, resource::get);
            TransactionInvocation.invokeAs(TransactionInvocation.caller(), guard, frame -> {
                var current = ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class);
                assertSame(current, resource.get()); first.set(current); current.write("first", "key", "one"); return "done";
            });
            assertTrue(first.get().revoked()); assertThrows(IllegalStateException.class, resource::get);
            TransactionInvocation.invokeAs(TransactionInvocation.caller(), guard, frame -> {
                var current = (ManagedOwnershipContext) resource.get(); assertNotSame(first.get(), current);
                assertThrows(org.j7m.middleware.api.MiddlewareException.class, () -> first.get().write("late", "key", "forbidden"));
                assertEquals("one", current.read("key").value()); current.write("second", "key", "two"); return "done";
            });
            assertEquals(2, endpoint.effects.get());
        }
    }
}
