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
import java.util.Map;
import java.util.Objects;
import org.j7m.middleware.distributed.ExecutionResult;
import org.j7m.middleware.distributed.ReplicatedResources;

/**
 * Host-selected typed durable job producer and query facade, without worker credentials or claims.
 * Its exact profile/namespace/program is selected by trusted setup, never by an application name
 * lookup. Public status is authoritative native state; local cancellation is not physical completion.
 */
public final class ManagedJobClient implements AutoCloseable {
    private volatile java.util.function.Supplier<ManagedJobProgram> source;
    ManagedJobClient(ManagedJobProgram program,java.util.function.BooleanSupplier admitted){this(()->{if(!admitted.getAsBoolean())throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.UNAVAILABLE,"Managed job binding is unavailable");return program;});}
    ManagedJobClient(java.util.function.Supplier<ManagedJobProgram> source){this.source=Objects.requireNonNull(source);}
    private ManagedJobProgram open(){var current=source;if(current==null)throw new IllegalStateException("Managed job client closed");return current.get();}
    /**
     * Durably submits one stable logical occurrence with the first caller's trace carrier.
     * @param logical stable application occurrence identity, at most512 UTF16 code units
     * @param input data of the declared input type
     * @param deadline original submission deadline, not the background handler's execution budget
     * @return READY, CONFLICT or explicit native capacity outcome
     * @throws IOException if submission outcome is unavailable or UNKNOWN
     * @throws IllegalArgumentException for malformed identity, input or bounded envelope
     */
    public ExecutionResult submit(String logical,Object input,Instant deadline)throws IOException {
        var program=open();return submitNative(program,logical,program.input(logical,input),deadline);
    }
    ExecutionResult submitEncoded(String logical,byte[] input,Instant deadline)throws IOException {var program=open();return submitNative(program,logical,program.inputBytes(logical,input),deadline);}
    private ExecutionResult submitNative(ManagedJobProgram program,String logical,String frame,Instant deadline)throws IOException {
        String id=program.nativeId(logical),request="job.submit."+OwnershipData.hash(id,frame);
        var jobs=new org.j7m.middleware.distributed.DurableJobs(program.profile.backend().client());
        return program.profile.serial()?jobs.submit(request,id,frame,program.serialGroup(),deadline):jobs.submit(request,id,frame,deadline);
    }
    /**
     * Durably cancels native work and fences its prior owner; this is not Java task termination.
     * The cooperative context observes revocation through native renewal/checkpoint fencing.
     * @param logical stable occurrence identity
     * @param deadline original cancellation operation deadline
     * @return CANCELLED or ALREADY_COMPLETED, never proof of physical handler exit
     * @throws IOException if cancellation outcome is unavailable or UNKNOWN
     */
    public ExecutionResult cancel(String logical,Instant deadline)throws IOException {
        var program=open();String id=program.nativeId(logical);
        return new org.j7m.middleware.distributed.DurableJobs(program.profile.backend().client()).cancel("job.cancel."+OwnershipData.hash(id),id,deadline);
    }
    /**
     * Reads durable logical status and its monotonically increasing native run version.
     * @param logical stable occurrence identity
     * @param deadline original read deadline
     * @return durable status; a committed failed outcome is FAILED, with authorized native metadata
     * @throws IOException if the current quorum is unavailable
     */
    public ExecutionResult status(String logical,Instant deadline)throws IOException {
        var program=open();var nativeStatus=new org.j7m.middleware.distributed.DurableJobs(program.profile.backend().client()).status(program.nativeId(logical),deadline);
        return nativeStatus.status().equals("COMPLETED")&&program.failed(nativeStatus.fields().getOrDefault("result",""))?new ExecutionResult("FAILED",nativeStatus.fields()):nativeStatus;
    }
    /**
     * Reads the declared typed terminal result; no app exception or stack is deserialized.
     * @param logical stable occurrence identity
     * @param type exact declared result type, primitive boxing allowed
     * @param deadline original read deadline
     * @param <T> expected result type
     * @return persisted result, possibly null for a void entry
     * @throws IOException if quorum is unavailable
     * @throws IllegalStateException if not terminal or the durable logical outcome is failed
     * @throws IllegalArgumentException for incompatible type or frame
     */
    @SuppressWarnings("unchecked")
    public <T>T result(String logical,Class<T> type,Instant deadline)throws IOException {
        var program=open();if(box(type)!=box(program.resultType))throw ManagedJobProgram.invalid();var status=status(logical,deadline);
        if(!java.util.Set.of("COMPLETED","FAILED").contains(status.status()))throw new IllegalStateException("Durable job result is not terminal");return (T)program.result(status.fields().getOrDefault("result",""));
    }
    /**
     * Reads the last confirmed checkpoint without observing live Java heap state.
     * @param logical stable occurrence identity
     * @param type exact declared checkpoint reference type
     * @param deadline original read deadline
     * @param <T> checkpoint type
     * @return persisted state, or null before any checkpoint
     * @throws IOException if quorum is unavailable
     * @throws IllegalArgumentException for incompatible type or frame
     */
    public <T>T checkpoint(String logical,Class<T> type,Instant deadline)throws IOException {return saved(logical,type,deadline,false);}
    /**
     * Reads the last confirmed progress hook value bound to the run's durable version.
     * @param logical stable occurrence identity
     * @param type exact declared progress reference type
     * @param deadline original read deadline
     * @param <T> progress type
     * @return persisted progress, or null before any progress capture
     * @throws IOException if quorum is unavailable
     * @throws IllegalArgumentException for incompatible type or frame
     */
    public <T>T progress(String logical,Class<T> type,Instant deadline)throws IOException {return saved(logical,type,deadline,true);}
    @SuppressWarnings("unchecked")
    private <T>T saved(String logical,Class<T> type,Instant deadline,boolean progress)throws IOException {
        var program=open();Class<?> expected=progress?program.progressType:program.stateType;if(expected==Void.class||type!=expected)throw ManagedJobProgram.invalid();
        var status=status(logical,deadline);if(status.status().equals("NOT_FOUND"))return null;var saved=program.saved(status.fields().getOrDefault("checkpoint",""));byte[] frame=progress?saved.progress():saved.state();return frame.length==0?null:(T)program.codec.decode(frame,type);
    }
    /**
     * Reads a protected value in the same fixed host namespace as job staged effects.
     * @param key logical identifier,1..128 ASCII identifier characters
     * @param deadline original read deadline
     * @return immutable native value/version, or null
     * @throws IOException if quorum is unavailable
     * @throws IllegalArgumentException for malformed key
     */
    public ReplicatedResources.Value readProtected(String key,Instant deadline)throws IOException {
        var program=open();var result=program.profile.backend().client().query("E_VALUE",effectKey(program,key),deadline);
        if(result.status().equals("NOT_FOUND"))return null;if(!result.status().equals("OK"))throw new IOException("Protected job read unavailable");return new ReplicatedResources.Value(result.fields().get("value"),Long.parseLong(result.fields().get("version")));
    }
    static String effectKey(ManagedJobProgram program,String key){if(key==null||!key.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid protected job key");return "job.effect."+OwnershipData.hash(program.profile.namespace(),program.feature.id(),Integer.toString(program.feature.version()),key);}
    private static Class<?> box(Class<?> type){Objects.requireNonNull(type);return Map.<Class<?>,Class<?>>of(int.class,Integer.class,long.class,Long.class,short.class,Short.class,byte.class,Byte.class,float.class,Float.class,double.class,Double.class,boolean.class,Boolean.class,char.class,Character.class,void.class,Void.class).getOrDefault(type,type);}
    /**
     * Revokes this producer/query facade without closing borrowed endpoints or running workers.
     */
    @Override public void close(){source=null;}
    /**
     * Omits namespace, identities and input data.
     * @return fixed safe diagnostic
     */
    @Override public String toString(){return "ManagedJobClient[host-selected]";}
}
