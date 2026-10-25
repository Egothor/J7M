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

import java.nio.file.Path;
import java.time.Duration;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.LeaseReleaseResult;
import org.j7m.middleware.api.coordination.LeaseState;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.coordination.ReleaseLeaseOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationLeaseAcquireRenewReleaseTest {
    @TempDir
    Path directory;

    @Test
    public void acquireRenewReleaseAndExpiredReacquireWork() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            LeaseGrant first = service.acquireLease(new LeaseName("work"), new PrincipalRef("node-a"), CoordinationTestSupport.leaseOptions());
            assertEquals(first.epoch(), service.renewLease(first.leaseId(), first.epoch().value()).epoch());
            LeaseReleaseResult release = service.releaseLease(first.leaseId(), new ReleaseLeaseOptions(first.epoch().value()));
            assertEquals(LeaseState.RELEASED, release.state());
            LeaseGrant second = service.acquireLease(new LeaseName("work"), new PrincipalRef("node-b"), CoordinationTestSupport.leaseOptions());
            clock.advance(Duration.ofSeconds(4L));
            LeaseGrant third = service.acquireLease(new LeaseName("work"), new PrincipalRef("node-c"), CoordinationTestSupport.leaseOptions());
            assertEquals(second.fencingToken().value() + 1L, third.fencingToken().value());
        }
    }
}
