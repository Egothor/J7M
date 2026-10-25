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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.j7m.middleware.annotations.HealthCheck;
import org.j7m.middleware.annotations.ReadinessCheck;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.application.api.ManagedHostAdapter;
import org.j7m.middleware.application.api.ManagedProbe;
import org.j7m.middleware.application.api.ManagedProbeKind;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedResolver;
import org.junit.jupiter.api.Test;

/**
 * Deployment independently validates finite metadata even for proc:none or forged indexes.
 */
class ManagedProbeContractsTest {
    public interface Parent {
        @HealthCheck boolean healthy();
    }
    public static class Service implements Parent {
        @Override public boolean healthy() { return true; }
        @ReadinessCheck public boolean ready() { return false; }
    }
    public static class Invalid {
        @HealthCheck public String healthy() { return "UP"; }
    }

    @Test
    void inheritedProbeMetadataMustExactlyMatchActualPublicMethodsAndScope() throws Exception {
        var health = new ManagedProbe(ManagedProbeKind.HEALTH, ManagedRuntimeContracts.identity(Service.class.getMethod("healthy")));
        var ready = new ManagedProbe(ManagedProbeKind.READINESS, ManagedRuntimeContracts.identity(Service.class.getMethod("ready")));
        var adapter = new Definition<>(Service.class, List.of(health, ready), Scope.Kind.ACTIVATION);
        assertEquals(List.of(health, ready), ManagedProbeContracts.validate(adapter));
        assertThrows(IllegalArgumentException.class, () -> ManagedProbeContracts.validate(new Definition<>(Service.class, List.of(ready), Scope.Kind.ACTIVATION)));
        assertThrows(IllegalArgumentException.class, () -> ManagedProbeContracts.validate(new Definition<>(Service.class, List.of(health, ready, ready), Scope.Kind.ACTIVATION)));
        assertThrows(IllegalArgumentException.class, () -> ManagedProbeContracts.validate(new Definition<>(Service.class, List.of(health, ready), Scope.Kind.CALL)));
        assertFalse(adapter.remoteMethods().contains(ready.method()), "host probes must not grant remote export");
    }

    @Test
    void runtimeRejectsLegacyProbeShapesInManagedProfileBeforeFactoryExecution() {
        assertThrows(IllegalArgumentException.class, () -> ManagedProbeContracts.validate(new Definition<>(Invalid.class, List.of(), Scope.Kind.ACTIVATION)));
    }

    @Test
    void publicProbeDescriptorsRejectNonBooleanOrUnboundedCallbackIdentities() throws Exception {
        var method = ManagedRuntimeContracts.identity(Service.class.getMethod("healthy"));
        var probe = new org.j7m.middleware.application.api.ManagedApplicationProbe("finite-health", ManagedProbeKind.HEALTH, () -> true);
        assertEquals("finite-health", probe.id());
        assertEquals(ManagedProbeKind.HEALTH, probe.kind());
        assertEquals(Boolean.TRUE, probe.check().call());
        assertThrows(IllegalArgumentException.class, () -> new org.j7m.middleware.application.api.ManagedApplicationProbe("x".repeat(129), ManagedProbeKind.HEALTH, () -> true));
        assertThrows(IllegalArgumentException.class, () -> new org.j7m.middleware.application.api.ManagedApplicationProbe("line\nnext", ManagedProbeKind.HEALTH, () -> true));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbe(ManagedProbeKind.READINESS,
                new org.j7m.middleware.application.api.ManagedMethod(method.declaringType(), "bad", List.of("int"), "boolean")));
        assertThrows(IllegalArgumentException.class, () -> new ManagedProbe(ManagedProbeKind.READINESS,
                new org.j7m.middleware.application.api.ManagedMethod(method.declaringType(), "bad", List.of(), "java.lang.Boolean")));
    }

    private record Definition<T>(Class<T> type, List<ManagedProbe> probes, Scope.Kind scope) implements ManagedHostAdapter<T> {
        @Override public String name() { return ""; }
        @Override public List<ManagedQualifier> qualifiers() { return List.of(); }
        @Override public List<ManagedDependency> dependencies() { return List.of(); }
        @Override public T create(final ManagedResolver resolver) { throw new AssertionError("validation must precede construction"); }
    }
}
