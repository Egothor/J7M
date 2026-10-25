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

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stable live service handle; each business invocation captures one exact target and is never
 * replayed automatically. The owning activation closes the handle to detach discovery/listeners.
 * @param <T> ordinary business interface
 */
public interface ServiceBinding<T> extends AutoCloseable {
    /**
     * Returns one identity-stable business proxy, even after target refresh.
     * @return nonnull stable proxy
     * @throws IllegalStateException for closed handles or OPTIONAL policy
     */
    T proxy();
    /**
     * Exposes absence explicitly without manufacturing a null proxy.
     * @return provider of an optional stable proxy, empty when no eligible target exists
     * @throws IllegalStateException if closed
     */
    ManagedProvider<Optional<T>> optionalProvider();
    /**
     * Returns an immutable typed snapshot; references are not authorization tokens.
     * @return current publication state
     */
    BindingSnapshot snapshot();
    /**
     * Observes future coalesced refresh snapshots on bounded owner workers, outside state locks.
     * Slow listeners may miss intermediate snapshots, never the latest published generation.
     * Closing revokes future scheduling; an already running listener must cooperate with shutdown.
     * @param listener bounded nonblocking observer, never supplied invocation payloads
     * @return listener registration, at most32 per binding
     * @throws IllegalStateException if closed or listener capacity is exhausted
     */
    AutoCloseable watch(Consumer<BindingSnapshot> listener);
    /**
     * Detaches discovery and observers; future business calls fail rather than resurrecting targets.
     */
    @Override void close();
}
