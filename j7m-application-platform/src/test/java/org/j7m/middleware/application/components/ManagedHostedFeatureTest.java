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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedMethod;
import org.junit.jupiter.api.Test;

class ManagedHostedFeatureTest {
    private final ManagedMethod method = new ManagedMethod("sample.Program", "initial", List.of(), "sample.State");

    @Test
    void snapshotsConfigurationAndCallbackAllowlist() {
        var attributes = new HashMap<>(Map.of("policy", "default"));
        var methods = new ArrayList<>(List.of(method));
        var feature = new ManagedHostedFeature("partition", "inventory", 1, attributes, methods);
        attributes.put("policy", "changed"); methods.clear();
        assertEquals(Map.of("policy", "default"), feature.attributes());
        assertEquals(List.of(method), feature.methods());
        assertThrows(UnsupportedOperationException.class, () -> feature.attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> feature.methods().clear());
    }

    @Test
    void acceptsExactDescriptorBoundsAndRejectsExcessOrAmbiguity() {
        var attributes = new HashMap<String, String>();
        for (int index = 0; index < 32; index++) attributes.put("key" + index, "x".repeat(512));
        var methods = new ArrayList<ManagedMethod>();
        for (int index = 0; index < 16; index++) methods.add(new ManagedMethod("Program", "call" + index, List.of(), "void"));
        assertEquals(16, new ManagedHostedFeature("K".repeat(64), "I".repeat(128), Integer.MAX_VALUE, attributes, methods).methods().size());
        attributes.put("excess", "value");
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "inventory", 1, attributes, methods));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "inventory", 1, Map.of(), List.of(method, method)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "inventory", 1, Map.of("policy", "x\nsecret"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "inventory", 1, Map.of("policy", "x".repeat(513)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "inventory", 0, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("../partition", "inventory", 1, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedFeature("partition", "", 1, Map.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new ManagedHostedFeature("partition", "inventory", 1, null, List.of()));
    }
}
