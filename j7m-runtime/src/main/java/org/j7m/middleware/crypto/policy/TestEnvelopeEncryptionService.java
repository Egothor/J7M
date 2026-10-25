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

package org.j7m.middleware.crypto.policy;

import java.util.Arrays;

/**
 * Deterministic reversible envelope encryption fixture for tests and demos.
 *
 * <p>This is not cryptographic protection. It exists only to prove that the
 * extension point can be exercised without exposing key material.</p>
 */
public final class TestEnvelopeEncryptionService implements EnvelopeEncryptionService {
    /**
     * Creates a deterministic envelope encryption fixture.
     */
    public TestEnvelopeEncryptionService() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] encrypt(final byte[] plaintext, final KeyReference keyReference) {
        byte[] input = plaintext == null ? new byte[0] : Arrays.copyOf(plaintext, plaintext.length);
        reverse(input);
        return input;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] decrypt(final byte[] ciphertext, final KeyReference keyReference) {
        byte[] input = ciphertext == null ? new byte[0] : Arrays.copyOf(ciphertext, ciphertext.length);
        reverse(input);
        return input;
    }

    private static void reverse(final byte[] value) {
        int left = 0;
        int right = value.length - 1;
        while (left < right) {
            byte item = value[left];
            value[left] = value[right];
            value[right] = item;
            left++;
            right--;
        }
    }
}
