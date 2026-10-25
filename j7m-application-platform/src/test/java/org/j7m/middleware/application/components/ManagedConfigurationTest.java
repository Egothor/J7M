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

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class ManagedConfigurationTest {
    @ConfigProperties(prefix = "app", refresh = true)
    public record Properties(int size, String name) { }
    record Settings(int stable, ManagedProvider<Integer> live, ManagedProvider<Properties> properties) { }
    private static ManagedDependency scalar(boolean provider, boolean refresh) {
        return new ManagedDependency(Integer.class, "", List.of(), provider, Map.of("config.key", "app.size", "config.default", "", "config.required", "true", "config.refresh", Boolean.toString(refresh)));
    }
    @Test void generatedAttributesBindStableAndRefreshingValuesBeforeCreationAndDetachOnClose() {
        var direct = scalar(false, false); var live = scalar(true, true);
        var properties = new ManagedDependency(Properties.class, "", List.of(), true, Map.of("config.prefix", "app", "config.refresh", "true"));
        var definition = ManagedContainerTest.definition(Settings.class, "", Scope.Kind.ACTIVATION,
                r -> new Settings(r.resolve(direct), r.provider(live), r.provider(properties)), direct, live, properties);
        try (var owner = new LiveConfiguration(1, Map.of("app.size", "3", "app.name", "first"))) {
            var container = ManagedContainer.activate(List.of(ManagedContainerTest.index(definition)), owner);
            var settings = container.get(Settings.class, "", List.of()); assertEquals(3, settings.stable); assertEquals(3, settings.live.get());
            owner.update(2, Map.of("app.size", "4", "app.name", "next"));
            assertEquals(3, settings.stable); assertEquals(4, settings.live.get()); assertEquals(new Properties(4, "next"), settings.properties.get());
            container.close(); ManagedContainerTest.code("CONTAINER_CLOSED", settings.live::get);
            // Detaching must remove validators: this snapshot cannot satisfy the old bindings.
            assertTrue(owner.update(3, Map.of()));
            try (ConfigBinding<String> stillBorrowed = owner.bindValue(String.class, "other", false, "alive", false, value -> { })) { assertEquals("alive", stillBorrowed.get()); }
        }
    }
    @Test void malformedRefreshUnsupportedAttributesAndMissingConfigFailBeforeConstructors() {
        var created = new AtomicInteger();
        var refresh = scalar(false, true);
        var definition = ManagedContainerTest.definition(Object.class, "", Scope.Kind.ACTIVATION, r -> { created.incrementAndGet(); return new Object(); }, refresh);
        try (var owner = new LiveConfiguration(1, Map.of("app.size", "3"))) {
            ManagedContainerTest.code("REFRESH_REQUIRES_PROVIDER", () -> ManagedContainer.activate(List.of(ManagedContainerTest.index(definition)), owner));
            var attrs = new HashMap<>(refresh.attributes()); attrs.put("config.refresh", "maybe");
            var malformed = new ManagedDependency(Integer.class, "", List.of(), true, attrs);
            var bad = ManagedContainerTest.definition(Object.class, "", Scope.Kind.ACTIVATION, r -> new Object(), malformed);
            ManagedContainerTest.code("INVALID_CONFIGURATION_FLAG", () -> ManagedContainer.activate(List.of(ManagedContainerTest.index(bad)), owner));
            ManagedContainerTest.code("CONFIGURATION_REQUIRED", () -> ManagedContainer.activate(List.of(ManagedContainerTest.index(definition))));
            assertEquals(0, created.get());
        }
    }
    @Test void failedStartDetachesEarlierRegistrationsWithoutClosingBorrowedOwner() {
        var edge = scalar(true, true);
        var bad = ManagedContainerTest.definition(Object.class, "", Scope.Kind.ACTIVATION, r -> { r.provider(edge); throw new Exception("private application failure"); }, edge);
        try (var owner = new LiveConfiguration(1, Map.of("app.size", "3"))) {
            ManagedContainerTest.code("CONSTRUCTION_FAILED", () -> ManagedContainer.activate(List.of(ManagedContainerTest.index(bad)), owner));
            assertTrue(owner.update(2, Map.of()));
        }
    }
}
