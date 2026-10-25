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

package org.j7m.middleware.security.defaults;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.CredentialProvider;
import org.j7m.middleware.security.api.CredentialRotationListener;
import org.j7m.middleware.security.api.CredentialSnapshot;

/**
 * Mutable credential provider for tests and demos.
 */
public final class MutableCredentialProvider implements CredentialProvider {
    private final List<CredentialRotationListener> listeners;
    private volatile CredentialSnapshot snapshot;

    /**
     * Creates a provider.
     *
     * @param credential initial credential
     */
    public MutableCredentialProvider(final Credential credential) {
        this.listeners = new CopyOnWriteArrayList<>();
        this.snapshot = new CredentialSnapshot(credential, Instant.now());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CredentialSnapshot current() {
        return this.snapshot;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addRotationListener(final CredentialRotationListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    /**
     * Rotates credentials.
     *
     * @param credential new credential
     */
    public void rotate(final Credential credential) {
        CredentialSnapshot next = new CredentialSnapshot(credential, Instant.now());
        this.snapshot = next;
        for (CredentialRotationListener listener : this.listeners) {
            listener.rotated(next);
        }
    }
}
