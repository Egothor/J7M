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

package org.j7m.middleware.durablelog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogRecordType;
import org.j7m.middleware.api.durablelog.LogStreamId;

/**
 * Encodes and decodes durable-log record frames.
 */
final class RecordFrameCodec {
    private static final int CHECKSUM_OFFSET = 60;
    private static final int MAX_STREAM_ID_BYTES = 128;
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final int HASH_BYTES = 32;

    private RecordFrameCodec() {
    }

    /**
     * Encoded frame.
     *
     * @param frame source frame
     * @param bytes encoded frame bytes
     */
    record EncodedFrame(RecordFrame frame, byte[] bytes) {
        /**
         * Creates encoded frame.
         */
        EncodedFrame {
            bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
        }
    }

    static EncodedFrame encode(final RecordFrame frame) {
        byte[] streamBytes = frame.stream().value().getBytes(StandardCharsets.UTF_8);
        byte[] headerBytes = encodeHeaders(frame.header().metadata());
        byte[] payload = frame.payload();
        byte[] previousHash = frame.previousHash();
        validateLengths(streamBytes.length, headerBytes.length, payload.length, previousHash.length, Long.MAX_VALUE);
        int frameLength = SegmentFileFormat.HEADER_SIZE + streamBytes.length + headerBytes.length + previousHash.length + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(frameLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(SegmentFileFormat.MAGIC);
        buffer.putShort(SegmentFileFormat.VERSION);
        buffer.putShort((short) 0);
        buffer.putLong(frame.batchId());
        buffer.putInt(frame.batchSize());
        buffer.putInt(frame.batchIndex());
        buffer.putInt(streamBytes.length);
        buffer.putLong(frame.offset().value());
        buffer.putLong(frame.header().timestampMillis());
        buffer.putInt(frame.header().type().ordinal());
        buffer.putInt(headerBytes.length);
        buffer.putInt(payload.length);
        buffer.putInt(previousHash.length);
        buffer.putInt(0);
        buffer.putLong(frameLength);
        buffer.put(streamBytes);
        buffer.put(headerBytes);
        buffer.put(previousHash);
        buffer.put(payload);
        byte[] bytes = buffer.array();
        int checksum = RecordChecksum.checksum(bytes);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(CHECKSUM_OFFSET, checksum);
        return new EncodedFrame(frame, bytes);
    }

    static DecodedFrame decode(final ByteBuffer header, final byte[] tail, final long maxPayloadBytes) {
        header.order(ByteOrder.BIG_ENDIAN);
        int magic = header.getInt(0);
        short version = header.getShort(4);
        if (magic != SegmentFileFormat.MAGIC || version != SegmentFileFormat.VERSION) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame header is invalid");
        }
        long batchId = header.getLong(8);
        int batchSize = header.getInt(16);
        int batchIndex = header.getInt(20);
        int streamLength = header.getInt(24);
        long offset = header.getLong(28);
        long timestamp = header.getLong(36);
        int typeOrdinal = header.getInt(44);
        int headerLength = header.getInt(48);
        int payloadLength = header.getInt(52);
        int previousHashLength = header.getInt(56);
        int checksum = header.getInt(CHECKSUM_OFFSET);
        long frameLength = header.getLong(64);
        validateLengths(streamLength, headerLength, payloadLength, previousHashLength, maxPayloadBytes);
        if (batchSize < 1 || batchIndex < 0 || batchIndex >= batchSize) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log batch metadata is invalid");
        }
        if (typeOrdinal < 0 || typeOrdinal >= LogRecordType.values().length) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log record type is invalid");
        }
        if (frameLength != SegmentFileFormat.HEADER_SIZE + streamLength + headerLength + payloadLength + previousHashLength) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame length is invalid");
        }
        if (tail.length != frameLength - SegmentFileFormat.HEADER_SIZE) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame tail length is invalid");
        }
        byte[] all = new byte[(int) frameLength];
        ByteBuffer copy = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
        for (int index = 0; index < SegmentFileFormat.HEADER_SIZE; index++) {
            all[index] = header.get(index);
        }
        System.arraycopy(tail, 0, all, SegmentFileFormat.HEADER_SIZE, tail.length);
        copy.putInt(CHECKSUM_OFFSET, 0);
        if (RecordChecksum.checksum(all) != checksum) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame checksum mismatch");
        }
        int position = 0;
        String stream = decodeUtf8(tail, position, streamLength, "durable log stream id is invalid");
        position += streamLength;
        Map<String, String> metadata = decodeHeaders(tail, position, headerLength);
        position += headerLength;
        byte[] previousHash = Arrays.copyOfRange(tail, position, position + previousHashLength);
        position += previousHashLength;
        byte[] payload = Arrays.copyOfRange(tail, position, position + payloadLength);
        LogRecordType type = LogRecordType.values()[typeOrdinal];
        RecordFrame frame = new RecordFrame(batchId, batchSize, batchIndex, new LogStreamId(stream), new LogOffset(offset),
                new LogRecordHeader(type, timestamp, metadata), payload, previousHash);
        return new DecodedFrame(frame, (int) frameLength);
    }

    static long validatedFrameLength(final ByteBuffer header, final long maxPayloadBytes, final long availableBytes) {
        header.order(ByteOrder.BIG_ENDIAN);
        int magic = header.getInt(0);
        short version = header.getShort(4);
        if (magic != SegmentFileFormat.MAGIC || version != SegmentFileFormat.VERSION) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame header is invalid");
        }
        int streamLength = header.getInt(24);
        int headerLength = header.getInt(48);
        int payloadLength = header.getInt(52);
        int previousHashLength = header.getInt(56);
        long frameLength = header.getLong(64);
        validateLengths(streamLength, headerLength, payloadLength, previousHashLength, maxPayloadBytes);
        long expected = SegmentFileFormat.HEADER_SIZE + (long) streamLength + headerLength + payloadLength + previousHashLength;
        if (frameLength != expected || frameLength > Integer.MAX_VALUE || frameLength > availableBytes) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame length is invalid");
        }
        return frameLength;
    }

    static DecodedHeaderMetadata decodeHeaderMetadata(final ByteBuffer header, final byte[] prefix, final long maxPayloadBytes, final long availableBytes) {
        header.order(ByteOrder.BIG_ENDIAN);
        validatedFrameLength(header, maxPayloadBytes, availableBytes);
        int streamLength = header.getInt(24);
        int headerLength = header.getInt(48);
        int previousHashLength = header.getInt(56);
        int expectedPrefixLength = streamLength + headerLength + previousHashLength;
        if (prefix.length != expectedPrefixLength) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log metadata prefix length is invalid");
        }
        decodeUtf8(prefix, 0, streamLength, "durable log stream id is invalid");
        Map<String, String> metadata = decodeHeaders(prefix, streamLength, headerLength);
        return new DecodedHeaderMetadata(metadata);
    }

    static int metadataPrefixLength(final ByteBuffer header, final long maxPayloadBytes, final long availableBytes) {
        header.order(ByteOrder.BIG_ENDIAN);
        validatedFrameLength(header, maxPayloadBytes, availableBytes);
        int streamLength = header.getInt(24);
        int headerLength = header.getInt(48);
        int previousHashLength = header.getInt(56);
        return streamLength + headerLength + previousHashLength;
    }

    static long frameLength(final ByteBuffer header) {
        return validatedFrameLength(header, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private static byte[] encodeHeaders(final Map<String, String> metadata) {
        TreeMap<String, String> sorted = new TreeMap<>(metadata == null ? Map.of() : metadata);
        if (sorted.size() > MAX_METADATA_ENTRIES) {
            throw new DurableLogException(MiddlewareErrorCode.INVALID_ARGUMENT, "durable log metadata entry limit exceeded");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            try {
                out.write(entry.getKey().getBytes(StandardCharsets.UTF_8));
                out.write('=');
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        byte[] encoded = out.toByteArray();
        if (encoded.length > MAX_METADATA_BYTES) {
            throw new DurableLogException(MiddlewareErrorCode.INVALID_ARGUMENT, "durable log metadata byte limit exceeded");
        }
        return encoded;
    }

    private static Map<String, String> decodeHeaders(final byte[] bytes, final int offset, final int length) {
        if (length == 0) {
            return Map.of();
        }
        if (length > MAX_METADATA_BYTES) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log metadata length is invalid");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        int position = offset;
        int end = offset + length;
        while (position < end) {
            int lineStart = position;
            while (position < end && bytes[position] != '\n') {
                position++;
            }
            if (position == lineStart) {
                position++;
                continue;
            }
            int separator = -1;
            for (int index = lineStart; index < position; index++) {
                if (bytes[index] == '=') {
                    separator = index;
                    break;
                }
            }
            if (separator <= lineStart) {
                throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log header metadata is invalid");
            }
            String key = decodeUtf8(bytes, lineStart, separator - lineStart, "durable log metadata key is invalid");
            String value = decodeUtf8(bytes, separator + 1, position - separator - 1, "durable log metadata value is invalid");
            if (metadata.putIfAbsent(key, value) != null) {
                throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log duplicate metadata key detected");
            }
            if (metadata.size() > MAX_METADATA_ENTRIES) {
                throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log metadata entry limit exceeded");
            }
            position++;
        }
        return Map.copyOf(metadata);
    }

    private static String decodeUtf8(final byte[] bytes, final int offset, final int length, final String message) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, message, exception);
        }
    }

    private static void validateLengths(
            final int streamLength,
            final int headerLength,
            final int payloadLength,
            final int previousHashLength,
            final long maxPayloadBytes) {
        if (streamLength < 1 || streamLength > MAX_STREAM_ID_BYTES || headerLength < 0 || headerLength > MAX_METADATA_BYTES
                || payloadLength < 0 || payloadLength > maxPayloadBytes || previousHashLength < 0
                || (previousHashLength != 0 && previousHashLength != HASH_BYTES)) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log frame length is invalid");
        }
    }
}

record DecodedFrame(RecordFrame frame, int length) {
}

record DecodedHeaderMetadata(Map<String, String> metadata) {
}
