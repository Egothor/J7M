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

package org.j7m.middleware.application.components;

import java.time.Instant;
import java.util.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InjectionDiagnostic;

/**
 * Safe typed activation failure retaining structured injection evidence across the host boundary.
 * No application Throwable, constructor argument, configuration or qualifier value is retained.
 * Operators may inspect diagnostic() locally; the ordinary error carries only a stable reason code.
 */
public final class ManagedInjectionException extends MiddlewareException {
    private static final long serialVersionUID=1L;
    /**
     * Finite pre-construction failure categories, independent of exception text.
     */
    public enum Reason {
        /**
         * A class does not belong to the verified application module/artifact.
         */ CLASS_ORIGIN,
        /**
         * A component index provider cannot be created through its declared module service.
         */ INDEX_PROVIDER_ACCESS,
        /**
         * A schema index provider cannot be created through its declared module service.
         */ SCHEMA_PROVIDER_ACCESS,
        /**
         * JPMS prevents access to a declared managed entry.
         */ JPMS_ACCESS
    }
    /**
     * Immutable safe local graph evidence.
     * @serial bounded class names and stable categories only
     */
    private final InjectionDiagnostic diagnostic;
    private ManagedInjectionException(InjectionDiagnostic diagnostic) {
        super(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,"managed activation preparation failed",Map.of("reasonCode",diagnostic.reasonCode()),null,null,false,false));
        this.diagnostic=diagnostic;
    }
    /**
     * Preserves an actual managed graph failure without retaining its original Throwable.
     * @param failure actual container failure with internally bounded diagnostic metadata
     * @return safe immutable diagnostic exception
     * @throws NullPointerException for missing evidence
     */
    public static ManagedInjectionException from(ManagedContainer.Failure failure) {return new ManagedInjectionException(Objects.requireNonNull(failure).diagnostic());}
    /**
     * Creates safe module/provider evidence from an actual local class token.
     * @param component actual class involved, null only when provider enumeration failed before a token
     * @param reason finite failure category
     * @return payload-free diagnostic exception
     * @throws NullPointerException for a missing reason
     */
    public static ManagedInjectionException preflight(Class<?> component,Reason reason) {
        Objects.requireNonNull(reason);String name=component==null?"":component.getName();if(name.length()>256) name=name.substring(0,256);
        return new ManagedInjectionException(new InjectionDiagnostic(Instant.EPOCH,name,"preflight","FAILED",reason.name(),name.isEmpty()?Map.of():Map.of("graphPath",name)));
    }
    /**
     * Returns immutable structured evidence from the actual host validation failure.
     * @return bounded graph or module diagnostic without application exception messages
     */
    public InjectionDiagnostic diagnostic() {return diagnostic;}
}
