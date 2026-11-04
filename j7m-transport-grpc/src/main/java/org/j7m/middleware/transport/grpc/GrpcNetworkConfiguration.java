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

package org.j7m.middleware.transport.grpc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;

/**
 * Immutable listener and published destination configuration for one gRPC provider.
 *
 * <p>Hosts are bare DNS names or IP literals, without brackets, scheme, port,
 * credentials or path. Bind port zero asks the OS for an available port.
 * Advertised port zero uses the actual bound port; a positive advertised port
 * supports host-managed forwarding. Reachability of forwarding and DNS from
 * other hosts is the deployer's responsibility. A wildcard bind requires an
 * explicit unicast advertised host. Startup resolves both hosts before binding;
 * wildcard and multicast destinations are rejected. TLS verifies the advertised
 * host against the peer certificate; this configuration never overrides it.</p>
 *
 * @param bindHost local listener host, including a wildcard if required
 * @param bindPort listener port, in the range 0 through 65535
 * @param advertisedHost non-wildcard, non-multicast destination host
 * @param advertisedPort published port, 0 for the bound port or 1 through 65535
 */
public record GrpcNetworkConfiguration(String bindHost, int bindPort, String advertisedHost, int advertisedPort) {
    /**
     * Validates host syntax and port ranges without opening sockets or resolving DNS.
     *
     * @throws MiddlewareException with INVALID_ARGUMENT for malformed hosts or ports
     */
    public GrpcNetworkConfiguration {
        validateHost(bindHost, "bindHost");
        validateHost(advertisedHost, "advertisedHost");
        if (bindPort < 0 || bindPort > 65535 || advertisedPort < 0 || advertisedPort > 65535) {
            throw invalid("grpc network ports must be between 0 and 65535");
        }
        if (advertisedHost.equals("0.0.0.0") || advertisedHost.equals("::")) {
            throw invalid("grpc advertisedHost must not be a wildcard");
        }
    }

    /**
     * Returns isolated IPv4 loopback settings with an OS-assigned port.
     *
     * @return listener and destination defaults used by existing constructors
     */
    public static GrpcNetworkConfiguration loopback() {
        return new GrpcNetworkConfiguration("127.0.0.1", 0, "127.0.0.1", 0);
    }

    InetSocketAddress bindAddress() {
        InetAddress bind = resolve(this.bindHost, "bindHost");
        if (bind.isMulticastAddress()) {
            throw invalid("grpc bindHost must not be multicast");
        }
        try {
            for (InetAddress advertised : InetAddress.getAllByName(this.advertisedHost)) {
                if (advertised.isAnyLocalAddress() || advertised.isMulticastAddress()
                        || advertised.getHostAddress().equals("255.255.255.255")) {
                    throw invalid("grpc advertisedHost must be a unicast destination");
                }
            }
        } catch (UnknownHostException exception) {
            throw invalid("grpc advertisedHost cannot be resolved");
        }
        return new InetSocketAddress(bind, this.bindPort);
    }

    String endpoint(final int boundPort) {
        int destinationPort = this.advertisedPort == 0 ? boundPort : this.advertisedPort;
        if (destinationPort <= 0 || destinationPort > 65535) {
            throw invalid("grpc listener did not resolve a usable destination port");
        }
        try {
            return new URI("grpc", null, this.advertisedHost, destinationPort, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw invalid("invalid grpc advertisedHost");
        }
    }

    private static void validateHost(final String host, final String field) {
        if (host == null || host.isBlank() || !host.equals(host.trim()) || host.contains("[")
                || host.contains("]") || host.contains("%")) {
            throw invalid("invalid grpc " + field);
        }
        try {
            URI uri = new URI("grpc://" + (host.contains(":") ? "[" + host + "]" : host) + ":1");
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty() || uri.getPort() != 1) {
                throw invalid("invalid grpc " + field);
            }
        } catch (URISyntaxException exception) {
            throw invalid("invalid grpc " + field);
        }
    }

    private static InetAddress resolve(final String host, final String field) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException exception) {
            throw invalid("grpc " + field + " cannot be resolved");
        }
    }

    private static MiddlewareException invalid(final String message) {
        return MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, message);
    }
}
