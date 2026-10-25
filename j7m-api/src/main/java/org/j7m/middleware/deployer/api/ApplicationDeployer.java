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

package org.j7m.middleware.deployer.api;

import java.util.List;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;

/**
 * Transport-neutral deployer API for packaging, repository deployment, and live activation.
 *
 * <p>Implementations must wrap public failures in the common middleware error
 * model. This first vertical slice supports package, verify, deploy, plan,
 * activate, deactivate, unload, and list. Upgrade and rollback are represented
 * by explicit result types but may remain unsupported by an implementation.</p>
 */
public interface ApplicationDeployer {
    /**
     * Packages an application into a {@code .j7m} bundle without activating it.
     *
     * @param request package request
     * @return packaged bundle descriptor
     */
    ApplicationBundle packageApplication(PackageRequest request);

    /**
     * Verifies a bundle without executing application code.
     *
     * @param bundle bundle to verify
     * @param options verification options
     * @return structured verification result
     */
    ApplicationVerificationResult verify(ApplicationBundle bundle, VerificationOptions options);

    /**
     * Deploys a verified bundle into the configured repository.
     *
     * @param bundle bundle to deploy
     * @param options deployment options
     * @return deployment result
     */
    ApplicationDeploymentResult deploy(ApplicationBundle bundle, DeployOptions options);

    /**
     * Creates an activation plan for an exact coordinate.
     *
     * @param coordinate application coordinate to activate
     * @param options activation planning options
     * @return activation plan
     */
    ApplicationDeploymentPlan planActivation(ApplicationCoordinate coordinate, ActivationOptions options);

    /**
     * Activates an application in the target runtime.
     *
     * @param coordinate application coordinate to activate
     * @param request activation request
     * @return activation snapshot
     */
    ApplicationActivation activate(ApplicationCoordinate coordinate, ApplicationActivationRequest request);

    /**
     * Deactivates a running application instance.
     *
     * @param instanceId active instance id
     * @param options deactivation options
     * @return deactivation result
     */
    ApplicationDeploymentResult deactivate(ApplicationInstanceId instanceId, DeactivationOptions options);

    /**
     * Drains a running application instance without stopping or unloading it.
     *
     * <p>Implementations that support live application lifecycle operations
     * must make this a distinct lifecycle transition rather than an alias for
     * {@link #deactivate(ApplicationInstanceId, DeactivationOptions)}. A
     * successful drain stops accepting new application invocations, marks
     * readiness false where the implementation exposes readiness, invokes the
     * application drain hook, preserves exported-service cleanup diagnostics,
     * and leaves the instance owned by the manager for a later stop or unload
     * sequence. Calling this method for an already draining instance is
     * idempotent.</p>
     *
     * <p>The default implementation is provided for source compatibility with
     * older deployers. It reports truthful unavailability and performs no
     * lifecycle mutation.</p>
     *
     * @param instanceId active instance id
     * @param options deactivation options controlling future graceful behavior
     * @return drain result
     */
    default ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
        return new ApplicationDeploymentResult(false, ApplicationRunState.FAILED,
                "application drain is unavailable from this deployer", List.of("reason=unavailable"));
    }

    /**
     * Unloads a stopped application instance.
     *
     * @param instanceId stopped instance id
     * @param options unload options
     * @return unload result
     */
    ApplicationDeploymentResult unload(ApplicationInstanceId instanceId, UnloadOptions options);

    /**
     * Performs an upgrade to a target coordinate.
     *
     * @param instanceId active instance id
     * @param targetVersion target coordinate
     * @param options upgrade options
     * @return upgrade result
     */
    ApplicationDeploymentResult upgrade(ApplicationInstanceId instanceId, ApplicationCoordinate targetVersion, UpgradeOptions options);

    /**
     * Rolls an upgraded instance back to its recorded rollback candidate.
     *
     * @param instanceId active instance id
     * @param options rollback options
     * @return rollback result
     */
    ApplicationDeploymentResult rollback(ApplicationInstanceId instanceId, RollbackOptions options);

    /**
     * Returns the current activation snapshot for one active application
     * instance.
     *
     * <p>This method is an exact lookup contract for administrative lifecycle
     * operations that need one instance state without allocating or scanning a
     * complete active-application listing. Implementations that do not support
     * exact lookup should return {@code null}; callers must treat a
     * {@code null} value as unknown state rather than as authorization or
     * lifecycle success. The default implementation is provided for source
     * compatibility with older deployers and performs no repository or runtime
     * mutation.</p>
     *
     * @param instanceId application instance id
     * @return activation snapshot, or {@code null} when unavailable or unknown
     */
    default ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
        return null;
    }

    /**
     * Lists active applications matching a query.
     *
     * @param query application query
     * @return matching active application snapshots
     */
    List<ApplicationActivation> listActiveApplications(ApplicationQuery query);
}
