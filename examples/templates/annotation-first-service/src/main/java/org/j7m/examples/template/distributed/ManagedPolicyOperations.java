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

/**
 * Explicit policy selection on ordinary business methods; no self-call interception is implied.
 */
@J7mComponent
public final class ManagedPolicyOperations {
    /**
     * Constructs a stateless business component.
     */
    public ManagedPolicyOperations() { }
    /**
     * Returns ordinary application data under a host's durable replay policy.
     * @param identity stable logical operation identity
     * @param value business data
     * @return unchanged data
     */
    @Deduplicated
    @Observed
    public String remember(@IdempotencyKey String identity,String value) {
        // J7M teaching: preserve the logical identity across retries; the host supplies replay protection.
        return value;
    }
    /**
     * Declares bounded dependency availability policy.
     * @param value business input
     * @return unchanged data
     */
    @CircuitBreaker
    @Bulkhead
    public String dependency(String value) { return value; }
    /**
     * Declares a participating native resource transaction boundary.
     * @param value business input
     * @return unchanged data
     */
    @ResourceTransaction
    public String resources(String value) { return value; }
    /**
     * Declares a local JDBC transaction boundary supplied by the host.
     * @param value business input
     * @return unchanged data
     */
    @JdbcTransaction
    public String database(String value) { return value; }
    /**
     * Declares native lease ownership for an explicitly invoked activity.
     * @param value business input
     * @return unchanged data
     */
    @LeaseGuard
    public String leased(String value) { return value; }
    /**
     * Declares leadership ownership without installing a periodic timer.
     * @param value business input
     * @return unchanged data
     */
    @LeaderTask
    public String leader(String value) { return value; }
}
