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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.internal.BoundedBundleIO;

/**
 * Reader for ZIP-compatible {@code .j7m} bundles.
 *
 * <p>The reader validates entry names against path traversal and reads only
 * metadata needed for verification. It does not load or execute application
 * classes. Instances are stateless and thread-safe.</p>
 */
public final class J7mBundleReader {
    /**
     * Canonical manifest path inside a bundle.
     */
    public static final String MANIFEST_ENTRY = "META-INF/j7m-application.json";
    /**
     * Canonical checksum path inside a bundle.
     */
    public static final String CHECKSUM_ENTRY = "META-INF/checksums.sha256";
    /**
     * Optional development provenance path.
     */
    public static final String PROVENANCE_ENTRY = "META-INF/j7m-provenance.json";
    /**
     * Optional SPDX SBOM path used by strict regulated verification.
     */
    public static final String SBOM_ENTRY = "META-INF/sbom.spdx.json";
    /**
     * Built-in bundle signature metadata path.
     */
    public static final String SIGNATURE_ENTRY = "META-INF/j7m-signature.json";

    private final ApplicationManifestParser parser;
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a reader.
     */
    public J7mBundleReader() {
        this(ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a reader with explicit bounded resource limits.
     *
     * @param limits bundle and metadata limits
     */
    public J7mBundleReader(final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.parser = new ApplicationManifestParser(this.limits);
    }

    /**
     * Reads a manifest from a bundle.
     *
     * @param bundle bundle to read
     * @return parsed application manifest
     */
    public ApplicationManifest readManifest(final ApplicationBundle bundle) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                BoundedBundleIO.validatedEntries(zip, this.limits);
                ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
                if (entry == null) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle manifest is missing");
                }
                return this.parser.parse(BoundedBundleIO.readEntryString(zip, entry, this.limits.maxManifestBytes(), this.limits));
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "failed to read application bundle", Map.of(), null, null, false, false), exception);
        }
    }

    /**
     * Reads checksum metadata from a bundle.
     *
     * @param bundle bundle to inspect
     * @return checksum manifest, or an empty manifest when the checksum entry is absent
     */
    public ChecksumManifest readChecksums(final ApplicationBundle bundle) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                BoundedBundleIO.validatedEntries(zip, this.limits);
                ZipEntry entry = zip.getEntry(CHECKSUM_ENTRY);
                if (entry == null) {
                    return new ChecksumManifest(Map.of());
                }
                String text = BoundedBundleIO.readEntryString(zip, entry, this.limits.maxChecksumBytes(), this.limits);
                Map<String, String> values = new LinkedHashMap<>();
                for (String line : text.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int space = trimmed.indexOf(' ');
                    if (space <= 0 || space == trimmed.length() - 1) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "malformed checksum line");
                    }
                    String digest = trimmed.substring(0, space).trim();
                    if (!digest.matches("[0-9a-f]{64}")) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "malformed checksum digest");
                    }
                    String name = BoundedBundleIO.normalizeEntryName(trimmed.substring(space + 1).trim(), false, this.limits);
                    if (values.putIfAbsent(name, digest) != null) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate checksum entry");
                    }
                }
                return new ChecksumManifest(values);
            }
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "failed to read bundle checksums", Map.of(), null, null, false, false), exception);
        }
    }

    /**
     * Reads signature metadata from a bundle.
     *
     * @param bundle bundle to inspect
     * @return signature metadata, or {@code null} when absent
     */
    public ApplicationSignature readSignature(final ApplicationBundle bundle) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                BoundedBundleIO.validatedEntries(zip, this.limits);
                ZipEntry entry = zip.getEntry(SIGNATURE_ENTRY);
                if (entry == null) {
                    return null;
                }
                return ApplicationSignature.parse(BoundedBundleIO.readEntryString(zip, entry, this.limits.maxSignatureBytes(), this.limits));
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "failed to read bundle signature", Map.of(), null, null, false, false), exception);
        }
    }

    /**
     * Tests whether a bundle entry exists.
     *
     * @param bundle bundle to inspect
     * @param entryName ZIP entry name
     * @return whether the entry exists
     */
    public boolean hasEntry(final ApplicationBundle bundle, final String entryName) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            String normalized = BoundedBundleIO.normalizeEntryName(entryName, false, this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                BoundedBundleIO.validatedEntries(zip, this.limits);
                return zip.getEntry(normalized) != null;
            }
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "failed to inspect bundle entry", Map.of(), null, null, false, false), exception);
        }
    }

    /**
     * Computes SHA-256 checksums for bundle entries excluding the checksum file.
     *
     * @param bundle bundle to inspect
     * @return computed checksum manifest
     */
    public ChecksumManifest computeChecksums(final ApplicationBundle bundle) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), this.limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                Map<String, String> values = new LinkedHashMap<>();
                List<ZipEntry> entries = BoundedBundleIO.validatedEntries(zip, this.limits);
                for (ZipEntry entry : entries) {
                    String name = BoundedBundleIO.normalizeEntryName(entry.getName(), entry.isDirectory(), this.limits);
                    if (!entry.isDirectory() && !CHECKSUM_ENTRY.equals(name) && !SIGNATURE_ENTRY.equals(name)) {
                        values.put(name, BoundedBundleIO.sha256(zip, entry, this.limits));
                    }
                }
                return new ChecksumManifest(values);
            }
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "failed to compute bundle checksums", Map.of(), null, null, false, false), exception);
        }
    }

    /**
     * Computes SHA-256 for a file.
     *
     * @param path file path
     * @return lowercase hexadecimal SHA-256 digest
     */
    public String sha256(final Path path) {
        return BoundedBundleIO.sha256(path, this.limits);
    }

    static String hex(final byte[] bytes) {
        return BoundedBundleIO.hex(bytes);
    }
}
