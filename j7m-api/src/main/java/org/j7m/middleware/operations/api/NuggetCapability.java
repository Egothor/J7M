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

package org.j7m.middleware.operations.api;

/**
 * Public nugget operation capability.
 *
 * <p>Capability presence says only that a nugget knows how to evaluate a
 * request. It is not authentication or authorization.</p>
 */
public enum NuggetCapability {
    /**
     * Read safe nugget identity and configuration summary.
     */
    READ_INFO,
    /**
     * Evaluate a PIN admission request.
     */
    PIN_ADMISSION,
    /**
     * Report safe runtime status.
     */
    READ_RUNTIME_STATUS,
    /**
     * Report safe JVM and process environment status.
     */
    READ_ENVIRONMENT,
    /**
     * Report safe disk and storage-root status.
     */
    READ_DISK,
    /**
     * Report health status.
     */
    READ_HEALTH,
    /**
     * Report readiness status.
     */
    READ_READINESS,
    /**
     * Report safe transport status.
     */
    READ_TRANSPORTS,
    /**
     * Report safe registry status.
     */
    READ_REGISTRY,
    /**
     * Report safe repository status.
     */
    READ_REPOSITORY,
    /**
     * Report safe hosted application status.
     */
    READ_APPLICATIONS,
    /**
     * Report safe hosted object/service status.
     */
    READ_OBJECTS,
    /**
     * Report bounded safe metrics.
     */
    READ_METRICS,
    /**
     * Report bounded safe recent errors.
     */
    READ_ERRORS,
    /**
     * Plan or start hosted applications through the public deployer authority.
     */
    START_APPLICATION,
    /**
     * Drain hosted applications through the public deployer authority.
     */
    DRAIN_APPLICATION,
    /**
     * Stop hosted applications through the public deployer authority.
     */
    STOP_APPLICATION,
    /**
     * Unload hosted applications through the public deployer authority.
     */
    UNLOAD_APPLICATION,
    /**
     * Drain the owning nugget and reject new mutating work where implemented.
     */
    DRAIN_NUGGET,
    /**
     * Shut down the owning nugget through the runtime close path.
     */
    SHUTDOWN_NUGGET,
    /**
     * Evaluate explicitly enabled injected self-termination for the owning nugget.
     */
    FORCE_TERMINATION,
    /**
     * Discover local and configured nugget administration endpoints.
     */
    DISCOVER_NUGGETS
}
