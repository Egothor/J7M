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

package org.j7m.middleware.deployer.api;

/**
 * Immutable local-runtime upgrade options.
 *
 * @param strategy upgrade strategy
 * @param rollingBatchSize number of local bindings to replace per rolling step
 * @param canaryInvocations number of deterministic canary test invocations
 * @param canarySuccessThreshold required canary successes before promotion
 * @param retainPrevious whether previous version may remain available for rollback
 */
public record UpgradeOptions(
        String strategy,
        int rollingBatchSize,
        int canaryInvocations,
        int canarySuccessThreshold,
        boolean retainPrevious) {
    /**
     * Creates immutable upgrade options.
     */
    public UpgradeOptions {
        strategy = strategy == null || strategy.isBlank() ? "stop-start" : strategy;
        rollingBatchSize = Math.max(1, rollingBatchSize);
        canaryInvocations = Math.max(0, canaryInvocations);
        canarySuccessThreshold = Math.max(0, canarySuccessThreshold);
    }

    /**
     * Compatibility constructor using conservative defaults.
     *
     * @param strategy upgrade strategy
     */
    public UpgradeOptions(final String strategy) {
        this(strategy, 1, 2, 2, true);
    }

    /**
     * Returns stop-start upgrade options.
     *
     * @return stop-start options
     */
    public static UpgradeOptions stopStart() {
        return new UpgradeOptions("stop-start");
    }

    /**
     * Creates rolling upgrade options.
     *
     * @return rolling upgrade options
     */
    public static UpgradeOptions rolling() {
        return new UpgradeOptions("rolling", 1, 0, 0, true);
    }

    /**
     * Creates blue-green upgrade options.
     *
     * @return blue-green upgrade options
     */
    public static UpgradeOptions blueGreen() {
        return new UpgradeOptions("blue-green", 1, 0, 0, true);
    }

    /**
     * Creates deterministic canary-baseline upgrade options.
     *
     * @return canary-baseline options
     */
    public static UpgradeOptions canaryBaseline() {
        return new UpgradeOptions("canary-baseline", 1, 2, 2, true);
    }
}
