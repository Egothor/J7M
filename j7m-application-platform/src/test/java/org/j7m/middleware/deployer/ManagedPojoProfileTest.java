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

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationRepository;
import org.junit.jupiter.api.Test;

/**
 * Managed annotation hosting is explicit; unknown profiles never reach repository execution.
 */
class ManagedPojoProfileTest {
    @Test
    void unknownProfilesFailBeforeFetchingAndLegacyDefaultsRemainExplicitlyCompatible() {
        AtomicInteger fetches = new AtomicInteger();
        ApplicationRepository repository = (ApplicationRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ApplicationRepository.class},
                (proxy, method, arguments) -> { fetches.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "fixture repository miss"); });
        MiddlewareRuntime runtime = (MiddlewareRuntime) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MiddlewareRuntime.class},
                (proxy, method, arguments) -> { throw new AssertionError("runtime must not be consulted"); });
        ApplicationManager manager = new ApplicationManager(runtime, repository);
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("fixture:pojo:1");
        var invalid = assertThrows(MiddlewareException.class, () -> manager.activate(coordinate,
                new ApplicationActivationRequest("test", "test", true, Map.of("j7m.annotations.profile", "automatic"))));
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, invalid.code());
        assertEquals(0, fetches.get());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> manager.activate(coordinate,
                new ApplicationActivationRequest("test", "test", false, Map.of("j7m.annotations.profile", "managed-v1")))).code());
        assertEquals(0, fetches.get());
        assertEquals(MiddlewareErrorCode.NOT_FOUND, assertThrows(MiddlewareException.class, () -> manager.activate(coordinate, null)).code());
        assertEquals(1, fetches.get());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> manager.activate(coordinate,
                new ApplicationActivationRequest("test", "test", true, Map.of("j7m.annotations.profile", "managed-v1", "verificationProfile", "development")))).code());
        assertEquals(1, fetches.get());
    }
}
