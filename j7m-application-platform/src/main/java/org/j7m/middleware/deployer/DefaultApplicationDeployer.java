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

package org.j7m.middleware.deployer;

import java.util.List;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRepository;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.deployer.api.ActivationOptions;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.PackageRequest;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;

/**
 * Default deployer facade for the first application-platform vertical slice.
 *
 * <p>The deployer is thread-safe when the supplied repository and application
 * manager are thread-safe.</p>
 */
public final class DefaultApplicationDeployer implements ApplicationDeployer {
    private final ApplicationRepository repository;
    private final ApplicationManager applicationManager;
    private final J7mBundleWriter bundleWriter;
    private final ApplicationBundleVerifier verifier;

    /**
     * Creates a deployer facade.
     *
     * @param repository application repository used for deploy and fetch operations
     * @param applicationManager application manager used for live lifecycle operations
     */
    public DefaultApplicationDeployer(final ApplicationRepository repository, final ApplicationManager applicationManager) {
        this.repository = repository;
        this.applicationManager = applicationManager;
        this.bundleWriter = new J7mBundleWriter();
        this.verifier = new ApplicationBundleVerifier();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationBundle packageApplication(final PackageRequest request) {
        return this.bundleWriter.write(request.manifestPath(), request.outputBundle(), request.libraries(), request.docs());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationVerificationResult verify(final ApplicationBundle bundle, final VerificationOptions options) {
        VerificationOptions safeOptions = options == null ? VerificationOptions.development() : options;
        return this.verifier.verify(bundle, ApplicationBundleVerifier.policy(
                safeOptions.verificationProfile(), safeOptions.strictSignatureRequired()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult deploy(final ApplicationBundle bundle, final DeployOptions options) {
        DeployOptions safeOptions = options == null ? new DeployOptions("system", false) : options;
        return this.repository.publish(bundle, new RepositoryPublishOptions(safeOptions.publisher(),
                safeOptions.strictVerification(), safeOptions.strictVerification() ? "strict-production" : "development"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentPlan planActivation(final ApplicationCoordinate coordinate, final ActivationOptions options) {
        ActivationOptions safeOptions = options == null ? new ActivationOptions(false) : options;
        return this.applicationManager.planActivation(coordinate, safeOptions.strictVerification(), safeOptions.configuration());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationActivation activate(final ApplicationCoordinate coordinate, final ApplicationActivationRequest request) {
        return this.applicationManager.activate(coordinate, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult deactivate(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
        return this.applicationManager.deactivate(instanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult drain(final ApplicationInstanceId instanceId, final DeactivationOptions options) {
        return this.applicationManager.drain(instanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult unload(final ApplicationInstanceId instanceId, final UnloadOptions options) {
        return this.applicationManager.unload(instanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult upgrade(final ApplicationInstanceId instanceId, final ApplicationCoordinate targetVersion, final UpgradeOptions options) {
        UpgradeOptions safeOptions = options == null ? UpgradeOptions.stopStart() : options;
        return this.applicationManager.upgrade(instanceId, targetVersion, safeOptions,
                new ApplicationActivationRequest("upgrade", "dev", false, java.util.Map.of()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationDeploymentResult rollback(final ApplicationInstanceId instanceId, final RollbackOptions options) {
        return this.applicationManager.rollback(instanceId, new ApplicationActivationRequest("rollback", "dev", false, java.util.Map.of()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ApplicationActivation activeApplication(final ApplicationInstanceId instanceId) {
        return this.applicationManager.active(instanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ApplicationActivation> listActiveApplications(final ApplicationQuery query) {
        return this.applicationManager.list(query);
    }
}
