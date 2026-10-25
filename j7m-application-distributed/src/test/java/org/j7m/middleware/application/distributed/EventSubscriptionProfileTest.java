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
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.j7m.middleware.distributed.ResourceEndpoint;
import org.junit.jupiter.api.Test;

class EventSubscriptionProfileTest {
    private static ResourceEndpoint endpoint() {
        return (ResourceEndpoint) Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),
                new Class<?>[]{ResourceEndpoint.class}, (proxy, method, arguments) -> { throw new AssertionError("No endpoint I/O during validation"); });
    }
    private static EventSubscriptionProfile profile(ResourceEndpoint source, ResourceEndpoint metadata, int page, Duration poll) {
        return new EventSubscriptionProfile(source, metadata, "source", Duration.ofSeconds(3), poll,
                Duration.ofSeconds(1), Duration.ofSeconds(5), page, 1024,
                EventSubscriptionProfile.Restart.RESUME, EventSubscriptionProfile.Expiry.FAIL);
    }
    @Test void finiteNativeBoundsAndSeparateMetadataAreEnforcedWithoutIo() {
        var source = endpoint(); var metadata = endpoint();
        assertEquals(1, profile(source, metadata, 1, Duration.ofMillis(1)).maximumPage());
        var maximum = profile(source, metadata, 256, Duration.ofMillis(999));
        assertEquals(256, maximum.maximumPage()); assertSame(source, maximum.source()); assertSame(metadata, maximum.metadata());
        assertEquals("source", maximum.sourceNamespace()); assertEquals(Duration.ofSeconds(3), maximum.lease());
        assertEquals(Duration.ofMillis(999), maximum.pollInterval()); assertEquals(Duration.ofSeconds(1), maximum.operationBudget());
        assertEquals(Duration.ofSeconds(5), maximum.handlerBudget()); assertEquals(1024, maximum.maximumSnapshotBytes());
        assertEquals(EventSubscriptionProfile.Restart.RESUME, maximum.restart()); assertEquals(EventSubscriptionProfile.Expiry.FAIL, maximum.expiry());
        assertThrows(IllegalArgumentException.class, () -> profile(source, source, 1, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> profile(source, metadata, 0, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> profile(source, metadata, 257, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> profile(source, metadata, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> profile(source, metadata, 1, Duration.ZERO));
    }
}
