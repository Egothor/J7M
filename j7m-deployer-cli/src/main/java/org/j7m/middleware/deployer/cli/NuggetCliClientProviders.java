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

package org.j7m.middleware.deployer.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

final class NuggetCliClientProviders {
    private NuggetCliClientProviders() {
    }

    static NuggetCliClient load(final ClassLoader classLoader) {
        ClassLoader loader = classLoader == null
                ? NuggetCliClientProvider.class.getClassLoader()
                : classLoader;
        List<NuggetCliClientProvider> providers = new ArrayList<>();
        try {
            ServiceLoader<NuggetCliClientProvider> serviceLoader =
                    ServiceLoader.load(NuggetCliClientProvider.class, loader);
            Iterator<NuggetCliClientProvider> iterator = serviceLoader.iterator();
            while (iterator.hasNext()) {
                NuggetCliClientProvider provider = iterator.next();
                providers.add(provider);
            }
        } catch (RuntimeException | ServiceConfigurationError exception) {
            return NuggetCliClient.unavailable("failed to load nugget client providers");
        }
        if (providers.isEmpty()) {
            return NuggetCliClient.unavailable("no nugget client provider is installed");
        }
        providers.sort(Comparator.comparing(NuggetCliClientProviders::providerId));
        if (providers.size() > 1) {
            return NuggetCliClient.unavailable("multiple nugget client providers are installed");
        }
        NuggetCliClientProvider provider = providers.get(0);
        try {
            NuggetCliClient client = provider.createClient();
            return client == null ? NuggetCliClient.unavailable("nugget client provider returned no client") : client;
        } catch (RuntimeException | ServiceConfigurationError exception) {
            return NuggetCliClient.unavailable("nugget client provider initialization failed");
        }
    }

    private static String providerId(final NuggetCliClientProvider provider) {
        String value;
        try {
            value = provider.providerId();
        } catch (RuntimeException | ServiceConfigurationError exception) {
            return "";
        }
        if (value == null || value.isBlank()) {
            return "";
        }
        return value;
    }
}
