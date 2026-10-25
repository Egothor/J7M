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

package org.j7m.middleware.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.MembershipQuery;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationRecoveryTest {
    @TempDir
    Path directory;

    @Test
    public void recoversMembersLeasesCoordinatesAndRejectsMalformedEvents() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.join(CoordinationTestSupport.member("node-a"), JoinOptions.defaults());
            service.acquireLease(new LeaseName("recover"), new PrincipalRef("node-a"), CoordinationTestSupport.leaseOptions());
            service.compareAndSet(new CoordinateKey("recover/key"), null, new CoordinateValue(new byte[] {7}), CasOptions.defaults());
        }
        try (LogBackedCoordinationService recovered = CoordinationTestSupport.open(this.directory, clock)) {
            assertEquals(1, recovered.membership(MembershipQuery.all()).members().size());
            assertEquals("test", recovered.membership(MembershipQuery.all()).members().getFirst().identity().metadata().get("zone"));
            assertEquals(1, recovered.status().leases().size());
            assertNotNull(recovered.get(new CoordinateKey("recover/key")));
        }

        Path bad = this.directory.resolveSibling(this.directory.getFileName() + "-bad");
        try {
            java.nio.file.Files.createDirectories(bad);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        CoordinationTestSupport.appendRawEvent(bad, "version=" + encoded("99") + "\ntype=" + encoded("MEMBER_JOINED") + "\n");
        CoordinationException exception = assertThrows(CoordinationException.class, () -> CoordinationTestSupport.open(bad, clock));
        CoordinationTestSupport.assertCode(MiddlewareErrorCode.RECOVERY_FAILED, exception);
        assertEquals("99", exception.remoteError().details().get("eventVersion"));
    }

    @Test
    public void recoveryStartsFromBoundedCheckpointAndReplaysTail() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        CoordinateKey key = new CoordinateKey("checkpoint/key");
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            for (int index = 0; index < 320; index++) {
                service.compareAndSet(key, index == 0 ? null : new CoordinateValue(new byte[] {(byte) (index - 1)}),
                        new CoordinateValue(new byte[] {(byte) index}), CasOptions.defaults());
            }
        }

        try (LogBackedCoordinationService recovered = CoordinationTestSupport.open(this.directory, clock)) {
            assertNotNull(recovered.get(key));
            assertTrue(recovered.recoveryReport().eventsReplayed() <= 128L,
                    "checkpoint recovery should replay only the bounded tail");
        }
    }

    @Test
    public void rejectsStateCardinalityAndCoordinateValueLimitsBeforeMutation() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = new CoordinationServiceBuilder()
                .directory(this.directory.resolve("state-limits"))
                .clock(clock)
                .maxMembers(1)
                .maxLeases(1)
                .maxCoordinates(1)
                .maxCoordinateValueBytes(1)
                .build()) {
            service.join(CoordinationTestSupport.member("node-a"), JoinOptions.defaults());
            CoordinationException memberLimit = assertThrows(CoordinationException.class,
                    () -> service.join(CoordinationTestSupport.member("node-b"), JoinOptions.defaults()));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, memberLimit);
            assertEquals(1, service.membership(MembershipQuery.all()).members().size());

            service.acquireLease(new LeaseName("lease-a"), new PrincipalRef("node-a"), CoordinationTestSupport.leaseOptions());
            CoordinationException leaseLimit = assertThrows(CoordinationException.class,
                    () -> service.acquireLease(new LeaseName("lease-b"), new PrincipalRef("node-a"),
                            CoordinationTestSupport.leaseOptions()));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, leaseLimit);
            assertEquals(1, service.status().leases().size());

            service.compareAndSet(new CoordinateKey("key-a"), null, new CoordinateValue(new byte[] {1}), CasOptions.defaults());
            CoordinationException coordinateLimit = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(new CoordinateKey("key-b"), null, new CoordinateValue(new byte[] {1}),
                            CasOptions.defaults()));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, coordinateLimit);
            assertEquals(1, service.status().coordinateCount());

            CoordinationException valueLimit = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(new CoordinateKey("key-a"), new CoordinateValue(new byte[] {1}),
                            new CoordinateValue(new byte[] {1, 2}), CasOptions.defaults()));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, valueLimit);
            assertEquals(new CoordinateValue(new byte[] {1}), service.get(new CoordinateKey("key-a")));
        }
    }

    @Test
    public void checkpointPublicationRejectsOversizedSerializedState() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        CoordinateKey key = new CoordinateKey("checkpoint/byte-limit");
        try (LogBackedCoordinationService service = new CoordinationServiceBuilder()
                .directory(this.directory.resolve("checkpoint-byte-limit"))
                .clock(clock)
                .maxCheckpointBytes(1)
                .build()) {
            service.compareAndSet(key, null, new CoordinateValue(new byte[] {0}), CasOptions.defaults());
            for (int index = 1; index < 256; index++) {
                service.compareAndSet(key, new CoordinateValue(new byte[] {(byte) (index - 1)}),
                        new CoordinateValue(new byte[] {(byte) index}), CasOptions.defaults());
            }
            CoordinationException checkpointLimit = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(key, new CoordinateValue(new byte[] {(byte) 255}),
                            new CoordinateValue(new byte[] {0}), CasOptions.defaults()));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.OVERLOADED, checkpointLimit);
        }
    }

    private static String encoded(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
