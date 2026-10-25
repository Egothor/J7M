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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.saga.SagaDefinition;
import org.j7m.middleware.api.saga.SagaDefinitionId;
import org.j7m.middleware.api.saga.SagaExecution;
import org.j7m.middleware.api.saga.SagaExecutionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SagaCompensationFailureDeadLetterTest {
    @Test
    void failedCompensationDeadLettersSafely(@TempDir final Path directory) {
        MutableSagaClock clock = SagaTestSupport.clock();
        SagaDefinitionId id = new SagaDefinitionId("dead-letter");
        SagaDefinition definition = new SagaDefinition(id, List.of(
                SagaTestSupport.compensationDeadLetterStep("reserve"),
                SagaTestSupport.failingStep("charge", false)));

        try (LogBackedSagaService service = SagaTestSupport.service(directory, clock)) {
            service.register(definition);
            SagaExecution execution = service.runUntilIdle(service.start(SagaTestSupport.request(id)).executionId());

            assertEquals(SagaExecutionState.DEAD_LETTERED, execution.state());
            assertFalse(service.status().deadLetters().isEmpty());
            assertEquals("COMPENSATION_BROKEN", service.status().deadLetters().getFirst().reasonCode());
        }
    }
}
