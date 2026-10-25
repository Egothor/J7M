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

/**
 * Security posture for the gRPC transport provider.
 *
 * <p>Phase 3.0 keeps production credential plumbing transport-local. The
 * default is explicit development insecure mode for compatibility with local
 * tests and demos. Production mode rejects insecure credentials unless the
 * caller sets an explicit override.</p>
 *
 * @param productionMode whether the transport is being started for production
 * @param allowInsecureProductionTransport explicit insecure production override
 * @param tlsConfiguration optional TLS configuration
 */
public record GrpcSecurityOptions(
        boolean productionMode,
        boolean allowInsecureProductionTransport,
        GrpcTlsConfiguration tlsConfiguration) {
    /**
     * Creates options without TLS material.
     *
     * @param productionMode whether production mode is enabled
     * @param allowInsecureProductionTransport explicit insecure production override
     */
    public GrpcSecurityOptions(final boolean productionMode, final boolean allowInsecureProductionTransport) {
        this(productionMode, allowInsecureProductionTransport, null);
    }

    /**
     * Development/test options that allow the local insecure channel.
     *
     * @return dev options
     */
    public static GrpcSecurityOptions development() {
        return new GrpcSecurityOptions(false, true, null);
    }

    /**
     * Production options that reject insecure transport.
     *
     * @return strict production options
     */
    public static GrpcSecurityOptions production() {
        return new GrpcSecurityOptions(true, false, null);
    }

    /**
     * Explicit production override for controlled tests or private lab use.
     *
     * @return overridden production options
     */
    public static GrpcSecurityOptions insecureProductionOverride() {
        return new GrpcSecurityOptions(true, true, null);
    }

    /**
     * Returns options for certificate-backed mTLS.
     *
     * @param productionMode whether production checks are enabled
     * @param tlsConfiguration TLS material
     * @return mTLS options
     */
    public static GrpcSecurityOptions mutualTls(final boolean productionMode, final GrpcTlsConfiguration tlsConfiguration) {
        return new GrpcSecurityOptions(productionMode, false, tlsConfiguration);
    }

    /**
     * Returns whether TLS is configured.
     *
     * @return true when TLS material is present
     */
    public boolean tlsEnabled() {
        return this.tlsConfiguration != null;
    }
}
