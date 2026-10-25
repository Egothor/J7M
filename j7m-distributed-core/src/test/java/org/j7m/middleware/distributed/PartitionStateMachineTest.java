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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartitionStateMachineTest {
    private static final byte[] KEY = new byte[32];
    private static final String SCOPE = "tenant/test";
    private static byte[] batch(String request, String key, String value) {
        return new ResourceCommand("BATCH", SCOPE, request, List.of(key, "0", value)).encode();
    }
    @Test void liveSnapshotSuffixProofAndOwnershipFencePreserveBusinessDedupe() {
        var sourceResource = new ReplicatedResources(); var targetResource = new ReplicatedResources();
        var source = new PartitionStateMachine("p", "left", 1, sourceResource, KEY);
        var target = new PartitionStateMachine("p", "right", 0, targetResource, KEY);
        source.apply(10, 7, PartitionStateMachine.mutation(1, batch("r1", "a", "first")));
        source.apply(11, 7, PartitionStateMachine.freeze(1, "move"));
        byte[] image = source.snapshotAt("move");
        source.apply(12, 7, PartitionStateMachine.mutation(1, batch("r2", "b", "second")));
        assertEquals("OK", source.receipt(12));
        target.apply(2, 1, PartitionStateMachine.stage("move", 2, image, PartitionStateMachine.sha256(image)));
        target.apply(3, 1, PartitionStateMachine.catchup("move", source.tail(target.status().logicalIndex(), 100)));
        source.apply(13, 7, PartitionStateMachine.freezeFinal(1, "move"));
        assertEquals(source.finalImageHash("move"), target.status().imageHash());
        target.apply(4, 1, PartitionStateMachine.activate(2, "move", target.status().imageHash(), new byte[32]));
        assertEquals("UNAUTHORIZED", target.receipt(4));
        byte[] proof = PartitionStateMachine.proof(KEY, "p", "right", 2, "move", target.status().imageHash());
        target.apply(5, 1, PartitionStateMachine.activate(2, "move", target.status().imageHash(), proof));
        source.apply(14, 8, PartitionStateMachine.mutation(1, batch("never", "c", "rejected")));
        assertEquals("FROZEN", source.receipt(14));
        target.apply(6, 1, PartitionStateMachine.mutation(1, batch("stale", "c", "rejected")));
        assertEquals("MOVED", target.receipt(6));
        target.apply(7, 1, PartitionStateMachine.mutation(2, batch("r1", "a", "first")));
        assertEquals("first", targetResource.queryAtReadIndex(SCOPE, "a", 3).value());
        assertEquals(1, targetResource.queryAtReadIndex(SCOPE, "a", 3).version());
        assertEquals("second", targetResource.queryAtReadIndex(SCOPE, "b", 3).value());
        assertNull(targetResource.queryAtReadIndex(SCOPE, "c", 3));
        var restored = new PartitionStateMachine("p", "right", 0, new ReplicatedResources(), KEY);
        restored.restore(target.snapshot()); assertEquals(target.status(), restored.status());
    }
    @Test void preparedTransactionBlocksPinAndFinalFreezeUntilResolution() {
        var resources = new ReplicatedResources(); var source = new PartitionStateMachine("p", "left", 1, resources, KEY);
        byte[] prepare = new ResourceCommand("PREPARE", SCOPE, "prepare", List.of("tx", "WRITE", "x", "v")).encode();
        source.apply(1, 1, PartitionStateMachine.mutation(1, prepare));
        source.apply(2, 1, PartitionStateMachine.freeze(1, "blocked"));
        assertEquals("PREPARED", source.receipt(2)); assertEquals("ACTIVE", source.status().phase());
        source.apply(3, 1, PartitionStateMachine.mutation(1, new ResourceCommand("ROLLBACK", SCOPE, "abort", List.of("tx", "1")).encode()));
        source.apply(4, 1, PartitionStateMachine.freeze(1, "blocked")); assertEquals("OK", source.receipt(4));
        source.apply(5, 1, PartitionStateMachine.mutation(1, new ResourceCommand("PREPARE", SCOPE, "prepare2", List.of("tx2", "WRITE", "y", "v")).encode()));
        source.apply(6, 1, PartitionStateMachine.freezeFinal(1, "blocked")); assertEquals("PREPARED", source.receipt(6));
    }
    @Test void resumedChunksAndCatchupRejectCorruptionAndGaps() {
        var source = new PartitionStateMachine("p", "a", 1, new ReplicatedResources(), KEY);
        source.apply(1, PartitionStateMachine.freeze(1, "h")); byte[] image = source.snapshotAt("h");
        var target = new PartitionStateMachine("p", "b", 0, new ReplicatedResources(), KEY);
        String hash = PartitionStateMachine.sha256(image); int half = image.length / 2;
        target.apply(1, PartitionStateMachine.chunk("h", 2, hash, image.length, 0, Arrays.copyOf(image, half)));
        var restored = new PartitionStateMachine("p", "b", 0, new ReplicatedResources(), KEY); restored.restore(target.snapshot());
        assertEquals(half, restored.uploadOffset());
        restored.apply(2, PartitionStateMachine.chunk("h", 2, hash, image.length, half, Arrays.copyOfRange(image, half, image.length)));
        assertEquals("STAGED", restored.status().phase());
        assertThrows(IllegalArgumentException.class, () -> restored.apply(3, PartitionStateMachine.catchup("h", List.of(new PartitionStateMachine.TailEntry(2, 1, batch("gap", "x", "v"))))));
        byte[] corrupted = image.clone(); corrupted[corrupted.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> target.apply(2, PartitionStateMachine.stage("h", 2, corrupted, hash)));
        assertEquals("EMPTY", target.status().phase());
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0})
    void invalidEpochAndUnappliedReadsAreRejected(long epoch) {
        var wrapper = new PartitionStateMachine("p", "a", 1, new ReplicatedResources(), KEY);
        assertThrows(IllegalArgumentException.class, () -> PartitionStateMachine.mutation(epoch, new byte[0]));
        assertThrows(IllegalStateException.class, () -> wrapper.query(1, new byte[0], 1));
    }
    @Test void conflictingConcurrentIdentityIsRejectedDuringSerializedApplyWithoutPoisoningHost() {
        var resources = new ReplicatedResources(); var state = new PartitionStateMachine("p", "g", 1, resources, KEY);
        byte[] first = batch("same", "x", "original"), conflicting = batch("same", "y", "changed");
        resources.validate(first, SCOPE, false); resources.validate(conflicting, SCOPE, false);
        state.apply(1, 1, PartitionStateMachine.mutation(1, first));
        state.apply(2, 1, PartitionStateMachine.mutation(1, conflicting)); assertEquals("CONFLICT", state.receipt(2));
        assertEquals("original", resources.queryAtReadIndex(SCOPE, "x", 1).value()); assertNull(resources.queryAtReadIndex(SCOPE, "y", 1));
        state.apply(3, 1, PartitionStateMachine.mutation(1, batch("healthy", "z", "healthy")));
        assertEquals("OK", state.receipt(3)); assertEquals("healthy", resources.queryAtReadIndex(SCOPE, "z", 2).value());
        state.apply(4, 1, PartitionStateMachine.mutation(1, first)); assertEquals("OK", state.receipt(4));
        assertEquals(1, resources.result(SCOPE, "same", 3).index());
        var restored = new PartitionStateMachine("p", "g", 0, new ReplicatedResources(), KEY); restored.restore(state.snapshot());
        restored.apply(5, 1, PartitionStateMachine.mutation(1, conflicting)); assertEquals("CONFLICT", restored.receipt(5));
    }
    @Test void timeSubscriptionWriteAndAckReplayAgainstEvolvingCandidateAtomically() {
        var sourceResource = new ReplicatedResources(); var targetResource = new ReplicatedResources();
        var source = new PartitionStateMachine("p", "left", 1, sourceResource, KEY);
        var target = new PartitionStateMachine("p", "right", 0, targetResource, KEY);
        source.apply(1, 3, PartitionStateMachine.mutation(1, sourceResource.timeCommand(1000)));
        source.apply(2, 3, PartitionStateMachine.freeze(1, "subscription")); byte[] image = source.snapshotAt("subscription");
        target.apply(1, 1, PartitionStateMachine.stage("subscription", 2, image, PartitionStateMachine.sha256(image)));
        source.apply(3, 3, PartitionStateMachine.mutation(1, sourceResource.timeCommand(1001)));
        source.apply(4, 3, PartitionStateMachine.mutation(1, new ResourceCommand("SUBSCRIBE", SCOPE, "sub", List.of("consumer", "BATCH", "p", "0", "0", "1000")).encode()));
        source.apply(5, 3, PartitionStateMachine.mutation(1, batch("event", "x", "v")));
        source.apply(6, 3, PartitionStateMachine.mutation(1, new ResourceCommand("ACK", SCOPE, "ack", List.of("consumer", "p", "1", "1", "1000")).encode()));
        var entries = source.tail(target.status().logicalIndex(), 100); byte[] valid = PartitionStateMachine.catchup("subscription", entries);
        byte[] before = target.snapshot(); target.validateControl(valid, SCOPE); assertArrayEquals(before, target.snapshot());
        var malformed = new ArrayList<>(entries); malformed.add(new PartitionStateMachine.TailEntry(6, 3, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> target.validateControl(PartitionStateMachine.catchup("subscription", malformed), SCOPE));
        assertArrayEquals(before, target.snapshot());
        assertThrows(IllegalArgumentException.class, () -> target.apply(2, 1, PartitionStateMachine.catchup("subscription", malformed)));
        assertArrayEquals(before, target.snapshot());
        target.apply(2, 1, valid); assertEquals("OK", target.receipt(2));
        assertTrue(targetResource.poll(SCOPE, "consumer", 10, 5).events().isEmpty());
        assertEquals("v", targetResource.queryAtReadIndex(SCOPE, "x", 5).value());
        source.apply(7, 3, PartitionStateMachine.freezeFinal(1, "subscription")); assertEquals(source.finalImageHash("subscription"), target.status().imageHash());
    }
    @Test void expiredLiveTailRepinsFullImageAndRejectsOldGenerationActivation() {
        var source = new PartitionStateMachine("p", "left", 1, new ReplicatedResources(), KEY);
        var target = new PartitionStateMachine("p", "right", 0, new ReplicatedResources(), KEY);
        byte[] command = new ResourceCommand("BATCH", SCOPE, "large", List.of("large1", "0", "x".repeat(30000), "large2", "0", "y".repeat(30000), "large3", "0", "z".repeat(30000))).encode();
        source.apply(1, 5, PartitionStateMachine.mutation(1, command)); source.apply(2, 5, PartitionStateMachine.freeze(1, "retained"));
        byte[] oldImage = source.snapshotAt("retained"); String oldHash = PartitionStateMachine.sha256(oldImage);
        target.apply(1, 1, PartitionStateMachine.stage("retained", 2, oldImage, oldHash));
        for (long index = 3; index < 203; index++) source.apply(index, 5, PartitionStateMachine.mutation(1, command));
        assertEquals("CURSOR_EXPIRED", assertThrows(IllegalStateException.class, () -> source.tail(1, 100)).getMessage());
        source.apply(203, 5, PartitionStateMachine.reset("retained")); target.apply(2, 1, PartitionStateMachine.reset("retained"));
        byte[] newImage = source.snapshotAt("retained"); String newHash = PartitionStateMachine.sha256(newImage); assertNotEquals(oldHash, newHash);
        target.apply(3, 1, PartitionStateMachine.stage("retained", 2, newImage, newHash));
        source.apply(204, 5, PartitionStateMachine.freezeFinal(1, "retained")); assertEquals(source.finalImageHash("retained"), target.status().imageHash());
        target.apply(4, 1, PartitionStateMachine.activate(2, "retained", oldHash, PartitionStateMachine.proof(KEY, "p", "right", 2, "retained", oldHash)));
        assertEquals("CONFLICT", target.receipt(4)); assertEquals("STAGED", target.status().phase());
        target.apply(5, 1, PartitionStateMachine.activate(2, "retained", newHash, PartitionStateMachine.proof(KEY, "p", "right", 2, "retained", newHash)));
        assertEquals("ACTIVE", target.status().phase());
    }
    @Test void migratedLeaseRenewsWhenDestinationConsensusTermIsLower() {
        var leftResource = new ReplicatedResources(); var rightResource = new ReplicatedResources();
        var left = new PartitionStateMachine("p", "left", 1, leftResource, KEY);
        var right = new PartitionStateMachine("p", "right", 0, rightResource, KEY);
        left.apply(1, 7, PartitionStateMachine.mutation(1, new ResourceCommand("TIME", SCOPE, "time", List.of("1000", "1000", "0")).encode()));
        left.apply(2, 7, PartitionStateMachine.mutation(1, new ResourceCommand("ACQUIRE", SCOPE, "acquire", List.of("lease", "owner", "10000", "0")).encode()));
        var acquired = leftResource.lease(SCOPE, "lease", 2); assertEquals(7, acquired.leadershipEpoch());
        left.apply(3, 7, PartitionStateMachine.freeze(1, "leaseMove")); byte[] image = left.snapshotAt("leaseMove");
        right.apply(1, 1, PartitionStateMachine.stage("leaseMove", 2, image, PartitionStateMachine.sha256(image)));
        left.apply(4, 7, PartitionStateMachine.freezeFinal(1, "leaseMove"));
        String hash = right.status().imageHash();
        right.apply(2, 1, PartitionStateMachine.activate(2, "leaseMove", hash, PartitionStateMachine.proof(KEY, "p", "right", 2, "leaseMove", hash)));
        right.apply(3, 1, PartitionStateMachine.mutation(2, new ResourceCommand("RENEW", SCOPE, "renew", List.of("lease", Long.toString(acquired.version()), "owner", "10000", "0")).encode()));
        var renewed = rightResource.lease(SCOPE, "lease", 3); assertNotNull(renewed);
        assertTrue(renewed.fence() > acquired.fence()); assertEquals(7, renewed.leadershipEpoch());
        assertEquals("OK", rightResource.result(SCOPE, "renew", 3).status());
    }
    @Test void topologyEpochCasAndProofSurviveSnapshot() throws IOException {
        var topology = new PartitionTopology(KEY); topology.apply(1, PartitionTopology.register("p", "left"));
        topology.apply(2, PartitionTopology.begin("h", "p", "right", 1));
        assertEquals("COPYING", topology.handoff("h", 2).phase());
        assertThrows(IllegalStateException.class, () -> topology.activationProof("h", 2));
        topology.apply(3, PartitionTopology.begin("parallel", "p", "other", 1)); assertEquals("CONFLICT", topology.receipt(3));
        topology.apply(4, PartitionTopology.cutover("h", 2, "a".repeat(64))); assertEquals("CONFLICT", topology.receipt(4));
        topology.apply(5, PartitionTopology.cutover("h", 1, "a".repeat(64))); assertEquals("right", topology.route("p", 5).ownerGroup());
        var restored = new PartitionTopology(KEY); restored.restore(topology.snapshot());
        assertArrayEquals(topology.activationProof("h", 5), restored.activationProof("h", 5));
        assertThrows(IllegalStateException.class, () -> restored.route("p", 6));
        byte[] corrupted = topology.snapshot(); corrupted[0] = 99;
        assertThrows(IllegalArgumentException.class, () -> restored.restore(corrupted)); assertEquals(2, restored.route("p", 5).epoch());
    }
}
