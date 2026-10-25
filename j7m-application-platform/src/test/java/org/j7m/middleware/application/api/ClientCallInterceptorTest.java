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

package org.j7m.middleware.application.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.*;
import org.junit.jupiter.api.Test;

class ClientCallInterceptorTest {
    interface Business { String run(); }
    @Test void defaultInterceptorMakesExactlyOneAttemptAndDoesNotInterpretFailureDetails() throws Exception {
        var method = Business.class.getMethod("run");
        var identity = new ManagedMethod(Business.class.getName(), "run", List.of(), String.class.getName());
        var reference = new ObjectRef("object", "service/object", "service", "runtime", "tenant", List.of(Business.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());
        var call = new ClientCall(new ManagedServiceContract("business", 1, identity), Business.class, method, reference, 7);
        var effects = new AtomicInteger();
        var attempt = new ClientAttempt() {
            @Override public Object execute() { effects.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "unknown"); }
            @Override public boolean businessEntered() { return true; }
        };
        var interceptor = ClientCallInterceptor.singleAttempt(); assertDoesNotThrow(() -> interceptor.validate(Business.class));
        assertThrows(MiddlewareException.class, () -> interceptor.invoke(call, attempt)); assertEquals(1, effects.get());
    }
}
