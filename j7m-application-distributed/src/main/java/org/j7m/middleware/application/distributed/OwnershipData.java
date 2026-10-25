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
import java.security.*;
import java.util.*;

/**
 * Internal length-framed UTF16 identifiers and String-only native protected-map values.
 * Hashing never invokes application toString or normalizes distinct Java code units. Values use
 * bounded ASCII transport frames, preserving even unpaired surrogates through native UTF8 facades.
 */
final class OwnershipData {
    private static final String PREFIX = "j7m-owned-v1:";
    private OwnershipData() { }
    static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (part.length() >>> shift));
                for (int index = 0; index < part.length(); index++) { char value = part.charAt(index); digest.update((byte) (value >>> 8)); digest.update((byte) value); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Required SHA-256 unavailable", unavailable); }
    }
    static String encode(String value) {
        Objects.requireNonNull(value);
        if (value.length() > 4096) throw new IllegalArgumentException("Ownership value limit");
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes); output.writeInt(value.length());
            for (int index = 0; index < value.length(); index++) output.writeChar(value.charAt(index));
            output.flush(); return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException("Ownership value encoding unavailable", impossible); }
    }
    static String decode(String value) throws IOException {
        if (value == null || !value.startsWith(PREFIX) || value.length() > 12000) throw new IOException("Invalid ownership value frame");
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(PREFIX.length()))))) {
            int count = input.readInt(); if (count < 0 || count > 4096 || input.available() != count * 2) throw new IOException("Invalid ownership value length");
            char[] characters = new char[count]; for (int index = 0; index < count; index++) characters[index] = input.readChar(); return new String(characters);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid ownership value frame", invalid); }
    }
    static void identifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid ownership data identity");
    }
}
