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

package org.j7m.middleware.reliability;

import java.lang.reflect.Method;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.reliability.BackpressurePolicy;
import org.j7m.middleware.api.reliability.DeliveryMode;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.ReliabilityDecision;
import org.j7m.middleware.api.reliability.ReliabilityStatus;

/**
 * Evaluates transport-neutral reliability policy for one invocation.
 *
 * <p>The evaluator is stateless and thread-safe. It does not perform durable
 * storage, messaging, coordination, or consensus.</p>
 */
public final class ReliabilityPolicyEvaluator {
    private final RetrySafetyResolver retrySafetyResolver;

    /**
     * Creates an evaluator with the default retry-safety resolver.
     */
    public ReliabilityPolicyEvaluator() {
        this(new RetrySafetyResolver());
    }

    /**
     * Creates an evaluator.
     *
     * @param retrySafetyResolver retry-safety resolver
     */
    public ReliabilityPolicyEvaluator(final RetrySafetyResolver retrySafetyResolver) {
        if (retrySafetyResolver == null) {
            throw new IllegalArgumentException("retrySafetyResolver must not be null");
        }
        this.retrySafetyResolver = retrySafetyResolver;
    }

    /**
     * Evaluates reliability policy before invocation dispatch.
     *
     * @param method Java method
     * @param options reliability options
     * @param payloadBytes estimated payload bytes
     * @return decision
     */
    public ReliabilityDecision evaluateBeforeDispatch(
            final Method method,
            final InvocationReliabilityOptions options,
            final long payloadBytes) {
        InvocationReliabilityOptions safe = options == null ? InvocationReliabilityOptions.defaults() : options;
        if (safe.deliverySemantics().mode() == DeliveryMode.UNSUPPORTED_EXACTLY_ONCE) {
            return ReliabilityDecision.rejected(ReliabilityStatus.UNSUPPORTED_EXACTLY_ONCE, MiddlewareErrorCode.INVALID_ARGUMENT,
                    "generic exactly-once distributed execution is unsupported");
        }
        if (safe.cancellationRequested()) {
            return ReliabilityDecision.rejected(ReliabilityStatus.CANCELLED, MiddlewareErrorCode.CANCELLED,
                    "invocation cancellation requested");
        }
        if (safe.maxPayloadBytes() > 0L && payloadBytes > safe.maxPayloadBytes()) {
            return ReliabilityDecision.rejected(ReliabilityStatus.LIMIT_EXCEEDED, MiddlewareErrorCode.INVALID_ARGUMENT,
                    "invocation payload exceeds reliability limit");
        }
        boolean retrySafe = this.retrySafetyResolver.isRetrySafe(method, safe);
        if (safe.deliverySemantics().mode() == DeliveryMode.AT_LEAST_ONCE_RETRYABLE && !retrySafe) {
            return ReliabilityDecision.rejected(ReliabilityStatus.NOT_RETRY_SAFE, MiddlewareErrorCode.INVALID_ARGUMENT,
                    "at-least-once retry requires retry-safe invocation");
        }
        return ReliabilityDecision.accepted(retrySafe ? ReliabilityStatus.RETRY_SAFE : ReliabilityStatus.ACCEPTED, retrySafe);
    }

    /**
     * Evaluates result-size limits after method execution.
     *
     * @param options reliability options
     * @param resultBytes estimated result bytes
     * @return decision
     */
    public ReliabilityDecision evaluateResult(final InvocationReliabilityOptions options, final long resultBytes) {
        InvocationReliabilityOptions safe = options == null ? InvocationReliabilityOptions.defaults() : options;
        if (safe.maxResultBytes() > 0L && resultBytes > safe.maxResultBytes()) {
            return ReliabilityDecision.rejected(ReliabilityStatus.LIMIT_EXCEEDED, MiddlewareErrorCode.OVERLOADED,
                    "invocation result exceeds reliability limit");
        }
        return ReliabilityDecision.accepted(ReliabilityStatus.ACCEPTED, safe.deliverySemantics().retrySafe());
    }

    /**
     * Returns the decision for a backpressure rejection.
     *
     * @param policy backpressure policy
     * @return decision
     */
    public ReliabilityDecision backpressureRejected(final BackpressurePolicy policy) {
        BackpressurePolicy safe = policy == null ? BackpressurePolicy.REJECT_IMMEDIATELY : policy;
        String message = safe == BackpressurePolicy.CALLER_MANAGED_RETRY_AFTER
                ? "runtime overloaded; caller-managed retry-after metadata is available"
                : "runtime overloaded";
        return ReliabilityDecision.rejected(ReliabilityStatus.BACKPRESSURE_APPLIED, MiddlewareErrorCode.OVERLOADED, message);
    }
}
