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
import java.util.Map;

/**
 * Immutable parsed application manifest.
 *
 * <p>The manifest is data only. Parsing and validation must not load or
 * execute application code.</p>
 *
 * @param schemaVersion manifest schema version
 * @param identity application identity
 * @param minJ7mVersion minimum J7M version
 * @param javaVersion required Java major version
 * @param moduleMode runtime module mode
 * @param bootstrapClass bootstrap {@link J7mApplication} class
 * @param bootstrapModule bootstrap module for JPMS mode
 * @param modules declared JPMS modules
 * @param services zero or more declared exported services; background-only applications still require a bootstrap
 * @param security security metadata
 * @param resources resource metadata
 */
public record ApplicationManifest(
        String schemaVersion,
        ApplicationIdentity identity,
        String minJ7mVersion,
        String javaVersion,
        String moduleMode,
        String bootstrapClass,
        String bootstrapModule,
        List<ModuleDeclaration> modules,
        List<ServiceDeclaration> services,
        SecurityDeclaration security,
        ResourceDeclaration resources) {
    /**
     * Creates immutable manifest data.
     */
    public ApplicationManifest {
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
        minJ7mVersion = minJ7mVersion == null ? "" : minJ7mVersion;
        javaVersion = javaVersion == null ? "" : javaVersion;
        moduleMode = moduleMode == null ? "CLASSPATH" : moduleMode;
        bootstrapClass = bootstrapClass == null ? "" : bootstrapClass;
        bootstrapModule = bootstrapModule == null ? "" : bootstrapModule;
        modules = modules == null ? List.of() : List.copyOf(modules);
        services = services == null ? List.of() : List.copyOf(services);
        security = security == null ? new SecurityDeclaration("default", false, Map.of()) : security;
        resources = resources == null ? new ResourceDeclaration("", "", 0, "", Map.of()) : resources;
    }

    /**
     * Compatibility constructor for classpath-only manifests from the first
     * application-platform slice.
     *
     * @param schemaVersion schema version
     * @param identity application identity
     * @param minJ7mVersion minimum J7M version
     * @param javaVersion Java version
     * @param moduleMode module mode
     * @param bootstrapClass bootstrap class
     * @param services declared services
     * @param security security metadata
     * @param resources resource metadata
     */
    public ApplicationManifest(
            final String schemaVersion,
            final ApplicationIdentity identity,
            final String minJ7mVersion,
            final String javaVersion,
            final String moduleMode,
            final String bootstrapClass,
            final List<ServiceDeclaration> services,
            final SecurityDeclaration security,
            final ResourceDeclaration resources) {
        this(schemaVersion, identity, minJ7mVersion, javaVersion, moduleMode, bootstrapClass, "", List.of(), services, security, resources);
    }

    /**
     * Immutable JPMS module declaration.
     *
     * @param name module name
     * @param path bundle-relative module JAR path
     */
    public record ModuleDeclaration(String name, String path) {
        /**
         * Creates immutable module metadata.
         */
        public ModuleDeclaration {
            name = name == null ? "" : name;
            path = path == null ? "" : path;
        }
    }

    /**
     * Immutable service declaration.
     *
     * @param serviceName logical service name
     * @param interfaceName Java interface class name
     * @param implementationClass Java implementation class name
     * @param transport requested transport id
     * @param globallyExported whether service is bound globally
     * @param metadata query and status metadata
     */
    public record ServiceDeclaration(
            String serviceName,
            String interfaceName,
            String implementationClass,
            String transport,
            boolean globallyExported,
            Map<String, String> metadata) {
        /**
         * Creates immutable service metadata.
         */
        public ServiceDeclaration {
            serviceName = serviceName == null ? "" : serviceName;
            interfaceName = interfaceName == null ? "" : interfaceName;
            implementationClass = implementationClass == null ? "" : implementationClass;
            transport = transport == null || transport.isBlank() ? "grpc" : transport;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Immutable security declaration.
     *
     * @param trustDomain application trust domain
     * @param confidential whether encrypted/authenticated transport is required
     * @param metadata additional safe security metadata
     */
    public record SecurityDeclaration(String trustDomain, boolean confidential, Map<String, String> metadata) {
        /**
         * Creates immutable security metadata.
         */
        public SecurityDeclaration {
            trustDomain = trustDomain == null || trustDomain.isBlank() ? "default" : trustDomain;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * Immutable resource declaration.
     *
     * @param cpu requested CPU description
     * @param memory requested memory description
     * @param maxConcurrentInvocations invocation concurrency limit
     * @param maxInboundPayload inbound payload limit description
     * @param metadata additional resource metadata
     */
    public record ResourceDeclaration(String cpu, String memory, int maxConcurrentInvocations, String maxInboundPayload, Map<String, String> metadata) {
        /**
         * Creates immutable resource metadata.
         */
        public ResourceDeclaration {
            cpu = cpu == null ? "" : cpu;
            memory = memory == null ? "" : memory;
            maxInboundPayload = maxInboundPayload == null ? "" : maxInboundPayload;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
