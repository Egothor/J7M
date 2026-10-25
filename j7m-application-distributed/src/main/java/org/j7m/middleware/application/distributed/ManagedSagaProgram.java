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

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;

/**
 * Pure finite saga metadata and durable data framing; never invokes a business method reflectively.
 */
final class ManagedSagaProgram {
    record Step(String name,int order,boolean idempotent,ManagedMethod callback,Class<?> input,Class<?> output,
            ManagedMethod compensation,boolean compensationIdempotent) { }
    record Input(String fingerprint,String logicalId,byte[] payload) { Input { payload=payload.clone(); } }
    final ManagedHostedFeature feature;
    final ManagedSagaProfile profile;
    final ManagedPayloadCodec codec;
    final List<Step> steps;
    final ManagedMethod reconciliation;
    final String fingerprint;
    ManagedSagaProgram(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,
            ManagedHostedResources.Bundle bundle,ManagedSagaProfile profile) {
        this.feature=feature;this.profile=profile;
        var annotation=definition.type().getAnnotation(SagaDefinition.class);
        if(annotation==null || !feature.kind().equals("saga") || !feature.id().equals(annotation.name())
                || feature.version()!=annotation.version() || !feature.attributes().equals(Map.of("policy",annotation.policy()))
                || !(definition instanceof ManagedHostedAdapter<?>) || definition.scope()!=Scope.Kind.ACTIVATION) throw invalid();
        var forward=new TreeMap<Integer,Method>(); var compensations=new HashMap<String,Method>(); Method reconcile=null;
        for(var method:definition.type().getMethods()) {
            var step=method.getAnnotation(SagaStep.class); var undo=method.getAnnotation(Compensation.class); var fix=method.getAnnotation(ReconciliationHandler.class);
            int count=(step==null?0:1)+(undo==null?0:1)+(fix==null?0:1); if(count==0) continue;
            if(count!=1 || Modifier.isStatic(method.getModifiers()) || method.getParameterCount()!=1) throw invalid();
            if(step!=null && (step.order()<0 || !identity(step.name()) || forward.putIfAbsent(step.order(),method)!=null)) throw invalid();
            if(undo!=null && compensations.putIfAbsent(undo.step(),method)!=null) throw invalid();
            if(fix!=null) { if(reconcile!=null || method.getParameterTypes()[0]!=ManagedSagaUncertainty.class || resultType(method)!=ManagedSagaDecision.class || profile.administration()==null) throw invalid(); reconcile=method; }
        }
        for(var method:definition.type().getDeclaredMethods()) if(!Modifier.isPublic(method.getModifiers())
                && (method.isAnnotationPresent(SagaStep.class)||method.isAnnotationPresent(Compensation.class)||method.isAnnotationPresent(ReconciliationHandler.class))) throw invalid();
        if(forward.isEmpty() || forward.size()>7) throw invalid();
        var collected=new ArrayList<Step>(); var methods=new HashSet<ManagedMethod>(); var names=new HashSet<String>(); Class<?> previous=null;
        for(var entry:forward.entrySet()) {
            Method method=entry.getValue(); var declared=method.getAnnotation(SagaStep.class); Class<?> input=method.getParameterTypes()[0],output=resultType(method);
            if(entry.getKey()!=collected.size() || !names.add(declared.name()) || output==void.class || output==Void.class || previous!=null && previous!=input) throw invalid();
            Method compensation=compensations.remove(declared.name());
            if(compensation!=null && (compensation.getParameterTypes()[0]!=input || !Set.of(void.class,Void.class).contains(resultType(compensation)))) throw invalid();
            var callback=identity(method); if(!methods.add(callback)) throw invalid();
            if(compensation!=null && !methods.add(identity(compensation))) throw invalid();
            collected.add(new Step(declared.name(),declared.order(),declared.idempotent(),callback,input,output,
                    compensation==null?null:identity(compensation),compensation!=null&&compensation.getAnnotation(Compensation.class).idempotent())); previous=output;
        }
        if(!compensations.isEmpty()) throw invalid(); reconciliation=reconcile==null?null:identity(reconcile);
        if(reconciliation!=null) methods.add(reconciliation);
        if(!methods.equals(new HashSet<>(feature.methods())) || methods.size()!=feature.methods().size()) throw invalid();
        steps=List.copyOf(collected);
        codec=profile.codec().forDefinition(definition.type(),steps.stream().flatMap(step->java.util.stream.Stream.of(step.input(),step.output())).distinct().toList());
        var semantics=new ArrayList<String>(List.of(bundle.artifactDigest(),profile.namespace(),feature.id(),Integer.toString(feature.version()),
                Integer.toString(profile.maximumCompensationAttempts()),Long.toString(profile.compensationBackoff().toMillis())));
        new TreeMap<>(bundle.semanticConfiguration()).forEach((key,value)->{semantics.add(key);semantics.add(value);});
        for(var step:steps) semantics.add(step.toString());
        fingerprint=OwnershipData.hash(semantics.toArray(String[]::new));
    }
    private static Class<?> resultType(Method method) {
        return java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())?AsyncMethodDescriptor.fromMethod(method).payloadType():method.getReturnType();
    }
    private static ManagedMethod identity(Method method) { return new ManagedMethod(method.getDeclaringClass().getName(),method.getName(),Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),method.getReturnType().getName()); }
    static boolean identity(String id) { return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"); }
    static boolean businessIdentity(String value) { return value!=null&&!value.isEmpty()&&value.length()<=512&&value.indexOf('\0')<0; }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid managed saga definition"); }
    String prefix() { return "saga."+compactHash(profile.namespace(),feature.id(),Integer.toString(feature.version()))+"."; }
    static String compactHash(String... parts) { return Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(OwnershipData.hash(parts))); }
    String nativeId(String logical) { if(!businessIdentity(logical)) throw invalid(); return prefix()+compactHash(logical); }
    String frame(Object value,Class<?> type) {
        byte[] frame=codec.encode(value,type); if(frame.length>8192) throw invalid(); return Base64.getEncoder().encodeToString(frame);
    }
    Object value(String frame,Class<?> type) { return codec.decode(Base64.getDecoder().decode(frame),type); }
    String input(String logical,Object value) {
        try { var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);out.writeInt(1);out.writeUTF(fingerprint);out.writeUTF(logical);
            byte[] payload=codec.encode(value,steps.getFirst().input()); if(payload.length>8192) throw invalid(); out.writeInt(payload.length);out.write(payload);out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException("Saga input encoding unavailable",impossible); }
    }
    Input input(String frame) {
        try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(frame)))) {
            if(in.readInt()!=1) throw invalid(); String program=in.readUTF(),logical=in.readUTF();int count=in.readInt();
            if(!program.matches("[0-9a-f]{64}")||!businessIdentity(logical)||count<0||count>8192||count!=in.available()) throw invalid();
            return new Input(program,logical,in.readNBytes(count));
        } catch(IOException|IllegalArgumentException malformed) { throw invalid(); }
    }
}
