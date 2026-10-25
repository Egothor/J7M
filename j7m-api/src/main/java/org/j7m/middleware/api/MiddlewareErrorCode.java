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

package org.j7m.middleware.api;

/**
 * Stable error codes returned by the middleware public API.
 *
 * <p>The enum is immutable and thread-safe. Codes are transport-neutral and are
 * used to wrap registry, codec, reflection, RMI, gRPC, and runtime failures
 * before they cross a public API boundary.</p>
 */
public enum MiddlewareErrorCode {
    /**
     * Requested object, service, method, or registry record was not found.
     */
    NOT_FOUND,
    /**
     * Target or subsystem is temporarily unavailable.
     */
    UNAVAILABLE,
    /**
     * Operation exceeded its caller-supplied or default deadline.
     */
    DEADLINE_EXCEEDED,
    /**
     * Operation was cancelled before completion.
     */
    CANCELLED,
    /**
     * Runtime refused work because a configured limit was exceeded.
     */
    OVERLOADED,
    /**
     * Caller is authenticated but lacks permission.
     */
    PERMISSION_DENIED,
    /**
     * Caller is not authenticated.
     */
    UNAUTHENTICATED,
    /**
     * Caller supplied invalid arguments or incompatible options.
     */
    INVALID_ARGUMENT,
    /**
     * Requested method is not exposed by the target object.
     */
    METHOD_NOT_FOUND,
    /**
     * Requested Java interface is not exposed or has an incompatible signature.
     */
    INTERFACE_MISMATCH,
    /**
     * Codec failed to encode or decode a value.
     */
    CODEC_ERROR,
    /**
     * Remote application method threw an exception.
     */
    REMOTE_EXCEPTION,
    /**
     * Reference or registry record lease has expired.
     */
    LEASE_EXPIRED,
    /**
     * Duplicate operation rejected by reliability semantics.
     */
    DUPLICATE_OPERATION,
    /**
     * Fencing token is stale.
     */
    STALE_FENCING_TOKEN,
    /**
     * Registry could not satisfy the operation.
     */
    REGISTRY_UNAVAILABLE,
    /**
     * Transport failed before or during invocation.
     */
    TRANSPORT_ERROR,
    /**
     * Durable log detected record or segment corruption.
     */
    LOG_CORRUPTION,
    /**
     * Durable log I/O, sync, or format operation failed.
     */
    DURABLE_LOG_ERROR,
    /**
     * Durable log recovery failed.
     */
    RECOVERY_FAILED,
    /**
     * Unexpected middleware implementation failure.
     */
    INTERNAL
}
