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
import org.j7m.middleware.api.ObjectRef;
import org.junit.jupiter.api.Test;

class ClientCallTest {
    public interface Business { String find(String key); }
    @Test void completeSignatureIsRequiredAndNegativeMonotonicDeadlineRemainsValid() throws Exception {
        var method = Business.class.getMethod("find", String.class);
        var identity = new ManagedMethod(Business.class.getName(), "find", List.of(String.class.getName()), String.class.getName());
        var target = new ObjectRef("object", "service/object", "service", "runtime", "tenant", List.of(Business.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());
        var contract = new ManagedServiceContract("business", 2, identity);
        var call = new ClientCall(contract, Business.class, method, target, -7);
        assertSame(target, call.target()); assertEquals(-7, call.deadlineNanos()); assertEquals(2, call.contract().version());
        var mismatch = new ManagedServiceContract("business", 2, new ManagedMethod(Business.class.getName(), "find", List.of(), String.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> new ClientCall(mismatch, Business.class, method, target, -7));
        assertThrows(NullPointerException.class, () -> new ClientCall(contract, Business.class, null, target, -7));
    }
}
