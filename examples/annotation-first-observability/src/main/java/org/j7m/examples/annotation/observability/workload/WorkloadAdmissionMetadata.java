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

package org.j7m.examples.annotation.observability.workload;

import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.annotations.BackpressureAware;
import org.j7m.middleware.annotations.Cancellable;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.LongRunning;
import org.j7m.middleware.annotations.RateLimited;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.ResourceRequirement;

/**
 * Focused workload and admission metadata example.
 *
 * <p>{@link Async}, {@link Cancellable}, {@link LongRunning},
 * {@link BackpressureAware}, {@link RateLimited}, and
 * {@link ResourceRequirement} describe scheduler and admission intent as
 * descriptor metadata. They are metadata-only in this example: no scheduler,
 * overload controller, rate limiter, or resource admission engine is started by
 * these annotations.</p>
 */
@J7mService(name = "//apps/example/annotation/observability/WorkloadAdmission", exported = false)
@ResourceRequirement("cpu<=1,memory<=256MiB")
public final class WorkloadAdmissionMetadata {
    // J7M teaching: workload annotations are grouped separately so readers can
    // see that admission policy is a configured consumer, not annotation magic.

    /**
     * Records workload metadata for one public task key.
     *
     * @param taskKey public task key
     * @return deterministic metadata summary
     */
    @RemoteMethod
    @Async
    @Cancellable
    @LongRunning
    @BackpressureAware
    @RateLimited("task")
    public String submit(final String taskKey) {
        return "workload=" + taskKey + " metadata-only";
    }
}
