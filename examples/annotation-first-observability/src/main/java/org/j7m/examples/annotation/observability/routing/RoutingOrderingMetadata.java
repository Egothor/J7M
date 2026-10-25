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

package org.j7m.examples.annotation.observability.routing;

import org.j7m.middleware.annotations.DataLocality;
import org.j7m.middleware.annotations.HighAvailability;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.OrderedBy;
import org.j7m.middleware.annotations.PreferLocal;
import org.j7m.middleware.annotations.Priority;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.ShardKey;

/**
 * Focused routing and ordering metadata example.
 *
 * <p>{@link DataLocality}, {@link OrderedBy}, {@link ShardKey},
 * {@link Priority}, {@link HighAvailability}, and {@link PreferLocal} provide
 * descriptor metadata for selectors and schedulers. This example does not
 * start a selector, reorder work, or provide failover by itself; J7M routing
 * policy remains the authoritative consumer.</p>
 */
@J7mService(name = "//apps/example/annotation/observability/RoutingOrdering", exported = false)
@HighAvailability
@PreferLocal
@DataLocality("region=eu")
public final class RoutingOrderingMetadata {
    // J7M teaching: routing hints are public metadata; they are not endpoint
    // credentials, load-balancer state, or proof that a candidate is allowed.

    /**
     * Records routing metadata for one tenant.
     *
     * @param tenant public tenant key
     * @return deterministic metadata summary
     */
    @RemoteMethod
    @OrderedBy("tenant")
    @Priority(10)
    @ShardKey("tenant")
    public String route(@ShardKey("tenant") final String tenant) {
        return "routing=" + tenant + " ordered";
    }
}
