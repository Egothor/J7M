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
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ManagedSagaContextTest {
    @Test void stagedEffectsHaveAnExactFiniteCapacityAndCannotEscapeTheCurrentCallback() throws Exception {
        var flow=new ManagedSagaFactoryTest.Flow();var checked=new ArrayBlockingQueue<ManagedSagaContext>(1);
        flow.inspect=context->{
            assertFalse(context.revoked());assertThrows(IllegalArgumentException.class,()->context.stageEffect("reservation",0,"duplicate"));
            assertThrows(IllegalArgumentException.class,()->context.stageEffect("bad/key",0,"value"));
            assertThrows(IllegalArgumentException.class,()->context.stageEffect("key",-1,"value"));
            assertThrows(IllegalArgumentException.class,()->context.stageEffect("key",0,"x".repeat(4097)));
            for(int i=0;i<15;i++) context.stageEffect("key"+i,0,"value");
            assertThrows(IllegalArgumentException.class,()->context.stageEffect("extra",0,"value"));checked.add(context);
        };
        try(var host=new ManagedSagaFactoryTest.Host(flow,new SagaEndpoint())) {
            host.client.start("start","run","input",Instant.now().plusSeconds(1));var captured=checked.poll(3,TimeUnit.SECONDS);assertNotNull(captured);
            host.binding.stopAdmission();assertTrue(host.binding.drain(Duration.ofSeconds(2)));
            assertTrue(captured.revoked());assertThrows(org.j7m.middleware.api.MiddlewareException.class,captured::sagaId);
            assertThrows(org.j7m.middleware.api.MiddlewareException.class,()->captured.stageEffect("late",0,"forbidden"));
        }
    }
}
