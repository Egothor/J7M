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

package org.j7m.middleware.application.distributed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;

/**
 * Invocation-bound staging capability for the deduplication partition's protected map.
 * Staging is not a write acknowledgement. The host commits the immutable CAS set, result and claim
 * fence in one D12 command only after USER work settles. Retaining this capability never extends
 * its claim, permits staging from another invocation, or grants reconciliation authority. A host
 * transaction boundary may prohibit protected CAS; an attempted protocol mix then prevents both
 * transaction commit and a cached success, even if application code catches the rejection.
 */
public final class ManagedDeduplicationContext {
    private final String invocationId;
    private final long fence;
    private final InvocationControl control;
    private final List<String> effects = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private boolean sealed;
    private boolean protectedEffectsProhibited, prohibitedAttempt;
    private int bytes;

    ManagedDeduplicationContext(String invocationId, long fence, InvocationControl control) {
        this.invocationId = invocationId; this.fence = fence; this.control = control;
    }

    /**
     * Returns a scoped opaque digest without the application key or caller identity.
     * @return stable durable invocation digest
     */
    public String invocationId() { return invocationId; }

    /**
     * Returns the acquired durable claim's fencing token.
     * @return positive claim fence; never an authority token for arbitrary external systems
     */
    public long fence() { return fence; }

    /**
     * Stages one protected map compare-and-set for atomic outcome completion.
     * At most64 distinct keys and256KiB of modified-UTF data are accepted. Expected version zero
     * means absent; positive versions come from the same execution partition's protected map.
     * Calls require the actual serial USER context, an open work phase and an unexpired budget.
     * @param key nonblank native map key, at most128 characters without NUL
     * @param expectedVersion zero or the observed positive version
     * @param value native textual value, at most16384 characters without NUL
     * @throws MiddlewareException for invalid bounds, exhausted capacity or a closed work phase
     * @throws IllegalStateException outside the owning invocation or under an incompatible transaction protocol
     */
    public void compareAndSet(String key, long expectedVersion, String value) {
        if (ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class) != this
                || !ManagedInvocationLifetime.currentUserWorkOpen()) throw new IllegalStateException("DEDUPLICATION_WORK_CLOSED");
        if (control.cancellationRequested() || control.remainingBudget().isZero() || control.remainingBudget().isNegative()) {
            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Deduplication work budget exhausted");
        }
        if (key == null || key.isBlank() || key.length() > 128 || key.indexOf(0) >= 0 || expectedVersion < 0
                || value == null || value.length() > 16384 || value.indexOf(0) >= 0) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Invalid protected CAS bounds");
        }
        String version = Long.toString(expectedVersion);
        int added = utfBytes(key) + utfBytes(version) + utfBytes(value) + 6;
        synchronized (this) {
            if (sealed) throw new IllegalStateException("DEDUPLICATION_WORK_CLOSED");
            if (protectedEffectsProhibited) { prohibitedAttempt = true; throw new IllegalStateException("CROSS_PROTOCOL_CAS_UNSUPPORTED"); }
            if (keys.contains(key)) throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Duplicate protected CAS key");
            if (keys.size() >= 64 || bytes + added > 262144) throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Protected CAS capacity exhausted");
            effects.add(key); effects.add(version); effects.add(value); keys.add(key); bytes += added;
        }
    }

    synchronized void prohibitProtectedEffects() {
        protectedEffectsProhibited = true;
        if (!effects.isEmpty()) { prohibitedAttempt = true; throw new IllegalStateException("CROSS_PROTOCOL_CAS_UNSUPPORTED"); }
    }
    synchronized boolean prohibitedEffectAttempt() { return prohibitedAttempt; }
    synchronized List<String> sealAndSnapshot() {
        sealed = true;
        if (prohibitedAttempt) throw new IllegalStateException("CROSS_PROTOCOL_CAS_UNSUPPORTED");
        return List.copyOf(effects);
    }

    private static int utfBytes(String value) {
        int size = 0;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); size += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3; }
        return size;
    }
}
