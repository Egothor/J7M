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

package org.j7m.middleware.application.api.replication;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Bounded artifact transfer chunk.
 *
 * @param transferId stable transfer identifier
 * @param digest artifact digest descriptor
 * @param sequence zero-based chunk sequence
 * @param totalChunks total chunk count
 * @param chunkDigest SHA-256 digest of this chunk payload
 * @param bytes chunk bytes, defensively copied
 */
public record RepositoryArtifactChunk(
        String transferId,
        RepositoryArtifactDigest digest,
        int sequence,
        int totalChunks,
        String chunkDigest,
        byte[] bytes) implements Serializable {
    /**
     * Creates a validated immutable chunk.
     */
    public RepositoryArtifactChunk {
        if (digest == null) {
            throw new IllegalArgumentException("artifact digest is required");
        }
        transferId = transferId == null || transferId.isBlank() ? "transfer-" + digest.coordinate() : transferId.trim();
        if (sequence < 0 || totalChunks <= 0 || sequence >= totalChunks) {
            throw new IllegalArgumentException("invalid chunk sequence");
        }
        bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
        chunkDigest = chunkDigest == null || chunkDigest.isBlank() ? "" : chunkDigest.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Compatibility constructor for callers that do not provide transfer or
     * chunk-digest metadata.
     *
     * @param digest artifact digest descriptor
     * @param sequence zero-based chunk sequence
     * @param totalChunks total chunk count
     * @param bytes chunk bytes
     */
    public RepositoryArtifactChunk(
            final RepositoryArtifactDigest digest,
            final int sequence,
            final int totalChunks,
            final byte[] bytes) {
        this("transfer-" + (digest == null ? "unknown" : digest.coordinate()), digest, sequence, totalChunks, "", bytes);
    }

    /**
     * Returns a defensive copy of the chunk bytes.
     *
     * @return chunk bytes
     */
    @Override
    public byte[] bytes() {
        return Arrays.copyOf(this.bytes, this.bytes.length);
    }
}
