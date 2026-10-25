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

package org.j7m.middleware.processor;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.j7m.middleware.annotations.Checkpoint;
import org.j7m.middleware.annotations.DurableJob;
import org.j7m.middleware.annotations.Progress;
import org.j7m.middleware.annotations.Restore;
import org.j7m.middleware.annotations.Schedule;

/**
 * Emits finite typed job hooks; no runtime scheduling or application code execution occurs here.
 */
final class JobHostedGenerator {
    record Result(String feature,List<ExecutableElement> callbacks,String methods) { }
    private final ProcessingEnvironment environment;
    JobHostedGenerator(ProcessingEnvironment environment){this.environment=environment;}
    Result generate(TypeElement type) {
        var all=environment.getElementUtils().getAllMembers(type).stream().filter(element->element.getKind()==ElementKind.METHOD).map(element->(ExecutableElement)element).toList();
        var entries=all.stream().filter(method->method.getAnnotation(DurableJob.class)!=null).toList();
        boolean marked=all.stream().anyMatch(method->method.getAnnotation(Checkpoint.class)!=null||method.getAnnotation(Restore.class)!=null||method.getAnnotation(Progress.class)!=null||method.getAnnotation(Schedule.class)!=null);
        if(entries.isEmpty()){require(!marked,"J7M_DURABLE_JOB_REQUIRED");return null;}
        require(entries.size()==1,"J7M_ONE_JOB_ENTRY_PER_COMPONENT");var entry=entries.getFirst();var marker=entry.getAnnotation(DurableJob.class);
        require(id(marker.value())&&id(marker.policy())&&marker.version()>0,"J7M_INVALID_DURABLE_JOB");
        validate(entry);require(entry.getParameters().size()==1&&data(entry.getParameters().getFirst().asType()),"J7M_INVALID_JOB_INPUT");
        TypeMirror result=result(entry);require(result.getKind()==TypeKind.VOID||result.toString().equals("java.lang.Void")||data(result),"J7M_INVALID_JOB_RESULT");
        ExecutableElement checkpoint=unique(all,Checkpoint.class),restore=unique(all,Restore.class),progress=unique(all,Progress.class);
        require((checkpoint==null)==(restore==null),"J7M_CHECKPOINT_REQUIRES_RESTORE");
        if(checkpoint==null){
            TypeElement current=type;
            while(current!=null&&!current.getQualifiedName().contentEquals("java.lang.Object")){
                for(var field:current.getEnclosedElements())if(field.getKind()==ElementKind.FIELD)
                    require(field.getModifiers().contains(Modifier.STATIC)||field.getModifiers().contains(Modifier.FINAL),"J7M_MUTABLE_JOB_REQUIRES_CHECKPOINT_RESTORE");
                var parent=environment.getTypeUtils().asElement(current.getSuperclass());current=parent instanceof TypeElement base?base:null;
            }
        }
        var callbacks=new ArrayList<ExecutableElement>();callbacks.add(entry);String state="java.lang.Void",view="java.lang.Void";
        String hooks="";
        if(checkpoint!=null){
            validate(checkpoint);validate(restore);require(checkpoint.getParameters().isEmpty()&&restore.getParameters().size()==1&&restore.getReturnType().getKind()==TypeKind.VOID
                &&environment.getTypeUtils().isSameType(checkpoint.getReturnType(),restore.getParameters().getFirst().asType())
                &&checkpoint.getReturnType().getKind()!=TypeKind.VOID&&!checkpoint.getReturnType().getKind().isPrimitive()&&data(checkpoint.getReturnType()),"J7M_INVALID_CHECKPOINT_RESTORE");
            state=checkpoint.getReturnType().toString();callbacks.add(checkpoint);callbacks.add(restore);
            hooks+="public Object checkpoint("+type.getQualifiedName()+" value)throws Exception{return value."+checkpoint.getSimpleName()+"();}public void restore("+type.getQualifiedName()+" value,Object state)throws Exception{value."+restore.getSimpleName()+"(("+state+")state);}";
        }
        if(progress!=null){validate(progress);require(progress.getParameters().isEmpty()&&!progress.getReturnType().getKind().isPrimitive()&&data(progress.getReturnType()),"J7M_INVALID_JOB_PROGRESS");view=progress.getReturnType().toString();callbacks.add(progress);hooks+="public Object progress("+type.getQualifiedName()+" value)throws Exception{return value."+progress.getSimpleName()+"();}";}
        for(var method:all){
            int roles=(method.getAnnotation(DurableJob.class)==null?0:1)+(method.getAnnotation(Checkpoint.class)==null?0:1)+(method.getAnnotation(Restore.class)==null?0:1)+(method.getAnnotation(Progress.class)==null?0:1);
            require(roles<=1,"J7M_CONFLICTING_JOB_HOOK_ROLES");
            require(method.getAnnotation(org.j7m.middleware.annotations.RemoteMethod.class)==null&&method.getAnnotation(org.j7m.middleware.annotations.EventSubscription.class)==null
                &&method.getAnnotation(org.j7m.middleware.annotations.DurableQueueConsumer.class)==null&&method.getAnnotation(org.j7m.middleware.annotations.DeadLetterHandler.class)==null
                &&method.getAnnotation(org.j7m.middleware.annotations.SagaStep.class)==null,"J7M_JOB_COMPONENT_CANNOT_HOST_OTHER_ENTRIES");
            if(method.getAnnotation(Schedule.class)!=null)require(method.equals(entry)&&id(method.getAnnotation(Schedule.class).value()),"J7M_INVALID_JOB_SCHEDULE");
        }
        require(entry.getAnnotation(org.j7m.middleware.annotations.Deduplicated.class)==null&&entry.getAnnotation(org.j7m.middleware.annotations.NonIdempotent.class)==null,"J7M_CONFLICTING_JOB_REPLAY_POLICY");
        String attributes="java.util.Map.of(\"policy\","+J7mProcessor.quote(marker.policy())+",\"schedule\","+J7mProcessor.quote(entry.getAnnotation(Schedule.class)==null?"":entry.getAnnotation(Schedule.class).value())+")";
        String feature="new org.j7m.middleware.application.api.ManagedHostedFeature(\"durable-job\","+J7mProcessor.quote(marker.value())+","+marker.version()+","+attributes+",java.util.List.of("+callbacks.stream().map(this::identity).collect(java.util.stream.Collectors.joining(","))+"))";
        String methods="public org.j7m.middleware.application.api.ManagedHostedFeature job(){return "+feature+";}public Class<?> inputType(){return "+entry.getParameters().getFirst().asType()+".class;}public Class<?> resultType(){return "+(result.getKind()==TypeKind.VOID?"java.lang.Void":result)+".class;}public Class<?> checkpointType(){return "+state+".class;}public Class<?> progressType(){return "+view+".class;}"+hooks;
        return new Result(feature,List.copyOf(callbacks),methods);
    }
    private ExecutableElement unique(List<ExecutableElement> methods,Class<? extends java.lang.annotation.Annotation> marker){var selected=methods.stream().filter(method->method.getAnnotation(marker)!=null).toList();require(selected.size()<=1,"J7M_AMBIGUOUS_JOB_HOOK");return selected.isEmpty()?null:selected.getFirst();}
    private void validate(ExecutableElement method){
        boolean hook=method.getAnnotation(Checkpoint.class)!=null||method.getAnnotation(Restore.class)!=null||method.getAnnotation(Progress.class)!=null;
        if(hook)for(var annotation:method.getAnnotationMirrors()){
            String name=annotation.getAnnotationType().toString();
            require(!name.startsWith("org.j7m.middleware.annotations.")||List.of(Checkpoint.class.getName(),Restore.class.getName(),Progress.class.getName()).contains(name),"J7M_UNSUPPORTED_JOB_HOOK_POLICY");
        }
        require(method.getModifiers().contains(Modifier.PUBLIC)&&!method.getModifiers().contains(Modifier.STATIC)&&method.getTypeParameters().isEmpty()&&!method.isVarArgs(),"J7M_INACCESSIBLE_JOB_HOOK");}
    private TypeMirror result(ExecutableElement method){var type=method.getReturnType();if(type instanceof DeclaredType declared&&environment.getTypeUtils().erasure(type).toString().equals("java.util.concurrent.CompletionStage")){require(declared.getTypeArguments().size()==1,"J7M_RAW_JOB_STAGE");return declared.getTypeArguments().getFirst();}return type;}
    private boolean data(TypeMirror type){if(type.getKind().isPrimitive())return true;if(type.toString().equals("byte[]"))return true;if(!(type instanceof DeclaredType declared)||!declared.getTypeArguments().isEmpty())return false;var element=(TypeElement)declared.asElement();return List.of("java.lang.String","java.lang.Boolean","java.lang.Byte","java.lang.Short","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double","java.lang.Character").contains(type.toString())||element.getKind()==ElementKind.RECORD&&element.getAnnotation(org.j7m.middleware.annotations.SchemaType.class)!=null;}
    private String identity(ExecutableElement method){String parameters=method.getParameters().stream().map(parameter->J7mProcessor.quote(binary(parameter.asType()))).collect(java.util.stream.Collectors.joining(","));return "new org.j7m.middleware.application.api.ManagedMethod("+J7mProcessor.quote(environment.getElementUtils().getBinaryName((TypeElement)method.getEnclosingElement()).toString())+","+J7mProcessor.quote(method.getSimpleName().toString())+",java.util.List.of("+parameters+"),"+J7mProcessor.quote(binary(method.getReturnType()))+")";}
    private String binary(TypeMirror type){if(type.toString().equals("byte[]"))return "[B";var element=environment.getTypeUtils().asElement(environment.getTypeUtils().erasure(type));return element instanceof TypeElement declared?environment.getElementUtils().getBinaryName(declared).toString():type.toString();}
    private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
