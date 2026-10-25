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

package org.j7m.middleware.annotations;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.annotation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Public metadata contracts tested in their actual host adapter consumer module; no API test dependency.
 */
class DeliveryAnnotationsTest {
    interface Producer {
        @QueuePublisher(queue="orders") long publish(String id,String value);
        @OutboxPublisher("sales") long enqueue(String id,String aggregate,String value);
    }
    static class Handler {
        @DurableQueueConsumer(queue="orders") @InboxHandler public void on(String input) { }
        @DeadLetterHandler(queue="orders",value="poison",policy="restricted",version=2) public void observe(String input) { }
    }
    @Test void publicProducerAndHandlerMetadataRetainDistinctLogicalIdentityAndPolicyDefaults() throws Exception {
        var publish=Producer.class.getMethod("publish",String.class,String.class).getAnnotation(QueuePublisher.class);
        assertEquals("orders",publish.queue());assertEquals("default",publish.policy());assertEquals(1,publish.version());
        var outbox=Producer.class.getMethod("enqueue",String.class,String.class,String.class).getAnnotation(OutboxPublisher.class);
        assertEquals("sales",outbox.value());assertEquals("default",outbox.policy());assertEquals(1,outbox.version());
        var method=Handler.class.getMethod("on",String.class);var consumer=method.getAnnotation(DurableQueueConsumer.class);
        assertEquals("orders",consumer.queue());assertEquals("",consumer.value());assertEquals(1,consumer.version());assertEquals("default",consumer.policy());
        assertEquals("default",method.getAnnotation(InboxHandler.class).policy());
        var observer=Handler.class.getMethod("observe",String.class).getAnnotation(DeadLetterHandler.class);
        assertEquals("orders",observer.queue());assertEquals("poison",observer.value());assertEquals(2,observer.version());assertEquals("restricted",observer.policy());
        assertNull(Producer.class.getAnnotation(QueuePublisher.class),"A method capability is not an interface-level blanket grant");
    }
    @Test void deliveryMarkersRemainRuntimeMethodMetadataWithoutInheritedOrComposedAuthority() {
        for(Class<? extends Annotation> marker:List.of(QueuePublisher.class,OutboxPublisher.class,DurableQueueConsumer.class,DeadLetterHandler.class,InboxHandler.class)) {
            assertEquals(RetentionPolicy.RUNTIME,marker.getAnnotation(Retention.class).value());
            assertEquals(Set.of(ElementType.METHOD),Set.of(marker.getAnnotation(Target.class).value()));
            assertFalse(marker.isAnnotationPresent(Inherited.class));assertFalse(marker.isAnnotationPresent(Qualifier.class));
        }
    }
}
