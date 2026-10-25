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

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable identity of one Java interface method.
 *
 * @param declaringInterfaceName fully qualified declaring interface name
 * @param methodName Java method name
 * @param parameterTypeNames ordered parameter type names
 * @param returnTypeName return type name
 * @param signatureHash deterministic signature hash
 */
public record MethodDescriptor(
        String declaringInterfaceName,
        String methodName,
        List<String> parameterTypeNames,
        String returnTypeName,
        String signatureHash) implements Serializable {

    /**
     * Creates an immutable method descriptor.
     */
    public MethodDescriptor {
        if (declaringInterfaceName == null || declaringInterfaceName.isBlank()) {
            throw new IllegalArgumentException("declaringInterfaceName must not be blank");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        parameterTypeNames = parameterTypeNames == null ? List.of() : List.copyOf(parameterTypeNames);
        returnTypeName = returnTypeName == null ? "void" : returnTypeName;
        signatureHash = signatureHash == null ? Integer.toHexString((declaringInterfaceName + "#" + methodName + parameterTypeNames + returnTypeName).hashCode()) : signatureHash;
    }

    /**
     * Builds a descriptor from a reflection method.
     *
     * @param method Java method
     * @return method descriptor
     */
    public static MethodDescriptor fromMethod(final Method method) {
        List<String> parameterNames = new ArrayList<>();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (Class<?> parameterType : parameterTypes) {
            parameterNames.add(parameterType.getName());
        }
        String declaringName = method.getDeclaringClass().getName();
        String returnName = method.getReturnType().getName();
        return new MethodDescriptor(declaringName, method.getName(), parameterNames, returnName, null);
    }
}
