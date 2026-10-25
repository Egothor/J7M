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
import org.junit.jupiter.api.Test;

class TransferableResourceTest {
    @Test void mapAdapterBindsScopeAndValidatesSnapshotsWithoutChangingLiveState() {
        TransferableResource resource = new ReplicatedResources(); resource.ownershipChanged("p", 1);
        byte[] command = new ResourceCommand("BATCH", "tenant", "request", java.util.List.of("key", "0", "value")).encode();
        assertFalse(resource.identityConflict(command)); resource.validateReplayCommand(command);
        var candidate = resource.copyForTransfer(); assertNotSame(resource, candidate); assertArrayEquals(resource.snapshot(), candidate.snapshot());
        resource.validate(command, "tenant", false); assertEquals("request", resource.requestIdentity(command));
        assertThrows(SecurityException.class, () -> resource.validate(command, "foreign", false));
        byte[] before = resource.snapshot(); resource.validateSnapshot(before); assertArrayEquals(before, resource.snapshot());
        assertThrows(IllegalArgumentException.class, () -> resource.validateSnapshot(new byte[] {1})); assertArrayEquals(before, resource.snapshot());
        assertTrue(resource.transferReady()); assertFalse(resource.timeSensitive(command)); assertFalse(resource.timeControl(command));
        assertTrue(resource.timeControl(resource.timeCommand(1000)));
        var map = (ReplicatedResources) resource;
        map.validateAdmission(ResourceCommand.decode(command), 0);
        map.validateAdmission(new ResourceCommand("SUBSCRIBE", "tenant", "sub", java.util.List.of("reader", "", "p", "0", "0", "1000")), 0);
        assertThrows(IllegalArgumentException.class, () -> map.validateAdmission(new ResourceCommand("BATCH", "tenant", "bad", java.util.List.of("missing")), 0));
        assertThrows(IllegalArgumentException.class, () -> map.validateAdmission(new ResourceCommand("SUBSCRIBE", "tenant", "future", java.util.List.of("reader", "", "p", "9", "9", "1000")), 0));
        assertNotNull(resource.timeCommand(1000));
        byte[] query = new ResourceCommand("QUERY_READ", "tenant", "query", java.util.List.of("key")).encode();
        resource.validateQuery(query, "tenant"); assertNotNull(resource.query(query));
        assertThrows(SecurityException.class, () -> resource.validateQuery(query, "other"));
        resource.ownershipEpoch(1);
    }
    @Test void incompletePluginCannotSilentlyAdmitUnvalidatedNetworkCommands() {
        TransferableResource incomplete = new TransferableResource() {
            @Override public boolean transferReady() { return true; }
            @Override public void apply(long index, byte[] command) { throw new UnsupportedOperationException("No writes"); }
            @Override public byte[] snapshot() { return new byte[0]; }
            @Override public void restore(byte[] snapshot) { throw new UnsupportedOperationException("No restore"); }
        };
        assertThrows(UnsupportedOperationException.class, () -> incomplete.validate(new byte[0], "tenant", false));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.validateQuery(new byte[0], "tenant"));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.identityConflict(new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.copyForTransfer());
        assertThrows(UnsupportedOperationException.class, () -> incomplete.validateReplayCommand(new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.validateSnapshot(new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.requestIdentity(new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.query(new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> incomplete.timeCommand(1));
        assertFalse(incomplete.timeSensitive(new byte[0])); assertFalse(incomplete.timeControl(new byte[0]));
    }
}
