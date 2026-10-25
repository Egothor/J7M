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

package org.j7m.middleware.application.api;

/**
 * Finite generated direct-access job and safe-point hooks, consumed by a trusted job factory.
 * Hooks execute only within the current owned USER call; no reflection, arbitrary heap snapshot
 * or callback timer is implied. Restore receives null before a fresh run to clear prior mutable state.
 * @param <T> activation-owned POJO job component
 */
public interface ManagedJobDefinition<T> extends ManagedHostedAdapter<T> {
    /**
     * Returns the exact durable-job feature including entry and optional hooks.
     * @return immutable feature declaration
     */
    ManagedHostedFeature job();
    /**
     * Returns the concrete entry input type.
     * @return scalar, bytes or registered schema record type
     */
    Class<?> inputType();
    /**
     * Returns the logical result type after unwrapping CompletionStage.
     * @return concrete data type or Void.class
     */
    Class<?> resultType();
    /**
     * Returns the checkpoint type; Void.class denotes no checkpoint/restore pair.
     * @return concrete nullable reference state type
     */
    Class<?> checkpointType();
    /**
     * Returns the progress hook type; Void.class denotes no progress hook.
     * @return concrete reference progress type
     */
    Class<?> progressType();
    /**
     * Captures state at an explicit safe point inside the current job invocation.
     * @param instance actual current job component
     * @return current checkpoint data
     * @throws Exception if the declared hook fails or is absent
     */
    default Object checkpoint(T instance) throws Exception { throw new IllegalArgumentException("Job checkpoint is not declared"); }
    /**
     * Restores the current claim's checkpoint before its entry, null for every fresh run.
     * @param instance actual current job component
     * @param checkpoint restored state or null for a fresh run
     * @throws Exception if the declared hook fails or is absent
     */
    default void restore(T instance,Object checkpoint) throws Exception { throw new IllegalArgumentException("Job restore is not declared"); }
    /**
     * Reads progress at an explicit safe point inside the owned invocation.
     * @param instance actual current job component
     * @return bounded progress data
     * @throws Exception if the declared hook fails or is absent
     */
    default Object progress(T instance) throws Exception { throw new IllegalArgumentException("Job progress is not declared"); }
}
