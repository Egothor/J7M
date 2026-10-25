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

package org.j7m.middleware.deployer.cli;

import org.j7m.middleware.operations.api.NuggetDiscovery;

/**
 * Transport-neutral client used by the J7M CLI nugget console.
 *
 * <p>A client instance is owned by one CLI invocation or one interactive shell
 * session. Implementations must be bounded, deterministic, and safe to close
 * more than once. The client invokes the accepted headless nugget discovery,
 * admission, and read-only status contracts; it must not implement a separate
 * administration model or treat discovery records as authorization evidence.</p>
 *
 * <p>Implementations must not expose clear PIN material, endpoint credentials,
 * transport internals, raw remote exceptions, or hidden target identifiers
 * through returned DTOs, exception messages, diagnostics, or {@code toString}
 * output.</p>
 */
public interface NuggetCliClient extends AutoCloseable {
    /**
     * Performs a bounded discovery refresh.
     *
     * @param query discovery query
     * @return discovery result
     */
    NuggetDiscovery.Result discover(NuggetDiscovery.Query query);

    /**
     * Resolves a selector against the available discovery view.
     *
     * @param selector selector
     * @return resolution result
     */
    NuggetDiscovery.Resolution resolve(NuggetDiscovery.Selector selector);

    /**
     * Connects to one selected discovery record.
     *
     * @param record selected record
     * @return owned connection
     */
    NuggetCliConnection connect(NuggetDiscovery.Record record);

    /**
     * Closes resources owned by this client.
     */
    @Override
    void close();

    /**
     * Creates an unavailable fallback client with the default safe reason.
     *
     * @return unavailable client
     */
    static NuggetCliClient unavailable() {
        return new NuggetUnavailableClient("no nugget client transport is configured");
    }

    /**
     * Creates an unavailable fallback client with a safe normalized reason.
     *
     * @param reason safe reason text
     * @return unavailable client
     */
    static NuggetCliClient unavailable(final String reason) {
        return new NuggetUnavailableClient(reason);
    }
}
