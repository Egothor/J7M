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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads records from segment locations.
 */
final class SegmentReader {
    private final Path directory;
    private final long maxPayloadBytes;

    SegmentReader(final Path directory, final long maxPayloadBytes) {
        this.directory = directory;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    RecordFrame read(final RecordLocation location) throws IOException {
        Path path = this.directory.resolve(SegmentFileFormat.fileName(location.segmentId().value()));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(SegmentFileFormat.HEADER_SIZE);
            readFully(channel, header, location.position());
            header.flip();
            long frameLength = RecordFrameCodec.validatedFrameLength(header, this.maxPayloadBytes, location.length());
            byte[] tail = new byte[(int) frameLength - SegmentFileFormat.HEADER_SIZE];
            ByteBuffer tailBuffer = ByteBuffer.wrap(tail);
            readFully(channel, tailBuffer, location.position() + SegmentFileFormat.HEADER_SIZE);
            tailBuffer.flip();
            return RecordFrameCodec.decode(header, tail, this.maxPayloadBytes).frame();
        }
    }

    static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position) throws IOException {
        long current = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, current);
            if (read < 0) {
                throw new java.io.EOFException("truncated durable log frame");
            }
            current += read;
        }
    }
}
