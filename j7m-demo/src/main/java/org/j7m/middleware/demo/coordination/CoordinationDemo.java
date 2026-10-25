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

package org.j7m.middleware.demo.coordination;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CasOptions;
import org.j7m.middleware.api.coordination.CoordinateKey;
import org.j7m.middleware.api.coordination.CoordinateValue;
import org.j7m.middleware.api.coordination.Heartbeat;
import org.j7m.middleware.api.coordination.HeartbeatOptions;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.LeaseGrant;
import org.j7m.middleware.api.coordination.LeaseName;
import org.j7m.middleware.api.coordination.LeaseOptions;
import org.j7m.middleware.api.coordination.MemberEndpoint;
import org.j7m.middleware.api.coordination.MemberIdentity;
import org.j7m.middleware.api.coordination.MemberId;
import org.j7m.middleware.api.coordination.MemberRole;
import org.j7m.middleware.api.coordination.MembershipQuery;
import org.j7m.middleware.api.coordination.PrincipalRef;
import org.j7m.middleware.api.coordination.WatchRegistration;
import org.j7m.middleware.coordination.CoordinationServiceBuilder;
import org.j7m.middleware.coordination.LogBackedCoordinationService;
import org.j7m.middleware.coordination.MutableCoordinationClock;

/**
 * Minimal Phase 6.4 coordination demo.
 */
public final class CoordinationDemo {
    // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private CoordinationDemo() {
    }

    /**
     * Runs the local coordination demo.
     *
     * @param args ignored
     * @throws Exception when temporary storage cannot be created
     */
    public static void main(final String[] args) throws Exception {
        Path directory = Files.createTempDirectory("j7m-coordination-demo-");
        MutableCoordinationClock clock = new MutableCoordinationClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger watchCount = new AtomicInteger();
        try (LogBackedCoordinationService service = new CoordinationServiceBuilder()
                .directory(directory)
                .clock(clock)
                .heartbeatOptions(new HeartbeatOptions(Duration.ofSeconds(2L), Duration.ofSeconds(5L)))
                .build()) {
            // J7M teaching: membership plus heartbeat records demonstrate liveness
            // evidence; only safe metadata is attached so coordination status remains
            // useful without exposing caller secrets.
            service.join(member("node-a", "local://node-a"), JoinOptions.defaults());
            service.join(member("node-b", "local://node-b"), JoinOptions.defaults());
            service.heartbeat(new MemberId("node-a"), new Heartbeat(clock.instant(), Map.of("safe", "true")));
            service.heartbeat(new MemberId("node-b"), new Heartbeat(clock.instant(), Map.of("safe", "true")));
            try (WatchRegistration ignored = service.watch(new CoordinateKey("cluster/config"), event -> watchCount.incrementAndGet())) {
                // J7M teaching: lease acquisition returns a fencing token that callers
                // must present on renewal; stale owners are rejected instead of silently
                // extending authority.
                LeaseGrant leader = service.acquireLease(new LeaseName("leader/main"), new PrincipalRef("node-a"),
                        new LeaseOptions(Duration.ofSeconds(3L), true));

                // J7M teaching: compare-and-set writes and watch notification show the
                // coordination state path a developer should use for guarded metadata
                // changes, not for consensus or application data replication.
                service.compareAndSet(new CoordinateKey("cluster/config"), null,
                        new CoordinateValue("v1".getBytes(StandardCharsets.UTF_8)), CasOptions.defaults());
                try {
                    service.renewLease(leader.leaseId(), leader.epoch().value() - 1L);
                } catch (org.j7m.middleware.api.coordination.CoordinationException exception) {
                    if (exception.code() != MiddlewareErrorCode.STALE_FENCING_TOKEN) {
                        throw exception;
                    }
                }

                // J7M teaching: a duplicate endpoint and an advanced clock produce
                // split-brain/liveness diagnostics so the demo reports suspicious
                // topology rather than pretending coordination is quorum consensus.
                service.join(member("node-c", "local://node-b"), JoinOptions.defaults());
                clock.advance(Duration.ofSeconds(6L));
                service.status();
                System.out.println("phase6.4 coordination directory: " + directory.getFileName());
                System.out.println("phase6.4 members=" + service.membership(MembershipQuery.all()).members().size()
                        + ", leaderToken=" + leader.fencingToken().value());
                System.out.println("phase6.4 watchNotifications=" + watchCount.get()
                        + ", splitBrainSuspected=" + service.status().splitBrainStatus().suspected());
                System.out.println("phase6.4 auditEvents=" + service.auditEvents().size()
                        + ", eventsReplayed=" + service.recoveryReport().eventsReplayed());
                System.out.println("phase6.4 non-goals: no consensus, no Raft, no quorum leadership, no repository replication");
            }
        }
    }

    private static MemberIdentity member(final String id, final String endpoint) {
        return new MemberIdentity(new MemberId(id), new MemberEndpoint(endpoint), MemberRole.COORDINATOR, Map.of("zone", "local"));
    }
}
