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

package org.j7m.middleware.gradle;

import javax.inject.Inject;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;

/**
 * Typed Gradle extension for local J7M application packaging workflows.
 *
 * <p>The extension is project-local and contains only safe configuration:
 * manifest location, repository location, output bundle location, verification
 * profile, public signing key identifier, and an optional smoke-test service
 * name. It never stores private key material.</p>
 */
public abstract class J7mApplicationExtension {
    private final ConfigurableFileCollection libraries;
    private final ConfigurableFileCollection modules;
    private final ConfigurableFileCollection documentation;
    private final ConfigurableFileCollection configurationFiles;

    /**
     * Creates the extension and configures conventional defaults.
     *
     * @param objects Gradle object factory
     */
    @Inject
    public J7mApplicationExtension(final ObjectFactory objects) {
        this.libraries = objects.fileCollection();
        this.modules = objects.fileCollection();
        this.documentation = objects.fileCollection();
        this.configurationFiles = objects.fileCollection();
        this.getVerificationProfile().convention("development");
        this.getSigningKeyId().convention("local-dev-ed25519");
        this.getSmokeTestService().convention("");
    }

    /**
     * Manifest file to package.
     *
     * @return manifest file property
     */
    public abstract RegularFileProperty getManifest();

    /**
     * Local repository directory used by deploy/run/status tasks.
     *
     * @return repository directory property
     */
    public abstract DirectoryProperty getRepositoryDir();

    /**
     * Output {@code .j7m} bundle file.
     *
     * @return bundle file property
     */
    public abstract RegularFileProperty getBundleFile();

    /**
     * Additional classpath library JARs to place under {@code lib/}.
     *
     * @return mutable Gradle file collection evaluated at task execution time
     */
    public ConfigurableFileCollection getLibraries() {
        return this.libraries;
    }

    /**
     * Additional JPMS module JARs to place under {@code modules/}.
     *
     * @return mutable Gradle file collection evaluated at task execution time
     */
    public ConfigurableFileCollection getModules() {
        return this.modules;
    }

    /**
     * Documentation files to place under {@code docs/}.
     *
     * @return mutable Gradle file collection evaluated at task execution time
     */
    public ConfigurableFileCollection getDocumentation() {
        return this.documentation;
    }

    /**
     * Configuration files to place under {@code config/}.
     *
     * @return mutable Gradle file collection evaluated at task execution time
     */
    public ConfigurableFileCollection getConfigurationFiles() {
        return this.configurationFiles;
    }

    /**
     * Verification profile: {@code development}, {@code strict-production},
     * or {@code strict-regulated}.
     *
     * @return verification profile property
     */
    public abstract Property<String> getVerificationProfile();

    /**
     * Safe local signing key identifier.
     *
     * @return signing key identifier property
     */
    public abstract Property<String> getSigningKeyId();

    /**
     * Optional smoke-test service name for local run summaries.
     *
     * @return smoke-test service property
     */
    public abstract Property<String> getSmokeTestService();
}
