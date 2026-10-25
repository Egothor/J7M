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

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.j7m.middleware.api.*;
import org.j7m.middleware.distributed.ExecutionResult;

/**
 * Resource-injectable saga control facade for one host-registered definition and version.
 * Administrative methods exist only when the host explicitly granted a separate server capability.
 * Transport failure never proves nonexecution; retry an identical stable request and business input.
 */
public final class ManagedSagaClient {
    /**
     * Safe durable run state without input, result payloads, owner credentials or endpoint data.
     * @param state actual native status
     * @param version exact observed durable version
     * @param step selected stable step identity, empty if none
     * @param compensationRequested durable inverse-progression decision
     * @param reason fixed host failure category, empty for application data or ordinary state
     */
    public record Status(String state,long version,String step,boolean compensationRequested,String reason) { }
    private final ManagedSagaFactory factory;private final String policy,definition;private final int version;private final BooleanSupplier open;private final boolean administrative;
    ManagedSagaClient(ManagedSagaFactory factory,String policy,String definition,int version,BooleanSupplier open,boolean administrative) { this.factory=factory;this.policy=policy;this.definition=definition;this.version=version;this.open=open;this.administrative=administrative; }
    private ManagedSagaFactory.Runner runner() {
        if(!open.getAsBoolean()) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE,"Saga binding is closed");return factory.runner(policy,definition,version);
    }
    private static Status view(ExecutionResult result) { return new Status(result.status(),Long.parseLong(result.fields().getOrDefault("version","0")),result.fields().getOrDefault("step",""),Boolean.parseBoolean(result.fields().getOrDefault("compensationRequested","false")),Set.of("NO_COMPENSATION_DECLARED","callback-outcome-unavailable").contains(result.fields().getOrDefault("stepResult",""))?result.fields().get("stepResult"):""); }
    private static void verifyStored(ManagedSagaProgram program,ExecutionResult state) {
        if(state.fields().containsKey("input")&&!program.input(state.fields().get("input")).fingerprint().equals(program.fingerprint)) throw new IllegalArgumentException("Saga program identity mismatch");
    }
    /**
     * Starts an ordinary typed input with a stable native request and run identity.
     * Encoding uses the exact generated first-step data type, never a class name from input.
     * @param request stable application operation identity
     * @param sagaId stable logical run identity
     * @param input first step's declared data value
     * @param deadline absolute original deadline
     * @return persisted native state; a timeout is not success
     * @throws IOException on unavailable quorum or uncertain completion
     * @throws MiddlewareException for closed/unavailable binding
     * @throws IllegalArgumentException for invalid identity, schema or frame bounds
     */
    public Status start(String request,String sagaId,Object input,Instant deadline) throws IOException {
        var runner=runner();var program=runner.program;if(!ManagedSagaProgram.businessIdentity(request)) throw ManagedSagaProgram.invalid();
        return view(program.profile.sagas().start("start."+OwnershipData.hash(program.profile.namespace(),definition,Integer.toString(version),request),program.nativeId(sagaId),program.input(sagaId,input),
                program.profile.maximumCompensationAttempts(),program.profile.compensationBackoff().toMillis(),deadline));
    }
    /**
     * Reads actual durable state without granting run ownership.
     * @param sagaId logical run identity
     * @param deadline absolute deadline
     * @return exact persisted status or NOT_FOUND
     * @throws IOException on unavailable quorum
     * @throws MiddlewareException for an unavailable binding
     */
    public Status status(String sagaId,Instant deadline) throws IOException {
        var program=runner().program;
        var state=program.profile.sagas().status(program.nativeId(sagaId),deadline);
        verifyStored(program,state);
        return view(state);
    }
    /**
     * Decodes a completed result with the exact final step schema.
     * @param <T> expected result type
     * @param sagaId logical run identity
     * @param type exact final step result class or its corresponding boxed primitive token
     * @param deadline absolute deadline
     * @return completed application result, possibly null
     * @throws IOException on unavailable quorum
     * @throws MiddlewareException while the run is not completed
     * @throws IllegalArgumentException for a different requested type
     */
    @SuppressWarnings("unchecked")
    public <T> T result(String sagaId,Class<T> type,Instant deadline) throws IOException {
        var program=runner().program;if(box(type)!=box(program.steps.getLast().output())) throw ManagedSagaProgram.invalid();
        var result=program.profile.sagas().status(program.nativeId(sagaId),deadline);
        verifyStored(program,result);
        if(!result.status().equals("COMPLETED")) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE,"Saga result is unavailable");
        return (T)box(type).cast(program.value(result.fields().get("result"),program.steps.getLast().output()));
    }
    /**
     * Requests a durable compensation decision using a separately granted administrative capability.
     * @param request stable administrative request
     * @param sagaId logical run identity
     * @param expectedVersion exact observed run version
     * @param deadline absolute deadline
     * @return persisted compensation decision or CONFLICT
     * @throws IOException on unavailable quorum or uncertain completion
     * @throws MiddlewareException without an explicit administrative registration
     */
    public Status requestCompensation(String request,String sagaId,long expectedVersion,Instant deadline) throws IOException {
        var program=runner().program;if(!administrative||program.profile.administration()==null) throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED,"Saga administration is unavailable");
        if(!ManagedSagaProgram.businessIdentity(request)) throw ManagedSagaProgram.invalid();
        verifyStored(program,program.profile.sagas().status(program.nativeId(sagaId),deadline));
        return view(program.profile.administrativeSagas().requestCompensation("compensation."+OwnershipData.hash(program.profile.namespace(),definition,Integer.toString(version),request),program.nativeId(sagaId),expectedVersion,deadline));
    }
    private static Class<?> box(Class<?> type) {
        Objects.requireNonNull(type);
        return Map.<Class<?>,Class<?>>of(int.class,Integer.class,long.class,Long.class,short.class,Short.class,byte.class,Byte.class,
                float.class,Float.class,double.class,Double.class,boolean.class,Boolean.class,char.class,Character.class,void.class,Void.class).getOrDefault(type,type);
    }
    ManagedSagaClient scoped(BooleanSupplier admitted) { return new ManagedSagaClient(factory,policy,definition,version,()->open.getAsBoolean()&&admitted.getAsBoolean(),administrative); }
    /**
     * Omits capabilities and program identities from diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedSagaClient[host-bound]"; }
}
