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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.AnnotatedApplicationDescriptor;
import org.j7m.middleware.application.api.AnnotatedServiceDescriptor;
import org.j7m.middleware.application.api.AnnotatedServiceMethodDescriptor;

/**
 * Deterministic processor for J7M annotation-first application metadata.
 *
 * <p>The processor validates annotation combinations and produces stable
 * descriptors used by tests, examples, deployment tooling, and diagnostics.
 * It does not perform classpath scanning or service export by itself. Exported
 * services still flow through the normal J7M {@code ObjectRef}, security, and
 * idempotency mechanisms.</p>
 */
public final class AnnotationModelProcessor {
    /**
     * Creates an annotation processor.
     */
    public AnnotationModelProcessor() {
    }

    /**
     * Builds an application descriptor from an annotated application and
     * explicit service classes.
     *
     * @param applicationClass application class
     * @param serviceClasses service implementation classes
     * @return descriptor
     */
    public AnnotatedApplicationDescriptor describeApplication(
            final Class<?> applicationClass,
            final List<Class<?>> serviceClasses) {
        if (applicationClass == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application class is required");
        }
        J7mApplication annotation = applicationClass.getAnnotation(J7mApplication.class);
        if (annotation == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "missing @J7mApplication: " + applicationClass.getName());
        }
        List<AnnotatedServiceDescriptor> services = new ArrayList<>();
        for (Class<?> serviceClass : serviceClasses == null ? List.<Class<?>>of() : serviceClasses) {
            services.add(this.describeService(serviceClass));
        }
        services.sort(Comparator.comparing(AnnotatedServiceDescriptor::serviceName));
        return new AnnotatedApplicationDescriptor(annotation.group(), annotation.name(), annotation.version(),
                applicationClass.getName(), services);
    }

    /**
     * Builds a service descriptor from an annotated service class.
     *
     * @param serviceClass service class
     * @return descriptor
     */
    public AnnotatedServiceDescriptor describeService(final Class<?> serviceClass) {
        if (serviceClass == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "service class is required");
        }
        J7mService service = serviceClass.getAnnotation(J7mService.class);
        if (service == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "missing @J7mService: " + serviceClass.getName());
        }
        if (service.exported() && serviceClass.isAnnotationPresent(NoRemoteExport.class)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "service cannot be both exported and @NoRemoteExport");
        }
        List<AnnotatedServiceMethodDescriptor> methods = new ArrayList<>();
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RemoteMethod.class) || method.isAnnotationPresent(LocalOnly.class)) {
                methods.add(this.describeMethod(method));
            }
        }
        methods.sort(Comparator.comparing(AnnotatedServiceMethodDescriptor::methodName));
        return new AnnotatedServiceDescriptor(service.name(), serviceClass.getName(), service.exported(), service.transport(), methods);
    }

    private AnnotatedServiceMethodDescriptor describeMethod(final Method method) {
        boolean remote = method.isAnnotationPresent(RemoteMethod.class);
        if (remote && method.isAnnotationPresent(LocalOnly.class)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "method cannot be both @RemoteMethod and @LocalOnly: " + method.getName());
        }
        if (method.isAnnotationPresent(Idempotent.class) && method.isAnnotationPresent(NonIdempotent.class)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "method cannot be both @Idempotent and @NonIdempotent: " + method.getName());
        }
        RequiresRole role = method.getAnnotation(RequiresRole.class);
        RequiresPermission permission = method.getAnnotation(RequiresPermission.class);
        Map<String, String> metadata = new LinkedHashMap<>();
        Deadline deadline = method.getAnnotation(Deadline.class);
        if (deadline != null) {
            metadata.put("deadlineMillis", Long.toString(deadline.millis()));
        }
        Retry retry = method.getAnnotation(Retry.class);
        if (retry != null) {
            metadata.put("retryMaxAttempts", Integer.toString(retry.maxAttempts()));
        }
        return new AnnotatedServiceMethodDescriptor(method.getName(), remote, method.isAnnotationPresent(Idempotent.class),
                role == null ? "" : role.value(), permission == null ? "" : permission.value(),
                permission == null ? "" : permission.resource(), metadata);
    }
}

