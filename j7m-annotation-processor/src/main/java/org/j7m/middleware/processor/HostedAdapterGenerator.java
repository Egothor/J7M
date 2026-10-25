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
import org.j7m.middleware.annotations.EventSubscription;
import org.j7m.middleware.annotations.PartitionHandler;

/**
 * Emits finite host-only direct callbacks; never executes or reflectively invokes application code.
 */
final class HostedAdapterGenerator {
    private final ProcessingEnvironment environment;

    HostedAdapterGenerator(final ProcessingEnvironment environment) { this.environment = environment; }

    boolean hosted(final TypeElement type) {
        return type.getAnnotation(org.j7m.middleware.annotations.SagaDefinition.class) != null || type.getAnnotation(PartitionHandler.class) != null || methods(type).stream()
                .anyMatch(method -> method.getAnnotation(EventSubscription.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.DurableQueueConsumer.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.DeadLetterHandler.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.SagaStep.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.Compensation.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.ReconciliationHandler.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.DurableJob.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.Schedule.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.Checkpoint.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.Restore.class) != null
                        || method.getAnnotation(org.j7m.middleware.annotations.Progress.class) != null);
    }

    String adapter(final TypeElement type) {
        if (methods(type).stream().anyMatch(method -> method.getAnnotation(org.j7m.middleware.annotations.DurableJob.class) != null)) return "ManagedJobDefinition";
        return type.getAnnotation(PartitionHandler.class) == null ? "ManagedHostedAdapter" : "ManagedPartitionDefinition";
    }

    String generate(final TypeElement type, final List<javax.lang.model.element.Element> dependencySites) {
        var features = new ArrayList<String>(backends(type, dependencySites));
        var callbacks = new ArrayList<ExecutableElement>();
        String partitionMethods = "";
        var partition = type.getAnnotation(PartitionHandler.class);
        if (partition != null) {
            require(identifier(partition.resource(), 128) && identifier(partition.policy(), 128) && partition.version() > 0,
                    "J7M_INVALID_PARTITION_PROFILE");
            require(type.getAnnotation(org.j7m.middleware.annotations.LeaseGuard.class) == null
                    && type.getAnnotation(org.j7m.middleware.annotations.LeaderTask.class) == null, "J7M_PARTITION_CALLBACK_MUST_BE_PURE");
            var initial = named(type, "initial"); var apply = named(type, "apply"); var query = named(type, "query");
            require(initial.getParameters().isEmpty() && apply.getParameters().size() == 2 && query.getParameters().size() == 2,
                    "J7M_INVALID_PARTITION_SIGNATURE");
            var state = initial.getReturnType();
            require(environment.getTypeUtils().isSameType(state, apply.getReturnType())
                    && environment.getTypeUtils().isSameType(state, apply.getParameters().getFirst().asType())
                    && environment.getTypeUtils().isSameType(state, query.getParameters().getFirst().asType()),
                    "J7M_PARTITION_STATE_TYPE_CONFLICT");
            for (var method : List.of(initial, apply, query)) validateCallback(method);
            for (var data : List.of(state, apply.getParameters().get(1).asType(), query.getParameters().get(1).asType(), query.getReturnType())) {
                require(dataType(data), "J7M_UNSUPPORTED_PARTITION_DATA_TYPE");
            }
            for (var field : environment.getElementUtils().getAllMembers(type)) {
                if (field.getKind() == ElementKind.FIELD && !field.getModifiers().contains(Modifier.STATIC)) {
                    require(field.getModifiers().contains(Modifier.FINAL), "J7M_MUTABLE_PARTITION_COMPONENT_FIELD");
                    var fieldType = environment.getTypeUtils().asElement(field.asType());
                    require(dataType(field.asType()) || fieldType != null
                            && fieldType.getAnnotation(org.j7m.middleware.annotations.ConfigProperties.class) != null,
                            "J7M_UNSUPPORTED_PARTITION_CONFIG_FIELD");
                }
            }
            for (var element : type.getEnclosedElements()) {
                if (element.getKind() == ElementKind.CONSTRUCTOR) {
                    for (var parameter : ((ExecutableElement) element).getParameters()) {
                        var parameterType = environment.getTypeUtils().asElement(parameter.asType());
                        require(parameter.getAnnotation(org.j7m.middleware.annotations.ConfigValue.class) != null
                                || parameterType != null && parameterType.getAnnotation(org.j7m.middleware.annotations.ConfigProperties.class) != null,
                                "J7M_PARTITION_REQUIRES_CONFIG_ONLY_DEPENDENCIES");
                    }
                }
                for (var annotation : element.getAnnotationMirrors()) {
                    String name = annotation.getAnnotationType().toString();
                    require(!List.of("Initialize", "Startup", "Drain", "Shutdown", "RemoteMethod", "LeaseGuard", "LeaderTask").stream()
                            .anyMatch(marker -> name.equals("org.j7m.middleware.annotations." + marker)), "J7M_PARTITION_CALLBACK_MUST_BE_PURE");
                }
            }
            callbacks.addAll(List.of(initial, apply, query));
            String feature = feature("partition", partition.resource(), partition.version(),
                    "java.util.Map.of(\"policy\"," + J7mProcessor.quote(partition.policy()) + ")", callbacks);
            features.add(feature);
            partitionMethods = "public org.j7m.middleware.application.api.ManagedHostedFeature partition(){return " + feature + ";}"
                    + "public Class<?> stateType(){return " + state + ".class;}"
                    + "public Class<?> commandType(){return " + apply.getParameters().get(1).asType() + ".class;}"
                    + "public Class<?> queryType(){return " + query.getParameters().get(1).asType() + ".class;}"
                    + "public Class<?> viewType(){return " + query.getReturnType() + ".class;}"
                    + "public Object initial(" + type.getQualifiedName() + " value)throws Exception{return value.initial();}"
                    + "public Object apply(" + type.getQualifiedName() + " value,Object state,Object command)throws Exception{return value.apply(("
                    + state + ")state,(" + apply.getParameters().get(1).asType() + ")command);}"
                    + "public Object query(" + type.getQualifiedName() + " value,Object state,Object query)throws Exception{return value.query(("
                    + state + ")state,(" + query.getParameters().get(1).asType() + ")query);}";
        }
        for (var method : methods(type)) {
            var subscription = method.getAnnotation(EventSubscription.class);
            if (subscription == null) continue;
            require(partition == null, "J7M_PARTITION_CANNOT_HOST_EFFECTFUL_CALLBACKS");
            validateCallback(method);
            require(identifier(subscription.value(), 128) && identifier(subscription.policy(), 128)
                    && subscription.version() > 0 && subscription.filter().length() <= 128
                    && subscription.filter().chars().noneMatch(Character::isISOControl), "J7M_INVALID_EVENT_SUBSCRIPTION");
            require(method.getParameters().size() == 1 && environment.getTypeUtils().erasure(method.getParameters().getFirst().asType()).toString()
                    .equals("org.j7m.middleware.application.distributed.ManagedEventDelivery"), "J7M_INVALID_EVENT_SUBSCRIPTION_PARAMETER");
            boolean voidReturn = method.getReturnType().getKind() == TypeKind.VOID;
            boolean stageReturn = method.getReturnType() instanceof DeclaredType declared
                    && environment.getTypeUtils().erasure(declared).toString().equals("java.util.concurrent.CompletionStage")
                    && declared.getTypeArguments().size() == 1 && declared.getTypeArguments().getFirst().toString().equals("java.lang.Void");
            require(voidReturn || stageReturn, "J7M_INVALID_EVENT_SUBSCRIPTION_RESULT");
            callbacks.add(method);
            features.add(feature("event-subscription", subscription.value(), subscription.version(),
                    "java.util.Map.of(\"policy\"," + J7mProcessor.quote(subscription.policy()) + ",\"filter\","
                            + J7mProcessor.quote(subscription.filter()) + ")", List.of(method)));
        }
        var queueIds = new java.util.HashSet<String>();
        for (var method : methods(type)) {
            var consumer = method.getAnnotation(org.j7m.middleware.annotations.DurableQueueConsumer.class);
            var deadLetter = method.getAnnotation(org.j7m.middleware.annotations.DeadLetterHandler.class);
            if (consumer == null && deadLetter == null) continue;
            require(consumer == null || deadLetter == null, "J7M_CONFLICTING_QUEUE_HANDLER");
            require(partition == null && method.getAnnotation(EventSubscription.class) == null,
                    "J7M_CONFLICTING_HOSTED_CALLBACK");
            validateCallback(method);
            String kind = consumer != null ? "queue-consumer" : "dead-letter-handler";
            String queue = consumer != null ? consumer.queue() : deadLetter.queue();
            String policy = consumer != null ? consumer.policy() : deadLetter.policy();
            String id = consumer != null ? consumer.value() : deadLetter.value();
            int version = consumer != null ? consumer.version() : deadLetter.version();
            if (id.isEmpty()) id = environment.getElementUtils().getBinaryName((TypeElement) method.getEnclosingElement()) + "#" + method.getSimpleName();
            require(id.length() <= 128 && !id.isBlank() && id.chars().noneMatch(Character::isISOControl)
                    && identifier(queue, 128) && identifier(policy, 128) && version > 0, "J7M_INVALID_QUEUE_HANDLER");
            require(queueIds.add(kind + ":" + id), "J7M_DUPLICATE_QUEUE_HANDLER_ID");
            require(method.getParameters().size() == 1 && dataType(method.getParameters().getFirst().asType()),
                    "J7M_INVALID_QUEUE_HANDLER_PARAMETER");
            boolean voidReturn = method.getReturnType().getKind() == TypeKind.VOID;
            boolean stageReturn = method.getReturnType() instanceof DeclaredType declared
                    && environment.getTypeUtils().erasure(declared).toString().equals("java.util.concurrent.CompletionStage")
                    && declared.getTypeArguments().size() == 1 && declared.getTypeArguments().getFirst().toString().equals("java.lang.Void");
            require(voidReturn || stageReturn, "J7M_INVALID_QUEUE_HANDLER_RESULT");
            callbacks.add(method);
            features.add(feature(kind, id, version, "java.util.Map.of(\"queue\"," + J7mProcessor.quote(queue)
                    + ",\"policy\"," + J7mProcessor.quote(policy) + ")", List.of(method)));
        }
        var saga = new SagaHostedGenerator(environment).generate(type);
        if (saga != null) {
            require(partition == null, "J7M_PARTITION_CANNOT_HOST_EFFECTFUL_CALLBACKS");
            features.add(saga.feature()); callbacks.addAll(saga.callbacks());
        }
        var job = new JobHostedGenerator(environment).generate(type);
        if (job != null) {
            require(features.isEmpty(), "J7M_JOB_COMPONENT_CANNOT_HOST_OTHER_ENTRIES");
            features.add(job.feature()); callbacks.addAll(job.callbacks());
        }
        require(features.size() <= 64, "J7M_HOSTED_FEATURE_CAPACITY");
        StringBuilder dispatch = new StringBuilder("public Object invokeHosted(" + type.getQualifiedName()
                + " value,org.j7m.middleware.application.api.ManagedMethod method,Object[] arguments)throws Exception{");
        for (var callback : callbacks) {
            dispatch.append("if(method.equals(").append(identity(callback)).append(")){if(arguments.length!=")
                    .append(callback.getParameters().size()).append(")throw new IllegalArgumentException(\"Hosted argument count\");");
            if (callback.getReturnType().getKind() != TypeKind.VOID) dispatch.append("return ");
            dispatch.append("value.").append(callback.getSimpleName()).append('(');
            var arguments = new ArrayList<String>();
            for (int index = 0; index < callback.getParameters().size(); index++) {
                arguments.add("(" + callback.getParameters().get(index).asType() + ")arguments[" + index + "]");
            }
            dispatch.append(String.join(",", arguments)).append(");");
            if (callback.getReturnType().getKind() == TypeKind.VOID) dispatch.append("return null;");
            dispatch.append('}');
        }
        dispatch.append("throw new IllegalArgumentException(\"Hosted callback is not declared\");}");
        return "public java.util.List<org.j7m.middleware.application.api.ManagedHostedFeature> hostedFeatures(){return java.util.List.of("
                + String.join(",", features) + ");}" + partitionMethods + (job == null ? "" : job.methods()) + dispatch;
    }

    List<String> backends(final TypeElement component, final List<javax.lang.model.element.Element> sites) {
        var features = new java.util.LinkedHashMap<String, String>();
        for (var site : sites) {
            var resource = site.getAnnotation(org.j7m.middleware.annotations.J7mResource.class);
            if (resource == null || resource.value().isEmpty()) continue;
            var type = site.asType();
            if (type instanceof DeclaredType declared && declared.asElement().toString().equals("org.j7m.middleware.application.api.ManagedProvider")) {
                require(declared.getTypeArguments().size() == 1, "J7M_INVALID_OUTBOX_PRODUCER_DEPENDENCY");
                type = declared.getTypeArguments().getFirst();
            }
            var element = environment.getTypeUtils().asElement(type);
            if (!(element instanceof TypeElement producer) || producer.getKind() != ElementKind.INTERFACE) continue;
            for (var method : methods(producer)) {
                var marker = method.getAnnotation(org.j7m.middleware.annotations.OutboxPublisher.class);
                if (marker == null) continue;
                require(identifier(resource.value(),128) && identifier(marker.value(),128)
                        && identifier(marker.policy(),128) && marker.version() > 0, "J7M_INVALID_OUTBOX_RELAY");
                String id = environment.getElementUtils().getBinaryName(component) + "#" + resource.value() + "#" + marker.value() + "#" + marker.version();
                require(id.length() <= 128, "J7M_OUTBOX_RELAY_ID_CAPACITY");
                String emitted = feature("outbox-relay", id, marker.version(), "java.util.Map.of(\"resource\","
                        + J7mProcessor.quote(resource.value()) + ",\"outbox\"," + J7mProcessor.quote(marker.value())
                        + ",\"policy\"," + J7mProcessor.quote(marker.policy()) + ",\"version\","
                        + J7mProcessor.quote(Integer.toString(marker.version())) + ")", List.of());
                String previous = features.putIfAbsent(id, emitted);
                require(previous == null || previous.equals(emitted), "J7M_CONFLICTING_OUTBOX_RELAY");
            }
        }
        return List.copyOf(features.values());
    }

    private List<ExecutableElement> methods(final TypeElement type) {
        return environment.getElementUtils().getAllMembers(type).stream().filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element).toList();
    }
    private ExecutableElement named(final TypeElement type, final String name) {
        var matching = methods(type).stream().filter(method -> method.getSimpleName().contentEquals(name)).toList();
        require(matching.size() == 1, "J7M_AMBIGUOUS_PARTITION_CALLBACK"); return matching.getFirst();
    }
    private static void validateCallback(final ExecutableElement method) {
        require(method.getModifiers().contains(Modifier.PUBLIC) && !method.getModifiers().contains(Modifier.STATIC)
                && method.getTypeParameters().isEmpty(), "J7M_INACCESSIBLE_HOSTED_CALLBACK");
    }
    private boolean dataType(final javax.lang.model.type.TypeMirror type) {
        if (type.getKind().isPrimitive()) return true;
        if (type.toString().equals("byte[]")) return true;
        if (!(type instanceof DeclaredType declared) || !declared.getTypeArguments().isEmpty()) return false;
        var element = (TypeElement) declared.asElement();
        return List.of("java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character").contains(element.getQualifiedName().toString())
                || element.getKind() == ElementKind.RECORD && element.getAnnotation(org.j7m.middleware.annotations.SchemaType.class) != null;
    }
    private String identity(final ExecutableElement method) {
        String parameters = method.getParameters().stream().map(parameter -> J7mProcessor.quote(binary(parameter.asType())))
                .collect(java.util.stream.Collectors.joining(","));
        return "new org.j7m.middleware.application.api.ManagedMethod(" + J7mProcessor.quote(environment.getElementUtils()
                .getBinaryName((TypeElement) method.getEnclosingElement()).toString()) + "," + J7mProcessor.quote(method.getSimpleName().toString())
                + ",java.util.List.of(" + parameters + ")," + J7mProcessor.quote(binary(method.getReturnType())) + ")";
    }
    private String binary(final javax.lang.model.type.TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            var component = ((javax.lang.model.type.ArrayType) type).getComponentType();
            if (component.getKind().isPrimitive()) return "[" + switch (component.getKind()) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case INT -> "I"; case LONG -> "J";
                case CHAR -> "C"; case FLOAT -> "F"; case DOUBLE -> "D"; default -> throw new IllegalArgumentException("Invalid array");
            };
            return "[L" + binary(component) + ";";
        }
        var erased = environment.getTypeUtils().erasure(type);
        var element = environment.getTypeUtils().asElement(erased);
        return element instanceof TypeElement declared ? environment.getElementUtils().getBinaryName(declared).toString() : erased.toString();
    }
    private String feature(final String kind, final String id, final int version, final String attributes,
            final List<ExecutableElement> methods) {
        return "new org.j7m.middleware.application.api.ManagedHostedFeature(" + J7mProcessor.quote(kind) + "," + J7mProcessor.quote(id)
                + "," + version + "," + attributes + ",java.util.List.of("
                + methods.stream().map(this::identity).collect(java.util.stream.Collectors.joining(",")) + "))";
    }
    private static boolean identifier(final String value, final int maximum) {
        return value != null && value.length() <= maximum && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
    }
    private static void require(final boolean condition, final String code) { if (!condition) throw new IllegalArgumentException(code); }
}
