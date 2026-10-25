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

package org.j7m.middleware.application.api;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import org.j7m.middleware.api.ObjectRef;

/**
 * Immutable trusted-host description of one logical dependency call. Arguments and caller
 * authority stay in its attempt closure; interception never changes the pinned target or deadline.
 * The absolute deadline uses the local JVM's {@link System#nanoTime()} domain and is not wire data.
 * @param contract complete public contract identity
 * @param contractType declared business interface, including its inherited declarations
 * @param method actual business-interface method corresponding to that identity
 * @param target exact reference captured once for the logical call
 * @param deadlineNanos absolute monotonic deadline; negative values are valid
 */
public record ClientCall(ManagedServiceContract contract, Class<?> contractType, Method method, ObjectRef target, long deadlineNanos) {
    /**
     * Validates that policy metadata belongs to the actual full method signature.
     * @param contract complete public contract
     * @param contractType declared business interface
     * @param method actual business-interface method
     * @param target exact target reference
     * @param deadlineNanos absolute local monotonic deadline
     * @throws NullPointerException if a required component is null
     * @throws IllegalArgumentException if the signature does not match
     */
    public ClientCall {
        Objects.requireNonNull(contract); Objects.requireNonNull(contractType); Objects.requireNonNull(method); Objects.requireNonNull(target);
        var actual = new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName());
        if (!contractType.isInterface() || !method.getDeclaringClass().isInterface()
                || !method.getDeclaringClass().isAssignableFrom(contractType) || !actual.equals(contract.method())) throw new IllegalArgumentException("Invalid client method identity");
    }
}
