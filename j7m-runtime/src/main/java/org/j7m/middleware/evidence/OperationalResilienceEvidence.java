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

package org.j7m.middleware.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Operational resilience evidence metadata.
 *
 * @param rto configured recovery time objective
 * @param rpo configured recovery point objective
 * @param haStatus HA/HP status text
 * @param discoveryStatus discovery status text
 * @param backupRestoreHookStatus backup/restore hook status
 * @param failoverDrillResult failover drill result
 * @param overloadControls overload control status
 * @param dependencyHealth dependency health text
 * @param recoveryProcedureReference recovery procedure reference
 * @param lastResilienceTest last resilience test timestamp
 * @param attributes additional safe attributes
 */
public record OperationalResilienceEvidence(
        Duration rto,
        Duration rpo,
        String haStatus,
        String discoveryStatus,
        String backupRestoreHookStatus,
        String failoverDrillResult,
        String overloadControls,
        String dependencyHealth,
        String recoveryProcedureReference,
        Instant lastResilienceTest,
        Map<String, String> attributes) {
    /**
     * Creates immutable values.
     */
    public OperationalResilienceEvidence {
        rto = rto == null ? Duration.ZERO : rto;
        rpo = rpo == null ? Duration.ZERO : rpo;
        haStatus = haStatus == null ? "" : haStatus;
        discoveryStatus = discoveryStatus == null ? "" : discoveryStatus;
        backupRestoreHookStatus = backupRestoreHookStatus == null ? "" : backupRestoreHookStatus;
        failoverDrillResult = failoverDrillResult == null ? "" : failoverDrillResult;
        overloadControls = overloadControls == null ? "" : overloadControls;
        dependencyHealth = dependencyHealth == null ? "" : dependencyHealth;
        recoveryProcedureReference = recoveryProcedureReference == null ? "" : recoveryProcedureReference;
        lastResilienceTest = lastResilienceTest == null ? Instant.EPOCH : lastResilienceTest;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
