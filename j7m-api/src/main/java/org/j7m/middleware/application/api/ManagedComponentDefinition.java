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
import org.j7m.middleware.annotations.Scope;

/**
 * Generated direct-access construction plan; component metadata must remain immutable.
 * Runtime validation precedes creation. Implementations call only public/package-accessible
 * members from their own generated package and do not require global JPMS opens.
 * @param <T> declared product contract type
 */
public interface ManagedComponentDefinition<T> {
    /**
     * Returns the exposed product contract.
     * @return nonnull erased contract
     */
    Class<T> type();
    /**
     * Returns the exact optional product name.
     * @return nonnull name, empty for unnamed
     */
    String name();
    /**
     * Returns exact custom qualifiers, including default members.
     * @return immutable qualifiers
     */
    List<ManagedQualifier> qualifiers();
    /**
     * Returns the declared lifetime.
     * @return activation, call or prototype
     */
    Scope.Kind scope();
    /**
     * Returns every constructor, injected-field and factory-receiver dependency.
     * @return finite immutable dependency list, including lazy edges
     */
    List<ManagedDependency> dependencies();
    /**
     * Creates the product with direct generated access.
     * @param resolver resolver restricted to this definition's declared edges
     * @return nonnull product assignable to type()
     * @throws Exception if application construction/factory code fails
     */
    T create(ManagedResolver resolver) throws Exception;
    /**
     * Injects declared fields after construction, before publishing the instance.
     * @param instance newly created product
     * @param resolver resolver restricted to declared edges
     * @throws Exception if field injection fails
     */
    default void inject(T instance, ManagedResolver resolver) throws Exception { }
    /**
     * Specifies whether the owning managed scope closes an AutoCloseable product.
     * Factory receivers retain their own independent ownership. Borrowed products are never closed
     * by the container; caller ownership must outlive every borrowing scope.
     * @return true for owned products by default
     */
    default boolean owned() { return true; }
}
