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

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.distributed.ReplicatedResources;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * Actual per-claim capability supplied only through an ownership provider in its owned CALL.
 * It never rebinds to a new owner. Every effect is a native FENCED mutation in the lease resource;
 * arbitrary SQL, files, Java callbacks and external RPC are outside this protection. Its protected
 * data namespace includes the trusted tenant and shard, so application keys cannot escape it.
 */
public final class ManagedOwnershipContext {
    private enum State { ACQUIRING, ACTIVE, REVOKED, CLOSED }
    private final Object policyOwner;
    private final String profileName;
    private final ManagedOwnershipProfile profile;
    private final ManagedOwnershipBackend.Session session;
    private final InvocationControl control;
    private final String leaseKey, dataPrefix, owner;
    private final BooleanSupplier bindingOpen;
    private final Consumer<ManagedOwnershipContext> schedule, cancel, disposed;
    private final LongSupplier clock;
    private volatile State state = State.ACQUIRING;
    private volatile String reason = "ACQUIRING";
    private volatile long validUntil;
    private ReplicatedResources.Lease lease;
    private static final class Effect {
        final String target, value, request;
        final List<String> arguments;
        Long version;
        Effect(String target, String value, String request, List<String> arguments) {
            this.target = target; this.value = value; this.request = request; this.arguments = List.copyOf(arguments);
        }
    }
    private final Map<String, Effect> effects = new HashMap<>();
    private long knownVersion;
    private boolean finished;

    ManagedOwnershipContext(Object policyOwner, String profileName, ManagedOwnershipProfile profile,
            ManagedOwnershipBackend.Session session, InvocationControl control, String leaseKey, String dataPrefix,
            BooleanSupplier bindingOpen, Consumer<ManagedOwnershipContext> schedule, Consumer<ManagedOwnershipContext> cancel,
            Consumer<ManagedOwnershipContext> disposed, LongSupplier clock) {
        this.policyOwner = policyOwner; this.profileName = profileName; this.profile = profile; this.session = session;
        this.control = control; this.leaseKey = leaseKey; this.dataPrefix = dataPrefix; this.bindingOpen = bindingOpen;
        this.schedule = schedule; this.cancel = cancel; this.disposed = disposed; this.clock = clock;
        owner = "managed-owner-" + UUID.randomUUID();
    }
    boolean matches(Object policy, String name, String key) { return policyOwner == policy && profileName.equals(name) && leaseKey.equals(key); }
    private Instant deadline() {
        Duration remaining = control.remainingBudget();
        if (remaining.isZero() || remaining.isNegative()) { revoke("DEADLINE"); throw failure(MiddlewareErrorCode.DEADLINE_EXCEEDED); }
        return Instant.now().plus(remaining.compareTo(profile.operationBudget()) < 0 ? remaining : profile.operationBudget());
    }
    synchronized void acquire() {
        long start = clock.getAsLong();
        try {
            var result = session.execute("ACQUIRE", "ownership-acquire-" + UUID.randomUUID(), List.of(leaseKey, owner, Long.toString(profile.lease().toMillis()), "0"), deadline());
            if (result.status().equals("CONFLICT")) { revoke("BUSY"); throw failure(MiddlewareErrorCode.UNAVAILABLE); }
            check(result); knownVersion = result.index();
            install(session.lease(leaseKey, deadline()), result, start); state = State.ACTIVE; reason = "ACTIVE";
            ensureAuthority(); schedule.accept(this);
        } catch (IOException uncertain) { revoke("UNKNOWN"); throw failure(MiddlewareErrorCode.UNAVAILABLE); }
        catch (SecurityException denied) { revoke("PERMISSION_DENIED"); throw failure(MiddlewareErrorCode.PERMISSION_DENIED); }
        catch (RuntimeException failed) { if (state != State.REVOKED) revoke("UNKNOWN"); throw failed; }
    }
    private void install(ReplicatedResources.Lease observed, ReplicatedResources.Result result, long start) throws IOException {
        long fence;
        try { fence = Long.parseLong(result.value()); }
        catch (NumberFormatException malformed) { throw new IOException("Invalid ownership claim result"); }
        if (observed == null || !observed.owner().equals(owner) || observed.version() != result.index()
                || observed.fence() != fence || fence < 1 || observed.leadershipEpoch() < 1) throw new IOException("Ownership claim is not established");
        lease = observed; knownVersion = observed.version();
        // A conservative one-second margin covers the declared two-sided 500ms clock tolerance.
        validUntil = start + profile.lease().minusSeconds(1).toNanos();
    }
    private void check(ReplicatedResources.Result result) {
        if (result.status().equals("OK")) return;
        revoke(Set.of("CAPACITY", "OVERLOADED").contains(result.status()) ? "CAPACITY" : "LEASE_LOST");
        throw failure(reason.equals("CAPACITY") ? MiddlewareErrorCode.OVERLOADED : MiddlewareErrorCode.STALE_FENCING_TOKEN);
    }
    synchronized void renew() {
        if (state != State.ACTIVE) return;
        try {
            ensureAuthority(); long start = clock.getAsLong();
            var result = session.execute("RENEW", "ownership-renew-" + UUID.randomUUID(), List.of(leaseKey, Long.toString(lease.version()), owner,
                    Long.toString(profile.lease().toMillis()), "0"), deadline());
            check(result); knownVersion = result.index(); install(session.lease(leaseKey, deadline()), result, start); ensureAuthority();
        } catch (IOException uncertain) { revoke("UNKNOWN"); }
        catch (SecurityException denied) { revoke("PERMISSION_DENIED"); }
        catch (RuntimeException rejected) { if (state != State.REVOKED) revoke("UNKNOWN"); }
    }
    void renewalOverloaded() { revoke("CAPACITY"); }
    private void ensureAuthority() {
        if (state != State.ACTIVE) throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);
        if (!bindingOpen.getAsBoolean()) { revoke("CLOSED"); throw failure(MiddlewareErrorCode.UNAVAILABLE); }
        if (control.remainingBudget().isZero()) { revoke("DEADLINE"); throw failure(MiddlewareErrorCode.DEADLINE_EXCEEDED); }
        if (control.cancellationRequested()) { revoke("CANCELLED"); throw failure(MiddlewareErrorCode.CANCELLED); }
        if (clock.getAsLong() - validUntil >= 0) { revoke("LEASE_LOST"); throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN); }
    }
    private void requireUser() {
        if (!ManagedInvocationLifetime.currentUserWorkOpen()
                || ManagedInvocationLifetime.currentContext(ManagedOwnershipContext.class) != this) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Ownership context is outside its owned USER invocation");
        }
        ensureAuthority();
    }
    private void revoke(String cause) { reason = cause; state = State.REVOKED; }
    private MiddlewareException failure(MiddlewareErrorCode code) {
        return new MiddlewareException(new RemoteError(code, "Ownership authority is unavailable", Map.of("outcome", "UNKNOWN", "status", reason), null, null, false, false));
    }

    /**
     * Reports local revocation, not a remote proof of current ownership. Native effects always
     * validate their fence and epoch independently. Checking this flag never renews a lease.
     * @return true after loss, cancellation, budget expiry or cleanup
     */
    public boolean revoked() {
        return state != State.ACTIVE || !bindingOpen.getAsBoolean() || control.cancellationRequested()
                || control.remainingBudget().isZero() || clock.getAsLong() - validUntil >= 0;
    }
    /**
     * Returns the current acquired/renewed token only inside the owning USER work.
     * @return snapshot fence; not authorization for arbitrary external effects
     * @throws MiddlewareException outside the owning invocation or after revocation
     */
    public synchronized long fence() { requireUser(); return lease.fence(); }
    /**
     * Returns the native lease's acquisition/renewal epoch.
     * @return historical native lease epoch
     * @throws MiddlewareException outside the owning invocation or after revocation
     */
    public synchronized long leadershipEpoch() { requireUser(); return lease.leadershipEpoch(); }
    /**
     * Returns the pinned partition epoch, or zero for an explicitly fixed non-migrating group.
     * @return immutable claim session epoch; it never follows handoff
     * @throws MiddlewareException outside the owning invocation or after revocation
     */
    public synchronized long partitionEpoch() { requireUser(); return session.partitionEpoch(); }

    /**
     * Writes String data through the same resource's atomic native FENCED operation. Keys are
     * restricted to this authenticated tenant/shard namespace. Reusing an effect identity with
     * different arguments fails; a lost response is UNKNOWN and never authorizes body replay.
     * @param effectId stable identity within this claim,1..512 characters without NUL
     * @param key application key,1..512 characters without NUL
     * @param value non-null String, at most4096 UTF16 units; code units round-trip without normalization
     * @return committed native version, not fresh ownership authority
     * @throws MiddlewareException on revocation, native rejection, permission, uncertain I/O or host effect-capacity exhaustion
     * @throws IllegalArgumentException for invalid data bounds or effect-identity conflict
     */
    public synchronized long write(String effectId, String key, String value) {
        OwnershipData.identifier(effectId); OwnershipData.identifier(key); String frame = OwnershipData.encode(value);
        requireUser(); String target = dataPrefix + OwnershipData.hash(key);
        Effect effect = effects.get(effectId);
        if (effect == null) {
            if (effects.size() >= profile.maximumEffects()) throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Ownership effect capacity");
            effect = new Effect(target, frame, "ownership-effect-" + OwnershipData.hash(owner, effectId),
                    List.of(leaseKey, Long.toString(lease.fence()), Long.toString(lease.leadershipEpoch()), target, frame));
            effects.put(effectId, effect);
        } else if (!effect.target.equals(target) || !effect.value.equals(frame)) throw new IllegalArgumentException("Ownership effect identity conflict");
        if (effect.version != null) return effect.version;
        try {
            var result = session.execute("FENCED", effect.request, effect.arguments, deadline());
            check(result); ensureAuthority(); effect.version = result.index(); return result.index();
        } catch (IOException uncertain) { revoke("UNKNOWN"); throw failure(MiddlewareErrorCode.UNAVAILABLE); }
        catch (SecurityException denied) { revoke("PERMISSION_DENIED"); throw failure(MiddlewareErrorCode.PERMISSION_DENIED); }
    }
    /**
     * Reads a fresh quorum snapshot from this context's protected namespace. This is not an atomic
     * transaction with a separate effect and does not replace the fence check on every write.
     * @param key application key,1..512 characters without NUL
     * @return decoded String/native version, or null when absent
     * @throws MiddlewareException after revocation, permission denial, invalid frame or uncertain I/O
     * @throws IllegalArgumentException for invalid key
     */
    public synchronized ReplicatedResources.Value read(String key) {
        OwnershipData.identifier(key); requireUser();
        try {
            var value = session.read(dataPrefix + OwnershipData.hash(key), deadline()); ensureAuthority();
            return value == null ? null : new ReplicatedResources.Value(OwnershipData.decode(value.value()), value.version());
        } catch (IOException uncertain) { revoke("UNKNOWN"); throw failure(MiddlewareErrorCode.UNAVAILABLE); }
        catch (SecurityException denied) { revoke("PERMISSION_DENIED"); throw failure(MiddlewareErrorCode.PERMISSION_DENIED); }
    }
    synchronized java.util.concurrent.CompletionStage<InvocationResult> finish(InvocationResult result) {
        if (finished) return CompletableFuture.completedFuture(result);
        boolean lost = revoked(); finished = true; state = State.CLOSED; cancel.accept(this);
        try {
            if (knownVersion > 0) {
                var reply = session.execute("CANCEL", "ownership-cancel-" + UUID.randomUUID(), List.of(leaseKey, Long.toString(knownVersion)),
                        Instant.now().plus(profile.cleanupBudget()));
                if (!reply.status().equals("OK")) lost = true;
            }
        } catch (IOException | RuntimeException uncertain) { lost = true; }
        finally { effects.clear(); state = State.CLOSED; reason = "CLOSED"; disposed.accept(this); }
        if (lost && result.failure() == null) return CompletableFuture.completedFuture(new InvocationResult(null,
                new MiddlewareException(new RemoteError(MiddlewareErrorCode.STALE_FENCING_TOKEN, "Ownership completion is uncertain",
                        Map.of("outcome", "UNKNOWN"), null, null, false, false))));
        return CompletableFuture.completedFuture(result);
    }
    /**
     * Omits tenant, keys, owner, values and token details from incidental diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedOwnershipContext[invocation-owned]"; }
}
