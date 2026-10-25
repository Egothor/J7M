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

package org.j7m.middleware.tests.fixtures;

import org.j7m.middleware.application.api.ApplicationContext;

/**
 * Public lifecycle fixtures used by JPMS lifecycle selection hardening tests.
 */
public final class LifecycleFixtures {
    private LifecycleFixtures() {
    }

    /**
     * Fixture with a broad overload and an exact lifecycle hook.
     */
    public static final class ExactAndObjectLifecycle {
        private int exactInvocations;
        private int objectInvocations;

        /**
         * Broad overload that must not be selected.
         *
         * @param context ignored context
         */
        public void start(final Object context) {
            this.objectInvocations++;
        }

        /**
         * Exact lifecycle hook that must be selected.
         *
         * @param context application context
         */
        public void start(final ApplicationContext context) {
            this.exactInvocations++;
        }

        /**
         * Returns exact hook invocation count.
         *
         * @return exact hook invocation count
         */
        public int exactInvocations() {
            return this.exactInvocations;
        }

        /**
         * Returns broad hook invocation count.
         *
         * @return broad hook invocation count
         */
        public int objectInvocations() {
            return this.objectInvocations;
        }
    }

    /**
     * Fixture with only a broad overload.
     */
    public static final class ObjectOnlyLifecycle {
        /**
         * Broad overload that must be rejected.
         *
         * @param context ignored context
         */
        public void start(final Object context) {
        }
    }

    /**
     * Fixture with a non-void lifecycle-shaped method.
     */
    public static final class NonVoidLifecycle {
        /**
         * Non-void method that must be rejected.
         *
         * @param context application context
         * @return diagnostic text
         */
        public String start(final ApplicationContext context) {
            return "invalid";
        }
    }

    /**
     * Fixture whose lifecycle hook throws sensitive-looking detail.
     */
    public static final class ThrowingLifecycle {
        /**
         * Throws from the lifecycle hook.
         *
         * @param context application context
         */
        public void start(final ApplicationContext context) {
            throw new IllegalStateException("PRIVATE token should not escape");
        }
    }

    /**
     * Fixture whose lifecycle hook has an interrupted cause chain.
     */
    public static final class InterruptedLifecycle {
        /**
         * Throws an ordinary lifecycle failure with an interrupted cause.
         *
         * @param context application context
         */
        public void start(final ApplicationContext context) {
            throw new IllegalStateException("interrupted lifecycle", new InterruptedException("interrupted"));
        }
    }

    /**
     * Controlled fatal error used to prove fatal lifecycle failures are not
     * swallowed by lifecycle mapping or rollback cleanup.
     */
    public static final class ControlledVirtualMachineError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the controlled fatal error.
         */
        public ControlledVirtualMachineError() {
            super("controlled fatal lifecycle failure");
        }
    }

    /**
     * Fixture whose rollback-style stop hook throws a controlled fatal error.
     */
    public static final class FatalStopLifecycle {
        /**
         * Throws a controlled fatal VM error.
         *
         * @param context application context
         */
        public void stop(final ApplicationContext context) {
            throw new ControlledVirtualMachineError();
        }
    }

    /**
     * Non-canonical context type with a lifecycle-like name.
     */
    public interface LookalikeApplicationContext {
    }

    /**
     * Fixture with a lookalike context parameter.
     */
    public static final class LookalikeContextLifecycle {
        /**
         * Lifecycle-like hook that must be rejected because the parameter is
         * not the canonical application context.
         *
         * @param context lookalike context
         */
        public void start(final LookalikeApplicationContext context) {
        }
    }

    /**
     * Default lifecycle declaration used to detect JVM reflection ambiguity
     * if a runtime exposes both inherited and concrete exact candidates.
     */
    public interface DefaultLifecycle {
        /**
         * Default lifecycle hook.
         *
         * @param context application context
         */
        default void ambiguous(final ApplicationContext context) {
        }
    }

    /**
     * Fixture overriding a default lifecycle declaration.
     */
    public static final class AmbiguousIfReflectionExposesInheritedLifecycle implements DefaultLifecycle {
        /**
         * Concrete lifecycle hook.
         *
         * @param context application context
         */
        @Override
        public void ambiguous(final ApplicationContext context) {
        }
    }

    /**
     * Generic base fixture that causes the Java compiler to emit a bridge
     * method in the concrete subclass.
     *
     * @param <T> context type
     */
    public abstract static class GenericLifecycle<T> {
        /**
         * Generic lifecycle hook.
         *
         * @param context context value
         */
        public abstract void bridge(final T context);
    }

    /**
     * Fixture with a compiler-generated bridge method and one exact hook.
     */
    public static final class BridgeLifecycle extends GenericLifecycle<ApplicationContext> {
        private int invocations;

        /**
         * Exact hook; the generated bridge must be ignored.
         *
         * @param context application context
         */
        @Override
        public void bridge(final ApplicationContext context) {
            this.invocations++;
        }

        /**
         * Returns exact hook invocation count.
         *
         * @return exact hook invocation count
         */
        public int invocations() {
            return this.invocations;
        }
    }
}
