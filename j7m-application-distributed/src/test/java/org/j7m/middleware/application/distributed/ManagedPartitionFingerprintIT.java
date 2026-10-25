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

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.junit.jupiter.api.Test;

class ManagedPartitionFingerprintIT {
    @Test void actualImmutableBackendFilesAndCanonicalProgramConfigurationDetermineGroupIdentity() {
        var definition = ManagedPartitionResourceTest.definition(); var schemas = new SchemaRegistry(List.of());
        var first = new LinkedHashMap<String, String>(); first.put("alpha", "one"); first.put("beta", "two");
        var reverse = new LinkedHashMap<String, String>(); reverse.put("beta", "two"); reverse.put("alpha", "one");
        String fingerprint = ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), first), definition, schemas, "tenant", 1);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertEquals(fingerprint, ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), reverse), definition, schemas, "tenant", 1));
        assertNotEquals(fingerprint, ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("b".repeat(64), first), definition, schemas, "tenant", 1));
        assertNotEquals(fingerprint, ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), Map.of("alpha", "changed")), definition, schemas, "tenant", 1));
        assertNotEquals(fingerprint, ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), first), definition, schemas, "tenant", 10000));
        assertTrue(group("group", fingerprint).matches("group\\.[0-9a-f]{64}"));
        assertEquals(113, group("g".repeat(48), fingerprint).length());
        assertThrows(IllegalArgumentException.class, () -> group("g".repeat(49), fingerprint));
        assertThrows(IllegalArgumentException.class, () -> group("../group", fingerprint));
        assertThrows(IllegalArgumentException.class, () -> group("group", "wire-self-assertion"));
        assertThrows(IllegalArgumentException.class, () -> ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), first), definition, schemas, "tenant", 0));
        assertThrows(IllegalArgumentException.class, () -> ManagedPartitionFingerprint.compute(new ManagedHostedResources.Bundle("a".repeat(64), first), definition, schemas, "tenant", 10001));
    }
    private static String group(String base, String fingerprint) {
        return ManagedPartitionFingerprint.groupId(base, fingerprint, "partition", 1, java.util.Set.of("a", "b", "c"), 500000, 20000000);
    }
    @Test void nativeBootstrapBindingIsSharedAcrossLocalMembersButNotDifferentElectoratesOrPartitions() {
        String fp = "a".repeat(64);
        String group = group("group", fp);
        assertEquals(group, ManagedPartitionFingerprint.groupId("group", fp, "partition", 1, new java.util.LinkedHashSet<>(List.of("c", "b", "a")), 500000, 20000000));
        assertNotEquals(group, ManagedPartitionFingerprint.groupId("group", fp, "partition", 1, java.util.Set.of("a"), 500000, 20000000));
        assertNotEquals(group, ManagedPartitionFingerprint.groupId("group", fp, "other", 1, java.util.Set.of("a", "b", "c"), 500000, 20000000));
        assertNotEquals(group, ManagedPartitionFingerprint.groupId("group", fp, "partition", 0, java.util.Set.of("a", "b", "c"), 500000, 20000000));
        assertNotEquals(group, ManagedPartitionFingerprint.groupId("group", fp, "partition", 1, java.util.Set.of("a", "b", "c"), 600000, 20000000));
        assertThrows(IllegalArgumentException.class, () -> ManagedPartitionFingerprint.groupId("group", fp, "partition", 1, java.util.Set.of(), 500000, 20000000));
    }
}
