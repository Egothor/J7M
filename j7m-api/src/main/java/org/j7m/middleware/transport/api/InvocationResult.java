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

package org.j7m.middleware.transport.api;

/**
 * Host-local logical outcome passed only to trusted result finalizers, never serialized.
 * Diagnostic rendering excludes application values and exception text.
 * @param value logical value, possibly null; discarded when failure is present
 * @param failure application or policy failure, null for success
 * @param diagnostics trusted protocol status set by a registered host finalizer, never copied from application throwables
 */
public record InvocationResult(Object value, Throwable failure, java.util.Map<String, String> diagnostics) {
    /**
     * Preserves the outcome constructor with no trusted protocol diagnostics.
     * @param value logical result
     * @param failure logical failure, nullable
     */
    public InvocationResult(Object value, Throwable failure) { this(value, failure, java.util.Map.of()); }
    /**
     * Creates an outcome without retaining a value alongside a failure.
     */
    public InvocationResult {
        if (failure != null) value = null;
        diagnostics = java.util.Map.copyOf(diagnostics);
        if (diagnostics.size() > 1 || diagnostics.entrySet().stream().anyMatch(entry -> !entry.getKey().equals("status")
                || !entry.getValue().matches("[A-Z_]{1,48}"))) throw new IllegalArgumentException("Invalid host protocol diagnostic");
    }
    /**
     * Creates a successful logical result.
     * @param value result, possibly null
     * @return successful host outcome
     */
    public static InvocationResult success(Object value) {
        return new InvocationResult(value, null);
    }
    /**
     * Creates a failed logical result without evaluating application exception methods.
     * @param failure non-null failure
     * @return failed host outcome
     */
    public static InvocationResult failure(Throwable failure) { return new InvocationResult(null, java.util.Objects.requireNonNull(failure)); }
    /**
     * Returns a payload-free diagnostic form.
     * @return success/failure classification only
     */
    @Override public String toString() { return "InvocationResult[failed=" + (failure != null) + "]"; }
}
