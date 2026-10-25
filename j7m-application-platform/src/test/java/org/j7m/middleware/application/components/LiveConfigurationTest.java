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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.annotations.ConfigProperties;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.application.api.ConfigBinding;
import org.junit.jupiter.api.Test;

class LiveConfigurationTest {
    @ConfigProperties(prefix = "pool", refresh = true)
    public record Pool(int minimum, int maximum, Duration timeout) {
        public Pool {
            if (minimum > maximum) { throw new IllegalArgumentException("Synthetic rejected secret value"); }
        }
    }

    @ConfigProperties(refresh = true)
    public record Defaults(@ConfigValue(value = "size", defaultValue = "7") int size) { }

    @Test
    void typedScalarsDefaultsAndPrimitiveTokensHaveExplicitConversion() {
        try (var owner = new LiveConfiguration(0, Map.of("enabled", "true", "timeout", "PT2S", "port", "42"))) {
            assertEquals(42, owner.bindValue(int.class, "port", true, "", true, value -> { }).get());
            assertTrue(owner.bindValue(Boolean.class, "enabled", true, "", true, value -> { }).get());
            assertEquals(Duration.ofSeconds(2), owner.bindValue(Duration.class, "timeout", true, "", true, value -> { }).get());
            assertEquals(7, owner.bindProperties(Defaults.class, value -> { }).get().size());
            assertNull(owner.bindValue(Integer.class, "missing", false, "", true, value -> { }).get());
            assertThrows(IllegalArgumentException.class, () -> owner.bindValue(Integer.class, "missing", true, "7", true, value -> { }));
            assertThrows(IllegalArgumentException.class, () -> owner.bindValue(List.class, "port", true, "", true, value -> { }));
        }
    }

    @Test
    void rejectedWholeRecordDoesNotPublishAnyPartialScalarOrSourceVersion() {
        try (var owner = new LiveConfiguration(1, values(2, 10))) {
            var pool = owner.bindProperties(Pool.class, value -> { });
            var minimum = owner.bindValue(Integer.class, "pool.minimum", true, "", true, value -> { });
            var error = assertThrows(IllegalArgumentException.class, () -> owner.update(2, values(20, 3)));
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
            assertEquals(1, owner.version());
            assertEquals(2, pool.get().minimum());
            assertEquals(2, minimum.get());
            assertTrue(owner.update(3, values(4, 12)));
            assertEquals(3, pool.snapshot().version());
            assertEquals(4, minimum.get());
        }
    }

    @Test
    void snapshotsPairVersionAndWholeRecordDuringConcurrentReads() throws Exception {
        try (var owner = new LiveConfiguration(0, values(0, 0)); var executor = Executors.newFixedThreadPool(2)) {
            var binding = owner.bindProperties(Pool.class, value -> { });
            var reader = executor.submit(() -> {
                for (int i = 0; i < 10_000; i++) {
                    var snapshot = binding.snapshot();
                    assertEquals(snapshot.version(), snapshot.value().minimum());
                    assertEquals(snapshot.value().minimum(), snapshot.value().maximum());
                }
            });
            var writer = executor.submit(() -> {
                for (int i = 1; i <= 100; i++) { owner.update(i, values(i, i)); }
            });
            reader.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void slowValidationDoesNotHoldMonitorAndOlderConcurrentUpdateCannotOverwriteWinner() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var owner = new LiveConfiguration(1, Map.of("value", "1")); var executor = Executors.newSingleThreadExecutor()) {
            var binding = owner.bindValue(Integer.class, "value", true, "", true, value -> {
                if (value == 2) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
            });
            var update = executor.submit(() -> owner.update(2, Map.of("value", "2")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(1, binding.get());
                assertTrue(owner.update(3, Map.of("value", "3")));
            } finally { release.countDown(); }
            assertFalse(update.get(10, TimeUnit.SECONDS));
            assertEquals(3, binding.snapshot().version());
            assertEquals(3, binding.get());
        }
    }

    @Test
    void stableBindingsRetainInitialValueWhileRefreshingBindingsReplayOnlyNewerSnapshots() {
        try (var owner = new LiveConfiguration(5, Map.of("value", "1"))) {
            var stable = owner.bindValue(Integer.class, "value", true, "", false, value -> { });
            var live = owner.bindValue(Integer.class, "value", true, "", true, value -> { });
            assertFalse(owner.update(4, Map.of("value", "0")));
            assertFalse(owner.update(5, Map.of("value", "0")));
            assertTrue(owner.update(6, Map.of("value", "2")));
            assertEquals(1, stable.get());
            assertEquals(5, stable.snapshot().version());
            assertEquals(2, live.get());
        }
    }

    @Test
    void callbacksCannotMutateOwnerReentrantlyOrEchoTheirExceptionPayload() {
        try (var owner = new LiveConfiguration(1, Map.of("value", "1"))) {
            var binding = owner.bindValue(Integer.class, "value", true, "", true, value -> {
                if (value == 2) { owner.update(9, Map.of("value", "Synthetic-secret")); }
            });
            var error = assertThrows(IllegalArgumentException.class, () -> owner.update(2, Map.of("value", "2")));
            assertFalse(error.toString().contains("Synthetic-secret"));
            assertEquals(1, binding.get());
            assertEquals(1, owner.version());
        }
    }

    @Test
    void registrationCapacityIsBoundedAndCloseReleasesCapacityAndReferences() {
        var owner = new LiveConfiguration(0, Map.of("value", "1"));
        var bindings = new ArrayList<ConfigBinding<Integer>>();
        for (int i = 0; i < 128; i++) {
            bindings.add(owner.bindValue(Integer.class, "value", true, "", true, value -> { }));
        }
        assertEquals(128, owner.bindingCount());
        assertThrows(IllegalStateException.class, () -> owner.bindValue(Integer.class, "value", true, "", true, value -> { }));
        bindings.getFirst().close();
        bindings.getFirst().close();
        assertThrows(IllegalStateException.class, bindings.getFirst()::get);
        assertEquals(1, owner.bindValue(Integer.class, "value", true, "", true, value -> { }).get());
        owner.close(); owner.close();
        assertEquals(0, owner.bindingCount());
        assertThrows(IllegalStateException.class, bindings.getLast()::get);
        assertThrows(IllegalStateException.class, owner::version);
        assertThrows(IllegalStateException.class, () -> owner.update(1, Map.of()));
    }

    @Test
    void snapshotBoundsAndInvalidScalarValuesRejectWithoutPublication() {
        assertThrows(IllegalArgumentException.class, () -> new LiveConfiguration(-1, Map.of()));
        var oversized = new HashMap<String, String>();
        for (int i = 0; i < 1024; i++) { oversized.put("k" + i, "v"); }
        try (var owner = new LiveConfiguration(0, oversized)) {
            oversized.put("overflow", "v");
            assertThrows(IllegalArgumentException.class, () -> owner.update(1, oversized));
            assertEquals(0, owner.version());
        }
        for (String invalid : List.of("NaN", "Infinity", "invalid")) {
            try (var owner = new LiveConfiguration(0, Map.of("number", invalid))) {
                var error = assertThrows(IllegalArgumentException.class, () -> owner.bindValue(Double.class, "number", true, "", true, value -> { }));
                assertEquals("Configuration conversion or validation rejected", error.getMessage());
                assertEquals(0, owner.bindingCount());
            }
        }
    }

    private static Map<String, String> values(final int minimum, final int maximum) {
        return Map.of("pool.minimum", Integer.toString(minimum), "pool.maximum", Integer.toString(maximum), "pool.timeout", "PT1S");
    }
}
