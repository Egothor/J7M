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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Native compensation decision is atomic with step progression and preserves uncertain origin.
 */
class SagaControlTest {
    private ExecutionStateMachine machine = new ExecutionStateMachine("execution"); private long index;
    private ExecutionResult apply(String operation,String request,String... args) {
        byte[] command = new ResourceCommand(operation,"tenant",request,List.of(args)).encode(); machine.validate(command,"tenant",true); machine.apply(++index,command);
        return ExecutionResult.decode(machine.query(new ResourceCommand("E_RESULT","tenant","query",List.of(request)).encode()));
    }
    private ExecutionResult status() { return ExecutionResult.decode(machine.query(new ResourceCommand("E_RUN","tenant","query",List.of("s")).encode())); }
    private String start() {
        apply("S_START","start","s","input","2","10",ExecutionStateMachine.encodeContext(Map.of()));
        return apply("S_CLAIM","claim","s","worker","100").fields().get("fence");
    }
    private void time(long now) { machine.apply(++index,machine.timeCommand(now)); }
    @Test void compensationDecisionRevokesOwnerAndCannotRaceAForwardStepOrLoseUnknownAfterClaim() {
        String fence = start();
        apply("S_STEP","one","s","worker",fence,"one","one-key","undo-one","true","false");
        apply("S_STEP_COMPLETE","one-done","s","worker",fence,"one","one-result");
        apply("S_STEP","two","s","worker",fence,"two","two-key","undo-two","true","true");
        var before = status();
        byte[] denied = new ResourceCommand("S_REQUEST_COMPENSATION","tenant","denied",List.of("s",before.fields().get("version"))).encode();
        assertThrows(SecurityException.class,() -> machine.validate(denied,"tenant",false));
        var decision = apply("S_REQUEST_COMPENSATION","decision","s",before.fields().get("version"));
        assertEquals("UNKNOWN",decision.status()); assertEquals("true",decision.fields().get("compensationRequested"));
        assertEquals(decision,apply("S_REQUEST_COMPENSATION","decision","s",before.fields().get("version")));
        assertEquals("FENCED",apply("S_STEP_COMPLETE","late-completion","s","worker",fence,"two","late").status());
        var next = apply("S_CLAIM","takeover","s","next","100"); assertEquals("UNKNOWN",next.status());
        var snapshot = machine.snapshot(); var restored = new ExecutionStateMachine("execution"); restored.restore(snapshot); assertArrayEquals(snapshot,restored.snapshot()); machine=restored;
        assertEquals("COMPENSATING",apply("S_RECONCILE","reconcile","s","two",status().fields().get("version"),"false","no-effect-established").status());
        assertEquals("CONFLICT",apply("S_STEP","forbidden-next","s","next",next.fields().get("fence"),"three","three-key","undo","true","true").status());
        assertEquals("COMPENSATING",apply("S_COMPENSATE","undo","s","next",next.fields().get("fence"),"one","false").status());
        assertEquals("COMPENSATED",apply("S_COMPENSATION_RESULT","undone","s","next",next.fields().get("fence"),"one","true","undone").status());
    }
    @Test void explicitKnownNoEffectFailureBlocksTheNextStepWithoutASecondDecisionWindow() {
        String fence=start(); apply("S_STEP","one","s","worker",fence,"one","key","undo","true","false");
        var failure=apply("S_STEP_RESOLVE","failed","s","worker",fence,"one","FAILED_NO_EFFECT","established-rejection");
        assertEquals("true",failure.fields().get("compensationRequested"));
        assertEquals("COMPENSATED",apply("S_STEP","next","s","worker",fence,"two","key2","undo","true","true").status());
        assertEquals("FAILED",failure.fields().get("stepStatus"));
    }
    @Test void completedSagaCanBeExplicitlyCompensatedAndCompensationUnknownRetainsItsOrigin() {
        String fence=start(); apply("S_STEP","one","s","worker",fence,"one","key","undo","true","true");
        assertEquals("COMPLETED",apply("S_STEP_COMPLETE","done","s","worker",fence,"one","result").status());
        assertEquals("COMPENSATING",apply("S_REQUEST_COMPENSATION","decision","s",status().fields().get("version")).status());
        var next=apply("S_CLAIM","next","s","next","100"); assertEquals("COMPENSATING",next.status());
        apply("S_COMPENSATE","undo","s","next",next.fields().get("fence"),"one","true");
        assertEquals("UNKNOWN",apply("S_STEP_RESOLVE","unknown","s","next",next.fields().get("fence"),"one","UNKNOWN","reply-lost").status());
        time(101); assertEquals("UNKNOWN",apply("S_CLAIM","again","s","other","100").status());
        assertEquals("COMPENSATED",apply("S_RECONCILE","established","s","one",status().fields().get("version"),"true","undo-established").status());
    }
    @Test void delayedForwardEvidenceCannotResolveALaterUncertainCompensationAtADifferentVersion() {
        String fence=start(); apply("S_STEP","one","s","worker",fence,"one","key","undo","false","true");
        apply("S_STEP_RESOLVE","unknown-forward","s","worker",fence,"one","UNKNOWN","reply-lost");
        String forwardVersion=status().fields().get("version");
        assertEquals("COMPLETED",apply("S_RECONCILE","forward-established","s","one",forwardVersion,"true","done").status());
        apply("S_REQUEST_COMPENSATION","decision","s",status().fields().get("version"));
        var next=apply("S_CLAIM","next","s","next","100");
        apply("S_COMPENSATE","inverse","s","next",next.fields().get("fence"),"one","false");
        apply("S_STEP_RESOLVE","unknown-inverse","s","next",next.fields().get("fence"),"one","UNKNOWN","inverse-reply-lost");
        String inverseVersion=status().fields().get("version");
        assertEquals("CONFLICT",apply("S_RECONCILE","stale-forward-evidence","s","one",forwardVersion,"true","done").status());
        assertEquals("UNKNOWN",status().status()); assertEquals(inverseVersion,status().fields().get("version"));
        assertEquals("COMPENSATED",apply("S_RECONCILE","inverse-established","s","one",inverseVersion,"true","inverse-done").status());
    }
    private EventJournal.Page auditPage() throws java.io.IOException {
        byte[] query=new ResourceCommand("E_POLL","tenant","query",List.of("audit","256")).encode();
        try(var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(machine.query(query)))) {
            assertEquals(0,in.readInt());return ResourceWire.page(in);
        }
    }
    @Test void authorizedReconciliationRecordsAnAtomicPayloadFreeAuditExactlyOnceAndSurvivesRestore() throws Exception {
        apply("E_SUBSCRIBE","audit-sub","audit","S_RECONCILE_AUDIT","execution","0","0","1000");
        String fence=start();apply("S_STEP","intent","s","worker",fence,"one","private-external-key","private-compensation-input","false","true");
        apply("S_STEP_RESOLVE","unknown","s","worker",fence,"one","UNKNOWN","private-failure-detail");
        String observed=status().fields().get("version");long before=index;
        var denied=new ResourceCommand("S_RECONCILE","tenant","denied",List.of("s","one",observed,"true","private-result")).encode();
        assertThrows(SecurityException.class,()->machine.validate(denied,"tenant",false));assertEquals(before,index);assertTrue(auditPage().events().isEmpty());
        var result=apply("S_RECONCILE","established","s","one",observed,"true","private-result");assertEquals("COMPLETED",result.status());
        assertEquals(result,apply("S_RECONCILE","established","s","one",observed,"true","private-result"));
        assertEquals("CONFLICT",apply("S_RECONCILE","stale","s","one",observed,"true","other-private-result").status());
        var page=auditPage();assertEquals(1,page.events().size());var event=page.events().getFirst();assertEquals("tenant",event.scope());assertEquals("S_RECONCILE_AUDIT",event.operation());
        var audit=ExecutionResult.decode(event.payload());assertEquals("COMPLETED",audit.status());
        assertEquals(Map.of("entity","s","step","one","observedVersion",observed,"committedVersion",result.fields().get("version"),"decision","SUCCEEDED"),audit.fields());
        assertTrue(event.payload().length<1024);
        var restored=new ExecutionStateMachine("execution");restored.restore(machine.snapshot());machine=restored;
        assertArrayEquals(event.payload(),auditPage().events().getFirst().payload());assertEquals("private-result",status().fields().get("result"),"Domain recovery result is preserved separately");
    }
    @Test void freshVerificationRejectsExpiredOwnersWithoutExtendingLeaseOrTreatingHistoricalReplayAsAuthority() {
        String fence=start();apply("S_STEP","intent","s","worker",fence,"one","key","input","true","true");
        var before=status();var live=apply("S_VERIFY","physical-one","s","worker",fence);
        assertEquals("COMPLETING",live.status());assertEquals(before.fields().get("version"),live.fields().get("version"));assertEquals(before.fields().get("expires"),live.fields().get("expires"));assertEquals(before,status());
        assertEquals(Set.of("owner","fence","expires","version"),live.fields().keySet());
        time(101);assertEquals(live,apply("S_VERIFY","physical-one","s","worker",fence),"Historical receipts remain historical");
        assertEquals("FENCED",apply("S_VERIFY","physical-two","s","worker",fence).status());assertEquals(before,status());
        var next=apply("S_CLAIM","next","s","next","100");assertTrue(Long.parseLong(next.fields().get("fence"))>Long.parseLong(fence));
        assertEquals("FENCED",apply("S_VERIFY","physical-old","s","worker",fence).status());
        assertEquals("COMPLETING",apply("S_VERIFY","physical-new","s","next",next.fields().get("fence")).status());
    }
}
