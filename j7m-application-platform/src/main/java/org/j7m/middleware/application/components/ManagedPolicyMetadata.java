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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.j7m.middleware.application.api.ManagedServiceContract;

/**
 * Finite verified metadata access for optional host policy adapters, without package opens or class loading.
 * Only the complete actual export contract contributes; ordinary application helpers are not entry points.
 */
public final class ManagedPolicyMetadata {
    private ManagedPolicyMetadata() { }
    /**
     * Resolves the exact public interface method including erased parameter and return identity.
     * @param contract complete verified public export identity
     * @param businessInterface declared exported interface
     * @return exact public method
     * @throws IllegalArgumentException for a mismatched or missing contract
     */
    public static Method method(ManagedServiceContract contract, Class<?> businessInterface) {
        return ManagedMethodSurface.method(contract, businessInterface);
    }
    /**
     * Returns the finite interface/implementation lineage contributing to this actual export.
     * @param contract complete verified public identity
     * @param implementation exported implementation
     * @param businessInterface declared business interface
     * @return immutable contributing method/type declarations
     */
    public static List<AnnotatedElement> declarations(ManagedServiceContract contract, Class<?> implementation, Class<?> businessInterface) {
        return List.copyOf(ManagedMethodSurface.declarations(contract, implementation, businessInterface));
    }
    /**
     * Resolves a scalar policy: method declarations override type defaults; equal-level conflicts fail closed.
     * @param contract complete public identity
     * @param implementation exported implementation
     * @param businessInterface declared business interface
     * @param annotation scalar annotation class
     * @param <A> annotation type
     * @return effective annotation, or null when absent
     * @throws IllegalArgumentException for conflicting effective declarations
     */
    public static <A extends Annotation> A scalar(ManagedServiceContract contract, Class<?> implementation,
            Class<?> businessInterface, Class<A> annotation) {
        Set<A> methods = new HashSet<>(); Set<A> defaults = new HashSet<>();
        for (AnnotatedElement declaration : declarations(contract, implementation, businessInterface)) {
            A value = declaration.getDeclaredAnnotation(annotation);
            if (value != null) (declaration instanceof Method ? methods : defaults).add(value);
        }
        Set<A> selected = methods.isEmpty() ? defaults : methods;
        if (selected.size() > 1) throw new IllegalArgumentException("Conflicting effective managed policy");
        return selected.isEmpty() ? null : selected.iterator().next();
    }
}
