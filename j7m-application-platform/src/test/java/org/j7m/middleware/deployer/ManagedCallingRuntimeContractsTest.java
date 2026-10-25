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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.application.components.ManagedCallingPolicy;
import org.junit.jupiter.api.Test;

/**
 * These manually declared classes have no generated descriptor; runtime must still validate them.
 */
class ManagedCallingRuntimeContractsTest {
    interface Good { @RemoteMethod @Async CompletionStage<byte[]> work(); }
    static class Worker implements Good { public CompletionStage<byte[]> work() { return null; } }
    interface Unmarked { @RemoteMethod CompletionStage<String> work(); }
    static class UnmarkedWorker implements Unmarked { public CompletionStage<String> work() { return null; } }
    interface Raw { @RemoteMethod @Async CompletionStage work(); }
    static class RawWorker implements Raw { public CompletionStage work() { return null; } }

    @Test void procNoneRuntimeRejectsMissingCapabilityAndUnsupportedLogicalShapes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ManagedRuntimeContracts.validateType(Worker.class, Set.of()));
        ManagedRuntimeContracts.validateType(Worker.class, new ManagedCallingPolicy().annotationTypes());
        assertTrue(ManagedRuntimeContracts.allowed(Worker.class, Good.class.getMethod("work")));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedRuntimeContracts.allowed(UnmarkedWorker.class, Unmarked.class.getMethod("work")));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedRuntimeContracts.allowed(RawWorker.class, Raw.class.getMethod("work")));
    }
}
