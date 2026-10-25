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

import java.util.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;

/**
 * Revocable current saga callback capability, installed only by its actual trusted host policy.
 * Staged protected CAS effects commit atomically with a forward step's durable successful result;
 * they are not immediate writes and cannot protect arbitrary external effects. A captured context
 * never authorizes a different callback or work after the original USER scope has sealed.
 */
public final class ManagedSagaContext {
    private final String saga, step, key, prefix;
    private final long fence, partitionEpoch, validUntil;
    private final boolean compensation;
    private final InvocationControl control;
    private final List<String> effects = new ArrayList<>();
    private volatile boolean revoked;
    ManagedSagaContext(String saga,String step,String key,long fence,long partitionEpoch,boolean compensation,String prefix,
            long validUntil,InvocationControl control) {
        this.saga=saga;this.step=step;this.key=key;this.fence=fence;this.partitionEpoch=partitionEpoch;this.compensation=compensation;
        this.prefix=prefix;this.validUntil=validUntil;this.control=control;
    }
    private void active() {
        if (revoked || System.nanoTime()>=validUntil || control.cancellationRequested() || control.remainingBudget().isZero()
                || !ManagedInvocationLifetime.currentUserWorkOpen() || ManagedInvocationLifetime.currentContext(ManagedSagaContext.class)!=this) {
            revoked=true; throw MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN,"Saga ownership is unavailable");
        }
    }
    /**
     * Returns the stable logical saga identity for this callback.
     * @return original run identity
     * @throws MiddlewareException outside this live callback
     */
    public String sagaId() { active();return saga; }
    /**
     * Returns the persisted step identity.
     * @return stable step name
     * @throws MiddlewareException outside this live callback
     */
    public String step() { active();return step; }
    /**
     * Returns the exact external protocol key retained across safe replay and takeover.
     * The application must actually send this key to an external service that honors idempotency.
     * @return persisted key; compensation uses its original step key with a fixed compensation suffix
     * @throws MiddlewareException outside this live callback
     */
    public String idempotencyKey() { active();return key; }
    /**
     * Returns the native run ownership fence. It is not an arbitrary database lock token.
     * @return current native fence
     * @throws MiddlewareException outside this live callback
     */
    public long fence() { active();return fence; }
    /**
     * Returns the immutable source ownership epoch pinned when this callback was admitted.
     * It never follows a migrated partition; the final native mutation verifies the same epoch.
     * @return captured positive partition epoch
     * @throws MiddlewareException outside this live callback
     */
    public long partitionEpoch() { active();return partitionEpoch; }
    /**
     * Observes local revocation without acquiring or renewing any authority.
     * @return whether this context has been revoked or its conservative local lease has expired
     */
    public boolean revoked() { return revoked || System.nanoTime()>=validUntil || control.cancellationRequested() || control.remainingBudget().isZero(); }
    /**
     * Stages a bounded native execution-map compare-and-set for successful forward completion.
     * The host namespaces the key. At most16 distinct keys fit one callback; duplicate keys fail
     * before a native mutation. Compensation cannot stage these effects because its native result
     * protocol has no atomic protected-map effect boundary.
     * @param key application key, 1..128 identifier characters
     * @param expectedVersion exact current native value version, zero for absence
     * @param value string value, at most4096 UTF16 code units and no NUL
     * @throws MiddlewareException after revocation, cancellation or the original deadline
     * @throws IllegalArgumentException for compensation, duplicate key or invalid/bounded input
     */
    public synchronized void stageEffect(String key,long expectedVersion,String value) {
        active();
        if(compensation || key==null || !key.matches("[A-Za-z0-9_.-]{1,128}") || expectedVersion<0 || value==null || value.length()>4096 || value.indexOf('\0')>=0) throw new IllegalArgumentException("Invalid staged saga effect");
        String target="saga.effect."+OwnershipData.hash(prefix,key);
        for(int i=0;i<effects.size();i+=3) if(effects.get(i).equals(target)) throw new IllegalArgumentException("Duplicate staged saga key");
        if(effects.size()>=48) throw new IllegalArgumentException("Saga effect capacity");
        effects.addAll(List.of(target,Long.toString(expectedVersion),value));
    }
    synchronized List<String> effects() { return List.copyOf(effects); }
    void revoke() { revoked=true; }
    /**
     * Omits business identities and staged values from diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedSagaContext[revoked="+revoked()+"]"; }
}
