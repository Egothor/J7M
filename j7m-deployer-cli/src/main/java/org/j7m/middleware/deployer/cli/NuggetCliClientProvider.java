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

/**
 * Provides a transport-neutral nugget CLI client for the J7M deployer
 * executable.
 *
 * <p>Providers are discovered through {@link java.util.ServiceLoader} by the
 * production CLI entry points. A provider owns any resources allocated during
 * {@link #createClient()} until the returned {@link NuggetCliClient} is closed
 * by the CLI invocation or shell session. Implementations must be deterministic,
 * bounded during initialization, and must not depend on concrete transport
 * implementation classes in the CLI module.</p>
 *
 * <p>Provider implementations are responsible for reporting trusted connection
 * security through {@link NuggetCliConnection#security()} after a connection is
 * established. Discovery records, endpoint strings, or object references are not
 * sufficient evidence that PIN submission is safe.</p>
 *
 * <p>Implementations must normalize transport failures through the
 * transport-neutral client contract and must not log, cache, or expose PIN
 * material, endpoint credentials, private keys, or raw transport exception
 * details. Methods may be called by the CLI session thread; implementations that
 * perform background work must define their own bounded execution and shutdown
 * policy and release resources from {@link NuggetCliClient#close()}.</p>
 */
public interface NuggetCliClientProvider {
    /**
     * Returns the stable provider identifier used for deterministic selection
     * and diagnostics.
     *
     * @return a bounded non-secret provider identifier
     */
    String providerId();

    /**
     * Creates one client instance owned by one CLI invocation or shell session.
     *
     * @return a new transport-neutral client
     * @throws RuntimeException if the provider cannot initialize a client
     */
    NuggetCliClient createClient();
}
