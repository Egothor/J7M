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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.coordination.CoordinationException;
import org.j7m.middleware.api.coordination.Heartbeat;
import org.j7m.middleware.api.coordination.JoinOptions;
import org.j7m.middleware.api.coordination.LeaveOptions;
import org.j7m.middleware.api.coordination.MemberId;
import org.j7m.middleware.api.coordination.MemberLiveness;
import org.j7m.middleware.api.coordination.MembershipQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class CoordinationHeartbeatExpiryTest {
    @TempDir
    Path directory;

    @Test
    public void heartbeatKeepsAliveAndMissingHeartbeatTransitionsDeterministically() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.join(CoordinationTestSupport.member("node-a"), JoinOptions.defaults());
            service.heartbeat(new MemberId("node-a"), new Heartbeat(clock.instant(), Map.of()));
            assertEquals(MemberLiveness.ALIVE, service.membership(MembershipQuery.all()).members().getFirst().liveness());
            clock.advance(Duration.ofSeconds(3L));
            assertEquals(MemberLiveness.SUSPECT, service.membership(MembershipQuery.all()).members().getFirst().liveness());
            clock.advance(Duration.ofSeconds(3L));
            assertEquals(MemberLiveness.DEAD, service.membership(MembershipQuery.all()).members().getFirst().liveness());
        }
        try (LogBackedCoordinationService recovered = CoordinationTestSupport.open(this.directory, clock)) {
            assertEquals(MemberLiveness.DEAD, recovered.membership(MembershipQuery.all()).members().getFirst().liveness());
        }
    }

    @Test
    public void heartbeatAfterLeftIsRejectedAndDoesNotResurrectMember() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.join(CoordinationTestSupport.member("node-left"), JoinOptions.defaults());
            service.leave(new MemberId("node-left"), LeaveOptions.defaults());
            CoordinationException exception = assertThrows(CoordinationException.class,
                    () -> service.heartbeat(new MemberId("node-left"), new Heartbeat(clock.instant(), Map.of())));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.INVALID_ARGUMENT, exception);
            assertEquals(MemberLiveness.LEFT, service.membership(MembershipQuery.all()).members().getFirst().liveness());
            assertTrue(service.auditEvents().stream().anyMatch(event ->
                    event.action().equals("HEARTBEAT_REJECTED")
                            && event.metadata().get("memberId").equals("node-left")
                            && event.metadata().get("liveness").equals(MemberLiveness.LEFT.name())));
        }
    }

    @Test
    public void heartbeatAfterDeadIsRejectedAndRequiresFutureExplicitRejoinFlow() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            service.join(CoordinationTestSupport.member("node-dead"), JoinOptions.defaults());
            clock.advance(Duration.ofSeconds(6L));
            assertEquals(MemberLiveness.DEAD, service.membership(MembershipQuery.all()).members().getFirst().liveness());
            CoordinationException exception = assertThrows(CoordinationException.class,
                    () -> service.heartbeat(new MemberId("node-dead"), new Heartbeat(clock.instant(), Map.of())));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.INVALID_ARGUMENT, exception);
            assertEquals(MemberLiveness.DEAD, service.membership(MembershipQuery.all()).members().getFirst().liveness());
            assertTrue(service.auditEvents().stream().anyMatch(event ->
                    event.action().equals("HEARTBEAT_REJECTED")
                            && event.metadata().get("memberId").equals("node-dead")
                            && event.metadata().get("liveness").equals(MemberLiveness.DEAD.name())));
        }
    }

    @Test
    public void replayedHeartbeatAfterTerminalStateDoesNotResurrectMember() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        Map<String, String> leftJoined = new LinkedHashMap<>();
        leftJoined.put("memberId", "node-replay-left");
        leftJoined.put("endpoint", "local://node-replay-left");
        leftJoined.put("role", "COORDINATOR");
        leftJoined.put("metadata", "");
        leftJoined.put("epoch", "1");
        leftJoined.put("lastHeartbeat", Long.toString(clock.instant().toEpochMilli()));
        Map<String, String> deadJoined = new LinkedHashMap<>();
        deadJoined.put("memberId", "node-replay-dead");
        deadJoined.put("endpoint", "local://node-replay-dead");
        deadJoined.put("role", "COORDINATOR");
        deadJoined.put("metadata", "");
        deadJoined.put("epoch", "1");
        deadJoined.put("lastHeartbeat", Long.toString(clock.instant().toEpochMilli()));
        CoordinationTestSupport.appendEvents(this.directory,
                new CoordinationEvent("MEMBER_JOINED", leftJoined),
                new CoordinationEvent("MEMBER_LEFT", Map.of("memberId", "node-replay-left")),
                new CoordinationEvent("HEARTBEAT_RECORDED",
                        Map.of("memberId", "node-replay-left", "observedAt", Long.toString(clock.instant().toEpochMilli()))),
                new CoordinationEvent("MEMBER_JOINED", deadJoined),
                new CoordinationEvent("MEMBER_LIVENESS_CHANGED",
                        Map.of("memberId", "node-replay-dead", "liveness", MemberLiveness.DEAD.name())),
                new CoordinationEvent("HEARTBEAT_RECORDED",
                        Map.of("memberId", "node-replay-dead", "observedAt", Long.toString(clock.instant().toEpochMilli()))));

        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            assertEquals(MemberLiveness.LEFT, service.membership(MembershipQuery.all()).members().stream()
                    .filter(member -> member.identity().memberId().value().equals("node-replay-left"))
                    .findFirst().orElseThrow().liveness());
            assertEquals(MemberLiveness.DEAD, service.membership(MembershipQuery.all()).members().stream()
                    .filter(member -> member.identity().memberId().value().equals("node-replay-dead"))
                    .findFirst().orElseThrow().liveness());
        }
    }

    @Test
    public void unknownMemberHeartbeatIsRejectedAndAudited() {
        MutableCoordinationClock clock = CoordinationTestSupport.clock();
        try (LogBackedCoordinationService service = CoordinationTestSupport.open(this.directory, clock)) {
            CoordinationException exception = assertThrows(CoordinationException.class,
                    () -> service.heartbeat(new MemberId("missing"), new Heartbeat(clock.instant(), Map.of())));
            CoordinationTestSupport.assertCode(MiddlewareErrorCode.NOT_FOUND, exception);
            assertTrue(service.auditEvents().stream().anyMatch(event ->
                    event.action().equals("HEARTBEAT_REJECTED")
                            && event.metadata().get("reasonCode").equals(MiddlewareErrorCode.NOT_FOUND.name())));
        }
    }
}
