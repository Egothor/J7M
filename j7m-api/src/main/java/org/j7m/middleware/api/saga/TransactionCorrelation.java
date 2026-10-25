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

package org.j7m.middleware.api.saga;

import java.io.Serializable;
import org.j7m.middleware.api.reliability.CausalContext;
import org.j7m.middleware.api.reliability.CorrelationContext;
import org.j7m.middleware.api.reliability.DurableOperationId;

/**
 * Correlation metadata for root and nested saga operations.
 *
 * <p>The record carries J7M reliability context across steps and
 * compensations. It is immutable, serializable, and safe for redacted status
 * views.
 *
 * @param correlationContext logical correlation identifier and nesting data
 * @param causalContext causal parent/child context
 * @param operationId durable operation identifier for this scope
 * @param parentOperationId parent operation identifier, or {@code null} for a root scope
 */
public record TransactionCorrelation(
        CorrelationContext correlationContext,
        CausalContext causalContext,
        DurableOperationId operationId,
        DurableOperationId parentOperationId) implements Serializable {
    /**
     * Creates a validated immutable correlation record.
     */
    public TransactionCorrelation {
        correlationContext = correlationContext == null ? CorrelationContext.root() : correlationContext;
        causalContext = causalContext == null ? CausalContext.root() : causalContext;
        operationId = operationId == null ? new DurableOperationId(java.util.UUID.randomUUID().toString()) : operationId;
    }

    /**
     * Creates a child correlation preserving the root correlation id.
     *
     * @return immutable child correlation with this operation as parent
     */
    public TransactionCorrelation child() {
        return new TransactionCorrelation(this.correlationContext.nestedInvocation(), this.causalContext.child(),
                new DurableOperationId(java.util.UUID.randomUUID().toString()), this.operationId);
    }
}
