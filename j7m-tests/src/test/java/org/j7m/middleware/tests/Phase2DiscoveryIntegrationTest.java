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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.admin.RuntimeAdmin;
import org.j7m.middleware.annotations.Benchmark;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.ha.HpSelection;
import org.j7m.middleware.ha.HpSelector;
import org.j7m.middleware.metrics.SlidingWindowStatistics;
import org.j7m.middleware.metrics.StatisticsSnapshot;
import org.j7m.middleware.registry.api.DiscoverySource;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryPeerQuality;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.bootstrap.BootstrapRegistryClient;
import org.j7m.middleware.registry.bootstrap.BootstrapRegistryOptions;
import org.j7m.middleware.registry.bootstrap.InMemoryBootstrapEndpointResolver;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.registry.mcast.MulticastBus;
import org.j7m.middleware.registry.mcast.MulticastBusDiagnostics;
import org.j7m.middleware.registry.mcast.MulticastMessageEnvelope;
import org.j7m.middleware.registry.mcast.MulticastMessageType;
import org.j7m.middleware.registry.mcast.MulticastProtocolCodec;
import org.j7m.middleware.registry.mcast.MulticastProtocolMessage;
import org.j7m.middleware.registry.mcast.MulticastQueryType;
import org.j7m.middleware.registry.mcast.MulticastRegistryBus;
import org.j7m.middleware.registry.mcast.MulticastRegistryClient;
import org.j7m.middleware.registry.mcast.MulticastRegistryOptions;
import org.j7m.middleware.registry.mcast.UdpMulticastBus;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 decentralized discovery and HP selection tests.
 */
public final class Phase2DiscoveryIntegrationTest {
    @Test
    public void multicastLookupAndListCollectMultipleAnswers() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-basic");
        MulticastRegistryBus bus = new MulticastRegistryBus();
        MulticastRegistryClient registryA = multicast("mcast-a", "default", bus, Clock.systemUTC(), signer);
        MulticastRegistryClient registryB = multicast("mcast-b", "default", bus, Clock.systemUTC(), signer);
        MulticastRegistryClient registryC = multicast("mcast-c", "default", bus, Clock.systemUTC(), signer);
        ObjectRef first = ref("mcast-one", "greeter", "runtime-a", "default", System.currentTimeMillis() + 30_000L,
                Map.of("zone", "eu", "load", "0.2"));
        ObjectRef second = ref("mcast-two", "greeter", "runtime-b", "default", System.currentTimeMillis() + 30_000L,
                Map.of("zone", "eu", "load", "0.1"));
        registryA.bind(signedRecord(first, signer));
        registryB.bind(signedRecord(second, signer));

        List<RegistryRecord> records = registryC.list(new RegistryQuery("greeter", List.of(Greeter.class.getName()), Map.of("zone", "eu"), "default",
                System.currentTimeMillis(), 10));

        assertEquals(2, records.size());
        assertTrue(registryC.multicastListenerRunning());
        assertTrue(registryC.discoveryStatistics().multicastMessagesSent() > 0L);
        assertTrue(registryA.discoveryStatistics().multicastMessagesReceived() > 0L || registryB.discoveryStatistics().multicastMessagesReceived() > 0L);
        registryA.close();
        registryB.close();
        registryC.close();
    }

    @Test
    public void multicastFiltersContextInterfaceParamsAndExpiredLeases() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-filter");
        MutableClock clock = new MutableClock(Instant.now());
        MulticastRegistryBus bus = new MulticastRegistryBus();
        MulticastRegistryClient registryA = multicast("mcast-filter-a", "alpha", bus, clock, signer);
        MulticastRegistryClient registryB = multicast("mcast-filter-b", "alpha", bus, clock, signer);
        MulticastRegistryClient registryOtherContext = multicast("mcast-filter-c", "beta", bus, clock, signer);
        ObjectRef live = ref("live", "svc", "runtime-a", "alpha", clock.millis() + 1_000L, Map.of("zone", "eu"));
        ObjectRef expired = ref("expired", "svc", "runtime-a", "alpha", clock.millis() + 10L, Map.of("zone", "eu"));
        ObjectRef otherContext = ref("other", "svc", "runtime-c", "beta", clock.millis() + 1_000L, Map.of("zone", "eu"));
        registryA.bind(signedRecord(live, clock.millis(), signer));
        registryA.bind(signedRecord(expired, clock.millis(), signer));
        registryOtherContext.bind(signedRecord(otherContext, clock.millis(), signer));

        clock.advance(Duration.ofMillis(20L));
        List<RegistryRecord> matches = registryB.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of("zone", "eu"), "alpha", clock.millis(), 10));

        assertEquals(1, matches.size());
        assertEquals("live", matches.getFirst().ref().objectId());
        assertTrue(registryB.list(new RegistryQuery("svc", List.of("other.Api"), Map.of(), "alpha", clock.millis(), 10)).isEmpty());
        assertFalse(registryB.recentDiscoveryEvents().isEmpty());
        registryA.close();
        registryB.close();
        registryOtherContext.close();
    }

    @Test
    public void multicastEnvelopeContainsPhase2CorrelationFields() {
        MulticastMessageEnvelope envelope = new MulticastMessageEnvelope(MulticastMessageType.QUESTION, "sender", "*",
                "ctx", "q1", MulticastQueryType.LIST, 10L, 50L, 1, 1, Map.of("service", "svc"));

        assertEquals(MulticastMessageType.QUESTION, envelope.messageType());
        assertEquals("sender", envelope.senderId());
        assertEquals("ctx", envelope.contextId());
        assertEquals("q1", envelope.questionId());
        assertEquals(MulticastQueryType.LIST, envelope.queryType());
    }

    @Test
    public void multicastCodecPreservesAuthMetadataAndRejectsMalformedPackets() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-key");
        ObjectRef ref = ref("auth", "svc", "runtime-a", "default", System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord record = new RegistryRecord(ref, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), ref.leaseUntil(), ObjectState.EXECUTED);
        RegistryRecord signed = record.withAuthentication(signer.sign(record));
        MulticastProtocolCodec codec = new MulticastProtocolCodec();
        MulticastMessageEnvelope envelope = new MulticastMessageEnvelope(MulticastMessageType.RESPONSE, "sender", "receiver",
                "default", "q-auth", MulticastQueryType.LIST, System.currentTimeMillis(), 500L, 0, 2, Map.of("safe", "yes"));

        byte[] encoded = codec.encode(new MulticastProtocolMessage(envelope, null, List.of(signed)));
        RegistryRecord decoded = codec.decode(encoded, encoded.length).records().getFirst();
        byte[] unknownVersion = encoded.clone();
        ByteBuffer.wrap(unknownVersion).putInt(4, 99);
        byte[] negativeRecords = encoded.clone();
        ByteBuffer.wrap(negativeRecords).putInt(negativeRecords.length - Integer.BYTES, -1);

        assertEquals(signed.authMetadata().get("signature"), decoded.authMetadata().get("signature"));
        assertThrows(UncheckedIOException.class, () -> codec.decode(unknownVersion, unknownVersion.length));
        assertThrows(UncheckedIOException.class, () -> codec.decode(packetWithRecordCount(-1), packetWithRecordCount(-1).length));
        assertThrows(UncheckedIOException.class, () -> codec.decode(packetWithDuplicateMetadataKey(), packetWithDuplicateMetadataKey().length));
        assertThrows(UncheckedIOException.class, () -> codec.decode(packetWithMalformedUtf8(), packetWithMalformedUtf8().length));
        assertThrows(UncheckedIOException.class, () -> codec.decode(negativeRecords, negativeRecords.length));
    }

    @Test
    public void multicastStrictTrustRejectsUnsignedRemoteCandidates() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-trust");
        MulticastRegistryBus bus = new MulticastRegistryBus();
        MulticastRegistryClient unsignedPeer = multicast("mcast-unsigned-peer", "default", bus);
        MulticastRegistryClient signedPeer = multicast("mcast-signed-peer", "default", bus);
        MulticastRegistryClient strictClient = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions("mcast-strict-client", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus),
                signer, true, null);
        ObjectRef unsigned = ref("unsigned-remote", "strict-service", "runtime-u", "default", System.currentTimeMillis() + 30_000L, Map.of());
        ObjectRef signedRef = ref("signed-remote", "strict-service", "runtime-s", "default", System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord signedBase = new RegistryRecord(signedRef, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), signedRef.leaseUntil(), ObjectState.EXECUTED);
        unsignedPeer.bind(new RegistryRecord(unsigned, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), unsigned.leaseUntil(), ObjectState.EXECUTED));
        signedPeer.bind(signedBase.withAuthentication(signer.sign(signedBase)));

        List<RegistryRecord> records = strictClient.list(new RegistryQuery("strict-service", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10));

        assertEquals(1, records.size());
        assertEquals("signed-remote", records.getFirst().ref().objectId());
        unsignedPeer.close();
        signedPeer.close();
        strictClient.close();
    }

    @Test
    public void multicastNonStrictDowngradeIsNotReturnedAsRemoteCandidate() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-lenient");
        ObjectRef ref = ref("mcast-downgraded", "mcast-lenient-service", "runtime-bad", "default",
                System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord base = new RegistryRecord(ref, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), ref.leaseUntil(), ObjectState.EXECUTED);
        RegistryRecord tampered = base.withAuthentication(Map.of("keyId", "mcast-lenient", "algorithm", "J7M-TEST-SHA256", "signature", "bad"));
        InjectedRecordMulticastBus bus = new InjectedRecordMulticastBus(List.of(tampered));
        MulticastRegistryClient client = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions("mcast-lenient-client", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus),
                signer, false, null);

        List<RegistryRecord> records = client.list(new RegistryQuery("mcast-lenient-service", List.of(Greeter.class.getName()), Map.of(),
                "default", System.currentTimeMillis(), 10));

        assertTrue(records.isEmpty());
        assertEquals(1L, client.discoveryStatistics().metadata().get("registryTrustDowngraded").longValue());
        client.close();
    }

    @Test
    public void verifierlessUnsignedReceivedRecordsAreDowngradedAndNeverEligible() {
        ObjectRef ref = ref("verifierless", "verifierless-service", "runtime-bad", "default",
                System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord unsigned = new RegistryRecord(ref, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(),
                ref.leaseUntil(), ObjectState.EXECUTED);
        EmbeddedRegistryClient embedded = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), null, false, null);

        RegistryRecord admitted = embedded.admitReceivedRecord(unsigned, "default");

        assertEquals("untrusted", admitted.authMetadata().get("trustStatus"));
        assertTrue(embedded.list(new RegistryQuery("verifierless-service", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10)).isEmpty());

        InjectedRecordMulticastBus bus = new InjectedRecordMulticastBus(List.of(unsigned));
        MulticastRegistryClient multicast = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions("verifierless-mcast", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus),
                null, false, null);
        assertTrue(multicast.list(new RegistryQuery("verifierless-service", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10)).isEmpty());
        assertEquals(1L, multicast.discoveryStatistics().metadata().get("registryTrustDowngraded").longValue());
        multicast.close();

        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("unsigned-peer", new FixedRegistryClient(List.of(unsigned)));
        BootstrapRegistryClient bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(),
                new BootstrapRegistryOptions("verifierless-bootstrap", "default", List.of("unsigned-peer"), Duration.ofSeconds(2L), 1, 4, 64,
                        Duration.ofMillis(1L), resolver),
                null, false, null);
        assertTrue(bootstrap.list(new RegistryQuery("verifierless-service", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10, System.currentTimeMillis() + 2_000L, 4, 1, DiscoverySource.BOOTSTRAP)).isEmpty());
        assertEquals(1L, bootstrap.discoveryStatistics().metadata().get("registryTrustDowngraded").longValue());
        bootstrap.close();
    }

    @Test
    public void signedReplayAdmissionRequiresIncreasingEvidenceAcrossRegistryTypes() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("replay-shared");
        MutableClock clock = new MutableClock(Instant.now());
        long nowMillis = clock.millis();
        ObjectRef firstRef = ref("replay-object", "replay-service", "runtime-replay", "default", nowMillis + 30_000L,
                Map.of(), 1L);
        RegistryRecord first = signedRecord(firstRef, nowMillis, nowMillis, firstRef.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord duplicate = signedRecord(firstRef, nowMillis, nowMillis, firstRef.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord lowerHeartbeat = signedRecord(firstRef, nowMillis - 2L, nowMillis - 1L, firstRef.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord higherHeartbeat = signedRecord(firstRef, nowMillis, nowMillis + 1L, firstRef.leaseUntil(), ObjectState.EXECUTED, signer);
        ObjectRef nextGenerationRef = ref("replay-object", "replay-service", "runtime-replay", "default", nowMillis + 30_000L,
                Map.of(), 2L);
        RegistryRecord nextGeneration = signedRecord(nextGenerationRef, nowMillis, nowMillis, nextGenerationRef.leaseUntil(),
                ObjectState.EXECUTED, signer);

        EmbeddedRegistryClient embedded = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), clock, signer, true, null);
        assertEquals("trusted", embedded.admitReceivedRecord(first, "default").authMetadata().get("trustStatus"));
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> embedded.admitReceivedRecord(duplicate, "default")).code());
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> embedded.admitReceivedRecord(lowerHeartbeat, "default")).code());
        assertEquals("trusted", embedded.admitReceivedRecord(higherHeartbeat, "default").authMetadata().get("trustStatus"));
        assertEquals("trusted", embedded.admitReceivedRecord(nextGeneration, "default").authMetadata().get("trustStatus"));

        MulticastRegistryClient multicast = new MulticastRegistryClient(new EmbeddedRegistryStore(), clock,
                new MulticastRegistryOptions("replay-mcast", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 4, 4,
                        new InjectedRecordMulticastBus(List.of(first, duplicate, higherHeartbeat))),
                signer, true, null);
        List<RegistryRecord> multicastRecords = multicast.list(new RegistryQuery("replay-service", List.of(Greeter.class.getName()),
                Map.of(), "default", nowMillis, 4));
        assertEquals(1, multicastRecords.size());
        multicast.close();

        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("replay-peer", new FixedRegistryClient(List.of(first, duplicate, higherHeartbeat)));
        BootstrapRegistryClient bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), clock,
                new BootstrapRegistryOptions("replay-bootstrap", "default", List.of("replay-peer"), Duration.ofSeconds(2L), 1, 4, 4,
                        Duration.ofMillis(1L), resolver),
                signer, true, null);
        List<RegistryRecord> bootstrapRecords = bootstrap.list(new RegistryQuery("replay-service", List.of(Greeter.class.getName()),
                Map.of(), "default", nowMillis, 4, nowMillis + 2_000L, 4, 1, DiscoverySource.BOOTSTRAP));
        assertEquals(1, bootstrapRecords.size());
        bootstrap.close();
    }

    @Test
    public void rejectedReplayDoesNotMutateStateAndConcurrentDuplicateHasOneWinner() throws InterruptedException {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("replay-concurrent");
        MutableClock clock = new MutableClock(Instant.now());
        long nowMillis = clock.millis();
        ObjectRef ref = ref("race-object", "race-service", "runtime-race", "default", nowMillis + 30_000L, Map.of(), 1L);
        RegistryRecord first = signedRecord(ref, nowMillis, nowMillis, ref.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord stale = signedRecord(ref, nowMillis - 2L, nowMillis - 1L, ref.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord next = signedRecord(ref, nowMillis, nowMillis + 1L, ref.leaseUntil(), ObjectState.EXECUTED, signer);
        EmbeddedRegistryClient embedded = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), clock, signer, true, null);

        embedded.admitReceivedRecord(first, "default");
        assertThrows(MiddlewareException.class, () -> embedded.admitReceivedRecord(stale, "default"));
        assertEquals("trusted", embedded.admitReceivedRecord(next, "default").authMetadata().get("trustStatus"));

        EmbeddedRegistryClient concurrent = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), clock, signer, true, null);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>(8);
        for (int index = 0; index < 8; index++) {
            Thread thread = Thread.ofVirtual().name("j7m-replay-race-", index).start(() -> {
                try {
                    start.await();
                    concurrent.admitReceivedRecord(first, "default");
                    successes.incrementAndGet();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (MiddlewareException exception) {
                    assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, exception.code());
                }
            });
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive());
        }
        assertEquals(1, successes.get());
    }

    @Test
    public void replayExpiryAndLiveProtectionAreDeterministic() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("replay-expiry");
        MutableClock clock = new MutableClock(Instant.now());
        long nowMillis = clock.millis();
        EmbeddedRegistryClient expiry = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), clock, signer, true, null);
        ObjectRef expiringRef = ref("expiring", "expiry-service", "runtime-expiry", "default", nowMillis + 10L, Map.of(), 1L);
        RegistryRecord expiring = signedRecord(expiringRef, nowMillis, nowMillis, expiringRef.leaseUntil(), ObjectState.EXECUTED, signer);
        expiry.admitReceivedRecord(expiring, "default");
        clock.advance(Duration.ofMillis(20L));
        ObjectRef renewedRef = ref("expiring", "expiry-service", "runtime-expiry", "default", clock.millis() + 30_000L, Map.of(), 1L);
        RegistryRecord renewed = signedRecord(renewedRef, nowMillis, clock.millis(), renewedRef.leaseUntil(), ObjectState.EXECUTED, signer);
        assertEquals("trusted", expiry.admitReceivedRecord(renewed, "default").authMetadata().get("trustStatus"));

        EmbeddedRegistryClient saturated = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), clock, signer, true, null);
        RegistryRecord first = null;
        for (int index = 0; index < 4096; index++) {
            ObjectRef liveRef = ref("live-" + index, "live-service", "runtime-live", "default", clock.millis() + 60_000L,
                    Map.of(), 1L);
            RegistryRecord live = signedRecord(liveRef, clock.millis(), clock.millis(), liveRef.leaseUntil(), ObjectState.EXECUTED, signer);
            if (index == 0) {
                first = live;
            }
            saturated.admitReceivedRecord(live, "default");
        }
        ObjectRef overflowRef = ref("live-overflow", "live-service", "runtime-live", "default", clock.millis() + 60_000L,
                Map.of(), 1L);
        RegistryRecord overflow = signedRecord(overflowRef, clock.millis(), clock.millis(), overflowRef.leaseUntil(),
                ObjectState.EXECUTED, signer);
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> saturated.admitReceivedRecord(overflow, "default")).code());
        RegistryRecord duplicateFirst = first;
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> saturated.admitReceivedRecord(duplicateFirst, "default")).code());
    }

    @Test
    public void tamperedSignedFreshnessFieldsAreRejected() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("replay-tamper");
        long nowMillis = System.currentTimeMillis();
        ObjectRef ref = ref("tampered", "tamper-service", "runtime-tamper", "default", nowMillis + 30_000L, Map.of(), 1L);
        RegistryRecord signed = signedRecord(ref, nowMillis, nowMillis, ref.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord tamperedHeartbeat = new RegistryRecord(ref, signed.parameters(), signed.createdAtMillis(), nowMillis + 1L,
                signed.leaseUntilMillis(), signed.state(), signed.authMetadata());
        ObjectRef tamperedLeaseRef = ref.withLeaseUntil(ref.leaseUntil() + 1L);
        RegistryRecord tamperedLease = new RegistryRecord(tamperedLeaseRef, signed.parameters(), signed.createdAtMillis(),
                signed.lastHeartbeatMillis(), tamperedLeaseRef.leaseUntil(), signed.state(), signed.authMetadata());
        EmbeddedRegistryClient embedded = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), signer, true, null);

        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> embedded.admitReceivedRecord(tamperedHeartbeat, "default")).code());
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> embedded.admitReceivedRecord(tamperedLease, "default")).code());
    }

    @Test
    public void multicastRepeatedDiscoveryUsesCachedTrustedRecordForRejectedDuplicate() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-repeat");
        long nowMillis = System.currentTimeMillis();
        ObjectRef ref = ref("repeat-object", "repeat-service", "runtime-repeat", "default", nowMillis + 30_000L,
                candidateMetadata("0.0"), 1L);
        RegistryRecord first = signedRecord(ref, nowMillis, nowMillis, ref.leaseUntil(), ObjectState.EXECUTED, signer);
        RegistryRecord changedCreatedAtWithOriginalSignature = new RegistryRecord(ref, first.parameters(), nowMillis - 1L,
                first.lastHeartbeatMillis(), first.leaseUntilMillis(), ObjectState.EXECUTED, first.authMetadata());
        RegistryRecord changedHeartbeatWithOriginalSignature = new RegistryRecord(ref, first.parameters(), first.createdAtMillis(),
                nowMillis + 10L, first.leaseUntilMillis(), ObjectState.EXECUTED, first.authMetadata());
        ObjectRef changedLeaseRef = ref.withLeaseUntil(nowMillis + 40_000L);
        RegistryRecord changedLeaseWithOriginalSignature = new RegistryRecord(changedLeaseRef, first.parameters(), first.createdAtMillis(),
                first.lastHeartbeatMillis(), changedLeaseRef.leaseUntil(), ObjectState.EXECUTED, first.authMetadata());
        MutableInjectedRecordMulticastBus bus = new MutableInjectedRecordMulticastBus(List.of(first));
        MulticastRegistryClient client = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions("mcast-repeat-client", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 4, 4, bus),
                signer, true, null);
        RegistryQuery query = new RegistryQuery("repeat-service", List.of(Greeter.class.getName()), Map.of(), "default",
                nowMillis, 4, nowMillis + 2_000L, 4, 0, DiscoverySource.MULTICAST);

        assertEquals(1, client.list(query).size());
        assertEquals(1, client.list(query).size());
        bus.records = List.of(changedCreatedAtWithOriginalSignature);
        assertTrue(client.list(query).isEmpty());
        bus.records = List.of(changedHeartbeatWithOriginalSignature);
        assertTrue(client.list(query).isEmpty());
        bus.records = List.of(changedLeaseWithOriginalSignature);
        assertTrue(client.list(query).isEmpty());

        Map<String, Long> metadata = client.discoveryStatistics().metadata();
        assertTrue(metadata.get("registryTrustRejected").longValue() >= 4L);
        assertEquals(1L, metadata.get("trustedRemoteCacheSize").longValue());
        client.close();
    }

    @Test
    public void multicastInFlightQuestionCapCannotBeExceeded() throws InterruptedException {
        int questionCap = 1024;
        BlockingMulticastBus bus = new BlockingMulticastBus(questionCap);
        MulticastRegistryClient client = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions("mcast-cap-client", "default", "239.7.0.1", 47070, null,
                        Duration.ofSeconds(30L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus));
        RegistryQuery query = new RegistryQuery("missing", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10, System.currentTimeMillis() + 30_000L, 32, 0, DiscoverySource.MULTICAST);
        List<Thread> threads = new java.util.ArrayList<>(questionCap);
        for (int index = 0; index < questionCap; index++) {
            Thread thread = Thread.ofVirtual().name("j7m-mcast-cap-test-", index).start(() -> client.list(query));
            threads.add(thread);
        }
        assertTrue(bus.awaitEntered());

        MiddlewareException failure = assertThrows(MiddlewareException.class, () -> client.list(query));

        assertEquals(MiddlewareErrorCode.OVERLOADED, failure.code());
        assertTrue(client.activeDiscoveryQuestions() <= questionCap);
        assertEquals(questionCap, bus.maxActive());
        bus.release();
        for (Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive());
        }
        assertEquals(0, client.activeDiscoveryQuestions());
        client.close();
    }

    @Test
    public void multicastEffectiveResultLimitIsAppliedBeforeRetention() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-bounds");
        long nowMillis = System.currentTimeMillis();
        List<RegistryRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ObjectRef untrustedRef = ref("untrusted-bound-" + index, "bounded-service", "runtime-untrusted", "default",
                    nowMillis + 30_000L, Map.of(), 1L);
            RegistryRecord unsigned = new RegistryRecord(untrustedRef, Map.of(), nowMillis, nowMillis, untrustedRef.leaseUntil(),
                    ObjectState.EXECUTED);
            records.add(unsigned.withAuthentication(Map.of("keyId", "mcast-bounds", "algorithm", "J7M-TEST-SHA256", "signature", "bad")));
        }
        for (int index = 0; index < 5; index++) {
            ObjectRef trustedRef = ref("trusted-bound-" + index, "bounded-service", "runtime-trusted-" + index, "default",
                    nowMillis + 30_000L, Map.of(), 1L);
            records.add(signedRecord(trustedRef, nowMillis, nowMillis + index, trustedRef.leaseUntil(), ObjectState.EXECUTED, signer));
        }
        CapturingMulticastBus belowLimitBus = new CapturingMulticastBus(records);
        MulticastRegistryClient belowLimit = boundedMulticast("bounded-below", belowLimitBus, signer, 4, 3);
        List<RegistryRecord> belowLimitRecords = belowLimit.list(new RegistryQuery("bounded-service", List.of(Greeter.class.getName()),
                Map.of(), "default", nowMillis, 2, nowMillis + 2_000L, Integer.MAX_VALUE, 0, DiscoverySource.MULTICAST));

        assertEquals(2, belowLimitRecords.size());
        assertEquals(2, belowLimitBus.observedLimit.get());
        assertEquals(3, belowLimitBus.observedPeers.get());
        assertEquals(0, belowLimit.activeDiscoveryQuestions());
        belowLimit.close();

        CapturingMulticastBus aboveLimitBus = new CapturingMulticastBus(records);
        MulticastRegistryClient aboveLimit = boundedMulticast("bounded-above", aboveLimitBus, signer, 4, 3);
        List<RegistryRecord> aboveLimitRecords = aboveLimit.list(new RegistryQuery("bounded-service", List.of(Greeter.class.getName()),
                Map.of(), "default", nowMillis, Integer.MAX_VALUE, nowMillis + 2_000L, Integer.MAX_VALUE, 0, DiscoverySource.MULTICAST));

        assertEquals(3, aboveLimitRecords.size());
        assertEquals(3, aboveLimitBus.observedLimit.get());
        assertEquals(3, aboveLimitBus.observedPeers.get());
        assertEquals(0, aboveLimit.activeDiscoveryQuestions());
        aboveLimit.close();
    }

    @Test
    public void multicastResponderCapIsIndependentOfDuplicateRecordVolume() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("mcast-responders");
        MulticastRegistryBus bus = new MulticastRegistryBus();
        List<MulticastRegistryClient> peers = new java.util.ArrayList<>();
        long nowMillis = System.currentTimeMillis();
        for (int index = 0; index < 6; index++) {
            MulticastRegistryClient peer = multicast("responder-peer-" + index, "default", bus, Clock.systemUTC(), signer);
            ObjectRef ref = ref("responder-object-" + index, "responder-service", "runtime-responder-" + index, "default",
                    nowMillis + 30_000L, Map.of(), 1L);
            peer.bind(signedRecord(ref, nowMillis, nowMillis + index, ref.leaseUntil(), ObjectState.EXECUTED, signer));
            peers.add(peer);
        }
        MulticastRegistryClient requester = boundedMulticast("responder-requester", bus, signer, 64, 2);

        List<RegistryRecord> records = requester.list(new RegistryQuery("responder-service", List.of(Greeter.class.getName()), Map.of(),
                "default", nowMillis, Integer.MAX_VALUE, nowMillis + 2_000L, Integer.MAX_VALUE, 0, DiscoverySource.MULTICAST));

        assertTrue(records.size() <= 2);
        assertTrue(bus.diagnostics().sent() <= 2L);
        assertEquals(0, requester.activeDiscoveryQuestions());
        requester.close();
        for (MulticastRegistryClient peer : peers) {
            peer.close();
        }
    }

    @Test
    public void bootstrapFindsServiceDirectlyAndThroughTwoHops() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("bootstrap-basic");
        EmbeddedRegistryClient registryA = new EmbeddedRegistryClient();
        EmbeddedRegistryClient registryC = new EmbeddedRegistryClient();
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("registry-a", registryA);
        resolver.register("registry-c", registryC);
        registryA.updatePeer(peer("registry-c", "registry-c", RegistryPeerState.ALIVE, 100_000L));
        ObjectRef direct = ref("direct", "direct-service", "runtime-a", "default", System.currentTimeMillis() + 30_000L, Map.of());
        ObjectRef twoHop = ref("two-hop", "two-hop-service", "runtime-c", "default", System.currentTimeMillis() + 30_000L, Map.of());
        registryA.bind(signedRecord(direct, signer));
        registryC.bind(signedRecord(twoHop, signer));
        BootstrapRegistryClient bootstrap = bootstrap(new EmbeddedRegistryClient(), resolver, List.of("registry-a"), 2, 8, Duration.ofSeconds(2L), signer);

        assertEquals(1, bootstrap.list(new RegistryQuery("direct-service", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size());
        assertEquals(1, bootstrap.list(new RegistryQuery("two-hop-service", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size());
        assertTrue(bootstrap.contactedBootstrapPeerCount() >= 2);
    }

    @Test
    public void bootstrapHonorsDepthDeadlineContactLimitsAndCacheRefresh() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("bootstrap-bounds");
        EmbeddedRegistryClient registryA = new EmbeddedRegistryClient();
        EmbeddedRegistryClient registryC = new EmbeddedRegistryClient();
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("registry-a", registryA);
        resolver.register("registry-c", registryC);
        registryA.updatePeer(peer("registry-c", "registry-c", RegistryPeerState.ALIVE, 100_000L));
        ObjectRef twoHop = ref("two-hop", "svc", "runtime-c", "default", System.currentTimeMillis() + 30_000L, Map.of());
        registryC.bind(signedRecord(twoHop, signer));
        BootstrapRegistryClient depthZero = bootstrap(new EmbeddedRegistryClient(), resolver, List.of("registry-a"), 0, 8, Duration.ofSeconds(2L), signer);
        BootstrapRegistryClient maxOneContact = bootstrap(new EmbeddedRegistryClient(), resolver, List.of("missing", "registry-c"), 2, 1, Duration.ofSeconds(2L), signer);
        BootstrapRegistryClient deadline = bootstrap(new EmbeddedRegistryClient(), resolver, List.of("registry-c"), 2, 8, Duration.ZERO, signer);

        assertTrue(depthZero.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).isEmpty());
        assertTrue(maxOneContact.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).isEmpty());
        assertTrue(deadline.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size() <= 1);

        BootstrapRegistryClient cached = bootstrap(new EmbeddedRegistryClient(), resolver, List.of("registry-c"), 2, 8, Duration.ofSeconds(2L), signer);
        assertEquals(1, cached.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size());
        registryC.unbind(twoHop);
        assertEquals(1, cached.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size());
        cached.clearCache();
        assertTrue(cached.list(new RegistryQuery("svc", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).isEmpty());
    }

    @Test
    public void bootstrapStrictTrustRejectsUnsignedRecordsAndBoundsPeerCycles() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("bootstrap-trust");
        EmbeddedRegistryClient registryA = new EmbeddedRegistryClient();
        EmbeddedRegistryClient registryB = new EmbeddedRegistryClient();
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("registry-a", registryA);
        resolver.register("registry-b", registryB);
        registryA.updatePeer(peer("registry-b", "registry-b", RegistryPeerState.ALIVE, 100_000L));
        registryB.updatePeer(peer("registry-a", "registry-a", RegistryPeerState.ALIVE, 100_000L));
        ObjectRef unsigned = ref("bootstrap-unsigned", "bootstrap-strict", "runtime-a", "default", System.currentTimeMillis() + 30_000L, Map.of());
        ObjectRef signedRef = ref("bootstrap-signed", "bootstrap-strict", "runtime-b", "default", System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord signedBase = new RegistryRecord(signedRef, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), signedRef.leaseUntil(), ObjectState.EXECUTED);
        registryA.bind(new RegistryRecord(unsigned, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), unsigned.leaseUntil(), ObjectState.EXECUTED));
        registryB.bind(signedBase.withAuthentication(signer.sign(signedBase)));
        BootstrapRegistryClient bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(),
                new BootstrapRegistryOptions("bootstrap-strict", "default", List.of("registry-a"), Duration.ofSeconds(2L), 4, 2, 64,
                        Duration.ofMillis(1L), resolver),
                signer, true, null);

        List<RegistryRecord> records = bootstrap.list(new RegistryQuery("bootstrap-strict", List.of(Greeter.class.getName()), Map.of(), "default",
                System.currentTimeMillis(), 10, System.currentTimeMillis() + 2_000L, 2, 4, DiscoverySource.BOOTSTRAP));

        assertEquals(1, records.size());
        assertEquals("bootstrap-signed", records.getFirst().ref().objectId());
        assertTrue(bootstrap.contactedBootstrapPeerCount() <= 2);
    }

    @Test
    public void bootstrapNonStrictDowngradeIsNotReturnedAsRemoteCandidate() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("bootstrap-lenient");
        ObjectRef ref = ref("bootstrap-downgraded", "bootstrap-lenient-service", "runtime-bad", "default",
                System.currentTimeMillis() + 30_000L, Map.of());
        RegistryRecord base = new RegistryRecord(ref, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), ref.leaseUntil(), ObjectState.EXECUTED);
        RegistryRecord tampered = base.withAuthentication(Map.of("keyId", "bootstrap-lenient", "algorithm", "J7M-TEST-SHA256", "signature", "bad"));
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("bad-peer", new FixedRegistryClient(List.of(tampered)));
        BootstrapRegistryClient bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(),
                new BootstrapRegistryOptions("bootstrap-lenient-client", "default", List.of("bad-peer"), Duration.ofSeconds(2L), 1, 4, 64,
                        Duration.ofMillis(1L), resolver),
                signer, false, null);

        List<RegistryRecord> records = bootstrap.list(new RegistryQuery("bootstrap-lenient-service", List.of(Greeter.class.getName()), Map.of(),
                "default", System.currentTimeMillis(), 10, System.currentTimeMillis() + 2_000L, 4, 1, DiscoverySource.BOOTSTRAP));

        assertTrue(records.isEmpty());
        assertEquals(1L, bootstrap.discoveryStatistics().metadata().get("registryTrustDowngraded").longValue());
    }

    @Test
    public void peerGraphPenalizesDeadPeerAndStatusExposesDiscoveryMetrics() {
        EmbeddedRegistryClient local = new EmbeddedRegistryClient();
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        BootstrapRegistryClient bootstrap = bootstrap(local, resolver, List.of("dead-peer"), 1, 4, Duration.ofMillis(500L));

        assertTrue(bootstrap.list(new RegistryQuery("missing", List.of(Greeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).isEmpty());

        List<RegistryPeerStatus> peers = bootstrap.peers(new PeerQuery("default", true, 10));
        assertTrue(peers.stream().anyMatch(peer -> peer.peerId().equals("dead-peer") && peer.state() == RegistryPeerState.DEAD));
        assertTrue(bootstrap.discoveryStatistics().peerContactFailures() >= 1L);
    }

    @Test
    public void multicastPeerGraphIsCappedExpiredAndClearedOnClose() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T00:00:00Z"));
        MulticastRegistryClient registry = new MulticastRegistryClient(new EmbeddedRegistryStore(), clock,
                new MulticastRegistryOptions("peer-cap", "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofMillis(10L), 64, 2, new MulticastRegistryBus()));
        registry.updatePeer(peerAt("peer-a", "peer-a", RegistryPeerState.ALIVE, 1L, clock.millis()));
        registry.updatePeer(peerAt("peer-b", "peer-b", RegistryPeerState.ALIVE, 1L, clock.millis()));
        registry.updatePeer(peerAt("peer-c", "peer-c", RegistryPeerState.DEAD, 1L, clock.millis()));

        assertTrue(registry.discoveryStatistics().metadata().get("peerGraphSize").longValue() <= 2L);
        List<RegistryPeerStatus> cappedPeers = registry.peers(new PeerQuery("default", true, 10));
        assertTrue(cappedPeers.size() <= 2);

        clock.advance(Duration.ofMillis(25L));
        registry.peers(new PeerQuery("default", true, 10));
        assertEquals(0L, registry.discoveryStatistics().metadata().get("peerGraphSize").longValue());

        registry.updatePeer(peerAt("peer-d", "peer-d", RegistryPeerState.ALIVE, 1L, clock.millis()));
        registry.close();
        assertEquals(0L, registry.discoveryStatistics().metadata().get("peerGraphSize").longValue());
        assertEquals(MiddlewareErrorCode.UNAVAILABLE, assertThrows(MiddlewareException.class,
                () -> registry.updatePeer(peerAt("peer-after-close", "peer-after-close", RegistryPeerState.ALIVE, 1L, clock.millis()))).code());
        assertEquals(0L, registry.discoveryStatistics().metadata().get("peerGraphSize").longValue());
    }

    @Test
    public void hpSelectorPrefersLowerLoadCandidateAndAdminShowsDecisions() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime slow = runtime("hp-slow", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default");
             DefaultMiddlewareRuntime fast = runtime("hp-fast", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default")) {
            slow.export("greeter", new TestGreeter("slow"), Greeter.class, export(Map.of("role", "primary", "load", "10.0", "latencyNanos", "9000000")));
            fast.export("greeter", new TestGreeter("fast"), Greeter.class, export(Map.of("role", "primary", "load", "0.0", "latencyNanos", "1000")));
            Greeter ha = slow.lookup("greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            assertEquals("fast:client", ha.greet("client"));
            String status = new RuntimeAdmin(slow).formatStatus();
            assertTrue(status.contains("hpDecisions="));
            assertFalse(slow.status().registry().recentHpDecisions().isEmpty());
        }
    }

    @Test
    public void multicastAdapterListCollectsCandidatesAndReportsDiagnostics() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("udp-basic");
        MulticastRegistryBus bus = new MulticastRegistryBus();
        MulticastRegistryClient registryA = multicast("udp-a", "default", bus, Clock.systemUTC(), signer);
        MulticastRegistryClient registryB = multicast("udp-b", "default", bus, Clock.systemUTC(), signer);
        MulticastRegistryClient registryC = multicast("udp-c", "default", bus, Clock.systemUTC(), signer);
        ObjectRef first = ref("udp-1", "udp-service", "runtime-a", "default", System.currentTimeMillis() + 30_000L, Map.of("zone", "eu"));
        ObjectRef second = ref("udp-2", "udp-service", "runtime-b", "default", System.currentTimeMillis() + 30_000L, Map.of("zone", "eu"));
        registryA.bind(signedRecord(first, signer));
        registryB.bind(signedRecord(second, signer));

        List<RegistryRecord> records = registryC.list(new RegistryQuery("udp-service", List.of(Greeter.class.getName()), Map.of("zone", "eu"), "default",
                System.currentTimeMillis(), 10));

        assertEquals(2, records.size());
        assertEquals("in-memory", registryC.multicastAdapterType());
        assertTrue(registryC.discoveryStatistics().multicastMessagesSent() > 0L);
        assertTrue(registryA.discoveryStatistics().multicastMessagesReceived() > 0L || registryB.discoveryStatistics().multicastMessagesReceived() > 0L);
        assertEquals("udp", new UdpMulticastBus().adapterType());
        assertEquals("udp", MulticastRegistryOptions.defaults("udp-default").bus().adapterType());
        registryA.close();
        registryB.close();
        registryC.close();
        assertFalse(registryC.multicastListenerRunning());
    }

    @Test
    public void multicastAdapterContextIsolationIsDeterministic() {
        MulticastRegistryBus bus = new MulticastRegistryBus();
        MulticastRegistryClient registryA = multicast("udp-context-a", "alpha", bus);
        MulticastRegistryClient registryB = multicast("udp-context-b", "beta", bus);
        ObjectRef ref = ref("udp-context", "udp-context-service", "runtime-a", "alpha", System.currentTimeMillis() + 30_000L, Map.of());
        registryA.bind(new RegistryRecord(ref, ref.metadata(), System.currentTimeMillis(), System.currentTimeMillis(), ref.leaseUntil(), ObjectState.EXECUTED));

        assertTrue(registryB.list(new RegistryQuery("udp-context-service", List.of(Greeter.class.getName()), Map.of(), "beta",
                System.currentTimeMillis(), 10)).isEmpty());

        registryA.close();
        registryB.close();
    }

    @Test
    public void forgedRemoteBenchmarkMetadataDoesNotRunProbe() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime slow = runtime("bench-slow", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default");
             DefaultMiddlewareRuntime fast = runtime("bench-fast", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default");
             DefaultMiddlewareRuntime client = runtime("bench-client", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default")) {
            slow.export("bench.greeter", new BenchGreeterImpl("slow", true, false), BenchGreeter.class,
                    export(Map.of("role", "primary", "benchmarkMethod", "ping", "benchmarkInterface", BenchGreeter.class.getName())));
            fast.export("bench.greeter", new BenchGreeterImpl("fast", false, false), BenchGreeter.class,
                    export(Map.of("role", "primary", "benchmarkMethod", "ping", "benchmarkInterface", BenchGreeter.class.getName())));
            BenchGreeter greeter = client.lookup("bench.greeter", BenchGreeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            assertEquals("fast:one", greeter.greet("one"));
            long probes = client.status().registry().metrics().getOrDefault("hpBenchmarkCount", 0L);
            assertEquals("fast:two", greeter.greet("two"));

            assertEquals(0L, probes);
            assertEquals(probes, client.status().registry().metrics().getOrDefault("hpBenchmarkCount", 0L));
        }
    }

    @Test
    public void nonAnnotatedBenchmarkMetadataDoesNotExecute() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime failing = runtime("bench-fail", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default");
             DefaultMiddlewareRuntime plain = runtime("bench-plain", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default");
             DefaultMiddlewareRuntime client = runtime("bench-fail-client", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default")) {
            failing.export("bench.fail.greeter", new BenchGreeterImpl("failing", false, true), BenchGreeter.class, export(Map.of("role", "primary", "load", "0.0")));
            plain.export("bench.fail.greeter", new TestGreeter("plain"), Greeter.class,
                    export(Map.of("role", "primary", "load", "0.1", "benchmarkMethod", "greet", "benchmarkInterface", Greeter.class.getName())));
            Greeter greeter = client.lookup("bench.fail.greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            assertTrue(greeter.greet("client").contains("client"));
            assertEquals(0L, client.status().registry().metrics().getOrDefault("hpBenchmarkFailures", 0L));
        }
    }

    @Test
    public void spoofedLocalBenchmarkReferenceDoesNotExecuteProbe() {
        MutableRegistryClient registry = new MutableRegistryClient();
        try (DefaultMiddlewareRuntime runtime = runtime("bench-spoof-client", registry, "default")) {
            ObjectRegistration<BenchGreeter> local = runtime.export("local.bench", new BenchGreeterImpl("local", false, false),
                    BenchGreeter.class, export(Map.of("role", "local")));
            ObjectRef localRef = local.ref();
            ObjectRef forged = new ObjectRef(localRef.objectId(), localRef.logicalName(), "spoof.bench", localRef.runtimeId(),
                    localRef.registryContext(), List.of(BenchGreeter.class.getName()), localRef.transport(),
                    localRef.endpoint() + "-forged", localRef.leaseUntil(), localRef.generation(), Map.of(
                            "role", "primary",
                            "trustStatus", "trusted",
                            "fencingToken", localRef.metadata().get("fencingToken"),
                            "topologyGeneration", localRef.metadata().get("topologyGeneration"),
                            "benchmarkMethod", "benchmark",
                            "benchmarkInterface", BenchGreeter.class.getName()));
            registry.records = List.of(new RegistryRecord(forged, forged.metadata(), System.currentTimeMillis(), System.currentTimeMillis(),
                    forged.leaseUntil(), ObjectState.EXECUTED, Map.of("trustStatus", "trusted")));

            assertThrows(MiddlewareException.class, () -> runtime.lookup("spoof.bench", BenchGreeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, false)));

            assertEquals(0L, runtime.status().registry().metrics().getOrDefault("hpBenchmarkCount", 0L));
        }
    }

    @Test
    public void benchmarkPermitReleasedWhenPostAcquireRevalidationFails() throws Exception {
        MutableRegistryClient registry = new MutableRegistryClient();
        try (DefaultMiddlewareRuntime runtime = runtime("bench-permit-client", registry, "default")) {
            ObjectRegistration<BenchGreeter> local = runtime.export("permit.bench", new BenchGreeterImpl("local", false, false),
                    BenchGreeter.class, export(Map.of("role", "local")));
            ObjectRef localRef = local.ref();
            ObjectRef forged = localRef.withTransport(localRef.transport(), localRef.endpoint() + "-forged");
            Field permitsField = DefaultMiddlewareRuntime.class.getDeclaredField("benchmarkPermits");
            permitsField.setAccessible(true);
            Semaphore permits = (Semaphore) permitsField.get(runtime);
            int permitsBefore = permits.availablePermits();
            Method benchmarkMethod = BenchGreeter.class.getMethod("benchmark");
            Method probe = DefaultMiddlewareRuntime.class.getDeclaredMethod("probeBenchmark", ObjectRef.class, Method.class, long.class, long.class);
            probe.setAccessible(true);

            probe.invoke(runtime, forged, benchmarkMethod, Long.valueOf(System.currentTimeMillis()), Long.valueOf(System.nanoTime() + 1_000_000_000L));

            assertEquals(permitsBefore, permits.availablePermits());
            assertEquals(1L, runtime.status().registry().metrics().getOrDefault("hpBenchmarkFailures", 0L));
        }
    }

    @Test
    public void benchmarkProbeDoesNotBeginAfterDeadlineExpiry() throws Exception {
        MutableRegistryClient registry = new MutableRegistryClient();
        CountingBenchGreeter greeter = new CountingBenchGreeter("expired", new CountDownLatch(0));
        try (DefaultMiddlewareRuntime runtime = runtime("bench-expired-client", registry, "default")) {
            ObjectRegistration<BenchGreeter> registration = runtime.export("expired.bench", greeter, BenchGreeter.class,
                    export(Map.of("role", "local")));
            Method benchmarkMethod = BenchGreeter.class.getMethod("benchmark");
            Method probe = DefaultMiddlewareRuntime.class.getDeclaredMethod("probeBenchmark", ObjectRef.class, Method.class, long.class, long.class);
            probe.setAccessible(true);

            probe.invoke(runtime, registration.ref(), benchmarkMethod, Long.valueOf(System.currentTimeMillis()), Long.valueOf(System.nanoTime() - 1L));

            assertEquals(0, greeter.begins.get());
            assertEquals(0L, runtime.status().registry().metrics().getOrDefault("hpBenchmarkCount", 0L));
        }
    }

    @Test
    public void benchmarkTimeoutIsCappedByRemainingBudgetAndReleasesPermit() throws Exception {
        MutableRegistryClient registry = new MutableRegistryClient();
        CountDownLatch release = new CountDownLatch(1);
        CountingBenchGreeter greeter = new CountingBenchGreeter("blocked", release);
        try (DefaultMiddlewareRuntime runtime = runtime("bench-budget-client", registry, "default")) {
            ObjectRegistration<BenchGreeter> registration = runtime.export("budget.bench", greeter, BenchGreeter.class,
                    export(Map.of("role", "local")));
            Field permitsField = DefaultMiddlewareRuntime.class.getDeclaredField("benchmarkPermits");
            permitsField.setAccessible(true);
            Semaphore permits = (Semaphore) permitsField.get(runtime);
            int permitsBefore = permits.availablePermits();
            Method benchmarkMethod = BenchGreeter.class.getMethod("benchmark");
            Method probe = DefaultMiddlewareRuntime.class.getDeclaredMethod("probeBenchmark", ObjectRef.class, Method.class, long.class, long.class);
            probe.setAccessible(true);
            long startedNanos = System.nanoTime();

            probe.invoke(runtime, registration.ref(), benchmarkMethod, Long.valueOf(System.currentTimeMillis()),
                    Long.valueOf(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20L)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            release.countDown();

            assertTrue(elapsedMillis < 100L);
            assertEquals(permitsBefore, permits.availablePermits());
            assertTrue(greeter.begins.get() <= 1);
        }
    }

    @Test
    public void fastTrustedBenchmarkCandidateStillSelects() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime runtime = runtime("bench-select-client", new EmbeddedRegistryClient(store, Clock.systemUTC()), "default")) {
            runtime.export("bench.select", new BenchGreeterImpl("slow", true, false), BenchGreeter.class,
                    export(Map.of("role", "primary", "load", "0.0")));
            runtime.export("bench.select", new BenchGreeterImpl("fast", false, false), BenchGreeter.class,
                    export(Map.of("role", "primary", "load", "0.0")));

            BenchGreeter selected = runtime.lookup("bench.select", BenchGreeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), false, false));

            assertEquals("fast:client", selected.greet("client"));
            assertTrue(runtime.status().registry().metrics().getOrDefault("hpBenchmarkCount", 0L) >= 2L);
        }
    }

    @Test
    public void unmarkedRegistryRecordIsNotRuntimeCandidate() {
        long nowMillis = System.currentTimeMillis();
        ObjectRef trustedLooking = ref("unmarked", "unmarked.service", "remote-runtime", "default", nowMillis + 30_000L,
                candidateMetadata("0.0"));
        RegistryRecord unmarked = new RegistryRecord(trustedLooking, trustedLooking.metadata(), nowMillis, nowMillis,
                trustedLooking.leaseUntil(), ObjectState.EXECUTED, Map.of());
        try (DefaultMiddlewareRuntime runtime = runtime("unmarked-client", new FixedRegistryClient(List.of(unmarked)), "default")) {
            List<ObjectRef> refs = runtime.list("unmarked.service", List.of(Greeter.class.getName()), Map.of(),
                    LookupOptions.defaults());

            assertTrue(refs.isEmpty());
        }
    }

    @Test
    public void hpSelectorRejectsInvalidNumericMetadataAndBreaksTiesDeterministically() {
        long nowMillis = System.currentTimeMillis();
        ObjectRef bad = ref("bad", "hp", "runtime-b", "default", nowMillis + 30_000L,
                Map.of("load", "NaN", "trustStatus", "trusted", "fencingToken", "1", "topologyGeneration", "1"));
        ObjectRef unmarked = ref("unmarked", "hp", "runtime-unmarked", "default", nowMillis + 30_000L,
                Map.of("load", "0.0", "fencingToken", "1", "topologyGeneration", "1"));
        ObjectRef untrusted = ref("untrusted", "hp", "runtime-0", "default", nowMillis + 30_000L,
                Map.of("load", "0.0", "trustStatus", "untrusted", "fencingToken", "1", "topologyGeneration", "1"));
        ObjectRef invalidFence = ref("invalid-fence", "hp", "runtime-1", "default", nowMillis + 30_000L,
                Map.of("load", "0.0", "trustStatus", "trusted", "fencingToken", "-1", "topologyGeneration", "1"));
        ObjectRef missingTopology = ref("missing-topology", "hp", "runtime-2", "default", nowMillis + 30_000L,
                Map.of("load", "0.0", "trustStatus", "trusted", "fencingToken", "1"));
        ObjectRef high = ref("high", "hp", "runtime-c", "default", nowMillis + 30_000L, candidateMetadata("0.5"));
        ObjectRef low = ref("low", "hp", "runtime-a", "default", nowMillis + 30_000L, candidateMetadata("0.5"));
        RegistryPeerStatus deadLow = new RegistryPeerStatus("low-peer", false, Map.of("runtimeId", "runtime-a"));
        ObjectRef liveLow = ref("live-low", "hp", "runtime-a2", "default", nowMillis + 30_000L, candidateMetadata("0.5"));

        HpSelection selection = new HpSelector().select(List.of(bad, unmarked, untrusted, invalidFence, missingTopology, high, low, liveLow), "local",
                List.of(deadLow), objectId -> null, Clock.systemUTC());

        assertEquals("live-low", selection.selected().objectId());
        assertEquals(2, selection.scores().size());
    }

    @Test
    public void hpSelectorTreatsPeerIdAsRuntimeIdentityForHealth() {
        long nowMillis = System.currentTimeMillis();
        ObjectRef deadRuntime = ref("dead-runtime", "hp-peer", "runtime-a", "default", nowMillis + 30_000L,
                candidateMetadata("0.0"));
        ObjectRef liveRuntime = ref("live-runtime", "hp-peer", "runtime-b", "default", nowMillis + 30_000L,
                candidateMetadata("0.1"));
        RegistryPeerStatus deadPeer = new RegistryPeerStatus("runtime-a", false, Map.of());

        HpSelection selection = new HpSelector().select(List.of(deadRuntime, liveRuntime), "local",
                List.of(deadPeer), objectId -> null, Clock.systemUTC());

        assertEquals("live-runtime", selection.selected().objectId());
        assertEquals(1, selection.scores().size());
    }

    @Test
    public void slidingWindowStatisticsForgetEvictedFailures() {
        SlidingWindowStatistics statistics = new SlidingWindowStatistics(2);
        statistics.record("svc", 1L, 100L, false, MiddlewareErrorCode.REMOTE_EXCEPTION);
        statistics.record("svc", 2L, 200L, true, null);
        statistics.record("svc", 3L, 300L, true, null);

        StatisticsSnapshot snapshot = statistics.snapshot();
        assertEquals(2L, snapshot.totalCount());
        assertEquals(2L, snapshot.successCount());
        assertEquals(0L, snapshot.failureCount());
        assertEquals(250L, snapshot.averageLatencyNanos());
        assertEquals(1.0D, statistics.successRate());
    }

    @Test
    public void slidingWindowStatisticsForgetStaleFailuresWithoutCapacityPressure() {
        AtomicLong nowMillis = new AtomicLong(10_000L);
        SlidingWindowStatistics statistics = new SlidingWindowStatistics(8, Duration.ofMillis(1_000L), nowMillis::get);
        statistics.record("svc", 8_000L, 100L, false, MiddlewareErrorCode.REMOTE_EXCEPTION);
        statistics.record("svc", 9_500L, 200L, true, null);

        StatisticsSnapshot snapshot = statistics.snapshot();

        assertEquals(1L, snapshot.totalCount());
        assertEquals(1L, snapshot.successCount());
        assertEquals(0L, snapshot.failureCount());
        assertEquals(1.0D, statistics.successRate());
    }

    @Test
    public void slidingWindowStatisticsRejectsCounterCardinalityOverflow() {
        SlidingWindowStatistics statistics = new SlidingWindowStatistics(4, Duration.ofSeconds(10L), () -> 1_000L, 3);

        for (int index = 0; index < 10; index++) {
            statistics.increment("counter-" + index);
        }

        StatisticsSnapshot snapshot = statistics.snapshot();
        assertEquals(3, snapshot.counters().size());
        assertTrue(snapshot.counters().containsKey("counter-0"));
        assertTrue(snapshot.counters().containsKey("counter-1"));
        assertTrue(snapshot.counters().containsKey("counter-2"));
        assertFalse(snapshot.counters().containsKey("counter-9"));
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final org.j7m.middleware.registry.api.RegistryClient registry, final String context) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registryContext(context)
                .registry(registry)
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build();
    }

    private static MulticastRegistryClient multicast(final String id, final String context, final MulticastRegistryBus bus) {
        return multicast(id, context, bus, Clock.systemUTC());
    }

    private static MulticastRegistryClient multicast(final String id, final String context, final MulticastRegistryBus bus, final Clock clock) {
        return new MulticastRegistryClient(new EmbeddedRegistryStore(), clock,
                new MulticastRegistryOptions(id, context, "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus));
    }

    private static MulticastRegistryClient multicast(
            final String id,
            final String context,
            final MulticastRegistryBus bus,
            final Clock clock,
            final DeterministicRegistryRecordSigner signer) {
        return new MulticastRegistryClient(new EmbeddedRegistryStore(), clock,
                new MulticastRegistryOptions(id, context, "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), 64, 32, bus),
                signer, true, null);
    }

    private static BootstrapRegistryClient bootstrap(
            final EmbeddedRegistryClient local,
            final InMemoryBootstrapEndpointResolver resolver,
            final List<String> endpoints,
            final int maxDepth,
            final int maxContacts,
            final Duration deadline) {
        return new BootstrapRegistryClient(local, new BootstrapRegistryOptions("bootstrap-test", "default", endpoints,
                deadline, maxDepth, maxContacts, 64, Duration.ofSeconds(30L), resolver));
    }

    private static BootstrapRegistryClient bootstrap(
            final EmbeddedRegistryClient local,
            final InMemoryBootstrapEndpointResolver resolver,
            final List<String> endpoints,
            final int maxDepth,
            final int maxContacts,
            final Duration deadline,
            final DeterministicRegistryRecordSigner signer) {
        return new BootstrapRegistryClient(local, Clock.systemUTC(), new BootstrapRegistryOptions("bootstrap-test", "default", endpoints,
                deadline, maxDepth, maxContacts, 64, Duration.ofSeconds(30L), resolver), signer, true, null);
    }

    private static MulticastRegistryClient boundedMulticast(
            final String id,
            final MulticastBus bus,
            final DeterministicRegistryRecordSigner signer,
            final int maxResults,
            final int maxPeers) {
        return new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions(id, "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(1L), maxResults, maxPeers, bus),
                signer, true, null);
    }

    private static ExportOptions export(final Map<String, String> metadata) {
        return new ExportOptions(false, "grpc", "default", List.of(), metadata, Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static Map<String, String> candidateMetadata(final String load) {
        return Map.of("load", load, "trustStatus", "trusted", "fencingToken", "1", "topologyGeneration", "1");
    }

    private static ObjectRef ref(
            final String objectId,
            final String service,
            final String runtime,
            final String context,
            final long leaseUntil,
            final Map<String, String> metadata) {
        return ref(objectId, service, runtime, context, leaseUntil, metadata, 1L);
    }

    private static ObjectRef ref(
            final String objectId,
            final String service,
            final String runtime,
            final String context,
            final long leaseUntil,
            final Map<String, String> metadata,
            final long generation) {
        return new ObjectRef(objectId, service + "/" + objectId, service, runtime, context,
                List.of(Greeter.class.getName()), "grpc", "grpc://127.0.0.1:1", leaseUntil, generation, metadata);
    }

    private static RegistryPeerStatus peer(final String id, final String endpoint, final RegistryPeerState state, final long latencyNanos) {
        return peerAt(id, endpoint, state, latencyNanos, System.currentTimeMillis());
    }

    private static RegistryPeerStatus peerAt(
            final String id,
            final String endpoint,
            final RegistryPeerState state,
            final long latencyNanos,
            final long nowMillis) {
        return new RegistryPeerStatus(id, state == RegistryPeerState.ALIVE, state, false,
                latencyNanos > 5_000_000L ? RegistryPeerQuality.SLOW : RegistryPeerQuality.FAST,
                false, RegistryTrustStatus.UNVERIFIED, nowMillis, latencyNanos, 0.0D,
                state == RegistryPeerState.ALIVE ? 1.0D : 0.0D,
                List.of(new RegistryEndpoint("bootstrap", endpoint, Map.of())),
                List.of(1), List.of("default"), "test", nowMillis, Map.of());
    }

    private static RegistryRecord signedRecord(final ObjectRef ref, final DeterministicRegistryRecordSigner signer) {
        return signedRecord(ref, System.currentTimeMillis(), signer);
    }

    private static RegistryRecord signedRecord(final ObjectRef ref, final long nowMillis, final DeterministicRegistryRecordSigner signer) {
        RegistryRecord base = new RegistryRecord(ref, ref.metadata(), nowMillis, nowMillis, ref.leaseUntil(), ObjectState.EXECUTED);
        return base.withAuthentication(signer.sign(base));
    }

    private static RegistryRecord signedRecord(
            final ObjectRef ref,
            final long createdAtMillis,
            final long lastHeartbeatMillis,
            final long leaseUntilMillis,
            final ObjectState state,
            final DeterministicRegistryRecordSigner signer) {
        RegistryRecord base = new RegistryRecord(ref, ref.metadata(), createdAtMillis, lastHeartbeatMillis, leaseUntilMillis, state);
        return base.withAuthentication(signer.sign(base));
    }

    private static byte[] packetWithRecordCount(final int recordCount) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4A374D32);
            out.writeInt(2);
            writeEnvelope(out);
            out.writeBoolean(false);
            out.writeInt(recordCount);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] packetWithDuplicateMetadataKey() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4A374D32);
            out.writeInt(2);
            writeString(out, MulticastMessageType.QUESTION.name());
            writeString(out, "sender");
            writeString(out, "*");
            writeString(out, "default");
            writeString(out, "q-duplicate");
            writeString(out, MulticastQueryType.LIST.name());
            out.writeLong(System.currentTimeMillis());
            out.writeLong(500L);
            out.writeInt(0);
            out.writeInt(2);
            out.writeInt(2);
            writeString(out, "duplicate");
            writeString(out, "one");
            writeString(out, "duplicate");
            writeString(out, "two");
            out.writeBoolean(false);
            out.writeInt(0);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] packetWithMalformedUtf8() {
        return new byte[] {
                0x4A, 0x37, 0x4D, 0x32,
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x01,
                (byte) 0xC3
        };
    }

    private static void writeEnvelope(final DataOutputStream out) throws IOException {
        writeString(out, MulticastMessageType.QUESTION.name());
        writeString(out, "sender");
        writeString(out, "*");
        writeString(out, "default");
        writeString(out, "q-count");
        writeString(out, MulticastQueryType.LIST.name());
        out.writeLong(System.currentTimeMillis());
        out.writeLong(500L);
        out.writeInt(0);
        out.writeInt(2);
        out.writeInt(0);
    }

    private static void writeString(final DataOutputStream out, final String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class InjectedRecordMulticastBus implements MulticastBus {
        private final List<RegistryRecord> records;

        private InjectedRecordMulticastBus(final List<RegistryRecord> records) {
            this.records = records == null ? List.of() : List.copyOf(records);
        }

        @Override
        public void register(final MulticastRegistryClient client) {
        }

        @Override
        public void unregister(final MulticastRegistryClient client) {
        }

        @Override
        public List<RegistryRecord> list(
                final MulticastRegistryClient client,
                final MulticastMessageEnvelope envelope,
                final RegistryQuery query,
                final long deadlineMillis) {
            return this.records;
        }

        @Override
        public MulticastBusDiagnostics diagnostics() {
            return new MulticastBusDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, false);
        }

        @Override
        public String adapterType() {
            return "injected";
        }

        @Override
        public String endpointSummary() {
            return "injected";
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableInjectedRecordMulticastBus implements MulticastBus {
        private volatile List<RegistryRecord> records;

        private MutableInjectedRecordMulticastBus(final List<RegistryRecord> records) {
            this.records = records == null ? List.of() : List.copyOf(records);
        }

        @Override
        public void register(final MulticastRegistryClient client) {
        }

        @Override
        public void unregister(final MulticastRegistryClient client) {
        }

        @Override
        public List<RegistryRecord> list(
                final MulticastRegistryClient client,
                final MulticastMessageEnvelope envelope,
                final RegistryQuery query,
                final long deadlineMillis) {
            return this.records;
        }

        @Override
        public MulticastBusDiagnostics diagnostics() {
            return new MulticastBusDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, false);
        }

        @Override
        public String adapterType() {
            return "mutable-injected";
        }

        @Override
        public String endpointSummary() {
            return "mutable-injected";
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingMulticastBus implements MulticastBus {
        private final List<RegistryRecord> records;
        private final AtomicInteger observedLimit;
        private final AtomicInteger observedPeers;

        private CapturingMulticastBus(final List<RegistryRecord> records) {
            this.records = records == null ? List.of() : List.copyOf(records);
            this.observedLimit = new AtomicInteger();
            this.observedPeers = new AtomicInteger();
        }

        @Override
        public void register(final MulticastRegistryClient client) {
        }

        @Override
        public void unregister(final MulticastRegistryClient client) {
        }

        @Override
        public List<RegistryRecord> list(
                final MulticastRegistryClient client,
                final MulticastMessageEnvelope envelope,
                final RegistryQuery query,
                final long deadlineMillis) {
            this.observedLimit.set(query.limit());
            this.observedPeers.set(query.maxPeerCount());
            return this.records;
        }

        @Override
        public MulticastBusDiagnostics diagnostics() {
            return new MulticastBusDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, false);
        }

        @Override
        public String adapterType() {
            return "capturing";
        }

        @Override
        public String endpointSummary() {
            return "capturing";
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingMulticastBus implements MulticastBus {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger active;
        private final AtomicInteger maxActive;

        private BlockingMulticastBus(final int expectedEntries) {
            this.entered = new CountDownLatch(expectedEntries);
            this.release = new CountDownLatch(1);
            this.active = new AtomicInteger();
            this.maxActive = new AtomicInteger();
        }

        @Override
        public void register(final MulticastRegistryClient client) {
        }

        @Override
        public void unregister(final MulticastRegistryClient client) {
        }

        @Override
        public List<RegistryRecord> list(
                final MulticastRegistryClient client,
                final MulticastMessageEnvelope envelope,
                final RegistryQuery query,
                final long deadlineMillis) {
            int activeNow = this.active.incrementAndGet();
            this.maxActive.accumulateAndGet(activeNow, Math::max);
            this.entered.countDown();
            try {
                if (!this.release.await(10L, TimeUnit.SECONDS)) {
                    throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "test multicast release timed out");
                }
                return List.of();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "test multicast interrupted");
            } finally {
                this.active.decrementAndGet();
            }
        }

        @Override
        public MulticastBusDiagnostics diagnostics() {
            return new MulticastBusDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, false);
        }

        @Override
        public String adapterType() {
            return "blocking";
        }

        @Override
        public String endpointSummary() {
            return "blocking";
        }

        @Override
        public void close() {
            this.release();
        }

        private boolean awaitEntered() throws InterruptedException {
            return this.entered.await(10L, TimeUnit.SECONDS);
        }

        private int maxActive() {
            return this.maxActive.get();
        }

        private void release() {
            this.release.countDown();
        }
    }

    private static final class FixedRegistryClient implements RegistryClient {
        private final List<RegistryRecord> records;

        private FixedRegistryClient(final List<RegistryRecord> records) {
            this.records = records == null ? List.of() : List.copyOf(records);
        }

        @Override
        public void bind(final RegistryRecord record) {
            throw new UnsupportedOperationException("test registry is read-only");
        }

        @Override
        public void unbind(final ObjectRef ref) {
        }

        @Override
        public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            return Optional.empty();
        }

        @Override
        public List<RegistryRecord> list(final RegistryQuery query) {
            return this.records;
        }

        @Override
        public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        }

        @Override
        public List<RegistryPeerStatus> peers(final PeerQuery query) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableRegistryClient implements RegistryClient {
        private List<RegistryRecord> records = List.of();

        @Override
        public void bind(final RegistryRecord record) {
        }

        @Override
        public void unbind(final ObjectRef ref) {
        }

        @Override
        public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            return Optional.empty();
        }

        @Override
        public List<RegistryRecord> list(final RegistryQuery query) {
            return this.records;
        }

        @Override
        public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        }

        @Override
        public List<RegistryPeerStatus> peers(final PeerQuery query) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Test service API.
     */
    public interface Greeter {
        /**
         * Greets a caller.
         */
        @Idempotent
        String greet(String caller);
    }

    /**
     * Benchmark-capable test API.
     */
    public interface BenchGreeter extends Greeter {
        /**
         * Lightweight benchmark probe.
         */
        @Benchmark
        long benchmark();
    }

    private static final class TestGreeter implements Greeter {
        private final String name;

        private TestGreeter(final String name) {
            this.name = name;
        }

        @Override
        public String greet(final String caller) {
            return this.name + ":" + caller;
        }
    }

    private static final class BenchGreeterImpl implements BenchGreeter {
        private final String name;
        private final boolean slowBenchmark;
        private final boolean failBenchmark;

        private BenchGreeterImpl(final String name, final boolean slowBenchmark, final boolean failBenchmark) {
            this.name = name;
            this.slowBenchmark = slowBenchmark;
            this.failBenchmark = failBenchmark;
        }

        @Override
        public String greet(final String caller) {
            return this.name + ":" + caller;
        }

        @Override
        public long benchmark() {
            if (this.failBenchmark) {
                throw new IllegalStateException("benchmark failed");
            }
            long value = 0L;
            int iterations = this.slowBenchmark ? 2_000_000 : 1_000;
            for (int index = 0; index < iterations; index++) {
                value += index;
            }
            return value;
        }
    }

    private static final class CountingBenchGreeter implements BenchGreeter {
        private final String name;
        private final CountDownLatch release;
        private final AtomicInteger begins;

        private CountingBenchGreeter(final String name, final CountDownLatch release) {
            this.name = name;
            this.release = release;
            this.begins = new AtomicInteger();
        }

        @Override
        public String greet(final String caller) {
            return this.name + ":" + caller;
        }

        @Override
        public long benchmark() {
            this.begins.incrementAndGet();
            try {
                this.release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return 1L;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }

        private void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }
    }
}
