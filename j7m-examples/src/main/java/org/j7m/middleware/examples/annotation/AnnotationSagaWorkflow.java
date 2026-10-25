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

package org.j7m.middleware.examples.annotation;

import java.util.ArrayList;
import java.util.List;
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
 * Descriptor-backed saga workflow example.
 *
 * <p>The saga annotations are descriptor metadata in this Phase 6.11 slice.
 * Durable execution, retries, and recovery remain owned by {@link SagaService}.
 * The example manually executes the steps and compensation so readers can see
 * the intended order without claiming a new transaction coordinator.</p>
 */
@SagaDefinition(name = "annotation-invoice-saga")
public final class AnnotationSagaWorkflow {
        // J7M teaching: this block demonstrates saga descriptor metadata without claiming distributed ACID or generic exactly-once behavior.
    private final SagaService sagaService;
    private final InvocationContext invocationContext;
    private final AnnotationExampleAudit audit;

    /**
     * Creates a workflow with the public saga service capability.
     *
     * @param sagaService injected public saga service
     * @param invocationContext current invocation context
     * @param audit safe audit collector
     */
    @Inject
    public AnnotationSagaWorkflow(
            @J7mResource("annotation-saga") final SagaService sagaService,
            @CurrentInvocation final InvocationContext invocationContext,
            final AnnotationExampleAudit audit) {
        this.sagaService = sagaService;
        this.invocationContext = invocationContext;
        this.audit = audit;
    }

    /**
     * Reserves an invoice.
     *
     * @param invoiceId invoice id
     * @return step result
     */
    @SagaStep(name = "reserve", order = 0)
    public String reserve(final String invoiceId) {
        // J7M teaching: saga step annotations define descriptor order; this
        // support class records correlation evidence without claiming a
        // distributed transaction.
        this.audit.record("saga.reserve:" + invoiceId + ":corr=" + this.invocationContext.correlationId());
        return "reserved:" + invoiceId;
    }

    /**
     * Charges an invoice.
     *
     * @param invoiceId invoice id
     * @param fail whether to simulate failure
     * @return step result
     */
    @SagaStep(name = "charge", order = 1)
    public String charge(final String invoiceId, final boolean fail) {
        // J7M teaching: the failure path exists so demos can show compensation
        // intent and dead-letter evidence without hiding the fact that runtime
        // orchestration belongs to SagaService.
        if (fail) {
            this.audit.record("saga.charge.failed:" + invoiceId);
            throw new IllegalStateException("simulated charge failure");
        }
        this.audit.record("saga.charge:" + invoiceId);
        return "charged:" + invoiceId;
    }

    /**
     * Compensates the reserve step.
     *
     * @param invoiceId invoice id
     * @return compensation result
     */
    @Compensation(step = "reserve")
    public String releaseReserve(final String invoiceId) {
        this.audit.record("saga.compensate.reserve:" + invoiceId);
        return "released:" + invoiceId;
    }

    /**
     * Handles failed workflow data through a safe dead-letter summary.
     *
     * @param invoiceId invoice id
     * @return safe dead-letter text
     */
    @DeadLetterHandler
    public String deadLetter(final String invoiceId) {
        this.audit.record("saga.dead-letter:" + invoiceId);
        return "dead-letter:" + invoiceId;
    }

    /**
     * Runs a deterministic local scenario that demonstrates step and
     * compensation order.
     *
     * @param invoiceId invoice id
     * @param failCharge whether to fail charge
     * @return ordered events
     */
    public List<String> runScenario(final String invoiceId, final boolean failCharge) {
        List<String> events = new ArrayList<>();
        events.add(this.reserve(invoiceId));
        try {
            events.add(this.charge(invoiceId, failCharge));
        } catch (IllegalStateException exception) {
            events.add(this.releaseReserve(invoiceId));
            events.add(this.deadLetter(invoiceId));
        }
        events.add("saga-service=" + this.sagaService.getClass().getInterfaces()[0].getSimpleName());
        return List.copyOf(events);
    }
}
