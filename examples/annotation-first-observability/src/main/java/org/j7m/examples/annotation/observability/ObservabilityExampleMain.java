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

package org.j7m.examples.annotation.observability;

import org.j7m.examples.annotation.observability.benchmark.ObservabilityBenchmarkMetadata;
import org.j7m.examples.annotation.observability.recovery.RecoveryProgressCallbackMetadata;
import org.j7m.examples.annotation.observability.routing.RoutingOrderingMetadata;
import org.j7m.examples.annotation.observability.workload.WorkloadAdmissionMetadata;

/**
 * Deterministic smoke entry point for the split observability example.
 *
 * <p>The entry point invokes one method from each focused source unit and
 * prints metadata markers. It proves the example is no longer represented by
 * one overloaded service class.</p>
 */
public final class ObservabilityExampleMain {
    private ObservabilityExampleMain() {
    }

    /**
     * Runs the observability metadata smoke flow.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        // J7M teaching: each focused source unit contributes one marker, so
        // validation fails if observability collapses back into an overloaded
        // class with unrelated annotations.
        final WorkloadAdmissionMetadata workload = new WorkloadAdmissionMetadata();
        final RoutingOrderingMetadata routing = new RoutingOrderingMetadata();
        final RecoveryProgressCallbackMetadata recovery = new RecoveryProgressCallbackMetadata();
        final ObservabilityBenchmarkMetadata benchmark = new ObservabilityBenchmarkMetadata();
        final StringBuilder callbackValue = new StringBuilder();

        recovery.publish("J7M_OBSERVABILITY_SENTINEL_SECRET", callbackValue::append);

        // J7M teaching: output is descriptor metadata and redaction proof, not
        // scheduler, selector, recovery-engine, or benchmark-runner evidence.
        System.out.println("annotation-first observability workload: " + workload.submit("task-7"));
        System.out.println("annotation-first observability routing: " + routing.route("tenant-7"));
        System.out.println("annotation-first observability recovery: "
                + recovery.checkpoint("42") + " " + recovery.restore("42") + " " + callbackValue);
        System.out.println("annotation-first observability benchmark: " + benchmark.latencyProbe());
    }
}
