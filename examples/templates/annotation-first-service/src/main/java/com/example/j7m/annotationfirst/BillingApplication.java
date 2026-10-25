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

package com.example.j7m.annotationfirst;

import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.Startup;

/**
 * Template application entry point for a small annotation-first J7M service.
 *
 * <p>{@link J7mApplication} gives the application a stable group, name, and
 * version for descriptor validation. The constructor demonstrates the
 * preferred injection-first style: application code receives a business
 * service, not runtime maps, transport internals, or module-layer handles.</p>
 *
 * <p>The {@link Startup} hook is enforced by the J7M injector in lifecycle
 * order. This template does not export a live ObjectRef by itself; export is
 * performed by the application platform when the descriptor is deployed.</p>
 */
@J7mApplication(group = "com.example", name = "billing", version = "1.0.0")
public final class BillingApplication {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final BillingService billingService;

    /**
     * Creates the application from the injected billing service.
     *
     * @param billingService service constructed by J7M-native injection
     */
    @Inject
    public BillingApplication(final BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * Warms local service state at application startup.
     *
     * <p>Template users should keep startup deterministic and avoid leaking
     * credentials or internal capability details through startup errors.</p>
     */
    @Startup
    void start() {
        this.billingService.warmup();
    }
}
