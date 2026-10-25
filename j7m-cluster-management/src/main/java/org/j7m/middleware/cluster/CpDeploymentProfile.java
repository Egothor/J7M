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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.j7m.middleware.consensus.NetworkConsensusConfig;

/**
 * Versioned CP bootstrap profiles for Linux/Java25 development qualification.
 * The roster is explicit and contains exactly the authorized voters, without discovery or learners.
 * Local durable storage must support fsync and atomic rename. Recovery and clock values describe
 * qualification assumptions, not measured runtime guarantees or an accepted production SLO.
 * A profile does not create directories, provision credentials, start nodes or change existing voters.
 */
public enum CpDeploymentProfile {
    /**
     * Version1: three voters, tolerating one unavailable voter while retaining a majority.
     */
    DEVELOPMENT_LINUX_JAVA25_CP3_V1(3),
    /**
     * Version1: five voters, tolerating two unavailable voters while retaining a majority.
     */
    DEVELOPMENT_LINUX_JAVA25_CP5_V1(5);

    private final int voters;
    CpDeploymentProfile(int voters) { this.voters = voters; }

    /**
     * Returns the exact authorized voter count.
     * @return three or five
     */
    public int voters() { return voters; }

    /**
     * Returns the maximum assumed measured clock uncertainty.
     * Hosts must use the production measured-time admission checks separately.
     * @return 500 milliseconds
     */
    public Duration clockUncertaintyAssumption() { return Duration.ofMillis(500); }

    /**
     * Returns the qualification recovery bound after sufficient voters return.
     * @return 30 seconds; this is not a runtime SLO promise
     */
    public Duration recoveryAssumption() { return Duration.ofSeconds(30); }

    /**
     * Builds immutable settings for one explicitly authorized voter, including restart settings.
     * The caller owns an exclusive per-node directory and supplies protected credentials. An existing
     * directory is permitted for normal restart; the node validates its durable generation/ownership.
     * Non-loopback endpoints require caller-provisioned mutual TLS; null TLS is loopback-only.
     * Distinct replication groups and fresh recovery generations require explicit distinct group IDs.
     * @param nodeId local authorized voter identity
     * @param authorizedVoters exact fixed voter-to-endpoint roster; no learners or implicit discovery
     * @param directory exclusively owned durable per-node directory, fresh or existing for restart
     * @param authenticationKey caller-provisioned secret of at least32 bytes, defensively copied
     * @param tlsContext mutual TLS context, or null for loopback development only
     * @param groupId explicit stable consensus generation identity
     * @return validated configuration with500ms election,20ms heartbeat,1MiB frames and128MiB snapshots
     * @throws IllegalArgumentException for a wrong roster, invalid endpoint or unsafe configuration
     * @throws NullPointerException for required null configuration fields
     */
    public NetworkConsensusConfig configuration(String nodeId, Map<String, InetSocketAddress> authorizedVoters,
            Path directory, byte[] authenticationKey, SSLContext tlsContext, String groupId) {
        if (authorizedVoters.size() != voters) throw new IllegalArgumentException("Profile requires exactly " + voters + " authorized voters");
        var endpoints = new HashSet<InetSocketAddress>();
        for (var entry : authorizedVoters.entrySet()) {
            String id = entry.getKey(); InetSocketAddress endpoint = entry.getValue();
            if (id == null || id.isBlank() || id.length() > 256 || id.indexOf('\0') >= 0 || endpoint == null
                    || endpoint.getPort() == 0 || !endpoints.add(endpoint)) throw new IllegalArgumentException("Invalid or duplicate authorized voter endpoint");
        }
        return new NetworkConsensusConfig(nodeId, authorizedVoters, authorizedVoters.keySet(), directory,
                authenticationKey, Duration.ofMillis(500), Duration.ofMillis(20), 1_048_576,
                134_217_728, tlsContext, groupId);
    }
}
