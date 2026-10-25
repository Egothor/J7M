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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationVersion;

/**
 * Deterministic JSON parser for {@code META-INF/j7m-application.json}.
 *
 * <p>The parser uses no reflection and no external JSON dependency. It returns
 * structured manifest data and lets the validator decide whether required
 * fields are present. Instances are stateless and thread-safe.</p>
 */
public final class ApplicationManifestParser {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "application", "runtime", "modules", "services", "security", "resources");
    private static final Set<String> APPLICATION_FIELDS = Set.of("group", "name", "version", "displayName", "description");
    private static final Set<String> RUNTIME_FIELDS = Set.of("minJ7mVersion", "javaVersion", "moduleMode", "bootstrapClass", "bootstrapModule");
    private static final Set<String> MODULE_FIELDS = Set.of("name", "path");
    private static final Set<String> SERVICE_FIELDS = Set.of("serviceName", "interfaceName", "implementationClass", "transport", "globallyExported", "metadata");
    private static final Set<String> SECURITY_FIELDS = Set.of("trustDomain", "confidential");
    private static final Set<String> RESOURCE_FIELDS = Set.of("cpu", "memory", "maxConcurrentInvocations", "maxInboundPayload");
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a stateless manifest parser.
     */
    public ApplicationManifestParser() {
        this(ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a manifest parser with explicit bounds.
     *
     * @param limits manifest and metadata limits
     */
    public ApplicationManifestParser(final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
    }

    /**
     * Parses a manifest JSON string.
     *
     * @param json manifest JSON text
     * @return parsed manifest
     */
    public ApplicationManifest parse(final String json) {
        Object parsed = SimpleJson.parse(json, this.limits);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("manifest root must be an object");
        }
        requireAllowed(root, ROOT_FIELDS, "manifest root");
        String schema = string(root, "schemaVersion");
        Map<?, ?> application = object(root, "application");
        Map<?, ?> runtime = object(root, "runtime");
        Map<?, ?> security = optionalObject(root, "security");
        Map<?, ?> resources = optionalObject(root, "resources");
        requireAllowed(application, APPLICATION_FIELDS, "manifest application");
        requireAllowed(runtime, RUNTIME_FIELDS, "manifest runtime");
        requireAllowed(security, SECURITY_FIELDS, "manifest security");
        requireAllowed(resources, RESOURCE_FIELDS, "manifest resources");
        String group = string(application, "group");
        String name = string(application, "name");
        String version = string(application, "version");
        ApplicationCoordinate coordinate = new ApplicationCoordinate(new ApplicationId(group, name), new ApplicationVersion(version));
        ApplicationIdentity identity = new ApplicationIdentity(coordinate, string(application, "displayName"), string(security, "trustDomain"));
        List<ApplicationManifest.ModuleDeclaration> modules = this.modules(root.get("modules"));
        List<ApplicationManifest.ServiceDeclaration> services = this.services(root.get("services"));
        ApplicationManifest.SecurityDeclaration securityDeclaration = new ApplicationManifest.SecurityDeclaration(
                string(security, "trustDomain"),
                bool(security, "confidential"),
                Map.of());
        ApplicationManifest.ResourceDeclaration resourceDeclaration = new ApplicationManifest.ResourceDeclaration(
                string(resources, "cpu"),
                string(resources, "memory"),
                integer(resources, "maxConcurrentInvocations"),
                string(resources, "maxInboundPayload"),
                Map.of());
        return new ApplicationManifest(schema, identity, string(runtime, "minJ7mVersion"), string(runtime, "javaVersion"),
                string(runtime, "moduleMode"), string(runtime, "bootstrapClass"), string(runtime, "bootstrapModule"),
                modules, services, securityDeclaration, resourceDeclaration);
    }

    private List<ApplicationManifest.ModuleDeclaration> modules(final Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("modules declaration must be an array");
        }
        List<ApplicationManifest.ModuleDeclaration> modules = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> module)) {
                throw new IllegalArgumentException("module declaration must be an object");
            }
            requireAllowed(module, MODULE_FIELDS, "manifest module");
            modules.add(new ApplicationManifest.ModuleDeclaration(string(module, "name"), string(module, "path")));
        }
        return List.copyOf(modules);
    }

    private List<ApplicationManifest.ServiceDeclaration> services(final Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("services declaration must be an array");
        }
        List<ApplicationManifest.ServiceDeclaration> services = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> service)) {
                throw new IllegalArgumentException("service declaration must be an object");
            }
            requireAllowed(service, SERVICE_FIELDS, "manifest service");
            services.add(new ApplicationManifest.ServiceDeclaration(
                    string(service, "serviceName"),
                    string(service, "interfaceName"),
                    string(service, "implementationClass"),
                    string(service, "transport"),
                    bool(service, "globallyExported"),
                    stringMap(optionalObject(service, "metadata"))));
        }
        return List.copyOf(services);
    }

    private static Map<?, ?> object(final Map<?, ?> parent, final String name) {
        Object value = parent.get(name);
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        if (value != null) {
            throw new IllegalArgumentException("manifest field must be an object");
        }
        return Map.of();
    }

    private static Map<?, ?> optionalObject(final Map<?, ?> parent, final String name) {
        return object(parent, name);
    }

    private static String string(final Map<?, ?> map, final String name) {
        Object value = map.get(name);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("manifest field must be a string");
        }
        return string;
    }

    private static boolean bool(final Map<?, ?> map, final String name) {
        Object value = map.get(name);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("manifest field must be a boolean");
        }
        return booleanValue.booleanValue();
    }

    private static int integer(final Map<?, ?> map, final String name) {
        Object value = map.get(name);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            long longValue = number.longValue();
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("manifest integer field is out of range");
            }
            return (int) longValue;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("manifest field must be an integer");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("manifest field must be an integer", exception);
        }
    }

    private static Map<String, String> stringMap(final Map<?, ?> map) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("manifest metadata must contain string keys and values");
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static void requireAllowed(final Map<?, ?> map, final Set<String> allowed, final String label) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !allowed.contains(text)) {
                throw new IllegalArgumentException(label + " contains unsupported field");
            }
        }
    }
}
