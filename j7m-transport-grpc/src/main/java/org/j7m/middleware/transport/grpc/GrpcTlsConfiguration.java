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

package org.j7m.middleware.transport.grpc;

import java.nio.file.Path;
import java.util.Objects;

/**
 * File-backed TLS material for the generic gRPC transport.
 *
 * <p>The configuration is immutable and thread-safe. The provider reads the
 * referenced files when the server or a client channel is created. Private key
 * bytes are never exposed through status, errors, or audit metadata.</p>
 *
 * @param certificateChain PEM certificate chain for this runtime
 * @param privateKey PEM private key for this runtime
 * @param trustedCertificates PEM trust roots or peer certificates
 * @param requireClientAuthentication whether the server requires client certs
 * @param trustDomain trust domain to attach to authenticated peer contexts
 * @param authorityOverride optional TLS authority override for loopback tests
 */
public record GrpcTlsConfiguration(
        Path certificateChain,
        Path privateKey,
        Path trustedCertificates,
        boolean requireClientAuthentication,
        String trustDomain,
        String authorityOverride) {
    /**
     * Creates a TLS configuration.
     */
    public GrpcTlsConfiguration {
        certificateChain = Objects.requireNonNull(certificateChain, "certificateChain must not be null").toAbsolutePath();
        privateKey = Objects.requireNonNull(privateKey, "privateKey must not be null").toAbsolutePath();
        trustedCertificates = Objects.requireNonNull(trustedCertificates, "trustedCertificates must not be null").toAbsolutePath();
        trustDomain = trustDomain == null || trustDomain.isBlank() ? "default" : trustDomain;
        authorityOverride = authorityOverride == null ? "" : authorityOverride;
    }

    /**
     * Creates a TLS configuration without an authority override.
     *
     * @param certificateChain PEM certificate chain
     * @param privateKey PEM private key
     * @param trustedCertificates PEM trust roots or peer certificates
     * @param requireClientAuthentication whether server requires client certs
     * @param trustDomain trust domain
     */
    public GrpcTlsConfiguration(
            final Path certificateChain,
            final Path privateKey,
            final Path trustedCertificates,
            final boolean requireClientAuthentication,
            final String trustDomain) {
        this(certificateChain, privateKey, trustedCertificates, requireClientAuthentication, trustDomain, "");
    }
}
