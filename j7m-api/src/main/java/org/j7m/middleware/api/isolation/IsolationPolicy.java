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

package org.j7m.middleware.api.isolation;

/**
 * Complete policy set for an isolated execution.
 *
 * <p>The policy is immutable and thread-safe. It controls J7M-provided
 * filesystem, network, environment, and stream-capture capabilities. It does
 * not assert kernel-level sandboxing for arbitrary user code.</p>
 *
 * @param fileSystemPolicy filesystem policy
 * @param networkEgressPolicy network egress policy
 * @param environmentPolicy environment policy
 * @param stdoutPolicy stdout capture policy
 * @param stderrPolicy stderr capture policy
 */
public record IsolationPolicy(
        FileSystemPolicy fileSystemPolicy,
        NetworkEgressPolicy networkEgressPolicy,
        EnvironmentPolicy environmentPolicy,
        StdoutPolicy stdoutPolicy,
        StderrPolicy stderrPolicy) {
    /**
     * Creates a validated isolation policy.
     */
    public IsolationPolicy {
        fileSystemPolicy = fileSystemPolicy == null ? FileSystemPolicy.denyAll() : fileSystemPolicy;
        networkEgressPolicy = networkEgressPolicy == null ? NetworkEgressPolicy.denyAll() : networkEgressPolicy;
        environmentPolicy = environmentPolicy == null ? EnvironmentPolicy.denyAll() : environmentPolicy;
        stdoutPolicy = stdoutPolicy == null ? new StdoutPolicy(StreamCapturePolicy.defaults()) : stdoutPolicy;
        stderrPolicy = stderrPolicy == null ? new StderrPolicy(StreamCapturePolicy.defaults()) : stderrPolicy;
    }

    /**
     * Creates a conservative deny-by-default policy.
     *
     * @return default policy
     */
    public static IsolationPolicy defaults() {
        return new IsolationPolicy(FileSystemPolicy.denyAll(), NetworkEgressPolicy.denyAll(),
                EnvironmentPolicy.denyAll(), new StdoutPolicy(StreamCapturePolicy.defaults()),
                new StderrPolicy(StreamCapturePolicy.defaults()));
    }
}
