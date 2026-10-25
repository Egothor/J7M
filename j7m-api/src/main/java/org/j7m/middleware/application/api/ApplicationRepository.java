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

package org.j7m.middleware.application.api;

import java.util.List;

/**
 * Application repository contract.
 *
 * <p>Implementations must verify checksums before accepting bundles and must
 * reject overwriting immutable artifacts.</p>
 */
public interface ApplicationRepository {
    /**
     * Publishes a bundle under its exact application coordinate.
     *
     * @param bundle bundle to publish
     * @param options repository publish options
     * @return publish result with safe diagnostic messages
     */
    ApplicationDeploymentResult publish(ApplicationBundle bundle, RepositoryPublishOptions options);

    /**
     * Fetches a bundle by exact coordinate.
     *
     * @param coordinate exact application coordinate
     * @return immutable bundle descriptor
     */
    ApplicationBundle fetch(ApplicationCoordinate coordinate);

    /**
     * Lists immutable versions known for the supplied application id.
     *
     * @param applicationId application id to query
     * @return versions sorted by repository implementation policy
     */
    List<ApplicationVersion> listVersions(ApplicationId applicationId);

    /**
     * Verifies the stored bundle for an exact coordinate.
     *
     * @param coordinate exact application coordinate
     * @return structured verification result
     */
    ApplicationVerificationResult verify(ApplicationCoordinate coordinate);

    /**
     * Promotes a coordinate to an environment marker.
     *
     * @param coordinate exact application coordinate
     * @param environment target environment name
     * @return promotion result
     */
    ApplicationDeploymentResult promote(ApplicationCoordinate coordinate, String environment);

    /**
     * Removes a coordinate promotion from an environment marker.
     *
     * @param coordinate exact application coordinate
     * @param environment environment name
     * @return demotion result
     */
    ApplicationDeploymentResult demote(ApplicationCoordinate coordinate, String environment);

    /**
     * Returns a safe status snapshot for this repository.
     *
     * @return repository status
     */
    ApplicationRepositoryStatus status();
}
