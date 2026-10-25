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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

class ManagedMethodSurfaceTest {
    interface Entry { String read(String value); String read(int value); }
    interface Other { String unrelated(); }
    static class Component implements Entry, Other {
        @Override public String read(final String value) { return value; }
        @Override public String read(final int value) { return Integer.toString(value); }
        @Override public String unrelated() { return "value"; }
    }

    @Test
    void exactOverloadLineageDoesNotIncludeUnrelatedInterfaceOrNeighboringOverload() {
        var contract = new ManagedServiceContract("entry", 1,
                new ManagedMethod(Entry.class.getName(), "read", List.of(String.class.getName()), String.class.getName()));
        var surface = ManagedMethodSurface.declarations(contract, Component.class, Entry.class);
        assertTrue(surface.contains(Entry.class));
        assertFalse(surface.contains(Other.class));
        assertTrue(surface.stream().anyMatch(element -> element instanceof Method method && method.getName().equals("read")));
        assertFalse(surface.stream().anyMatch(element -> element instanceof Method method && method.getParameterTypes()[0] == int.class));
    }

    @Test
    void malformedPublicIdentityAndNonimplementationAreRejectedWithoutLoadingNames() {
        var wrongOwner = new ManagedServiceContract("entry", 1,
                new ManagedMethod("not.loaded.untrusted.Type", "read", List.of(String.class.getName()), String.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> ManagedMethodSurface.method(wrongOwner, Entry.class));
        assertThrows(IllegalArgumentException.class, () -> ManagedMethodSurface.declarations(wrongOwner, Object.class, Entry.class));
    }
}
