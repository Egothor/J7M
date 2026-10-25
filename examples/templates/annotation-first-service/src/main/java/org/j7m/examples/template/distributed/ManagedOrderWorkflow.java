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

package org.j7m.examples.template.distributed;

import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.distributed.ManagedSagaDecision;
import org.j7m.middleware.application.distributed.ManagedSagaUncertainty;

/**
 * Ordinary typed steps with explicit uncertainty reporting.
 * Generated descriptor metadata is consumed by the host's explicit managed saga adapter.
 */
@SagaDefinition(name="orders")
public final class ManagedOrderWorkflow {
    /**
     * Constructs the workflow.
     */
    public ManagedOrderWorkflow() { }
    /**
     * Returns a durable typed result.
     * @param input original business data
     * @return completed result
     */
    @SagaStep(name="prepare",order=0,idempotent=true) public String prepare(String input) { return input; }
    /**
     * Leaves unknown external outcomes for an operator when no independent evidence exists.
     * @param uncertainty exact versioned uncertain effect
     * @return explicit unresolved decision
     */
    @ReconciliationHandler public ManagedSagaDecision establish(ManagedSagaUncertainty uncertainty) {
        // J7M teaching: preserve UNKNOWN when no independent evidence establishes the external effect.
        return new ManagedSagaDecision(ManagedSagaDecision.Outcome.UNRESOLVED,null);
    }
}
