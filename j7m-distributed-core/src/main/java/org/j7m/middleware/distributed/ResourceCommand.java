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

/**
 * Version-one deterministic command. All identifiers are tenant-scoped by the resource facade.
 * Commands contain no executable code or object serialization; unknown operations fail before mutation.
 * @param operation operation code
 * @param scope authenticated tenant and principal scope
 * @param requestId unique request within scope
 * @param arguments bounded textual arguments, never null
 */
public record ResourceCommand(String operation, String scope, String requestId, List<String> arguments) {
    /**
     * Maximum encoded command size in bytes.
     */
    public static final int MAX_BYTES = 1_048_576;
    /**
     * Validates and defensively copies a command.
     * @param operation operation code
     * @param scope nonblank authorization scope
     * @param requestId nonblank idempotency key
     * @param arguments at most 4096 nonnull arguments
     * @throws IllegalArgumentException for invalid identifiers or bounds
     */
    public ResourceCommand {
        if (operation == null || operation.isBlank() || scope == null || scope.isBlank()
                || requestId == null || requestId.isBlank() || arguments == null || arguments.size() > 4096
                || operation.length() > 64 || scope.length() > 512 || requestId.length() > 512
                || scope.indexOf('\0') >= 0 || requestId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid command identity/bounds");
        }
        arguments = List.copyOf(arguments);
    }
    /**
     * Encodes this immutable command in the version-one wire format.
     * @return owned bytes
     * @throws IllegalArgumentException if an argument or command exceeds its limit
     */
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF(operation); out.writeUTF(scope); out.writeUTF(requestId);
            out.writeInt(arguments.size());
            for (String argument : arguments) out.writeUTF(argument);
            out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Command too large");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("Command encoding bounds", e); }
    }
    /**
     * Validates an entire command before returning it; trailing bytes are rejected.
     * @param bytes bounded wire bytes
     * @return validated command
     * @throws IllegalArgumentException for corruption or unknown format
     */
    public static ResourceCommand decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Command too large");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IllegalArgumentException("Unsupported command version");
            String operation = in.readUTF(), scope = in.readUTF(), id = in.readUTF();
            int count = in.readInt();
            if (count < 0 || count > 4096) throw new IllegalArgumentException("Argument count");
            var args = new ArrayList<String>();
            for (int i = 0; i < count; i++) args.add(in.readUTF());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing command bytes");
            return new ResourceCommand(operation, scope, id, args);
        } catch (IOException e) { throw new IllegalArgumentException("Corrupt command", e); }
    }
}
