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

package com.example.j7m.annotationfirst;

import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.DurableQueueConsumer;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.SagaDefinition;
import org.j7m.middleware.annotations.SagaStep;
import org.j7m.middleware.api.messaging.DurableQueue;
import org.j7m.middleware.api.saga.SagaService;

/**
 * Billing service template showing annotation-first service metadata and
 * constructor injection.
 *
 * <p>{@link J7mService} declares the service name and export intent.
 * {@link RequiresRole} and {@link RequiresPermission} contribute descriptor
 * metadata for the authorization policy; they are not a standalone security
 * engine. {@link SagaDefinition}, {@link SagaStep}, and
 * {@link DurableQueueConsumer} are descriptor metadata in this template.
 * Durable saga orchestration and queue delivery remain owned by the injected
 * public {@link SagaService} and {@link DurableQueue} APIs.</p>
 *
 * <p>The template removes boilerplate around service identity, dependency
 * lookup, and descriptor metadata. It does not claim generic exactly-once
 * processing, distributed ACID transactions, or automatic external broker
 * integration.</p>
 */
@J7mService(name = "//apps/example/billing/BillingService", exported = true, transport = "default")
@SagaDefinition(name = "billing-approval")
@RequiresRole("billing-user")
public final class BillingService {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final DurableQueue queue;
    private final SagaService sagas;
    private final String region;

    /**
     * Creates the billing service from public J7M capabilities.
     *
     * <p>{@link J7mResource} selects the durable queue binding and
     * {@link ConfigValue} supplies a public region value with a default.
     * Application code should copy this constructor-injection shape instead of
     * reaching into a runtime registry.</p>
     *
     * @param queue public durable queue capability
     * @param sagas public saga service capability
     * @param region configured public region label
     */
    @Inject
    public BillingService(
            @J7mResource("billing-events") final DurableQueue queue,
            final SagaService sagas,
            @ConfigValue(value = "billing.region", defaultValue = "local") final String region) {
        this.queue = queue;
        this.sagas = sagas;
        this.region = region;
    }

    /**
     * Approves an invoice as the primary remote service method.
     *
     * <p>{@link RemoteMethod} marks the service contract and
     * {@link Idempotent} contributes retry/dedupe metadata. The permission and
     * saga-step annotations are descriptor metadata until connected to the J7M
     * authorization and saga services by the application platform.</p>
     *
     * @param id public invoice identifier
     * @return safe approval result
     */
    @RemoteMethod
    @Idempotent
    @RequiresPermission(value = "approve", resource = "//apps/example/billing")
    @SagaStep(name = "approve-invoice", order = 0)
    public ApprovalResult approve(final InvoiceId id) {
        // J7M teaching: this is the business line a developer should copy;
        // service export, permission metadata, and saga-step metadata are
        // declared around it instead of wired by hand.
        return new ApprovalResult(id.value(), "accepted-" + this.region);
    }

    /**
     * Shows where a queue consumer method would live in a template service.
     *
     * <p>{@link DurableQueueConsumer} is descriptor metadata here. A production
     * queue dispatcher must connect this method to durable queue leases,
     * acknowledgements, retries, and dead-letter handling before delivery
     * semantics are enforced.</p>
     *
     * @param eventId public event identifier
     */
    @DurableQueueConsumer(queue = "billing-events")
    public void consumeBillingEvent(final String eventId) {
        // J7M teaching: queue-consumer annotations are descriptor metadata in
        // the template; real delivery still belongs to DurableQueue leases and
        // acknowledgement handling.
        this.queue.getClass();
        this.sagas.status();
        eventId.length();
    }

    /**
     * Performs local warmup for the startup hook.
     *
     * <p>The method is package-private because application developers should
     * normally interact through annotated service methods, not lifecycle
     * internals.</p>
     */
    void warmup() {
        this.queue.getClass();
    }
}
