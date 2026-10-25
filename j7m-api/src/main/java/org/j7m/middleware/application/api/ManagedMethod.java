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

import java.util.List;

/**
 * Internal component Java method signature emitted in a versioned component index.
 * The index format version is not a service contract version. This record alone is not a wire
 * identity: bind it to an explicit contract ID and contract version before wire or durable use.
 * The A00 preflight Report supplies that envelope; managed hosting supplies verified export metadata.
 * @param declaringType declaring binary type name
 * @param name method name
 * @param parameterTypes ordered erased binary names, including JVM array names
 * @param returnType erased binary return name
 */
public record ManagedMethod(String declaringType, String name, List<String> parameterTypes, String returnType) {
    /**
     * Validates bounded immutable signature metadata.
     * @param declaringType nonblank binary name
     * @param name nonblank method name
     * @param parameterTypes at most64 nonblank binary names
     * @param returnType nonblank return name
     * @throws IllegalArgumentException for malformed metadata
     */
    public ManagedMethod {
        if (declaringType == null || declaringType.isBlank() || declaringType.length() > 512 || name == null
                || name.isBlank() || name.length() > 256 || parameterTypes == null || parameterTypes.size() > 64
                || returnType == null || returnType.isBlank() || returnType.length() > 512) throw new IllegalArgumentException("Invalid managed method");
        parameterTypes = List.copyOf(parameterTypes);
        for (String type : parameterTypes) if (type.isBlank() || type.length() > 512) throw new IllegalArgumentException("Invalid managed parameter type");
    }
}
