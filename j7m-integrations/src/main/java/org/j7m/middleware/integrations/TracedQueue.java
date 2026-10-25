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

package org.j7m.middleware.integrations;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.distributed.*;

/**
 * Real replicated-queue facade carrying a bounded, versioned W3C context envelope.
 * Message context is supplied explicitly and must remain identical on retries. Payloads remain
 * opaque and are never exported. Send and consumer spans share the persisted production context.
 */
public final class TracedQueue {
    private static final int MAGIC = 0x4a375431;
    private final ReplicatedQueue queue;
    private final Observability telemetry;
    /**
     * Immutable delivery envelope.
     * @param context durable propagation parent
     * @param payload application bytes, copied on construction and access
     */
    public record Message(TraceCarrier context, byte[] payload) {
        /**
         * Copies and bounds application bytes.
         * @param context immutable propagation carrier
         * @param payload bytes, at most 30000
         * @throws IllegalArgumentException for oversized payload
         */
        public Message { Objects.requireNonNull(context); payload = payload.clone(); if (payload.length > 30000) throw new IllegalArgumentException("Traced payload exceeds budget"); }
        /**
         * Returns independent payload bytes.
         * @return copied payload
         */
        @Override public byte[] payload() { return payload.clone(); }
    }
    /**
     * Handler called under the persisted consumer context. Commit effects before returning;
     * the outer queue acknowledgement remains a separate, retryable operation.
     */
    @FunctionalInterface public interface Handler {
        /**
         * Processes an application payload without implicit queue acknowledgement.
         * @param payload independent application bytes
         * @throws Exception if processing fails
         */
        void process(byte[] payload) throws Exception;
    }
    /**
     * Creates an instrumented facade over the real queue.
     * @param queue authenticated replicated queue
     * @param telemetry concrete export integration
     */
    public TracedQueue(ReplicatedQueue queue, Observability telemetry) { this.queue = Objects.requireNonNull(queue); this.telemetry = Objects.requireNonNull(telemetry); }
    /**
     * Publishes a deterministic message envelope using a stable request/message identity.
     * @param request stable request identity
     * @param name queue name
     * @param id stable message identity
     * @param message immutable payload and retry-stable propagation metadata
     * @param attempts queue delivery attempt limit
     * @param deadline absolute deadline
     * @return underlying durable queue result
     * @throws IOException on unavailable/unknown outcome
     */
    public String send(String request, String name, String id, Message message, int attempts, Instant deadline) throws IOException {
        try (var span = telemetry.start(Observability.Operation.QUEUE_SEND, message.context())) {
            try {
                String result = queue.send(request, name, id, encode(message), attempts, deadline);
                if (!result.startsWith("SENT\t")) span.failed();
                return result;
            } catch (IOException | RuntimeException failure) { span.failed(); throw failure; }
        }
    }
    /**
     * Claims a delivery and records an actual queue claim observation.
     * @param request stable claim request
     * @param name queue name
     * @param group consumer group
     * @param member member identity
     * @param generation current assignment generation
     * @param visibilityMillis delivery lease milliseconds
     * @param deadline absolute deadline
     * @return underlying MESSAGE/EMPTY result, preserving receipt metadata
     * @throws IOException for unavailable or unknown claim
     */
    public String claim(String request, String name, String group, String member, long generation, long visibilityMillis, Instant deadline) throws IOException {
        String result = queue.claim(request, name, group, member, generation, visibilityMillis, deadline);
        if (result.startsWith("MESSAGE\t")) telemetry.queueClaimed(); return result;
    }
    /**
     * Acknowledges only a committed consumer effect and records confirmed acknowledgements.
     * @param request stable acknowledgement request
     * @param name queue name
     * @param group consumer group
     * @param member live member
     * @param generation current group generation
     * @param id message identity
     * @param receipt current delivery receipt
     * @param deadline absolute deadline
     * @return underlying acknowledgement result
     * @throws IOException for unknown or unavailable acknowledgement
     */
    public String ack(String request, String name, String group, String member, long generation, String id, long receipt, Instant deadline) throws IOException {
        String result = queue.ack(request, name, group, member, generation, id, receipt, deadline);
        if (result.equals("ACKED")) telemetry.queueAcknowledged(); return result;
    }
    /**
     * Observes the actual retained dead-letter page without exporting identities.
     * @param name queue name
     * @param deadline absolute deadline
     * @return actual first retained dead-letter page
     * @throws IOException for query failure
     */
    public List<String> deadLetters(String name, Instant deadline) throws IOException {
        List<String> result = queue.deadLetters(name, deadline); telemetry.queueDeadLetters(result.size()); return result;
    }

    /**
     * Processes an encoded queue payload under its persisted remote parent.
     * @param envelope bytes returned by a successful real queue claim
     * @param handler application callback, such as a transactional inbox consumer
     * @throws Exception if the envelope or callback fails
     */
    public void consume(byte[] envelope, Handler handler) throws Exception {
        Message message = decode(envelope);
        try (var span = telemetry.start(Observability.Operation.QUEUE_CONSUME, message.context())) {
            try { handler.process(message.payload()); } catch (Exception failure) { span.failed(); throw failure; }
        }
    }
    /**
     * Encodes a bounded context envelope for a queue or transactional outbox.
     * @param message immutable payload/context
     * @return deterministic version-one bytes
     * @throws IOException if encoding fails
     */
    public static byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeUTF(message.context().traceparent()); out.writeUTF(message.context().tracestate());
            byte[] payload = message.payload(); out.writeInt(payload.length); out.write(payload);
        }
        return bytes.toByteArray();
    }
    /**
     * Decodes an immutable envelope, rejecting unsupported formats and trailing bytes.
     * @param envelope at most 32768 encoded bytes
     * @return decoded context and independent payload
     * @throws IOException for unsupported or malformed bytes
     */
    public static Message decode(byte[] envelope) throws IOException {
        if (envelope.length > 32768) throw new IOException("Trace envelope exceeds queue budget");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported traced message format");
            TraceCarrier context;
            try { context = new TraceCarrier(in.readUTF(), in.readUTF()); } catch (IllegalArgumentException malformed) { throw new IOException("Invalid trace context", malformed); }
            int size = in.readInt(); if (size < 0 || size > 30000) throw new IOException("Invalid message size");
            byte[] payload = in.readNBytes(size); if (payload.length != size || in.available() != 0) throw new IOException("Truncated/trailing message data");
            return new Message(context, payload);
        }
    }
}
