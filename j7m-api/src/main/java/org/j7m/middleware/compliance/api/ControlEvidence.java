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

package org.j7m.middleware.compliance.api;

import java.time.Instant;
import java.util.Map;

/**
 * Evidence record collected for a control assessment.
 *
 * <p>Evidence is a factual observation. It is not a certification claim. The
 * record is immutable and safe to share between threads.</p>
 *
 * @param timestamp collection timestamp
 * @param runtimeId runtime id, when runtime-specific
 * @param environment environment label
 * @param controlId control id
 * @param sourceModule source module or component
 * @param collectionMethod collection method
 * @param result safe result text
 * @param limitation known limitation or gap
 * @param correlationId correlation id
 * @param signatureMetadata safe hash/signature metadata
 * @param attributes additional safe attributes
 */
public record ControlEvidence(
        Instant timestamp,
        String runtimeId,
        String environment,
        ControlId controlId,
        String sourceModule,
        String collectionMethod,
        String result,
        String limitation,
        String correlationId,
        Map<String, String> signatureMetadata,
        Map<String, String> attributes) {
    /**
     * Creates normalized immutable evidence.
     */
    public ControlEvidence {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        runtimeId = runtimeId == null ? "" : runtimeId;
        environment = environment == null ? "" : environment;
        controlId = controlId == null ? new ControlId("") : controlId;
        sourceModule = sourceModule == null ? "" : sourceModule;
        collectionMethod = collectionMethod == null ? "" : collectionMethod;
        result = result == null ? "" : result;
        limitation = limitation == null ? "" : limitation;
        correlationId = correlationId == null ? "" : correlationId;
        signatureMetadata = signatureMetadata == null ? Map.of() : Map.copyOf(signatureMetadata);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
