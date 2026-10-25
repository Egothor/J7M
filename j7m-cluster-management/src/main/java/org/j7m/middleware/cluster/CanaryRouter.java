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

package org.j7m.middleware.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Deterministic bounded identity routing; no request payload or identity is retained or exported.
 */
public final class CanaryRouter {
    private CanaryRouter() { }
    /**
     * Selects a release bucket from a scope, policy revision and stable request identity.
     * @param scope authenticated routing namespace
     * @param revision policy revision
     * @param requestId stable application identity, at most 512 characters
     * @return bucket in 0..9999
     * @throws IllegalArgumentException for invalid identities or revision
     */
    public static int bucket(String scope, long revision, String requestId) {
        CanaryPolicy.identity(scope);
        if (revision < 1 || requestId == null || requestId.isEmpty() || requestId.length() > 512)
            throw new IllegalArgumentException("Invalid routing identity");
        byte[] bytes = digest(scope + "\u0000" + revision + "\u0000" + requestId);
        return Math.floorMod(java.nio.ByteBuffer.wrap(bytes).getLong(), 10000);
    }
    /**
     * Selects a stable node from an already sorted, nonempty bounded eligible set.
     * @param scope authenticated routing namespace
     * @param revision policy revision
     * @param requestId stable application identity
     * @param eligible sorted node identities, 1..64 entries
     * @return selected node identity
     * @throws IllegalArgumentException for invalid routing inputs
     */
    public static String node(String scope, long revision, String requestId, List<String> eligible) {
        bucket(scope, revision, requestId);
        if (eligible.isEmpty() || eligible.size() > 64) throw new IllegalArgumentException("Invalid route capacity");
        byte[] bytes = digest(scope + "\u0000" + revision + "\u0000node\u0000" + requestId);
        return eligible.get(Math.floorMod(java.nio.ByteBuffer.wrap(bytes).getLong(), eligible.size()));
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
