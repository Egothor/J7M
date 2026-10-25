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

import java.util.*;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import org.j7m.middleware.annotations.*;

/**
 * Validates finite saga declarations and emits metadata for the shared direct hosted dispatcher.
 * It neither invokes application code nor creates a native saga protocol in generated classes.
 */
final class SagaHostedGenerator {
    record Result(String feature,List<ExecutableElement> callbacks) { Result {callbacks=List.copyOf(callbacks);} }
    private final ProcessingEnvironment environment;
    SagaHostedGenerator(ProcessingEnvironment environment) {this.environment=environment;}
    Result generate(TypeElement type) {
        var declaration=type.getAnnotation(SagaDefinition.class);
        var methods=environment.getElementUtils().getAllMembers(type).stream().filter(element->element.getKind()==ElementKind.METHOD).map(element->(ExecutableElement)element).toList();
        boolean marked=methods.stream().anyMatch(this::marked);
        if(declaration==null) {require(!marked,"J7M_SAGA_DEFINITION_REQUIRED");return null;}
        require(id(declaration.name())&&id(declaration.policy())&&declaration.version()>0,"J7M_INVALID_SAGA_DEFINITION");
        var forward=new TreeMap<Integer,ExecutableElement>();var compensations=new HashMap<String,ExecutableElement>();ExecutableElement reconcile=null;
        for(var method:methods) {
            if(!marked(method)) continue;
            require(method.getModifiers().contains(Modifier.PUBLIC)&&!method.getModifiers().contains(Modifier.STATIC)&&method.getTypeParameters().isEmpty()
                    &&method.getParameters().size()==1&&!method.isVarArgs(),"J7M_INVALID_SAGA_CALLBACK");
            var step=method.getAnnotation(SagaStep.class);var undo=method.getAnnotation(Compensation.class);var fix=method.getAnnotation(ReconciliationHandler.class);
            require((step==null?0:1)+(undo==null?0:1)+(fix==null?0:1)==1&&method.getAnnotation(RemoteMethod.class)==null,"J7M_CONFLICTING_SAGA_CALLBACK");
            if(step!=null) require(step.order()>=0&&id(step.name())&&forward.putIfAbsent(step.order(),method)==null,"J7M_INVALID_SAGA_STEP_ORDER");
            if(undo!=null) require(id(undo.step())&&compensations.putIfAbsent(undo.step(),method)==null,"J7M_DUPLICATE_SAGA_COMPENSATION");
            if(fix!=null) {
                require(reconcile==null&&method.getParameters().getFirst().asType().toString().equals("org.j7m.middleware.application.distributed.ManagedSagaUncertainty")
                        &&result(method).toString().equals("org.j7m.middleware.application.distributed.ManagedSagaDecision"),"J7M_INVALID_SAGA_RECONCILIATION");reconcile=method;
            }
        }
        for(var element:type.getEnclosedElements()) if(element instanceof ExecutableElement method&&marked(method)) require(method.getModifiers().contains(Modifier.PUBLIC),"J7M_INACCESSIBLE_SAGA_CALLBACK");
        require(!forward.isEmpty()&&forward.size()<=7,"J7M_SAGA_STEP_CAPACITY");
        var names=new HashSet<String>();var callbacks=new ArrayList<ExecutableElement>();TypeMirror previous=null;
        int order=0;
        for(var entry:forward.entrySet()) {
            var method=entry.getValue();var marker=method.getAnnotation(SagaStep.class);var input=method.getParameters().getFirst().asType();var output=result(method);
            require(entry.getKey()==order++&&names.add(marker.name()),"J7M_INVALID_SAGA_STEP_ORDER");
            require(data(input)&&data(output)&&output.getKind()!=TypeKind.VOID,"J7M_UNSUPPORTED_SAGA_DATA");
            require(previous==null||environment.getTypeUtils().isSameType(previous,input),"J7M_SAGA_CHAIN_TYPE_MISMATCH");previous=output;callbacks.add(method);
            var inverse=compensations.remove(marker.name());
            if(inverse!=null) {
                require(environment.getTypeUtils().isSameType(input,inverse.getParameters().getFirst().asType())
                        &&(inverse.getReturnType().getKind()==TypeKind.VOID||result(inverse).toString().equals("java.lang.Void")),"J7M_INVALID_SAGA_COMPENSATION");callbacks.add(inverse);
            }
        }
        require(compensations.isEmpty(),"J7M_UNKNOWN_SAGA_COMPENSATION_TARGET");if(reconcile!=null) callbacks.add(reconcile);
        String emitted="new org.j7m.middleware.application.api.ManagedHostedFeature(\"saga\","+J7mProcessor.quote(declaration.name())+","+declaration.version()
                +",java.util.Map.of(\"policy\","+J7mProcessor.quote(declaration.policy())+"),java.util.List.of("+callbacks.stream().map(this::identity).collect(java.util.stream.Collectors.joining(","))+"))";
        return new Result(emitted,callbacks);
    }
    private boolean marked(ExecutableElement method) {return method.getAnnotation(SagaStep.class)!=null||method.getAnnotation(Compensation.class)!=null||method.getAnnotation(ReconciliationHandler.class)!=null;}
    private TypeMirror result(ExecutableElement method) {
        var type=method.getReturnType();var erased=environment.getTypeUtils().erasure(type).toString();
        if(erased.equals("java.util.concurrent.CompletionStage")||erased.equals("java.util.concurrent.CompletableFuture")) {
            require(type instanceof DeclaredType&&((DeclaredType)type).getTypeArguments().size()==1,"J7M_INVALID_SAGA_ASYNC_SHAPE");
            var payload=((DeclaredType)type).getTypeArguments().getFirst();require(payload instanceof DeclaredType||payload.getKind()==TypeKind.ARRAY,"J7M_INVALID_SAGA_ASYNC_SHAPE");return payload;
        }
        return type;
    }
    private boolean data(TypeMirror type) {
        if(type.getKind().isPrimitive()) return type.getKind()!=TypeKind.VOID;
        if(type.toString().equals("byte[]")) return true;
        if(!(type instanceof DeclaredType declared)||!declared.getTypeArguments().isEmpty()) return false;
        var element=(TypeElement)declared.asElement();
        return List.of("java.lang.String","java.lang.Boolean","java.lang.Byte","java.lang.Short","java.lang.Character","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double").contains(element.getQualifiedName().toString())
                ||element.getKind()==ElementKind.RECORD&&element.getAnnotation(SchemaType.class)!=null;
    }
    private String identity(ExecutableElement method) {
        String parameters=method.getParameters().stream().map(parameter->J7mProcessor.quote(binary(parameter.asType()))).collect(java.util.stream.Collectors.joining(","));
        return "new org.j7m.middleware.application.api.ManagedMethod("+J7mProcessor.quote(environment.getElementUtils().getBinaryName((TypeElement)method.getEnclosingElement()).toString())+","+J7mProcessor.quote(method.getSimpleName().toString())
                +",java.util.List.of("+parameters+"),"+J7mProcessor.quote(binary(method.getReturnType()))+")";
    }
    private String binary(TypeMirror type) {
        if(type.getKind()==TypeKind.ARRAY) {
            var component=((ArrayType)type).getComponentType();
            if(component.getKind().isPrimitive()) return "["+switch(component.getKind()) {case BOOLEAN->"Z";case BYTE->"B";case SHORT->"S";case INT->"I";case LONG->"J";case CHAR->"C";case FLOAT->"F";case DOUBLE->"D";default->throw new IllegalArgumentException("Invalid array");};
            return "[L"+binary(component)+";";
        }
        var erased=environment.getTypeUtils().erasure(type);var element=environment.getTypeUtils().asElement(erased);
        return element instanceof TypeElement declared?environment.getElementUtils().getBinaryName(declared).toString():erased.toString();
    }
    private static boolean id(String value) {return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");}
    private static void require(boolean condition,String code) {if(!condition) throw new IllegalArgumentException(code);}
}
