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

package org.j7m.middleware.application.components;

import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Child-JVM-only fixture proving the declared HOST_JVM_HEAP_CEILING against a real -Xmx limit.
 */
public final class ManagedResourcePolicyHeapProcess {
    private static volatile byte[] allocation;
    private static int constructed;

    @IsolationProfile("heap")
    interface Work { String read(); }
    static final class Worker implements Work {
        Worker() { constructed++; }
        @Override public String read() { return "value"; }
    }

    private ManagedResourcePolicyHeapProcess() { }

    /**
     * Verifies matched or mismatched heap policy without affecting the parent test process.
     * @param args one boolean selecting whether the JVM profile must be accepted
     * @throws Exception if the fixture or declared enforcement is inconsistent
     */
    public static void main(final String[] args) throws Exception {
        final boolean accepted = Boolean.parseBoolean(args[0]);
        try (var policy = new ManagedResourcePolicy(Map.of(), Map.of("heap", new ManagedResourcePolicy.HostHeapProfile(80L * 1024 * 1024)))) {
            var contract = new ManagedServiceContract("heap.contract", 1,
                    new ManagedMethod(Work.class.getName(), "read", List.of(), String.class.getName()));
            try {
                policy.validate(contract, Worker.class, Work.class);
            } catch (IllegalArgumentException denied) {
                if (accepted || constructed != 0) { throw new AssertionError("Heap policy rejected incorrectly"); }
                System.out.println("HEAP_REJECTED_BEFORE_CONSTRUCTION");
                return;
            }
            if (!accepted) { throw new AssertionError("Heap policy accepted an unenforced limit"); }
            var worker = new Worker();
            if (!worker.read().equals("value") || constructed != 1) { throw new AssertionError(); }
            try {
                allocation = new byte[256 * 1024 * 1024];
                throw new AssertionError("Heap cap did not constrain allocation: " + allocation.length);
            } catch (OutOfMemoryError expected) {
                System.out.println("HOST_HEAP_LIMIT_ENFORCED");
            } finally { allocation = null; }
        }
    }
}
