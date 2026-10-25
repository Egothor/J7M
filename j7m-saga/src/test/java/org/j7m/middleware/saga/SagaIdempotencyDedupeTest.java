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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaStartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaIdempotencyDedupeTest {
    @Test
    void duplicateStartReturnsPreviousExecutionAndSurvivesRecovery(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("idem");
        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            SagaStartResult first = service.start(SagaTestSupport.idempotentRequest(id, "order-42"));
            SagaStartResult duplicate = service.start(SagaTestSupport.idempotentRequest(id, "order-42"));
            assertEquals(first.executionId(), duplicate.executionId());
            assertTrue(duplicate.duplicate());
        }
        try (LogBackedSagaService recovered = SagaTestSupport.service(directory, clock)) {
            recovered.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            SagaStartResult duplicate = recovered.start(SagaTestSupport.idempotentRequest(id, "order-42"));
            assertTrue(duplicate.duplicate());
        }
    }

    @Test
    void duplicateStartReturnsPreviousExecutionAtActiveLimit(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("idem-active-limit");
        SagaConfig config = new SagaConfig(directory.resolve("events"), directory.resolve("messaging"), clock,
                1, 16, 3, 64, 1024, 64, 16);
        try (LogBackedSagaService service = new LogBackedSagaService(config)) {
            service.register(SagaTestSupport.twoStepDefinition(id, SagaTestSupport.newOrder(), SagaTestSupport.newOrder()));
            SagaStartResult first = service.start(SagaTestSupport.idempotentRequest(id, "order-at-limit"));

            SagaStartResult duplicate = service.start(SagaTestSupport.idempotentRequest(id, "order-at-limit"));

            assertEquals(first.executionId(), duplicate.executionId());
            assertTrue(duplicate.duplicate());
            assertEquals(1L, service.status().metrics().activeSagas());
        }
    }
}
