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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.j7m.middleware.application.api.ConfigurationSource;
import org.junit.jupiter.api.Test;

class ConfigurationSourceTest {
    private static final class Source implements ConfigurationSource {
        private Consumer<Snapshot> listener;
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public AutoCloseable watch(final Consumer<Snapshot> target) {
            this.listener = target;
            return this.closes::incrementAndGet;
        }

        void emit(final long version, final String value) {
            this.listener.accept(new Snapshot(version, Map.of("value", value)));
        }
    }

    @Test
    void reconnectReplayAndLateDetachedEventsDoNotReplaceNewerValues() throws Exception {
        var first = new Source();
        var replacement = new Source();
        try (var owner = new LiveConfiguration(0, Map.of("value", "0"))) {
            var binding = owner.bindValue(Integer.class, "value", true, "", true, value -> { });
            var watch = owner.attach(first);
            assertThrows(IllegalStateException.class, () -> owner.attach(replacement));
            first.emit(2, "2");
            first.emit(3, "invalid-sensitive");
            assertEquals(2, binding.get());
            assertEquals(1, owner.rejectedSourceUpdates());
            watch.close(); watch.close();
            assertEquals(1, first.closes.get());
            var next = owner.attach(replacement);
            replacement.emit(1, "1");
            first.emit(99, "99");
            assertEquals(2, owner.version());
            replacement.emit(4, "4");
            assertEquals(4, binding.get());
            assertEquals(1, owner.rejectedSourceUpdates());
            next.close();
            assertEquals(1, replacement.closes.get());
        }
    }

    @Test
    void synchronousInitialDeliveryAndOwnerCloseHaveExactlyOneSubscriptionCleanup() {
        var closes = new AtomicInteger();
        var owner = new LiveConfiguration(0, Map.of());
        ConfigurationSource source = listener -> {
            listener.accept(new ConfigurationSource.Snapshot(1, Map.of("value", "7")));
            return closes::incrementAndGet;
        };
        owner.attach(source);
        var binding = owner.bindValue(Integer.class, "value", true, "", true, value -> { });
        assertEquals(7, binding.get());
        owner.close(); owner.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, binding::get);
    }

    @Test
    void failedAttachReleasesItsSlotAndSnapshotFormattingNeverExposesValues() {
        try (var owner = new LiveConfiguration(0, Map.of())) {
            var failure = assertThrows(IllegalArgumentException.class, () -> owner.attach(listener -> {
                throw new IllegalStateException("Synthetic-sensitive-payload");
            }));
            assertFalse(failure.toString().contains("Synthetic-sensitive-payload"));
            assertTrue(new ConfigurationSource.Snapshot(0, Map.of("key", "Synthetic-sensitive-payload")).toString().contains("version=0"));
            assertFalse(new ConfigurationSource.Snapshot(0, Map.of("key", "Synthetic-sensitive-payload")).toString().contains("Synthetic-sensitive-payload"));
            assertThrows(IllegalArgumentException.class, () -> new ConfigurationSource.Snapshot(-1, Map.of()));
            owner.attach(new Source());
        }
    }

    @Test
    void detachDuringValidationPreventsTheOldSubscriptionFromPublishing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = new Source();
        try (var owner = new LiveConfiguration(1, Map.of("value", "1")); var executor = Executors.newSingleThreadExecutor()) {
            var binding = owner.bindValue(Integer.class, "value", true, "", true, value -> {
                if (value == 2) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
            });
            var watch = owner.attach(source);
            var update = executor.submit(() -> source.emit(2, "2"));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                watch.close();
            } finally { release.countDown(); }
            update.get(10, TimeUnit.SECONDS);
            assertEquals(1, owner.version());
            assertEquals(1, binding.get());
            assertEquals(1, source.closes.get());
        }
    }
}
