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
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ManagedQueueDeliveryContextTest {
    static QueueConsumerProfile profile() {
        var schema = new SchemaRegistry(List.of());
        var source = new ReplicatedQueue("partition", "tenant", (p, d) -> { throw new AssertionError("No context metadata read may access the backend"); });
        return new QueueConsumerProfile(source, null, new ManagedPayloadCodec(new DefaultValueCodec(schema), schema), "tenant", "logical", "group", "worker", Set.of("queue"),
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofHours(1), QueueConsumerProfile.Strategy.INBOX);
    }
    @Test void metadataAndEnvelopeAreOwnerBoundDefensiveAndUnavailableAfterReleaseOrInAnotherCall() throws Exception {
        var retained = new AtomicReference<ManagedQueueDeliveryContext>(); byte[] payload = new byte[32768]; payload[0] = 7;
        QueueDelivery delivery = new QueueDelivery("queue", "group", "worker", 1, "message", payload, 1, 1, 1); payload[0] = 9;
        ManagedInvocationPolicy.Binding binding = new ManagedInvocationPolicy.Binding() {
            @Override public Object invoke(InvocationContext invocation, Object[] arguments, ManagedInvocationPolicy.Proceed next) throws Exception {
                var context = new ManagedQueueDeliveryContext(profile(), "handler", 1, delivery); next.putContext(ManagedQueueDeliveryContext.class, context);
                retained.set(context); return next.invoke();
            }
            @Override public void close() { }
        };
        TransactionInvocation.invoke(binding, frame -> {
            var current = retained.get(); assertSame(delivery, current.delivery()); assertEquals(7, current.envelope()[0]);
            byte[] copy = current.envelope(); copy[0] = 1; assertEquals(7, current.envelope()[0]); assertEquals(32768, current.envelope().length);
            assertEquals(64, current.inboxId().length()); return null;
        });
        assertThrows(IllegalStateException.class, () -> retained.get().delivery());
        assertThrows(IllegalStateException.class, () -> retained.get().envelope());
        ManagedQueueDeliveryContext old = retained.get();
        TransactionInvocation.invoke(binding, frame -> { assertThrows(IllegalStateException.class, old::delivery); assertThrows(IllegalStateException.class, old::envelope); return null; });
    }
    @Test void profileLimitsRejectUnsafeRenewalWindowAndMissingExternalGuard() {
        var p = profile();
        assertThrows(NullPointerException.class, () -> new QueueConsumerProfile(p.source(), null, p.codec(), p.tenant(), p.namespace(), p.group(), p.member(), p.queues(),
                p.visibility(), p.operationBudget(), p.handlerBudget(), p.retention(), QueueConsumerProfile.Strategy.EXTERNAL_GUARD));
        assertThrows(IllegalArgumentException.class, () -> new QueueConsumerProfile(p.source(), null, p.codec(), p.tenant(), p.namespace(), p.group(), p.member(), p.queues(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), p.handlerBudget(), p.retention(), QueueConsumerProfile.Strategy.INBOX));
        assertFalse(p.toString().contains("tenant"));
    }
}
