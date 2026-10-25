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

package org.j7m.middleware.api;

import java.util.List;
import java.util.Map;

/**
 * Thread-safe public entry point for exporting and locating middleware objects.
 *
 * <p>Implementations own transport endpoints, registry bindings, heartbeat
 * tasks, lifecycle hooks, and exported object maps. Public methods throw
 * {@link MiddlewareException} with normalized error codes instead of raw
 * reflection, registry, or transport failures.</p>
 *
 * <p>The runtime is the owner of exported-object lifecycle. Calls may block on
 * registry access, local lifecycle hooks, transport dispatch, or caller
 * supplied deadlines. Security and authorization checks are evaluated before
 * invocation, and status output must be redacted so principals, credentials,
 * and sensitive metadata are not exposed. JPMS boundaries remain authoritative:
 * exporting an object does not open internal packages or grant access to
 * non-exported implementation classes.</p>
 */
public interface MiddlewareRuntime extends AutoCloseable {
    /**
     * Returns this runtime's immutable identity.
     *
     * @return runtime identity
     */
    RuntimeIdentity identity();

    /**
     * Exports a Java object under a logical service name.
     *
     * @param serviceName logical service name
     * @param instance object instance
     * @param primaryInterface primary Java interface exposed remotely
     * @param options export options
     * @param <T> primary Java interface type
     * @return registration handle
     * @throws MiddlewareException when arguments are invalid, lifecycle hooks fail, or registry/transport export fails
     */
    <T> ObjectRegistration<T> export(String serviceName, T instance, Class<T> primaryInterface, ExportOptions options);

    /**
     * Prepares a Java object export without making it discoverable.
     *
     * <p>The returned handle owns any lifecycle and transport-local state
     * created during preparation. The object is not returned by
     * {@link #list(String, List, Map, LookupOptions)} and cannot be found by
     * {@link #lookup(String, Class, LookupOptions)} until
     * {@link PreparedObjectRegistration#commit()} succeeds. This enables
     * application activation code to prepare all declared services first and
     * publish them only after readiness gates have closed. Callers must close
     * every uncommitted prepared handle when any part of the publication group
     * fails.</p>
     *
     * @param serviceName logical service name
     * @param instance object instance
     * @param primaryInterface primary Java interface exposed remotely
     * @param options export options
     * @param <T> primary Java interface type
     * @return prepared registration handle
     * @throws MiddlewareException when arguments are invalid, lifecycle hooks
     *         fail, transport preparation fails, or the runtime does not support
     *         prepared publication
     */
    <T> PreparedObjectRegistration<T> prepareExport(String serviceName, T instance, Class<T> primaryInterface, ExportOptions options);

    /**
     * Commits a prepared export group as one publication operation.
     *
     * <p>All supplied handles must have been returned by this runtime and must
     * still be uncommitted. Implementations must validate the entire group
     * before making any member visible through {@link #list(String, List, Map,
     * LookupOptions)}, {@link #lookup(String, Class, LookupOptions)}, status
     * snapshots, registry records, or heartbeats. If any mandatory publication
     * step fails, all records and local publication state created for the group
     * are rolled back before the method throws. Callers remain responsible for
     * closing the prepared handles after a failed commit so transport-local
     * preparation state and lifecycle ownership are released.</p>
     *
     * <p>The returned registration list preserves the order of the supplied
     * prepared registrations. The method may block on registry publication and
     * heartbeat scheduling, and failures are reported as normalized
     * {@link MiddlewareException} instances.</p>
     *
     * @param preparedExports prepared export handles to publish
     * @return committed registration handles in input order
     * @throws MiddlewareException when the group is empty, contains invalid
     *         handles, contains handles from another runtime, has already been
     *         closed or committed, or when publication fails
     */
    List<ObjectRegistration<?>> commitPreparedExports(List<? extends PreparedObjectRegistration<?>> preparedExports);

    /**
     * Looks up one object by service name and Java interface.
     *
     * @param serviceName logical service name
     * @param api required Java interface
     * @param options lookup options
     * @param <T> Java interface type
     * @return local instance, transport proxy, or HA proxy
     * @throws MiddlewareException with {@code NOT_FOUND}, {@code INTERFACE_MISMATCH}, {@code REGISTRY_UNAVAILABLE}, or transport errors
     */
    <T> T lookup(String serviceName, Class<T> api, LookupOptions options);

    /**
     * Lists matching object references.
     *
     * @param serviceName logical service name
     * @param interfaces required interface names
     * @param requiredParams required metadata key/value pairs
     * @param options lookup options
     * @return immutable list of matching references
     * @throws MiddlewareException when registry access fails
     */
    List<ObjectRef> list(String serviceName, List<String> interfaces, Map<String, String> requiredParams, LookupOptions options);

    /**
     * Creates a proxy for one exact concrete reference without registry reselection or HA retry.
     * Each proxy invocation retains normal transport/security behavior and its explicit budget;
     * creating a reference does not grant authority or durable deduplication. Implementations that
     * cannot provide exact-reference semantics fail closed for source compatibility.
     * @param <T> ordinary business interface
     * @param reference concrete selected identity
     * @param contract required interface exposed by reference
     * @param options per-call proxy budget and safe metadata
     * @return exact-reference business proxy
     * @throws MiddlewareException for unavailable support, invalid references or incompatible contracts
     */
    default <T> T reference(ObjectRef reference, Class<T> contract, ProxyOptions options) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Exact-reference connection is unsupported");
    }

    /**
     * Returns a read-only status snapshot.
     *
     * @return runtime status
     */
    RuntimeStatus status();

    /**
     * Shuts the runtime down and releases owned resources. The method is idempotent.
     */
    @Override
    void close();
}
