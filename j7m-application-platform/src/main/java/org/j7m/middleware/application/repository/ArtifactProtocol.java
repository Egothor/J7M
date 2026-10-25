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

package org.j7m.middleware.application.repository;

import java.io.*;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Explicit bounded authenticated artifact wire protocol, independent of runtime discovery.
 */
final class ArtifactProtocol {
    static final int MAX_FRAME = 2097152;
    private ArtifactProtocol() { }
    static void send(DataOutputStream out, byte[] body, byte[] key) throws IOException {
        if (body.length > MAX_FRAME) throw new IOException("Artifact frame bound");
        out.writeInt(body.length); out.write(body); out.write(mac(body, key)); out.flush();
    }
    static byte[] receive(DataInputStream in) throws IOException { int size = in.readInt(); if (size < 0 || size > MAX_FRAME) throw new IOException("Artifact frame bound"); byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException(); return bytes; }
    static void authenticate(DataInputStream in, byte[] body, byte[] key) throws IOException { byte[] tag = in.readNBytes(32); if (tag.length != 32 || !MessageDigest.isEqual(tag, mac(body, key))) throw new IOException("Artifact authentication rejected"); }
    private static byte[] mac(byte[] body, byte[] key) throws IOException { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(body); } catch (GeneralSecurityException failure) { throw new IOException("Artifact authentication unavailable", failure); } }
    static byte[] bytes(DataInputStream in, int maximum) throws IOException { int size = in.readInt(); if (size < 0 || size > maximum || size > in.available()) throw new IOException("Artifact payload bound"); return in.readNBytes(size); }
    static void bytes(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Trailing artifact data"); }
    static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
}
