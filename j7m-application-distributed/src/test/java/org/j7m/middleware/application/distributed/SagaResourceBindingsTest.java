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
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class SagaResourceBindingsTest {
    private static ManagedDependency dependency(Class<?> type,String name,boolean provider) {return new ManagedDependency(type,"",List.of(),provider,Map.of("resource.name",name,"binding.refresh","NEVER","binding.policy","default"));}
    @Test void contextProviderResolvesOnlyItsActualCallbackAndClientCloseRevokesBorrowedFacade() throws Exception {
        try(var host=new ManagedSagaFactoryTest.Host(new ManagedSagaFactoryTest.Flow(),new SagaEndpoint())) {
            var resources=new ResourceBindingFactory(Map.of("context",ResourceBindingFactory.sagaContext(),"client",ResourceBindingFactory.saga(host.client)),Set.of("default"));
            assertThrows(IllegalArgumentException.class,()->resources.validate(dependency(ManagedSagaContext.class,"context",false),Scope.Kind.ACTIVATION));
            var contextEdge=dependency(ManagedSagaContext.class,"context",true);resources.validate(contextEdge,Scope.Kind.ACTIVATION);
            var context=resources.bind(contextEdge);assertThrows(IllegalStateException.class,context::get);
            var observed=new ArrayBlockingQueue<ManagedSagaContext>(1);host.flow.inspect=value->{assertSame(value,context.get());observed.add(value);};
            var clientBinding=resources.bind(dependency(ManagedSagaClient.class,"client",false));var client=(ManagedSagaClient)clientBinding.get();
            client.start("start","run","input",Instant.now().plusSeconds(1));assertNotNull(observed.poll(3,TimeUnit.SECONDS));
            clientBinding.close();assertThrows(org.j7m.middleware.api.MiddlewareException.class,()->client.status("run",Instant.now().plusSeconds(1)));
            context.close();assertThrows(IllegalStateException.class,context::get);
        }
    }
}
