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

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Portable reference to one concrete exported Java object instance.
 *
 * <p>An {@code ObjectRef} is not just an endpoint. It identifies an object
 * instance, describes the logical service it belongs to, and carries enough
 * metadata for registry lookup and HA replacement selection. The record is
 * immutable and thread-safe.</p>
 *
 * <p>References are routing evidence, not bearer authorization tokens. A caller
 * still needs normal security context, authorization, fencing, and deadline
 * checks before invocation. Metadata must be safe for status and audit output;
 * credentials and sensitive values belong in security context, not in this
 * record. JPMS module boundaries are unaffected by a reference: a reference to
 * an exported object does not grant reflective access to non-exported packages.
 * Compatibility is based on stable public fields and interface names, so
 * future versions may add metadata keys but must preserve these record
 * components.</p>
 *
 * @param objectId unique exported object identifier within the hosting runtime
 * @param logicalName concrete logical object name
 * @param serviceName logical service name used for lookup and HA alternatives
 * @param runtimeId hosting runtime identifier
 * @param registryContext registry context used for isolation
 * @param interfaces exposed Java interface names
 * @param transport transport identifier
 * @param endpoint transport endpoint address
 * @param leaseUntil lease expiration timestamp in milliseconds, or {@code 0} when not leased
 * @param generation export generation used to distinguish restarted instances
 * @param metadata public object metadata and query parameters
 */
public record ObjectRef(
        String objectId,
        String logicalName,
        String serviceName,
        String runtimeId,
        String registryContext,
        List<String> interfaces,
        String transport,
        String endpoint,
        long leaseUntil,
        long generation,
        Map<String, String> metadata) implements Serializable {

    /**
     * Creates a reference with immutable collection fields.
     */
    public ObjectRef {
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId must not be blank");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
        logicalName = logicalName == null || logicalName.isBlank() ? serviceName + "/" + objectId : logicalName;
        registryContext = registryContext == null || registryContext.isBlank() ? "default" : registryContext;
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        transport = transport == null ? "" : transport;
        endpoint = endpoint == null ? "" : endpoint;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Returns whether the reference lease is active at the supplied timestamp.
     *
     * @param nowMillis wall-clock timestamp in milliseconds
     * @return true when no lease is set or the lease is still active
     */
    public boolean isLeaseActive(final long nowMillis) {
        return this.leaseUntil == 0L || this.leaseUntil > nowMillis;
    }

    /**
     * Returns a copy of this reference with a new lease timestamp.
     *
     * @param newLeaseUntil new lease timestamp in milliseconds
     * @return updated reference
     */
    public ObjectRef withLeaseUntil(final long newLeaseUntil) {
        return new ObjectRef(this.objectId, this.logicalName, this.serviceName, this.runtimeId, this.registryContext,
                this.interfaces, this.transport, this.endpoint, newLeaseUntil, this.generation, this.metadata);
    }

    /**
     * Returns a copy of this reference with transport details.
     *
     * @param newTransport transport identifier
     * @param newEndpoint endpoint address
     * @return updated reference
     */
    public ObjectRef withTransport(final String newTransport, final String newEndpoint) {
        return new ObjectRef(this.objectId, this.logicalName, this.serviceName, this.runtimeId, this.registryContext,
                this.interfaces, newTransport, newEndpoint, this.leaseUntil, this.generation, this.metadata);
    }

    /**
     * Returns a copy of this reference with new metadata.
     *
     * @param newMetadata metadata to attach
     * @return updated reference
     */
    public ObjectRef withMetadata(final Map<String, String> newMetadata) {
        return new ObjectRef(this.objectId, this.logicalName, this.serviceName, this.runtimeId, this.registryContext,
                this.interfaces, this.transport, this.endpoint, this.leaseUntil, this.generation, newMetadata);
    }

    /**
     * Returns a redacted diagnostic representation of this reference.
     *
     * <p>The returned text intentionally omits endpoint text, metadata keys,
     * and metadata values. Object references may appear in status, audit, proxy,
     * and error diagnostics; metadata is caller controlled and must not be
     * rendered through {@code Map.toString()} or {@code keySet().toString()}.</p>
     *
     * @return redacted reference diagnostics
     */
    @Override
    public String toString() {
        return "ObjectRef[objectId=" + this.objectId
                + ", serviceName=" + this.serviceName
                + ", runtimeId=" + this.runtimeId
                + ", registryContext=" + this.registryContext
                + ", interfaces=" + this.interfaces.size()
                + ", transport=" + this.transport
                + ", endpoint=<redacted>"
                + ", leaseUntil=" + this.leaseUntil
                + ", generation=" + this.generation
                + ", metadata=<redacted:" + this.metadata.size() + ">"
                + "]";
    }
}
