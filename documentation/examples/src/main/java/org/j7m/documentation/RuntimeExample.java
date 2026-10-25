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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Runs real gRPC invocation and callback traffic between two runtimes in one JVM.
 */
public final class RuntimeExample {
    private RuntimeExample() { }

    /**
     * Receives an application callback.
     */
    public interface Listener {
        /**
         * Receives one public result message.
         * @param value result message
         */
        void onResult(String value);
    }
    /**
     * Business contract exported through the explicit runtime API.
     */
    public interface GreetingApi {
        /**
         * Greets a recipient and invokes the supplied callback by reference.
         * @param name recipient name
         * @param listener application callback exported by the caller's runtime
         * @return greeting
         */
        String greet(String name, Listener listener);
    }
    /**
     * Public application implementation; no service or injection annotation is required.
     */
    public static final class GreetingService implements GreetingApi {
        /**
         * Creates the stateless service.
         */
        public GreetingService() { }
        /**
         * {@inheritDoc}
         */
        @Override public String greet(String name, Listener listener) {
            String result = "Hello, " + name;
            listener.onResult(result);
            return result;
        }
    }
    /**
     * Runs an actual remote call, callback and discovery filter check.
     * This isolated tutorial deliberately disables nugget administration and uses development gRPC
     * security. Production hosts configure authenticated transport and the administration PIN.
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        var store = new EmbeddedRegistryStore();
        try (var server = new MiddlewareRuntimeBuilder().runtimeId("docs-server")
                    .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                    .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var client = new MiddlewareRuntimeBuilder().runtimeId("docs-client")
                    .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                    .addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build()) {
            var options = new ExportOptions(false, "grpc", "default", List.of(), Map.of("region", "local"), null, null);
            try (var registration = server.export("greetings", new GreetingService(), GreetingApi.class, options)) {
                var received = new CopyOnWriteArrayList<String>();
                Listener callback = received::add;
                GreetingApi proxy = client.lookup("greetings", GreetingApi.class, LookupOptions.defaults());
                require("Hello, Ada".equals(proxy.greet("Ada", callback)), "Remote result changed");
                require(received.equals(List.of("Hello, Ada")), "Callback did not return through the caller runtime");
                require(client.list("greetings", List.of(GreetingApi.class.getName()), Map.of("region", "other"),
                        LookupOptions.defaults()).isEmpty(), "Required registry metadata was ignored");
                require(!registration.ref().runtimeId().equals(client.identity().runtimeId()), "Call selected the client runtime");
            }
        }
        System.out.println("RuntimeExample OK: two runtimes, actual gRPC, callback, filtered discovery");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
