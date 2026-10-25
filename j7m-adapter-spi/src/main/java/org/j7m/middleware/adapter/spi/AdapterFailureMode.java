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

package org.j7m.middleware.adapter.spi;

/**
 * Failure condition that a compatibility harness can simulate or inspect.
 *
 * <p>The enum is immutable and thread-safe. Failure modes are generic and do
 * not require any external broker, database, cloud service, workflow engine, or
 * container runtime during J7M core validation.</p>
 */
public enum AdapterFailureMode {
    /**
     * Target transport or endpoint is unavailable.
     */
    NETWORK_UNAVAILABLE,
    /**
     * Operation wrote only part of its intended durable state.
     */
    PARTIAL_WRITE,
    /**
     * Durable record or message is corrupted.
     */
    CORRUPTED_RECORD,
    /**
     * Artifact payload or metadata is corrupted.
     */
    CORRUPTED_ARTIFACT,
    /**
     * Lease owner is stale.
     */
    STALE_LEASE,
    /**
     * Fencing token is stale.
     */
    STALE_FENCING_TOKEN,
    /**
     * Operation exceeds its deadline.
     */
    TIMEOUT,
    /**
     * Operation is cancelled before completion.
     */
    CANCELLATION,
    /**
     * Operation is delivered or requested more than once.
     */
    DUPLICATE_OPERATION,
    /**
     * Requested capability is unsupported.
     */
    UNSUPPORTED_FEATURE,
    /**
     * Adapter or target refuses work because a limit was exceeded.
     */
    OVERLOADED_TARGET,
    /**
     * Required credential is absent.
     */
    CREDENTIAL_MISSING,
    /**
     * Required credential is malformed or rejected.
     */
    CREDENTIAL_INVALID,
    /**
     * Authorization hook denies the operation.
     */
    AUTHORIZATION_DENIED,
    /**
     * Adapter cannot rebuild claimed durable state.
     */
    RECOVERY_FAILURE,
    /**
     * Adapter returns malformed or incomplete data.
     */
    MALFORMED_RESPONSE,
    /**
     * Public status, error, evidence, or output leaks sensitive data.
     */
    REDACTION_FAILURE
}
