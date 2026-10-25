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

package org.j7m.middleware.demo.adapter;

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
import org.j7m.middleware.adapter.spi.AdapterTckReport;
import org.j7m.middleware.adapter.spi.AdapterTckResult;
import org.j7m.middleware.adapter.spi.AdapterTckScenario;
import org.j7m.middleware.adapter.tck.AdapterTckProfiles;
import org.j7m.middleware.adapter.tck.AdapterTckRunner;
import org.j7m.middleware.adapter.tck.AdapterTckVersion;

/**
 * Phase 6.9 adapter TCK demonstration.
 *
 * <p>The demo runs the TCK against in-demo fake providers only. It does not
 * create a production adapter and does not require any external broker,
 * database, cloud service, workflow engine, transaction manager, or container
 * runtime.</p>
 */
public final class AdapterTckDemo {
        // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private AdapterTckDemo() {
    }

    /**
     * Runs the adapter TCK demonstration.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        // J7M teaching: the demo uses local fake providers only, which lets
        // maintainers compare pass, limitation, and rejection reports without
        // adding a production vendor adapter or external service.
        final AdapterTckRunner runner = new AdapterTckRunner();
        final DemoAdapter pass = DemoAdapter.pass(AdapterSemanticProfile.durableLog());
        final DemoAdapter partial = DemoAdapter.unsupported(AdapterSemanticProfile.coordination(), AdapterCapability.WATCH_NOTIFY);
        final DemoAdapter failing = DemoAdapter.failing(AdapterSemanticProfile.repositoryReplication(), AdapterCapability.EXACT_BYTE_PRESERVATION);

        print("pass", runner.run(pass, pass, AdapterTckProfiles.durableLog()));
        print("partial", runner.run(partial, partial, AdapterTckProfiles.coordination()));
        print("failing", runner.run(failing, failing, AdapterTckProfiles.repositoryReplication()));
        System.out.println("adapter-tck demo complete: fake fixtures only, external services required=false, output redacted=true");
    }

    private static void print(final String label, final AdapterTckReport report) {
        System.out.println(label
                + " adapter="
                + report.summary().adapterId().value()
                + " profile="
                + report.summary().semanticProfile().name()
                + " level="
                + report.summary().compatibilityLevel()
                + " passed="
                + report.summary().passed()
                + " failed="
                + report.summary().failed()
                + " unsupported="
                + report.summary().unsupported());
        System.out.println(label
                + " semanticMatrix="
                + "required:" + report.summary().semanticProfile().requiredCapabilities().size()
                + ",optional:" + report.summary().semanticProfile().optionalCapabilities().size()
                + " profile=" + report.summary().semanticProfile().name());
        System.out.println(label + " compatibilityReport=" + report.summary().compatibilityLevel());
        System.out.println(label + " securityFindings=" + report.summary().securityFindings());
        System.out.println(label + " failureModeFindings=" + report.summary().failureModeFindings());
        System.out.println(label + " redactionFindings=" + report.summary().redactionFindings());
        if (!report.summary().limitations().isEmpty()) {
            System.out.println(label + " limitations=" + report.summary().limitations());
        }
    }

    private static final class DemoAdapter implements AdapterProvider, AdapterSemanticProbe {
        private final AdapterSemanticProfile profile;
        private final Set<AdapterCapability> unsupported;
        private final Set<AdapterCapability> failing;

        private DemoAdapter(
                final AdapterSemanticProfile profile,
                final Set<AdapterCapability> unsupported,
                final Set<AdapterCapability> failing) {
            this.profile = profile;
            this.unsupported = Set.copyOf(unsupported);
            this.failing = Set.copyOf(failing);
        }

        static DemoAdapter pass(final AdapterSemanticProfile profile) {
            return new DemoAdapter(profile, Set.of(), Set.of());
        }

        static DemoAdapter unsupported(final AdapterSemanticProfile profile, final AdapterCapability capability) {
            return new DemoAdapter(profile, Set.of(capability), Set.of());
        }

        static DemoAdapter failing(final AdapterSemanticProfile profile, final AdapterCapability capability) {
            return new DemoAdapter(profile, Set.of(), Set.of(capability));
        }

        @Override
        public AdapterProviderId id() {
            return new AdapterProviderId("demo." + this.profile.name());
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
            return new AdapterCapabilityDescriptor(
                    id(),
                    "demo-fixture",
                    this.profile.type(),
                    Set.of(this.profile),
                    capabilities,
                    this.unsupported,
                    AdapterSecurityProfile.strict(),
                    new AdapterResourceProfile(Map.of("scope", "demo-only")),
                    EnumSet.allOf(AdapterFailureMode.class),
                    this.unsupported.stream().map(capability -> "unsupported " + capability.name()).toList(),
                    AdapterTckVersion.CURRENT,
                    AdapterCompatibilityLevel.NATIVE_EQUIVALENT);
        }

        @Override
        public AdapterHealth health() {
            return new AdapterHealth(id(), true, AdapterLifecycle.RUNNING, "OK", Map.of());
        }

        @Override
        public AdapterStatus status() {
            return new AdapterStatus(id(), AdapterLifecycle.RUNNING, AdapterCompatibilityLevel.NATIVE_EQUIVALENT, Map.of());
        }

        @Override
        public AdapterMetricsSnapshot metrics() {
            return new AdapterMetricsSnapshot(0, 0, 0, 0, 0);
        }

        @Override
        public List<AdapterAuditEvent> auditEvents() {
            return List.of();
        }

        @Override
        public void close() {
        }

        @Override
        public AdapterTckResult runScenario(final AdapterTckScenario scenario) {
            if (this.unsupported.contains(scenario.capability())) {
                return AdapterTckResult.unsupported(scenario, "UNSUPPORTED_BY_DEMO");
            }
            if (this.failing.contains(scenario.capability())) {
                return AdapterTckResult.failed(scenario, "DEMO_FAILURE");
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
            return false;
        }

    }
}
