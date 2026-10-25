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
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic, tenant-scoped FIFO queues and consumer generations. All operations run in a
 * committed log callback; time is supplied only by the hosting measured-clock admission layer.
 * One live delivery per queue preserves partition order. Acknowledged identities remain tombstones
 * until an explicit future retention protocol; capacity exhaustion rejects new work explicitly.
 * Snapshots include messages, membership, generations, delivery leases and request fingerprints.
 * Instances serialize their callbacks and queries and perform no external effects.
 */
public final class QueueStateMachine implements TransferableResource {
    /**
     * Maximum retained messages and requests; no silent identity eviction occurs.
     */
    public static final int MAX_ENTRIES = 100_000;
    private static final int FORMAT = 3;
    private static final long MAX_IMAGE_BYTES = 33_554_432;
    private final Map<String, StoredResult> transientEmpty = new LinkedHashMap<>();
    private static final int MAX_PAYLOAD = 32_768;
    private final Map<String, QueueData> queues = new TreeMap<>();
    private final Map<String, Group> groups = new TreeMap<>();
    private final Map<String, StoredResult> results = new TreeMap<>();
    private String partition;
    private long ownershipEpoch;
    private long time;
    private long index;
    private long nextSequence;
    private long requestBytes;
    private long liveBytes;
    private long payloadBytes;
    private int totalMessages;
    private EventJournal journal = new EventJournal(10_000, 16_777_216);

    /**
     * Creates the default partition named queue.
     */
    public QueueStateMachine() { this("queue"); }

    /**
     * Creates an empty transferable queue partition.
     * @param partition globally unique topology partition identity
     * @throws IllegalArgumentException for invalid identity
     */
    public QueueStateMachine(String partition) { identifier(partition); this.partition = partition; liveBytes = 54L + utfSize(partition); }

    /**
     * Applies an ownership epoch issued by the hosting committed topology wrapper.
     * @param epoch nonnegative monotonically increasing ownership epoch
     * @throws IllegalArgumentException on epoch regression
     */
    public synchronized void ownershipEpoch(long epoch) {
        if (epoch < ownershipEpoch) throw new IllegalArgumentException("Queue ownership epoch regression");
        ownershipEpoch = epoch;
    }

    /**
     * Validates a bounded queue command before log admission.
     * @param command immutable queue command
     * @throws IllegalArgumentException for unsupported operations or malformed arguments
     */
    public static void validate(ResourceCommand command) {
        Objects.requireNonNull(command);
        identifier(command.scope()); identifier(command.requestId());
        List<String> a = command.arguments();
        if (command.operation().equals("Q_SEND_BATCH")) {
            if (a.size() < 4 || a.size() > 514 || a.size() % 2 != 0) throw new IllegalArgumentException("Queue batch bounds");
            identifier(a.get(0)); number(a.get(1), 1, 1000); Set<String> ids = new HashSet<>();
            for (int i = 2; i < a.size(); i += 2) { identifier(a.get(i)); if (!ids.add(a.get(i)) || decode(a.get(i + 1)).length > MAX_PAYLOAD) throw new IllegalArgumentException("Queue batch identity/payload"); }
            if (command.encode().length > PartitionStateMachine.MAX_COMMAND) throw new IllegalArgumentException("Queue batch bytes");
            return;
        }
        int count = switch (command.operation()) {
            case "Q_TIME" -> 1;
            case "Q_SEND" -> 4;
            case "Q_REDRIVE" -> 6;
            case "Q_RENEW_VISIBILITY" -> 7;
            case "Q_JOIN", "Q_HEARTBEAT" -> 3;
            case "Q_REBALANCE" -> 1;
            case "Q_CLAIM" -> 6;
            case "Q_ACK", "Q_NACK" -> 6;
            default -> throw new IllegalArgumentException("Unsupported queue operation");
        };
        if (a.size() != count) throw new IllegalArgumentException("Queue argument count");
        if (command.operation().equals("Q_TIME")) { number(a.get(0), 0, Long.MAX_VALUE); return; }
        for (int i = 0; i < a.size(); i++) {
            if (command.operation().equals("Q_SEND") && i == 2) continue;
            identifier(a.get(i));
        }
        switch (command.operation()) {
            case "Q_SEND" -> { if (decode(a.get(2)).length > MAX_PAYLOAD) throw new IllegalArgumentException("Payload limit"); number(a.get(3), 1, 1000); }
            case "Q_JOIN", "Q_HEARTBEAT" -> number(a.get(2), 1, 86_400_000);
            case "Q_CLAIM" -> { number(a.get(3), 1, Long.MAX_VALUE); number(a.get(4), 1, 86_400_000); number(a.get(5), 0, 1); }
            case "Q_REDRIVE" -> { number(a.get(2), 1, Long.MAX_VALUE); number(a.get(4), 1, 1000); }
            case "Q_RENEW_VISIBILITY" -> { number(a.get(3), 1, Long.MAX_VALUE); number(a.get(5), 1, Long.MAX_VALUE); number(a.get(6), 1, 86_400_000); }
            case "Q_ACK", "Q_NACK" -> { number(a.get(3), 1, Long.MAX_VALUE); number(a.get(5), 1, Long.MAX_VALUE); }
            default -> { }
        }
        command.encode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean identityConflict(byte[] bytes) {
        ResourceCommand command = ResourceCommand.decode(bytes);
        if (command.operation().equals("Q_TIME")) return false;
        StoredResult previous = results.get(key(command.scope(), command.requestId()));
        return previous != null && !Arrays.equals(previous.command, bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized TransferableResource copyForTransfer() {
        QueueStateMachine copy = new QueueStateMachine(partition); copy.restore(snapshot()); return copy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateReplayCommand(byte[] bytes) { validate(ResourceCommand.decode(bytes)); }

    /**
     * {@inheritDoc}
     */
    @Override
    public String requestIdentity(byte[] bytes) { return ResourceCommand.decode(bytes).requestId(); }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void validate(byte[] bytes, String authenticatedScope, boolean administrative) {
        ResourceCommand command = ResourceCommand.decode(bytes); validate(command);
        if (!command.scope().equals(authenticatedScope)) throw new SecurityException("Queue scope denied");
        if (Set.of("Q_TIME", "Q_REDRIVE").contains(command.operation()) && !administrative) throw new SecurityException("Queue clock control denied");
        StoredResult previous = results.get(key(command.scope(), command.requestId()));
        if (previous != null && !Arrays.equals(previous.command, bytes)) throw new IllegalArgumentException("Request identity reused");
        if (!command.operation().equals("Q_TIME") && previous == null
                && (results.size() >= MAX_ENTRIES || !fits(command, bytes))) throw new IllegalStateException("OVERLOADED: queue request retention capacity");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateQuery(byte[] bytes, String authenticatedScope) {
        ResourceCommand c = ResourceCommand.decode(bytes);
        if (!c.scope().equals(authenticatedScope)) throw new SecurityException("Queue query scope denied");
        int count = switch (c.operation()) {
            case "Q_RESULT" -> c.arguments().size() == 2 ? 2 : 1;
            case "Q_DLQ" -> 1;
            case "Q_DLQ_PAGE" -> c.arguments().size() == 6 ? 6 : 4;
            case "Q_DLQ_NEXT" -> 2;
            case "Q_RECEIPT" -> 3;
            case "Q_ASSIGN" -> 2;
            case "Q_EVENTS" -> 0;
            default -> throw new IllegalArgumentException("Queue query denied");
        };
        if (c.arguments().size() != count) throw new IllegalArgumentException("Queue query arguments");
        for (int i = 0; i < c.arguments().size(); i++) {
            String argument = c.arguments().get(i);
            if (c.operation().equals("Q_DLQ_PAGE") && i == 5) { if (argument.length() > 256) throw new IllegalArgumentException("Queue cursor bounds"); }
            else identifier(argument);
        }
        if (c.operation().equals("Q_DLQ_PAGE") && c.arguments().size() == 6 && !c.arguments().get(4).equals(partition)) throw new IllegalArgumentException("Foreign queue cursor partition");
        if (c.operation().equals("Q_DLQ_PAGE")) { number(c.arguments().get(1), 0, Long.MAX_VALUE); number(c.arguments().get(2), 0, Long.MAX_VALUE); number(c.arguments().get(3), 1, 256); }
        if (c.operation().equals("Q_RESULT") && c.arguments().size() == 2 && !c.arguments().get(1).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid command fingerprint");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean timeControl(byte[] bytes) { return ResourceCommand.decode(bytes).operation().equals("Q_TIME"); }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean timeSensitive(byte[] bytes) {
        return !Set.of("Q_SEND", "Q_SEND_BATCH", "Q_TIME").contains(ResourceCommand.decode(bytes).operation());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] timeCommand(long milliseconds) {
        return new ResourceCommand("Q_TIME", "queue-clock", "maintenance", List.of(Long.toString(milliseconds))).encode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void apply(long committedIndex, byte[] bytes) {
        ResourceCommand c = ResourceCommand.decode(bytes); validate(c);
        if (c.operation().equals("Q_TIME")) {
            time = Math.max(time, Long.parseLong(c.arguments().getFirst()));
            expireMembers(); index = committedIndex; return;
        }
        String request = key(c.scope(), c.requestId());
        StoredResult previous = results.get(request);
        if (previous != null) {
            index = committedIndex; return;
        }
        if (results.size() >= MAX_ENTRIES || !fits(c, bytes)) {
            rememberTransient(request, bytes, new Reply("OVERLOADED", committedIndex)); index = committedIndex; return;
        }
        String value = execute(c);
        if (value.equals("EMPTY") && c.operation().equals("Q_CLAIM") && c.arguments().get(5).equals("0")) {
            rememberTransient(request, bytes, new Reply(value, committedIndex));
            index = committedIndex; return;
        }
        transientEmpty.remove(request);
        if (!c.operation().equals("Q_SEND_BATCH")) journal.append(partition, ownershipEpoch, c.scope(), c.operation(), bytes);
        String compact = compact(c, value);
        results.put(request, new StoredResult(bytes.clone(), new Reply(compact, committedIndex)));
        requestBytes += retainedSize(request, bytes, compact);
        index = committedIndex;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void appliedThrough(long committedIndex) { index = committedIndex; }

    /**
     * Returns a retained committed result after the hosting read barrier.
     * @param scope authenticated scope
     * @param request original idempotency identity
     * @param barrier applied quorum index
     * @return immutable result, or null when never submitted/capacity rejected
     * @throws IllegalStateException if the barrier has not been applied
     */
    public synchronized Reply result(String scope, String request, long barrier) {
        barrier(barrier); StoredResult r = results.get(key(scope, request)); return r == null ? null : new Reply(materialize(r), r.reply.index);
    }

    /**
     * Returns the first 256 stable ordered dead-letter identities of a queue.
     * @param scope authenticated scope
     * @param queue queue identity
     * @param barrier applied quorum index
     * @return immutable dead-letter message identities
     * @throws IllegalStateException if the barrier is not applied
     */
    public synchronized List<String> deadLetters(String scope, String queue, long barrier) {
        barrier(barrier); QueueData q = queues.get(key(scope, queue));
        return q == null ? List.of() : q.messages.values().stream().filter(m -> m.state.equals("DLQ")).limit(256).map(m -> m.id).toList();
    }

    /**
     * Returns current group generation and assigned queues for a live member.
     * @param scope authenticated scope
     * @param group group identity
     * @param member member identity
     * @param barrier applied quorum index
     * @return generation followed by zero or more queue names
     * @throws IllegalStateException if the barrier is not applied
     */
    public synchronized List<String> assignment(String scope, String group, String member, long barrier) {
        barrier(barrier); Group g = groups.get(key(scope, group));
        if (g == null || !g.members.containsKey(member)) return List.of();
        List<String> result = new ArrayList<>(); result.add(Long.toString(g.generation));
        for (String q : queues.keySet()) if (q.startsWith(scope + "\0") && assigned(scope, group, q.substring(scope.length() + 1), member))
            result.add(q.substring(scope.length() + 1));
        return List.copyOf(result);
    }

    /**
     * Immutable operation response; payload is a versioned tab-separated status format.
     * @param value response payload
     * @param index original committed index
     */
    public record Reply(String value, long index) { }

    /**
     * Measures the exact current queue image without serializing payloads or changing state.
     * The32MiB resource cap leaves room for simultaneous pinned/upload images and suffix within
     * the qualified128MiB partition-node snapshot. All pre-admission predictions are conservative.
     * @return exact current resource snapshot byte length
     */
    public synchronized long retainedSnapshotBytes() { return liveBytes + requestBytes + journal.encodedSize(); }
    private static int utfSize(String value) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); result += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3; }
        return result;
    }
    private static long messageBytes(Message message) {
        return 68L + utfSize(message.id) + utfSize(message.payload) + utfSize(message.state) + utfSize(message.group) + utfSize(message.member)
                + utfSize(message.parent) + utfSize(message.child) + utfSize(message.actor);
    }
    private static long retainedSize(String key, byte[] command, String value) { return 16L + utfSize(key) + command.length + utfSize(value); }
    private void rememberTransient(String request, byte[] bytes, Reply reply) {
        if (transientEmpty.size() >= 256) transientEmpty.remove(transientEmpty.keySet().iterator().next());
        transientEmpty.put(request, new StoredResult(bytes.clone(), reply));
    }
    private static String compact(ResourceCommand command, String value) {
        if (!command.operation().equals("Q_CLAIM") || !value.startsWith("MESSAGE\t")) return value;
        String[] fields = value.split("\t", -1); fields[2] = ""; return String.join("\t", fields);
    }
    private String materialize(StoredResult saved) {
        if (!saved.reply.value.startsWith("MESSAGE\t")) return saved.reply.value;
        ResourceCommand command = ResourceCommand.decode(saved.command);
        if (!command.operation().equals("Q_CLAIM")) throw new IllegalStateException("Invalid retained reply");
        String[] fields = saved.reply.value.split("\t", -1);
        QueueData queue = queues.get(key(command.scope(), command.arguments().getFirst()));
        Message message = queue == null || fields.length != 6 ? null : queue.messages.get(fields[1]);
        if (message == null || !fields[2].isEmpty() || Long.parseLong(fields[3]) != message.sequence
                || number(fields[4], 1, message.receipt) != number(fields[5], 1, message.attempts)) throw new IllegalStateException("Invalid retained receipt");
        fields[2] = message.payload; return String.join("\t", fields);
    }
    private boolean fits(ResourceCommand command, byte[] encoded) {
        long reply = command.operation().equals("Q_CLAIM") ? 1664 : 128;
        long retained = 16L + utfSize(key(command.scope(), command.requestId())) + encoded.length + reply;
        long events = 0;
        if (command.operation().equals("Q_SEND_BATCH")) {
            var a = command.arguments();
            for (int i = 2; i < a.size(); i += 2) {
                byte[] inner = new ResourceCommand("Q_SEND", command.scope(), command.requestId(), List.of(a.get(0), a.get(i), a.get(i + 1), a.get(1))).encode();
                events += eventGrowth(command.scope(), "Q_SEND", inner.length);
            }
        } else events = eventGrowth(command.scope(), command.operation(), encoded.length);
        return requestBytes + retained <= MAX_IMAGE_BYTES && retainedSnapshotBytes() + retained + liveGrowth(command) + events <= MAX_IMAGE_BYTES;
    }
    private long eventGrowth(String scope, String operation, int payload) {
        return 54L + 3L * utfSize(partition) + utfSize(scope) + utfSize(operation) + payload;
    }
    private long liveGrowth(ResourceCommand command) {
        var a = command.arguments(); String scope = command.scope(), op = command.operation();
        if (op.equals("Q_JOIN")) {
            Group group = groups.get(key(scope, a.get(0)));
            return (group == null ? 14L + utfSize(key(scope, a.get(0))) : 0)
                    + (group == null || !group.members.containsKey(a.get(1)) ? 10L + utfSize(a.get(1)) : 0);
        }
        if (Set.of("Q_HEARTBEAT", "Q_REBALANCE", "Q_ACK", "Q_NACK", "Q_RENEW_VISIBILITY").contains(op)) return 0;
        QueueData queue = queues.get(key(scope, a.getFirst())); long growth = 0;
        if (op.equals("Q_SEND") || op.equals("Q_SEND_BATCH")) {
            if (queue == null) growth += 6L + utfSize(key(scope, a.getFirst()));
            if (op.equals("Q_SEND")) { if (queue == null || !queue.messages.containsKey(a.get(1))) growth += 73L + utfSize(a.get(1)) + utfSize(a.get(2)); }
            else for (int i = 2; i < a.size(); i += 2) if (queue == null || !queue.messages.containsKey(a.get(i))) growth += 73L + utfSize(a.get(i)) + utfSize(a.get(i + 1));
        } else if (op.equals("Q_CLAIM")) growth += 3L + utfSize(a.get(1)) + utfSize(a.get(2));
        else if (op.equals("Q_REDRIVE") && queue != null && queue.messages.containsKey(a.get(1))) {
            Message parent = queue.messages.get(a.get(1)); growth += 73L + 2L * utfSize(a.get(3)) + utfSize(parent.payload) + utfSize(parent.id) + utfSize(a.get(5));
        }
        return growth;
    }

    private String execute(ResourceCommand c) {
        List<String> a = c.arguments(); String scope = c.scope(); String op = c.operation();
        if (op.equals("Q_JOIN") || op.equals("Q_HEARTBEAT")) {
            if (op.equals("Q_HEARTBEAT") && !groups.containsKey(key(scope, a.get(0)))) return "STALE_MEMBER";
            if (!groups.containsKey(key(scope, a.get(0))) && groups.size() >= 4096) return "OVERLOADED";
            String groupKey = key(scope, a.get(0));
            Group g = groups.get(groupKey);
            if (g == null) { g = new Group(); groups.put(groupKey, g); liveBytes += 14L + utfSize(groupKey); }
            if (!g.members.containsKey(a.get(1)) && g.members.size() >= 1024) return "OVERLOADED";
            boolean present = g.members.containsKey(a.get(1));
            if (op.equals("Q_HEARTBEAT") && !present) return "STALE_MEMBER";
            if (!present) { g.generation++; liveBytes += 10L + utfSize(a.get(1)); }
            g.members.put(a.get(1), plus(time, Long.parseLong(a.get(2))));
            return "OK\t" + g.generation;
        }
        if (op.equals("Q_REBALANCE")) {
            Group g = groups.get(key(scope, a.get(0)));
            if (g == null) return "NO_GROUP";
            return "OK\t" + ++g.generation;
        }
        String queue = a.get(0); QueueData q = queues.get(key(scope, queue));
        if (op.equals("Q_SEND_BATCH")) {
            int additions = 0; long additionalBytes = 0;
            for (int i = 2; i < a.size(); i += 2) {
                Message existing = q == null ? null : q.messages.get(a.get(i));
                if (existing == null) { additions++; additionalBytes += a.get(i + 1).length(); }
                else if (!existing.payload.equals(a.get(i + 1)) || existing.maxAttempts != Integer.parseInt(a.get(1))) return "MESSAGE_CONFLICT";
            }
            if (totalMessages + additions > MAX_ENTRIES || payloadBytes + additionalBytes > 33_554_432
                    || q == null && queues.size() >= 1024) return "OVERLOADED";
            for (int i = 2; i < a.size(); i += 2) {
                ResourceCommand sent = new ResourceCommand("Q_SEND", scope, c.requestId(), List.of(queue, a.get(i), a.get(i + 1), a.get(1)));
                execute(sent); journal.append(partition, ownershipEpoch, scope, "Q_SEND", sent.encode());
            }
            return "SENT_BATCH\t" + (a.size() - 2) / 2;
        }
        if (op.equals("Q_SEND")) {
            if (q == null) {
                if (queues.size() >= 1024) return "OVERLOADED";
                q = new QueueData(); queues.put(key(scope, queue), q); liveBytes += 6L + utfSize(key(scope, queue));
            }
            Message old = q.messages.get(a.get(1));
            if (old != null) return old.payload.equals(a.get(2)) && old.maxAttempts == Integer.parseInt(a.get(3)) ? "SENT\t" + old.sequence : "MESSAGE_CONFLICT";
            if (totalMessages >= MAX_ENTRIES || payloadBytes + a.get(2).length() > 33_554_432) return "OVERLOADED";
            Message message = new Message(a.get(1), a.get(2), ++nextSequence, Integer.parseInt(a.get(3)));
            q.messages.put(message.id, message); q.ordered.put(message.sequence, message); liveBytes += messageBytes(message); totalMessages++; payloadBytes += message.payload.length(); return "SENT\t" + message.sequence;
        }
        if (op.equals("Q_REDRIVE")) {
            Message parent = q == null ? null : q.messages.get(a.get(1));
            if (parent == null || !parent.state.equals("DLQ") || parent.receipt != Long.parseLong(a.get(2))) return "STALE_DELIVERY";
            if (!parent.child.isEmpty()) return parent.child.equals(a.get(3)) ? "REDRIVEN\t" + q.messages.get(parent.child).sequence : "ALREADY_REDRIVEN";
            if (parent.depth >= 16) return "LINEAGE_LIMIT";
            if (q.messages.containsKey(a.get(3))) return "MESSAGE_CONFLICT";
            if (totalMessages >= MAX_ENTRIES || payloadBytes + parent.payload.length() > 33_554_432) return "OVERLOADED";
            Message child = new Message(a.get(3), parent.payload, ++nextSequence, Integer.parseInt(a.get(4)));
            child.parent = parent.id; child.depth = parent.depth + 1; child.actor = a.get(5); liveBytes -= messageBytes(parent); parent.child = child.id; liveBytes += messageBytes(parent);
            q.messages.put(child.id, child); q.ordered.put(child.sequence, child); liveBytes += messageBytes(child); totalMessages++; payloadBytes += child.payload.length();
            return "REDRIVEN\t" + child.sequence;
        }
        if (q == null) return "EMPTY";
        String group = a.get(1), member = a.get(2); long generation = Long.parseLong(a.get(3));
        Group g = groups.get(key(scope, group));
        if (g == null || g.generation != generation || !g.members.containsKey(member) || !assigned(scope, group, queue, member)) return "STALE_GENERATION";
        if (op.equals("Q_CLAIM")) {
            for (Message m : q.messages.values()) {
                if (m.state.equals("ACK") || m.state.equals("DLQ")) continue;
                Group deliveryGroup = groups.get(key(scope, m.group.isEmpty() ? group : m.group));
                if (m.state.equals("INFLIGHT") && m.until > time && deliveryGroup != null
                        && m.generation == deliveryGroup.generation && deliveryGroup.members.containsKey(m.member)) return "EMPTY";
                if (m.attempts >= m.maxAttempts) { liveBytes -= messageBytes(m); m.state = "DLQ"; liveBytes += messageBytes(m); continue; }
                liveBytes -= messageBytes(m); m.attempts++; m.state = "INFLIGHT"; m.group = group; m.member = member;
                m.generation = generation; m.receipt++; m.claimedAt = time; m.until = plus(time, Long.parseLong(a.get(4))); liveBytes += messageBytes(m);
                return "MESSAGE\t" + m.id + "\t" + m.payload + "\t" + m.sequence + "\t" + m.receipt + "\t" + m.attempts;
            }
            return "EMPTY";
        }
        Message m = q.messages.get(a.get(4));
        if (m == null || !m.state.equals("INFLIGHT") || m.until <= time || !m.group.equals(group)
                || !m.member.equals(member) || m.generation != generation || m.receipt != Long.parseLong(a.get(5))) return "STALE_DELIVERY";
        if (op.equals("Q_RENEW_VISIBILITY")) {
            long expiry = plus(time, Long.parseLong(a.get(6)));
            if (expiry > plus(m.claimedAt, 86_400_000)) return "RENEWAL_LIMIT";
            m.until = Math.max(m.until, expiry); return "RENEWED\t" + m.until;
        }
        liveBytes -= messageBytes(m);
        m.state = op.equals("Q_ACK") ? "ACK" : m.attempts >= m.maxAttempts ? "DLQ" : "READY";
        liveBytes += messageBytes(m);
        return op.equals("Q_ACK") ? "ACKED" : "NACKED";
    }

    private boolean assigned(String scope, String group, String queue, String member) {
        Group g = groups.get(key(scope, group));
        if (g == null || g.members.isEmpty()) return false;
        List<String> members = new ArrayList<>(g.members.keySet());
        return members.get(Math.floorMod(queue.hashCode(), members.size())).equals(member);
    }
    private void expireMembers() {
        for (Group g : groups.values()) {
            boolean removed = false;
            for (var iterator = g.members.entrySet().iterator(); iterator.hasNext();) {
                var member = iterator.next(); if (member.getValue() <= time) { liveBytes -= 10L + utfSize(member.getKey()); iterator.remove(); removed = true; }
            }
            if (removed) g.generation++;
        }
    }
    private int messageCount() { return totalMessages; }
    private void barrier(long barrier) { if (barrier > index) throw new IllegalStateException("Read barrier not applied"); }
    private static String key(String scope, String id) { identifier(scope); identifier(id); return scope + '\0' + id; }
    private static void identifier(String value) { if (value == null || value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0 || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid queue identifier"); }
    private static long number(String value, long min, long max) { try { long n = Long.parseLong(value); if (n < min || n > max) throw new IllegalArgumentException("Queue numeric bounds"); return n; } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid queue number", e); } }
    private static byte[] decode(String value) { try { return Base64.getDecoder().decode(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid queue payload", e); } }
    private static long plus(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static final class Group { long generation; final Map<String, Long> members = new TreeMap<>(); }
    private static final class QueueData { final Map<String, Message> messages = new LinkedHashMap<>(); final NavigableMap<Long, Message> ordered = new TreeMap<>(); }
    private static final class Message {
        final String id, payload; final long sequence; final int maxAttempts;
        String state = "READY", group = "", member = "", parent = "", child = "", actor = ""; long generation, receipt, until, claimedAt; int attempts, depth;
        Message(String id, String payload, long sequence, int maxAttempts) { this.id = id; this.payload = payload; this.sequence = sequence; this.maxAttempts = maxAttempts; }
    }
    synchronized boolean pending(String scope, String queue, long measuredTime) {
        QueueData q = queues.get(key(scope, queue));
        if (q == null) return false;
        for (Message m : q.messages.values()) {
            if (m.state.equals("ACK") || m.state.equals("DLQ")) continue;
            Group g = groups.get(key(scope, m.group.isEmpty() ? "unused" : m.group));
            return !m.state.equals("INFLIGHT") || m.until <= measuredTime || g == null || g.generation != m.generation || g.members.getOrDefault(m.member, 0L) <= measuredTime;
        }
        return false;
    }
    synchronized boolean deadLetterAfter(String scope, String queue, long after) {
        QueueData q = queues.get(key(scope, queue));
        return q != null && q.ordered.tailMap(after, false).values().stream().anyMatch(m -> m.state.equals("DLQ"));
    }
    synchronized String resumeToken(String scope, String queue, String group, String member, long measuredTime) {
        QueueData q = queues.get(key(scope, queue)); Group g = groups.get(key(scope, group));
        if (q != null && g != null && g.members.getOrDefault(member, 0L) > measuredTime) for (Message m : q.messages.values())
            if (m.state.equals("INFLIGHT") && m.until > measuredTime && m.group.equals(group) && m.member.equals(member) && m.generation == g.generation)
                return m.id + '\0' + m.generation + ':' + m.receipt;
        return "";
    }
    synchronized long nextExpiry(String scope, String queue, long after) {
        QueueData q = queues.get(key(scope, queue)); long earliest = Long.MAX_VALUE;
        if (q != null) for (Message m : q.messages.values()) if (m.state.equals("INFLIGHT")) {
            if (m.until > after) earliest = Math.min(earliest, m.until); Group g = groups.get(key(scope, m.group));
            if (g != null && g.members.getOrDefault(m.member, 0L) > after) earliest = Math.min(earliest, g.members.get(m.member));
        }
        for (var entry : groups.entrySet()) if (entry.getKey().startsWith(scope + "\0")) for (long expiry : entry.getValue().members.values()) if (expiry > after) earliest = Math.min(earliest, expiry);
        return earliest;
    }
    private record StoredResult(byte[] command, Reply reply) { }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] snapshot() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(FORMAT); out.writeUTF(partition); out.writeLong(ownershipEpoch); out.writeLong(time); out.writeLong(index); out.writeLong(nextSequence);
            out.writeInt(queues.size());
            for (var entry : queues.entrySet()) {
                out.writeUTF(entry.getKey()); out.writeInt(entry.getValue().messages.size());
                for (Message m : entry.getValue().messages.values()) {
                    out.writeUTF(m.id); out.writeUTF(m.payload); out.writeLong(m.sequence); out.writeInt(m.maxAttempts);
                    out.writeUTF(m.state); out.writeUTF(m.group); out.writeUTF(m.member);
                    out.writeLong(m.generation); out.writeLong(m.receipt); out.writeLong(m.until); out.writeInt(m.attempts);
                    out.writeLong(m.claimedAt); out.writeInt(m.depth); out.writeUTF(m.parent); out.writeUTF(m.child); out.writeUTF(m.actor);
                }
            }
            out.writeInt(groups.size());
            for (var entry : groups.entrySet()) {
                out.writeUTF(entry.getKey()); Group g = entry.getValue(); out.writeLong(g.generation); out.writeInt(g.members.size());
                for (var member : g.members.entrySet()) { out.writeUTF(member.getKey()); out.writeLong(member.getValue()); }
            }
            out.writeInt(results.size());
            for (var entry : results.entrySet()) {
                out.writeUTF(entry.getKey()); StoredResult r = entry.getValue(); out.writeInt(r.command.length); out.write(r.command);
                out.writeUTF(r.reply.value); out.writeLong(r.reply.index);
            }
            byte[] events = journal.snapshot(); out.writeInt(events.length); out.write(events);
            out.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Queue snapshot bounds", e); }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void restore(byte[] bytes) {
        QueueStateMachine restored = new QueueStateMachine();
        if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Queue aggregate snapshot limit");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != FORMAT) throw new IOException("Unsupported queue snapshot");
            restored.partition = in.readUTF(); identifier(restored.partition);
            if (!restored.partition.equals(partition)) throw new IOException("Foreign queue partition snapshot");
            restored.ownershipEpoch = in.readLong();
            if (restored.ownershipEpoch < 0) throw new IOException("Ownership epoch bounds");
            restored.time = in.readLong(); restored.index = in.readLong(); restored.nextSequence = in.readLong();
            for (int n = count(in); n > 0; n--) {
                String name = in.readUTF(); QueueData q = new QueueData();
                for (int m = count(in); m > 0; m--) {
                    Message v = new Message(in.readUTF(), in.readUTF(), in.readLong(), in.readInt());
                    v.state = in.readUTF(); v.group = in.readUTF(); v.member = in.readUTF(); v.generation = in.readLong();
                    v.receipt = in.readLong(); v.until = in.readLong(); v.attempts = in.readInt();
                    v.claimedAt = in.readLong(); v.depth = in.readInt(); v.parent = in.readUTF(); v.child = in.readUTF(); v.actor = in.readUTF(); if (q.messages.put(v.id, v) != null || q.ordered.put(v.sequence, v) != null) throw new IOException("Duplicate message"); restored.totalMessages++; restored.payloadBytes += v.payload.length();
                }
                if (restored.queues.put(name, q) != null) throw new IOException("Duplicate queue");
            }
            for (int n = count(in); n > 0; n--) {
                String name = in.readUTF(); Group g = new Group(); g.generation = in.readLong();
                for (int m = count(in); m > 0; m--) { if (g.members.put(in.readUTF(), in.readLong()) != null) throw new IOException("Duplicate member"); }
                if (restored.groups.put(name, g) != null) throw new IOException("Duplicate group");
            }
            for (int n = count(in); n > 0; n--) {
                String key = in.readUTF(); int length = in.readInt();
                if (length < 0 || length > ResourceCommand.MAX_BYTES) throw new IOException("Queue command bounds");
                byte[] command = in.readNBytes(length); if (command.length != length) throw new EOFException();
                validate(ResourceCommand.decode(command)); String reply = in.readUTF(); long at = in.readLong(); restored.requestBytes += retainedSize(key, command, reply); if (restored.results.put(key, new StoredResult(command, new Reply(reply, at))) != null) throw new IOException("Duplicate result");
            }
            int eventLength = in.readInt();
            if (eventLength < 0 || eventLength > 67_108_864) throw new IOException("Journal snapshot bounds");
            byte[] eventBytes = in.readNBytes(eventLength); if (eventBytes.length != eventLength) throw new EOFException();
            restored.journal = EventJournal.restore(eventBytes); restored.liveBytes = bytes.length - restored.requestBytes - eventLength; restored.validateState();
            if (in.available() != 0 || !Arrays.equals(bytes, restored.snapshot()) || restored.messageCount() > MAX_ENTRIES || restored.payloadBytes > 33_554_432 || restored.requestBytes > 33_554_432) throw new IOException("Queue snapshot trailing bytes/bounds");
        } catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Corrupt queue snapshot", e); }
        transientEmpty.clear(); queues.clear(); queues.putAll(restored.queues); groups.clear(); groups.putAll(restored.groups);
        results.clear(); results.putAll(restored.results); partition = restored.partition; ownershipEpoch = restored.ownershipEpoch; time = restored.time; index = restored.index; nextSequence = restored.nextSequence; totalMessages = restored.totalMessages; requestBytes = restored.requestBytes; liveBytes = restored.liveBytes; payloadBytes = restored.payloadBytes; journal = restored.journal;
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void validateSnapshot(byte[] snapshot) { new QueueStateMachine(partition).restore(snapshot); }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean transferReady() { return true; }

    /**
     * Reads retained results, assignments, DLQ identities or journal counts through the partition host.
     * Query format is a bounded ResourceCommand with Q_RESULT(request[,expectedCommandSha256]), Q_ASSIGN(group,member),
     * Q_DLQ(queue), or Q_EVENTS(); all scope authorization belongs to the authenticated host.
     * @param bytes bounded version-one query
     * @return UTF-8 response; unknown request returns UNKNOWN
     * @throws IllegalArgumentException for malformed queries
     */
    @Override
    public synchronized byte[] query(byte[] bytes) {
        ResourceCommand c = ResourceCommand.decode(bytes);
        String value = switch (c.operation()) {
            case "Q_RESULT" -> {
                if (c.arguments().size() != 1 && c.arguments().size() != 2) throw new IllegalArgumentException("Query arguments");
                if (c.arguments().size() == 2 && !c.arguments().get(1).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid command fingerprint");
                StoredResult original = results.get(key(c.scope(), c.arguments().getFirst()));
                if (original == null) original = transientEmpty.get(key(c.scope(), c.arguments().getFirst()));
                if (original != null && c.arguments().size() == 2 && !fingerprint(original.command).equals(c.arguments().get(1))) yield "REQUEST_CONFLICT";
                Reply reply = original == null ? null : original.reply;
                yield reply == null ? results.size() >= MAX_ENTRIES || requestBytes >= 33_554_432 ? "OVERLOADED" : "UNKNOWN" : materialize(original);
            }
            case "Q_ASSIGN" -> {
                if (c.arguments().size() != 2) throw new IllegalArgumentException("Query arguments");
                yield String.join("\t", assignment(c.scope(), c.arguments().get(0), c.arguments().get(1), index));
            }
            case "Q_DLQ" -> {
                if (c.arguments().size() != 1) throw new IllegalArgumentException("Query arguments");
                yield String.join("\t", deadLetters(c.scope(), c.arguments().getFirst(), index));
            }
            case "Q_DLQ_NEXT" -> {
                long after = number(c.arguments().get(1), 0, Long.MAX_VALUE); String answer = "";
                QueueData q = queues.get(key(c.scope(), c.arguments().getFirst()));
                if (q != null) for (Message m : q.ordered.tailMap(after, false).values()) if (m.state.equals("DLQ")) {
                    answer = "MESSAGE\t" + m.id + "\t" + m.payload + "\t" + m.sequence + "\t" + m.receipt + "\t" + m.attempts + "\t" + m.generation + "\t" + m.group + "\t" + m.member; break;
                }
                yield answer;
            }
            case "Q_DLQ_PAGE" -> {
                long after = Long.parseLong(c.arguments().get(1)), high = Long.parseLong(c.arguments().get(2));
                if (high == 0) high = nextSequence;
                if (after < 0 || after > high || high > nextSequence) throw new IllegalArgumentException("Invalid DLQ cursor");
                int maximum = Integer.parseInt(c.arguments().get(3)); if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("DLQ page count");
                QueueData q = queues.get(key(c.scope(), c.arguments().getFirst()));
                List<String> fields = new ArrayList<>(); fields.add(Long.toString(high)); long through = after; int bytesUsed = 32, count = 0;
                if (q != null) for (Message m : q.ordered.tailMap(after, false).values()) {
                    if (!m.state.equals("DLQ") || m.sequence <= after || m.sequence > high) continue;
                    String encoded = Base64.getEncoder().encodeToString(PartitionStateMachine.encode(out -> {
                        out.writeUTF(m.id); out.writeUTF(m.payload); out.writeLong(m.sequence); out.writeLong(m.receipt); out.writeInt(m.attempts); out.writeInt(m.maxAttempts);
                        out.writeUTF(m.parent); out.writeUTF(m.child); out.writeUTF(m.actor); out.writeInt(m.depth);
                    }));
                    if (count == maximum || bytesUsed + encoded.length() > 512 * 1024) break;
                    fields.add(encoded); through = m.sequence; count++; bytesUsed += encoded.length() + 1;
                }
                fields.add(1, Long.toString(through)); yield String.join("\t", fields);
            }
            case "Q_RECEIPT" -> {
                QueueData q = queues.get(key(c.scope(), c.arguments().getFirst())); String answer = "";
                if (q != null) for (Message m : q.messages.values()) if (m.state.equals("INFLIGHT") && m.group.equals(c.arguments().get(1)) && m.member.equals(c.arguments().get(2))) {
                    Group g = groups.get(key(c.scope(), m.group));
                    if (g != null && g.generation == m.generation && g.members.containsKey(m.member) && m.until > time)
                        answer = "MESSAGE\t" + m.id + "\t" + m.payload + "\t" + m.sequence + "\t" + m.receipt + "\t" + m.attempts + "\t" + m.generation;
                    break;
                }
                yield answer;
            }
            case "Q_EVENTS" -> {
                if (!c.arguments().isEmpty()) throw new IllegalArgumentException("Query arguments");
                yield Integer.toString(journal.retainedEvents());
            }
            default -> throw new IllegalArgumentException("Unsupported queue query");
        };
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void validateState() throws IOException {
        if (time < 0 || index < 0 || nextSequence != totalMessages || queues.size() > 1024 || groups.size() > 4096) throw new IOException("Queue state bounds");
        Set<Long> sequences = new HashSet<>();
        for (var entry : queues.entrySet()) {
            scopedIdentifier(entry.getKey());
            for (Message m : entry.getValue().messages.values()) {
                identifier(m.id); if (decode(m.payload).length > MAX_PAYLOAD || m.sequence <= 0 || m.sequence > nextSequence
                        || !sequences.add(m.sequence) || m.maxAttempts < 1 || m.maxAttempts > 1000 || m.attempts < 0
                        || m.attempts > m.maxAttempts || m.generation < 0 || m.receipt != m.attempts || m.until < 0 || m.claimedAt < 0 || m.claimedAt > time || m.depth < 0 || m.depth > 16
                        || m.until < m.claimedAt || m.until > plus(m.claimedAt, 86_400_000)
                        || !Set.of("READY", "INFLIGHT", "ACK", "DLQ").contains(m.state)) throw new IOException("Invalid message state");
                if (!m.parent.isEmpty()) { Message parent = entry.getValue().messages.get(m.parent); if (parent == null || !parent.child.equals(m.id) || parent.depth + 1 != m.depth || parent.sequence >= m.sequence) throw new IOException("Invalid redrive parent"); }
                if (!m.child.isEmpty()) { Message child = entry.getValue().messages.get(m.child); if (child == null || !child.parent.equals(m.id)) throw new IOException("Invalid redrive child"); }
                if (m.parent.isEmpty() && m.depth != 0 || !m.parent.isEmpty() && m.actor.isBlank()) throw new IOException("Invalid redrive audit");
                if (!m.actor.isEmpty()) identifier(m.actor);
                if (m.attempts == 0 && (!m.state.equals("READY") || !m.group.isEmpty() || !m.member.isEmpty() || m.generation != 0 || m.until != 0 || m.claimedAt != 0)) throw new IOException("Invalid undelivered metadata");
                if (!m.group.isEmpty()) identifier(m.group);
                if (!m.member.isEmpty()) identifier(m.member);
                if (m.state.equals("INFLIGHT") && (m.group.isEmpty() || m.member.isEmpty() || m.generation == 0 || m.receipt == 0 || m.attempts == 0))
                    throw new IOException("Invalid delivery state");
            }
        }
        for (var entry : groups.entrySet()) {
            scopedIdentifier(entry.getKey()); Group g = entry.getValue();
            if (g.generation <= 0 || g.generation > 2L * MAX_ENTRIES + 1 || g.members.size() > 1024) throw new IOException("Invalid group state");
            for (var member : g.members.entrySet()) { identifier(member.getKey()); if (member.getValue() <= 0) throw new IOException("Invalid member expiry"); }
        }
        for (var entry : results.entrySet()) {
            scopedIdentifier(entry.getKey()); StoredResult value = entry.getValue(); ResourceCommand command = ResourceCommand.decode(value.command);
            if (!entry.getKey().equals(key(command.scope(), command.requestId())) || value.reply.index <= 0 || value.reply.index > index
                    || command.operation().equals("Q_TIME")) throw new IOException("Invalid retained result");
            try { materialize(value); } catch (RuntimeException invalid) { throw new IOException("Invalid retained reply reference", invalid); }
        }
    }
    static String fingerprint(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 is unavailable", impossible); }
    }

    private static void scopedIdentifier(String value) throws IOException {
        int separator = value.indexOf('\0');
        if (separator <= 0 || value.indexOf('\0', separator + 1) >= 0) throw new IOException("Invalid scoped identity");
        identifier(value.substring(0, separator)); identifier(value.substring(separator + 1));
    }

    private static int count(DataInputStream in) throws IOException { int n = in.readInt(); if (n < 0 || n > MAX_ENTRIES) throw new IOException("Queue snapshot count"); return n; }
}
