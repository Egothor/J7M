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

package org.j7m.middleware.compliance.defaults;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.compliance.api.CmdbInventoryExporter;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ControlEvidence;
import org.j7m.middleware.compliance.api.EnterpriseIntegrationStatus;
import org.j7m.middleware.compliance.api.GrcEvidenceRepository;
import org.j7m.middleware.compliance.api.ObservabilityTelemetryExporter;
import org.j7m.middleware.compliance.api.SiemEvidenceExporter;
import org.j7m.middleware.compliance.api.SoarWebhookPublisher;
import org.j7m.middleware.compliance.api.VulnerabilityMetadataProvider;

/**
 * Non-networked enterprise integration fixtures.
 *
 * <p>These adapters are thread-safe and deterministic. They are useful for
 * tests, demos, and architecture handoff, but production deployments should
 * provide real GRC, SIEM, SOAR, CMDB, telemetry, and vulnerability adapters.</p>
 */
public final class NoopEnterpriseIntegrations {
    private NoopEnterpriseIntegrations() {
    }

    /**
     * Returns a no-op GRC repository.
     *
     * @return repository
     */
    public static GrcEvidenceRepository grcRepository() {
        return new NoopGrcEvidenceRepository();
    }

    /**
     * Returns a no-op SIEM exporter.
     *
     * @return exporter
     */
    public static SiemEvidenceExporter siemExporter() {
        return new NoopSiemEvidenceExporter();
    }

    /**
     * Returns a no-op SOAR publisher.
     *
     * @return publisher
     */
    public static SoarWebhookPublisher soarPublisher() {
        return new NoopSoarWebhookPublisher();
    }

    /**
     * Returns a no-op CMDB exporter.
     *
     * @return exporter
     */
    public static CmdbInventoryExporter cmdbExporter() {
        return new NoopCmdbInventoryExporter();
    }

    /**
     * Returns a no-op telemetry exporter.
     *
     * @return exporter
     */
    public static ObservabilityTelemetryExporter telemetryExporter() {
        return new NoopObservabilityTelemetryExporter();
    }

    /**
     * Returns a no-op vulnerability metadata provider.
     *
     * @return provider
     */
    public static VulnerabilityMetadataProvider vulnerabilityMetadataProvider() {
        return new NoopVulnerabilityMetadataProvider();
    }

    private static final class NoopGrcEvidenceRepository implements GrcEvidenceRepository {
        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("grc");
        }

        @Override
        public String store(final ComplianceReport report) {
            return "noop-grc:" + (report == null ? "missing" : report.profile().id());
        }
    }

    private static final class NoopSiemEvidenceExporter implements SiemEvidenceExporter {
        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("siem");
        }

        @Override
        public long export(final List<ControlEvidence> evidence) {
            return evidence == null ? 0L : evidence.size();
        }
    }

    private static final class NoopSoarWebhookPublisher implements SoarWebhookPublisher {
        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("soar");
        }

        @Override
        public String publish(final String eventType, final Map<String, String> payload) {
            return "noop-soar:" + (eventType == null ? "" : eventType);
        }
    }

    private static final class NoopCmdbInventoryExporter implements CmdbInventoryExporter {
        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("cmdb");
        }

        @Override
        public long export(final List<Map<String, String>> inventory) {
            return inventory == null ? 0L : inventory.size();
        }
    }

    private static final class NoopObservabilityTelemetryExporter implements ObservabilityTelemetryExporter {
        private final AtomicLong exported;

        private NoopObservabilityTelemetryExporter() {
            this.exported = new AtomicLong();
        }

        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("observability");
        }

        @Override
        public long export(final Map<String, Long> counters) {
            long count = counters == null ? 0L : counters.size();
            this.exported.addAndGet(count);
            return count;
        }
    }

    private static final class NoopVulnerabilityMetadataProvider implements VulnerabilityMetadataProvider {
        @Override
        public EnterpriseIntegrationStatus status() {
            return statusFor("vulnerability");
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of("sbom", "not-configured", "vex", "not-configured");
        }
    }

    private static EnterpriseIntegrationStatus statusFor(final String id) {
        return new EnterpriseIntegrationStatus(id, true, "noop", "Production integration must be supplied by deployment.");
    }
}
