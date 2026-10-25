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

enum SagaEventType {
    SAGA_DEFINITION_REGISTERED,
    SAGA_STARTED,
    SAGA_STEP_SCHEDULED,
    SAGA_STEP_STARTED,
    SAGA_STEP_SUCCEEDED,
    SAGA_STEP_FAILED,
    SAGA_STEP_RETRY_SCHEDULED,
    SAGA_STEP_TIMED_OUT,
    SAGA_STEP_DEAD_LETTERED,
    SAGA_COMPENSATION_SCHEDULED,
    SAGA_COMPENSATION_STARTED,
    SAGA_COMPENSATION_SUCCEEDED,
    SAGA_COMPENSATION_FAILED,
    SAGA_COMPENSATION_RETRY_SCHEDULED,
    SAGA_COMPENSATION_DEAD_LETTERED,
    SAGA_CANCEL_REQUESTED,
    SAGA_CANCELLED,
    SAGA_TIMED_OUT,
    SAGA_COMPLETED,
    SAGA_FAILED,
    TRANSACTION_CONTEXT_PROPAGATED,
    SAGA_AUDIT_RECORDED,
    SAGA_CHECKPOINT
}
