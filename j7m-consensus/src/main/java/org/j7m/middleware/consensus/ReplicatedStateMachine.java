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

package org.j7m.middleware.consensus;

/**
 * Deterministic application state owned by one consensus node. Callbacks are serialized;
 * callbacks must not invoke their node, use wall clocks, or perform network operations.
 */
public interface ReplicatedStateMachine {
    /**
     * Applies one committed command exactly once within a recovery generation.
     * @param index globally ordered log position
     * @param command immutable command copy
     */
    void apply(long index, byte[] command);
    /**
     * Applies a committed command with its authoritative consensus leadership term. The default
     * preserves existing implementations; epoch-aware resources override this callback.
     * @param index globally ordered committed log position
     * @param term positive term persisted in this log entry, never a caller-supplied epoch
     * @param command immutable command copy
     */
    default void apply(long index, long term, byte[] command) { apply(index, command); }

    /**
     * Advances the applied watermark for every committed entry, including internal metadata.
     * @param index fully applied log position
     */
    default void appliedThrough(long index) { }
    /**
     * Captures all application state after the last apply.
     * @return independent snapshot bytes, never null
     */
    byte[] snapshot();
    /**
     * Replaces state before replaying the suffix; implementations must validate before mutation.
     * @param snapshot previously captured application state
     */
    void restore(byte[] snapshot);
}
