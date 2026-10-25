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

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Public-host-API signed application fixture; fault gates exist only around test protocol edges.
 */
public final class JdbcQueueFrontendProcess {
    private JdbcQueueFrontendProcess() { }
    private static PartitionClient endpoint(String ports) {
        return new PartitionClient(Arrays.stream(ports.split(",")).map(port -> new InetSocketAddress("127.0.0.1", Integer.parseInt(port))).toList(), "app", new byte[32]);
    }
    /**
     * Hosts the ordinary signed application with actual JDBC/inbox/outbox and queue policies.
     * @param args bundle, signer public key, work directory, queue ports, execution ports, H2 TCP URL, fault mode
     * @throws Exception on setup, signed activation or control failure
     */
    public static void main(String[] args) throws Exception {
        var broker = endpoint(args[3]); var execution = endpoint(args[4]); var release = new CountDownLatch(1);
        String mode = args.length > 6 ? args[6] : "normal";
        PartitionEndpoint edge = new PartitionEndpoint() {
            @Override public PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant end) throws IOException {
                String operation = ResourceCommand.decode(command).operation();
                if (mode.equals("before-ack") && operation.equals("Q_ACK")) await("BEFORE_ACK");
                var response = broker.execute(epoch, id, command, query, end);
                if (mode.equals("after-send") && operation.equals("Q_SEND")) await("AFTER_SEND");
                return response;
            }
            private void await(String stage) throws IOException {
                System.out.println(stage); System.out.flush();
                try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException(stopped); }
            }
            @Override public PartitionReply query(long epoch, byte[] query, Instant end) throws IOException { return broker.query(epoch, query, end); }
            @Override public PartitionStateMachine.Status status(Instant end) throws IOException { return broker.status(end); }
            @Override public QueueSession subscribeQueue(long epoch, String queue, String group, String member, long visibility, boolean observation, Instant end) throws IOException {
                return broker.subscribeQueue(epoch, queue, group, member, visibility, observation, end);
            }
        };
        var queue = new ReplicatedQueue("queue-partition", "tenant", (p,d) -> new ReplicatedQueue.Route(edge, 1));
        var client = new ExecutionClient("execution", "tenant", (p,d) -> new ReplicatedQueue.Route(execution, 1), InvocationTraceContext::current);
        var schema = new SchemaRegistry(List.of()); var codec = new ManagedPayloadCodec(new DefaultValueCodec(schema), schema);
        var jdbc = new ManagedJdbcTransactionPolicy.Registration(() -> DriverManager.getConnection(args[5], "sa", ""), Duration.ofSeconds(2));
        var transactions = new ManagedJdbcTransactionPolicy(Map.of("default", jdbc));
        var inbox = new ManagedInboxPolicy(Map.of("default", new ManagedInboxPolicy.Registration("inbox", "tenant", "default", jdbc, null)));
        var publication = new OutboxProfile("sales", "tenant", "orders", 1, "default", jdbc, codec, new DeduplicatedRpc(client), Duration.ofHours(1), Duration.ofDays(1), null);
        var relay = new OutboxRelayProfile(publication, queue, "events", 16, Duration.ofMillis(10), Duration.ofSeconds(1));
        var profile = new QueueConsumerProfile(queue, null, codec, "tenant", "jdbc-workers", "workers", "inbox-member", Set.of("orders"),
                Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofHours(1), QueueConsumerProfile.Strategy.INBOX);
        var key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
        var trust = new PublicKeyTrustStore(Map.of(new SigningKeyId("operator"), key)); var work = Path.of(args[2]);
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId("jdbc-host").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var consumer = new ManagedQueueConsumerFactory(Map.of("default", profile), 1)) {
            var repository = new LocalApplicationRepository(work.resolve("repository"), Clock.systemUTC(), ApplicationPlatformLimits.defaults(), trust);
            var coordinate = ApplicationCoordinate.parse("fixture:jdbc-delivery:1");
            var bundle = new ApplicationBundle(Path.of(args[0]), coordinate, new J7mBundleReader().sha256(Path.of(args[0])));
            if (!repository.listVersions(coordinate.id()).contains(coordinate.version())) {
                var result = repository.publish(bundle, new RepositoryPublishOptions("fixture", true));
                if (!result.success()) throw new IllegalStateException("Signed fixture publication rejected");
            } else if (!repository.fetch(coordinate).sha256().equals(bundle.sha256())) throw new IllegalStateException("Artifact changed on restart");
                try (var outbox = new ManagedOutboxFactory(Map.of("producer", new ManagedOutboxFactory.Registration("inboxfixture.Publisher", Map.of("default", relay))), 1)) {
                    var connection = new ResourceBindingFactory(Map.of("jdbc", ResourceBindingFactory.jdbcConnection()), Set.of("default"));
                    var bindings = new ManagedHostBindings(Map.of("resource.name", new ManagedHostBindings.Factory() {
                        private ManagedHostBindings.Factory selected(ManagedDependency dependency) { return dependency.attributes().get("resource.name").equals("producer") ? outbox : connection; }
                        public void validate(ManagedDependency dependency, Scope.Kind scope) { selected(dependency).validate(dependency, scope); }
                        public ManagedHostBindings.BoundValue bind(ManagedDependency dependency) throws Exception { return selected(dependency).bind(dependency); }
                    }));
                    var worker = new SecurityContext(new Principal("jdbc-worker", "jdbc-worker", Set.of(), Set.of()), "jdbc-host", new TrustDomain("default"), null, "", "", "", false, null, Set.of(), Map.of(), Instant.EPOCH);
                    var policies = new ArrayList<ManagedInvocationPolicy>(List.of(new ManagedCallingPolicy(), consumer, transactions, inbox));
                    if (mode.equals("missing-guard")) policies.remove(consumer);
                    if (mode.equals("missing-jdbc")) policies.remove(transactions);
                    var hosted = new HashMap<String, ManagedHostedResources.Entry>();
                    hosted.put("queue-consumer", new ManagedHostedResources.Entry(consumer, worker, Duration.ofSeconds(10)));
                    if (!mode.equals("missing-relay")) hosted.put("outbox-relay", new ManagedHostedResources.Entry(outbox, worker, Duration.ofSeconds(10)));
                    var options = new ManagedHostingOptions(bindings, policies, ManagedProbeSettings.defaults(), ManagedInvocationSettings.defaults(), new ManagedHostedResources(hosted));
                    var manager = new ApplicationManager(runtime, repository, ApplicationPlatformLimits.defaults(), options, trust);
                    ApplicationActivation activation;
                    try { activation = manager.activate(coordinate, new ApplicationActivationRequest("fixture", "fixture", true, Map.of("j7m.annotations.profile", "managed-v1", "constructor.marker", work.resolve("constructed").toString()))); }
                    catch (RuntimeException denied) {
                        if (!mode.startsWith("missing-") || Files.exists(work.resolve("constructed"))) throw denied;
                        System.out.println("READY REJECTED"); System.out.flush(); return;
                    }
                    if (mode.startsWith("missing-")) throw new AssertionError("Missing host capability was accepted");
                    System.out.println("READY " + activation.state()); System.out.flush();
                    try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                        String command; while ((command = input.readLine()) != null) {
                            if (command.equals("RELEASE")) { release.countDown(); System.out.println("RELEASED"); }
                            else if (command.equals("STATUS")) { var status = consumer.status("jdbc-workers", "inbox-handler"); System.out.println("STATUS " + status.phase() + " " + status.reason() + " " + status.acknowledged()); }
                            else if (command.equals("STOP")) { var stopped = manager.deactivate(activation.instanceIdentity().instanceId()); System.out.println("STOP " + stopped.success()); System.out.flush(); if (stopped.success()) return; }
                            else throw new IllegalArgumentException("Unknown fixture control");
                            System.out.flush();
                        }
                    } finally { release.countDown(); manager.deactivate(activation.instanceIdentity().instanceId()); }
                }
        }
    }
}
