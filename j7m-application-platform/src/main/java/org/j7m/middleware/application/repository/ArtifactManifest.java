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
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.j7m.middleware.application.api.ApplicationCoordinate;

/**
 * Versioned signed artifact manifest. Signature covers tenant, coordinate, complete length/hash,
 * fixed chunk size and every ordered chunk hash; no untrusted Java deserialization is used.
 * @param tenant immutable authorization scope
 * @param coordinate exact immutable application/artifact coordinate
 * @param length complete byte length
 * @param contentHash lowercase SHA-256
 * @param chunkSize fixed chunk size in bytes
 * @param chunkHashes ordered SHA-256 hashes
 * @param keyId trusted Ed25519 signer identity
 * @param signature Ed25519 signature over canonical version-one metadata
 */
public record ArtifactManifest(String tenant, String coordinate, long length, String contentHash,
        int chunkSize, List<String> chunkHashes, String keyId, byte[] signature) {
    /**
     * Validates bounds and defensively copies metadata.
     * @param tenant scope
     * @param coordinate immutable coordinate
     * @param length artifact length
     * @param contentHash full digest
     * @param chunkSize chunk size
     * @param chunkHashes chunk digests
     * @param keyId signer
     * @param signature signature bytes
     * @throws IllegalArgumentException for malformed identities, bounds or hashes
     */
    public ArtifactManifest {
        identifier(tenant); identifier(keyId); coordinate = ApplicationCoordinate.parse(coordinate).toString();
        if (chunkHashes == null || signature == null || contentHash == null) throw new IllegalArgumentException("Missing manifest fields");
        if (coordinate.length() > 512 || length < 1 || length > 67108864 || chunkSize < 4096 || chunkSize > 262144
                || chunkHashes.size() != (length + chunkSize - 1) / chunkSize || chunkHashes.size() > 16384
                || !contentHash.matches("[0-9a-f]{64}") || signature.length != 64) throw new IllegalArgumentException("Manifest bounds");
        chunkHashes = List.copyOf(chunkHashes); if (chunkHashes.stream().anyMatch(hash -> !hash.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("Chunk digest");
        signature = signature.clone();
    }
    /**
     * Returns a signature copy.
     * @return independent signature bytes
     */
    @Override public byte[] signature() { return signature.clone(); }
    /**
     * Generates a manifest without executing or loading artifact code.
     * @param tenant authorized scope
     * @param coordinate exact coordinate
     * @param file source artifact
     * @param chunkSize fixed chunk byte limit
     * @param keyId signer identity
     * @param key Ed25519 signing key
     * @return signed immutable manifest
     * @throws IOException for source or signature failure
     */
    public static ArtifactManifest sign(String tenant, String coordinate, Path file, int chunkSize, String keyId, PrivateKey key) throws IOException {
        long length = Files.size(file); if (length < 1 || length > 67108864 || chunkSize < 4096 || chunkSize > 262144) throw new IllegalArgumentException("Artifact bounds");
        List<String> hashes = new ArrayList<>(); MessageDigest complete = digest();
        try (InputStream in = Files.newInputStream(file)) { byte[] chunk; while ((chunk = in.readNBytes(chunkSize)).length > 0) { hashes.add(hash(chunk)); complete.update(chunk); } }
        var unsigned = new ArtifactManifest(tenant, coordinate, length, HexFormat.of().formatHex(complete.digest()), chunkSize, hashes, keyId, new byte[64]);
        try { Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key); signer.update(unsigned.canonical());
            return new ArtifactManifest(tenant, coordinate, length, unsigned.contentHash, chunkSize, hashes, keyId, signer.sign());
        } catch (GeneralSecurityException failure) { throw new IOException("Manifest signing failed", failure); }
    }
    /**
     * Verifies the signature with a configured trust anchor.
     * @param trust signer identity to Ed25519 public key
     * @throws SecurityException for unknown or invalid signatures
     */
    public void verify(Map<String, PublicKey> trust) {
        try { PublicKey key = trust.get(keyId); if (key == null) throw new SecurityException("Untrusted signer");
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key); verifier.update(canonical());
            if (!verifier.verify(signature)) throw new SecurityException("Invalid artifact signature");
        } catch (GeneralSecurityException failure) { throw new SecurityException("Artifact signature rejected", failure); }
    }
    /**
     * Stable hash identifying the complete signed manifest.
     * @return lowercase SHA-256 manifest identity
     */
    public String id() { return hash(encode()); }
    /**
     * Encodes explicit version-one metadata.
     * @return independent bounded bytes
     */
    public byte[] encode() { return encode(out -> { out.write(canonical()); out.write(signature); }); }
    /**
     * Decodes bounded data completely before returning a manifest.
     * @param bytes encoded version-one data
     * @return validated manifest
     * @throws IllegalArgumentException for unsupported or malformed metadata
     */
    public static ArtifactManifest decode(byte[] bytes) {
        if (bytes.length > 2097152) throw new IllegalArgumentException("Manifest too large");
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != 1) throw new IOException("Unknown manifest version");
            String tenant = in.readUTF(), coordinate = in.readUTF(); long length = in.readLong(); String hash = in.readUTF(); int size = in.readInt(), count = in.readInt();
            if (count < 1 || count > 16384) throw new IOException("Chunk count"); List<String> hashes = new ArrayList<>(); for (int n = 0; n < count; n++) hashes.add(in.readUTF());
            String key = in.readUTF(); byte[] signature = in.readNBytes(64); if (in.available() != 0) throw new IOException("Trailing manifest data");
            return new ArtifactManifest(tenant, coordinate, length, hash, size, hashes, key, signature);
        } catch (IOException failure) { throw new IllegalArgumentException("Malformed manifest", failure); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public boolean equals(Object other) {
        if (!(other instanceof ArtifactManifest manifest)) return false;
        return tenant.equals(manifest.tenant) && coordinate.equals(manifest.coordinate) && length == manifest.length
                && contentHash.equals(manifest.contentHash) && chunkSize == manifest.chunkSize && chunkHashes.equals(manifest.chunkHashes)
                && keyId.equals(manifest.keyId) && Arrays.equals(signature, manifest.signature);
    }
    /**
     * {@inheritDoc}
     */
    @Override public int hashCode() { return 31 * Objects.hash(tenant, coordinate, length, contentHash, chunkSize, chunkHashes, keyId) + Arrays.hashCode(signature); }
    byte[] canonical() { return encode(out -> { out.writeInt(1); out.writeUTF(tenant); out.writeUTF(coordinate); out.writeLong(length); out.writeUTF(contentHash); out.writeInt(chunkSize);
        out.writeInt(chunkHashes.size()); for (String hash : chunkHashes) out.writeUTF(hash); out.writeUTF(keyId); }); }
    static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9_.@-]{1,128}") || value.contains("..")) throw new IllegalArgumentException("Invalid identifier"); }
    static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
    static String hash(byte[] value) { return HexFormat.of().formatHex(digest().digest(value)); }
    interface Writer { void write(DataOutputStream output) throws IOException; }
    static byte[] encode(Writer writer) { try { ByteArrayOutputStream result = new ByteArrayOutputStream(); writer.write(new DataOutputStream(result)); return result.toByteArray(); } catch (IOException failure) { throw new IllegalStateException(failure); } }
}
