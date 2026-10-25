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

package org.j7m.middleware.consensus;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32C;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLServerSocket;

/**
 * Persistent Raft group member with authenticated bounded RPC, automatic elections and joint
 * consensus. Acknowledged commands survive loss of a minority; storage must honor force and
 * atomic rename. One instance exclusively owns its directory. Client methods are thread safe;
 * callers retry another leader after NOT_LEADER and reuse request IDs after UNKNOWN. Request
 * IDs are retained indefinitely (including snapshots), and cannot identify different commands.
 * Admission stops with UNAVAILABLE when the request journal reaches maxSnapshotBytes/128 entries;
 * deployments must provision that bound for the group lifetime or start a new group generation.
 * Deadlines bound caller waiting, not rollback of a committed command. Applications implement
 * cooperative cancellation inside their commands; transport timeout never cancels committed work.
 */
public final class NetworkConsensusNode implements AutoCloseable {
    private static final int FORMAT = 1;
    private static final int VOTE = 1, APPEND = 2, SNAPSHOT = 3;
    private static final byte COMMAND = 1, NOOP = 2, JOINT = 3, FINAL = 4, CONTROL = 5;
    private final NetworkConsensusConfig config;
    private final ReplicatedStateMachine machine;
    private final long recoveredIndex;
    private final Object mutex = new Object();
    private final List<Entry> log = new ArrayList<>();
    private final Map<String, Result> requests = new HashMap<>();
    private final Map<String, Long> next = new HashMap<>(), match = new HashMap<>();
    private final Map<String, Incoming> incoming = new HashMap<>();
    private final ExecutorService handlers = new ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> { Thread t = new Thread(runnable, "j7m-consensus-rpc"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "j7m-consensus-clock"); t.setDaemon(true); return t;
    });
    private final ExecutorService outbound = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
        Thread t = new Thread(r, "j7m-consensus-outbound"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.CallerRunsPolicy());
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private FileChannel wal;
    private ServerSocket server;
    private volatile boolean closed;
    private boolean started, leader, campaigning;
    private long term, commit, applied, epoch, snapshotIndex, snapshotTerm;
    private long electionAt, heartbeatAt, lastQuorumAt;
    private String votedFor = "", leaderId = "";
    private Set<String> members, joint = Set.of(), committedJoint = Set.of();
    private byte[] snapshotBytes = new byte[0];
    private IOException failed;
    private volatile java.util.function.Consumer<String> faultInjector;

    void faultInjector(java.util.function.Consumer<String> injector) { faultInjector = injector; }
    private void fault(String boundary) {
        var injector = faultInjector; if (injector != null) injector.accept(boundary);
    }

    private record Entry(long index, long term, byte type, String requestId, byte[] command) { }
    private record Result(long index, byte[] digest) { }
    private record Reply(long term, boolean success, long index) { }
    private record Incoming(Path path, long size, byte[] digest) { }

    /**
     * Opens and recovers a node, but does not start its network endpoint.
     * @param config bootstrap, identity and resource bounds
     * @param machine deterministic application callbacks
     * @throws IOException if storage is locked, corrupt, unsupported or cannot be recovered
     */
    public NetworkConsensusNode(NetworkConsensusConfig config, ReplicatedStateMachine machine) throws IOException {
        this.config = Objects.requireNonNull(config); this.machine = Objects.requireNonNull(machine);
        this.members = config.initialMembers();
        Files.createDirectories(config.directory());
        lockChannel = FileChannel.open(config.directory().resolve("network.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { directoryLock = lockChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException e) { lockChannel.close(); throw new IOException("storage already owned", e); }
        if (directoryLock == null) { lockChannel.close(); throw new IOException("storage already owned"); }
        try {
            recover();
            recoveredIndex = commit;
            wal = FileChannel.open(walPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            wal.position(wal.size());
            if (wal.size() == 0) durableRecord(identityRecord());
            applyCommitted(); resetElection();
        } catch (IOException | RuntimeException e) {
            directoryLock.release(); lockChannel.close(); throw e;
        }
    }

    /**
     * Starts automatic elections, heartbeats and the authenticated endpoint.
     * @throws IOException if binding fails or the node has closed
     */
    public void start() throws IOException {
        synchronized (mutex) {
            checkOpen(); if (started) return;
            server = config.tlsContext() == null ? new ServerSocket() : config.tlsContext().getServerSocketFactory().createServerSocket();
            if (server instanceof SSLServerSocket tls) tls.setNeedClientAuth(true);
            server.setReuseAddress(true);
            server.bind(config.peers().get(config.nodeId())); started = true;
            Thread accept = new Thread(this::accept, "j7m-consensus-accept-" + config.nodeId()); accept.setDaemon(true); accept.start();
            clock.scheduleWithFixedDelay(this::tickSafely, 1, config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Commits a command, or waits for an earlier identical request, and returns after local apply.
     * @param requestId stable nonempty application-scoped id, at most 1024 UTF-8 bytes
     * @param command immutable copied command, bounded by the frame budget
     * @param deadline absolute caller deadline
     * @return durable committed log position
     * @throws IOException NOT_LEADER, UNAVAILABLE before admission, UNKNOWN after admission,
     * or CONFLICT when the id was used with a different command
     */
    public long append(String requestId, byte[] command, Instant deadline) throws IOException {
        Objects.requireNonNull(deadline); Objects.requireNonNull(command);
        if (requestId == null || requestId.isBlank() || requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024
                || command.length > config.maxFrameBytes() / 4) throw new IllegalArgumentException("command/id exceeds budget");
        synchronized (mutex) {
            requireLeader();
            if (remaining(deadline) <= 0) throw new IOException("UNAVAILABLE: deadline before admission");
            Result known = requests.get(requestId);
            byte[] digest = hash(command);
            if (known != null && !MessageDigest.isEqual(known.digest(), digest)) throw new IOException("CONFLICT: request id");
            long index;
            if (known != null) index = known.index();
            else {
                if (requests.size() >= config.maxSnapshotBytes() / 128) throw new IOException("UNAVAILABLE: request journal capacity");
                index = lastIndex() + 1;
                Entry entry = new Entry(index, term, COMMAND, requestId, command.clone());
                persistEntries(List.of(entry)); log.add(entry); requests.put(requestId, new Result(index, digest));
            }
            awaitApplied(index, deadline);
            fault("before-append-response");
            return index;
        }
    }

    /**
     * Commits a trusted deterministic maintenance command without retaining a request identity.
     * The state machine must make repeated controls harmless through its own monotonic sequence
     * or logical version. This operation is unsuitable for ordinary application requests, which
     * must use append with persistent request deduplication. Timeout never rolls back a control.
     * @param command bounded copied maintenance command bytes
     * @param deadline absolute caller deadline
     * @return committed and locally applied log position
     * @throws IOException UNAVAILABLE before admission or UNKNOWN after admission
     */
    public long appendControl(byte[] command, Instant deadline) throws IOException {
        Objects.requireNonNull(command); Objects.requireNonNull(deadline);
        if (command.length > config.maxFrameBytes() / 4) throw new IllegalArgumentException("control exceeds budget");
        synchronized (mutex) {
            requireLeader();
            if (remaining(deadline) <= 0) throw new IOException("UNAVAILABLE: deadline before admission");
            Entry entry = new Entry(lastIndex() + 1, term, CONTROL, "", command.clone());
            persistEntries(List.of(entry)); log.add(entry); awaitApplied(entry.index(), deadline); return entry.index();
        }
    }

    /**
     * Establishes a linearizable read barrier using a fresh current-term quorum confirmation.
     * @param deadline absolute caller deadline
     * @return locally applied committed prefix at the barrier
     * @throws IOException if the node is not leader, loses quorum, or misses the deadline
     */
    public long readIndex(Instant deadline) throws IOException {
        long readTerm;
        long fence;
        synchronized (mutex) {
            requireLeader(); readTerm = term;
            fence = lastIndex();
            // A current-term committed entry is required before Raft can establish a read barrier.
            while (commit == 0 || termAt(commit) != term) {
                waitUntil(deadline, "UNAVAILABLE: current-term barrier"); requireLeader();
            }
        }
        Set<String> acknowledged = replicateRound(readTerm, deadline);
        synchronized (mutex) {
            requireLeader();
            if (term != readTerm || !quorum(acknowledged)) throw new IOException("UNAVAILABLE: read quorum");
            long barrier = commit;
            awaitApplied(Math.min(fence, barrier), deadline);
            if (remaining(deadline) <= 0) throw new IOException("UNAVAILABLE: read deadline");
            return barrier;
        }
    }

    /**
     * Tests callback ownership without exposing mutable application state.
     * @param candidate application callback instance
     * @return true only when this node owns that exact instance
     */
    public boolean hosts(ReplicatedStateMachine candidate) { return machine == candidate; }

    /**
     * Returns the committed prefix restored during construction, before any new commands.
     * Unlike status progress, this immutable diagnostic distinguishes recovery from a fresh group
     * observed only after it accepted new writes. Zero means no committed startup history.
     * @return immutable recovered committed index, never the uncommitted tail
     */
    public long recoveredIndex() { return recoveredIndex; }

    /**
     * Returns a diagnostic local view without contacting a quorum.
     * @return immutable local status
     */
    public NetworkConsensusStatus status() {
        synchronized (mutex) { return new NetworkConsensusStatus(config.nodeId(), term, leaderId, commit, applied, epoch, members, joint); }
    }

    /**
     * Changes voters through learner catch-up followed by durable joint and final configurations.
     * Only one change may be in flight. All endpoints must be explicitly authorized in peers.
     * @param expectedConfigEpoch caller's committed configuration epoch
     * @param newMembers nonempty target voter set
     * @param deadline absolute deadline; timeout does not undo a submitted configuration
     * @return committed new epoch
     * @throws IOException CONFLICT, NOT_CAUGHT_UP, NOT_LEADER or UNKNOWN
     */
    public long reconfigure(long expectedConfigEpoch, Set<String> newMembers, Instant deadline) throws IOException {
        Set<String> target = Set.copyOf(newMembers);
        synchronized (mutex) {
            requireLeader();
            if (expectedConfigEpoch != epoch || !joint.isEmpty()) throw new IOException("CONFLICT: configuration");
            if (target.isEmpty() || !config.peers().keySet().containsAll(target)) throw new IllegalArgumentException("unauthorized voter");
            if (target.equals(members)) return epoch;
            for (String id : target) {
                if (!id.equals(config.nodeId()) && match.getOrDefault(id, 0L) < commit)
                    throw new IOException("NOT_CAUGHT_UP: " + id);
            }
            long changeEpoch = epoch + 1;
            Entry entry = new Entry(lastIndex() + 1, term, JOINT, "", encodeConfiguration(changeEpoch, target));
            persistEntries(List.of(entry)); log.add(entry); joint = target;
            awaitApplied(entry.index(), deadline);
            Entry finish = log.stream().filter(e -> e.type() == FINAL && e.index() > applied).findFirst().orElse(null);
            if (finish == null && epoch < changeEpoch) {
                finish = new Entry(lastIndex() + 1, term, FINAL, "", encodeConfiguration(changeEpoch, target));
                persistEntries(List.of(finish)); log.add(finish);
            }
            if (finish != null) awaitApplied(finish.index(), deadline);
            return epoch;
        }
    }

    record RecoveryImage(String groupId, String nodeId, long currentTerm, byte[] checkpoint) { }

    RecoveryImage exportRecoveryImage(Instant deadline) throws IOException {
        long barrier = readIndex(deadline);
        synchronized (mutex) {
            checkOpen();
            if (applied < barrier) throw new IOException("backup read barrier is not applied");
            byte[] state = Objects.requireNonNull(machine.snapshot()).clone();
            if (state.length > config.maxSnapshotBytes()) throw new IOException("backup state size limit");
            byte[] image = checkpoint(applied, termAt(applied), state);
            if (image.length > (long) config.maxSnapshotBytes() * 4) throw new IOException("backup checkpoint size limit");
            return new RecoveryImage(config.groupId(), config.nodeId(), term, image);
        }
    }

    /**
     * Atomically captures the applied prefix and compacts only that committed prefix.
     * Configuration, joint transition, request journal, term and vote survive the compacted file.
     * @return included committed index
     * @throws IOException if snapshot is too large or atomic durable publication fails
     */
    public long snapshot() throws IOException {
        synchronized (mutex) {
            checkOpen();
            byte[] bytes = Objects.requireNonNull(machine.snapshot()).clone();
            if (bytes.length > config.maxSnapshotBytes()) throw new IOException("snapshot exceeds size limit");
            long index = applied, includedTerm = termAt(index);
            byte[] checkpoint = checkpoint(index, includedTerm, bytes);
            if (checkpoint.length > (long) config.maxSnapshotBytes() * 4) throw new IOException("snapshot manifest exceeds size limit");
            try { compact(checkpoint); } catch (IOException error) { fail(error); throw error; }
            snapshotIndex = index; snapshotTerm = includedTerm; snapshotBytes = bytes; snapshotManifest = checkpoint;
            log.removeIf(entry -> entry.index() <= index);
            return index;
        }
    }

    private void tickSafely() {
        try { tick(); }
        catch (Throwable error) { synchronized (mutex) { fail(new IOException("consensus stopped after internal failure", error)); } }
    }

    private void tick() throws IOException {
        long electionTerm = 0;
        long replicateTerm = 0;
        synchronized (mutex) {
            if (closed || failed != null) return;
            long now = System.nanoTime();
            if (leader) { if (now >= heartbeatAt) { heartbeatAt = now + config.heartbeatInterval().toNanos(); replicateTerm = term; } }
            else if (now >= electionAt && electorate().contains(config.nodeId()) && !campaigning) {
                campaigning = true; leaderId = ""; term++; votedFor = config.nodeId(); persistTerm(); resetElection(); electionTerm = term;
            }
        }
        if (electionTerm != 0) campaign(electionTerm);
        if (replicateTerm != 0) replicateRound(replicateTerm, Instant.now().plus(config.electionTimeout()));
    }

    private void campaign(long electionTerm) throws IOException {
        Set<String> votes = ConcurrentHashMap.newKeySet(); votes.add(config.nodeId());
        List<Future<?>> calls = new ArrayList<>();
        Set<String> voters;
        long last, lastTerm;
        synchronized (mutex) { voters = electorate(); last = lastIndex(); lastTerm = termAt(last); }
        for (String peer : voters) if (!peer.equals(config.nodeId())) calls.add(outbound.submit(() -> {
            try {
                Reply reply = rpc(peer, VOTE, out -> { out.writeLong(electionTerm); out.writeLong(last); out.writeLong(lastTerm); });
                synchronized (mutex) { observeTerm(reply.term()); if (term == electionTerm && reply.success()) votes.add(peer); }
            } catch (IOException ignored) { /* An unreachable voter cannot contribute to a quorum. */ }
        }));
        join(calls, Instant.now().plus(config.electionTimeout()));
        synchronized (mutex) {
            campaigning = false;
            if (!closed && term == electionTerm && quorum(votes)) {
                leader = true; leaderId = config.nodeId(); lastQuorumAt = System.nanoTime();
                next.clear(); match.clear();
                for (String id : config.peers().keySet()) next.put(id, lastIndex() + 1);
                Entry noop = new Entry(lastIndex() + 1, term, NOOP, "", new byte[0]);
                persistEntries(List.of(noop)); log.add(noop); heartbeatAt = 0; mutex.notifyAll();
            }
        }
    }

    private Set<String> replicateRound(long expectedTerm, Instant deadline) {
        Set<String> acknowledgements = ConcurrentHashMap.newKeySet(); acknowledgements.add(config.nodeId());
        List<Future<?>> calls = new ArrayList<>();
        for (String peer : config.peers().keySet()) if (!peer.equals(config.nodeId())) calls.add(outbound.submit(() -> {
            try { if (replicatePeer(peer, expectedTerm)) acknowledgements.add(peer); }
            catch (IOException ignored) { /* Timeout/rejection is not an acknowledgement. */ }
        }));
        join(calls, deadline);
        synchronized (mutex) {
            if (leader && term == expectedTerm) {
                if (quorum(acknowledgements)) lastQuorumAt = System.nanoTime();
                else if (System.nanoTime() - lastQuorumAt > config.electionTimeout().toNanos()) {
                    leader = false; leaderId = ""; resetElection(); mutex.notifyAll();
                }
                try { if (leader) advanceCommit(); }
                catch (IOException error) { fail(error); }
            }
        }
        return acknowledgements;
    }

    private boolean replicatePeer(String peer, long expectedTerm) throws IOException {
        long previous, previousTerm, leaderCommit;
        List<Entry> batch;
        boolean needsSnapshot;
        synchronized (mutex) { needsSnapshot = next.getOrDefault(peer, lastIndex() + 1) <= snapshotIndex; }
        if (needsSnapshot) return sendSnapshot(peer, expectedTerm);
        synchronized (mutex) {
            if (!leader || term != expectedTerm || closed) return false;
            long position = Math.min(next.getOrDefault(peer, lastIndex() + 1), lastIndex() + 1);
            if (position <= snapshotIndex) return false;
            previous = position - 1; previousTerm = termAt(previous); leaderCommit = commit;
            batch = new ArrayList<>(); int bytes = 64;
            for (Entry entry : log) if (entry.index() >= position) {
                int size = entry.command().length + entry.requestId().length() * 3 + 40;
                if (!batch.isEmpty() && bytes + size > config.maxFrameBytes() / 2) break;
                bytes += size; batch.add(entry);
            }
        }
        Reply reply = rpc(peer, APPEND, out -> {
            out.writeLong(expectedTerm); out.writeLong(previous); out.writeLong(previousTerm); out.writeLong(leaderCommit);
            out.writeInt(batch.size()); for (Entry entry : batch) writeEntry(out, entry);
        });
        synchronized (mutex) {
            observeTerm(reply.term());
            if (!leader || term != expectedTerm) return false;
            if (reply.success()) {
                match.merge(peer, reply.index(), Math::max); next.put(peer, reply.index() + 1);
                return true;
            }
            next.put(peer, Math.max(1, Math.min(previous, reply.index() + 1)));
            return false;
        }
    }

    private void advanceCommit() throws IOException {
        for (long candidate = lastIndex(); candidate > commit; candidate--) {
            if (termAt(candidate) != term) continue;
            Set<String> replicated = new HashSet<>(); replicated.add(config.nodeId());
            for (var entry : match.entrySet()) if (entry.getValue() >= candidate) replicated.add(entry.getKey());
            if (quorum(replicated)) { persistCommit(candidate); commit = candidate; applyCommitted(); mutex.notifyAll(); break; }
        }
        if (leader && !committedJoint.isEmpty() && log.stream().noneMatch(e -> e.type() == FINAL && e.index() > applied)) {
            Entry finish = new Entry(lastIndex() + 1, term, FINAL, "", encodeConfiguration(epoch + 1, committedJoint));
            persistEntries(List.of(finish)); log.add(finish);
        }
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = server.accept(); socket.setSoTimeout(rpcTimeout()); socket.setTcpNoDelay(true);
                try { handlers.execute(() -> handle(socket)); }
                catch (RejectedExecutionException e) { socket.close(); }
            } catch (IOException error) { if (!closed) synchronized (mutex) { fail(error); } }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            DataInputStream network = new DataInputStream(socket.getInputStream());
            byte[] frame = readFrame(network); byte[] signature = network.readNBytes(32);
            if (!MessageDigest.isEqual(sign(frame), signature)) throw new IOException("unauthenticated frame");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != FORMAT || !in.readUTF().equals(config.groupId())) throw new IOException("unsupported protocol/group");
            String sender = in.readUTF(), recipient = in.readUTF(), nonce = in.readUTF();
            if (!recipient.equals(config.nodeId()) || !config.peers().containsKey(sender)) throw new IOException("unauthorized identity");
            int operation = in.readUnsignedByte();
            Reply reply;
            synchronized (mutex) {
                checkOpen();
                reply = switch (operation) {
                    case VOTE -> vote(sender, in);
                    case APPEND -> receiveAppend(sender, in);
                    case SNAPSHOT -> receiveSnapshot(sender, in);
                    default -> throw new IOException("unknown operation");
                };
                if (in.available() != 0) throw new IOException("trailing RPC data");
            }
            byte[] body = encode(out -> { out.writeInt(FORMAT); out.writeUTF(config.groupId()); out.writeUTF(config.nodeId()); out.writeUTF(sender);
                out.writeUTF(nonce); out.writeLong(reply.term()); out.writeBoolean(reply.success()); out.writeLong(reply.index()); });
            DataOutputStream out = new DataOutputStream(socket.getOutputStream()); writeFrame(out, body); out.flush();
        } catch (IOException | RuntimeException ignored) { /* Invalid frames are rejected without state disclosure. */ }
    }

    private Reply vote(String sender, DataInputStream in) throws IOException {
        long candidateTerm = in.readLong(), candidateIndex = in.readLong(), candidateLastTerm = in.readLong();
        if (in.available() != 0) throw new IOException("trailing vote data");
        if (!electorate().contains(sender)) return new Reply(term, false, lastIndex());
        observeTerm(candidateTerm);
        boolean fresh = candidateLastTerm > termAt(lastIndex()) || candidateLastTerm == termAt(lastIndex()) && candidateIndex >= lastIndex();
        boolean grant = candidateTerm == term && fresh && (votedFor.isEmpty() || votedFor.equals(sender));
        if (grant) { votedFor = sender; persistTerm(); resetElection(); }
        return new Reply(term, grant, lastIndex());
    }

    private Reply receiveAppend(String sender, DataInputStream in) throws IOException {
        long leaderTerm = in.readLong(), previous = in.readLong(), previousTerm = in.readLong(), leaderCommit = in.readLong();
        int count = in.readInt();
        if (count < 0 || count > config.maxFrameBytes() / 25) throw new IOException("entry count limit");
        List<Entry> entries = new ArrayList<>();
        for (int n = 0; n < count; n++) entries.add(readEntry(in));
        if (in.available() != 0) throw new IOException("trailing append data");
        if (!electorate().contains(sender) || leaderTerm < term) return new Reply(term, false, lastIndex());
        observeTerm(leaderTerm); leader = false; leaderId = sender; resetElection();
        if (previous < snapshotIndex || previous > lastIndex() || termAt(previous) != previousTerm)
            return new Reply(term, false, Math.max(snapshotIndex, Math.min(previous - 1, lastIndex())));
        long replacementFrom = Long.MAX_VALUE;
        for (Entry entry : entries) if (entry.index() > snapshotIndex && entry.index() <= lastIndex() && entryAt(entry.index()).term() != entry.term()) { replacementFrom = entry.index(); break; }
        long validatedIndex = previous + 1;
        Map<String, Long> batchIds = new HashMap<>();
        for (Entry entry : entries) {
            if (entry.index() != validatedIndex++ || entry.term() > leaderTerm) throw new IOException("invalid entry sequence");
            if (entry.type() == COMMAND) {
                if (entry.requestId().isBlank() || entry.requestId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) throw new IOException("invalid request identity");
                Long duplicate = batchIds.putIfAbsent(entry.requestId(), entry.index());
                Result prior = requests.get(entry.requestId());
                if (duplicate != null && duplicate != entry.index() || prior != null && prior.index() < entry.index() && prior.index() < replacementFrom) throw new IOException("duplicate request entry");
            } else if (entry.type() == JOINT || entry.type() == FINAL) decodeConfiguration(entry.command());
        }
        long expected = previous + 1;
        List<Entry> added = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.index() != expected++ || entry.term() > leaderTerm) throw new IOException("invalid entry sequence");
            if (entry.index() <= lastIndex()) {
                Entry existing = entryAt(entry.index());
                if (existing.term() != entry.term()) {
                    if (entry.index() <= commit) throw new IOException("committed prefix conflict");
                    persistTruncate(entry.index()); log.removeIf(item -> item.index() >= entry.index()); rebuildRequests(); recomputeJoint();
                } else if (!same(existing, entry)) throw new IOException("same-term entry conflict");
            }
            if (entry.index() > lastIndex()) { log.add(entry); added.add(entry); }
        }
        if (!added.isEmpty()) { persistEntries(added); addRequests(added); recomputeJoint(); }
        if (leaderCommit > commit) { long newCommit = Math.max(commit, Math.min(leaderCommit, previous + entries.size()));
            if (newCommit > commit) { persistCommit(newCommit); commit = newCommit; applyCommitted(); } }
        mutex.notifyAll(); return new Reply(term, true, previous + entries.size());
    }

    private void applyCommitted() throws IOException {
        try {
            while (applied < commit) {
                Entry entry = entryAt(applied + 1);
                if (entry.type() == COMMAND || entry.type() == CONTROL) machine.apply(entry.index(), entry.term(), entry.command().clone());
                else if (entry.type() == JOINT) { Configuration c = decodeConfiguration(entry.command()); joint = c.members(); committedJoint = joint; }
                else if (entry.type() == FINAL) {
                    Configuration c = decodeConfiguration(entry.command()); members = c.members(); epoch = c.epoch(); joint = Set.of(); committedJoint = Set.of();
                    if (!members.contains(config.nodeId())) { leader = false; leaderId = ""; }
                }
                applied++; machine.appliedThrough(applied);
            }
            recomputeJoint();
        } catch (RuntimeException error) { fail(new IOException("state machine failed; recovery required", error)); throw failed; }
    }

    private record Configuration(long epoch, Set<String> members) { }
    private static byte[] encodeConfiguration(long epoch, Set<String> ids) throws IOException {
        return encode(out -> { out.writeLong(epoch); writeMembers(out, ids); });
    }
    private Configuration decodeConfiguration(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            long generation = in.readLong(); Set<String> ids = readMembers(in);
            if (generation <= 0 || !config.peers().keySet().containsAll(ids) || in.available() != 0) throw new IOException("invalid configuration");
            return new Configuration(generation, ids);
        }
    }
    private void recomputeJoint() throws IOException {
        joint = committedJoint;
        for (long index = applied + 1; index <= lastIndex(); index++) {
            Entry entry = entryAt(index); if (entry.type() == JOINT) joint = decodeConfiguration(entry.command()).members();
        }
    }
    private Set<String> electorate() { Set<String> ids = new HashSet<>(members); ids.addAll(joint); return ids; }
    private boolean quorum(Set<String> ids) { return majority(members, ids) && (joint.isEmpty() || majority(joint, ids)); }
    private static boolean majority(Set<String> voters, Set<String> acknowledgements) {
        return voters.stream().filter(acknowledgements::contains).count() > voters.size() / 2;
    }
    private void observeTerm(long newer) throws IOException {
        if (newer > term) { term = newer; votedFor = ""; leader = false; leaderId = ""; persistTerm(); resetElection(); mutex.notifyAll(); }
    }
    private void resetElection() { electionAt = System.nanoTime() + config.electionTimeout().toNanos()
            + ThreadLocalRandom.current().nextLong(config.electionTimeout().toNanos()); }
    private void awaitApplied(long index, Instant deadline) throws IOException {
        while (applied < index) { waitUntil(deadline, "UNKNOWN: command outcome"); if (!leader) throw new IOException("UNKNOWN: leadership changed"); }
    }
    private void waitUntil(Instant deadline, String message) throws IOException {
        checkOpen(); long remaining = remaining(deadline); if (remaining <= 0) throw new IOException(message);
        try { mutex.wait(Math.max(1, Math.min(remaining, 100))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("UNKNOWN: interrupted", e); }
    }
    private static long remaining(Instant deadline) { return java.time.Duration.between(Instant.now(), deadline).toMillis(); }
    private void requireLeader() throws IOException {
        checkOpen();
        if (!started || !leader) throw new IOException(leaderId.isEmpty() ? "UNAVAILABLE: no known leader" : "NOT_LEADER: " + leaderId);
        if (System.nanoTime() - lastQuorumAt > config.electionTimeout().toNanos()) throw new IOException("UNAVAILABLE: quorum confirmation expired");
    }
    private void checkOpen() throws IOException { if (failed != null) throw failed; if (closed) throw new IOException("node closed"); }
    private void fail(IOException error) { failed = error; leader = false; mutex.notifyAll(); }
    private long lastIndex() { return log.isEmpty() ? snapshotIndex : log.get(log.size() - 1).index(); }
    private long termAt(long index) { return index == snapshotIndex ? snapshotTerm : entryAt(index).term(); }
    private Entry entryAt(long index) { return log.get(Math.toIntExact(index - snapshotIndex - 1)); }
    private static boolean same(Entry a, Entry b) { return a.type() == b.type() && a.requestId().equals(b.requestId()) && Arrays.equals(a.command(), b.command()); }
    private void rebuildRequests() throws IOException {
        requests.entrySet().removeIf(entry -> entry.getValue().index() > snapshotIndex);
        addRequests(log);
    }
    private void addRequests(List<Entry> entries) throws IOException {
        for (Entry entry : entries) if (entry.type() == COMMAND) {
            Result prior = requests.putIfAbsent(entry.requestId(), new Result(entry.index(), hash(entry.command())));
            if (prior != null && prior.index() != entry.index()) throw new IOException("duplicate committed request id");
        }
    }

    private Reply rpc(String peer, int operation, Encoder encoder) throws IOException {
        String nonce = UUID.randomUUID().toString();
        byte[] body = encode(out -> { out.writeInt(FORMAT); out.writeUTF(config.groupId()); out.writeUTF(config.nodeId()); out.writeUTF(peer); out.writeUTF(nonce);
            out.writeByte(operation); encoder.write(out); });
        if (body.length > config.maxFrameBytes()) throw new IOException("RPC exceeds frame limit");
        try (Socket socket = config.tlsContext() == null ? new Socket() : config.tlsContext().getSocketFactory().createSocket()) {
            socket.connect(config.peers().get(peer), rpcTimeout()); socket.setSoTimeout(rpcTimeout()); socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream()); writeFrame(out, body); out.flush();
            DataInputStream network = new DataInputStream(socket.getInputStream());
            byte[] response = readFrame(network), authentication = network.readNBytes(32);
            if (!MessageDigest.isEqual(sign(response), authentication)) throw new IOException("invalid reply authentication");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(response));
            if (in.readInt() != FORMAT || !in.readUTF().equals(config.groupId()) || !in.readUTF().equals(peer) || !in.readUTF().equals(config.nodeId()) || !in.readUTF().equals(nonce))
                throw new IOException("reply identity/nonce mismatch");
            Reply reply = new Reply(in.readLong(), in.readBoolean(), in.readLong());
            if (in.available() != 0) throw new IOException("trailing reply data"); return reply;
        }
    }
    private int rpcTimeout() { return Math.toIntExact(Math.min(2000, Math.max(50, config.electionTimeout().toMillis() / 2))); }
    private byte[] readFrame(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > config.maxFrameBytes()) throw new IOException("frame size limit");
        byte[] frame = in.readNBytes(size); if (frame.length != size) throw new EOFException("incomplete frame"); return frame;
    }
    private void writeFrame(DataOutputStream out, byte[] frame) throws IOException { out.writeInt(frame.length); out.write(frame); out.write(sign(frame)); }
    private byte[] sign(byte[] frame) throws IOException {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(config.authenticationKey(), "HmacSHA256")); return mac.doFinal(frame); }
        catch (GeneralSecurityException e) { throw new IOException(e); }
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { encoder.write(out); } return bytes.toByteArray();
    }
    private static void join(List<Future<?>> calls, Instant deadline) {
        for (Future<?> call : calls) try { call.get(Math.max(1, remaining(deadline)), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        catch (ExecutionException | TimeoutException ignored) { /* Missing RPCs never count toward quorum. */ }
    }
    private static void writeMembers(DataOutputStream out, Set<String> ids) throws IOException {
        out.writeInt(ids.size()); for (String id : new TreeSet<>(ids)) out.writeUTF(id);
    }
    private Set<String> readMembers(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size <= 0 || size > config.peers().size()) throw new IOException("membership limit");
        Set<String> ids = new HashSet<>(); for (int n = 0; n < size; n++) if (!ids.add(in.readUTF())) throw new IOException("duplicate voter");
        if (!config.peers().keySet().containsAll(ids)) throw new IOException("unknown voter"); return Set.copyOf(ids);
    }
    private static void writeEntry(DataOutputStream out, Entry entry) throws IOException {
        out.writeLong(entry.index()); out.writeLong(entry.term()); out.writeByte(entry.type()); out.writeUTF(entry.requestId());
        out.writeInt(entry.command().length); out.write(entry.command());
    }
    private Entry readEntry(DataInputStream in) throws IOException {
        long index = in.readLong(), entryTerm = in.readLong(); byte type = in.readByte(); String id = in.readUTF();
        int size = in.readInt();
        if (index <= 0 || entryTerm <= 0 || type < COMMAND || type > CONTROL || size < 0 || size > config.maxFrameBytes() / 4)
            throw new IOException("invalid bounded entry");
        byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException();
        return new Entry(index, entryTerm, type, id, bytes);
    }

    private byte[] identityRecord() throws IOException { return encode(out -> { out.writeByte(6); out.writeUTF(config.groupId()); out.writeUTF(config.nodeId()); }); }
    private Path walPath() { return config.directory().resolve("network.wal"); }
    private void persistTerm() throws IOException { durableRecord(encode(out -> { out.writeByte(1); out.writeLong(term); out.writeUTF(votedFor); })); }
    private void persistEntries(List<Entry> entries) throws IOException {
        durableRecord(encode(out -> { out.writeByte(2); out.writeInt(entries.size()); for (Entry entry : entries) writeEntry(out, entry); }));
    }
    private void persistCommit(long index) throws IOException {
        fault("before-commit-force"); durableRecord(encode(out -> { out.writeByte(3); out.writeLong(index); })); fault("after-commit-force");
    }
    private void persistTruncate(long index) throws IOException { durableRecord(encode(out -> { out.writeByte(4); out.writeLong(index); })); }
    private void durableRecord(byte[] bytes) throws IOException {
        try { appendRecord(wal, bytes); fault("before-wal-force"); wal.force(true); fault("after-wal-force"); }
        catch (IOException error) { fail(error); throw error; }
    }
    private static void appendRecord(FileChannel channel, byte[] bytes) throws IOException {
        CRC32C crc = new CRC32C(); crc.update(bytes);
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 8); buffer.putInt(bytes.length).put(bytes).putInt((int) crc.getValue()).flip();
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private void recover() throws IOException {
        if (!Files.exists(walPath())) return;
        try (FileChannel channel = FileChannel.open(walPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long position = 0;
            while (position < channel.size()) {
                long start = position;
                ByteBuffer length = ByteBuffer.allocate(4);
                if (!readFully(channel, length)) { channel.truncate(start); channel.force(true); break; }
                length.flip(); int size = length.getInt();
                if (size <= 0 || size > (long) config.maxSnapshotBytes() * 4 + config.maxFrameBytes()) throw new IOException("corrupt WAL record length");
                ByteBuffer payload = ByteBuffer.allocate(size + 4);
                if (!readFully(channel, payload)) { channel.truncate(start); channel.force(true); break; }
                payload.flip(); byte[] bytes = new byte[size]; payload.get(bytes); int checksum = payload.getInt();
                CRC32C crc = new CRC32C(); crc.update(bytes); if ((int) crc.getValue() != checksum) throw new IOException("corrupt WAL checksum");
                if (start == 0 && bytes[0] != 6) throw new IOException("missing WAL group identity");
                replayRecord(bytes); position = start + size + 8L;
            }
        }
        if (commit < snapshotIndex || commit > lastIndex()) throw new IOException("invalid recovered commit prefix");
        rebuildRequests();
        if (snapshotIndex > 0) machine.restore(snapshotBytes.clone());
        applied = snapshotIndex; machine.appliedThrough(applied);
    }
    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false; return true;
    }
    private void replayRecord(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int kind = in.readUnsignedByte();
            if (kind == 1) {
                long newTerm = in.readLong(); String vote = in.readUTF();
                if (newTerm < term || !vote.isEmpty() && !config.peers().containsKey(vote)) throw new IOException("invalid recovered term/vote");
                term = newTerm; votedFor = vote;
            } else if (kind == 2) {
                int count = in.readInt(); if (count < 0 || count > config.maxFrameBytes()) throw new IOException("invalid WAL count");
                for (int n = 0; n < count; n++) { Entry entry = readEntry(in); if (entry.index() != lastIndex() + 1) throw new IOException("noncontiguous WAL"); log.add(entry); }
            } else if (kind == 3) {
                long index = in.readLong(); if (index < commit || index > lastIndex()) throw new IOException("invalid WAL commit"); commit = index;
            } else if (kind == 4) {
                long index = in.readLong(); if (index <= commit) throw new IOException("committed WAL truncation"); log.removeIf(entry -> entry.index() >= index);
            } else if (kind == 5) {
                byte[] checkpoint = in.readAllBytes(); installCheckpoint(decodeCheckpoint(checkpoint)); return;
            } else if (kind == 6) {
                if (!in.readUTF().equals(config.groupId()) || !in.readUTF().equals(config.nodeId())) throw new IOException("WAL identity mismatch");
            } else throw new IOException("unknown WAL record");
            if (in.available() != 0) throw new IOException("trailing WAL data");
        }
    }
    private record Checkpoint(long index, long term, long epoch, Set<String> members, Set<String> joint,
            Map<String, Result> requests, byte[] state, byte[] manifest) { }
    private byte[] snapshotManifest = new byte[0];
    private byte[] checkpoint(long index, long includedTerm, byte[] bytes) throws IOException {
        return encode(out -> {
            out.writeInt(FORMAT); out.writeUTF(config.groupId()); out.writeLong(index); out.writeLong(includedTerm); out.writeLong(epoch); writeMembers(out, members);
            out.writeBoolean(!committedJoint.isEmpty()); if (!committedJoint.isEmpty()) writeMembers(out, committedJoint);
            List<Map.Entry<String, Result>> journal = requests.entrySet().stream().filter(e -> e.getValue().index() <= index).sorted(Map.Entry.comparingByKey()).toList();
            out.writeInt(journal.size()); for (var entry : journal) { out.writeUTF(entry.getKey()); out.writeLong(entry.getValue().index()); out.write(entry.getValue().digest()); }
            out.writeInt(bytes.length); out.write(hash(bytes)); out.write(bytes);
        });
    }
    private Checkpoint decodeCheckpoint(byte[] bytes) throws IOException {
        if (bytes.length > (long) config.maxSnapshotBytes() * 4) throw new IOException("manifest size limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != FORMAT || !in.readUTF().equals(config.groupId())) throw new IOException("unsupported snapshot schema/group");
            long index = in.readLong(), includedTerm = in.readLong(), generation = in.readLong();
            if (index < 0 || includedTerm < 0 || generation < 0) throw new IOException("invalid snapshot position");
            Set<String> voters = readMembers(in), transitioning = in.readBoolean() ? readMembers(in) : Set.of();
            int count = in.readInt(); if (count < 0 || count > bytes.length / 42) throw new IOException("snapshot journal limit");
            Map<String, Result> journal = new HashMap<>();
            for (int n = 0; n < count; n++) {
                String id = in.readUTF(); long position = in.readLong(); byte[] digest = in.readNBytes(32);
                if (position < 1 || position > index || digest.length != 32 || journal.put(id, new Result(position, digest)) != null)
                    throw new IOException("invalid snapshot journal");
            }
            int size = in.readInt(); if (size < 0 || size > config.maxSnapshotBytes()) throw new IOException("snapshot state size limit");
            byte[] expected = in.readNBytes(32), state = in.readNBytes(size);
            if (state.length != size || !MessageDigest.isEqual(expected, hash(state)) || in.available() != 0) throw new IOException("snapshot state checksum");
            return new Checkpoint(index, includedTerm, generation, voters, transitioning, journal, state, bytes.clone());
        }
    }
    private void installCheckpoint(Checkpoint checkpoint) {
        snapshotIndex = checkpoint.index(); snapshotTerm = checkpoint.term(); snapshotBytes = checkpoint.state(); snapshotManifest = checkpoint.manifest();
        epoch = checkpoint.epoch(); members = checkpoint.members(); joint = checkpoint.joint(); committedJoint = joint;
        requests.clear(); requests.putAll(checkpoint.requests()); log.clear(); commit = snapshotIndex; applied = snapshotIndex;
    }
    private void compact(byte[] checkpoint) throws IOException {
        Checkpoint decoded = decodeCheckpoint(checkpoint);
        List<Entry> suffix = log.stream().filter(entry -> entry.index() > decoded.index()).toList();
        Path temporary = config.directory().resolve("network.wal.next");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            appendRecord(channel, identityRecord());
            appendRecord(channel, encode(out -> { out.writeByte(1); out.writeLong(term); out.writeUTF(votedFor); }));
            appendRecord(channel, encode(out -> { out.writeByte(5); out.write(checkpoint); }));
            for (Entry entry : suffix) appendRecord(channel, encode(out -> { out.writeByte(2); out.writeInt(1); writeEntry(out, entry); }));
            appendRecord(channel, encode(out -> { out.writeByte(3); out.writeLong(Math.max(commit, decoded.index())); }));
            fault("before-snapshot-force"); channel.force(true); fault("after-snapshot-force");
        }
        wal.close();
        try {
            fault("before-snapshot-publish");
            Files.move(temporary, walPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault("after-snapshot-publish");
            try (FileChannel directory = FileChannel.open(config.directory(), StandardOpenOption.READ)) { directory.force(true); }
            fault("after-snapshot-directory-force");
        } catch (IOException error) { fail(error); throw error; }
        finally {
            wal = FileChannel.open(walPath(), StandardOpenOption.READ, StandardOpenOption.WRITE); wal.position(wal.size());
        }
        snapshotManifest = checkpoint;
    }

    private final Map<String, Long> snapshotOffsets = new HashMap<>();
    private boolean sendSnapshot(String peer, long expectedTerm) throws IOException {
        byte[] manifest;
        long index, offset;
        synchronized (mutex) {
            manifest = snapshotManifest; index = snapshotIndex; offset = snapshotOffsets.getOrDefault(peer, 0L);
            if (offset > manifest.length) offset = 0;
        }
        long chunkOffset = offset;
        int size = (int) Math.min(config.maxFrameBytes() / 2, manifest.length - offset);
        byte[] digest = hash(manifest);
        Reply reply = rpc(peer, SNAPSHOT, out -> {
            out.writeLong(expectedTerm); out.writeLong(index); out.writeLong(manifest.length); out.writeLong(chunkOffset); out.write(digest);
            out.writeInt(size); out.write(manifest, (int) chunkOffset, size);
        });
        synchronized (mutex) {
            observeTerm(reply.term()); if (!leader || term != expectedTerm) return false;
            if (reply.success()) {
                snapshotOffsets.remove(peer); match.merge(peer, index, Math::max); next.put(peer, index + 1); return true;
            }
            if (reply.index() >= 0 && reply.index() <= manifest.length) snapshotOffsets.put(peer, reply.index());
            return false;
        }
    }
    private Reply receiveSnapshot(String sender, DataInputStream in) throws IOException {
        long leaderTerm = in.readLong(), index = in.readLong(), size = in.readLong(), offset = in.readLong();
        byte[] digest = in.readNBytes(32); int chunkSize = in.readInt();
        if (size <= 0 || size > (long) config.maxSnapshotBytes() * 4 || offset < 0 || offset > size || chunkSize < 0
                || chunkSize > config.maxFrameBytes() / 2 || offset + chunkSize > size || digest.length != 32) throw new IOException("invalid snapshot chunk");
        byte[] chunk = in.readNBytes(chunkSize); if (chunk.length != chunkSize) throw new EOFException();
        if (in.available() != 0) throw new IOException("trailing snapshot data");
        if (!electorate().contains(sender) || leaderTerm < term) return new Reply(term, false, 0);
        observeTerm(leaderTerm); leader = false; leaderId = sender; resetElection();
        if (index <= commit) return new Reply(term, true, index);
        Path temporary = config.directory().resolve("snapshot-" + HexFormat.of().formatHex(digest) + ".partial");
        try (var partials = Files.newDirectoryStream(config.directory(), "snapshot-*.partial")) {
            for (Path partial : partials) if (!partial.equals(temporary)) Files.deleteIfExists(partial);
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long received = channel.size();
            if (offset != received) return new Reply(term, false, received);
            channel.position(offset); ByteBuffer buffer = ByteBuffer.wrap(chunk); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            received += chunkSize;
            if (received < size) return new Reply(term, false, received);
        }
        byte[] manifest = Files.readAllBytes(temporary);
        if (!MessageDigest.isEqual(hash(manifest), digest)) throw new IOException("snapshot manifest checksum");
        Checkpoint checkpoint = decodeCheckpoint(manifest);
        if (checkpoint.index() != index || checkpoint.term() > term) throw new IOException("snapshot term/index mismatch");
        // Publication is durable before application state replacement; restart restores old or new WAL.
        log.clear();
        try { compact(manifest); } catch (IOException error) { fail(error); throw error; }
        installCheckpoint(checkpoint);
        try { fault("before-snapshot-state-restore"); machine.restore(snapshotBytes.clone()); machine.appliedThrough(applied); }
        catch (RuntimeException error) { fail(new IOException("snapshot restore failed", error)); throw failed; }
        Files.deleteIfExists(temporary); mutex.notifyAll(); return new Reply(term, true, index);
    }

    /**
     * Stops background work and releases sockets and durable directory ownership.
     * @throws IOException if the durable file cannot be closed
     */
    @Override public void close() throws IOException {
        synchronized (mutex) {
            if (closed) return; closed = true; leader = false; mutex.notifyAll();
            clock.shutdownNow(); outbound.shutdownNow(); handlers.shutdownNow();
            if (server != null) server.close(); wal.close(); directoryLock.release(); lockChannel.close();
        }
    }
}
