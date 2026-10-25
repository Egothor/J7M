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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.internal.BoundedBundleIO;

/**
 * JDK Ed25519 bundle signer for local development and repository tests.
 *
 * <p>The signer computes a deterministic SHA-256 digest over sorted ZIP entry
 * names and entry bytes, excluding {@code META-INF/j7m-signature.json}. It then
 * signs that digest and rewrites the bundle with signature metadata. The class
 * is stateless and thread-safe.</p>
 */
public final class DefaultBundleSigner implements BundleSigner {
    private final SigningKeyPair keyPair;
    private final Clock clock;
    private final J7mBundleReader reader;
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a signer using deterministic local development signing material.
     */
    public DefaultBundleSigner() {
        this(SigningKeyPair.localDevelopment(), Clock.systemUTC());
    }

    /**
     * Creates a signer.
     *
     * @param keyPair signing key pair
     * @param clock clock for signature creation timestamps
     */
    public DefaultBundleSigner(final SigningKeyPair keyPair, final Clock clock) {
        this(keyPair, clock, ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a signer with explicit bounded resource limits.
     *
     * @param keyPair signing key pair
     * @param clock clock for signature creation timestamps
     * @param limits bundle and metadata limits
     */
    public DefaultBundleSigner(
            final SigningKeyPair keyPair,
            final Clock clock,
            final ApplicationPlatformLimits limits) {
        if (keyPair == null) {
            throw new IllegalArgumentException("key pair is required");
        }
        this.keyPair = keyPair;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.reader = new J7mBundleReader(this.limits);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationBundle sign(final ApplicationBundle bundle) {
        try {
            byte[] digest = BundleSignatureSupport.canonicalDigest(bundle, this.limits);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(this.keyPair.privateKey());
            signer.update(digest);
            ApplicationSignature signature = new ApplicationSignature(
                    ApplicationSignature.SCHEMA_VERSION,
                    "Ed25519",
                    this.keyPair.keyId().value(),
                    this.keyPair.signerId(),
                    this.clock.instant(),
                    "SHA-256",
                    J7mBundleReader.hex(digest),
                    Base64.getEncoder().encodeToString(signer.sign()));
            this.rewriteWithSignature(bundle, signature);
            return new ApplicationBundle(bundle.path(), bundle.coordinate(), this.reader.sha256(bundle.path()));
        } catch (GeneralSecurityException | IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "failed to sign application bundle", Map.of(), null, null, false, true), exception);
        }
    }

    private void rewriteWithSignature(final ApplicationBundle bundle, final ApplicationSignature signature) throws IOException {
        Path source = bundle.path();
        Path directory = source.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            throw BoundedBundleIO.failure("bundle directory is required");
        }
        Path temporary = Files.createTempFile(directory, "j7m-sign-", ".tmp");
        boolean moved = false;
        try {
            BoundedBundleIO.validateBundleFile(source, this.limits);
            try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
                    ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
                List<ZipEntry> entries = BoundedBundleIO.validatedEntries(zip, this.limits);
                for (ZipEntry entry : entries) {
                    String name = BoundedBundleIO.normalizeEntryName(entry.getName(), entry.isDirectory(), this.limits);
                    if (entry.isDirectory() || J7mBundleReader.SIGNATURE_ENTRY.equals(name)) {
                        continue;
                    }
                    ZipEntry outputEntry = new ZipEntry(name);
                    outputEntry.setTime(0L);
                    output.putNextEntry(outputEntry);
                    try (java.io.InputStream input = zip.getInputStream(entry)) {
                        BoundedBundleIO.copy(input, output, this.limits.maxEntryExpandedBytes(), this.limits.ioBufferBytes(), null);
                    }
                    output.closeEntry();
                }
                byte[] signatureBytes = signature.toJson().getBytes(StandardCharsets.UTF_8);
                if (signatureBytes.length > this.limits.maxSignatureBytes()) {
                    throw BoundedBundleIO.failure("bundle signature metadata exceeds configured limit");
                }
                ZipEntry signatureEntry = new ZipEntry(J7mBundleReader.SIGNATURE_ENTRY);
                signatureEntry.setTime(0L);
                output.putNextEntry(signatureEntry);
                output.write(signatureBytes);
                output.closeEntry();
            }
            this.forceFile(temporary);
            try {
                Files.move(temporary, source, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            this.forceDirectory(directory);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(final Path directory) {
        try (FileChannel channel = FileChannel.open(directory, java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException exception) {
            ignoreBestEffortIoFailure(exception);
        }
    }

    private static void ignoreBestEffortIoFailure(final IOException exception) {
        Objects.requireNonNull(exception, "exception");
    }
}
