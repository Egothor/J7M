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

package org.j7m.middleware.application.components;

import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;

/**
 * Trusted managed-host adapter for generated service.name dependencies. It validates all finite
 * metadata before graph construction, then prepares a stable proxy and owns its discovery handle.
 * Ordinary direct and ManagedProvider business-interface injection are supported. OPTIONAL policy
 * requires a generated {@code ManagedProvider<Optional<T>>} edge; absence is never a nullable proxy.
 */
public final class ServiceBindingFactory implements ManagedHostBindings.Factory {
    private static final Set<String> KEYS = Set.of("service.name", "service.contract.id", "service.contract.version", "binding.refresh", "binding.policy");
    private final LiveServiceBindings owner;
    private final String registryContext;
    /**
     * Creates a host adapter over a borrowed owner.
     * @param owner authorized live-binding owner
     * @param registryContext explicit host application/registry context
     * @throws IllegalArgumentException if context is invalid
     */
    public ServiceBindingFactory(LiveServiceBindings owner, String registryContext) {
        this.owner = Objects.requireNonNull(owner);
        if (registryContext == null || registryContext.isBlank() || registryContext.length() > 256) throw new IllegalArgumentException("Invalid binding registry context");
        this.registryContext = registryContext;
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validate(ManagedDependency dependency, Scope.Kind scope) {
        boolean optional = dependency.attributes().containsKey("binding.optional");
        var keys = new HashSet<>(KEYS); if (optional) keys.add("binding.optional");
        if (!dependency.attributes().keySet().equals(keys) || optional && (!dependency.provider() || !"true".equals(dependency.attributes().get("binding.optional")))) throw new IllegalArgumentException("Unsupported service binding metadata");
        var request = request(dependency);
        owner.validateContract(dependency.type());
        if (!dependency.name().isEmpty() && !dependency.name().equals(request.serviceName())) throw new IllegalArgumentException("Conflicting service binding name");
        if ((owner.policy(dependency.attributes().get("binding.policy")).unavailable() == BindingPolicy.Unavailable.OPTIONAL) != optional) throw new IllegalArgumentException("Optional service requires typed Optional provider");
    }
    private <T> ServiceBindingRequest<T> request(Class<T> type, ManagedDependency dependency) {
        String rawVersion = dependency.attributes().get("service.contract.version"); int version;
        try { version = Integer.parseInt(rawVersion); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid service contract version"); }
        if (!Integer.toString(version).equals(rawVersion)) throw new IllegalArgumentException("Invalid service contract version");
        return new ServiceBindingRequest<>(type, dependency.attributes().get("service.name"), dependency.attributes().get("service.contract.id"), version,
                dependency.qualifiers(), registryContext, BindingRefresh.valueOf(dependency.attributes().get("binding.refresh")));
    }
    private ServiceBindingRequest<?> request(ManagedDependency dependency) { return request(dependency.type(), dependency); }
    /**
     * {@inheritDoc}
     */
    @Override public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) {
        validate(dependency, Scope.Kind.ACTIVATION);
        ServiceBinding<?> binding = owner.open(request(dependency), dependency.attributes().get("binding.policy"));
        return new ManagedHostBindings.BoundValue() {
            @Override public Object get() { return dependency.attributes().containsKey("binding.optional") ? binding.optionalProvider().get() : binding.proxy(); }
            @Override public void close() { binding.close(); }
        };
    }
}
