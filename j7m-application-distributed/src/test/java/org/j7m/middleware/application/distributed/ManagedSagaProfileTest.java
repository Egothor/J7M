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
import org.junit.jupiter.api.Test;

class ManagedSagaProfileTest {
    @Test void finiteTimingAndAttemptBoundsRejectUnsafeLeaseBudgets() {
        var valid=ManagedSagaFactoryTest.profile(new SagaEndpoint(),true);
        assertNotNull(valid.backend());assertNotNull(valid.administration());assertNotNull(valid.events());assertNotNull(valid.codec());
        assertEquals("commerce",valid.namespace());assertEquals(2,valid.maximumCompensationAttempts());
        for(var lease:List.of(Duration.ofMillis(1999),Duration.ofDays(1).plusMillis(1),Duration.ofSeconds(3))) {
            assertThrows(IllegalArgumentException.class,()->new ManagedSagaProfile(valid.backend(),null,valid.codec(),"commerce",lease,valid.pollInterval(),valid.operationBudget(),valid.handlerBudget(),2,valid.compensationBackoff()));
        }
        for(int attempts:List.of(0,1001)) assertThrows(IllegalArgumentException.class,()->new ManagedSagaProfile(valid.backend(),null,valid.codec(),"commerce",valid.lease(),valid.pollInterval(),valid.operationBudget(),valid.handlerBudget(),attempts,valid.compensationBackoff()));
        for(int attempts:List.of(1,1000)) assertEquals(attempts,new ManagedSagaProfile(valid.backend(),null,valid.codec(),"commerce",valid.lease(),valid.pollInterval(),valid.operationBudget(),valid.handlerBudget(),attempts,valid.compensationBackoff()).maximumCompensationAttempts());
        assertFalse(valid.toString().contains("commerce"));
    }
    @Test void reconciliationEvidenceHasExplicitOutcomeAndPayloadFreeDiagnostics() {
        for(var outcome:ManagedSagaDecision.Outcome.values()) {
            var decision=new ManagedSagaDecision(outcome,"private-payload");assertEquals(outcome,decision.outcome());assertEquals("private-payload",decision.result());assertFalse(decision.toString().contains("private"));
        }
        assertThrows(NullPointerException.class,()->new ManagedSagaDecision(null,null));
        var evidence=new ManagedSagaUncertainty("run","step","external-key",true,1,"private-payload");
        assertEquals("run",evidence.sagaId());assertEquals("step",evidence.step());assertEquals("external-key",evidence.idempotencyKey());assertTrue(evidence.compensation());assertEquals(1,evidence.version());assertEquals("private-payload",evidence.input());assertFalse(evidence.toString().contains("private"));
        assertThrows(IllegalArgumentException.class,()->new ManagedSagaUncertainty("run","step","key",false,-1,null));
    }
}
