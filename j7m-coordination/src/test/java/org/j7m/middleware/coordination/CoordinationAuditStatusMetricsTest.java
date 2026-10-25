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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.CoordinateVersion;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.coordination.ReleaseLeaseOptions;
import org.j7m.middleware.api.reliability.FencingToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationAuditStatusMetricsTest {
    @TempDir
    Path directory;

    @Test
    public void auditStatusMetricsAreVisibleAndRedacted() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.join(CoordinationTestSupport.member("node-a"), JoinOptions.defaults());
            service.acquireLease(new LeaseName("audit"), new PrincipalRef("node-a"), CoordinationTestSupport.leaseOptions());
            service.compareAndSet(new CoordinateKey("audit/key"), null, new CoordinateValue("SECRET".getBytes(StandardCharsets.UTF_8)), CasOptions.defaults());
            assertTrue(service.status().metrics().joins() >= 1L);
            assertTrue(service.status().metrics().leaseGrants() >= 1L);
            assertTrue(service.status().metrics().casSuccesses() >= 1L);
            assertFalse(service.status().toString().contains("SECRET"));
            assertFalse(service.auditEvents().toString().contains("SECRET"));
        }
    }

    @Test
    public void staleLeaseCasAndWatchFailurePathsEmitSafeAuditEvents() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            LeaseGrant grant = service.acquireLease(new LeaseName("audit-stale"), new PrincipalRef("node-a"),
                    CoordinationTestSupport.leaseOptions());
            CoordinationException renew = assertThrows(CoordinationException.class, () -> service.renewLease(grant.leaseId(), 99L));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, renew);
            CoordinationException release = assertThrows(CoordinationException.class,
                    () -> service.releaseLease(grant.leaseId(), new ReleaseLeaseOptions(99L)));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, release);

            CoordinateKey key = new CoordinateKey("audit/cas");
            CoordinateValue secret = new CoordinateValue("SECRET-CAS".getBytes(StandardCharsets.UTF_8));
            assertTrue(service.compareAndSet(key, null, secret, CasOptions.defaults()).success());
            assertFalse(service.compareAndSet(key, null, new CoordinateValue(new byte[] {2}), CasOptions.defaults()).success());
            assertFalse(service.compareAndSet(key, secret, new CoordinateValue(new byte[] {3}),
                    new CasOptions(new CoordinateVersion(99L), null, null)).success());
            CoordinateKey fencedKey = new CoordinateKey("audit/fenced-cas");
            CoordinateValue fencedSecret = new CoordinateValue("SECRET-FENCED".getBytes(StandardCharsets.UTF_8));
            assertTrue(service.compareAndSet(fencedKey, null, fencedSecret,
                    new CasOptions(null, grant.leaseId(), grant.fencingToken())).success());
            CoordinationException staleFence = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(key, secret, new CoordinateValue(new byte[] {4}),
                            new CasOptions(null, grant.leaseId(), new FencingToken("other", "lease:audit-stale", 1L))));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, staleFence);

            service.watch(key, event -> {
                throw new IllegalStateException("boom");
            });
            service.compareAndSet(key, secret, new CoordinateValue(new byte[] {5}), CasOptions.defaults());

            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("EPOCH_MISMATCH_REJECTED")
                    && event.metadata().get("operation").equals("renew-lease")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("EPOCH_MISMATCH_REJECTED")
                    && event.metadata().get("operation").equals("release-lease")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("CAS_APPLIED")
                    && event.metadata().get("decision").equals("applied")
                    && event.metadata().get("reasonCode").equals("CAS_APPLIED")
                    && event.metadata().get("resourceType").equals("coordinate")
                    && event.metadata().get("key").equals("audit/cas")
                    && event.metadata().get("version").equals("1")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("CAS_APPLIED")
                    && event.metadata().get("decision").equals("applied")
                    && event.metadata().get("reasonCode").equals("CAS_APPLIED")
                    && event.metadata().get("resourceType").equals("coordinate")
                    && event.metadata().get("key").equals("audit/fenced-cas")
                    && event.metadata().get("leaseName").equals("audit-stale")
                    && event.metadata().get("leaseId").equals(grant.leaseId().value())
                    && event.metadata().get("fencingAuthority").equals(grant.fencingToken().authority())
                    && event.metadata().get("fencingScope").equals(grant.fencingToken().scope())
                    && event.metadata().get("fencingToken").equals(Long.toString(grant.fencingToken().value()))));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("CAS_MISMATCH")
                    && event.metadata().get("decision").equals("mismatch")
                    && event.metadata().get("reasonCode").equals("VALUE_MISMATCH")
                    && event.metadata().get("key").equals("audit/cas")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("CAS_MISMATCH")
                    && event.metadata().get("decision").equals("mismatch")
                    && event.metadata().get("reasonCode").equals("VERSION_MISMATCH")
                    && event.metadata().get("key").equals("audit/cas")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("FENCING_TOKEN_REJECTED")
                    && event.metadata().get("decision").equals("rejected")
                    && event.metadata().get("reasonCode").equals(MiddlewareErrorCode.STALE_FENCING_TOKEN.name())
                    && event.metadata().get("operation").equals("cas")));
            assertTrue(service.auditEvents().stream().anyMatch(event -> event.action().equals("WATCH_CALLBACK_FAILED")
                    && event.metadata().get("reasonCode").equals(MiddlewareErrorCode.INTERNAL.name())));
            assertFalse(service.auditEvents().toString().contains("SECRET-CAS"));
            assertFalse(service.auditEvents().toString().contains("SECRET-FENCED"));
        }
    }
}
