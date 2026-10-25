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

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.CircuitBreaker;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.registry.api.*;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.registry.mcast.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Actual service-JVM fixture: UDP discovery plus gRPC calls. Registry signatures use the existing
 * explicitly test-only signer; this qualification does not claim a production cryptographic profile.
 */
public final class LiveBindingNodeProcess {
    private LiveBindingNodeProcess() { }
    public interface Business {
        @Idempotent String identify();
        @Idempotent @CircuitBreaker("dependency") String effect(String mode);
    }
    public static final class Service implements Business {
        final String id; final AtomicInteger effects = new AtomicInteger();
        Service(String id) { this.id = id; }
        @Override public String identify() { return id; }
        @Override public String effect(String mode) { effects.incrementAndGet(); if (mode.equals("unavailable")) throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "application unavailable"); return id; }
    }
    static RegistryClient registry(String id, String context, int port) throws Exception {
        var signer = new DeterministicRegistryRecordSigner("ap05-network-fixture");
        var options = new MulticastRegistryOptions(id, context, "239.7.12.9", port, NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress()),
                Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofMillis(200), 32, 16, new UdpMulticastBus());
        var delegate = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), options, signer, true, null);
        return new RegistryClient() {
            @Override public void bind(RegistryRecord record) { delegate.bind(record.withAuthentication(signer.sign(record))); }
            @Override public void unbind(ObjectRef ref) { delegate.unbind(ref); }
            @Override public Optional<RegistryRecord> lookup(ObjectRef ref) { return delegate.lookup(ref); }
            @Override public List<RegistryRecord> list(RegistryQuery query) { return delegate.list(query); }
            @Override public void heartbeat(ObjectRef ref, long leaseUntil) { delegate.lookup(ref).ifPresent(record -> bind(record.heartbeat(System.currentTimeMillis(), leaseUntil))); }
            @Override public List<RegistryPeerStatus> peers(PeerQuery query) { return delegate.peers(query); }
            @Override public void close() { delegate.close(); }
        };
    }
    public static void main(String[] args) throws Exception {
        String id = args[0], context = args[1]; int port = Integer.parseInt(args[2]); var service = new Service(id);
        var transport = new GrpcTransportProvider();
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId(id).registryContext(context).registry(registry(id, context, port)).addTransport(transport).disableNuggetAdmin().build()) {
            Map<String, String> metadata = Map.of("j7m.contract.id", "binding-business", "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of()));
            try (var registration = runtime.export("binding-service", service, Business.class,
                    new ExportOptions(false, "grpc", context, List.of(), metadata, Duration.ofSeconds(3), Duration.ofMillis(100)));
                 var annotated = runtime.export("annotated-service", new Service(id), Business.class, new ExportOptions(false, "grpc", context, List.of(),
                         Map.of("j7m.contract.id", Business.class.getName(), "j7m.contract.version", "1", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), Duration.ofSeconds(3), Duration.ofMillis(100)))) {
                if (id.equals("c")) runtime.export("binding-service", service, Business.class, new ExportOptions(false, "grpc", context, List.of(),
                        Map.of("j7m.contract.id", "binding-business", "j7m.contract.version", "2", "j7m.qualifier.fingerprint", ManagedQualifier.fingerprint(List.of())), Duration.ofSeconds(3), Duration.ofMillis(100)));
                System.out.println("READY " + id); System.out.flush();
                var input = new BufferedReader(new InputStreamReader(System.in)); String command;
                while ((command = input.readLine()) != null) {
                    if (command.equals("COUNT")) { System.out.println("COUNT " + service.effects.get()); System.out.flush(); }
                    else if (command.equals("DISCONNECT")) { transport.close(); System.out.println("DISCONNECTED"); System.out.flush(); }
                    else break;
                }
            }
        }
    }
}
