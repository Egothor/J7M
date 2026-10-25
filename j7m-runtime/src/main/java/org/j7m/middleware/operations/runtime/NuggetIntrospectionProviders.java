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

package org.j7m.middleware.operations.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.operations.api.NuggetIntrospection;

final class NuggetIntrospectionProviders {
    static final int MAX_PROVIDERS = 8;
    static final int MAX_DISK_ROOTS = 16;

    private final List<NuggetIntrospection.Provider> providers;
    private final List<NuggetDiskRoot> diskRoots;

    private NuggetIntrospectionProviders(final Builder builder) {
        this.providers = List.copyOf(builder.providers);
        this.diskRoots = List.copyOf(builder.diskRoots);
    }

    static Builder builder() {
        return new Builder();
    }

    List<NuggetIntrospection.Provider> providers() {
        return this.providers;
    }

    List<NuggetDiskRoot> diskRoots() {
        return this.diskRoots;
    }

    static final class Builder {
        private final List<NuggetIntrospection.Provider> providers = new ArrayList<>();
        private final List<NuggetDiskRoot> diskRoots = new ArrayList<>();

        private Builder() {
            this.diskRoots.add(new NuggetDiskRoot("working-directory", Path.of("").toAbsolutePath().normalize(), false));
        }

        Builder provider(final NuggetIntrospection.Provider value) {
            if (value != null) {
                if (this.providers.size() >= MAX_PROVIDERS) {
                    throw new IllegalArgumentException("too many nugget introspection providers");
                }
                this.providers.add(value);
            }
            return this;
        }

        Builder diskRoot(final String role, final Path path, final boolean sensitive) {
            if (path != null) {
                if (this.diskRoots.size() >= MAX_DISK_ROOTS) {
                    throw new IllegalArgumentException("too many nugget disk roots");
                }
                this.diskRoots.add(new NuggetDiskRoot(role, path, sensitive));
            }
            return this;
        }

        NuggetIntrospectionProviders build() {
            return new NuggetIntrospectionProviders(this);
        }
    }
}
