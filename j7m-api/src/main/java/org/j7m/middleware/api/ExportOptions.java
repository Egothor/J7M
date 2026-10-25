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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable options used when exporting an object.
 *
 * @param localOnly whether the object should remain local and skip registry binding
 * @param transportId requested transport identifier
 * @param registryContext registry context override
 * @param interfaces additional exposed interface names
 * @param metadata public metadata and query parameters
 * @param leaseDuration registry lease duration for global exports
 * @param heartbeatInterval heartbeat interval for global exports
 */
public record ExportOptions(
        boolean localOnly,
        String transportId,
        String registryContext,
        List<String> interfaces,
        Map<String, String> metadata,
        Duration leaseDuration,
        Duration heartbeatInterval) {

    /**
     * Creates immutable export options.
     */
    public ExportOptions {
        transportId = transportId == null || transportId.isBlank() ? "grpc" : transportId;
        registryContext = registryContext == null || registryContext.isBlank() ? "default" : registryContext;
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30L) : leaseDuration;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(10L) : heartbeatInterval;
    }

    /**
     * Returns default global gRPC export options.
     *
     * @return default options
     */
    public static ExportOptions defaults() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    /**
     * Returns local-only options.
     *
     * @return local-only options
     */
    public static ExportOptions localOnlyOptions() {
        return new ExportOptions(true, "local", "default", List.of(), Map.of(), Duration.ZERO, Duration.ZERO);
    }
}
