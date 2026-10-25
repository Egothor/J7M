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

package org.j7m.middleware.examples.banking;

import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.J7mContext;

/**
 * Demonstration-only approval service.
 *
 * <p>This class intentionally contains no real banking logic. It only shows
 * caller identity, annotation authorization, audit-safe logging, and sensitive
 * value redaction patterns.</p>
 */
public final class BankingApprovalServiceImpl implements BankingApprovalService {
    private final List<String> auditLog;

    /**
     * Creates a service with an empty safe audit log.
     */
    public BankingApprovalServiceImpl() {
        this.auditLog = new ArrayList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApprovalResult approvePayment(final PaymentId id, final Money amount) {
        InvocationContext current = J7mContext.currentInvocation();
        String principal = current.security().principal().id();
        this.auditLog.add("approvePayment principal=" + principal + " id=" + id.value() + " amount=<redacted>");
        return new ApprovalResult(true, principal, current.correlationId());
    }

    /**
     * Returns safe audit entries. Sensitive amount values are never included.
     *
     * @return immutable audit entries
     */
    @Override
    public synchronized List<String> auditLog() {
        return List.copyOf(this.auditLog);
    }
}
