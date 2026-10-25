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

package org.j7m.middleware.application.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;

/**
 * Internal bounded I/O utility for the application-platform repository and
 * deployment implementation.
 *
 * <p>The type is intentionally located in a non-exported package. Public
 * application code must use {@link J7mBundleReader}, {@link J7mBundleWriter},
 * and {@link ApplicationBundleVerifier} instead.</p>
 */
public final class BoundedBundleIO {
    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    private BoundedBundleIO() {
    }

    /**
     * Validates that a bundle path names a regular file within the configured
     * artifact byte limit.
     *
     * @param path bundle path to validate
     * @param limits active platform limits
     * @throws IOException if the file metadata cannot be read
     * @throws MiddlewareException if the file is missing, not regular, or too large
     */
    public static void validateBundleFile(final Path path, final ApplicationPlatformLimits limits) throws IOException {
        if (path == null) {
            throw failure("bundle path is required");
        }
        if (!Files.isRegularFile(path)) {
            throw failure("application bundle is not a regular file");
        }
        long size = Files.size(path);
        if (size > limits.maxBundleBytes()) {
            throw failure("application bundle exceeds configured size limit");
        }
    }

    /**
     * Returns validated ZIP entries in deterministic name order.
     *
     * @param zip open ZIP file owned by the caller
     * @param limits active platform limits
     * @return validated entries sorted by raw ZIP name
     * @throws MiddlewareException if names, counts, sizes, duplicates, or
     * compression ratios violate the configured limits
     */
    public static List<ZipEntry> validatedEntries(final ZipFile zip, final ApplicationPlatformLimits limits) {
        List<ZipEntry> entries = new ArrayList<>();
        Set<String> raw = new HashSet<>();
        Set<String> normalized = new HashSet<>();
        int manifestCount = 0;
        int checksumCount = 0;
        long compressedTotal = 0L;
        long expandedTotal = 0L;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (entries.size() == limits.maxEntryCount()) {
                throw failure("application bundle contains too many entries");
            }
            String name = entry.getName();
            String normalizedName = normalizeEntryName(name, entry.isDirectory(), limits);
            if (!raw.add(name) || !normalized.add(normalizedName)) {
                throw failure("application bundle contains duplicate entries");
            }
            if (J7mBundleReader.MANIFEST_ENTRY.equals(normalizedName)) {
                manifestCount++;
            } else if (J7mBundleReader.CHECKSUM_ENTRY.equals(normalizedName)) {
                checksumCount++;
            }
            long compressed = entry.getCompressedSize();
            long expanded = entry.getSize();
            if (!entry.isDirectory() && (compressed < 0L || expanded < 0L)) {
                throw failure("bundle entry size metadata is unavailable");
            }
            if (compressed < 0L) {
                compressed = 0L;
            }
            compressedTotal = checkedAdd(compressedTotal, compressed, "compressed bundle size limit exceeded");
            if (compressedTotal > limits.maxCompressedBytes()) {
                throw failure("compressed bundle size limit exceeded");
            }
            if (expanded > limits.maxEntryExpandedBytes()) {
                throw failure("bundle entry exceeds configured expanded size limit");
            }
            if (expanded > 0L) {
                expandedTotal = checkedAdd(expandedTotal, expanded, "expanded bundle size limit exceeded");
                if (expandedTotal > limits.maxAggregateExpandedBytes()) {
                    throw failure("expanded bundle size limit exceeded");
                }
                if (compressed <= 0L || compressionRatioExceeded(expanded, compressed, limits.maxCompressionRatio())) {
                    throw failure("bundle entry compression ratio exceeds configured limit");
                }
            }
            entries.add(entry);
        }
        if (manifestCount > 1 || checksumCount > 1) {
            throw failure("application bundle contains duplicate mandatory metadata");
        }
        entries.sort(Comparator.comparing(ZipEntry::getName));
        return entries;
    }

    private static boolean compressionRatioExceeded(final long expanded, final long compressed, final int limit) {
        if (limit <= 0) {
            return true;
        }
        if (compressed > Long.MAX_VALUE / (long) limit) {
            return false;
        }
        return expanded > compressed * (long) limit;
    }

    /**
     * Reads a bounded ZIP entry as UTF-8 text.
     *
     * <p>The caller retains ownership of the supplied {@link ZipFile}. The
     * method reads at most {@code maxBytes} expanded bytes, also honoring the
     * active per-entry expansion limit, and decodes the resulting byte sequence
     * with {@link StandardCharsets#UTF_8}. The returned string is newly
     * allocated and does not retain references to the ZIP entry buffer.</p>
     *
     * @param zip open ZIP file owned and closed by the caller
     * @param entry entry to read from {@code zip}
     * @param maxBytes maximum expanded bytes accepted for this read
     * @param limits active platform limits that bound entry expansion and I/O
     * buffer size
     * @return decoded UTF-8 entry contents
     * @throws IOException if ZIP input cannot be opened or read
     * @throws MiddlewareException if the declared or observed expanded entry
     * size exceeds the configured limit
     */
    public static String readEntryString(
            final ZipFile zip,
            final ZipEntry entry,
            final long maxBytes,
            final ApplicationPlatformLimits limits) throws IOException {
        byte[] bytes = readEntryBytes(zip, entry, maxBytes, limits);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads a bounded ZIP entry into a newly allocated byte array.
     *
     * <p>The caller retains ownership of the supplied {@link ZipFile}. The
     * method rejects entries whose declared size exceeds {@code maxBytes} or
     * the active platform limit and also enforces the same bound while reading,
     * protecting callers from missing or dishonest ZIP size metadata. This
     * helper is intended for small bundle metadata, not application artifacts.</p>
     *
     * @param zip open ZIP file owned and closed by the caller
     * @param entry entry to read from {@code zip}
     * @param maxBytes maximum expanded bytes accepted for this read
     * @param limits active platform limits that bound entry expansion and I/O
     * buffer size
     * @return newly allocated entry bytes
     * @throws IOException if ZIP input cannot be opened or read
     * @throws MiddlewareException if the declared or observed expanded entry
     * size exceeds the configured limit
     */
    public static byte[] readEntryBytes(
            final ZipFile zip,
            final ZipEntry entry,
            final long maxBytes,
            final ApplicationPlatformLimits limits) throws IOException {
        long declared = entry.getSize();
        if (declared > maxBytes || declared > limits.maxEntryExpandedBytes()) {
            throw failure("bundle metadata exceeds configured limit");
        }
        int capacity = declared > 0L && declared <= Integer.MAX_VALUE ? (int) declared : 1024;
        ByteArrayOutputStream output = new ByteArrayOutputStream(capacity);
        try (InputStream input = zip.getInputStream(entry)) {
            copy(input, output, maxBytes, limits.ioBufferBytes(), null);
        }
        return output.toByteArray();
    }

    /**
     * Computes the SHA-256 digest of a bounded application bundle file.
     *
     * <p>The method validates that {@code path} names a regular file within
     * the configured bundle-size limit before reading it. The file is streamed
     * through a fixed-size buffer from {@code limits}; the full bundle is never
     * materialized in memory. The returned value is lowercase hexadecimal and
     * contains no path or file-system details.</p>
     *
     * @param path application bundle path to digest
     * @param limits active platform limits that bound file size and I/O buffer
     * size
     * @return lowercase hexadecimal SHA-256 digest
     * @throws MiddlewareException if the path is invalid, the file exceeds the
     * configured limit, file I/O fails, or the SHA-256 algorithm is unavailable
     */
    public static String sha256(final Path path, final ApplicationPlatformLimits limits) {
        try {
            validateBundleFile(path, limits);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                copy(input, OutputStream.nullOutputStream(), limits.maxBundleBytes(), limits.ioBufferBytes(), digest);
            }
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to compute checksum",
                    java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * Computes the SHA-256 digest of a bounded ZIP entry.
     *
     * <p>The caller retains ownership of the supplied {@link ZipFile}. The
     * entry is streamed through a fixed-size buffer from {@code limits}; the
     * expanded entry is never materialized in memory. The read is bounded by
     * the configured maximum expanded entry size and returns lowercase
     * hexadecimal digest text suitable for deterministic bundle metadata.</p>
     *
     * @param zip open ZIP file owned and closed by the caller
     * @param entry entry to digest from {@code zip}
     * @param limits active platform limits that bound entry expansion and I/O
     * buffer size
     * @return lowercase hexadecimal SHA-256 digest
     * @throws MiddlewareException if ZIP input fails, the expanded entry
     * exceeds the configured limit, or the SHA-256 algorithm is unavailable
     */
    public static String sha256(final ZipFile zip, final ZipEntry entry, final ApplicationPlatformLimits limits) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = zip.getInputStream(entry)) {
                copy(input, OutputStream.nullOutputStream(), limits.maxEntryExpandedBytes(), limits.ioBufferBytes(), digest);
            }
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to compute entry checksum",
                    java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * Copies a stream with a fixed caller-provided maximum byte count.
     *
     * <p>The method allocates one buffer of {@code bufferBytes}, updates the
     * optional digest with copied bytes, preserves interruption by throwing
     * {@link IOException}, and never closes either stream.</p>
     *
     * @param input source stream
     * @param output destination stream
     * @param maxBytes maximum bytes to copy
     * @param bufferBytes buffer size
     * @param digest optional digest to update, or {@code null}
     * @return bytes copied
     * @throws IOException if stream I/O fails, the current thread is
     * interrupted, or the configured maximum is exceeded
     */
    public static long copy(
            final InputStream input,
            final OutputStream output,
            final long maxBytes,
            final int bufferBytes,
            final MessageDigest digest) throws IOException {
        byte[] buffer = new byte[bufferBytes];
        long total = 0L;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("operation interrupted");
            }
            int read = input.read(buffer);
            if (read < 0) {
                return total;
            }
            total = checkedAdd(total, read, "stream size limit exceeded");
            if (total > maxBytes) {
                throw failure("stream size limit exceeded");
            }
            if (digest != null) {
                digest.update(buffer, 0, read);
            }
            output.write(buffer, 0, read);
        }
    }

    /**
     * Validates and normalizes a bundle entry name.
     *
     * @param name raw ZIP entry name
     * @param directory whether the entry is a directory
     * @param limits active platform limits
     * @return normalized slash-separated entry name
     * @throws MiddlewareException if the name is absolute, contains traversal,
     * Windows drive or UNC syntax, NUL/control characters, empty segments, or
     * exceeds configured length/depth limits
     */
    public static String normalizeEntryName(
            final String name,
            final boolean directory,
            final ApplicationPlatformLimits limits) {
        if (name == null || name.isBlank()) {
            throw failure("bundle entry name is invalid");
        }
        int bytes = name.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxEntryNameBytes()) {
            throw failure("bundle entry name exceeds configured limit");
        }
        if (name.indexOf('\0') >= 0 || containsControl(name) || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.startsWith("//") || hasWindowsDrivePrefix(name)) {
            throw failure("bundle entry name is unsafe");
        }
        String effective = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (effective.isBlank()) {
            throw failure("bundle entry name is invalid");
        }
        String[] segments = effective.split("/", -1);
        if (segments.length > limits.maxEntryPathDepth()) {
            throw failure("bundle entry path depth exceeds configured limit");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw failure("bundle entry name is unsafe");
            }
        }
        return String.join("/", segments);
    }

    /**
     * Adds two non-negative counters with overflow detection.
     *
     * @param left left operand
     * @param right right operand
     * @param message safe public failure message
     * @return sum
     * @throws MiddlewareException if the addition would overflow or
     * {@code right} is negative
     */
    public static long checkedAdd(final long left, final long right, final String message) {
        if (right < 0L || left > Long.MAX_VALUE - right) {
            throw failure(message);
        }
        return left + right;
    }

    /**
     * Writes a bounded UTF-8 metadata file through a same-directory temporary
     * file and replacement.
     *
     * <p>The temporary file is forced before publication. The target directory
     * is forced after replacement when the underlying file system supports
     * directory channels. If atomic replacement is not supported, the method
     * falls back to a regular replacement while preserving the same validation
     * and force ordering.</p>
     *
     * @param target target metadata path
     * @param text UTF-8 text to publish
     * @param maxBytes maximum encoded bytes
     * @throws IOException if the write, force, replace, or cleanup operation
     * fails
     * @throws MiddlewareException if the encoded text exceeds {@code maxBytes}
     */
    public static void writeSmallFileAtomically(
            final Path target,
            final String text,
            final long maxBytes) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw failure("metadata exceeds configured limit");
        }
        writeSmallFileAtomically(target, bytes);
    }

    /**
     * Writes a small metadata file through a same-directory temporary file and
     * replacement.
     *
     * @param target target metadata path
     * @param bytes bytes to publish
     * @throws IOException if the write, force, replace, or cleanup operation
     * fails
     */
    public static void writeSmallFileAtomically(final Path target, final byte[] bytes) throws IOException {
        if (target == null) {
            throw failure("metadata target is required");
        }
        Path directory = target.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            throw failure("metadata target directory is required");
        }
        Files.createDirectories(directory);
        Path temporary = directory.resolve(target.getFileName().toString() + "-" + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes == null ? new byte[0] : bytes, StandardOpenOption.CREATE_NEW);
            forceFile(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            forceDirectory(directory);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * Forces file data and metadata when the file system supports it.
     *
     * @param path regular file to force
     * @throws IOException if forcing fails
     */
    public static void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Forces a directory entry when the file system supports directory
     * channels. Unsupported platforms are treated as a documented best-effort
     * limitation.
     *
     * @param directory directory to force
     */
    public static void forceDirectory(final Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException exception) {
            ignoreUnsupportedDirectoryForce(exception);
        }
    }

    private static void ignoreUnsupportedDirectoryForce(final Exception exception) {
        java.util.Objects.requireNonNull(exception, "exception");
    }

    /**
     * Encodes bytes as lowercase hexadecimal text.
     *
     * <p>The method performs a deterministic two-character encoding for each
     * input byte and allocates only the output character array and result
     * string. The input array is read-only from this method's perspective and
     * is not retained.</p>
     *
     * @param bytes bytes to encode
     * @return lowercase hexadecimal representation of {@code bytes}
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public static String hex(final byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = HEX_ALPHABET[value >>> 4];
            output[index * 2 + 1] = HEX_ALPHABET[value & 0x0f];
        }
        return new String(output);
    }

    /**
     * Creates a safe public invalid-argument middleware exception.
     *
     * @param message safe public message that must not include caller payloads
     * @return middleware exception
     */
    public static MiddlewareException failure(final String message) {
        return new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, message,
                java.util.Map.of(), null, null, false, false));
    }

    private static boolean containsControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWindowsDrivePrefix(final String value) {
        return value.length() >= 2 && value.charAt(1) == ':'
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'));
    }
}
