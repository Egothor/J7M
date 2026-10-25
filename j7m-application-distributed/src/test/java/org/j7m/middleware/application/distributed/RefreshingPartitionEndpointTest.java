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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.annotations.BindingRefresh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshingPartitionEndpointTest {
    @TempDir Path directory;
    @Test void authoritativePreparationAndFailedCallRefreshAreObservableWithoutReplay() throws Exception {
        var fixture = new ResourceBindingProcessIT(); fixture.directory = directory; fixture.keys();
        try (var topology = fixture.new Node("topology", true, 0)) {
            var authority = topology.admin(); authority.register("partition", "source", Instant.now().plusSeconds(5));
            var uncertain = new ResourceBindingFactoryTest.UncertainEndpoint();
            try (var endpoint = new RefreshingPartitionEndpoint("partition", authority, Map.of("source", uncertain), BindingRefresh.ON_FAILURE, Instant.now().plusSeconds(5))) {
                assertEquals("source", endpoint.ownership().ownerGroup()); assertEquals(1, endpoint.ownership().epoch()); assertEquals(1, endpoint.refreshes());
                assertThrows(IOException.class, () -> endpoint.query(0, new byte[0], Instant.now().plusSeconds(5)));
                assertEquals(1, endpoint.refreshes());
                assertThrows(IOException.class, () -> endpoint.query(0, new byte[0], Instant.now().plusSeconds(5)));
                assertEquals(2, endpoint.refreshes());
                assertThrows(IOException.class, () -> endpoint.query(0, new byte[0], Instant.EPOCH)); assertEquals(2, endpoint.refreshes());
                endpoint.close(); assertThrows(IOException.class, () -> endpoint.status(Instant.now().plusSeconds(1)));
                assertFalse(uncertain.closed);
            }
            assertThrows(IllegalArgumentException.class, () -> new RefreshingPartitionEndpoint("", authority, Map.of("source", uncertain), BindingRefresh.NEVER, Instant.now().plusSeconds(1)));
            assertThrows(IOException.class, () -> new RefreshingPartitionEndpoint("partition", authority, Map.of("unauthorized", uncertain), BindingRefresh.NEVER, Instant.now().plusSeconds(5)));
        }
    }
}
