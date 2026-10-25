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

package org.j7m.middleware.application.distributed;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionReply;
import org.j7m.middleware.distributed.PartitionTopology;

/**
 * Typed quorum client for a trusted portable reducer program and fixed authenticated scope.
 * A locality preference gets at most100ms and one quarter of the remaining budget before same-group
 * fallback; it never extends the original deadline.
 * Stable request identity and business bytes survive topology refresh; receipt epochs describe
 * historical commits and never grant current write authority. IOException leaves an UNKNOWN outcome.
 * This facade is thread-safe with thread-safe borrowed routing/endpoints/codec; it owns no threads.
 */
public final class ManagedPartitionClient {
    /**
     * Trusted current topology read; no application metadata supplies routing authority.
     */
    @FunctionalInterface public interface Routes {
        /**
         * Resolves a stable key through an authenticated quorum topology.
         * @param key canonical application routing key
         * @param deadline original finite operation deadline
         * @return current owner and monotonically increasing ownership epoch
         * @throws IOException for unavailable topology or an unknown outcome
         */
        PartitionTopology.Route resolve(String key, Instant deadline) throws IOException;
    }
    /**
     * Authorized endpoint alternatives within ONE native group; hints cannot change group authority.
     * @param primary authenticated default group endpoint
     * @param localities finite host-approved same-group endpoint preferences, at most64
     */
    public record Group(PartitionEndpoint primary, Map<String, PartitionEndpoint> localities) {
        /**
         * Snapshots endpoint references without opening connections.
         * @throws IllegalArgumentException for invalid locality names or inventory capacity
         * @throws NullPointerException for absent endpoints
         */
        public Group {
            Objects.requireNonNull(primary); localities = Map.copyOf(localities);
            if (localities.size() > 64 || localities.keySet().stream().anyMatch(key -> !key.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) {
                throw new IllegalArgumentException("Invalid partition locality inventory");
            }
        }
        /**
         * Returns a fixed diagnostic without endpoints or credentials.
         * @return redacted group description
         */
        @Override public String toString() { return "PartitionGroup[authorized,endpoints=redacted]"; }
    }
    private final Routes routes;
    private final Map<String, Group> groups;
    private final String fingerprint;
    private final String scope;
    private final Class<?> commandType;
    private final Class<?> queryType;
    private final Class<?> viewType;
    private final PayloadFrameEngine codec;
    private final PartitionRouting routing;
    private final java.util.function.BooleanSupplier admission;

    /**
     * Creates a typed facade over finite authenticated routing and endpoint capabilities.
     * @param routes trusted fresh topology source, normally an existing PartitionRouter
     * @param groups authorized native group identities and same-group locality preferences,1..256
     * @param program validated generated POJO program metadata
     * @param fingerprint locally verified portable program fingerprint, not a caller assertion
     * @param scope fixed server-authorized resource scope
     * @param codec borrowed explicit effect-free value codec
     * @param schemas explicit matching A01 data allowlist
     * @throws IllegalArgumentException for invalid types, routing declarations or inventory bounds
     */
    public ManagedPartitionClient(final Routes routes, final Map<String, Group> groups, final ManagedPartitionDefinition<?> program,
            final String fingerprint, final String scope, final ValueCodec codec, final SchemaRegistry schemas) {
        this.routes = Objects.requireNonNull(routes); this.groups = Map.copyOf(groups);
        if (groups.isEmpty() || groups.size() > 256 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || scope == null || scope.isBlank() || scope.length() > 128 || scope.chars().anyMatch(Character::isISOControl)
                || groups.keySet().stream().anyMatch(key -> key.isBlank() || key.length() > 128)) throw invalid();
        ManagedPartitionResource.validateData(program, schemas);
        this.fingerprint = fingerprint; this.scope = scope;
        commandType = program.commandType(); queryType = program.queryType(); viewType = program.viewType();
        this.codec = new PayloadFrameEngine(codec, schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
        for (Class<?> type : java.util.List.of(commandType, queryType, viewType)) this.codec.validateDeclaredType(type);
        routing = new PartitionRouting(program, schemas); admission = () -> true;
    }
    /**
     * Extracts a stable canonical routing key through the actual declared scalar/schema selector.
     * Use the same key for related reads; it is routing data and grants no ownership authority.
     * @param command concrete command or a data-only key template of the same schema
     * @return lowercase64-character key independent of non-key command fields
     * @throws IllegalArgumentException for invalid key data
     * @throws IllegalStateException when the program does not declare a shard selector
     */
    public String routingKey(final Object command) { open(); return routing.key(command); }
    /**
     * Commits using the actual declared scalar or numeric schema-field shard selector.
     * @param request stable bounded business identity, unchanged on retries
     * @param command bounded concrete command data
     * @param deadline original absolute deadline, never extended by topology refresh
     * @return first durable receipt or explicit uncommitted OVERLOADED receipt
     * @throws IOException for unavailable quorum, cancellation or UNKNOWN outcome
     * @throws IllegalArgumentException if no valid shard selector is declared or input conflicts
     */
    public ManagedPartitionResource.Receipt execute(final String request, final Object command, final Instant deadline) throws IOException {
        return execute(request, routingKey(command), command, deadline);
    }
    /**
     * Commits on an explicit stable key; a declared selector must still match the command itself.
     * @param request stable bounded business identity
     * @param key explicit routing key, at most128 characters
     * @param command bounded concrete command data
     * @param deadline original absolute deadline
     * @return durable first-commit or uncommitted overload receipt
     * @throws IOException for unavailable quorum, cancellation or UNKNOWN outcome
     * @throws IllegalArgumentException for mismatched shard or conflicting business input
     */
    public ManagedPartitionResource.Receipt execute(final String request, final String key, final Object command, final Instant deadline) throws IOException {
        open(); if (routing.explicit() && !routing.key(command).equals(key)) throw invalid();
        byte[] payload = codec.encode(command, commandType);
        while (remaining(deadline)) {
            var route = route(key, deadline);
            byte[] resultQuery = ManagedPartitionResource.query(fingerprint, route.ownerGroup(), scope, request, new byte[0]);
            // Avoid permanently appending rejected new requests once the retained journal is full.
            var prior = exchange(route, deadline, (endpoint, attemptDeadline) -> endpoint.query(route.epoch(), resultQuery, attemptDeadline));
            if (retry(prior)) continue;
            var existing = ManagedPartitionResource.receipt(accepted(prior));
            if (existing.status().equals("OVERLOADED")) return existing;
            byte[] frame = ManagedPartitionResource.command(fingerprint, route.ownerGroup(), scope, request, key, payload);
            var reply = exchange(route, deadline, (endpoint, attemptDeadline) -> endpoint.execute(route.epoch(), ManagedPartitionResource.requestId(scope, request), frame, resultQuery, attemptDeadline));
            if (retry(reply)) continue;
            return ManagedPartitionResource.receipt(accepted(reply));
        }
        throw unavailable();
    }
    /**
     * Reads a concrete view under fresh topology and quorum ownership barriers.
     * @param key stable application routing key
     * @param query bounded concrete query data
     * @param deadline original absolute deadline
     * @return independent decoded view
     * @throws IOException for unavailable quorum or cancellation
     * @throws IllegalArgumentException for invalid schema or routing input
     */
    public Object query(final String key, final Object query, final Instant deadline) throws IOException {
        open(); byte[] payload = codec.encode(query, queryType);
        while (remaining(deadline)) {
            var route = route(key, deadline);
            byte[] frame = ManagedPartitionResource.query(fingerprint, route.ownerGroup(), scope, "", payload);
            var reply = exchange(route, deadline, (endpoint, attemptDeadline) -> endpoint.query(route.epoch(), frame, attemptDeadline));
            if (retry(reply)) continue;
            return codec.decode(accepted(reply), viewType);
        }
        throw unavailable();
    }
    /**
     * Reads a view while checking the caller's requested Java type against the actual program.
     * @param <V> expected concrete view type
     * @param key stable routing key
     * @param query concrete query data
     * @param expectedType exact declared view type, including its primitive/boxed equivalent
     * @param deadline original absolute deadline
     * @return concrete independent decoded view
     * @throws IllegalArgumentException before RPC if the requested view type differs
     * @throws IOException for unavailable quorum, cancellation or UNKNOWN outcome
     */
    @SuppressWarnings("unchecked")
    public <V> V query(final String key, final Object query, final Class<V> expectedType, final Instant deadline) throws IOException {
        open();
        if (boxed(Objects.requireNonNull(expectedType)) != boxed(viewType)) throw invalid();
        return (V) query(key, query, deadline);
    }
    private static Class<?> boxed(final Class<?> type) {
        if (!type.isPrimitive()) return type;
        return Map.of(boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class, int.class, Integer.class,
                long.class, Long.class, float.class, Float.class, double.class, Double.class, char.class, Character.class).get(type);
    }
    private ManagedPartitionClient(final ManagedPartitionClient source, final java.util.function.BooleanSupplier admission) {
        routes = source.routes; groups = source.groups; fingerprint = source.fingerprint; scope = source.scope;
        commandType = source.commandType; queryType = source.queryType; viewType = source.viewType;
        codec = source.codec; routing = source.routing; this.admission = () -> source.admission.getAsBoolean() && admission.getAsBoolean();
    }
    ManagedPartitionClient scoped(final java.util.function.BooleanSupplier admission) {
        return new ManagedPartitionClient(this, Objects.requireNonNull(admission));
    }
    private void open() {
        if (!admission.getAsBoolean()) throw new IllegalStateException("Partition client binding is closed");
    }
    private PartitionTopology.Route route(final String key, final Instant deadline) throws IOException {
        open(); if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) throw invalid();
        var route = routes.resolve(key, deadline);
        if (route == null || route.epoch() < 1 || !groups.containsKey(route.ownerGroup())) throw unavailable(); return route;
    }
    private PartitionReply exchange(final PartitionTopology.Route route, final Instant deadline, final Operation operation) throws IOException {
        open(); var group = groups.get(route.ownerGroup()); var preferred = group.localities().get(routing.locality());
        long remaining = java.time.Duration.between(Instant.now(), deadline).toMillis();
        if (preferred != null && preferred != group.primary() && remaining > 1) {
            // A failed preference cannot consume the whole original budget and prevent fallback.
            long preferenceBudget = Math.min(100, Math.max(1, remaining / 4));
            Instant attemptDeadline = Instant.now().plusMillis(preferenceBudget);
            if (attemptDeadline.isAfter(deadline)) attemptDeadline = deadline;
            try { return operation.call(preferred, attemptDeadline); }
            catch (IOException unavailable) { if (!remaining(deadline)) throw unavailable; }
        }
        open(); return operation.call(group.primary(), deadline);
    }
    private static boolean remaining(final Instant deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw unavailable(); return Instant.now().isBefore(Objects.requireNonNull(deadline));
    }
    private static boolean retry(final PartitionReply reply) {
        return reply.status().equals("MOVED") || reply.status().equals("FROZEN");
    }
    private static byte[] accepted(final PartitionReply reply) throws IOException {
        if (!reply.status().equals("OK")) throw unavailable(); return reply.payload();
    }
    @FunctionalInterface private interface Operation { PartitionReply call(PartitionEndpoint endpoint, Instant attemptDeadline) throws IOException; }
    private static IOException unavailable() { return new IOException("Partition unavailable; outcome UNKNOWN"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid partition client input or routing declaration"); }
}
