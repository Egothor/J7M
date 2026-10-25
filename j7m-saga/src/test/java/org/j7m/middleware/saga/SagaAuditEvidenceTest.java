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

package org.j7m.middleware.saga;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.j7m.middleware.api.saga.SagaStartRequest;
import org.j7m.middleware.api.saga.TransactionContext;
import org.j7m.middleware.api.saga.TransactionParticipant;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaAuditEvidenceTest {
    @Test
    void auditEvidenceHasDecisionReasonAndRedactsPayload(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("audit");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());
            String status = service.status().toString();

            assertTrue(service.status().evidence().stream().allMatch(event -> !event.decision().isBlank() && !event.reasonCode().isBlank()));
            assertFalse(status.contains(new String(SagaTestSupport.SECRET_PAYLOAD, StandardCharsets.UTF_8)));
            assertTrue(status.contains("payloadSize"));
        }
    }

    @Test
    void statusEvidenceAndRecoveryRedactCredentialLikeMetadata(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("redaction");
        TransactionContext context = new TransactionContext(null, null, null, null, null, null, null, false,
                new TransactionParticipant("operator", Map.of("authorization", "Bearer top-secret-token")),
                Map.of("password", "p@ssw0rd-value", "api_key", "api-key-value", "tenant", "tenant-a"));
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            service.runUntilIdle(service.start(new SagaStartRequest(id, null, context, SagaTestSupport.SECRET_PAYLOAD)).executionId());
            String publicText = service.status().toString();

            assertFalse(publicText.contains("p@ssw0rd-value"));
            assertFalse(publicText.contains("api-key-value"));
            assertFalse(publicText.contains("top-secret-token"));
            assertFalse(publicText.contains(new String(SagaTestSupport.SECRET_PAYLOAD, StandardCharsets.UTF_8)));
            assertTrue(publicText.contains("tenant-a"));
            assertTrue(service.status().executions().getFirst().transactionContext().metadata().containsValue("[REDACTED]"));
        }
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            String publicText = recovered.status().toString();
            assertFalse(publicText.contains("p@ssw0rd-value"));
            assertFalse(publicText.contains("api-key-value"));
            assertFalse(publicText.contains("top-secret-token"));
        }
    }
}
