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

package org.j7m.middleware.examples.annotation;

import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.Named;

/**
 * Named formatter capability injected into the simple annotation-first service.
 *
 * <p>The component demonstrates qualifier-based application dependency
 * injection. It owns no runtime resources and is thread-safe because all state
 * is immutable.</p>
 */
@J7mComponent(name = "formal")
@Named("formal")
public final class AnnotationGreetingFormatter {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final String style;

    /**
     * Creates a formatter.
     *
     * @param style public formatting style
     */
    public AnnotationGreetingFormatter(final String style) {
        this.style = style == null || style.isBlank() ? "standard" : style;
    }

    /**
     * Formats a greeting.
     *
     * @param region configured region
     * @param name caller supplied name
     * @return formatted greeting
     */
    public String format(final String region, final String name) {
        String safeName = name == null || name.isBlank() ? "developer" : name;
        return this.style + ":" + region + ":hello " + safeName;
    }
}
