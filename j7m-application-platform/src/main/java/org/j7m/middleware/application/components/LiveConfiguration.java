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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.annotations.ConfigProperties;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.application.api.ConfigBinding;
import org.j7m.middleware.application.api.ConfigBindingSnapshot;
import org.j7m.middleware.application.api.ConfigurationSource;

/**
 * Host-owned, versioned configuration with atomically validated typed bindings.
 *
 * <p>Each accepted update validates every refreshing binding before publication. Failed conversion or
 * validation preserves the previous version. Values and exception details are never echoed in
 * diagnostics. Validators and record constructors are trusted, side-effect-free application code;
 * they may run more than once after concurrent registration/update. They run outside the owner
 * monitor and must not mutate this owner reentrantly. Scalar values and public records of supported
 * scalar components are supported, without private reflection or JPMS opens. Instances are
 * thread-safe; external configuration sources feed complete snapshots through {@link #update}.
 * Source reconnect must preserve a monotonically increasing version namespace.
 */
public final class LiveConfiguration implements AutoCloseable {
    private static final int MAX_BINDINGS = 128;
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_ATTEMPTS = 8;
    private final Object monitor = new Object();
    private final Map<Long, Registration<?>> registrations = new LinkedHashMap<>();
    private final ThreadLocal<Boolean> validating = ThreadLocal.withInitial(() -> false);
    private final AtomicLong rejectedSourceUpdates = new AtomicLong();
    private volatile State state;
    private volatile boolean closed;
    private long sequence;
    private long generation;
    private SourceWatch sourceWatch;

    private record State(long version, Map<String, String> raw, Map<Long, ConfigBindingSnapshot<?>> values) { }

    /**
     * Creates an owner with no registrations.
     * @param initialVersion nonnegative initial source version
     * @param initial complete initial string snapshot, at most 1024 entries
     * @throws IllegalArgumentException if bounds or the version are invalid
     */
    public LiveConfiguration(final long initialVersion, final Map<String, String> initial) {
        if (initialVersion < 0) {
            throw new IllegalArgumentException("Invalid configuration version");
        }
        this.state = new State(initialVersion, copyValues(initial), Map.of());
    }

    /**
     * Registers a typed scalar binding, validated against the current complete snapshot.
     * Supported types are String, primitive wrappers, Duration and enums; primitive Class tokens
     * are normalized to their wrappers. A required missing value is rejected even with a default.
     * An optional missing scalar uses a nonempty default, empty String, or null for other types.
     * @param type supported scalar type
     * @param key exact configuration key
     * @param required whether absence is invalid
     * @param defaultValue optional textual default, never included in diagnostics
     * @param refresh whether later accepted versions replace this binding's initial value
     * @param validator side-effect-free validation, never null; exceptions reject safely
     * @param <T> scalar value type
     * @return closeable stable typed provider
     * @throws IllegalArgumentException if conversion, validation or metadata is invalid
     * @throws IllegalStateException if closed, called reentrantly or registration capacity is exhausted
     */
    public <T> ConfigBinding<T> bindValue(final Class<T> type, final String key, final boolean required,
            final String defaultValue, final boolean refresh, final Consumer<? super T> validator) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(validator);
        checkKey(key);
        if (defaultValue == null || defaultValue.length() > 8192) {
            throw new IllegalArgumentException("Invalid configuration default");
        }
        checkScalar(type);
        return register(values -> castValue(type, scalar(type, values.get(key), required, defaultValue)), refresh, validator);
    }

    /**
     * Registers a public record annotated with ConfigProperties as an immutable whole-value binding.
     * Components use prefix plus their name; ConfigValue can override the relative name, required
     * flag and default. A component without ConfigValue is required. Component-level refresh is
     * rejected: refresh is selected for the whole properties record. The public canonical
     * constructor must be accessible through normal JPMS exports, without setAccessible.
     * @param type public annotated record type with at most 128 supported scalar components
     * @param validator side-effect-free whole-record validation
     * @param <T> immutable record type
     * @return closeable whole-record provider
     * @throws IllegalArgumentException if the record, conversion or validation is invalid
     * @throws IllegalStateException if closed, reentrant or capacity is exhausted
     */
    public <T> ConfigBinding<T> bindProperties(final Class<T> type, final Consumer<? super T> validator) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(validator);
        final ConfigProperties properties = type.getAnnotation(ConfigProperties.class);
        if (!type.isRecord() || !Modifier.isPublic(type.getModifiers()) || properties == null) {
            throw new IllegalArgumentException("Public annotated configuration record required");
        }
        final String prefix = properties.prefix();
        if (!prefix.isEmpty()) {
            checkKey(prefix);
        }
        final RecordComponent[] components = type.getRecordComponents();
        if (components.length > 128) {
            throw new IllegalArgumentException("Configuration record capacity exceeded");
        }
        final List<Function<Map<String, String>, Object>> readers = new ArrayList<>();
        for (RecordComponent component : components) {
            checkScalar(component.getType());
            final ConfigValue value = component.getAnnotation(ConfigValue.class);
            if (value != null && value.refresh()) {
                throw new IllegalArgumentException("Configuration component refresh is unsupported");
            }
            final String key = (prefix.isEmpty() ? "" : prefix + ".") + (value == null ? component.getName() : value.value());
            checkKey(key);
            readers.add(values -> scalar(component.getType(), values.get(key), value == null || value.required(),
                    value == null ? "" : value.defaultValue()));
        }
        final Constructor<T> constructor;
        try {
            constructor = type.getConstructor(java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Configuration record constructor is inaccessible");
        }
        return register(values -> {
            try {
                final Object[] arguments = readers.stream().map(reader -> reader.apply(values)).toArray();
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Configuration record construction rejected");
            }
        }, properties.refresh(), validator);
    }

    /**
     * Publishes a newer full source snapshot after validating all registered refresh bindings.
     * Conversions occur outside the owner monitor; concurrent structural changes cause at most
     * eight retries. A source may replay snapshots after reconnect: stale versions return false.
     * @param version positive or zero monotonically increasing source version
     * @param values complete replacement snapshot, not a patch
     * @return true if published, false if no newer than the current version
     * @throws IllegalArgumentException if input or any binding validation fails
     * @throws IllegalStateException if closed, reentrant or repeatedly changed concurrently
     */
    public boolean update(final long version, final Map<String, String> values) {
        return update(version, values, null);
    }

    private boolean update(final long version, final Map<String, String> values, final SourceWatch expectedSource) {
        mutationAllowed();
        if (version < 0) {
            throw new IllegalArgumentException("Invalid configuration version");
        }
        final Map<String, String> copy = copyValues(values);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            final State before;
            final long observedGeneration;
            final List<Registration<?>> current;
            synchronized (this.monitor) {
                requireOpen();
                if (expectedSource != null && this.sourceWatch != expectedSource) {
                    return false;
                }
                before = this.state;
                if (version <= before.version()) {
                    return false;
                }
                observedGeneration = this.generation;
                current = List.copyOf(this.registrations.values());
            }
            final Map<Long, ConfigBindingSnapshot<?>> converted = new HashMap<>();
            for (Registration<?> registration : current) {
                converted.put(registration.id, registration.refresh ? evaluate(registration, version, copy) : before.values().get(registration.id));
            }
            synchronized (this.monitor) {
                requireOpen();
                if (expectedSource != null && this.sourceWatch != expectedSource) {
                    return false;
                }
                if (this.state == before && this.generation == observedGeneration) {
                    this.state = new State(version, copy, Map.copyOf(converted));
                    return true;
                }
            }
        }
        throw new IllegalStateException("Configuration changed concurrently; retry snapshot");
    }

    /**
     * Returns the accepted complete source version, without any configuration values.
     * @return nonnegative accepted version
     * @throws IllegalStateException if closed
     */
    public long version() {
        requireOpen();
        return this.state.version();
    }

    /**
     * Returns the number of active binding registrations.
     * @return bounded active count, zero after close
     */
    public int bindingCount() {
        synchronized (this.monitor) {
            return this.registrations.size();
        }
    }

    /**
     * Attaches one trusted source, including initial snapshot and tail delivery. A second active
     * source is rejected; detach before reconnecting a replacement source. Late detached-source
     * events cannot publish. Invalid live updates are rejected and counted without terminating the
     * subscription, so a later valid snapshot can recover. The owner closes this subscription.
     * @param source trusted host-selected configuration provider
     * @return idempotent detach handle; source close failures have redacted errors
     * @throws IllegalArgumentException if subscribing fails or the source returns no handle
     * @throws IllegalStateException if closed, reentrant or another source is attached
     * @throws NullPointerException if source is null
     */
    public AutoCloseable attach(final ConfigurationSource source) {
        Objects.requireNonNull(source);
        mutationAllowed();
        final SourceWatch watch = new SourceWatch();
        synchronized (this.monitor) {
            requireOpen();
            if (this.sourceWatch != null) {
                throw new IllegalStateException("Configuration source is already attached");
            }
            this.sourceWatch = watch;
        }
        try {
            watch.install(Objects.requireNonNull(source.watch(watch::accept)));
            return watch;
        } catch (Exception exception) {
            watch.close();
            throw new IllegalArgumentException("Configuration source subscription failed");
        }
    }

    /**
     * Returns rejected source deliveries without exposing their contents.
     * @return rejection count; stale or detached-source events are ignored, not counted
     */
    public long rejectedSourceUpdates() {
        return this.rejectedSourceUpdates.get();
    }

    /**
     * Closes all registrations idempotently and discards owned raw values.
     * @throws IllegalStateException if called reentrantly by a validator
     */
    @Override
    public void close() {
        mutationAllowed();
        final SourceWatch toClose;
        synchronized (this.monitor) {
            toClose = this.sourceWatch;
            if (!this.closed) {
                this.closed = true;
                this.registrations.clear();
                this.state = new State(this.state.version(), Map.of(), Map.of());
                this.generation++;
            }
        }
        if (toClose != null) {
            toClose.close();
        }
    }

    private <T> ConfigBinding<T> register(final Function<Map<String, String>, T> reader,
            final boolean refresh, final Consumer<? super T> validator) {
        mutationAllowed();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            final State before;
            final long observedGeneration;
            final Registration<T> registration;
            synchronized (this.monitor) {
                requireOpen();
                if (this.registrations.size() >= MAX_BINDINGS) {
                    throw new IllegalStateException("Configuration binding capacity exceeded");
                }
                before = this.state;
                observedGeneration = this.generation;
                registration = new Registration<>(++this.sequence, reader, refresh, validator);
            }
            final ConfigBindingSnapshot<T> value = evaluate(registration, before.version(), before.raw());
            synchronized (this.monitor) {
                requireOpen();
                if (this.state == before && this.generation == observedGeneration) {
                    final Map<Long, ConfigBindingSnapshot<?>> updated = new HashMap<>(before.values());
                    updated.put(registration.id, value);
                    this.registrations.put(registration.id, registration);
                    this.generation++;
                    this.state = new State(before.version(), before.raw(), Map.copyOf(updated));
                    return registration;
                }
            }
        }
        throw new IllegalStateException("Configuration changed concurrently; retry registration");
    }

    private <T> ConfigBindingSnapshot<T> evaluate(final Registration<T> registration, final long version,
            final Map<String, String> values) {
        this.validating.set(true);
        try {
            final T value = registration.reader.apply(values);
            registration.validator.accept(value);
            return new ConfigBindingSnapshot<>(version, value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Configuration conversion or validation rejected");
        } finally {
            this.validating.remove();
        }
    }

    private void mutationAllowed() {
        if (this.validating.get()) {
            throw new IllegalStateException("Reentrant configuration mutation rejected");
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Configuration owner is closed");
        }
    }

    private static Map<String, String> copyValues(final Map<String, String> values) {
        if (values == null || values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid configuration snapshot capacity");
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            checkKey(entry.getKey());
            if (entry.getValue() == null || entry.getValue().length() > 8192) {
                throw new IllegalArgumentException("Invalid configuration snapshot value");
            }
        }
        return Map.copyOf(values);
    }

    private static void checkKey(final String key) {
        if (key == null || key.isBlank() || key.length() > 256 || !key.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid configuration key");
        }
    }

    private static Class<?> boxed(final Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return Map.<Class<?>, Class<?>>of(int.class, Integer.class, long.class, Long.class, boolean.class, Boolean.class,
                double.class, Double.class, float.class, Float.class, byte.class, Byte.class,
                short.class, Short.class, char.class, Character.class).getOrDefault(type, Void.class);
    }

    private static void checkScalar(final Class<?> type) {
        final Class<?> actual = boxed(type);
        if (!List.of(String.class, Integer.class, Long.class, Boolean.class, Double.class, Float.class,
                Byte.class, Short.class, Character.class, Duration.class).contains(actual) && !actual.isEnum()) {
            throw new IllegalArgumentException("Unsupported configuration scalar type");
        }
    }

    private static Object scalar(final Class<?> type, final String raw, final boolean required, final String fallback) {
        if (raw == null && required) {
            throw new IllegalArgumentException("Required configuration value missing");
        }
        final String value = raw == null ? fallback : raw;
        final Class<?> actual = boxed(type);
        if (actual == String.class) {
            return value;
        }
        if (raw == null && fallback.isEmpty() && !type.isPrimitive()) {
            return null;
        }
        if (actual == Integer.class) { return Integer.valueOf(value); }
        if (actual == Long.class) { return Long.valueOf(value); }
        if (actual == Byte.class) { return Byte.valueOf(value); }
        if (actual == Short.class) { return Short.valueOf(value); }
        if (actual == Boolean.class && (value.equals("true") || value.equals("false"))) { return Boolean.valueOf(value); }
        if (actual == Character.class && value.length() == 1) { return value.charAt(0); }
        if (actual == Duration.class) { return Duration.parse(value); }
        if (actual == Double.class) {
            final double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed)) { return parsed; }
        }
        if (actual == Float.class) {
            final float parsed = Float.parseFloat(value);
            if (Float.isFinite(parsed)) { return parsed; }
        }
        if (actual.isEnum()) {
            for (Object constant : actual.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(value)) { return constant; }
            }
        }
        throw new IllegalArgumentException("Invalid configuration scalar value");
    }

    @SuppressWarnings("unchecked")
    private static <T> T castValue(final Class<T> type, final Object value) {
        return (T) boxed(type).cast(value);
    }

    private final class SourceWatch implements AutoCloseable {
        private final AutoCloseable detached = () -> { };
        private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();

        void install(final AutoCloseable installed) throws Exception {
            if (!this.subscription.compareAndSet(null, installed)) {
                installed.close();
            }
        }

        void accept(final ConfigurationSource.Snapshot snapshot) {
            if (this.subscription.get() == this.detached || closed) {
                return;
            }
            try {
                update(snapshot.version(), snapshot.values(), this);
            } catch (RuntimeException exception) {
                rejectedSourceUpdates.incrementAndGet();
            }
        }

        @Override
        public void close() {
            mutationAllowed();
            synchronized (monitor) {
                if (sourceWatch == this) {
                    sourceWatch = null;
                }
            }
            final AutoCloseable previous = this.subscription.getAndSet(this.detached);
            if (previous != null && previous != this.detached) {
                try {
                    previous.close();
                } catch (Exception exception) {
                    throw new IllegalStateException("Configuration source close failed");
                }
            }
        }
    }

    private final class Registration<T> implements ConfigBinding<T> {
        private final long id;
        private final Function<Map<String, String>, T> reader;
        private final boolean refresh;
        private final Consumer<? super T> validator;
        private volatile boolean detached;

        Registration(final long id, final Function<Map<String, String>, T> reader, final boolean refresh,
                final Consumer<? super T> validator) {
            this.id = id;
            this.reader = reader;
            this.refresh = refresh;
            this.validator = validator;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ConfigBindingSnapshot<T> snapshot() {
            requireOpen();
            final ConfigBindingSnapshot<?> value = state.values().get(this.id);
            if (this.detached || value == null) {
                throw new IllegalStateException("Configuration binding is closed");
            }
            return (ConfigBindingSnapshot<T>) value;
        }

        @Override
        public void close() {
            mutationAllowed();
            synchronized (monitor) {
                if (!this.detached) {
                    this.detached = true;
                    registrations.remove(this.id);
                    final Map<Long, ConfigBindingSnapshot<?>> updated = new HashMap<>(state.values());
                    updated.remove(this.id);
                    generation++;
                    state = new State(state.version(), state.raw(), Map.copyOf(updated));
                }
            }
        }
    }
}
