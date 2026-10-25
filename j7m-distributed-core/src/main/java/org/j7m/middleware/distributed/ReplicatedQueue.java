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

package org.j7m.middleware.distributed;

import java.io.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Durable queue and consumer-group facade over authenticated, ownership-fenced partition endpoints.
 * Every mutation has a caller-chosen stable request identity; transport uncertainty must be retried
 * with identical inputs. Delivery is at least once; external side effects require application
 * idempotency. Concurrent reuse of an identity with different bytes returns REQUEST_CONFLICT. FIFO is strict within each named queue: at most one current delivery is live.
 * Instances are thread-safe if their route resolver is thread-safe.
 */
public final class ReplicatedQueue {
    private final String scope;
    private final RouteResolver routes;
    /**
     * Resolves authoritative partition ownership; implementations must be thread-safe.
     */
    @FunctionalInterface
    public interface RouteResolver {
        /**
         * Supplies the latest committed ownership route after MOVED or FROZEN.
         * @param partition stable queue resource partition
         * @param deadline absolute deadline
         * @return authoritative endpoint and epoch
         * @throws IOException when the topology authority lacks quorum
         */
        Route resolve(String partition, Instant deadline) throws IOException;
    }
    /**
     * Immutable resolved route.
     * @param endpoint authenticated group endpoint
     * @param epoch committed ownership epoch
     */
    public record Route(PartitionEndpoint endpoint, long epoch) {
        /**
         * Validates the route. @param endpoint group endpoint @param epoch positive ownership epoch
         */
        public Route { Objects.requireNonNull(endpoint); if (epoch <= 0) throw new IllegalArgumentException("Invalid route epoch"); }
    }
    private final String partition;
    /**
     * Creates a scope-bound routed facade. The remote endpoint binds this same scope to credentials.
     * @param partition stable transferable resource partition
     * @param scope authenticated tenant scope
     * @param routes resolver backed by committed topology
     * @throws IllegalArgumentException for invalid identity
     */
    public ReplicatedQueue(String partition, String scope, RouteResolver routes) {
        new ResourceCommand("Q_REBALANCE", scope, "validation", List.of(partition));
        QueueStateMachine.validate(new ResourceCommand("Q_REBALANCE", scope, "validation", List.of(partition)));
        this.partition = partition; this.scope = scope; this.routes = Objects.requireNonNull(routes);
    }
    /**
     * Returns the configured stable logical resource identity, not an ownership attestation.
     * Actual effects still require the authorized route and server epoch check.
     * @return configured partition identity
     */
    public String partitionId() { return partition; }
    /**
     * Returns the configured tenant scope, never credentials or permission to change it.
     * @return configured authenticated scope expected by this facade
     */
    public String scopeId() { return scope; }
    /**
     * Sends a stable message identity once. The same message with different payload/attempt limit conflicts.
     * @param request stable request identity
     * @param queue FIFO queue name within partition
     * @param messageId durable message identity
     * @param payload opaque bytes, at most 32768
     * @param maxAttempts delivery attempts in [1,1000] before DLQ
     * @param deadline absolute deadline
     * @return SENT and stable sequence, MESSAGE_CONFLICT, or OVERLOADED
     * @throws IOException on quorum/transport failure or unknown completion
     */
    public String send(String request, String queue, String messageId, byte[] payload, int maxAttempts, Instant deadline) throws IOException {
        return execute("Q_SEND", request, List.of(queue, messageId, Base64.getEncoder().encodeToString(payload), Integer.toString(maxAttempts)), deadline);
    }
    /**
     * Atomically sends up to 256 messages to one FIFO queue, retaining each stable identity.
     * @param request stable batch request identity
     * @param queue queue name
     * @param messages insertion-ordered message identities and payloads
     * @param maxAttempts delivery limit in [1,1000]
     * @param deadline absolute deadline
     * @return SENT_BATCH and message count, MESSAGE_CONFLICT or OVERLOADED; failures mutate none
     * @throws IOException on unavailable quorum or unknown completion
     * @throws IllegalArgumentException for empty/oversized batches or invalid messages
     */
    public String sendBatch(String request, String queue, Map<String, byte[]> messages, int maxAttempts, Instant deadline) throws IOException {
        List<String> args = new ArrayList<>(List.of(queue, Integer.toString(maxAttempts)));
        for (var entry : messages.entrySet()) { args.add(entry.getKey()); args.add(Base64.getEncoder().encodeToString(entry.getValue())); }
        return execute("Q_SEND_BATCH", request, args, deadline);
    }

    /**
     * Joins or renews a member; a new member advances the durable group generation.
     * @param request stable request identity
     * @param group group identity
     * @param member member identity
     * @param leaseMillis membership lease in (0,86400000]
     * @param deadline absolute deadline
     * @return OK and generation, or OVERLOADED
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String join(String request, String group, String member, long leaseMillis, Instant deadline) throws IOException {
        return execute("Q_JOIN", request, List.of(group, member, Long.toString(leaseMillis)), deadline);
    }
    /**
     * Renews an existing member without renewing any message visibility lease.
     * @param request stable request identity
     * @param group group identity
     * @param member member identity
     * @param leaseMillis membership duration
     * @param deadline absolute deadline
     * @return OK and generation, or STALE_MEMBER
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String heartbeat(String request, String group, String member, long leaseMillis, Instant deadline) throws IOException {
        return execute("Q_HEARTBEAT", request, List.of(group, member, Long.toString(leaseMillis)), deadline);
    }
    /**
     * Explicitly fences previous assignments and deliveries with a new generation.
     * @param request stable request identity
     * @param group group identity
     * @param deadline absolute deadline
     * @return OK and new generation, or NO_GROUP
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String rebalance(String request, String group, Instant deadline) throws IOException {
        return execute("Q_REBALANCE", request, List.of(group), deadline);
    }
    /**
     * Claims the oldest unacknowledged eligible message for an assigned live member.
     * @param request stable request identity
     * @param queue queue identity
     * @param group group identity
     * @param member member identity
     * @param generation expected assignment generation
     * @param visibilityMillis delivery lease in (0,86400000]
     * @param deadline absolute deadline
     * @return MESSAGE with identity/base64 payload/sequence/receipt/attempt, EMPTY, or STALE_GENERATION
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String claim(String request, String queue, String group, String member, long generation,
            long visibilityMillis, Instant deadline) throws IOException {
        return claim(request, queue, group, member, generation, visibilityMillis, false, deadline);
    }
    /**
     * Acknowledges only the exact live delivery receipt and current member generation.
     * @param request stable request identity
     * @param queue queue identity
     * @param group group identity
     * @param member member identity
     * @param generation current assignment generation
     * @param messageId delivered identity
     * @param receipt delivered receipt token
     * @param deadline absolute deadline
     * @return ACKED, STALE_GENERATION or STALE_DELIVERY
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String ack(String request, String queue, String group, String member, long generation, String messageId,
            long receipt, Instant deadline) throws IOException {
        return delivery("Q_ACK", request, queue, group, member, generation, messageId, receipt, deadline);
    }
    /**
     * Rejects the exact delivery, making it eligible again or moving it to DLQ at maxAttempts.
     * @param request stable request identity
     * @param queue queue identity
     * @param group group identity
     * @param member member identity
     * @param generation assignment generation
     * @param messageId delivered identity
     * @param receipt delivered receipt token
     * @param deadline absolute deadline
     * @return NACKED, STALE_GENERATION or STALE_DELIVERY
     * @throws IOException on unhealthy clock or unavailable quorum
     */
    public String nack(String request, String queue, String group, String member, long generation, String messageId,
            long receipt, Instant deadline) throws IOException {
        return delivery("Q_NACK", request, queue, group, member, generation, messageId, receipt, deadline);
    }
    /**
     * Reads a member's durable generation and assigned queue names through quorum.
     * @param group group identity
     * @param member live member identity
     * @param deadline absolute deadline
     * @return generation followed by queue names; empty when absent
     * @throws IOException on unavailable quorum or stale routing
     */
    public List<String> assignment(String group, String member, Instant deadline) throws IOException {
        String response = query("Q_ASSIGN", List.of(group, member), deadline);
        return response.isEmpty() ? List.of() : List.of(response.split("\t"));
    }
    /**
     * Reads the first 256 ordered dead-letter identities, retained across ownership changes and restarts.
     * @param queue queue identity
     * @param deadline absolute deadline
     * @return dead-letter identities
     * @throws IOException on unavailable quorum
     */
    public List<String> deadLetters(String queue, Instant deadline) throws IOException {
        String response = query("Q_DLQ", List.of(queue), deadline);
        return response.isEmpty() ? List.of() : List.of(response.split("\t"));
    }
    /**
     * Claims with explicit EMPTY-result persistence; successful grants are always durable.
     * With false an EMPTY attempt does not reserve its identity and may succeed later.
     * @param request stable identity once a grant occurs
     * @param queue FIFO queue
     * @param group consumer group
     * @param member live member
     * @param generation current assignment generation
     * @param visibilityMillis visibility in1..86400000milliseconds
     * @param retainEmptyResult whether EMPTY must remain an immutable durable result
     * @param deadline original absolute deadline
     * @return MESSAGE, EMPTY or explicit fencing/admission status
     * @throws IOException for unavailable or unknown outcome
     */
    public String claim(String request, String queue, String group, String member, long generation,
            long visibilityMillis, boolean retainEmptyResult, Instant deadline) throws IOException {
        return execute("Q_CLAIM", request, List.of(queue, group, member, Long.toString(generation), Long.toString(visibilityMillis), retainEmptyResult ? "1" : "0"), deadline);
    }
    /**
     * Renews only the exact still-live delivery without resetting attempts or its finite renewal window.
     * @param request stable renewal operation identity
     * @param delivery exact delivered grant
     * @param visibilityMillis positive duration, at most24hours
     * @param deadline original deadline
     * @return RENEWED, RENEWAL_LIMIT or explicit stale status
     * @throws IOException for unavailable or unknown outcome
     */
    public String renewVisibility(String request, QueueDelivery delivery, long visibilityMillis, Instant deadline) throws IOException {
        return execute("Q_RENEW_VISIBILITY", request, List.of(delivery.queue(), delivery.group(), delivery.member(), Long.toString(delivery.generation()),
                delivery.messageId(), Long.toString(delivery.receipt()), Long.toString(visibilityMillis)), deadline);
    }
    /**
     * Creates one audit-linked child from an exact DLQ receipt; requires real server administrative permission.
     * @param request stable redrive operation identity
     * @param queue source and destination FIFO queue
     * @param messageId original dead-letter identity
     * @param receipt expected dead-letter receipt
     * @param newMessageId unique child identity
     * @param maximumAttempts child delivery limit1..1000
     * @param deadline original deadline
     * @return REDRIVEN and sequence or explicit conflict/stale/limit status
     * @throws IOException on unavailable or unknown completion
     * @throws SecurityException for a nonadministrative remote binding
     */
    public String redrive(String request, String queue, String messageId, long receipt, String newMessageId, int maximumAttempts, Instant deadline) throws IOException {
        return execute("Q_REDRIVE", request, List.of(queue, messageId, Long.toString(receipt), newMessageId, Integer.toString(maximumAttempts), "host"), deadline);
    }
    /**
     * Reads one bounded DLQ page within an initial high-water; zero requests the initial high-water.
     * @param queue scope-bound queue identity
     * @param after last returned sequence, initially0
     * @param highWater first-read upper bound, initially0
     * @param maximum page entry limit1..256, also bounded by encoded bytes
     * @param deadline original deadline shared by all pages
     * @return immutable full-content page
     * @throws IOException for unavailable or corrupt response
     */
    public DeadLetterPage deadLetterPage(String queue, long after, long highWater, int maximum, Instant deadline) throws IOException {
        return fetchDeadLetterPage(queue, after, highWater, maximum, "", deadline);
    }
    /**
     * Reads the next bounded page using its server-authenticated scope/partition-bound continuation.
     * @param queue original queue identity
     * @param previous previous page, or null for the first page
     * @param maximum requested entry count1..256
     * @param deadline original deadline shared across the traversal
     * @return next immutable page; an empty page terminates traversal
     * @throws IOException on unavailable or malformed response
     */
    public DeadLetterPage deadLetterPage(String queue, DeadLetterPage previous, int maximum, Instant deadline) throws IOException {
        return fetchDeadLetterPage(queue, previous == null ? 0 : previous.through(), previous == null ? 0 : previous.highWater(), maximum,
                previous == null ? "" : previous.continuation(), deadline);
    }
    private DeadLetterPage fetchDeadLetterPage(String queue, long after, long highWater, int maximum, String continuation, Instant deadline) throws IOException {
        String response = query("Q_DLQ_PAGE", List.of(queue, Long.toString(after), Long.toString(highWater), Integer.toString(maximum), partition, continuation), deadline);
        String[] fields = response.split("\\t");
        try {
            if (fields.length < 2) throw new IOException("Incomplete DLQ page");
            List<DeadLetter> entries = new ArrayList<>();
            boolean sealed = fields.length > 2 && fields[fields.length - 1].startsWith("!");
            for (int i = 2; i < fields.length - (sealed ? 1 : 0); i++) {
                var in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(fields[i])));
                entries.add(new DeadLetter(in.readUTF(), Base64.getDecoder().decode(in.readUTF()), in.readLong(), in.readLong(), in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt()));
                if (in.available() != 0) throw new IOException("Trailing DLQ entry");
            }
            return new DeadLetterPage(entries, Long.parseLong(fields[1]), Long.parseLong(fields[0]), sealed ? fields[fields.length - 1].substring(1) : "");
        } catch (IllegalArgumentException invalid) { throw new IOException("Corrupt DLQ page", invalid); }
    }
    /**
     * Lazily traverses all initial DLQ entries with one page in memory and one original deadline.
     * Concurrent arrivals above the first high-water cannot extend this stream indefinitely.
     * @param queue scope-bound queue
     * @param deadline original absolute deadline, never refreshed perpage
     * @return sequential stream whose I/O failures are UncheckedIOException
     */
    public Stream<DeadLetter> deadLetterStream(String queue, Instant deadline) {
        Iterator<DeadLetter> iterator = new Iterator<>() {
            Iterator<DeadLetter> current = Collections.emptyIterator(); long after, high; DeadLetterPage previous; boolean finished;
            @Override public boolean hasNext() {
                if (current.hasNext()) return true;
                if (finished) return false;
                try {
                    DeadLetterPage page = deadLetterPage(queue, previous, 256, deadline); previous = page; high = page.highWater();
                    if (page.entries().isEmpty()) { finished = true; return false; }
                    if (page.through() <= after) throw new IOException("Nonadvancing DLQ cursor");
                    after = page.through(); current = page.entries().iterator(); if (after == high) finished = true;
                    return current.hasNext();
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            @Override public DeadLetter next() { if (!hasNext()) throw new NoSuchElementException(); return current.next(); }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Subscribes to complete durable grants with bounded demand and no client-side claim polling.
     * @param queue named queue
     * @param group group identity
     * @param member stable identity retained on reconnect
     * @param visibilityMillis positive native visibility duration
     * @param deadline original connection deadline
     * @return owned authenticated session
     * @throws IOException for unsupported endpoint, unavailable ownership or authentication
     */
    public QueueSession subscribe(String queue, String group, String member, long visibilityMillis, Instant deadline) throws IOException {
        Route route = routes.resolve(partition, deadline);
        return route.endpoint().subscribeQueue(route.epoch(), queue, group, member, visibilityMillis, false, deadline);
    }
    /**
     * Observes complete retained poison records; callback success never ACKs or redrives them.
     * @param queue source queue
     * @param observer stable logical observer identity
     * @param deadline original connection deadline
     * @return owned observational credit session
     * @throws IOException for unsupported endpoint, unavailable ownership or authentication
     */
    public QueueSession observeDeadLetters(String queue, String observer, Instant deadline) throws IOException {
        Route route = routes.resolve(partition, deadline);
        return route.endpoint().subscribeQueue(route.epoch(), queue, "dead-letter-observers", observer, 60000, true, deadline);
    }
    private String delivery(String op, String request, String queue, String group, String member, long generation,
            String message, long receipt, Instant deadline) throws IOException {
        return execute(op, request, List.of(queue, group, member, Long.toString(generation), message, Long.toString(receipt)), deadline);
    }
    private String execute(String op, String request, List<String> args, Instant deadline) throws IOException {
        ResourceCommand command = new ResourceCommand(op, scope, request, args); QueueStateMachine.validate(command);
        byte[] bytes = command.encode(), query = new ResourceCommand("Q_RESULT", scope, "query", List.of(request, QueueStateMachine.fingerprint(bytes))).encode();
        for (int attempt = 0; attempt < 8 && Instant.now().isBefore(deadline); attempt++) {
            Route route = routes.resolve(partition, deadline);
            PartitionReply reply = route.endpoint.execute(route.epoch, request, bytes, query, deadline);
            if (reply.status().equals("OK")) {
                String value = new String(reply.payload(), StandardCharsets.UTF_8);
                return value;
            }
            if (reply.status().equals("CONFLICT")) return "REQUEST_CONFLICT";
            if (!Set.of("MOVED", "FROZEN").contains(reply.status())) throw new IOException("Queue outcome " + reply.status());
        }
        throw new IOException("Queue topology unavailable before deadline");
    }
    private String query(String operation, List<String> args, Instant deadline) throws IOException {
        byte[] query = new ResourceCommand(operation, scope, "query", args).encode();
        for (int attempt = 0; attempt < 8 && Instant.now().isBefore(deadline); attempt++) {
            Route route = routes.resolve(partition, deadline); PartitionReply reply = route.endpoint.query(route.epoch, query, deadline);
            if (reply.status().equals("OK")) return new String(reply.payload(), StandardCharsets.UTF_8);
            if (!Set.of("MOVED", "FROZEN").contains(reply.status())) throw new IOException("Queue query " + reply.status());
        }
        throw new IOException("Queue topology unavailable before deadline");
    }
}
