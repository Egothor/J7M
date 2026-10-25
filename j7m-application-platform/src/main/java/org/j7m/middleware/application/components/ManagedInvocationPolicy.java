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

package org.j7m.middleware.application.components;

import java.lang.annotation.Annotation;
import java.util.Set;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Trusted host-installed policy factory. Application annotations never install capabilities.
 * Implementations validate actual effective type/method metadata, not untrusted assertion strings.
 * The host invokes validate for every export before component construction, then prepares a bound
 * policy. Each bound policy is closed on failed activation or unexport; the factory is host-owned.
 */
public interface ManagedInvocationPolicy {
    /**
     * Fixed outer-to-inner host ordering. Registration order is stable within one phase;
     * result finalizers unwind in reverse so transaction commit precedes durable replay storage,
     * which precedes outer post-audit. Applications cannot install or reorder these policies.
     */
    enum Phase {
        /**
         * Authentication/authorization and mandatory outer audit.
         */ SECURITY,
        /**
         * Host observation around the admitted operation.
         */ OBSERVATION,
        /**
         * Host resource admission.
         */ ADMISSION,
        /**
         * Lease/fence ownership.
         */ OWNERSHIP,
        /**
         * Durable idempotency/replay claim.
         */ REPLAY,
        /**
         * Participating transaction completion.
         */ TRANSACTION,
        /**
         * Remaining direct application policies.
         */ APPLICATION
    }

    /**
     * Returns the fixed trusted host ordering phase.
     * @return application phase by default for source compatibility
     */
    default Phase phase() { return Phase.APPLICATION; }

    /**
     * Declares exactly which annotation behaviors this implementation enforces.
     * @return immutable finite capability set; an empty set permits a host-wide observer
     */
    Set<Class<? extends Annotation>> annotationTypes();

    /**
     * Validates every finite component before any application constructor runs.
     * The default rejects claimed policy metadata on non-exported components because ordinary Java
     * calls have no invocation boundary. A real component adapter may override this to validate and
     * enforce its supported metadata; annotation presence never installs that adapter.
     * @param component declared component/product type
     * @param exported whether this exact type is a manifest-declared exported implementation
     * @throws IllegalArgumentException for unsupported non-entry policy metadata
     */
    default void validateComponent(Class<?> component, boolean exported) {
        if (exported) return;
        java.util.Set<Class<?>> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>(); pending.add(component);
        Set<Class<? extends Annotation>> claimed = Set.copyOf(annotationTypes());
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!visited.add(type) || type == Object.class) continue;
            if (visited.size() > 256) throw new IllegalArgumentException("component hierarchy capacity");
            rejectClaimed(type, claimed);
            for (var method : type.getDeclaredMethods()) {
                rejectClaimed(method, claimed);
                for (var parameter : method.getParameters()) rejectClaimed(parameter, claimed);
            }
            for (var field : type.getDeclaredFields()) rejectClaimed(field, claimed);
            for (var constructor : type.getDeclaredConstructors()) {
                rejectClaimed(constructor, claimed);
                for (var parameter : constructor.getParameters()) rejectClaimed(parameter, claimed);
            }
            if (type.getSuperclass() != null) pending.add(type.getSuperclass());
            for (Class<?> contract : type.getInterfaces()) pending.add(contract);
        }
    }

    private static void rejectClaimed(java.lang.reflect.AnnotatedElement element, Set<Class<? extends Annotation>> claimed) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (claimed.contains(annotation.annotationType())) throw new IllegalArgumentException("non-entry component policy is unsupported");
        }
    }

    /**
     * Rejects claimed metadata that has no actual manifest-declared invocation boundary.
     * Runs for every finite component before binding policies or constructing application objects.
     * @param component declared component/product type
     * @param exports immutable actual full wire contracts mapped to their business interfaces
     * @throws IllegalArgumentException for claimed metadata outside a supported exposure
     */
    default void validateExposure(final Class<?> component,
            final java.util.Map<ManagedServiceContract, Class<?>> exports) {
        ManagedMethodSurface.validateExposure(component, exports, annotationTypes());
    }

    /**
     * Validates exact generated local hosted boundaries without broadening the wire interface rule.
     * The host must obtain this finite allowlist from a verified generated definition. This setup
     * entrypoint grants no application invocation or worker authority on its own.
     * @param component concrete generated component
     * @param contracts finite complete hosted contract allowlist
     * @throws IllegalArgumentException for unsupported or nonpublic callback metadata
     */
    default void validateHostedExposure(final Class<?> component, final Set<ManagedServiceContract> contracts) {
        var surfaces = new java.util.HashMap<ManagedServiceContract, Class<?>>();
        for (var contract : contracts) surfaces.put(contract, component);
        validateHostedExposure(component, java.util.Map.copyOf(surfaces));
    }

    /**
     * Validates a component's combined exact wire-interface and concrete hosted callback surfaces.
     * @param component concrete generated component
     * @param surfaces verified contracts mapped to their actual interface or hosted component surface
     * @throws IllegalArgumentException for unsupported or uncovered metadata
     */
    default void validateHostedExposure(final Class<?> component,
            final java.util.Map<ManagedServiceContract, Class<?>> surfaces) {
        var hosted = new java.util.HashSet<ManagedServiceContract>();
        for (var entry : surfaces.entrySet()) if (entry.getValue() == component) hosted.add(entry.getKey());
        try {
            ManagedMethodSurface.hosted(component, Set.copyOf(hosted), () -> {
                validateExposure(component, java.util.Map.copyOf(surfaces));
                return null;
            });
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Hosted policy exposure failed"); }
    }

    /**
     * Validates one callback from a verified generated hosted allowlist.
     * @param contract exact callback contract
     * @param component generated concrete component
     * @param callbacks finite callback identities belonging to this definition
     * @throws IllegalArgumentException for an undeclared callback or unsupported policy
     */
    default void validateHosted(final ManagedServiceContract contract, final Class<?> component,
            final Set<org.j7m.middleware.application.api.ManagedMethod> callbacks) {
        if (!callbacks.contains(contract.method())) throw new IllegalArgumentException("Undeclared hosted callback");
        try {
            ManagedMethodSurface.hosted(component, Set.of(contract), () -> { validate(contract, component, component); return null; });
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Hosted policy validation failed"); }
    }

    /**
     * Binds one previously validated callback under the same finite hosted surface rule.
     * @param contract exact callback contract
     * @param component generated concrete component
     * @param callbacks finite verified callback identities
     * @return owned policy binding
     * @throws Exception if the callback is undeclared or policy preparation fails
     */
    default Binding bindHosted(final ManagedServiceContract contract, final Class<?> component,
            final Set<org.j7m.middleware.application.api.ManagedMethod> callbacks) throws Exception {
        if (!callbacks.contains(contract.method())) throw new IllegalArgumentException("Undeclared hosted callback");
        return ManagedMethodSurface.hosted(component, Set.of(contract), () -> bind(contract, component, component));
    }

    /**
     * Validates a complete export contract without executing application code.
     * @param contract verified contract ID/version and complete method signature
     * @param implementation concrete application implementation
     * @param businessInterface manifest-declared ordinary business interface
     * @throws IllegalArgumentException for unsupported combinations
     */
    void validate(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface);

    /**
     * Prepares bounded per-export policy state after all contracts validate, before constructors.
     * @param contract verified complete method identity
     * @param implementation concrete application implementation
     * @param businessInterface manifest-declared business interface
     * @return owned policy state
     * @throws Exception when policy preparation fails
     */
    Binding bind(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) throws Exception;

    /**
     * Direct generated dispatch continuation, callable according to the installed policy contract.
     */
    @FunctionalInterface
    interface Proceed {
        /**
         * Invokes the remaining host pipeline and eventual generated direct call.
         * @return application result, possibly null
         * @throws Exception if dispatch fails
         */
        Object invoke() throws Exception;
        /**
         * Returns the actual prepared/committed target reference supplied by the hosting runtime.
         * A reference is routing evidence, not a bearer authority token. Missing target information
         * must fail closed for a policy requiring a concrete resource identity.
         * @return concrete exported target, or null when the host cannot supply it
         */
        default ObjectRef target() { return null; }
        /**
         * Returns trusted physical completion for deferred admission ownership.
         * Cancellation of a reply stage must not complete this host-owned view.
         * @return physical completion, or null for a legacy synchronous pipeline
         */
        default java.util.concurrent.CompletionStage<Void> physicalCompletion() { return null; }
        /**
         * Reports actual host result-finalizer support, never inferred from application metadata.
         * @return false for a legacy synchronous continuation
         */
        default boolean supportsResultFinalizers() { return false; }
        /**
         * Registers a bounded trusted result finalizer before generated body entry.
         * @param finalizer host-owned callback; executes after actual USER work settles
         */
        default void addResultFinalizer(org.j7m.middleware.transport.api.InvocationResultFinalizer finalizer) {
            throw new IllegalStateException("RESULT_FINALIZATION_UNAVAILABLE");
        }
        /**
         * Installs an exact-type host attachment for this invocation before body entry.
         * @param type exact key
         * @param value assignable non-null host-owned value
         * @param <T> attachment type
         */
        default <T> void putContext(Class<T> type, T value) { throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE"); }
        /**
         * Reads an existing exact-type attachment under serial CALL context.
         * @param type exact key
         * @param <T> attachment type
         * @return installed value
         */
        default <T> T context(Class<T> type) { throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE"); }
        /**
         * Finds a typed attachment only inside the same actual owned CALL.
         * Empty denotes absence, never unsupported or physically closed access.
         * @param type exact key
         * @param <T> attachment type
         * @return optional current frame value
         */
        default <T> java.util.Optional<T> findContext(Class<T> type) {
            throw new IllegalStateException("INVOCATION_CONTEXT_UNAVAILABLE");
        }



    }

    /**
     * Owned per-export policy state; cleanup must not retain application class loaders.
     */
    interface Binding extends AutoCloseable {
        /**
         * Wraps an authenticated invocation; no built-in retry or deduplication is implied.
         * @param context current safe immutable dispatch context
         * @param arguments ordered transport-decoded arguments
         * @param next remaining policy/direct dispatch continuation
         * @return application or policy result
         * @throws Exception if authorization, policy or application dispatch fails
         */
        Object invoke(InvocationContext context, Object[] arguments, Proceed next) throws Exception;
        /**
         * Releases this binding's resources exactly once when called by the host.
         * @throws Exception if cleanup fails
         */
        @Override void close() throws Exception;
    }
}
