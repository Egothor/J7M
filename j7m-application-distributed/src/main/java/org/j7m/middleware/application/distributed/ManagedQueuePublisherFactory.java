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

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.TracedQueue;

/**
 * Trusted resource binding implementing ordinary producer interfaces without application producer
 * bodies. Publication intent preserves first-writer canonical content and trace across retries;
 * confirmed sequence means native queue send committed, not atomicity with caller DB/external effects.
 * Endpoints/codecs remain borrowed; closing a binding only revokes its local proxy.
 */
public final class ManagedQueuePublisherFactory implements ManagedHostBindings.Factory {
    /**
     * Explicit finite host publication capability, never selected from arbitrary annotation classes.
     * @param contract exact producer interface, or null for a trusted symbolic registration
     * @param contractName exact authorized binary name, never used for class loading
     * @param namespace stable logical publication namespace
     * @param tenant actual authorized backend tenant
     * @param queue borrowed authenticated native facade
     * @param intents borrowed D12 first-writer intent backend in its own authorized scope
     * @param codec trusted side-effect-free schema-allowlisted payload codec
     * @param retention native D12 intent retention1ms..1day
     * @param maximumAttempts queue poison limit1..1000
     * @param queues finite destination allowlist, at most256
     */
    public record Registration(Class<?> contract, String contractName, String namespace, String tenant, ReplicatedQueue queue,
            DeduplicatedRpc intents, ManagedPayloadCodec codec, Duration retention, int maximumAttempts, Set<String> queues) {
        /**
         * Copies bounds without connecting or constructing application values.
         */
        public Registration {
            DeliveryIdentities.typeName(contractName); Objects.requireNonNull(queue); Objects.requireNonNull(intents); Objects.requireNonNull(codec);
            DeliveryIdentities.name(namespace); DeliveryIdentities.scope(tenant);
            if (!queue.scopeId().equals(tenant)) throw new IllegalArgumentException("Publisher tenant differs from queue facade");
            queues = Set.copyOf(queues);
            if (contract != null && (!contract.isInterface() || !contract.getName().equals(contractName)) || queues.isEmpty() || queues.size() > 256 || maximumAttempts < 1 || maximumAttempts > 1000
                    || retention == null || retention.compareTo(Duration.ofMillis(1)) < 0 || retention.compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Invalid publication registration");
            queues.forEach(DeliveryIdentities::name);
        }
        /**
         * Registers an exact host-visible producer interface identity.
         * @param contract ordinary interface
         * @param namespace publication namespace
         * @param tenant authorized tenant
         * @param queue borrowed queue
         * @param intents borrowed first-intent journal
         * @param codec explicit bounded payload codec
         * @param retention first-intent retention
         * @param maximumAttempts poison limit
         * @param queues authorized destinations
         */
        public Registration(Class<?> contract, String namespace, String tenant, ReplicatedQueue queue, DeduplicatedRpc intents,
                ManagedPayloadCodec codec, Duration retention, int maximumAttempts, Set<String> queues) {
            this(Objects.requireNonNull(contract), contract.getName(), namespace, tenant, queue, intents, codec, retention, maximumAttempts, queues);
        }
        /**
         * Authorizes an application-owned interface by name. Its actual generated dependency
         * type, annotations and full method/codec contract are validated before construction.
         * @param contractName exact authorized binary name, without class loading
         * @param namespace publication namespace
         * @param tenant authorized tenant
         * @param queue borrowed queue
         * @param intents borrowed first-intent journal
         * @param codec explicit bounded payload codec
         * @param retention first-intent retention
         * @param maximumAttempts poison limit
         * @param queues authorized destinations
         */
        public Registration(String contractName, String namespace, String tenant, ReplicatedQueue queue, DeduplicatedRpc intents,
                ManagedPayloadCodec codec, Duration retention, int maximumAttempts, Set<String> queues) {
            this(null, contractName, namespace, tenant, queue, intents, codec, retention, maximumAttempts, queues);
        }
        /**
         * Omits endpoints, tenant and payload schema from incidental diagnostics.
         * @return fixed safe profile identity
         */
        @Override public String toString() { return "QueuePublisherRegistration[host-owned]"; }
    }
    private final Map<String, Registration> registrations;
    /**
     * Creates at most256 explicitly named producer capabilities.
     * @param registrations resource name to immutable host registration
     * @throws IllegalArgumentException for invalid capacity/name/method contract
     */
    public ManagedQueuePublisherFactory(Map<String, Registration> registrations) {
        TransactionPolicySupport.names(registrations); this.registrations = Map.copyOf(registrations);
        this.registrations.values().forEach(registration -> { if (registration.contract() != null) validateContract(registration, registration.contract()); });
    }
    private ManagedPayloadCodec validateContract(Registration registration, Class<?> contract) {
        if (!contract.isInterface() || !Modifier.isPublic(contract.getModifiers()) || !contract.getName().equals(registration.contractName())
                || registration.contract() != null && registration.contract() != contract) throw new IllegalArgumentException("Producer identity mismatch");
        Method[] methods = contract.getMethods();
        if (methods.length < 1 || methods.length > 64) throw new IllegalArgumentException("Producer method capacity");
        var payloads = new ArrayList<Class<?>>();
        for (Method method : methods) {
            QueuePublisher marker = method.getAnnotation(QueuePublisher.class);
            if (marker == null || method.isDefault() || Modifier.isStatic(method.getModifiers()) || method.getReturnType() != long.class
                    || method.getParameterCount() != 2 || method.getParameterTypes()[0] != String.class || marker.version() < 1
                    || !registration.queues().contains(marker.queue()) || !marker.policy().equals("default"))
                throw new IllegalArgumentException("Unsupported queue producer contract");
            payloads.add(method.getParameterTypes()[1]);
        }
        var codec = registration.codec().forDefinition(contract, payloads);
        payloads.forEach(codec::validateDeclaredType); return codec;
    }
    /**
     * Validates provider-only strict resource binding before component constructors.
     * @param dependency generated resource dependency
     * @param scope component capture scope
     * @throws IllegalArgumentException for unknown resource, refresh or unsupported metadata
     */
    @Override public void validate(ManagedDependency dependency, Scope.Kind scope) {
        Registration registration = registrations.get(dependency.attributes().get("resource.name"));
        if (registration == null || !dependency.provider() || !dependency.qualifiers().isEmpty()
                || !dependency.attributes().keySet().equals(Set.of("resource.name", "binding.refresh", "binding.policy"))
                || !dependency.attributes().get("binding.refresh").equals("NEVER") || !dependency.attributes().get("binding.policy").equals("default"))
            throw new IllegalArgumentException("Unsupported producer resource binding");
        validateContract(registration, dependency.type());
    }
    /**
     * Creates an owned revocable local proxy; network work happens only in an actual USER call.
     * @param dependency validated producer edge
     * @return binding that never owns the borrowed backends
     */
    @Override public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) {
        validate(dependency, Scope.Kind.ACTIVATION); Registration registration = registrations.get(dependency.attributes().get("resource.name"));
        var payloadCodec = validateContract(registration, dependency.type());
        var closed = new AtomicBoolean();
        Object proxy = Proxy.newProxyInstance(dependency.type().getClassLoader(), new Class<?>[]{dependency.type()}, (self, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == arguments[0]; case "hashCode" -> System.identityHashCode(self); case "toString" -> "ManagedQueuePublisher[stable]";
                default -> throw new IllegalStateException("Unsupported Object method");
            };
            if (closed.get() || !ManagedInvocationLifetime.currentUserWorkOpen()) throw new IllegalStateException("Publication scope unavailable");
            InvocationContext context = J7mContext.currentInvocation();
            if (context.security().principal().id().equals("anonymous")) throw MiddlewareException.of(MiddlewareErrorCode.UNAUTHENTICATED, "Publication requires authenticated caller");
            if (arguments == null || arguments.length != 2 || !(arguments[0] instanceof String id)) throw new IllegalArgumentException("Publication identity required");
            DeliveryIdentities.businessKey(id); QueuePublisher marker = method.getAnnotation(QueuePublisher.class);
            byte[] frame = payloadCodec.encode(arguments[1], method.getParameterTypes()[1]);
            String identity = DeliveryIdentities.digest("queue-publication", registration.tenant(), registration.namespace(), registration.queue().partitionId(), marker.queue(),
                    context.security().trustDomain().id(), context.security().principal().id(), method.getDeclaringClass().getName(), method.getName(), Arrays.stream(method.getParameterTypes()).map(Class::getName).toList().toString(), method.getReturnType().getName(), Integer.toString(marker.version()), id);
            String hash = DeliveryIdentities.digest(marker.queue(), Integer.toString(registration.maximumAttempts()), Base64.getEncoder().encodeToString(frame));
            TracedQueue.Message message = PublicationIntentStore.remember(registration.intents(), registration.retention(), identity, hash, frame, context);
            String result = registration.queue().send("publication-" + identity, marker.queue(), identity, TracedQueue.encode(message), registration.maximumAttempts(), TransactionPolicySupport.deadline(context));
            if (!result.startsWith("SENT\t")) throw MiddlewareException.of(result.equals("MESSAGE_CONFLICT") || result.equals("REQUEST_CONFLICT")
                    ? MiddlewareErrorCode.INVALID_ARGUMENT : MiddlewareErrorCode.UNAVAILABLE, "Queue publication not confirmed");
            return Long.parseLong(result.substring(5));
        });
        return new ManagedHostBindings.BoundValue() {
            @Override public Object get() { if (closed.get()) throw new IllegalStateException("Producer binding closed"); return proxy; }
            @Override public void close() { closed.set(true); }
        };
    }
}
