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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.j7m.middleware.annotations.Checkpoint;
import org.j7m.middleware.annotations.DurableJob;
import org.j7m.middleware.annotations.Progress;
import org.j7m.middleware.annotations.Restore;
import org.j7m.middleware.annotations.Schedule;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedJobDefinition;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;

/**
 * Pure finite job metadata and bounded data envelopes; no reflective business invocation occurs.
 */
final class ManagedJobProgram {
    record Input(String logical,byte[] payload){Input{payload=payload.clone();}}
    record Saved(byte[] state,byte[] progress){Saved{state=state.clone();progress=progress.clone();}}
    final ManagedHostedFeature feature;
    final ManagedJobProfile profile;
    final ManagedPayloadCodec codec;
    final ManagedJobDefinition<Object> definition;
    final ManagedMethod entry;
    final Class<?> inputType,resultType,stateType,progressType;
    final String fingerprint,schedule;
    @SuppressWarnings("unchecked")
    ManagedJobProgram(ManagedHostedFeature feature,ManagedComponentDefinition<?> source,ManagedHostedResources.Bundle bundle,ManagedJobProfile profile){
        this.feature=feature;this.profile=profile;
        if(!(source instanceof ManagedJobDefinition<?> typed)||source.scope()!=Scope.Kind.ACTIVATION)throw invalid();
        definition=(ManagedJobDefinition<Object>)typed;
        var entries=Arrays.stream(source.type().getMethods()).filter(method->method.isAnnotationPresent(DurableJob.class)).toList();
        if(entries.size()!=1)throw invalid();Method method=entries.getFirst();var marker=method.getAnnotation(DurableJob.class);validate(method);
        if(!feature.kind().equals("durable-job")||!feature.id().equals(marker.value())||feature.version()!=marker.version()
                ||!feature.equals(definition.job())||method.getParameterCount()!=1)throw invalid();
        schedule=method.isAnnotationPresent(Schedule.class)?method.getAnnotation(Schedule.class).value():"";
        if(!feature.attributes().equals(Map.of("policy",marker.policy(),"schedule",schedule)))throw invalid();
        entry=identity(method);inputType=method.getParameterTypes()[0];resultType=java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())?AsyncMethodDescriptor.fromMethod(method).payloadType():method.getReturnType();
        Method checkpoint=unique(source.type(),Checkpoint.class),restore=unique(source.type(),Restore.class),progress=unique(source.type(),Progress.class);
        if((checkpoint==null)!=(restore==null))throw invalid();stateType=checkpoint==null?Void.class:checkpoint.getReturnType();progressType=progress==null?Void.class:progress.getReturnType();
        var methods=new HashSet<ManagedMethod>();methods.add(entry);
        if(checkpoint!=null){validate(checkpoint);validate(restore);if(checkpoint.getParameterCount()!=0||restore.getParameterCount()!=1||restore.getParameterTypes()[0]!=stateType||restore.getReturnType()!=void.class||stateType.isPrimitive()||stateType==Void.class)throw invalid();methods.add(identity(checkpoint));methods.add(identity(restore));}
        if(progress!=null){validate(progress);if(progress.getParameterCount()!=0||progressType.isPrimitive()||progressType==Void.class)throw invalid();methods.add(identity(progress));}
        if(!methods.equals(new HashSet<>(feature.methods()))||methods.size()!=feature.methods().size()||definition.inputType()!=inputType||definition.resultType()!=boxVoid(resultType)||definition.checkpointType()!=stateType||definition.progressType()!=progressType)throw invalid();
        for(Class<?> current=source.type();current!=null&&current!=Object.class;current=current.getSuperclass()){
            for(var field:current.getDeclaredFields())if(checkpoint==null&&!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers()))throw invalid();
            for(Method declared:current.getDeclaredMethods()){
                int roles=(declared.isAnnotationPresent(DurableJob.class)?1:0)+(declared.isAnnotationPresent(Checkpoint.class)?1:0)+(declared.isAnnotationPresent(Restore.class)?1:0)+(declared.isAnnotationPresent(Progress.class)?1:0);
                if(roles>1||Arrays.stream(declared.getParameters()).anyMatch(parameter->parameter.isAnnotationPresent(Progress.class)))throw invalid();
                boolean hook=declared.isAnnotationPresent(Checkpoint.class)||declared.isAnnotationPresent(Restore.class)||declared.isAnnotationPresent(Progress.class);
                if(hook){validate(declared);for(var annotation:declared.getDeclaredAnnotations())if(annotation.annotationType().getPackageName().equals("org.j7m.middleware.annotations")&&!Set.of(Checkpoint.class,Restore.class,Progress.class).contains(annotation.annotationType()))throw invalid();}
                if(declared.isAnnotationPresent(Schedule.class)&&!declared.equals(method))throw invalid();
                if(declared.isAnnotationPresent(org.j7m.middleware.annotations.RemoteMethod.class)||declared.isAnnotationPresent(org.j7m.middleware.annotations.EventSubscription.class)||declared.isAnnotationPresent(org.j7m.middleware.annotations.DurableQueueConsumer.class)||declared.isAnnotationPresent(org.j7m.middleware.annotations.SagaStep.class))throw invalid();
            }
        }
        if(method.isAnnotationPresent(org.j7m.middleware.annotations.Deduplicated.class)||method.isAnnotationPresent(org.j7m.middleware.annotations.NonIdempotent.class))throw invalid();
        for(Class<?> data:List.of(inputType,boxVoid(resultType),stateType,progressType))validateData(data);
        codec=profile.codec().forDefinition(source.type(),List.of(inputType,boxVoid(resultType),stateType,progressType));
        var semantics=new ArrayList<String>(List.of(bundle.artifactDigest(),profile.namespace(),feature.id(),Integer.toString(feature.version()),Boolean.toString(profile.serial())));
        new TreeMap<>(bundle.semanticConfiguration()).forEach((key,value)->{semantics.add(key);semantics.add(value);});feature.methods().forEach(value->semantics.add(value.toString()));
        fingerprint=OwnershipData.hash(semantics.toArray(String[]::new));
    }
    private static Class<?> boxVoid(Class<?> type){return type==void.class?Void.class:type;}
    private static void validateData(Class<?> type){if(type==Void.class||type.isPrimitive()||List.of(String.class,Boolean.class,Byte.class,Short.class,Integer.class,Long.class,Float.class,Double.class,Character.class,byte[].class).contains(type))return;if(!type.isRecord()||!type.isAnnotationPresent(org.j7m.middleware.annotations.SchemaType.class))throw invalid();}
    private static void validate(Method method){if(!Modifier.isPublic(method.getModifiers())||Modifier.isStatic(method.getModifiers())||method.getTypeParameters().length!=0||method.isVarArgs())throw invalid();}
    private static Method unique(Class<?> type,Class<? extends java.lang.annotation.Annotation> marker){var selected=Arrays.stream(type.getMethods()).filter(method->method.isAnnotationPresent(marker)).toList();if(selected.size()>1)throw invalid();return selected.isEmpty()?null:selected.getFirst();}
    private static ManagedMethod identity(Method method){return new ManagedMethod(method.getDeclaringClass().getName(),method.getName(),Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),method.getReturnType().getName());}
    private static boolean businessIdentity(String value){return value!=null&&!value.isEmpty()&&value.length()<=512&&value.indexOf('\0')<0;}
    private static String compactHash(String... parts){return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.HexFormat.of().parseHex(OwnershipData.hash(parts)));}
    String prefix(){return "job."+compactHash(profile.namespace(),feature.id(),Integer.toString(feature.version()))+".";}
    String nativeId(String logical){if(!businessIdentity(logical))throw invalid();return prefix()+compactHash(logical);}
    String serialGroup(){return "job."+compactHash(profile.namespace(),feature.id(),Integer.toString(feature.version()));}
    String input(String logical,Object value){return inputBytes(logical,codec.encode(value,inputType));}
    String inputBytes(String logical,byte[] payload){nativeId(logical);return encode(out->{out.writeInt(1);out.writeUTF(fingerprint);out.writeUTF(logical);blob(out,payload);});}
    Input input(String frame){try(var in=read(frame)){if(in.readInt()!=1||!in.readUTF().equals(fingerprint))throw invalid();String logical=in.readUTF();byte[] payload=blob(in);if(!businessIdentity(logical)||in.available()!=0)throw invalid();return new Input(logical,payload);}catch(IOException malformed){throw invalid();}}
    String saved(byte[] state,byte[] progress){return encode(out->{out.writeInt(1);out.writeUTF(fingerprint);blob(out,state);blob(out,progress);});}
    Saved saved(String frame){if(frame.isEmpty())return new Saved(new byte[0],new byte[0]);try(var in=read(frame)){if(in.readInt()!=1||!in.readUTF().equals(fingerprint))throw invalid();var saved=new Saved(blob(in),blob(in));if(in.available()!=0)throw invalid();return saved;}catch(IOException malformed){throw invalid();}}
    String outcome(Object value,Throwable failure){return encode(out->{out.writeInt(1);out.writeUTF(fingerprint);out.writeBoolean(failure==null);if(failure==null)blob(out,codec.encode(value,boxVoid(resultType)));else out.writeUTF("JOB_FAILED");});}
    boolean failed(String frame){try(var in=read(frame)){if(in.readInt()!=1||!in.readUTF().equals(fingerprint))throw invalid();boolean success=in.readBoolean();if(success)blob(in);else if(!in.readUTF().equals("JOB_FAILED"))throw invalid();if(in.available()!=0)throw invalid();return !success;}catch(IOException malformed){throw invalid();}}
    Object result(String frame){try(var in=read(frame)){if(in.readInt()!=1||!in.readUTF().equals(fingerprint))throw invalid();boolean success=in.readBoolean();if(!success){in.readUTF();if(in.available()!=0)throw invalid();throw new IllegalStateException("Durable job failed");}byte[] payload=blob(in);if(in.available()!=0)throw invalid();return codec.decode(payload,boxVoid(resultType));}catch(IOException malformed){throw invalid();}}
    private interface Writer{void write(DataOutputStream output)throws IOException;}
    private static String encode(Writer writer){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);writer.write(out);out.flush();if(bytes.size()>ManagedPayloadCodec.MAX_FRAME_BYTES)throw invalid();return Base64.getEncoder().encodeToString(bytes.toByteArray());}catch(IOException invalid){throw invalid();}}
    private static DataInputStream read(String frame){if(frame.length()>16384)throw invalid();try{byte[] bytes=Base64.getDecoder().decode(frame);if(bytes.length>ManagedPayloadCodec.MAX_FRAME_BYTES)throw invalid();return new DataInputStream(new ByteArrayInputStream(bytes));}catch(IllegalArgumentException malformed){throw invalid();}}
    private static void blob(DataOutputStream out,byte[] value)throws IOException{out.writeInt(value.length);out.write(value);}
    private static byte[] blob(DataInputStream in)throws IOException{int size=in.readInt();if(size<0||size>ManagedPayloadCodec.MAX_FRAME_BYTES||size>in.available())throw invalid();return in.readNBytes(size);}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid managed job definition or frame");}
}
