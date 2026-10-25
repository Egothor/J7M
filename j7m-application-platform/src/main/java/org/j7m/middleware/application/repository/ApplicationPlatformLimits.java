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

package org.j7m.middleware.application.repository;

/**
 * Bounded resource policy for local application-platform bundle, repository,
 * metadata, and replication operations.
 *
 * <p>Instances are immutable and thread-safe. The default policy is intended
 * for local production acceptance paths: it is large enough for ordinary
 * application bundles, but every caller-controlled byte stream, ZIP entry,
 * metadata document, audit page, and chunked transfer remains bounded. The
 * canonical constructor validates all values against hard implementation
 * ceilings so that integer conversions and aggregate counters remain safe.</p>
 *
 * @param maxBundleBytes maximum bytes in one {@code .j7m} artifact file
 * @param maxCompressedBytes maximum aggregate compressed bytes in a bundle
 * @param maxEntryExpandedBytes maximum expanded bytes for one bundle entry
 * @param maxAggregateExpandedBytes maximum aggregate expanded bytes in a bundle
 * @param maxMetadataBytes maximum bytes for non-specific metadata entries
 * @param maxManifestBytes maximum bytes for {@code j7m-application.json}
 * @param maxChecksumBytes maximum bytes for {@code checksums.sha256}
 * @param maxSignatureBytes maximum bytes for {@code j7m-signature.json}
 * @param maxEntryCount maximum ZIP entries in one bundle
 * @param maxEntryNameBytes maximum UTF-8 bytes in one ZIP entry name
 * @param maxEntryPathDepth maximum normalized path segment count for an entry
 * @param maxCompressionRatio maximum expanded-to-compressed ratio for one entry
 * @param ioBufferBytes fixed buffer size used by streaming copy and hashing
 * @param maxAuditEventsPerPage maximum repository audit lines returned by one page
 * @param maxAuditLineBytes maximum UTF-8 bytes in one audit JSON line
 * @param maxReplicationChunks maximum chunks accepted by compatibility chunk APIs
 */
public record ApplicationPlatformLimits(
        long maxBundleBytes,
        long maxCompressedBytes,
        long maxEntryExpandedBytes,
        long maxAggregateExpandedBytes,
        long maxMetadataBytes,
        long maxManifestBytes,
        long maxChecksumBytes,
        long maxSignatureBytes,
        int maxEntryCount,
        int maxEntryNameBytes,
        int maxEntryPathDepth,
        int maxCompressionRatio,
        int ioBufferBytes,
        int maxAuditEventsPerPage,
        int maxAuditLineBytes,
        int maxReplicationChunks) {
    /**
     * Hard implementation ceiling for one bundle artifact.
     */
    public static final long HARD_MAX_BUNDLE_BYTES = 2L * 1024L * 1024L * 1024L;
    /**
     * Hard implementation ceiling for one expanded ZIP entry.
     */
    public static final long HARD_MAX_ENTRY_EXPANDED_BYTES = 512L * 1024L * 1024L;
    /**
     * Hard implementation ceiling for aggregate expanded ZIP data.
     */
    public static final long HARD_MAX_AGGREGATE_EXPANDED_BYTES = 4L * 1024L * 1024L * 1024L;
    /**
     * Hard implementation ceiling for ZIP entry count.
     */
    public static final int HARD_MAX_ENTRY_COUNT = 65_536;
    /**
     * Hard implementation ceiling for metadata materialized as text.
     */
    public static final long HARD_MAX_METADATA_BYTES = 16L * 1024L * 1024L;
    /**
     * Standard streaming buffer size.
     */
    public static final int STANDARD_IO_BUFFER_BYTES = 64 * 1024;

    /**
     * Creates and validates immutable limits.
     *
     * @throws IllegalArgumentException if any value is non-positive where a
     * positive value is required, exceeds a hard ceiling, or cannot be used
     * safely for bounded I/O accounting
     */
    public ApplicationPlatformLimits {
        requireRange("maxBundleBytes", maxBundleBytes, 1L, HARD_MAX_BUNDLE_BYTES);
        requireRange("maxCompressedBytes", maxCompressedBytes, 1L, HARD_MAX_BUNDLE_BYTES);
        requireRange("maxEntryExpandedBytes", maxEntryExpandedBytes, 1L, HARD_MAX_ENTRY_EXPANDED_BYTES);
        requireRange("maxAggregateExpandedBytes", maxAggregateExpandedBytes, 1L, HARD_MAX_AGGREGATE_EXPANDED_BYTES);
        requireRange("maxMetadataBytes", maxMetadataBytes, 1L, HARD_MAX_METADATA_BYTES);
        requireRange("maxManifestBytes", maxManifestBytes, 1L, HARD_MAX_METADATA_BYTES);
        requireRange("maxChecksumBytes", maxChecksumBytes, 1L, HARD_MAX_METADATA_BYTES);
        requireRange("maxSignatureBytes", maxSignatureBytes, 1L, HARD_MAX_METADATA_BYTES);
        requireRange("maxEntryCount", maxEntryCount, 1, HARD_MAX_ENTRY_COUNT);
        requireRange("maxEntryNameBytes", maxEntryNameBytes, 1, 4096);
        requireRange("maxEntryPathDepth", maxEntryPathDepth, 1, 128);
        requireRange("maxCompressionRatio", maxCompressionRatio, 1, 10_000);
        requireRange("ioBufferBytes", ioBufferBytes, 1024, STANDARD_IO_BUFFER_BYTES);
        requireRange("maxAuditEventsPerPage", maxAuditEventsPerPage, 1, 10_000);
        requireRange("maxAuditLineBytes", maxAuditLineBytes, 128, 1024 * 1024);
        requireRange("maxReplicationChunks", maxReplicationChunks, 1, 1_000_000);
        if (maxEntryExpandedBytes > maxAggregateExpandedBytes) {
            throw new IllegalArgumentException("maxEntryExpandedBytes must not exceed maxAggregateExpandedBytes");
        }
        if (maxManifestBytes > maxMetadataBytes || maxChecksumBytes > maxMetadataBytes || maxSignatureBytes > maxMetadataBytes) {
            throw new IllegalArgumentException("metadata-specific limits must not exceed maxMetadataBytes");
        }
    }

    /**
     * Returns conservative production defaults for local application-platform
     * APIs.
     *
     * @return immutable default limits
     */
    public static ApplicationPlatformLimits defaults() {
        return new ApplicationPlatformLimits(
                512L * 1024L * 1024L,
                512L * 1024L * 1024L,
                128L * 1024L * 1024L,
                1024L * 1024L * 1024L,
                4L * 1024L * 1024L,
                1024L * 1024L,
                2L * 1024L * 1024L,
                64L * 1024L,
                4096,
                240,
                16,
                100,
                STANDARD_IO_BUFFER_BYTES,
                256,
                64 * 1024,
                8192);
    }

    private static void requireRange(final String name, final long value, final long minimum, final long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be in range [" + minimum + ", " + maximum + "]");
        }
    }

    private static void requireRange(final String name, final int value, final int minimum, final int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be in range [" + minimum + ", " + maximum + "]");
        }
    }
}
