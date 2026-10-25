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

package org.j7m.middleware.adapter.tck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.j7m.middleware.adapter.spi.AdapterAuditEvent;
import org.j7m.middleware.adapter.spi.AdapterCapability;
import org.j7m.middleware.adapter.spi.AdapterCapabilityDescriptor;
import org.j7m.middleware.adapter.spi.AdapterCapabilityLevel;
import org.j7m.middleware.adapter.spi.AdapterCompatibilityLevel;
import org.j7m.middleware.adapter.spi.AdapterFailureMode;
import org.j7m.middleware.adapter.spi.AdapterFailureModeResult;
import org.j7m.middleware.adapter.spi.AdapterHealth;
import org.j7m.middleware.adapter.spi.AdapterLifecycle;
import org.j7m.middleware.adapter.spi.AdapterMetricsSnapshot;
import org.j7m.middleware.adapter.spi.AdapterProvider;
import org.j7m.middleware.adapter.spi.AdapterProviderId;
import org.j7m.middleware.adapter.spi.AdapterReferenceOperation;
import org.j7m.middleware.adapter.spi.AdapterReferenceOutcome;
import org.j7m.middleware.adapter.spi.AdapterReferenceSession;
import org.j7m.middleware.adapter.spi.AdapterResourceProfile;
import org.j7m.middleware.adapter.spi.AdapterSecurityProfile;
import org.j7m.middleware.adapter.spi.AdapterSemanticProbe;
import org.j7m.middleware.adapter.spi.AdapterSemanticProfile;
import org.j7m.middleware.adapter.spi.AdapterStatus;
import org.j7m.middleware.adapter.spi.AdapterTckResult;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;

final class FakeAdapters {
    private FakeAdapters() {
    }

    static FakeAdapter pass(final AdapterSemanticProfile profile) {
        return new FakeAdapter(profile, Set.of(), false, AdapterSecurityProfile.strict(), Set.of());
    }

    static FakeAdapter unsupported(final AdapterSemanticProfile profile, final AdapterCapability capability) {
        return new FakeAdapter(profile, Set.of(capability), false, AdapterSecurityProfile.strict(), Set.of());
    }

    static FakeAdapter failing(final AdapterSemanticProfile profile, final AdapterCapability capability) {
        return new FakeAdapter(profile, Set.of(), false, AdapterSecurityProfile.strict(), Set.of(capability));
    }

    static FakeAdapter redactionFail(final AdapterSemanticProfile profile) {
        return new FakeAdapter(profile, Set.of(), true, AdapterSecurityProfile.strict(), Set.of());
    }

    static FakeAdapter securityFail(final AdapterSemanticProfile profile) {
        return new FakeAdapter(
                profile,
                Set.of(),
                false,
                new AdapterSecurityProfile(false, false, false, false, false, List.of()),
                Set.of());
    }

    static FakeAdapter leakingScenarioMetadata(final AdapterSemanticProfile profile, final String key, final String value) {
        return new FakeAdapter(profile, Set.of(), false, AdapterSecurityProfile.strict(), Set.of(), key, value);
    }

    static FakeAdapter leakingProviderOutput(final AdapterSemanticProfile profile, final String value) {
        return new FakeAdapter(profile, Set.of(), false, AdapterSecurityProfile.strict(), Set.of(), "", "", value);
    }

    static final class FakeAdapter implements AdapterProvider, AdapterSemanticProbe {
        private final AdapterSemanticProfile profile;
        private final Set<AdapterCapability> unsupported;
        private final boolean leak;
        private final AdapterSecurityProfile securityProfile;
        private final Set<AdapterCapability> failing;
        private final String leakMetadataKey;
        private final String leakMetadataValue;
        private final String providerLeakValue;
        private boolean closed;

        FakeAdapter(
                final AdapterSemanticProfile profile,
                final Set<AdapterCapability> unsupported,
                final boolean leak,
                final AdapterSecurityProfile securityProfile,
                final Set<AdapterCapability> failing) {
            this(profile, unsupported, leak, securityProfile, failing, "", "");
        }

        FakeAdapter(
                final AdapterSemanticProfile profile,
                final Set<AdapterCapability> unsupported,
                final boolean leak,
                final AdapterSecurityProfile securityProfile,
                final Set<AdapterCapability> failing,
                final String leakMetadataKey,
                final String leakMetadataValue) {
            this(profile, unsupported, leak, securityProfile, failing, leakMetadataKey, leakMetadataValue, "");
        }

        FakeAdapter(
                final AdapterSemanticProfile profile,
                final Set<AdapterCapability> unsupported,
                final boolean leak,
                final AdapterSecurityProfile securityProfile,
                final Set<AdapterCapability> failing,
                final String leakMetadataKey,
                final String leakMetadataValue,
                final String providerLeakValue) {
            this.profile = profile;
            this.unsupported = Set.copyOf(unsupported);
            this.leak = leak;
            this.securityProfile = securityProfile;
            this.failing = Set.copyOf(failing);
            this.leakMetadataKey = leakMetadataKey;
            this.leakMetadataValue = leakMetadataValue;
            this.providerLeakValue = providerLeakValue;
        }

        @Override
        public AdapterProviderId id() {
            return new AdapterProviderId("fake." + this.profile.name());
        }

        @Override
        public AdapterCapabilityDescriptor descriptor() {
            final Map<AdapterCapability, AdapterCapabilityLevel> capabilities = new EnumMap<>(AdapterCapability.class);
            for (AdapterCapability capability : this.profile.requiredCapabilities()) {
                capabilities.put(capability, this.unsupported.contains(capability)
                        ? AdapterCapabilityLevel.UNSUPPORTED
                        : AdapterCapabilityLevel.REQUIRED);
            }
            for (AdapterCapability capability : this.profile.optionalCapabilities()) {
                capabilities.put(capability, this.unsupported.contains(capability)
                        ? AdapterCapabilityLevel.UNSUPPORTED
                        : AdapterCapabilityLevel.OPTIONAL);
            }
            final Set<AdapterFailureMode> modes = EnumSet.allOf(AdapterFailureMode.class);
            return new AdapterCapabilityDescriptor(
                    id(),
                    "test-fixture",
                    this.profile.type(),
                    Set.of(this.profile),
                    capabilities,
                    this.unsupported,
                    this.securityProfile,
                    new AdapterResourceProfile(Map.of("fixture", "test-only")),
                    modes,
                    this.unsupported.stream().map(capability -> "unsupported " + capability.name()).toList(),
                    AdapterTckVersion.CURRENT,
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT);
        }

        @Override
        public AdapterHealth health() {
            return new AdapterHealth(
                    id(),
                    !this.closed,
                    this.closed ? AdapterLifecycle.CLOSED : AdapterLifecycle.RUNNING,
                    "OK",
                    providerMetadata("health"));
        }

        @Override
        public AdapterStatus status() {
            return new AdapterStatus(
                    id(),
                    this.closed ? AdapterLifecycle.CLOSED : AdapterLifecycle.RUNNING,
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    providerMetadata("status"));
        }

        @Override
        public AdapterMetricsSnapshot metrics() {
            return new AdapterMetricsSnapshot(0, 0, 0, 0, 0);
        }

        @Override
        public List<AdapterAuditEvent> auditEvents() {
            if (this.providerLeakValue.isBlank()) {
                return List.of();
            }
            return List.of(new AdapterAuditEvent(
                    Instant.EPOCH,
                    id(),
                    "PROVIDER_OUTPUT",
                    "OBSERVED",
                    "OK",
                    Map.of("message", this.providerLeakValue)));
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            if (this.unsupported.contains(scenario.capability())) {
                return AdapterTckResult.unsupported(scenario, "UNSUPPORTED_BY_FIXTURE");
            }
            if (this.failing.contains(scenario.capability())) {
                return AdapterTckResult.failed(scenario, "FIXTURE_FAILURE");
            }
            if (!this.leakMetadataKey.isBlank()) {
                return new AdapterTckResult(
                        scenario.id(),
                        true,
                        false,
                        "PASSED",
                        AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                        Map.of(this.leakMetadataKey, this.leakMetadataValue));
            }
            return new AdapterTckResult(
                    scenario.id(),
                    true,
                    false,
                    "PASSED",
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT,
                    Map.of("observable", "scenario-completed"));
        }

        @Override
        public List<AdapterReferenceOutcome> runReferenceOperations(
                final AdapterTckScenario scenario,
                final List<AdapterReferenceOperation> operations,
                final AdapterReferenceSession session) {
            final List<AdapterReferenceOutcome> outcomes = new ArrayList<>(operations.size());
            for (AdapterReferenceOperation operation : operations) {
                outcomes.add(session.recordEffect(operation, AdapterTckRunner.referenceObservedValueFor(operation)));
            }
            return List.copyOf(outcomes);
        }

        @Override
        public AdapterFailureModeResult runFailureMode(final AdapterFailureMode failureMode) {
            return new AdapterFailureModeResult(failureMode, true, "HANDLED", Map.of("mode", "handled"));
        }

        @Override
        public boolean leaksSensitiveData(final String sentinel) {
            return this.leak;
        }

        List<String> publicStrings() {
            final List<String> strings = new ArrayList<>();
            strings.add(status().toString());
            strings.add(descriptor().toString());
            return strings;
        }

        private Map<String, String> providerMetadata(final String area) {
            if (this.providerLeakValue.isBlank()) {
                return Map.of("state", area);
            }
            return Map.of("note", this.providerLeakValue);
        }
    }
}
