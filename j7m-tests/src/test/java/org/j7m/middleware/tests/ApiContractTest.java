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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RemoteError;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for public API value contracts and error metadata.
 */
public final class ApiContractTest {
    @Test
    public void objectRefDefensivelyCopiesCollections() {
        List<String> interfaces = new java.util.ArrayList<>(List.of("example.Api"));
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("zone", "test"));

        ObjectRef ref = new ObjectRef("object-1", "", "service", "runtime", "", interfaces, "grpc", "endpoint", 100L, 1L, metadata);
        interfaces.add("other.Api");
        metadata.put("zone", "changed");

        assertEquals(List.of("example.Api"), ref.interfaces());
        assertEquals("test", ref.metadata().get("zone"));
        assertEquals("service/object-1", ref.logicalName());
        assertEquals("default", ref.registryContext());
    }

    @Test
    public void middlewareExceptionCarriesRemoteFailureMetadata() {
        RemoteError error = new RemoteError(
                MiddlewareErrorCode.TRANSPORT_ERROR,
                "transport failed",
                Map.of("transport", "grpc"),
                "invoke-1",
                "remote-runtime",
                true,
                true);

        MiddlewareException exception = new MiddlewareException(error);

        assertEquals(MiddlewareErrorCode.TRANSPORT_ERROR, exception.code());
        assertEquals("invoke-1", exception.remoteError().invocationId());
        assertEquals("remote-runtime", exception.remoteError().remoteRuntimeId());
        assertTrue(exception.retryable());
        assertTrue(exception.dirtyTarget());
    }

    @Test
    public void objectRefRequiresConcreteObjectIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectRef("", "", "service", "runtime", "default",
                List.of(), "grpc", "endpoint", 0L, 1L, Map.of()));
    }
}
