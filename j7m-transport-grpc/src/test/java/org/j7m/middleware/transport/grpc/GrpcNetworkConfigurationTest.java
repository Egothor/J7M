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

package org.j7m.middleware.transport.grpc;

import static org.junit.jupiter.api.Assertions.*;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.junit.jupiter.api.Test;

class GrpcNetworkConfigurationTest {
    @Test void resolvesPortZeroAndPreservesExplicitForwardedPort() {
        var defaults = GrpcNetworkConfiguration.loopback();
        assertEquals("127.0.0.1", defaults.bindHost());
        assertEquals(0, defaults.bindPort());
        assertEquals("127.0.0.1", defaults.bindAddress().getAddress().getHostAddress());
        assertEquals("grpc://127.0.0.1:4321", defaults.endpoint(4321));
        var forwarded = new GrpcNetworkConfiguration("0.0.0.0", 0, "localhost", 65535);
        assertTrue(forwarded.bindAddress().getAddress().isAnyLocalAddress());
        assertEquals("grpc://localhost:65535", forwarded.endpoint(4321));
        assertEquals("grpc://[::1]:1234", new GrpcNetworkConfiguration("::1", 0, "::1", 0).endpoint(1234));
    }

    @Test void rejectsMalformedHostsAndOutOfRangePorts() {
        for (String host : new String[] {null, "", " ", "http://localhost", "localhost:12", "localhost/path",
                "user@localhost", "localhost?query", "localhost#fragment", "[::1]", "fe80::1%lo", "bad_host"}) {
            assertInvalid(() -> new GrpcNetworkConfiguration(host, 0, "localhost", 0));
            assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, host, 0));
        }
        for (int port : new int[] {-1, 65536}) {
            assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", port, "localhost", 0));
            assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, "localhost", port));
        }
        assertInvalid(() -> GrpcNetworkConfiguration.loopback().endpoint(0));
        assertInvalid(() -> new GrpcTransportProvider(null, null));
    }

    @Test void rejectsWildcardMulticastAndUnresolvedDestinationsBeforeListening() {
        assertInvalid(() -> new GrpcNetworkConfiguration("0.0.0.0", 0, "0.0.0.0", 0));
        assertInvalid(() -> new GrpcNetworkConfiguration("::", 0, "::", 0));
        assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, "0:0:0:0:0:0:0:0", 0).bindAddress());
        assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, "224.0.0.1", 0).bindAddress());
        assertInvalid(() -> new GrpcNetworkConfiguration("224.0.0.1", 0, "127.0.0.1", 0).bindAddress());
        assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, "255.255.255.255", 0).bindAddress());
        assertInvalid(() -> new GrpcNetworkConfiguration("unresolved.invalid", 0, "127.0.0.1", 0).bindAddress());
        assertInvalid(() -> new GrpcNetworkConfiguration("127.0.0.1", 0, "unresolved.invalid", 0).bindAddress());
    }

    private static void assertInvalid(final org.junit.jupiter.api.function.Executable action) {
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, action).code());
    }
}
