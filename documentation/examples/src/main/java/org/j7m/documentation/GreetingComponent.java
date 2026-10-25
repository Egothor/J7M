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

package org.j7m.documentation;

import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;

/**
 * Constructor-injected component whose lifecycle is driven by its owning host.
 */
@J7mComponent
public final class GreetingComponent {
    private final GreetingFormatter formatter;
    private boolean started;

    /**
     * Creates a component from a graph-validated dependency.
     * @param formatter formatter resolved from the same activation
     */
    @Inject
    public GreetingComponent(GreetingFormatter formatter) {
        this.formatter = formatter;
    }

    /**
     * Opens business admission after dependency construction and initialization.
     */
    @Startup
    public void start() { started = true; }

    /**
     * Produces a greeting while this component has been started.
     * @param name recipient name
     * @return formatted greeting
     * @throws IllegalStateException before startup or after shutdown
     */
    public String greet(String name) {
        if (!started) throw new IllegalStateException("Component is not started");
        return formatter.format(name);
    }

    /**
     * Revokes this component's simple example admission flag.
     */
    @Shutdown
    public void stop() { started = false; }
}
