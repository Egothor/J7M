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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.internal.BoundedBundleIO;

final class BundleSignatureSupport {
    private BundleSignatureSupport() {
    }

    static byte[] canonicalDigest(final ApplicationBundle bundle) {
        return canonicalDigest(bundle, ApplicationPlatformLimits.defaults());
    }

    static byte[] canonicalDigest(final ApplicationBundle bundle, final ApplicationPlatformLimits limits) {
        try {
            BoundedBundleIO.validateBundleFile(bundle.path(), limits);
            try (ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                List<ZipEntry> entries = BoundedBundleIO.validatedEntries(zip, limits);
                for (ZipEntry entry : entries) {
                    String name = BoundedBundleIO.normalizeEntryName(entry.getName(), entry.isDirectory(), limits);
                    if (entry.isDirectory() || J7mBundleReader.SIGNATURE_ENTRY.equals(name)) {
                        continue;
                    }
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (InputStream input = zip.getInputStream(entry)) {
                        BoundedBundleIO.copy(input, new java.io.OutputStream() {
                            @Override
                            public void write(final int value) {
                                digest.update((byte) value);
                            }

                            @Override
                            public void write(final byte[] bytes, final int offset, final int length) {
                                digest.update(bytes, offset, length);
                            }
                        }, limits.maxEntryExpandedBytes(), limits.ioBufferBytes(), null);
                    }
                    digest.update((byte) 0xff);
                }
                return digest.digest();
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "failed to compute bundle signature digest", java.util.Map.of(), null, null, false, false),
                    exception);
        }
    }
}
