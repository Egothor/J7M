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
import java.time.Duration;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.junit.jupiter.api.Test;

class ManagedInboxPolicyTest {
    public static class Bad { @InboxHandler public void on(String value) { } }
    @Test void preflightRequiresRealHostedJdbcMetadataAndNamedCapabilityWithoutOpeningConnection() {
        var registration = new ManagedJdbcTransactionPolicy.Registration(() -> { throw new AssertionError("No setup connection"); }, Duration.ofSeconds(1));
        var policy = new ManagedInboxPolicy(Map.of("default", new ManagedInboxPolicy.Registration("inbox", "tenant", "default", registration, null)));
        assertEquals(ManagedInvocationPolicy.Phase.APPLICATION, policy.phase()); assertEquals(Set.of(InboxHandler.class), policy.annotationTypes());
        var method = new ManagedMethod(Bad.class.getName(), "on", List.of(String.class.getName()), "void");
        var contract = new ManagedServiceContract("hosted.queue-consumer.bad", 1, method);
        assertThrows(IllegalArgumentException.class, () -> policy.validateHosted(contract, Bad.class, Set.of(method)));
        var actual = ManagedInboxPolicyIT.method(ManagedInboxPolicyIT.Handler.class);
        var proper = new ManagedServiceContract("hosted.queue-consumer.handler", 1, actual);
        assertThrows(IllegalArgumentException.class, () -> new ManagedInboxPolicy(Map.of()).validateHosted(proper, ManagedInboxPolicyIT.Handler.class, Set.of(actual)));
        policy.validateHosted(proper, ManagedInboxPolicyIT.Handler.class, Set.of(actual));
    }
}
