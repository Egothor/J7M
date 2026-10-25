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
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;

class ManagedSagaProgramTest {
    @SagaDefinition(name="orders",policy="missing") public static class UnknownProfile extends ManagedSagaFactoryTest.Flow { }
    @SagaDefinition(name="orders") public static class BrokenOrder extends ManagedSagaFactoryTest.Flow {
        @Override @SagaStep(name="reserve",order=4) public String reserve(String input) {throw new AssertionError("No constructor/body");}
    }
    @SagaDefinition(name="orders") public static class Reconciler extends ManagedSagaFactoryTest.Flow {
        @ReconciliationHandler public ManagedSagaDecision reconcile(ManagedSagaUncertainty input) {return new ManagedSagaDecision(ManagedSagaDecision.Outcome.UNRESOLVED,null);}
    }
    @Test void actualShapeProfileAndDescriptorAreValidatedWithoutConstructingAComponent() {
        var profile=ManagedSagaFactoryTest.profile(new SagaEndpoint(),true);
        try(var factory=new ManagedSagaFactory(Map.of("default",profile),1,1)) {
            var good=new ManagedSagaFactoryTest.Adapter(ManagedSagaFactoryTest.Flow.class);
            assertDoesNotThrow(()->factory.validate(good.feature(),good,ManagedSagaFactoryTest.BUNDLE));
            factory.validateComponent(ManagedSagaFactoryTest.Flow.class,true);
            assertThrows(IllegalArgumentException.class,()->factory.validateComponent(ManagedSagaFactoryTest.Flow.class,false));
            assertEquals(org.j7m.middleware.application.components.ManagedInvocationPolicy.Phase.REPLAY,factory.phase());
            for(var type:List.of(UnknownProfile.class,BrokenOrder.class)) {var adapter=new ManagedSagaFactoryTest.Adapter(type);assertThrows(IllegalArgumentException.class,()->factory.validate(adapter.feature(),adapter,ManagedSagaFactoryTest.BUNDLE));}
            var forged=new ManagedHostedFeature("saga","orders",2,Map.of("policy","default"),good.feature().methods());
            assertThrows(IllegalArgumentException.class,()->factory.validate(forged,good,ManagedSagaFactoryTest.BUNDLE));
            assertThrows(IllegalArgumentException.class,()->factory.bind(good.feature(),good,new Object(),ManagedSagaFactoryTest.BUNDLE,(method,decoder,options)->java.util.concurrent.CompletableFuture.completedFuture(null)));
        }
        var annotation=ManagedSagaFactoryTest.Flow.class.getAnnotation(SagaDefinition.class);assertEquals("orders",annotation.name());assertEquals(1,annotation.version());assertEquals("default",annotation.policy());
    }
    @Test void dataFramesBindExactProgramAndRejectMalformedOrOversizedInput() {
        var adapter=new ManagedSagaFactoryTest.Adapter(ManagedSagaFactoryTest.Flow.class);var profile=ManagedSagaFactoryTest.profile(new SagaEndpoint(),true);
        var program=new ManagedSagaProgram(adapter.feature(),adapter,ManagedSagaFactoryTest.BUNDLE,profile);
        String frame=program.input("run","value");var input=program.input(frame);assertEquals(program.fingerprint,input.fingerprint());assertEquals("run",input.logicalId());assertEquals("value",profile.codec().decode(input.payload(),String.class));
        assertEquals("value",program.value(program.frame("value",String.class),String.class));
        assertThrows(IllegalArgumentException.class,()->program.input("malformed"));assertThrows(IllegalArgumentException.class,()->program.nativeId(""));assertNotEquals(program.nativeId("order/a:1"),program.nativeId("order/b:1"));
        assertThrows(RuntimeException.class,()->program.input("run","x".repeat(10000)));
        var changed=new ManagedSagaProgram(adapter.feature(),adapter,new org.j7m.middleware.application.components.ManagedHostedResources.Bundle("b".repeat(64),Map.of()),profile);
        assertNotEquals(program.fingerprint,changed.fingerprint);assertEquals(program.nativeId("run"),changed.nativeId("run"));
    }
}
