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

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.SecurityContext;
import org.junit.jupiter.api.Test;

class ManagedPolicyExposureTest {
    interface Api { @RemoteMethod String call(@Sensitive String input); }
    static class Allowed implements Api { public String call(String input) { return input; } }
    static class PrivateHelper extends Allowed { @RequiresRole("admin") private void local() { } @Override public String call(String input) { local(); return input; } }
    static class PublicHelper extends Allowed { @RequiresPermission("admin") public void local() { } }
    static class FactoryHelper extends Allowed { @Produces @Audit public Object product() { return new Object(); } }
    static class ProbeHelper extends Allowed { @HealthCheck @RequiresRole("admin") public boolean probe() { return true; } }
    static class UnsupportedField extends Allowed { @Sensitive public String secret; }
    static class UnexportedOverload extends Allowed { @RequiresRole("admin") public String call(int input) { return Integer.toString(input); } }
    static class InheritedBad extends PrivateHelper { }
    static class ResourceHelper extends Allowed { @ResourceRequirement("shared") public void local() { } }
    @IsolationProfile("host") static class HeapOnly { }

    interface GenericApi<T> { @RemoteMethod T convert(T value); }
    static class GenericAllowed implements GenericApi<String> {
        @RequiresRole("admin") public String convert(@Sensitive String value) { return value; }
    }
    static class GenericAmbiguous implements GenericApi<String> {
        @RequiresRole("admin") public String convert(@Sensitive String value) { return value; }
        @RequiresRole("other") public Integer convert(Integer value) { return value; }
    }

    private static ManagedServiceContract contract() {
        return new ManagedServiceContract("api", 1, new ManagedMethod(Api.class.getName(), "call", List.of(String.class.getName()), String.class.getName()));
    }
    private static ManagedSecurityPolicy policy() {
        return new ManagedSecurityPolicy(new AuthorizationPolicy() {
            public String id() { return "allow"; }
            public AuthorizationDecision evaluate(SecurityContext context, PermissionRequest request) { return AuthorizationDecision.allow(id()); }
        }, new org.j7m.middleware.security.defaults.InMemoryAuditSink(32), Clock.systemUTC());
    }

    @Test void actualExposureAllowsAnnotatedWireParametersButRejectsEveryUninterceptedPolicySite() {
        var policy = policy(); Map<ManagedServiceContract, Class<?>> exports = Map.of(contract(), Api.class);
        policy.validateExposure(Allowed.class, exports);
        for (Class<?> component : List.of(PrivateHelper.class, PublicHelper.class, FactoryHelper.class, ProbeHelper.class,
                UnsupportedField.class, UnexportedOverload.class, InheritedBad.class)) {
            assertThrows(IllegalArgumentException.class, () -> policy.validateExposure(component, exports), component.getName());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.validateExposure(Allowed.class, Map.of()), "annotated interface without actual export");
    }

    @Test void resourceLocalMethodRejectsWhileVerifiedNonentryHeapInvariantRemainsSupported() {
        try (var policy = new ManagedResourcePolicy(Map.of("shared", new ManagedResourcePolicy.ResourceProfile(1, 0,
                java.time.Duration.ZERO, java.time.Duration.ofMillis(1), 1)), Map.of("host", new ManagedResourcePolicy.HostHeapProfile(Runtime.getRuntime().maxMemory())))) {
            policy.validateComponent(HeapOnly.class, false); policy.validateExposure(HeapOnly.class, Map.of());
            assertThrows(IllegalArgumentException.class, () -> policy.validateExposure(ResourceHelper.class, Map.of(contract(), Api.class)));
        }
    }

    @Test void genericBridgeCoversOnlyUnambiguousBodyWhosePoliciesAreCopiedOntoActualEntry() {
        var generic = new ManagedServiceContract("generic", 1, new ManagedMethod(GenericApi.class.getName(), "convert", List.of(Object.class.getName()), Object.class.getName()));
        policy().validateExposure(GenericAllowed.class, Map.of(generic, GenericApi.class));
        assertThrows(IllegalArgumentException.class, () -> policy().validateExposure(GenericAmbiguous.class, Map.of(generic, GenericApi.class)));
    }

    @Test void rejectsUnrelatedOrForgedExportContractWithoutLoadingWireNames() {
        assertThrows(IllegalArgumentException.class, () -> ManagedMethodSurface.validateExposure(Allowed.class,
                Map.of(contract(), String.class), Set.of(Sensitive.class)));
        var forged = new ManagedServiceContract("api", 1, new ManagedMethod("missing.SecretClass", "call", List.of(), "void"));
        assertThrows(IllegalArgumentException.class, () -> ManagedMethodSurface.validateExposure(Allowed.class,
                Map.of(forged, Api.class), Set.of(Sensitive.class)));
    }
}
