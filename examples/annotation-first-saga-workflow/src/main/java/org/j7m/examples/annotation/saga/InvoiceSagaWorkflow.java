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

package org.j7m.examples.annotation.saga;

import org.j7m.middleware.annotations.Compensation;
import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.DeadLetterHandler;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.SagaDefinition;
import org.j7m.middleware.annotations.SagaStep;
import org.j7m.middleware.api.saga.SagaService;
import org.j7m.middleware.application.api.InvocationContext;

/**
 * Invoice saga workflow source used to demonstrate descriptor generation.
 *
 * <p>{@link SagaDefinition}, {@link SagaStep}, {@link Compensation}, and
 * {@link DeadLetterHandler} are descriptor metadata in this example. They show
 * the ordering and compensation intent that a runtime can validate, while
 * durable orchestration, retries, recovery, and dead-letter persistence remain
 * owned by the injected {@link SagaService}.</p>
 *
 * <p>The workflow deliberately avoids distributed ACID or generic exactly-once
 * claims. The current {@link InvocationContext} supplies a public correlation
 * id for evidence, and no internal saga engine state is exposed to application
 * code.</p>
 */
@SagaDefinition(name = "invoice-approval")
public final class InvoiceSagaWorkflow {
    // J7M teaching: saga descriptor metadata is visible without claiming
    // distributed ACID or generic exactly-once behavior.
    private final SagaService sagaService;
    private final InvocationContext context;

    /**
     * Creates the workflow from public saga and invocation capabilities.
     *
     * @param sagaService public saga service capability
     * @param context current invocation context used for correlation metadata
     */
    @Inject
    public InvoiceSagaWorkflow(
            @J7mResource("invoice-saga") final SagaService sagaService,
            @CurrentInvocation final InvocationContext context) {
        this.sagaService = sagaService;
        this.context = context;
    }

    /**
     * Descriptor step that reserves the invoice before charging.
     *
     * @param invoiceId public invoice identifier
     * @return safe reservation summary
     */
    @SagaStep(name = "reserve", order = 0)
    public String reserve(final String invoiceId) {
        // J7M teaching: step order is descriptor metadata; the example records
        // correlation evidence without pretending to run a distributed
        // transaction.
        return this.context.correlationId() + ":reserve:" + invoiceId;
    }

    /**
     * Descriptor step that represents charging after reservation.
     *
     * <p>The public {@link SagaService} reference proves injection, but this
     * example does not start durable orchestration. Real applications should
     * use the Saga API to execute and recover the workflow.</p>
     *
     * @param invoiceId public invoice identifier
     * @return safe charge summary
     */
    @SagaStep(name = "charge", order = 1)
    public String charge(final String invoiceId) {
        // J7M teaching: the injected SagaService is the public orchestration
        // capability, while this method remains descriptor-backed business
        // code for the example.
        return this.sagaService.getClass().getSimpleName() + ":charge:" + invoiceId;
    }

    /**
     * Compensation metadata for the reserve step.
     *
     * @param invoiceId public invoice identifier
     * @return safe compensation summary
     */
    @Compensation(step = "reserve")
    public String release(final String invoiceId) {
        return "release:" + invoiceId;
    }

    /**
     * Dead-letter metadata for unrecoverable workflow input.
     *
     * @param invoiceId public invoice identifier
     * @return safe dead-letter summary
     */
    @DeadLetterHandler
    public String deadLetter(final String invoiceId) {
        return "dead-letter:" + invoiceId;
    }
}
