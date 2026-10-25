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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.distributed.*;

/**
 * Actual native state machine with controlled committed time and explicit transport fault points.
 * Network/process qualification is separate; this fixture does not emulate native lease semantics.
 */
final class OwnershipEndpoint implements ResourceEndpoint {
    private final ReplicatedResources machine = new ReplicatedResources();
    private final String scope = "ownership-test";
    private long index, time = 1000;
    final AtomicInteger effects = new AtomicInteger(), acquisitions = new AtomicInteger(), renewals = new AtomicInteger();
    final AtomicBoolean loseRenew = new AtomicBoolean();
    final CountDownLatch renewalFailed = new CountDownLatch(1);
    final BlockingQueue<ReplicatedResources.Lease> renewed = new ArrayBlockingQueue<>(64);
    volatile CountDownLatch renewEntered, renewRelease;
    volatile String leaseKey;
    OwnershipEndpoint() { advance(0); }
    synchronized void advance(long delta) {
        time += delta;
        machine.apply(++index, 1, new ResourceCommand("TIME", scope, "clock-" + index, List.of(Long.toString(time), Long.toString(time), "0")).encode());
    }
    public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
        if (operation.equals("RENEW") && renewEntered != null) {
            renewEntered.countDown();
            try { if (!renewRelease.await(3, TimeUnit.SECONDS)) throw new IOException("Controlled renewal deadline"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Interrupted renewal", stopped); }
        }
        ReplicatedResources.Result result;
        synchronized (this) {
            var command = new ResourceCommand(operation, scope, request, arguments); byte[] frame = command.encode();
            machine.validate(frame, scope, false); machine.validateAdmission(command, index);
            var previous = machine.result(scope, request, index);
            machine.apply(++index, 1, frame); result = machine.result(scope, request, index);
            if (result == null) result = new ReplicatedResources.Result("OVERLOADED", "", index);
            if (operation.equals("ACQUIRE")) { acquisitions.incrementAndGet(); leaseKey = arguments.getFirst(); }
            if (operation.equals("RENEW")) { renewals.incrementAndGet(); var current = machine.lease(scope, arguments.getFirst(), index); if (current != null) renewed.offer(current); }
            if (operation.equals("FENCED") && result.status().equals("OK") && previous == null) effects.incrementAndGet();
        }
        if (operation.equals("RENEW") && loseRenew.compareAndSet(true, false)) { renewalFailed.countDown(); throw new IOException("Renew committed but reply lost"); }
        return result;
    }
    public synchronized ReplicatedResources.Lease lease(String key, Instant deadline) { return machine.lease(scope, key, index); }
    public synchronized ReplicatedResources.Value read(String key, Instant deadline) { return machine.queryAtReadIndex(scope, key, index); }
    public ReplicatedResources.Result transaction(String tx, Instant deadline) { throw new UnsupportedOperationException(); }
    public List<String> prepared(Instant deadline) { throw new UnsupportedOperationException(); }
    public List<String> participants(String tx, Instant deadline) { throw new UnsupportedOperationException(); }
}
