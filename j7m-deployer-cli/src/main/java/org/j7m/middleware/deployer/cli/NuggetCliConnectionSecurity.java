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

package org.j7m.middleware.deployer.cli;

/**
 * Describes trusted security properties of an established nugget CLI
 * connection.
 *
 * <p>The descriptor is created by the installed client provider after it has
 * established or otherwise resolved the actual connector. It is not derived from
 * unauthenticated discovery metadata. The descriptor contains only safe
 * classifications and never exposes certificates, keys, tokens, endpoints,
 * transport handles, or implementation internals.</p>
 *
 * @param locality connection locality classification
 * @param confidentiality confidentiality classification
 * @param peerAuthentication peer authentication classification
 * @param integrity integrity-protection classification
 * @param profile safe transport security profile identifier
 * @param credentialSubmissionAllowed whether clear PIN submission is allowed by
 *        the provider under the active security policy
 */
public record NuggetCliConnectionSecurity(
        Locality locality,
        Confidentiality confidentiality,
        PeerAuthentication peerAuthentication,
        Integrity integrity,
        String profile,
        boolean credentialSubmissionAllowed) {
    /**
     * Creates a connection security descriptor.
     *
     * @param locality connection locality
     * @param confidentiality confidentiality state
     * @param peerAuthentication peer authentication state
     * @param integrity integrity state
     * @param profile safe profile identifier
     * @param credentialSubmissionAllowed whether PIN submission is allowed
     */
    public NuggetCliConnectionSecurity {
        if (locality == null) {
            locality = Locality.UNKNOWN;
        }
        if (confidentiality == null) {
            confidentiality = Confidentiality.UNKNOWN;
        }
        if (peerAuthentication == null) {
            peerAuthentication = PeerAuthentication.UNKNOWN;
        }
        if (integrity == null) {
            integrity = Integrity.UNKNOWN;
        }
        profile = safeProfile(profile);
    }

    /**
     * Returns a descriptor for an explicitly trusted local connector.
     *
     * @return trusted local connection security
     */
    public static NuggetCliConnectionSecurity trustedLocal() {
        return new NuggetCliConnectionSecurity(
                Locality.TRUSTED_LOCAL,
                Confidentiality.CONFIDENTIAL,
                PeerAuthentication.AUTHENTICATED,
                Integrity.PROTECTED,
                "trusted-local",
                true);
    }

    /**
     * Returns a descriptor for a confidential authenticated remote connector.
     *
     * @param profile safe provider profile identifier
     * @return secure remote connection security
     */
    public static NuggetCliConnectionSecurity secureRemote(final String profile) {
        return new NuggetCliConnectionSecurity(
                Locality.REMOTE,
                Confidentiality.CONFIDENTIAL,
                PeerAuthentication.AUTHENTICATED,
                Integrity.PROTECTED,
                profile,
                true);
    }

    /**
     * Returns a descriptor for an insecure or unknown connector.
     *
     * @return insecure connection security
     */
    public static NuggetCliConnectionSecurity insecure() {
        return new NuggetCliConnectionSecurity(
                Locality.UNKNOWN,
                Confidentiality.NOT_CONFIDENTIAL,
                PeerAuthentication.UNKNOWN,
                Integrity.UNKNOWN,
                "insecure",
                false);
    }

    boolean permitsCredentialSubmission() {
        if (!this.credentialSubmissionAllowed) {
            return false;
        }
        if (this.locality == Locality.TRUSTED_LOCAL) {
            return this.confidentiality == Confidentiality.CONFIDENTIAL
                    && this.integrity == Integrity.PROTECTED;
        }
        return this.confidentiality == Confidentiality.CONFIDENTIAL
                && this.peerAuthentication == PeerAuthentication.AUTHENTICATED
                && this.integrity == Integrity.PROTECTED;
    }

    private static String safeProfile(final String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(Math.min(32, value.length()));
        for (int index = 0; index < value.length() && builder.length() < 32; index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                builder.append(Character.toLowerCase(character));
            } else if (character == '-' || character == '_') {
                builder.append(character);
            }
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }

    /**
     * Connection locality.
     */
    public enum Locality {
        /**
         * Explicitly trusted local connector.
         */
        TRUSTED_LOCAL,

        /**
         * Remote connector.
         */
        REMOTE,

        /**
         * Locality cannot be proven.
         */
        UNKNOWN
    }

    /**
     * Confidentiality state.
     */
    public enum Confidentiality {
        /**
         * Connection protects credential confidentiality.
         */
        CONFIDENTIAL,

        /**
         * Connection does not protect credential confidentiality.
         */
        NOT_CONFIDENTIAL,

        /**
         * Confidentiality cannot be proven.
         */
        UNKNOWN
    }

    /**
     * Peer-authentication state.
     */
    public enum PeerAuthentication {
        /**
         * Peer identity is authenticated by the connector.
         */
        AUTHENTICATED,

        /**
         * Peer identity is not authenticated.
         */
        UNAUTHENTICATED,

        /**
         * Peer authentication cannot be proven.
         */
        UNKNOWN
    }

    /**
     * Integrity protection state.
     */
    public enum Integrity {
        /**
         * Connection protects message integrity.
         */
        PROTECTED,

        /**
         * Connection does not protect message integrity.
         */
        UNPROTECTED,

        /**
         * Integrity protection cannot be proven.
         */
        UNKNOWN
    }
}
