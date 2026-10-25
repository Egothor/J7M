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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.TrustDomain;
import org.junit.jupiter.api.Test;

class ManagedHostedResourcesTest {
    @Test void missingAtomicAdmissionImplementationFailsClosedInsteadOfPretendingToStartOrDrain() {
        ManagedHostedResources.Binding unsupported = new ManagedHostedResources.Binding() {
            @Override public void prepare() { throw new AssertionError("not called"); }
            @Override public void start() { throw new AssertionError("not called"); }
            @Override public boolean drain(Duration budget) { throw new AssertionError("not called"); }
            @Override public void close() { }
        };
        assertThrows(UnsupportedOperationException.class, unsupported::stopAdmission);
        assertThrows(UnsupportedOperationException.class, unsupported::commitAdmission);
    }

    private static SecurityContext worker() {
        return new SecurityContext(Principal.anonymous(), "host", new TrustDomain("worker"), null,
                "", "", "", false, null, Set.of(), Map.of(), Instant.EPOCH);
    }
    private static ManagedHostedResources.Factory unusedFactory() {
        return new ManagedHostedResources.Factory() {
            @Override public void validate(org.j7m.middleware.application.api.ManagedHostedFeature feature,
                    org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) {
                throw new AssertionError("Registry construction must not execute a factory");
            }
            @Override public ManagedHostedResources.Binding bind(org.j7m.middleware.application.api.ManagedHostedFeature feature,
                    org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, Object component,
                    ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) {
                throw new AssertionError("Registry construction must not execute a factory");
            }
        };
    }

    @Test
    void snapshotsOnlyExplicitFactoriesWithoutExecutingApplicationOrBackendCode() {
        var entry = new ManagedHostedResources.Entry(unusedFactory(), worker(), Duration.ofDays(1));
        var entries = new HashMap<>(Map.of("partition", entry));
        var registry = new ManagedHostedResources(entries); entries.clear();
        assertTrue(registry.supports("partition"));
        assertSame(entry, registry.require("partition"));
        assertTrue(registry.annotationTypes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("application-installed"));
        assertFalse(ManagedHostedResources.empty().supports("partition"));
        assertThrows(IllegalArgumentException.class, () -> ManagedHostedResources.empty().require("partition"));
    }

    @Test
    void rejectsRemoteAuthorityClaimsAndUnboundedHostProfiles() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources.Entry(unusedFactory(), SecurityContext.system("host"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources.Entry(unusedFactory(), worker(), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources.Entry(unusedFactory(), worker(), Duration.ofDays(1).plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources(Map.of("../partition", new ManagedHostedResources.Entry(unusedFactory(), worker(), Duration.ofSeconds(1)))));
        var entries = new HashMap<String, ManagedHostedResources.Entry>();
        for (int index = 0; index < 64; index++) entries.put("kind" + index, new ManagedHostedResources.Entry(unusedFactory(), worker(), Duration.ofSeconds(1)));
        assertTrue(new ManagedHostedResources(entries).supports("kind63"));
        entries.put("excess", entries.get("kind0"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources(entries));
    }

    @Test
    void verifiedBundleBoundsAndDiagnosticsDoNotDiscloseConfiguration() {
        var configuration = new HashMap<>(Map.of("secret", "SYNTHETIC_PRIVATE_CONFIG"));
        var bundle = new ManagedHostedResources.Bundle("a".repeat(64), configuration); configuration.clear();
        assertTrue(bundle.semanticConfiguration().containsKey("secret"));
        assertFalse(bundle.toString().contains("SYNTHETIC_PRIVATE_CONFIG"));
        assertThrows(UnsupportedOperationException.class, () -> bundle.semanticConfiguration().clear());
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources.Bundle("application-assertion", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources.Bundle("a".repeat(64), Map.of("key", "v".repeat(65536))));
    }

    @Test void sameActualFactoryCanOwnTwoKindsButDistinctFactoryCannotStealItsAnnotation() {
        var first = new CapabilityFactory();
        var entry = new ManagedHostedResources.Entry(first, worker(), Duration.ofSeconds(1));
        var registry = new ManagedHostedResources(Map.of("queue-consumer",entry,"dead-letter-handler",entry));
        assertTrue(registry.annotationTypes().contains(org.j7m.middleware.annotations.DurableQueueConsumer.class));
        assertFalse(registry.backendOnly("queue-consumer"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources(Map.of("one",entry,"two",
                new ManagedHostedResources.Entry(new CapabilityFactory(),worker(),Duration.ofSeconds(1)))));
        first.backends = Set.of("queue-consumer");
        assertThrows(IllegalArgumentException.class, () -> registry.require("queue-consumer"));
    }

    @Test void backendAndGuardCapabilitiesAreFiniteCapturedHostStateRatherThanDescriptorAssertions() {
        var factory = new CapabilityFactory(); factory.backends = Set.of("outbox-relay");
        ManagedInvocationPolicy guard = new ManagedInvocationPolicy() {
            @Override public Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() { return Set.of(); }
            @Override public void validate(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> business) { }
            @Override public Binding bind(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> business) { return new Binding() {
                @Override public Object invoke(org.j7m.middleware.application.api.InvocationContext context, Object[] arguments, Proceed next) throws Exception { return next.invoke(); }
                @Override public void close() { }
            }; }
        };
        factory.guards = Set.of(guard);
        var registry = new ManagedHostedResources(Map.of("outbox-relay",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(1))));
        assertTrue(registry.backendOnly("outbox-relay")); assertSame(guard, registry.requiredPolicies("outbox-relay").getFirst());
        factory.guards = Set.of();
        assertThrows(IllegalArgumentException.class, () -> registry.requiredPolicies("outbox-relay"));
        factory.backends = Set.of("../invalid");
        assertThrows(IllegalArgumentException.class, () -> new ManagedHostedResources(Map.of("outbox-relay",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(1)))));
    }

    private static final class CapabilityFactory implements ManagedHostedResources.Factory {
        private Set<String> backends = Set.of();
        private Set<ManagedInvocationPolicy> guards = Set.of();
        @Override public Set<String> backendKinds() { return backends; }
        @Override public Set<ManagedInvocationPolicy> requiredPolicies() { return guards; }
        @Override public Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() { return Set.of(org.j7m.middleware.annotations.DurableQueueConsumer.class); }
        @Override public void validate(org.j7m.middleware.application.api.ManagedHostedFeature feature, org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) { }
        @Override public ManagedHostedResources.Binding bind(org.j7m.middleware.application.api.ManagedHostedFeature feature, org.j7m.middleware.application.api.ManagedComponentDefinition<?> definition, Object component, ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) { throw new AssertionError("No construction during registry validation"); }
    }
}
