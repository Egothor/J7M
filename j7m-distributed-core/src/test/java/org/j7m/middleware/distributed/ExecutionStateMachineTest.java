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
import java.io.*;
import org.junit.jupiter.api.Test;

class ExecutionStateMachineTest {
    private final ExecutionStateMachine machine = new ExecutionStateMachine("execution");
    private long index;
    private static final String HASH = "a".repeat(64);
    private ExecutionResult apply(String operation, String request, String... args) {
        List<String> supplied = new ArrayList<>(List.of(args));
        if (operation.equals("R_CLAIM") && supplied.size() == 5) supplied.add("attempt-" + request);
        machine.apply(++index, new ResourceCommand(operation, "tenant", request, supplied).encode());
        if (operation.equals("R_CLAIM")) return ExecutionResult.decode(machine.query(new ResourceCommand("E_RESULT", "tenant", "q", List.of(request, supplied.get(5))).encode()));
        return query("E_RESULT", request);
    }
    private ExecutionResult query(String kind, String id) { return ExecutionResult.decode(machine.query(new ResourceCommand(kind, "tenant", "query", List.of(id)).encode())); }
    private void time(long millis) { machine.apply(++index, machine.timeCommand(millis)); }
    private long startSaga(String id) {
        apply("S_START", "start-" + id, id, "input", "2", "10", ExecutionStateMachine.encodeContext(Map.of()));
        return Long.parseLong(apply("S_CLAIM", "claim-" + id, id, "worker", "10").fields().get("fence"));
    }
    @Test void terminalStepSurvivesLeaseExpiryAndNewOwnerCompletesSaga() {
        time(100); long token = startSaga("s");
        apply("S_STEP", "intent", "s", "worker", Long.toString(token), "last", "external-key", "undo", "true", "true");
        time(111); var claim = apply("S_CLAIM", "takeover", "s", "next", "10");
        var result = apply("S_STEP_COMPLETE", "done", "s", "next", claim.fields().get("fence"), "last", "result");
        assertEquals("COMPLETED", result.status());
    }
    @Test void confirmedCompensationReconciliationCannotRepeatItsExternalEffect() {
        time(100); long token = startSaga("s");
        apply("S_STEP", "intent", "s", "worker", Long.toString(token), "one", "external-key", "undo", "true", "false");
        apply("S_STEP_COMPLETE", "done", "s", "worker", Long.toString(token), "one", "result");
        apply("S_COMPENSATE", "undo-intent", "s", "worker", Long.toString(token), "one", "false");
        time(111); assertEquals("UNKNOWN", apply("S_CLAIM", "takeover", "s", "next", "10").status());
        assertEquals("COMPENSATED", apply("S_RECONCILE", "reconcile", "s", "one", query("E_RUN", "s").fields().get("version"), "true", "confirmed").status());
    }
    @Test void jobMethodsCannotBypassSagaStepsAndSagaMethodsCannotClaimJobs() {
        time(100); long token = startSaga("s");
        assertEquals("WRONG_KIND", apply("J_COMPLETE", "bypass", "s", "worker", Long.toString(token), "result").status());
        apply("J_SUBMIT", "job", "j", "input", ExecutionStateMachine.encodeContext(Map.of()));
        assertEquals("WRONG_KIND", apply("S_CLAIM", "wrong", "j", "worker", "10").status());
    }
    @Test void onlyFirstRpcClaimAcquiresPermissionAndExternalRecoveryIsUnknown() {
        time(100);
        var first = apply("R_CLAIM", "worker-a", "rpc", HASH, "1000", "true", ExecutionStateMachine.encodeContext(Map.of()));
        var second = apply("R_CLAIM", "worker-b", "rpc", HASH, "1000", "true", ExecutionStateMachine.encodeContext(Map.of()));
        assertEquals("true", first.fields().get("acquired")); assertEquals("false", second.fields().get("acquired"));
        assertEquals("UNKNOWN", second.status());
        var copy = new ExecutionStateMachine("execution"); copy.restore(machine.snapshot());
        var after = ExecutionResult.decode(copy.query(new ResourceCommand("E_RPC", "tenant", "q", List.of("rpc")).encode())); assertEquals("UNKNOWN", after.status());
    }
    @Test void retainedRpcExpiryCoversExactOperationReplayAndTraceChangesAreNonsemantic() {
        time(100);
        String firstContext = ExecutionStateMachine.encodeContext(Map.of("traceparent", "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01"));
        String otherContext = ExecutionStateMachine.encodeContext(Map.of("traceparent", "00-" + "3".repeat(32) + "-" + "4".repeat(16) + "-01"));
        var first = apply("R_INVOKE", "invoke", "rpc", HASH, "10", "result", "", firstContext, "false", "value", "0", "once");
        var replay = apply("R_INVOKE", "invoke", "rpc", HASH, "10", "result", "", otherContext, "false", "value", "0", "once"); assertEquals(first, replay); assertEquals("COMPLETED", replay.status()); assertEquals(firstContext, ExecutionStateMachine.encodeContext(replay.context()));
        time(110); assertEquals("EXPIRED", apply("R_INVOKE", "invoke", "rpc", HASH, "10", "result", "", firstContext, "false", "value", "0", "once").status()); assertEquals("EXPIRED", query("E_RPC", "rpc").status());
        assertEquals("EXPIRED", apply("R_INVOKE", "another-op", "rpc", HASH, "10", "result", "", firstContext, "false").status()); assertEquals("once", query("E_VALUE", "value").fields().get("value"));
    }
    @Test void compensationRetryHasCommittedBackoffAndStrictAttemptLimit() {
        time(100); long token = startSaga("s");
        apply("S_STEP", "intent", "s", "worker", Long.toString(token), "step", "key", "undo", "true", "false");
        apply("S_STEP_COMPLETE", "done", "s", "worker", Long.toString(token), "step", "result");
        apply("S_COMPENSATE", "attempt1", "s", "worker", Long.toString(token), "step", "true");
        assertEquals("RETRY", apply("S_COMPENSATION_RESULT", "failed1", "s", "worker", Long.toString(token), "step", "false", "unavailable").fields().get("stepStatus"));
        assertEquals("BACKOFF", apply("S_COMPENSATE", "early", "s", "worker", Long.toString(token), "step", "true").status());
        time(111); var takeover = apply("S_CLAIM", "new-owner", "s", "next", "100"); String fence = takeover.fields().get("fence");
        assertEquals("COMPENSATING", takeover.status()); assertEquals("true", takeover.fields().get("compensationRequested"));
        assertEquals("COMPENSATING", apply("S_COMPENSATE", "attempt2", "s", "next", fence, "step", "true").status());
        assertEquals("EXHAUSTED", apply("S_COMPENSATION_RESULT", "failed2", "s", "next", fence, "step", "false", "unavailable").fields().get("stepStatus"));
        assertEquals("RETRY_EXHAUSTED", apply("S_COMPENSATE", "attempt3", "s", "next", fence, "step", "true").status());
    }
    @Test void inProgressCompletionErrorsAuthorizationSnapshotsAndMalformedCommandsAreBounded() {
        time(100); var claim = apply("R_CLAIM", "claim", "rpc", HASH, "1000", "false", ExecutionStateMachine.encodeContext(Map.of())); assertEquals("IN_PROGRESS", claim.status());
        assertEquals("IN_PROGRESS", apply("R_CLAIM", "duplicate", "rpc", HASH, "1000", "false", ExecutionStateMachine.encodeContext(Map.of())).status());
        assertEquals("FENCED", apply("R_COMPLETE", "bad", "rpc", HASH, "999", "result", "", "false").status());
        assertEquals("ERROR", apply("R_COMPLETE", "done", "rpc", HASH, claim.fields().get("fence"), "", "stored-error", "false").status());
        byte[] snapshot = machine.snapshot(); var restored = new ExecutionStateMachine("execution"); restored.validateSnapshot(snapshot); restored.restore(snapshot); assertArrayEquals(snapshot, restored.snapshot());
        assertNotNull(machine.copyForTransfer()); assertTrue(machine.transferReady()); machine.ownershipChanged("execution", 2); assertThrows(IllegalArgumentException.class, () -> machine.ownershipChanged("execution", 1));
        byte[] command = new ResourceCommand("R_RECONCILE", "tenant", "reconcile", List.of("rpc", HASH, "1", "result", "", "false")).encode();
        assertThrows(SecurityException.class, () -> machine.validate(command, "tenant", false)); assertThrows(SecurityException.class, () -> machine.validate(command, "other", true));
        machine.validateReplayCommand(command); assertEquals("reconcile", machine.requestIdentity(command)); assertFalse(machine.identityConflict(command));
        assertThrows(IllegalArgumentException.class, () -> ExecutionStateMachine.validate(new ResourceCommand("J_CLAIM", "tenant", "id", List.of("job", "worker", "0"))));
        byte[] corrupt = snapshot.clone(); corrupt[3] = 127; byte[] before = machine.snapshot(); assertThrows(IllegalArgumentException.class, () -> machine.restore(corrupt)); assertArrayEquals(before, machine.snapshot());
        assertThrows(IllegalArgumentException.class, () -> ExecutionResult.decode(new byte[0])); assertThrows(IllegalArgumentException.class, () -> new ExecutionResult("", Map.of()));
    }
    @Test void readRefreshCannotConflictWithApplicationIdentityAndRetainedResultsRespectSnapshotBudget() {
        time(100); apply("J_SUBMIT", "read-time", "job", "x".repeat(16384), ExecutionStateMachine.encodeContext(Map.of()));
        byte[] refresh = new ResourceCommand("E_REFRESH", "tenant", "read-time", List.of()).encode(); assertFalse(machine.identityConflict(refresh)); machine.validate(refresh, "tenant", false);
        var claim = apply("J_CLAIM", "claim", "job", "owner", "1000"); String token = claim.fields().get("fence");
        int overloaded = 0;
        for (int i = 0; i < 1500; i++) if (apply("J_CHECKPOINT", "checkpoint-" + i, "job", "owner", token, "small-" + i).status().equals("OVERLOADED")) overloaded++;
        assertTrue(overloaded > 0); assertTrue(machine.snapshot().length < 134217728);
        var restored = new ExecutionStateMachine("execution"); restored.restore(machine.snapshot());
        assertEquals(query("E_RUN", "job"), ExecutionResult.decode(restored.query(new ResourceCommand("E_RUN", "tenant", "q", List.of("job")).encode())));
        assertFalse(query("E_RUN", "job").fields().get("checkpoint").equals("small-1499"));
    }
    @Test void reverseCompensationStatusSelectsEarlierPersistedStepAfterLaterStepCompletes() {
        time(100); long token = startSaga("s");
        for (String name : List.of("first", "second")) {
            apply("S_STEP", "intent-" + name, "s", "worker", Long.toString(token), name, "key-" + name, "undo-" + name, "true", "false");
            apply("S_STEP_COMPLETE", "done-" + name, "s", "worker", Long.toString(token), name, "result-" + name);
        }
        apply("S_COMPENSATE", "comp-second", "s", "worker", Long.toString(token), "second", "true");
        apply("S_COMPENSATION_RESULT", "undone-second", "s", "worker", Long.toString(token), "second", "true", "undone");
        var selected = apply("S_COMPENSATE", "comp-first", "s", "worker", Long.toString(token), "first", "true");
        assertEquals("first", selected.fields().get("step")); assertEquals("key-first", selected.fields().get("idempotencyKey")); assertEquals("undo-first", selected.fields().get("compensation"));
        assertEquals("COMPENSATING", selected.fields().get("stepStatus")); assertEquals(selected, query("E_RUN", "s"));
    }
    @Test void claimPermitIsBoundToPhysicalAttemptAndNeverExposedByGenericReceipt() {
        time(100); String context = ExecutionStateMachine.encodeContext(Map.of());
        var first = apply("R_CLAIM", "operation", "rpc", HASH, "1000", "true", context, "physical-first"); assertEquals("true", first.fields().get("acquired")); assertFalse(first.fields().containsKey("claimAttempt"));
        var repeated = apply("R_CLAIM", "operation", "rpc", HASH, "1000", "true", context, "physical-next"); assertEquals("UNKNOWN", repeated.status()); assertEquals("false", repeated.fields().get("acquired"));
        assertEquals("false", query("E_RESULT", "operation").fields().get("acquired"));
        var copy = new ExecutionStateMachine("execution"); copy.restore(machine.snapshot());
        var recovered = ExecutionResult.decode(copy.query(new ResourceCommand("E_RESULT", "tenant", "q", List.of("operation", "physical-recovery")).encode())); assertEquals("UNKNOWN", recovered.status()); assertEquals("false", recovered.fields().get("acquired"));
    }
    @Test void boundedStepPagesRequireOneStableVersionAndLatestRecoverySelectsActiveOlderStep() throws Exception {
        time(100); long token = startSaga("s");
        for (int i = 0; i < 9; i++) { String name = "step-" + i; apply("S_STEP", "intent-" + i, "s", "worker", Long.toString(token), name, "key-" + i, "undo-" + i, "true", "false"); apply("S_STEP_COMPLETE", "done-" + i, "s", "worker", Long.toString(token), name, "done"); }
        byte[] firstPage = machine.query(new ResourceCommand("E_STEPS", "tenant", "q", List.of("s", "0", "8", "0")).encode());
        var first = new DataInputStream(new ByteArrayInputStream(firstPage)); assertEquals(0, first.readInt()); long version = first.readLong(); assertEquals(9, first.readInt()); assertEquals(8, first.readInt());
        var last = new DataInputStream(new ByteArrayInputStream(machine.query(new ResourceCommand("E_STEPS", "tenant", "q", List.of("s", "8", "8", Long.toString(version))).encode()))); assertEquals(0, last.readInt()); assertEquals(version, last.readLong()); assertEquals(9, last.readInt()); assertEquals(1, last.readInt());
        apply("S_COMPENSATE", "comp-last", "s", "worker", Long.toString(token), "step-8", "true");
        var stale = new DataInputStream(new ByteArrayInputStream(machine.query(new ResourceCommand("E_STEPS", "tenant", "q", List.of("s", "8", "8", Long.toString(version))).encode()))); assertEquals(1, stale.readInt());
        var restored = new ExecutionStateMachine("execution"); restored.restore(machine.snapshot());
        var selected = ExecutionResult.decode(restored.query(new ResourceCommand("E_RUN", "tenant", "q", List.of("s")).encode())); assertEquals("step-8", selected.fields().get("step")); assertEquals("COMPENSATING", selected.fields().get("stepStatus"));
    }

    @Test void renewPreservesFenceButTerminalJobCannotChangeResultCheckpointOrProtectedEffects() {
        time(100); apply("J_SUBMIT","submit","job","input",ExecutionStateMachine.encodeContext(Map.of()));
        var claim=apply("J_CLAIM","claim","job","worker","10"); String token=claim.fields().get("fence");
        time(105); var renewed=apply("J_RENEW","renew","job","worker",token,"20");
        assertEquals(token,renewed.fields().get("fence")); assertEquals("125",renewed.fields().get("expires"));
        var done=apply("J_COMPLETE","complete","job","worker",token,"original","effect","0","one");
        assertEquals("COMPLETED",done.status()); var terminal=query("E_RUN","job");
        assertEquals("CONFLICT",apply("J_COMPLETE","complete-again","job","worker",token,"changed","other","0","two").status());
        assertEquals("CONFLICT",apply("J_CHECKPOINT","late-checkpoint","job","worker",token,"changed").status());
        assertEquals("CONFLICT",apply("J_RENEW","late-renew","job","worker",token,"20").status());
        assertEquals(terminal,query("E_RUN","job")); assertEquals("NOT_FOUND",query("E_VALUE","other").status());
        assertEquals(done,apply("J_COMPLETE","complete","job","worker",token,"original","effect","0","one"));
        var restored=new ExecutionStateMachine("execution"); restored.restore(machine.snapshot());
        assertEquals(terminal,ExecutionResult.decode(restored.query(new ResourceCommand("E_RUN","tenant","q",List.of("job")).encode())));
    }

    @Test void expiredOrCancelledClaimCannotRenewItsOwnership() {
        time(100); apply("J_SUBMIT","submit","job","input",ExecutionStateMachine.encodeContext(Map.of()));
        var claim=apply("J_CLAIM","claim","job","worker","10"); String token=claim.fields().get("fence");
        time(110); assertEquals("FENCED",apply("J_RENEW","expired","job","worker",token,"20").status());
        var next=apply("J_CLAIM","next","job","next","10"); assertNotEquals(token,next.fields().get("fence"));
        apply("J_CANCEL","cancel","job");
        assertEquals("CONFLICT",apply("J_RENEW","cancelled","job","next",next.fields().get("fence"),"20").status());
    }

    @Test void runPagesCrossAggregateFrameBoundWithoutChangingRunLayoutOrLeakingAnotherScope() throws Exception {
        for(int i=0;i<190;i++) apply("J_SUBMIT","submit-"+i,String.format("job-%03d",i),"x".repeat(12000),ExecutionStateMachine.encodeContext(Map.of()));
        assertThrows(IllegalStateException.class,()->machine.query(new ResourceCommand("E_SNAPSHOT_TAIL","tenant","q",List.of()).encode()));
        String after="", upper=""; boolean initial=true, more; var ids=new TreeSet<String>();
        do {
            byte[] encoded=machine.query(new ResourceCommand("E_RUNS","tenant","q",List.of("job-",after,upper,"256",Boolean.toString(initial))).encode());
            assertTrue(encoded.length<=ResourceWire.MAX_FRAME);
            try(var in=new DataInputStream(new ByteArrayInputStream(encoded))) {
                assertEquals(1,in.readInt());in.readLong();ResourceWire.cursor(in);String bound=ResourceWire.text(in);
                if(initial) upper=bound; else assertEquals(upper,bound);
                more=in.readBoolean();int count=in.readInt();assertTrue(count>0&&count<=256);
                for(int i=0;i<count;i++){String id=ResourceWire.text(in);int size=in.readInt();assertEquals("READY",ExecutionResult.decode(in.readNBytes(size)).status());assertTrue(ids.add(id));after=id;}
                assertEquals(0,in.available());
            }
            initial=false;
        } while(more);
        assertEquals(190,ids.size());assertEquals("job-189",upper);
        byte[] foreign=machine.query(new ResourceCommand("E_RUNS","other","q",List.of("","","","256","true")).encode());
        try(var in=new DataInputStream(new ByteArrayInputStream(foreign))){in.readInt();in.readLong();ResourceWire.cursor(in);assertEquals("",ResourceWire.text(in));assertFalse(in.readBoolean());assertEquals(0,in.readInt());}
    }

    @Test void persistedSerialGroupForbidsDifferentRunsButAllowAndAnotherTenantRemainIndependent() {
        time(100);String context=ExecutionStateMachine.encodeContext(Map.of());
        apply("J_SUBMIT","first-submit","first","input",context,"serial");apply("J_SUBMIT","second-submit","second","input",context,"serial");
        var first=apply("J_CLAIM","first-claim","first","one","100");assertEquals("RUNNING",first.status());
        assertEquals("OWNED",apply("J_CLAIM","second-denied","second","two","100").status());
        apply("J_SUBMIT","allow-submit","parallel","input",context);assertEquals("RUNNING",apply("J_CLAIM","allow-claim","parallel","three","100").status());
        machine.apply(++index,new ResourceCommand("J_SUBMIT","other","foreign-submit",List.of("foreign","input",context,"serial")).encode());
        machine.apply(++index,new ResourceCommand("J_CLAIM","other","foreign-claim",List.of("foreign","foreign-worker","100")).encode());
        assertEquals("RUNNING",ExecutionResult.decode(machine.query(new ResourceCommand("E_RESULT","other","q",List.of("foreign-claim")).encode())).status());
        var restored=new ExecutionStateMachine("execution");restored.restore(machine.snapshot());
        restored.apply(++index,new ResourceCommand("J_CLAIM","tenant","restart-denied",List.of("second","two","100")).encode());
        assertEquals("OWNED",ExecutionResult.decode(restored.query(new ResourceCommand("E_RESULT","tenant","q",List.of("restart-denied")).encode())).status());
        assertEquals("COMPLETED",apply("J_COMPLETE","first-done","first","one",first.fields().get("fence"),"done").status());
        assertEquals("RUNNING",apply("J_CLAIM","second-accepted","second","two","100").status());
    }
}
