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

package org.j7m.middleware.application.distributed;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.consensus.NetworkConsensusConfig;
import org.j7m.middleware.distributed.PartitionServer;
import org.j7m.middleware.distributed.ReplicatedResourceClient;

/**
 * Separate JVM native host used only by the A10 acceptance fixture.
 */
public final class ManagedPartitionNodeProcess {
    private ManagedPartitionNodeProcess() { }
    /**
     * Opens the actual generated program, registered factory and durable native services.
     * @param arguments nodeId,directory,peerRoster,originalSeed,artifact,semanticConfig,initialEpoch
     * @throws Exception on invalid fixture input or actual durable/network failure
     */
    public static void main(final String[] arguments) throws Exception {
        String id = arguments[0]; Path directory = Path.of(arguments[1]); var peers = new TreeMap<String, InetSocketAddress>();
        for (String item : arguments[2].split(",")) {
            String[] pair = item.split(":"); peers.put(pair[0], new InetSocketAddress("127.0.0.1", Integer.parseInt(pair[1])));
        }
        var originalSeed = Set.copyOf(List.of(arguments[3].split(","))); Path artifact = Path.of(arguments[4]);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        var bundle = new ManagedHostedResources.Bundle(digest, Map.of("configuration", arguments[5]));
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, ManagedPartitionNodeProcess.class.getClassLoader())) {
            var definition = ServiceLoader.load(ManagedComponentIndex.class, loader).stream()
                    .filter(provider -> provider.type().getClassLoader() == loader).map(ServiceLoader.Provider::get)
                    .flatMap(index -> index.components().stream()).filter(ManagedPartitionDefinition.class::isInstance)
                    .map(value -> (ManagedPartitionDefinition<?>) value).findFirst().orElseThrow();
            Object component = definition.create(null);
            var config = new NetworkConsensusConfig(id, peers, originalSeed, directory, new byte[32], Duration.ofMillis(500),
                    Duration.ofMillis(20), 512 * 1024, 128 * 1024 * 1024, null, "group");
            byte[] authority = new byte[32]; java.util.Arrays.fill(authority, (byte) 7);
            byte[] adminKey = new byte[32]; java.util.Arrays.fill(adminKey, (byte) 1);
            var profile = new ManagedPartitionProfile(config, "partition", Long.parseLong(arguments[6]), "tenant", 10000,
                    new InetSocketAddress("127.0.0.1", 0), Map.of("client", new PartitionServer.Binding("tenant", new byte[32], false, false),
                            "admin", new PartitionServer.Binding("tenant", adminKey, true, true)),
                    authority, () -> { long now = System.currentTimeMillis(); return new ReplicatedResourceClient.ClockMeasurement(now, now, 0, true); }, null);
            try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
                var admission = new java.util.concurrent.atomic.AtomicBoolean();
                var invoker = new ManagedHostedResources.Invoker() {
                    @Override public boolean admissionOpen() { return admission.get(); }
                    @Override public java.util.concurrent.CompletionStage<Object> invokeDecoded(org.j7m.middleware.application.api.ManagedMethod method,
                            java.util.concurrent.Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options) { throw new AssertionError("Effect invocation from pure reducer"); }
                };
                var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition, component, bundle, invoker);
                try {
                    binding.prepare(); binding.start(); binding.commitAdmission(); admission.set(true);
                    System.out.println("READY " + binding.address().getPort() + " " + binding.groupId()); System.out.flush();
                    var input = new BufferedReader(new InputStreamReader(System.in));
                    for (String line; (line = input.readLine()) != null;) {
                        if (line.equals("STATUS")) {
                            var status = binding.node().status();
                            System.out.println("STATUS " + status.leaderId() + " " + status.configEpoch() + " " + status.members().size() + " " + status.appliedIndex());
                        } else if (line.equals("SNAPSHOT")) { System.out.println("SNAPSHOT " + binding.node().snapshot()); }
                        else if (line.startsWith("MEMBERS ")) {
                            try {
                                binding.node().reconfigure(binding.node().status().configEpoch(), Set.copyOf(List.of(line.substring(8).split(","))), Instant.now().plusSeconds(3));
                                System.out.println("MEMBERS OK");
                            } catch (java.io.IOException unavailable) { System.out.println("MEMBERS UNKNOWN"); }
                        } else if (line.equals("STOP")) { System.out.println("STOP " + binding.drain(Duration.ofSeconds(3))); System.out.flush(); break; }
                        else { throw new IllegalArgumentException("Unknown fixture control"); }
                        System.out.flush();
                    }
                } finally {
                    if (!binding.drain(Duration.ofSeconds(3))) throw new IllegalStateException("Partition remains physically active");
                    binding.close();
                }
            }
        }
    }
}
