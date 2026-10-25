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

package org.j7m.examples.annotation.simple;

import java.util.Map;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.AuditPublisher;
import org.j7m.middleware.application.api.ConfigurationView;
import org.j7m.middleware.application.api.MetricsPublisher;
import org.j7m.middleware.application.api.ServiceExporter;
import org.j7m.middleware.application.components.DefaultJ7mInjector;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;

/**
 * Deterministic smoke entry point for the canonical simple annotation example.
 *
 * <p>The main method uses the public J7M injector to construct the service and
 * application from copied-project dependencies. It proves constructor
 * injection, named bindings, safe configuration, public resource binding,
 * current application context injection, and startup lifecycle invocation
 * without requiring a transport, registry, external DI framework, or runtime
 * internals.</p>
 */
public final class SimpleExampleMain {
    private SimpleExampleMain() {
    }

    /**
     * Runs the simple service smoke flow.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        final ApplicationContext context = applicationContext();
        final SimpleGreetingService.GreetingAudit audit = new SimpleGreetingService.GreetingAudit() {
            @Override
            public void record(final String eventName) {
                eventName.length();
            }
        };

        // J7M teaching: the smoke flow uses the public injector, so the copied
        // example proves the same annotation-owned dependency path described
        // by the README without starting deployment or transport internals.
        final DefaultJ7mInjector serviceInjector = injector(context, audit).build();
        final SimpleGreetingService service = serviceInjector.instantiate(SimpleGreetingService.class);
        final DefaultJ7mInjector applicationInjector = injector(context, audit)
                .bind(SimpleGreetingService.class, service)
                .build();
        final SimpleAnnotationApplication application =
                applicationInjector.instantiate(SimpleAnnotationApplication.class);

        // J7M teaching: startup is invoked through J7mInjector so lifecycle
        // ownership is explicit and the output remains deterministic.
        applicationInjector.invokeLifecycle(application, org.j7m.middleware.annotations.Startup.class);
        System.out.println("annotation-first simple greeting: " + application.startupSummary());
    }

    private static DefaultJ7mInjector.Builder injector(
            final ApplicationContext context,
            final SimpleGreetingService.GreetingAudit audit) {
        return DefaultJ7mInjector.builder()
                .applicationContext(context)
                .configuration(ConfigurationView.of(Map.of("annotation.region", "eu")))
                .bind(SimpleGreetingService.SimpleFormatter.class, "formal", new SimpleGreetingService.SimpleFormatter())
                .bind(SimpleGreetingService.GreetingAudit.class, "greeting-audit", audit);
    }

    private static ApplicationContext applicationContext() {
        final RuntimeIdentity runtime = new RuntimeIdentity("annotation-basic-runtime", "examples", Map.of());
        final ApplicationIdentity application = new ApplicationIdentity(
                ApplicationCoordinate.parse("org.j7m.examples:annotation-simple:1.0.0"),
                "Annotation First Simple Service",
                "examples");
        final ApplicationInstanceIdentity instance = new ApplicationInstanceIdentity(
                new ApplicationInstanceId("annotation-simple-instance"),
                application,
                runtime,
                "JPMS");
        final ConfigurationView configuration = ConfigurationView.of(Map.of("annotation.region", "eu"));
        return new ApplicationContext() {
            @Override
            public ApplicationIdentity applicationIdentity() {
                return application;
            }

            @Override
            public ApplicationInstanceIdentity instanceIdentity() {
                return instance;
            }

            @Override
            public Principal activationPrincipal() {
                return Principal.runtime(runtime.runtimeId());
            }

            @Override
            public RuntimeIdentity runtimeIdentity() {
                return runtime;
            }

            @Override
            public ConfigurationView configuration() {
                return configuration;
            }

            @Override
            public ServiceExporter services() {
                return new ServiceExporter() {
                    @Override
                    public <T> ObjectRegistration<T> export(
                            final String serviceName,
                            final T instance,
                            final Class<T> api,
                            final ExportOptions options) {
                        throw new IllegalStateException("the BASIC smoke example does not export services");
                    }
                };
            }

            @Override
            public AuditPublisher audit() {
                return AuditPublisher.noop();
            }

            @Override
            public MetricsPublisher metrics() {
                return MetricsPublisher.noop();
            }

            @Override
            public ResourceUsageSnapshot resources() {
                return new ResourceUsageSnapshot(0, 0, 0, 0, 0);
            }
        };
    }
}
