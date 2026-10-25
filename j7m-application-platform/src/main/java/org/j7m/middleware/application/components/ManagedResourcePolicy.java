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
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.annotations.Priority;
import org.j7m.middleware.annotations.ResourceRequirement;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Host-installed named resource admission and explicit JVM heap capability policy.
 *
 * <p>Profiles are shared within this factory's host JVM, not coordinated across a cluster. Admission
 * enforces concurrency, queue capacity, relative priority and bounded wait. Heap profiles validate
 * the actual whole-process JVM heap ceiling; they neither create per-application memory quotas nor
 * claim OS/filesystem/native-code isolation. Unknown profiles and unsupported asynchronous entries
 * are rejected before component construction. Host setup owns this factory and closes it only
 * after its exported applications drain. Permission and deadline policies remain separate layers.
 */
public final class ManagedResourcePolicy implements ManagedInvocationPolicy, AutoCloseable {
    /**
     * One host-local shared admission profile.
     * @param capacity simultaneous managed method executions, 1..1000000
     * @param maximumQueued waiting callers, 0..1024
     * @param maximumWait bounded queue wait, zero..one minute
     * @param ageingStep positive priority ageing interval, at most one day
     * @param maximumPriority highest application-declared priority allowed, -1024..1024
     */
    public record ResourceProfile(int capacity, int maximumQueued, Duration maximumWait,
            Duration ageingStep, int maximumPriority) {
        /**
         * Validates finite enforced admission bounds.
         * @throws IllegalArgumentException for invalid bounds
         * @throws NullPointerException if either interval is null
         */
        public ResourceProfile {
            Objects.requireNonNull(maximumWait);
            Objects.requireNonNull(ageingStep);
            if (capacity < 1 || capacity > 1_000_000 || maximumQueued < 0 || maximumQueued > 1024
                    || maximumWait.isNegative() || maximumWait.compareTo(Duration.ofMinutes(1)) > 0
                    || ageingStep.isNegative() || ageingStep.isZero() || ageingStep.compareTo(Duration.ofDays(1)) > 0
                    || maximumPriority < -1024 || maximumPriority > 1024) {
                throw new IllegalArgumentException("Invalid managed resource profile");
            }
        }
    }

    /**
     * Whole-host JVM heap ceiling, verified against Runtime.maxMemory.
     * @param maximumHeapBytes positive host heap ceiling in bytes; not a per-app or native memory quota
     */
    public record HostHeapProfile(long maximumHeapBytes) {
        /**
         * Rejects invalid ceilings.
         * @throws IllegalArgumentException if the ceiling is not positive
         */
        public HostHeapProfile {
            if (maximumHeapBytes <= 0) { throw new IllegalArgumentException("Invalid host JVM heap ceiling"); }
        }
    }

    private record Rules(String resource, String isolation, int priority, boolean admitted) { }
    private final Map<String, ResourceProfile> resources;
    private final Map<String, HostHeapProfile> isolation;
    private final Map<String, ManagedAdmissionController> controllers;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an immutable registry of host-enforced profiles.
     * A Priority declaration without ResourceRequirement selects the named resource profile default.
     * Method priority overrides a type default only within the host profile's maximumPriority;
     * contradictory method priorities or resource/isolation names are rejected.
     * @param resources at most 64 named shared host-local admission profiles
     * @param isolation at most 64 named whole-host JVM heap profiles
     * @throws IllegalArgumentException for invalid identifiers or excessive profiles
     * @throws NullPointerException for null maps/members
     */
    public ManagedResourcePolicy(final Map<String, ResourceProfile> resources, final Map<String, HostHeapProfile> isolation) {
        this.resources = Map.copyOf(resources);
        this.isolation = Map.copyOf(isolation);
        if (this.resources.size() > 64 || this.isolation.size() > 64) {
            throw new IllegalArgumentException("Managed resource profile capacity exceeded");
        }
        this.resources.keySet().forEach(ManagedResourcePolicy::identifier);
        this.isolation.keySet().forEach(ManagedResourcePolicy::identifier);
        final Map<String, ManagedAdmissionController> controllers = new HashMap<>();
        this.resources.forEach((name, profile) -> controllers.put(name,
                new ManagedAdmissionController(profile.capacity(), profile.maximumQueued(), profile.ageingStep())));
        this.controllers = Map.copyOf(controllers);
    }

    /**
     * {@inheritDoc}
     */
    @Override public Phase phase() { return Phase.ADMISSION; }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Class<? extends Annotation>> annotationTypes() {
        return Set.of(ResourceRequirement.class, IsolationProfile.class, Priority.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateComponent(final Class<?> component, final boolean exported) {
        if (this.closed.get()) { throw new IllegalStateException("Managed resource factory is closed"); }
        for (Class<?> type : ManagedMethodSurface.types(component)) {
            validateSite(type, exported);
            for (Method method : type.getDeclaredMethods()) { validateSite(method, exported); }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        resolve(contract, implementation, businessInterface);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Binding bind(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        final Rules rules = resolve(contract, implementation, businessInterface);
        final AtomicBoolean detached = new AtomicBoolean();
        return new Binding() {
            @Override
            public Object invoke(final InvocationContext context, final Object[] arguments, final Proceed next) throws Exception {
                if (detached.get() || closed.get()) {
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Managed resource binding is closed");
                }
                checkHeap(rules.isolation());
                if (!rules.admitted()) { return next.invoke(); }
                final ResourceProfile profile = resources.get(rules.resource());
                final ManagedInvocationLifetime lifetime = ManagedInvocationLifetime.current();
                final Duration wait = lifetime == null || profile.maximumWait().compareTo(lifetime.remainingBudget()) <= 0
                        ? profile.maximumWait() : lifetime.remainingBudget();
                final var permit = controllers.get(rules.resource()).acquire(rules.priority(), wait);
                if (lifetime != null && lifetime.cancellationRequested()) {
                    permit.close();
                    throw MiddlewareException.of(lifetime.remainingBudget().isZero() ? MiddlewareErrorCode.DEADLINE_EXCEEDED
                            : MiddlewareErrorCode.CANCELLED, "Managed admission budget ended before entry");
                }
                final CompletionStage<Void> physical = next.physicalCompletion();
                if (physical == null && CompletionStage.class.isAssignableFrom(
                        ManagedMethodSurface.method(contract, businessInterface).getReturnType())) {
                    permit.close();
                    throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Managed async lifetime is unavailable");
                }
                if (physical != null) {
                    physical.whenComplete((ignored, failure) -> permit.close());
                    return next.invoke();
                }
                try (permit) { return next.invoke(); }
            }

            @Override
            public void close() { detached.set(true); }
        };
    }

    /**
     * Returns aggregate host-local admission counters for a registered profile.
     * @param profile exact trusted profile name
     * @return immutable aggregate counters
     * @throws IllegalArgumentException if the profile is unknown
     */
    public ManagedAdmissionController.Status status(final String profile) {
        if (profile == null) { throw new IllegalArgumentException("Unknown managed resource profile"); }
        final ManagedAdmissionController controller = this.controllers.get(profile);
        if (controller == null) { throw new IllegalArgumentException("Unknown managed resource profile"); }
        return controller.status();
    }

    /**
     * Stops future/queued admission without cancelling running methods or closing borrowed host services.
     */
    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) { this.controllers.values().forEach(ManagedAdmissionController::close); }
    }

    private Rules resolve(final ManagedServiceContract contract, final Class<?> implementation, final Class<?> businessInterface) {
        if (this.closed.get()) { throw new IllegalStateException("Managed resource factory is closed"); }
        final List<AnnotatedElement> declarations = ManagedMethodSurface.declarations(contract, implementation, businessInterface);
        final Set<String> resourceNames = new HashSet<>();
        final Set<String> isolationNames = new HashSet<>();
        final Set<Integer> methodPriorities = new HashSet<>();
        final Set<Integer> typePriorities = new HashSet<>();
        for (AnnotatedElement declaration : declarations) {
            final ResourceRequirement resource = declaration.getDeclaredAnnotation(ResourceRequirement.class);
            if (resource != null) { resourceNames.add(identifier(resource.value())); }
            final IsolationProfile isolated = declaration.getDeclaredAnnotation(IsolationProfile.class);
            if (isolated != null) { isolationNames.add(identifier(isolated.value())); }
            final Priority priority = declaration.getDeclaredAnnotation(Priority.class);
            if (priority != null) {
                if (priority.value() < -1024 || priority.value() > 1024) {
                    throw new IllegalArgumentException("Unsupported managed relative priority");
                }
                (declaration instanceof Method ? methodPriorities : typePriorities).add(priority.value());
            }
        }
        final Set<Integer> priorities = methodPriorities.isEmpty() ? typePriorities : methodPriorities;
        if (resourceNames.size() > 1 || isolationNames.size() > 1 || priorities.size() > 1) {
            throw new IllegalArgumentException("Conflicting managed resource declarations");
        }
        final boolean admitted = !resourceNames.isEmpty() || !priorities.isEmpty();
        final String resource = resourceNames.isEmpty() ? "default" : resourceNames.iterator().next();
        final String isolated = isolationNames.isEmpty() ? "" : isolationNames.iterator().next();
        final int priority = priorities.isEmpty() ? 0 : priorities.iterator().next();
        if (admitted && (!this.resources.containsKey(resource) || priority < -1024
                || priority > this.resources.get(resource).maximumPriority())) {
            throw new IllegalArgumentException("Unknown or unsupported managed resource requirement");
        }
        checkHeap(isolated);
        final Class<?> returnType = ManagedMethodSurface.method(contract, businessInterface).getReturnType();
        if (admitted && Future.class.isAssignableFrom(returnType) && !CompletionStage.class.isAssignableFrom(returnType)) {
            throw new IllegalArgumentException("Managed asynchronous resource accounting requires an async host policy");
        }
        return new Rules(resource, isolated, priority, admitted);
    }

    private void validateSite(final AnnotatedElement element, final boolean exported) {
        final IsolationProfile isolated = element.getDeclaredAnnotation(IsolationProfile.class);
        if (isolated != null) { checkHeap(identifier(isolated.value())); }
        final ResourceRequirement requirement = element.getDeclaredAnnotation(ResourceRequirement.class);
        final Priority priority = element.getDeclaredAnnotation(Priority.class);
        if (requirement != null && !this.resources.containsKey(identifier(requirement.value()))) {
            throw new IllegalArgumentException("Unknown managed component resource requirement");
        }
        if (priority != null && (priority.value() < -1024 || priority.value() > 1024)) {
            throw new IllegalArgumentException("Unsupported managed component relative priority");
        }
        if (!exported && (requirement != null || priority != null)) {
            throw new IllegalArgumentException("Managed entry admission is unsupported on a non-entry component");
        }
    }

    private void checkHeap(final String name) {
        if (name.isEmpty()) { return; }
        final HostHeapProfile profile = this.isolation.get(name);
        if (profile == null || Runtime.getRuntime().maxMemory() > profile.maximumHeapBytes()) {
            throw new IllegalArgumentException("Unknown or unenforced host JVM heap isolation profile");
        }
    }

    private static String identifier(final String name) {
        if (name == null || name.isBlank() || name.length() > 128 || !name.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid managed resource profile name");
        }
        return name;
    }
}
