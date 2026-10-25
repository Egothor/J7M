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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.*;

import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.RemoteMethod;
import org.junit.jupiter.api.Test;

/**
 * Effective method lineage controls type policy, rather than unrelated interface annotations.
 */
class ManagedRuntimeContractsTest {
    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    static class ClaimedPartition { }
    static class ClaimedCallback {
        @org.j7m.middleware.annotations.EventSubscription("events") public void receive(String data) { }
        @org.j7m.middleware.annotations.EventSubscription("hidden") private void hidden(String data) { }
    }

    @Test
    void installedCapabilitiesCannotHideMissingOrWrongFiniteHostedDescriptors() throws Exception {
        var subscription = org.j7m.middleware.annotations.EventSubscription.class;
        var partition = org.j7m.middleware.annotations.PartitionHandler.class;
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateHostedCoverage(ClaimedPartition.class,
                java.util.Map.of(), java.util.Set.of(partition)));
        var receive = ClaimedCallback.class.getMethod("receive", String.class);
        var descriptor = new org.j7m.middleware.application.api.ManagedHostedFeature("event-subscription", "events", 1,
                java.util.Map.of("policy", "default", "filter", ""), java.util.List.of(ManagedRuntimeContracts.identity(receive)));
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateHostedCoverage(ClaimedCallback.class,
                java.util.Map.of(descriptor, java.util.Set.of(subscription)), java.util.Set.of(subscription)), "private annotated helper has no boundary");
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateHostedCoverage(ClaimedCallback.class,
                java.util.Map.of(), java.util.Set.of(subscription)));
    }

    static class ParentCallback {
        @org.j7m.middleware.annotations.EventSubscription("events") public void receive(String data) { }
    }
    static class RedeclaredCallback extends ParentCallback {
        @Override @org.j7m.middleware.annotations.EventSubscription("events") public void receive(String data) { }
    }
    static class ConflictingCallback extends ParentCallback {
        @Override @org.j7m.middleware.annotations.EventSubscription("different") public void receive(String data) { }
    }
    @Test
    void exactHostedCoverageIncludesInheritedDeclarationsButRejectsChangedOperationalMetadata() throws Exception {
        var capability = org.j7m.middleware.annotations.EventSubscription.class;
        for (Class<?> type : java.util.List.of(ParentCallback.class, RedeclaredCallback.class)) {
            var method = type.getMethod("receive", String.class);
            var feature = new org.j7m.middleware.application.api.ManagedHostedFeature("event-subscription", "events", 1,
                    java.util.Map.of("policy", "default", "filter", ""), java.util.List.of(ManagedRuntimeContracts.identity(method)));
            assertDoesNotThrow(() -> ManagedRuntimeContracts.validateHostedCoverage(type,
                    java.util.Map.of(feature, java.util.Set.of(capability)), java.util.Set.of(capability)));
        }
        var method = ConflictingCallback.class.getMethod("receive", String.class);
        var feature = new org.j7m.middleware.application.api.ManagedHostedFeature("event-subscription", "different", 1,
                java.util.Map.of("policy", "default", "filter", ""), java.util.List.of(ManagedRuntimeContracts.identity(method)));
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateHostedCoverage(ConflictingCallback.class,
                java.util.Map.of(feature, java.util.Set.of(capability)), java.util.Set.of(capability)));
    }
    @LocalOnly interface Local { void local(); }
    @NoRemoteExport interface ValueOnly { Object data(); }
    interface Remote { @RemoteMethod String remote(); }
    static class Mixed implements Local, ValueOnly, Remote {
        public void local() { }
        public Object data() { return "data"; }
        public String remote() { return "remote"; }
    }
    interface Denied { @RemoteMethod String remote(); }
    @LocalOnly static class Conflict implements Denied { public String remote() { return "denied"; } }

    @Test
    void unrelatedTypeDefaultsAreExcludedButSameMethodConflictsFail() throws Exception {
        var remote = Remote.class.getMethod("remote");
        assertTrue(ManagedRuntimeContracts.allowed(Mixed.class, remote));
        assertFalse(ManagedRuntimeContracts.allowed(Mixed.class, Local.class.getMethod("local")));
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.allowed(Conflict.class, Denied.class.getMethod("remote")));
        var identity = ManagedRuntimeContracts.identity(remote);
        assertEquals(Remote.class.getName(), identity.declaringType());
        assertEquals("java.lang.String", identity.returnType());
    }
}
