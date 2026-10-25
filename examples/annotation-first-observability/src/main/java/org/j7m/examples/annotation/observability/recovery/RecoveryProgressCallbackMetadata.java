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

package org.j7m.examples.annotation.observability.recovery;

import org.j7m.middleware.annotations.Callback;
import org.j7m.middleware.annotations.Checkpoint;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.Progress;
import org.j7m.middleware.annotations.Restore;
import org.j7m.middleware.annotations.Sensitive;

/**
 * Focused recovery, progress, and callback metadata example.
 *
 * <p>{@link Checkpoint}, {@link Progress}, and {@link Restore} describe
 * recovery hooks and progress reporting points. {@link Callback} describes an
 * ObjectRef-compatible callback shape. The example does not persist durable
 * checkpoints, read restore state, export the callback over a transport, or
 * expose sensitive payload data.</p>
 */
@J7mService(name = "//apps/example/annotation/observability/RecoveryProgress", exported = false)
public final class RecoveryProgressCallbackMetadata {
    // J7M teaching: recovery annotations are grouped apart from scheduling so
    // checkpoint storage remains visibly owned by a recovery engine.

    /**
     * Creates a safe checkpoint summary.
     *
     * @param marker public progress marker
     * @return deterministic checkpoint summary
     */
    @Checkpoint
    @Progress
    public String checkpoint(@Progress final String marker) {
        return "checkpoint=" + marker;
    }

    /**
     * Creates a safe restore summary.
     *
     * @param marker public restore marker
     * @return deterministic restore summary
     */
    @Restore
    public String restore(final String marker) {
        return "restore=" + marker;
    }

    /**
     * Sends a redacted progress callback.
     *
     * @param payload sensitive payload that must not be printed
     * @param callback callback reference candidate
     */
    public void publish(@Sensitive final String payload, @Callback final ProgressCallback callback) {
        // J7M teaching: callback output is safe progress metadata; the
        // sensitive payload is consumed only to prove it is not echoed.
        payload.length();
        callback.accept("progress=<redacted>");
    }

    /**
     * Callback contract used by the recovery example.
     *
     * <p>The interface is a metadata example only. Possessing a callback
     * reference is not authorization to invoke it remotely.</p>
     */
    @Callback
    public interface ProgressCallback {
        /**
         * Accepts a safe progress value.
         *
         * @param value public progress value
         */
        void accept(String value);
    }
}
