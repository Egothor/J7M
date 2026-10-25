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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueueEncodingAdmissionTest {
    @Test void actualModifiedUtfAggregateAdmissionRejectsWithoutMutationAndAcceptedImageAlwaysRestores() {
        var state = new QueueStateMachine("分区🙂"); String scope = "租户🙂"; long index = 0;
        String payload = Base64.getEncoder().encodeToString(new byte[32768]); int admitted = 0;
        for (int i = 0; i < 1000; i++) {
            String id = "🙂".repeat(200) + i;
            var command = new ResourceCommand("Q_SEND", scope, "请求🙂" + i, List.of("队列🙂", id, payload, "1"));
            byte[] bytes = command.encode(); long before = state.retainedSnapshotBytes();
            try { state.validate(bytes, scope, false); }
            catch (IllegalStateException capacity) {
                assertTrue(capacity.getMessage().startsWith("OVERLOADED")); assertEquals(before, state.retainedSnapshotBytes()); break;
            }
            state.apply(++index, bytes); admitted++;
        }
        assertTrue(admitted > 1 && admitted < 1000, "Aggregate image must be explicitly bounded, not silently truncated");
        byte[] snapshot = state.snapshot(); assertEquals(snapshot.length, state.retainedSnapshotBytes()); assertTrue(snapshot.length <= 33554432);
        byte[] rejected = new ResourceCommand("Q_SEND", scope, "拒绝🙂", List.of("队列🙂", "🙂".repeat(200) + "last", payload, "1")).encode();
        assertThrows(IllegalStateException.class, () -> state.validate(rejected, scope, false)); assertArrayEquals(snapshot, state.snapshot());
        var restored = new QueueStateMachine("分区🙂"); restored.restore(snapshot);
        assertArrayEquals(snapshot, restored.snapshot()); assertEquals(restored.snapshot().length, restored.retainedSnapshotBytes());
        assertThrows(IllegalStateException.class, () -> restored.validate(rejected, scope, false)); assertArrayEquals(snapshot, restored.snapshot());
    }
}
