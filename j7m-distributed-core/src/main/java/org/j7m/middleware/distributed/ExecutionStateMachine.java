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
import java.security.*;
import java.util.*;

/**
 * Reusable deterministic execution partition: durable jobs, saga intents/compensations and RPC claims
 * share atomic protected-map effects, durable results and events. External effects never execute in
 * apply. Only committed healthy host time expires ownership; UNKNOWN work requires explicit privileged
 * reconciliation. Retained identities are bounded tombstones, never silently reused after expiry.
 * All callbacks serialize access; snapshots include every recovery and trace-context dependency.
 */
public final class ExecutionStateMachine implements TransferableResource {
    private static final int LIMIT = 10000;
    private static final long BYTE_LIMIT = 16777216;
    private static final Set<String> ADMIN = Set.of("E_TIME", "S_RECONCILE", "S_REQUEST_COMPENSATION", "R_RECONCILE");
    private String partition;
    private final java.util.function.Consumer<String> commitProbe;
    private long epoch, index, time, fence, retainedBytes;
    private Map<String, Long> rpcReplays = new TreeMap<>();
    private Map<String, Run> runs = new TreeMap<>();
    private Map<String, Rpc> rpcs = new TreeMap<>();
    private Map<String, ReplicatedResources.Value> values = new TreeMap<>();
    private Map<String, Saved> results = new TreeMap<>();
    private EventJournal journal = new EventJournal(10000, 16777216);
    private record Saved(String hash, ExecutionResult result, long expires) { }
    private record Rpc(String hash, String status, String result, String error, long expires, long fence, String context) { }
    private record Step(String name, String key, String compensation, boolean idempotent, String status, String result, int attempts, long due, boolean terminal) { }
    private static final class Run {
        String kind, input, serialGroup = "", checkpoint = "", result = "", status = "READY", owner = "", currentStep = "", context;
        long fence, expires, version, backoff;
        int maxAttempts;
        boolean compensationRequested;
        List<Step> steps = new ArrayList<>();
        Run copy() { Run copy = new Run(); copy.kind = kind; copy.input = input; copy.serialGroup = serialGroup; copy.checkpoint = checkpoint; copy.result = result; copy.status = status; copy.owner = owner; copy.context = context; copy.currentStep = currentStep; copy.fence = fence; copy.expires = expires; copy.version = version; copy.backoff = backoff; copy.maxAttempts = maxAttempts; copy.compensationRequested = compensationRequested; copy.steps.addAll(steps); return copy; }
    }
    /**
     * Creates an empty execution partition with bounded retained identities.
     * @param partition stable topology partition identity
     */
    public ExecutionStateMachine(String partition) { this(partition, ignored -> { }); }
    ExecutionStateMachine(String partition, java.util.function.Consumer<String> commitProbe) { id(partition); this.partition = partition; this.commitProbe = Objects.requireNonNull(commitProbe); }
    /**
     * Validates a version-one execution command before admission.
     * @param c immutable resource command
     * @throws IllegalArgumentException for unknown operation, malformed arity or bounds
     */
    public static void validate(ResourceCommand c) {
        id(c.scope()); id(c.requestId()); List<String> a = c.arguments();
        for (String value : a) if (value.length() > 16384 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Execution argument bounds");
        int count = switch (c.operation()) {
            case "E_TIME" -> 1; case "E_REFRESH" -> 0;
            case "E_SUBSCRIBE" -> 6; case "E_ACK" -> 5; case "E_RESUME" -> 2;
            case "J_SUBMIT" -> c.arguments().size()==4 ? 4 : 3; case "S_START" -> 5;
            case "J_CLAIM", "S_CLAIM" -> 3;
            case "J_CHECKPOINT", "J_RENEW" -> 4;
            case "J_COMPLETE" -> -4;
            case "J_CANCEL" -> 1;
            case "S_STEP" -> 8;
            case "S_STEP_COMPLETE" -> -5;
            case "S_COMPENSATE" -> 5;
            case "S_COMPENSATION_RESULT" -> 6;
            case "S_RECONCILE" -> 5;
            case "S_REQUEST_COMPENSATION" -> 2;
            case "S_STEP_RESOLVE" -> 6;
            case "S_VERIFY" -> 3;
            case "R_INVOKE" -> -7;
            case "R_CLAIM" -> a.size() == 5 ? 5 : 6;
            case "R_COMPLETE" -> -6;
            case "R_UNKNOWN" -> 3;
            case "R_RECONCILE" -> -6;
            default -> throw new IllegalArgumentException("Unknown execution operation");
        };
        int minimum = Math.abs(count);
        if (count >= 0 && a.size() != count || count < 0 && (a.size() < minimum || (a.size() - minimum) % 3 != 0)) throw new IllegalArgumentException("Execution argument count");
        if (c.operation().equals("E_REFRESH")) return;
        if (c.operation().equals("E_TIME")) { number(a.getFirst(), 0, Long.MAX_VALUE - 86400000); return; }
        id(a.getFirst());
        switch (c.operation()) {
            case "E_SUBSCRIBE" -> { id(a.get(2)); number(a.get(3), 0, Long.MAX_VALUE); number(a.get(4), 0, Long.MAX_VALUE); number(a.get(5), 1, 86400000); }
            case "E_ACK" -> { id(a.get(1)); number(a.get(2), 0, Long.MAX_VALUE); number(a.get(3), 0, Long.MAX_VALUE); number(a.get(4), 1, 86400000); }
            case "E_RESUME" -> number(a.get(1), 1, 86400000);
            case "J_SUBMIT" -> { carrier(a.get(2)); if(a.size()==4)id(a.get(3)); }
            case "S_START" -> { number(a.get(2), 1, 1000); number(a.get(3), 1, 3600000); carrier(a.get(4)); }
            case "J_CLAIM", "S_CLAIM" -> { id(a.get(1)); number(a.get(2), 1, 86400000); }
            case "J_RENEW" -> { id(a.get(1)); number(a.get(2), 1, Long.MAX_VALUE); number(a.get(3), 1, 86400000); }
            case "J_CHECKPOINT", "J_COMPLETE", "S_STEP", "S_STEP_COMPLETE", "S_COMPENSATE", "S_COMPENSATION_RESULT", "S_STEP_RESOLVE", "S_VERIFY" -> { id(a.get(1)); number(a.get(2), 1, Long.MAX_VALUE); }
            case "R_INVOKE", "R_CLAIM" -> { hash(a.get(1)); number(a.get(2), 1, 86400000); }
            case "R_COMPLETE", "R_UNKNOWN", "R_RECONCILE" -> { hash(a.get(1)); number(a.get(2), 1, Long.MAX_VALUE); }
            default -> { }
        }
        if (c.operation().equals("S_REQUEST_COMPENSATION")) number(a.get(1), 0, Long.MAX_VALUE);
        if (c.operation().equals("S_STEP_RESOLVE")) { id(a.get(3)); if (!Set.of("FAILED_NO_EFFECT", "UNKNOWN").contains(a.get(4))) throw new IllegalArgumentException("Saga step resolution"); }
        if (c.operation().equals("S_STEP")) { id(a.get(3)); id(a.get(4)); bool(a.get(6)); bool(a.get(7)); }
        if (c.operation().equals("S_COMPENSATE")) { id(a.get(3)); bool(a.get(4)); }
        if (c.operation().equals("S_COMPENSATION_RESULT")) { id(a.get(3)); bool(a.get(4)); }
        if (c.operation().equals("S_RECONCILE")) { id(a.get(1)); number(a.get(2), 0, Long.MAX_VALUE); bool(a.get(3)); }
        if (c.operation().equals("R_INVOKE")) { carrier(a.get(5)); bool(a.get(6)); }
        if (c.operation().equals("R_CLAIM")) { bool(a.get(3)); carrier(a.get(4)); }
        if (c.operation().equals("R_COMPLETE") || c.operation().equals("R_RECONCILE")) bool(a.get(5));
        if (count < 0) for (int i = minimum; i < a.size(); i += 3) { id(a.get(i)); number(a.get(i + 1), 0, Long.MAX_VALUE); }
        if (c.encode().length > PartitionStateMachine.MAX_COMMAND) throw new IllegalArgumentException("Execution command size");
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validate(byte[] bytes, String scope, boolean administrative) {
        ResourceCommand c = ResourceCommand.decode(bytes); validate(c);
        if (!c.scope().equals(scope)) throw new SecurityException("Execution tenant denied");
        if (ADMIN.contains(c.operation()) && !administrative) throw new SecurityException("Execution reconciliation permission required");
        if (identityConflict(bytes)) throw new IllegalArgumentException("Execution request identity conflict");
        if (!timeControl(bytes) && !c.operation().equals("E_REFRESH") && !results.containsKey(key(scope, c.requestId())) && (results.size() >= LIMIT || retainedBytes + bytes.length > BYTE_LIMIT)) throw new IllegalStateException("OVERLOADED");
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized boolean identityConflict(byte[] bytes) { ResourceCommand c = ResourceCommand.decode(bytes); Saved saved = results.get(key(c.scope(), c.requestId())); return !timeControl(bytes) && !c.operation().equals("E_REFRESH") && saved != null && !saved.hash.equals(commandDigest(bytes)); }
    /**
     * {@inheritDoc}
     */
    @Override public void validateReplayCommand(byte[] bytes) { validate(ResourceCommand.decode(bytes)); }
    /**
     * {@inheritDoc}
     */
    @Override public String requestIdentity(byte[] bytes) { return ResourceCommand.decode(bytes).requestId(); }
    /**
     * {@inheritDoc}
     */
    @Override public boolean timeControl(byte[] bytes) { return ResourceCommand.decode(bytes).operation().equals("E_TIME"); }
    /**
     * {@inheritDoc}
     */
    @Override public boolean timeSensitive(byte[] bytes) { return !timeControl(bytes); }
    /**
     * {@inheritDoc}
     */
    @Override public byte[] timeCommand(long millis) { return new ResourceCommand("E_TIME", "execution-clock", "clock", List.of(Long.toString(millis))).encode(); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void ownershipChanged(String partition, long epoch) { if (!this.partition.equals(partition) || epoch < this.epoch) throw new IllegalArgumentException("Execution ownership"); this.epoch = epoch; }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized boolean transferReady() { return true; }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void appliedThrough(long index) { if (index < this.index) throw new IllegalArgumentException("Execution watermark"); this.index = index; }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void apply(long index, byte[] bytes) {
        ResourceCommand c = ResourceCommand.decode(bytes); validate(c);
        if (index <= this.index) throw new IllegalArgumentException("Execution index regression");
        if (c.operation().equals("E_REFRESH")) { this.index = index; return; }
        if (timeControl(bytes)) { time = Math.max(time, Long.parseLong(c.arguments().getFirst())); this.index = index; return; }
        String identity = key(c.scope(), c.requestId()); Saved prior = results.get(identity);
        if (prior != null) { if (!prior.hash.equals(commandDigest(bytes))) throw new IllegalArgumentException("Execution request conflict"); this.index = index; return; }
        if (results.size() >= LIMIT || retainedBytes + bytes.length > BYTE_LIMIT) { this.index = index; return; }
        ExecutionStateMachine next = copy(); ExecutionResult result;
        try { result = next.execute(c, index); }
        catch (Rejected rejected) { next = copy(); result = new ExecutionResult(rejected.getMessage(), Map.of()); }
        long retainedCost = bytes.length + result.encode().length + identity.length() * 2L + 256;
        if (retainedBytes + retainedCost > BYTE_LIMIT) { this.index = index; return; }
        next.index = index; next.retainedBytes += retainedCost; next.results.put(identity, new Saved(commandDigest(bytes), result, c.operation().startsWith("R_") && next.rpcs.containsKey(key(c.scope(), c.arguments().getFirst())) ? next.rpcs.get(key(c.scope(), c.arguments().getFirst())).expires : Long.MAX_VALUE));
        if (!Set.of("E_SUBSCRIBE", "E_ACK", "E_RESUME").contains(c.operation())) next.journal.append(partition, epoch, c.scope(), c.operation(), eventResult(c, result).encode());
        if (c.operation().equals("S_RECONCILE") && Set.of("RUNNING", "COMPLETING", "COMPLETED", "COMPENSATING", "COMPENSATED").contains(result.status())) {
            var audit = new ExecutionResult(result.status(), Map.of("entity", c.arguments().get(0), "step", c.arguments().get(1),
                    "observedVersion", c.arguments().get(2), "committedVersion", result.fields().get("version"),
                    "decision", Boolean.parseBoolean(c.arguments().get(3)) ? "SUCCEEDED" : "FAILED_NO_EFFECT"));
            next.journal.append(partition, epoch, c.scope(), "S_RECONCILE_AUDIT", audit.encode());
        }
        install(next); commitProbe.accept(c.requestId());
    }
    private ExecutionResult execute(ResourceCommand c, long index) {
        List<String> a = c.arguments(); String id = key(c.scope(), a.getFirst()); String op = c.operation();
        if (op.startsWith("E_")) {
            try {
                if (op.equals("E_SUBSCRIBE")) { reject(!a.get(2).equals(partition), "CONFLICT"); journal.subscribe(a.get(0), c.scope(), a.get(1), new EventJournal.Cursor(a.get(2), Long.parseLong(a.get(3)), Long.parseLong(a.get(4))), Long.parseLong(a.get(5)), time); }
                else if (op.equals("E_ACK")) { reject(!a.get(1).equals(partition), "CONFLICT"); journal.ack(a.get(0), c.scope(), new EventJournal.Cursor(a.get(1), Long.parseLong(a.get(2)), Long.parseLong(a.get(3))), Long.parseLong(a.get(4)), time); }
                else journal.resume(a.get(0), c.scope(), Long.parseLong(a.get(1)), time);
                return new ExecutionResult("OK", Map.of());
            } catch (IllegalArgumentException | IllegalStateException e) { throw new Rejected(e.getMessage()); }
        }
        if (op.startsWith("R_")) return rpc(c, id, index);
        if (op.equals("J_SUBMIT") || op.equals("S_START")) {
            reject(runs.containsKey(id), "CONFLICT"); reject(runs.size() >= LIMIT, "OVERLOADED"); Run run = new Run(); run.kind = op.equals("J_SUBMIT") ? "JOB" : "SAGA"; run.input = a.get(1); run.context = a.get(op.equals("J_SUBMIT")?2:4); run.serialGroup=op.equals("J_SUBMIT")&&a.size()==4?a.get(3):""; run.version = index;
            if (run.kind.equals("SAGA")) { run.maxAttempts = Integer.parseInt(a.get(2)); run.backoff = Long.parseLong(a.get(3)); }
            runs.put(id, run); return result(run);
        }
        Run old = runs.get(id); reject(old == null, "NOT_FOUND");
        reject(op.startsWith("J_") && !old.kind.equals("JOB") || op.startsWith("S_") && !old.kind.equals("SAGA"), "WRONG_KIND");
        Run run = old.copy(); runs.put(id, run);
        if (op.equals("J_CANCEL")) { reject(run.status.equals("COMPLETED"), "ALREADY_COMPLETED"); run.status = "CANCELLED"; run.owner = ""; run.fence = ++fence; run.version = index; return result(run); }
        if (op.equals("S_REQUEST_COMPENSATION")) {
            reject(run.version != Long.parseLong(a.get(1)) || run.compensationRequested, "CONFLICT");
            run.compensationRequested = true; run.owner = ""; run.expires = 0; run.fence = ++fence;
            run.status = run.status.equals("UNKNOWN") || run.steps.stream().anyMatch(step -> Set.of("ACTIVE", "COMPENSATING").contains(step.status))
                    ? "UNKNOWN" : compensationStatus(run);
            run.version = index; return result(run);
        }
        if (op.endsWith("CLAIM")) {
            reject(Set.of("COMPLETED", "CANCELLED", "COMPENSATED").contains(run.status), run.status);
            reject(run.expires > time && !run.owner.isEmpty(), "OWNED");
            reject(serialBlocked(c.scope(),id,run,time),"OWNED");
            run.owner = a.get(1); run.fence = ++fence; run.expires = time + Long.parseLong(a.get(2)); run.version = index;
            boolean uncertain = run.steps.stream().anyMatch(step -> (step.status.equals("ACTIVE") || step.status.equals("COMPENSATING")) && !step.idempotent);
            run.status = uncertain || run.status.equals("UNKNOWN") ? "UNKNOWN" : run.compensationRequested ? compensationStatus(run) : run.status.equals("COMPLETING") ? "COMPLETING" : "RUNNING"; return result(run);
        }
        if (op.equals("S_RECONCILE")) {
            reject(!run.status.equals("UNKNOWN") || run.version != Long.parseLong(a.get(2)), "CONFLICT"); int position = step(run, a.get(1)); Step previous = run.steps.get(position); reject(!previous.status.equals("ACTIVE") && !previous.status.equals("COMPENSATING") && !previous.status.equals("UNKNOWN"), "CONFLICT");
            run.currentStep = a.get(1);
            boolean success = bool(a.get(3)); boolean compensation = previous.status.equals("COMPENSATING");
            String reconciled = compensation ? success ? "COMPENSATED" : previous.attempts >= run.maxAttempts ? "EXHAUSTED" : "RETRY" : success ? "DONE" : "FAILED";
            long due = compensation && !success ? time + retryBackoff(run, previous.attempts) : 0;
            run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, reconciled, a.get(4), previous.attempts, due, previous.terminal));
            run.status = compensation ? run.steps.stream().allMatch(step -> Set.of("COMPENSATED", "FAILED").contains(step.status)) ? "COMPENSATED" : "COMPENSATING" : success && previous.terminal ? "COMPLETED" : "RUNNING";
            if (!compensation && success && previous.terminal && !run.compensationRequested) run.result = a.get(4);
            if (!compensation && !success) run.compensationRequested = true;
            if (run.compensationRequested) run.status = compensationStatus(run);
            run.version = index; return result(run);
        }
        if (op.startsWith("J_")) reject(!run.status.equals("RUNNING"), "CONFLICT");
        owner(run, a.get(1), Long.parseLong(a.get(2))); reject(run.status.equals("UNKNOWN"), "UNKNOWN");
        if (op.equals("S_VERIFY")) return new ExecutionResult(run.status,Map.of("owner",run.owner,"fence",Long.toString(run.fence),"expires",Long.toString(run.expires),"version",Long.toString(run.version)));
        switch (op) {
            case "J_RENEW" -> run.expires = time + Long.parseLong(a.get(3));
            case "J_CHECKPOINT" -> run.checkpoint = a.get(3);
            case "J_COMPLETE" -> { effects(c.scope(), a, 4, index); run.result = a.get(3); run.status = "COMPLETED"; }
            case "S_STEP_RESOLVE" -> {
                int position = step(run, a.get(3)); Step previous = run.steps.get(position);
                reject(!Set.of("ACTIVE", "COMPENSATING").contains(previous.status), "CONFLICT"); run.currentStep = previous.name;
                if (a.get(4).equals("UNKNOWN")) {
                    // Preserve ACTIVE versus COMPENSATING: reconciliation must know which effect is uncertain.
                    run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, previous.status,
                            a.get(5), previous.attempts, previous.due, previous.terminal));
                    run.status = "UNKNOWN";
                } else if (previous.status.equals("COMPENSATING")) {
                    String state = previous.attempts >= run.maxAttempts ? "EXHAUSTED" : "RETRY";
                    run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, state,
                            a.get(5), previous.attempts, time + retryBackoff(run, previous.attempts), previous.terminal));
                } else {
                    run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, "FAILED",
                            a.get(5), previous.attempts, 0, previous.terminal));
                    run.compensationRequested = true; run.status = compensationStatus(run);
                }
            }
            case "S_STEP" -> {
                reject(run.compensationRequested, "CONFLICT");
                reject(!run.kind.equals("SAGA") || run.status.equals("COMPENSATING"), "CONFLICT");
                if (run.steps.stream().anyMatch(step -> step.status.equals("ACTIVE") || step.status.equals("COMPENSATING"))) throw new Rejected("IN_PROGRESS");
                reject(run.steps.size() >= 256, "OVERLOADED"); reject(run.steps.stream().anyMatch(step -> step.name.equals(a.get(3))), "STEP_EXISTS");
                run.currentStep = a.get(3);
                run.steps.add(new Step(a.get(3), a.get(4), a.get(5), bool(a.get(6)), "ACTIVE", "", 0, 0, bool(a.get(7))));
                run.status = bool(a.get(7)) ? "COMPLETING" : "RUNNING";
            }
            case "S_STEP_COMPLETE" -> {
                run.currentStep = a.get(3); int position = step(run, a.get(3)); Step previous = run.steps.get(position); reject(!previous.status.equals("ACTIVE"), "CONFLICT"); effects(c.scope(), a, 5, index);
                run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, "DONE", a.get(4), 0, 0, previous.terminal)); if (previous.terminal) { run.status = "COMPLETED"; run.result = a.get(4); }
            }
            case "S_COMPENSATE" -> {
                run.currentStep = a.get(3); int position = step(run, a.get(3)); Step previous = run.steps.get(position);
                reject(previous.attempts >= run.maxAttempts, "RETRY_EXHAUSTED"); reject(!Set.of("DONE", "RETRY").contains(previous.status), "CONFLICT"); reject(previous.due > time, "BACKOFF");
                for (int i = position + 1; i < run.steps.size(); i++) reject(!Set.of("COMPENSATED", "FAILED").contains(run.steps.get(i).status), "COMPENSATION_ORDER");
                run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, bool(a.get(4)), "COMPENSATING", previous.result, previous.attempts + 1, 0, previous.terminal)); run.compensationRequested = true; run.status = "COMPENSATING";
            }
            case "S_COMPENSATION_RESULT" -> {
                run.currentStep = a.get(3); int position = step(run, a.get(3)); Step previous = run.steps.get(position); reject(!previous.status.equals("COMPENSATING"), "CONFLICT"); boolean success = bool(a.get(4));
                long backoff = retryBackoff(run, previous.attempts);
                String state = success ? "COMPENSATED" : previous.attempts >= run.maxAttempts ? "EXHAUSTED" : "RETRY";
                run.steps.set(position, new Step(previous.name, previous.key, previous.compensation, previous.idempotent, state, a.get(5), previous.attempts, time + backoff, previous.terminal));
                if (run.steps.stream().allMatch(step -> Set.of("COMPENSATED", "FAILED").contains(step.status))) run.status = "COMPENSATED";
            }
            default -> throw new Rejected("CONFLICT");
        }
        run.version = index;
        return op.equals("J_RENEW") ? new ExecutionResult("RUNNING",Map.of("owner",run.owner,"fence",Long.toString(run.fence),"expires",Long.toString(run.expires),"version",Long.toString(run.version))) : result(run);
    }
    private ExecutionResult rpc(ResourceCommand c, String id, long index) {
        var a = c.arguments(); Rpc previous = rpcs.get(id); String op = c.operation();
        if (previous != null) {
            reject(!previous.hash.equals(a.get(1)), "CONFLICT"); reject(previous.expires <= time, "EXPIRED");
            if (op.equals("R_INVOKE")) { rpcReplays.merge(c.scope(), 1L, Long::sum); return rpcResult(previous); }
            if (op.equals("R_CLAIM")) { rpcReplays.merge(c.scope(), 1L, Long::sum); ExecutionResult existing = rpcResult(previous); Map<String, String> fields = new TreeMap<>(existing.fields()); fields.put("acquired", "false"); return new ExecutionResult(previous.status.equals("EXTERNAL_IN_PROGRESS") ? "UNKNOWN" : existing.status(), fields); }
        }
        if (op.equals("R_CLAIM") || op.equals("R_INVOKE")) {
            reject(rpcs.size() >= LIMIT, "OVERLOADED"); long expiry = time + Long.parseLong(a.get(2)); long token = ++fence;
            if (op.equals("R_CLAIM")) { Rpc created = new Rpc(a.get(1), bool(a.get(3)) ? "EXTERNAL_IN_PROGRESS" : "IN_PROGRESS", "", "", expiry, token, a.get(4)); rpcs.put(id, created); Map<String, String> fields = new TreeMap<>(rpcResult(created).fields()); fields.put("acquired", "true"); fields.put("claimIdentity", c.requestId()); fields.put("claimAttempt", a.size() == 6 ? a.get(5) : ""); fields.put("claimRequest", a.getFirst()); return new ExecutionResult(created.status, fields); }
            reject(bool(a.get(6)), "EXTERNAL_REQUIRES_CLAIM"); effects(c.scope(), a, 7, index);
            Rpc created = new Rpc(a.get(1), a.get(4).isEmpty() ? "COMPLETED" : "ERROR", a.get(3), a.get(4), expiry, token, a.get(5)); rpcs.put(id, created); return rpcResult(created);
        }
        reject(previous == null, "NOT_FOUND"); reject(previous.fence != Long.parseLong(a.get(2)), "FENCED");
        if (op.equals("R_UNKNOWN")) { reject(!previous.status.equals("EXTERNAL_IN_PROGRESS"), "CONFLICT"); Rpc unknown = new Rpc(previous.hash, "UNKNOWN", "", "", previous.expires, previous.fence, previous.context); rpcs.put(id, unknown); return rpcResult(unknown); }
        boolean reconciliation = op.equals("R_RECONCILE"); reject(previous.status.equals("UNKNOWN") && !reconciliation, "UNKNOWN");
        reject(!Set.of("IN_PROGRESS", "EXTERNAL_IN_PROGRESS", "UNKNOWN").contains(previous.status), "ALREADY_COMPLETED"); effects(c.scope(), a, 6, index);
        Rpc completed = new Rpc(previous.hash, a.get(4).isEmpty() ? "COMPLETED" : "ERROR", a.get(3), a.get(4), previous.expires, previous.fence, previous.context); rpcs.put(id, completed); return rpcResult(completed);
    }
    private void effects(String scope, List<String> a, int offset, long index) {
        reject(values.size() + (a.size() - offset) / 3 > LIMIT, "OVERLOADED"); Set<String> seen = new HashSet<>();
        for (int i = offset; i < a.size(); i += 3) { String key = key(scope, a.get(i)); reject(!seen.add(key), "CONFLICT"); ReplicatedResources.Value previous = values.get(key); reject((previous == null ? 0 : previous.version()) != Long.parseLong(a.get(i + 1)), "CONFLICT"); }
        for (int i = offset; i < a.size(); i += 3) values.put(key(scope, a.get(i)), new ReplicatedResources.Value(a.get(i + 2), index));
    }
    private static long retryBackoff(Run run, int attempts) { return Math.min(86400000, run.backoff * (1L << Math.min(20, Math.max(0, attempts - 1)))); }
    private void owner(Run run, String owner, long token) { reject(!run.owner.equals(owner) || run.fence != token || run.expires <= time, "FENCED"); reject(Set.of("CANCELLED", "COMPLETED", "COMPENSATED").contains(run.status), run.status); }
    private static int step(Run run, String name) { for (int i = 0; i < run.steps.size(); i++) if (run.steps.get(i).name.equals(name)) return i; throw new Rejected("NOT_FOUND"); }
    private ExecutionResult claimReceipt(ExecutionResult saved, ResourceCommand query) {
        if (!saved.fields().containsKey("acquired")) return saved;
        boolean permitted = saved.fields().get("acquired").equals("true") && query.arguments().size() == 2
                && query.arguments().get(1).equals(saved.fields().get("claimAttempt"));
        Map<String, String> fields = new TreeMap<>(saved.fields()); String status = saved.status();
        if (!permitted) {
            String invocation = fields.get("claimRequest"); Rpc current = invocation == null ? null : rpcs.get(key(query.scope(), invocation));
            if (current != null) { fields.putAll(rpcResult(current).fields()); status = current.status; }
            if (status.equals("EXTERNAL_IN_PROGRESS")) status = "UNKNOWN";
            fields.put("acquired", "false");
        }
        fields.remove("claimAttempt"); fields.remove("claimRequest"); return new ExecutionResult(status, fields);
    }
    private static ExecutionResult eventResult(ResourceCommand command, ExecutionResult result) {
        Map<String, String> fields = new TreeMap<>(result.fields()); fields.remove("claimAttempt"); fields.remove("claimRequest"); fields.put("entity", command.arguments().getFirst()); return new ExecutionResult(result.status(), fields);
    }
    private static ExecutionResult stepView(Run run, Step step) {
        return new ExecutionResult(step.status, Map.of("step", step.name, "idempotencyKey", step.key, "compensation", step.compensation, "idempotent", Boolean.toString(step.idempotent), "stepResult", step.result, "attempts", Integer.toString(step.attempts), "due", Long.toString(step.due), "terminal", Boolean.toString(step.terminal), "version", Long.toString(run.version), "runStatus", run.status));
    }
    private static String compensationStatus(Run run) {
        return run.steps.stream().allMatch(step -> Set.of("FAILED", "COMPENSATED").contains(step.status)) ? "COMPENSATED" : "COMPENSATING";
    }
    private static ExecutionResult result(Run run) {
        Map<String, String> fields = new TreeMap<>(); fields.put("compensationRequested", Boolean.toString(run.compensationRequested)); fields.put("input", run.input); fields.put("checkpoint", run.checkpoint); fields.put("result", run.result); fields.put("owner", run.owner); fields.put("fence", Long.toString(run.fence)); fields.put("expires", Long.toString(run.expires)); fields.put("version", Long.toString(run.version));
        if (!run.steps.isEmpty()) { Step step = run.steps.stream().filter(selected -> selected.name.equals(run.currentStep)).findFirst().orElse(run.steps.getLast()); fields.put("step", step.name); fields.put("stepStatus", step.status); fields.put("stepResult", step.result); fields.put("idempotencyKey", step.key); fields.put("compensation", step.compensation); fields.put("attempts", Integer.toString(step.attempts)); fields.put("due", Long.toString(step.due)); }
        contextFields(fields, run.context); return new ExecutionResult(run.status, fields);
    }
    private static ExecutionResult rpcResult(Rpc rpc) { Map<String, String> fields = new TreeMap<>(); fields.put("result", rpc.result); fields.put("error", rpc.error); fields.put("fence", Long.toString(rpc.fence)); fields.put("expires", Long.toString(rpc.expires)); contextFields(fields, rpc.context); return new ExecutionResult(rpc.status, fields); }
    /**
     * {@inheritDoc}
     */
    @Override public void validateQuery(byte[] bytes, String scope) {
        ResourceCommand c = ResourceCommand.decode(bytes); if (!c.scope().equals(scope)) throw new SecurityException("Execution query scope");
        int count = switch (c.operation()) { case "E_STATS", "E_SNAPSHOT_TAIL" -> 0; case "E_POLL", "E_STEP" -> 2; case "E_STEPS" -> 4; case "E_RUNS" -> 5; case "E_RESULT" -> c.arguments().size() == 2 ? 2 : 1; case "E_RUN", "E_RPC", "E_VALUE" -> 1; default -> throw new IllegalArgumentException("Execution query"); };
        if (c.arguments().size() != count) throw new IllegalArgumentException("Execution query arguments");
        if (c.operation().equals("E_RUNS")) {
            for (int i = 0; i < 3; i++) { String value = c.arguments().get(i); if (value.length() > 128 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Execution scan identity"); }
            number(c.arguments().get(3), 1, 256); bool(c.arguments().get(4)); return;
        }
        if (count > 0) id(c.arguments().getFirst()); if (c.operation().equals("E_RESULT") && count == 2) id(c.arguments().get(1)); if (c.operation().equals("E_POLL")) number(c.arguments().get(1), 1, 256);
        if (c.operation().equals("E_STEP")) id(c.arguments().get(1));
        if (c.operation().equals("E_STEPS")) { number(c.arguments().get(1), 0, 256); number(c.arguments().get(2), 1, 8); number(c.arguments().get(3), 0, Long.MAX_VALUE); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] query(byte[] bytes) {
        ResourceCommand c = ResourceCommand.decode(bytes); validateQuery(bytes, c.scope()); String identity = c.arguments().isEmpty() ? "" : key(c.scope(), c.arguments().getFirst()); ExecutionResult result;
        try {
            if (c.operation().equals("E_RUNS")) return runPage(c);
            if (c.operation().equals("E_STEPS")) {
                Run run = runs.get(identity); if (run == null) return ResourceWire.encode(out -> { out.writeInt(2); out.writeLong(0); out.writeInt(0); out.writeInt(0); });
                long expectedVersion = Long.parseLong(c.arguments().get(3)); int offset = Integer.parseInt(c.arguments().get(1)), maximum = Integer.parseInt(c.arguments().get(2));
                if (expectedVersion != 0 && expectedVersion != run.version) return ResourceWire.encode(out -> { out.writeInt(1); out.writeLong(run.version); out.writeInt(0); out.writeInt(0); });
                return ResourceWire.encode(out -> { out.writeInt(0); out.writeLong(run.version); out.writeInt(run.steps.size()); int end = Math.min(run.steps.size(), offset + maximum); out.writeInt(Math.max(0, end - offset)); for (int i = offset; i < end; i++) blob(out, stepView(run, run.steps.get(i)).encode()); });
            }
            if (c.operation().equals("E_POLL")) {
                try { EventJournal.Page page = journal.poll(c.arguments().getFirst(), c.scope(), Integer.parseInt(c.arguments().get(1)), time); return ResourceWire.encode(out -> { out.writeInt(0); ResourceWire.page(out, page); }); }
                catch (IllegalStateException expired) { return ResourceWire.encode(out -> { out.writeInt(1); ResourceWire.text(out, expired.getMessage()); }); }
            }
            if (c.operation().equals("E_SNAPSHOT_TAIL")) {
                return ResourceWire.encode(out -> {
                    out.writeInt(1); out.writeLong(index); ResourceWire.cursor(out, journal.latest(partition));
                    String prefix = c.scope() + '\0'; List<Map.Entry<String, Run>> selected = runs.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix)).toList();
                    out.writeInt(selected.size()); for (var entry : selected) { ResourceWire.text(out, entry.getKey().substring(prefix.length())); blob(out, result(entry.getValue()).encode()); }
                });
            }
        } catch (IOException e) { throw new IllegalStateException("Execution query bounds", e); }
        switch (c.operation()) {
            case "E_STATS" -> {
                String prefix = c.scope() + '\0'; var selected = runs.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
                result = new ExecutionResult("OK", Map.of("jobs", Long.toString(selected.stream().filter(run -> run.kind.equals("JOB")).count()), "sagas", Long.toString(selected.stream().filter(run -> run.kind.equals("SAGA")).count()), "unknown", Long.toString(selected.stream().filter(run -> run.status.equals("UNKNOWN")).count()), "active", Long.toString(selected.stream().filter(run -> Set.of("RUNNING", "COMPLETING", "COMPENSATING").contains(run.status)).count()), "rpcReplays", Long.toString(rpcReplays.getOrDefault(c.scope(), 0L)), "retainedRequests", Long.toString(results.keySet().stream().filter(key -> key.startsWith(prefix)).count())));
            }
            case "E_RESULT" -> {
                Saved saved = results.get(identity); result = saved == null ? new ExecutionResult("OVERLOADED", Map.of()) : saved.expires <= time ? new ExecutionResult("EXPIRED", Map.of()) : claimReceipt(saved.result, c);
            }
            case "E_STEP" -> { Run run = runs.get(identity); result = run == null ? new ExecutionResult("NOT_FOUND", Map.of()) : run.steps.stream().filter(step -> step.name.equals(c.arguments().get(1))).map(step -> stepView(run, step)).findFirst().orElse(new ExecutionResult("NOT_FOUND", Map.of())); }
            case "E_RUN" -> result = runs.containsKey(identity) ? result(runs.get(identity)) : new ExecutionResult("NOT_FOUND", Map.of());
            case "E_RPC" -> { Rpc rpc = rpcs.get(identity); result = rpc == null ? new ExecutionResult("NOT_FOUND", Map.of()) : rpc.expires <= time ? new ExecutionResult("EXPIRED", Map.of()) : rpc.status.equals("EXTERNAL_IN_PROGRESS") ? new ExecutionResult("UNKNOWN", rpcResult(rpc).fields()) : rpcResult(rpc); }
            default -> { var value = values.get(identity); result = value == null ? new ExecutionResult("NOT_FOUND", Map.of()) : new ExecutionResult("OK", Map.of("value", value.value(), "version", Long.toString(value.version()))); }
        }
        return result.encode();
    }
    private ExecutionStateMachine copy() { ExecutionStateMachine copy = new ExecutionStateMachine(partition); copy.epoch = epoch; copy.index = index; copy.time = time; copy.fence = fence; copy.retainedBytes = retainedBytes; copy.rpcReplays.putAll(rpcReplays); copy.runs.putAll(runs); copy.rpcs.putAll(rpcs); copy.values.putAll(values); copy.results.putAll(results); copy.journal = journal.copy(); return copy; }
    record PushCandidate(String id, boolean existing, long fence) { }
    synchronized PushCandidate pushCandidate(String scope,String prefix,String kind,String owner,long now,Set<String> sent) {
        String namespace=scope+'\0'; int active=0;
        for(var entry:runs.entrySet()) {
            Run run=entry.getValue(); if(!entry.getKey().startsWith(namespace+prefix)||!run.kind.equals(kind))continue;
            String id=entry.getKey().substring(namespace.length());
            requirePushFrame(run,id,owner);
            if(run.owner.equals(owner)&&run.expires>now&&!Set.of("COMPLETED","CANCELLED","COMPENSATED").contains(run.status)) {
                active++; if(!sent.contains(id+'\0'+run.fence+'\0'+run.version))return new PushCandidate(id,true,run.fence);
            }
        }
        if(active>=4)return null;
        for(var entry:runs.entrySet()) {
            Run run=entry.getValue(); if(!entry.getKey().startsWith(namespace+prefix)||!run.kind.equals(kind))continue;
            if(!Set.of("COMPLETED","CANCELLED","COMPENSATED").contains(run.status)&&(run.owner.isEmpty()||run.expires<=now)&&!serialBlocked(scope,entry.getKey(),run,now))
                return new PushCandidate(entry.getKey().substring(namespace.length()),false,0);
        }
        return null;
    }
    private boolean serialBlocked(String scope,String identity,Run run,long now) {
        if(run.serialGroup.isEmpty())return false;String namespace=scope+'\0';
        return runs.entrySet().stream().anyMatch(entry->!entry.getKey().equals(identity)&&entry.getKey().startsWith(namespace)
                &&entry.getValue().kind.equals("JOB")&&entry.getValue().serialGroup.equals(run.serialGroup)
                &&entry.getValue().status.equals("RUNNING")&&!entry.getValue().owner.isEmpty()&&entry.getValue().expires>now);
    }
    private void requirePushFrame(Run source,String id,String owner) {
        Run preview=source.copy();preview.owner=owner;preview.fence=Long.MAX_VALUE;preview.expires=Long.MAX_VALUE;preview.version=Long.MAX_VALUE;preview.status="COMPENSATING";
        new ExecutionDelivery(id,source.kind,Long.MAX_VALUE,result(preview));
    }
    synchronized boolean pushClaimActive(String scope,String id,String owner,long fence,long now) {
        Run run=runs.get(key(scope,id));return run!=null&&run.owner.equals(owner)&&run.fence==fence&&run.expires>now
                &&!Set.of("COMPLETED","CANCELLED","COMPENSATED").contains(run.status);
    }
    synchronized long pushNextExpiry(String scope,String prefix,String kind,long now) {
        String namespace=scope+'\0';long next=Long.MAX_VALUE;
        for(var entry:runs.entrySet())if(entry.getKey().startsWith(namespace+prefix)&&entry.getValue().kind.equals(kind)) {
            Run run=entry.getValue();if(!Set.of("COMPLETED","CANCELLED","COMPENSATED").contains(run.status)&&run.expires>now)next=Math.min(next,run.expires);
        }
        return next;
    }

    private byte[] runPage(final ResourceCommand command) throws IOException {
        var arguments = command.arguments(); String scope = command.scope() + '\0';
        String prefix = arguments.get(0), after = arguments.get(1), upper = arguments.get(2);
        int maximum = Integer.parseInt(arguments.get(3));
        if (Boolean.parseBoolean(arguments.get(4))) {
            upper = runs.keySet().stream().filter(key -> key.startsWith(scope + prefix)).map(key -> key.substring(scope.length()))
                    .max(String::compareTo).orElse("");
        }
        var selected = new java.util.ArrayList<java.util.Map.Entry<String, byte[]>>();
        int bytes = 1024; boolean more = false;
        for (var entry : runs.entrySet()) {
            if (!entry.getKey().startsWith(scope + prefix)) continue;
            String id = entry.getKey().substring(scope.length());
            if (id.compareTo(after) <= 0 || id.compareTo(upper) > 0) continue;
            byte[] encoded = result(entry.getValue()).encode(); int size = encoded.length + 4 + 4 + id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (selected.size() >= maximum || bytes + size > ResourceWire.MAX_FRAME) { more = true; break; }
            selected.add(java.util.Map.entry(id, encoded)); bytes += size;
        }
        String bound = upper; boolean hasMore = more;
        return ResourceWire.encode(out -> {
            out.writeInt(1); out.writeLong(index); ResourceWire.cursor(out, journal.latest(partition));
            ResourceWire.text(out, bound); out.writeBoolean(hasMore); out.writeInt(selected.size());
            for (var item : selected) { ResourceWire.text(out,item.getKey()); blob(out,item.getValue()); }
        });
    }

    private void install(ExecutionStateMachine copy) { partition = copy.partition; epoch = copy.epoch; index = copy.index; time = copy.time; fence = copy.fence; retainedBytes = copy.retainedBytes; rpcReplays = copy.rpcReplays; runs = copy.runs; rpcs = copy.rpcs; values = copy.values; results = copy.results; journal = copy.journal; }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized TransferableResource copyForTransfer() { return copy(); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validateSnapshot(byte[] bytes) { new ExecutionStateMachine(partition).restore(bytes); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes); out.writeInt(4); out.writeUTF(partition); out.writeLong(epoch); out.writeLong(index); out.writeLong(time); out.writeLong(fence); out.writeLong(retainedBytes); out.writeInt(rpcReplays.size()); for (var counter : rpcReplays.entrySet()) { out.writeUTF(counter.getKey()); out.writeLong(counter.getValue()); }
            out.writeInt(runs.size()); for (var entry : runs.entrySet()) { out.writeUTF(entry.getKey()); Run run = entry.getValue(); strings(out, List.of(run.kind, run.input, run.checkpoint, run.result, run.status, run.owner, run.context, run.currentStep)); out.writeLong(run.fence); out.writeLong(run.expires); out.writeLong(run.version); out.writeLong(run.backoff); out.writeInt(run.maxAttempts); out.writeBoolean(run.compensationRequested); out.writeUTF(run.serialGroup); out.writeInt(run.steps.size()); for (Step step : run.steps) { strings(out, List.of(step.name, step.key, step.compensation, Boolean.toString(step.idempotent), step.status, step.result)); out.writeInt(step.attempts); out.writeLong(step.due); out.writeBoolean(step.terminal); } }
            out.writeInt(rpcs.size()); for (var entry : rpcs.entrySet()) { out.writeUTF(entry.getKey()); Rpc rpc = entry.getValue(); strings(out, List.of(rpc.hash, rpc.status, rpc.result, rpc.error, rpc.context)); out.writeLong(rpc.expires); out.writeLong(rpc.fence); }
            out.writeInt(values.size()); for (var entry : values.entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().value()); out.writeLong(entry.getValue().version()); }
            out.writeInt(results.size()); for (var entry : results.entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().hash); blob(out, entry.getValue().result.encode()); out.writeLong(entry.getValue().expires); }
            blob(out, journal.snapshot()); out.flush(); if (bytes.size() > 134217728) throw new IllegalStateException("Execution snapshot capacity"); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Execution snapshot encoding", e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void restore(byte[] bytes) {
        if (bytes.length > 134217728) throw new IllegalArgumentException("Execution snapshot size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); int format = in.readInt(); if (format != 4) throw new IllegalArgumentException("Execution snapshot version"); String name = in.readUTF(); if (!name.equals(partition)) throw new IllegalArgumentException("Execution snapshot partition"); ExecutionStateMachine next = new ExecutionStateMachine(name); next.epoch = nonnegative(in.readLong()); next.index = nonnegative(in.readLong()); next.time = nonnegative(in.readLong()); next.fence = nonnegative(in.readLong()); next.retainedBytes = nonnegative(in.readLong()); for (int n = count(in, LIMIT); n > 0; n--) { String scope = in.readUTF(); id(scope); if (next.rpcReplays.put(scope, nonnegative(in.readLong())) != null) throw new IllegalArgumentException("Duplicate replay counter"); } reject(next.retainedBytes > BYTE_LIMIT, "Execution snapshot retention");
            for (int n = count(in, LIMIT); n > 0; n--) { String key = in.readUTF(); Run run = new Run(); var strings = strings(in, 8); run.kind = strings.get(0); run.input = strings.get(1); run.checkpoint = strings.get(2); run.result = strings.get(3); run.status = strings.get(4); run.owner = strings.get(5); run.context = strings.get(6); run.currentStep = strings.get(7); carrier(run.context); run.fence = nonnegative(in.readLong()); run.expires = nonnegative(in.readLong()); run.version = nonnegative(in.readLong()); run.backoff = nonnegative(in.readLong()); run.maxAttempts = in.readInt(); run.compensationRequested = in.readBoolean(); run.serialGroup=in.readUTF();if(!run.serialGroup.isEmpty()){id(run.serialGroup);if(!run.kind.equals("JOB"))throw new IllegalArgumentException("Invalid execution serial group");} for (int steps = count(in, 256); steps > 0; steps--) { var s = strings(in, 6); run.steps.add(new Step(s.get(0), s.get(1), s.get(2), bool(s.get(3)), s.get(4), s.get(5), (int) number(Integer.toString(in.readInt()), 0, 1000), nonnegative(in.readLong()), in.readBoolean())); } if (next.runs.put(key, run) != null) throw new IllegalArgumentException("Duplicate run"); }
            for (int n = count(in, LIMIT); n > 0; n--) { String key = in.readUTF(); var s = strings(in, 5); hash(s.getFirst()); carrier(s.get(4)); Rpc rpc = new Rpc(s.get(0), s.get(1), s.get(2), s.get(3), nonnegative(in.readLong()), nonnegative(in.readLong()), s.get(4)); if (next.rpcs.put(key, rpc) != null) throw new IllegalArgumentException("Duplicate RPC"); }
            for (int n = count(in, LIMIT); n > 0; n--) if (next.values.put(in.readUTF(), new ReplicatedResources.Value(in.readUTF(), nonnegative(in.readLong()))) != null) throw new IllegalArgumentException("Duplicate value");
            for (int n = count(in, LIMIT); n > 0; n--) if (next.results.put(in.readUTF(), new Saved(in.readUTF(), ExecutionResult.decode(blob(in, 1048576)), nonnegative(in.readLong()))) != null) throw new IllegalArgumentException("Duplicate request");
            next.journal = EventJournal.restore(blob(in, 75497472)); if (in.available() != 0) throw new IllegalArgumentException("Execution snapshot trailing bytes"); next.validateRestored(); install(next);
        } catch (IOException | Rejected e) { throw new IllegalArgumentException("Execution snapshot corruption", e); }
    }
    private void validateRestored() {
        var serialClaims=new HashSet<String>();
        for (var entry : runs.entrySet()) {
            storedKey(entry.getKey()); Run run = entry.getValue();
            if (!Set.of("JOB", "SAGA").contains(run.kind) || !Set.of("READY", "RUNNING", "COMPLETING", "COMPLETED", "CANCELLED", "UNKNOWN", "COMPENSATING", "COMPENSATED").contains(run.status) || run.fence > fence || run.version > index) throw new IllegalArgumentException("Invalid execution run");
            if (!run.owner.isEmpty()) id(run.owner);
            if(!run.serialGroup.isEmpty()&&run.status.equals("RUNNING")&&!run.owner.isEmpty()&&run.expires>time) {
                String group=entry.getKey().substring(0,entry.getKey().indexOf('\0'))+'\0'+run.serialGroup;
                if(!serialClaims.add(group))throw new IllegalArgumentException("Overlapping execution serial claims");
            }
            if (run.compensationRequested && (!run.kind.equals("SAGA") || !Set.of("UNKNOWN", "COMPENSATING", "COMPENSATED").contains(run.status))) throw new IllegalArgumentException("Invalid compensation decision");
            if (!run.currentStep.isEmpty() && run.steps.stream().noneMatch(step -> step.name.equals(run.currentStep))) throw new IllegalArgumentException("Missing selected saga step");
            if (run.kind.equals("SAGA")) { number(Integer.toString(run.maxAttempts), 1, 1000); number(Long.toString(run.backoff), 1, 3600000); }
            Set<String> steps = new HashSet<>();
            for (Step step : run.steps) {
                id(step.name); id(step.key);
                if (!steps.add(step.name) || !Set.of("ACTIVE", "DONE", "FAILED", "COMPENSATING", "COMPENSATED", "RETRY", "EXHAUSTED", "UNKNOWN").contains(step.status) || step.attempts > run.maxAttempts) throw new IllegalArgumentException("Invalid execution step");
            }
        }
        for (var entry : rpcs.entrySet()) { storedKey(entry.getKey()); Rpc rpc = entry.getValue(); if (!Set.of("IN_PROGRESS", "EXTERNAL_IN_PROGRESS", "UNKNOWN", "COMPLETED", "ERROR").contains(rpc.status) || rpc.fence <= 0 || rpc.fence > fence) throw new IllegalArgumentException("Invalid RPC state"); }
        for (var entry : values.entrySet()) { storedKey(entry.getKey()); if (entry.getValue().value().length() > 16384 || entry.getValue().version() <= 0 || entry.getValue().version() > index) throw new IllegalArgumentException("Invalid execution value"); }
        long minimumBytes = 0;
        for (var entry : results.entrySet()) { storedKey(entry.getKey()); hash(entry.getValue().hash); minimumBytes += entry.getValue().result.encode().length + entry.getKey().length() * 2L + 256; }
        if (minimumBytes > retainedBytes) throw new IllegalArgumentException("Invalid execution retention accounting");
    }
    private static void storedKey(String key) { int separator = key.indexOf('\0'); if (separator < 1 || separator != key.lastIndexOf('\0')) throw new IllegalArgumentException("Invalid scoped execution key"); id(key.substring(0, separator)); id(key.substring(separator + 1)); }
    static String encodeContext(Map<String, String> fields) { TraceCarrier.fromMap(fields); if (fields.size() > 2) throw new IllegalArgumentException("Trace carrier fields"); for (var entry : fields.entrySet()) if (!Set.of("traceparent", "tracestate").contains(entry.getKey()) || entry.getValue().length() > 512 || entry.getValue().indexOf('\n') >= 0 || entry.getValue().indexOf('\r') >= 0) throw new IllegalArgumentException("Trace carrier"); try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes); out.writeInt(fields.size()); for (var entry : new TreeMap<>(fields).entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); } out.flush(); return Base64.getEncoder().encodeToString(bytes.toByteArray()); } catch (IOException e) { throw new IllegalArgumentException(e); } }
    private static Map<String, String> carrier(String encoded) { try { byte[] bytes = Base64.getDecoder().decode(encoded); if (bytes.length > 2048) throw new IllegalArgumentException("Trace carrier size"); DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); int count = count(in, 2); Map<String, String> fields = new TreeMap<>(); for (int n = 0; n < count; n++) if (fields.put(in.readUTF(), in.readUTF()) != null) throw new IllegalArgumentException("Trace carrier duplicate"); if (in.available() != 0 || !encodeContext(fields).equals(encoded)) throw new IllegalArgumentException("Trace carrier encoding"); return fields; } catch (IOException e) { throw new IllegalArgumentException("Trace carrier", e); } }
    private static void contextFields(Map<String, String> fields, String context) { carrier(context).forEach((key, value) -> fields.put("context." + key, value)); }
    private static void strings(DataOutputStream out, List<String> values) throws IOException { for (String value : values) out.writeUTF(value); }
    private static List<String> strings(DataInputStream in, int count) throws IOException { List<String> values = new ArrayList<>(); for (int i = 0; i < count; i++) { String value = in.readUTF(); if (value.length() > 16384) throw new IllegalArgumentException("Snapshot text bounds"); values.add(value); } return values; }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int limit) throws IOException { int size = count(in, limit); byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException(); return bytes; }
    private static int count(DataInputStream in, int maximum) throws IOException { int count = in.readInt(); if (count < 0 || count > maximum) throw new IllegalArgumentException("Execution count"); return count; }
    private static long nonnegative(long value) { if (value < 0) throw new IllegalArgumentException("Negative execution value"); return value; }
    private static long number(String text, long min, long max) { long value = Long.parseLong(text); if (value < min || value > max) throw new IllegalArgumentException("Execution number bounds"); return value; }
    private static boolean bool(String value) { if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Execution boolean"); return Boolean.parseBoolean(value); }
    private static void hash(String value) { if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("RPC input hash"); }
    private static void id(String value) { if (value == null || value.isBlank() || value.length() > 128 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Execution identity"); }
    private static String key(String scope, String id) { return scope + '\0' + id; }
    private static String commandDigest(byte[] bytes) {
        ResourceCommand command = ResourceCommand.decode(bytes); int position = contextPosition(command.operation());
        if (position < 0) return digest(bytes);
        List<String> args = new ArrayList<>(command.arguments()); args.set(position, "");
        if (command.operation().equals("R_CLAIM") && args.size() == 6) args.removeLast();
        return digest(new ResourceCommand(command.operation(), command.scope(), command.requestId(), args).encode());
    }
    static int contextPosition(String operation) { return switch (operation) { case "J_SUBMIT" -> 2; case "S_START", "R_CLAIM" -> 4; case "R_INVOKE" -> 5; default -> -1; }; }
    private static String digest(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static void reject(boolean condition, String status) { if (condition) throw new Rejected(status); }
    private static final class Rejected extends RuntimeException { Rejected(String message) { super(message); } }
}
