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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.reliability.FencingToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationFencingTokenTest {
    @TempDir
    Path directory;

    @Test
    public void monotonicStaleEqualAndScopeMismatchPoliciesWork() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            LeaseGrant first = service.acquireLease(new LeaseName("fence"), new PrincipalRef("node-a"), CoordinationTestSupport.leaseOptions());
            clock.advance(Duration.ofSeconds(4L));
            LeaseGrant second = service.acquireLease(new LeaseName("fence"), new PrincipalRef("node-b"), CoordinationTestSupport.leaseOptions());
            assertEquals(first.fencingToken().value() + 1L, second.fencingToken().value());
            service.compareAndSet(new CoordinateKey("protected"), null, new CoordinateValue(new byte[] {1}),
                    new CasOptions(null, second.leaseId(), second.fencingToken()));
            CoordinationException stale = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(new CoordinateKey("protected"), new CoordinateValue(new byte[] {1}), new CoordinateValue(new byte[] {2}),
                            new CasOptions(null, second.leaseId(), first.fencingToken())));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, stale);
            CoordinationException mismatch = assertThrows(CoordinationException.class,
                    () -> service.compareAndSet(new CoordinateKey("protected"), new CoordinateValue(new byte[] {1}), new CoordinateValue(new byte[] {2}),
                            new CasOptions(null, second.leaseId(), new FencingToken("other-authority", "lease:fence", 2L))));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.STALE_FENCING_TOKEN, mismatch);
        }
    }
}
