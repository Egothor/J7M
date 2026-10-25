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

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.CurrentApplication;
import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.ConfigurationView;
import org.j7m.middleware.application.api.InjectionBindingKey;
import org.j7m.middleware.application.api.InjectionDiagnostic;
import org.j7m.middleware.application.api.InjectionGraphStatus;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.J7mInjector;
import org.j7m.middleware.security.api.Principal;

/**
 * Default reflection-based J7M application injector.
 *
 * <p>The injector is intentionally small and deterministic. It resolves
 * public API capability bindings, configuration values, and scoped context
 * objects for application-owned components. It does not scan the class path,
 * load vendor integrations, expose runtime internals, or alter JPMS access
 * rules beyond the caller's declared {@code opens} permissions.</p>
 */
public final class DefaultJ7mInjector implements J7mInjector {
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
            "accesskey", "privatekey", "credential", "authorization", "cookie",
            "session", "bearer", "endpointcredential");

    private final Clock clock;
    private final ConfigurationView configuration;
    private final ApplicationContext applicationContext;
    private final Map<InjectionBindingKey, List<Object>> bindings;
    private final List<InjectionDiagnostic> diagnostics;

    private DefaultJ7mInjector(final Builder builder) {
        this.clock = builder.clock;
        this.configuration = builder.configuration;
        this.applicationContext = builder.applicationContext;
        this.bindings = copyBindings(builder.bindings);
        this.diagnostics = new ArrayList<>();
    }

    /**
     * Creates a builder for a default J7M injector.
     *
     * @return injector builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T instantiate(final Class<T> type) {
        if (type == null) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "", "component type is required");
        }
        try {
            Constructor<T> constructor = this.constructor(type);
            Object[] arguments = this.arguments(constructor);
            T instance = constructor.newInstance(arguments);
            this.injectFields(instance);
            this.diagnostic(type.getName(), "component.instantiate", "success", "COMPONENT_CREATED", Map.of());
            return instance;
        } catch (MiddlewareException exception) {
            throw exception;
        } catch (InaccessibleObjectException exception) {
            throw failure(MiddlewareErrorCode.PERMISSION_DENIED, type.getName(), "component constructor is not open for injection");
        } catch (ReflectiveOperationException exception) {
            throw failure(MiddlewareErrorCode.INTERNAL, type.getName(), "component construction failed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invokeLifecycle(final Object component, final Class<? extends Annotation> annotation) {
        if (component == null || annotation == null) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, "", "component and lifecycle annotation are required");
        }
        List<Method> methods = new ArrayList<>();
        Class<?> cursor = component.getClass();
        while (cursor != null && cursor != Object.class) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotation)) {
                    methods.add(method);
                }
            }
            cursor = cursor.getSuperclass();
        }
        methods.sort(Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (method.getParameterCount() != 0) {
                throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, component.getClass().getName(),
                        "lifecycle method must not declare parameters");
            }
            try {
                if (!method.canAccess(component)) {
                    method.setAccessible(true);
                }
                method.invoke(component);
                this.diagnostic(component.getClass().getName(), "lifecycle." + annotation.getSimpleName(),
                        "success", "LIFECYCLE_INVOKED", Map.of("method", method.getName()));
            } catch (InaccessibleObjectException exception) {
                throw failure(MiddlewareErrorCode.PERMISSION_DENIED, component.getClass().getName(),
                        "lifecycle method is not open for injection");
            } catch (ReflectiveOperationException exception) {
                throw failure(MiddlewareErrorCode.INTERNAL, component.getClass().getName(),
                        "lifecycle method failed");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InjectionGraphStatus status() {
        List<InjectionBindingKey> keys = new ArrayList<>(this.bindings.keySet());
        keys.sort(Comparator.comparing((InjectionBindingKey key) -> key.type().getName()).thenComparing(InjectionBindingKey::name));
        return new InjectionGraphStatus(keys, List.copyOf(this.diagnostics));
    }

    private <T> Constructor<T> constructor(final Class<T> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        List<Constructor<?>> injected = new ArrayList<>();
        for (Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                injected.add(constructor);
            }
        }
        Constructor<?> selected;
        if (injected.size() > 1) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "multiple injection constructors");
        } else if (injected.size() == 1) {
            selected = injected.getFirst();
        } else {
            try {
                selected = type.getDeclaredConstructor();
            } catch (NoSuchMethodException exception) {
                if (constructors.length == 1) {
                    selected = constructors[0];
                } else {
                    throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "no injection constructor");
                }
            }
        }
        try {
            if (!selected.canAccess(null)) {
                selected.setAccessible(true);
            }
        } catch (InaccessibleObjectException exception) {
            throw failure(MiddlewareErrorCode.PERMISSION_DENIED, type.getName(), "constructor package is not open for injection");
        }
        @SuppressWarnings("unchecked")
        Constructor<T> typed = (Constructor<T>) selected;
        return typed;
    }

    private Object[] arguments(final Executable executable) {
        Parameter[] parameters = executable.getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            values[index] = this.resolve(parameters[index].getType(), parameters[index].getAnnotations());
        }
        return values;
    }

    private void injectFields(final Object instance) {
        Class<?> cursor = instance.getClass();
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)
                        || field.isAnnotationPresent(J7mResource.class)
                        || field.isAnnotationPresent(ConfigValue.class)
                        || field.isAnnotationPresent(CurrentInvocation.class)
                        || field.isAnnotationPresent(CurrentPrincipal.class)
                        || field.isAnnotationPresent(CurrentApplication.class)) {
                    try {
                        if (!field.canAccess(instance)) {
                            field.setAccessible(true);
                        }
                        field.set(instance, this.resolve(field.getType(), field.getAnnotations()));
                    } catch (InaccessibleObjectException exception) {
                        throw failure(MiddlewareErrorCode.PERMISSION_DENIED, instance.getClass().getName(),
                                "field package is not open for injection");
                    } catch (IllegalAccessException exception) {
                        throw failure(MiddlewareErrorCode.PERMISSION_DENIED, instance.getClass().getName(),
                                "field injection failed");
                    }
                }
            }
            cursor = cursor.getSuperclass();
        }
    }

    private Object resolve(final Class<?> type, final Annotation[] annotations) {
        ConfigValue configValue = annotation(annotations, ConfigValue.class);
        if (configValue != null) {
            return this.config(type, configValue);
        }
        if (annotation(annotations, CurrentInvocation.class) != null) {
            return requireType(type, InvocationContext.class, J7mContext.currentInvocation());
        }
        if (annotation(annotations, CurrentPrincipal.class) != null) {
            return requireType(type, Principal.class, J7mContext.currentInvocation().security().principal());
        }
        if (annotation(annotations, CurrentApplication.class) != null) {
            return requireType(type, ApplicationContext.class, this.applicationContext);
        }
        String qualifier = qualifier(annotations);
        InjectionBindingKey key = new InjectionBindingKey(type, qualifier);
        List<Object> candidates = this.bindings.getOrDefault(key, List.of());
        if (candidates.isEmpty() && !qualifier.isEmpty()) {
            candidates = this.bindings.getOrDefault(new InjectionBindingKey(type, ""), List.of());
        }
        if (candidates.isEmpty()) {
            this.diagnostic(type.getName(), "dependency.resolve", "failure", "MISSING_DEPENDENCY",
                    Map.of("qualifier", qualifier));
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "missing injected dependency");
        }
        if (candidates.size() > 1) {
            this.diagnostic(type.getName(), "dependency.resolve", "failure", "DUPLICATE_DEPENDENCY",
                    Map.of("qualifier", qualifier));
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "duplicate injected dependency");
        }
        return candidates.getFirst();
    }

    private Object config(final Class<?> type, final ConfigValue configValue) {
        if (type != String.class) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "configuration injection supports String values");
        }
        Optional<String> value = this.configuration.get(configValue.value());
        if (value.isPresent()) {
            return value.get();
        }
        if (configValue.required()) {
            this.diagnostic(type.getName(), "config.resolve", "failure", "MISSING_CONFIG",
                    Map.of("key", redact(configValue.value(), configValue.value())));
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, type.getName(), "missing required configuration value");
        }
        return configValue.defaultValue();
    }

    private static Object requireType(final Class<?> requested, final Class<?> expected, final Object value) {
        if (!requested.isAssignableFrom(expected)) {
            throw failure(MiddlewareErrorCode.INVALID_ARGUMENT, requested.getName(), "incompatible scoped injection type");
        }
        return value;
    }

    private static String qualifier(final Annotation[] annotations) {
        Named named = annotation(annotations, Named.class);
        if (named != null) {
            return named.value();
        }
        J7mResource resource = annotation(annotations, J7mResource.class);
        return resource == null ? "" : resource.value();
    }

    private static <A extends Annotation> A annotation(final Annotation[] annotations, final Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    private void diagnostic(
            final String component,
            final String action,
            final String result,
            final String reasonCode,
            final Map<String, String> metadata) {
        Map<String, String> safe = new LinkedHashMap<>();
        metadata.forEach((String key, String value) -> safe.put(key, redact(key, value)));
        this.diagnostics.add(new InjectionDiagnostic(this.clock.instant(), component, action, result, reasonCode, safe));
    }

    private static MiddlewareException failure(final MiddlewareErrorCode code, final String component, final String message) {
        String safeComponent = component == null ? "" : component;
        String safeMessage = message + (safeComponent.isBlank() ? "" : ": " + safeComponent);
        return MiddlewareException.of(code, safeMessage);
    }

    private static Map<InjectionBindingKey, List<Object>> copyBindings(final Map<InjectionBindingKey, List<Object>> input) {
        Map<InjectionBindingKey, List<Object>> copy = new LinkedHashMap<>();
        input.forEach((InjectionBindingKey key, List<Object> values) -> copy.put(key, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static String redact(final String key, final String value) {
        String normalized = key == null ? "" : key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE_KEYS) {
            if (normalized.contains(sensitive)) {
                return "<redacted>";
            }
        }
        if (value == null) {
            return "";
        }
        if (value.contains("J7M_SENTINEL_SECRET_TOKEN")
                || value.contains("J7M_SENTINEL_PASSWORD")
                || value.contains("J7M_SENTINEL_API_KEY")) {
            return "<redacted>";
        }
        return value;
    }

    /**
     * Builder for {@link DefaultJ7mInjector}.
     */
    public static final class Builder {
        private final Map<InjectionBindingKey, List<Object>> bindings;
        private Clock clock;
        private ConfigurationView configuration;
        private ApplicationContext applicationContext;

        private Builder() {
            this.bindings = new LinkedHashMap<>();
            this.clock = Clock.systemUTC();
            this.configuration = ConfigurationView.of(Map.of());
            this.applicationContext = null;
        }

        /**
         * Sets the clock used for diagnostics.
         *
         * @param value clock
         * @return this builder
         */
        public Builder clock(final Clock value) {
            this.clock = value == null ? Clock.systemUTC() : value;
            return this;
        }

        /**
         * Sets the safe application configuration view.
         *
         * @param value configuration view
         * @return this builder
         */
        public Builder configuration(final ConfigurationView value) {
            this.configuration = value == null ? ConfigurationView.of(Map.of()) : value;
            return this;
        }

        /**
         * Sets the safe application context.
         *
         * @param value application context
         * @return this builder
         */
        public Builder applicationContext(final ApplicationContext value) {
            this.applicationContext = value;
            return this;
        }

        /**
         * Adds an unqualified public capability binding.
         *
         * @param type public API type
         * @param instance capability instance
         * @param <T> capability type
         * @return this builder
         */
        public <T> Builder bind(final Class<T> type, final T instance) {
            return this.bind(type, "", instance);
        }

        /**
         * Adds a qualified public capability binding.
         *
         * @param type public API type
         * @param name qualifier name
         * @param instance capability instance
         * @param <T> capability type
         * @return this builder
         */
        public <T> Builder bind(final Class<T> type, final String name, final T instance) {
            if (type == null || instance == null) {
                throw new IllegalArgumentException("binding type and instance are required");
            }
            InjectionBindingKey key = new InjectionBindingKey(type, name);
            this.bindings.computeIfAbsent(key, ignored -> new ArrayList<>()).add(instance);
            return this;
        }

        /**
         * Builds the injector.
         *
         * @return injector
         */
        public DefaultJ7mInjector build() {
            return new DefaultJ7mInjector(this);
        }
    }
}

