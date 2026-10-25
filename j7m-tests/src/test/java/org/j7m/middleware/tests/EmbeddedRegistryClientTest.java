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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the embedded in-memory registry.
 */
public final class EmbeddedRegistryClientTest {
    @Test
    public void registryFiltersByServiceInterfaceContextAndParams() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T00:00:00Z"));
        EmbeddedRegistryClient registry = new EmbeddedRegistryClient(clock);
        ObjectRef ref = ref("object-1", "service", "example.Api", clock.millis() + 1_000L, Map.of("zone", "eu"));

        registry.bind(new RegistryRecord(ref, Map.of("zone", "eu"), clock.millis(), clock.millis(), ref.leaseUntil(), ObjectState.EXECUTED));

        List<RegistryRecord> matches = registry.list(new RegistryQuery("service", List.of("example.Api"), Map.of("zone", "eu"), "default", clock.millis(), 10));
        assertEquals(1, matches.size());
        assertEquals("object-1", matches.getFirst().ref().objectId());
        assertTrue(registry.list(new RegistryQuery("service", List.of("other.Api"), Map.of(), "default", clock.millis(), 10)).isEmpty());
    }

    @Test
    public void registryDoesNotReturnExpiredRecordsAndHeartbeatRenewsLease() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T00:00:00Z"));
        EmbeddedRegistryClient registry = new EmbeddedRegistryClient(clock);
        ObjectRef ref = ref("object-1", "service", "example.Api", clock.millis() + 100L, Map.of());

        registry.bind(new RegistryRecord(ref, Map.of(), clock.millis(), clock.millis(), ref.leaseUntil(), ObjectState.EXECUTED));
        clock.advance(Duration.ofMillis(101L));
        assertTrue(registry.lookup(ref).isEmpty());

        ObjectRef second = ref("object-2", "service", "example.Api", clock.millis() + 100L, Map.of());
        registry.bind(new RegistryRecord(second, Map.of(), clock.millis(), clock.millis(), second.leaseUntil(), ObjectState.EXECUTED));
        registry.heartbeat(second, clock.millis() + 1_000L);
        clock.advance(Duration.ofMillis(500L));

        assertTrue(registry.lookup(second).isPresent());
    }

    @Test
    public void unbindIsIdempotent() {
        EmbeddedRegistryClient registry = new EmbeddedRegistryClient();
        ObjectRef ref = ref("object-1", "service", "example.Api", System.currentTimeMillis() + 1_000L, Map.of());

        registry.unbind(ref);
        registry.unbind(ref);

        assertTrue(registry.lookup(ref).isEmpty());
    }

    private static ObjectRef ref(
            final String objectId,
            final String serviceName,
            final String interfaceName,
            final long leaseUntil,
            final Map<String, String> metadata) {
        return new ObjectRef(objectId, serviceName + "/" + objectId, serviceName, "runtime", "default",
                List.of(interfaceName), "grpc", "endpoint", leaseUntil, 1L, metadata);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }

        private void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }
    }
}
