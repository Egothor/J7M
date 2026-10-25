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

package org.j7m.middleware.registry.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.junit.jupiter.api.Test;

class BootstrapEndpointResolverTest {
    @Test void functionalLocalResolverRetainsItsDeadlineOverloadWithoutNetworkOwnership() {
        try (var local = new EmbeddedRegistryClient()) {
            BootstrapEndpointResolver legacy = endpoint -> "local".equals(endpoint) ? Optional.of(local) : Optional.empty();
            assertSame(local, legacy.resolve("local").orElseThrow());
            assertSame(local, legacy.resolve("local", System.currentTimeMillis() + 1000).orElseThrow());
            assertTrue(legacy.resolve("unknown", System.currentTimeMillis() + 1000).isEmpty());
        }
    }

    @Test void resolverFailureIsInsideSeedFailoverBoundaryInsteadOfEscapingBeforeContact() {
        var signer = new DeterministicRegistryRecordSigner("bootstrap-test");
        try (var remote = new EmbeddedRegistryClient()) {
            var value = BootstrapTestProcess.record("valid", System.currentTimeMillis() + 60_000);
            remote.bind(value.withAuthentication(signer.sign(value)));
            AtomicInteger attempts = new AtomicInteger();
            BootstrapEndpointResolver resolver = endpoint -> {
                attempts.incrementAndGet();
                if ("unavailable".equals(endpoint)) {
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Synthetic unavailable endpoint");
                }
                return Optional.of(remote);
            };
            var options = new BootstrapRegistryOptions("local", "tenant", List.of("unavailable", "available"),
                    Duration.ofSeconds(1), 0, 2, 64, Duration.ofMillis(1), resolver);
            try (var bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(), options, signer, true, null)) {
                assertEquals("valid", bootstrap.lookup(value.ref()).orElseThrow().ref().objectId());
                assertEquals(2, attempts.get());
                assertEquals(1, bootstrap.discoveryStatistics().peerContactFailures());
            }
        }
    }
}
