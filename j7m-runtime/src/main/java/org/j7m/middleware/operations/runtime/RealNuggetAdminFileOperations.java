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

package org.j7m.middleware.operations.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Iterator;
import java.util.stream.Stream;

final class RealNuggetAdminFileOperations implements NuggetAdminFileOperations {
    static final RealNuggetAdminFileOperations INSTANCE = new RealNuggetAdminFileOperations();

    private static final Set<PosixFilePermission> FORBIDDEN_SHARED_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private RealNuggetAdminFileOperations() {
    }

    @Override
    public void validateProtectedFile(final Path path) throws IOException {
        if (path == null) {
            throw new IOException("protected file rejected");
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("protected file rejected");
        }
        validateProtectedPath(path);
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            validateProtectedDirectory(parent);
        }
    }

    @Override
    public void validateProtectedDirectory(final Path path) throws IOException {
        validateProtectedDirectoryInternal(path);
    }

    @Override
    public void ensureProtectedDirectory(final Path path) throws IOException {
        if (path == null) {
            throw new IOException("protected directory rejected");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            validateProtectedDirectoryInternal(path);
            return;
        }
        Files.createDirectories(path);
        trySetOwnerOnlyPermissions(path);
        validateProtectedDirectoryInternal(path);
    }

    @Override
    public NuggetAdminFileOperations.DiscoveryDirectoryEntries listDiscoveryEntries(
            final Path directory,
            final int maxEntries) throws IOException {
        validateProtectedDirectoryInternal(directory);
        List<Path> values = new ArrayList<>();
        int limit = Math.max(0, maxEntries);
        boolean incomplete = false;
        try (Stream<Path> stream = Files.list(directory)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (values.size() >= limit) {
                    incomplete = true;
                    break;
                }
                values.add(path);
            }
        }
        values.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return new NuggetAdminFileOperations.DiscoveryDirectoryEntries(values, incomplete);
    }

    @Override
    public byte[] readAllBytes(final Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public char[] readBoundedUtf8Chars(final Path path, final int maxBytes) throws IOException {
        if (path == null || maxBytes <= 0) {
            throw new IOException("protected PIN file rejected");
        }
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0L || before.size() > maxBytes || before.fileKey() == null) {
            throw new IOException("protected PIN file rejected");
        }
        byte[] bytes = new byte[(int) before.size()];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = channel.read(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
                if (read < 0) {
                    throw new IOException("protected PIN file rejected");
                }
                offset += read;
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("protected PIN file rejected");
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey())
                || before.size() != after.size()
                || after.size() != bytes.length
                || !after.isRegularFile()) {
            throw new IOException("protected PIN file rejected");
        }
        java.nio.CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            return chars;
        } catch (CharacterCodingException e) {
            throw new IOException("protected PIN file rejected", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    @Override
    public String readBoundedUtf8(final Path path, final int maxBytes) throws IOException {
        long size = Files.size(path);
        if (size <= 0L || size > maxBytes) {
            throw new IOException("protected verifier file rejected");
        }
        byte[] bytes = new byte[(int) size];
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new IOException("protected verifier file rejected");
                }
                offset += read;
            }
            if (input.read() >= 0) {
                throw new IOException("protected verifier file rejected");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("protected verifier file rejected", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public String readProtectedBoundedUtf8(final Path path, final int maxBytes) throws IOException {
        if (path == null || maxBytes <= 0) {
            throw new IOException("protected discovery record rejected");
        }
        validateProtectedFile(path);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0L || before.size() > maxBytes || before.fileKey() == null) {
            throw new IOException("protected discovery record rejected");
        }
        byte[] bytes = new byte[(int) before.size()];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = channel.read(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
                if (read < 0) {
                    throw new IOException("protected discovery record rejected");
                }
                offset += read;
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("protected discovery record rejected");
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey())
                || before.size() != after.size()
                || after.size() != bytes.length
                || !after.isRegularFile()) {
            throw new IOException("protected discovery record rejected");
        }
        validateProtectedFile(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("protected discovery record rejected", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void persistVerifierRecord(final Path path, final byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > NuggetPinVerifier.MAX_RECORD_BYTES) {
            throw new IOException("verifier upgrade persistence failed");
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
            trySetOwnerOnlyPermissions(tmp);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            validateProtectedFile(tmp);
            if (Files.size(tmp) != bytes.length) {
                throw new IOException("verifier upgrade persistence failed");
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(parent);
            tmp = null;
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Override
    public void persistDiscoveryRecord(final Path directory, final String fileName, final byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > NuggetDiscoveryRecordCodec.MAX_RECORD_BYTES || fileName == null
                || !fileName.endsWith(".j7m-discovery") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IOException("discovery record persistence failed");
        }
        ensureProtectedDirectory(directory);
        Path path = directory.resolve(fileName);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(directory, "." + fileName, ".tmp");
            trySetOwnerOnlyPermissions(tmp);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            validateProtectedFile(tmp);
            if (Files.size(tmp) != bytes.length) {
                throw new IOException("discovery record persistence failed");
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(directory);
            tmp = null;
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Override
    public void removeDiscoveryRecord(final Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            validateProtectedFile(path);
            Files.deleteIfExists(path);
        }
    }

    @Override
    public void cleanupDiscoveryTemps(final Path directory, final int maxDeletes) throws IOException {
        if (directory == null || maxDeletes <= 0 || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        validateProtectedDirectoryInternal(directory);
        int[] deleted = {0};
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> examined = new ArrayList<>(maxDeletes);
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext() && examined.size() < maxDeletes) {
                examined.add(iterator.next());
            }
            examined.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path path : examined) {
                if (path.getFileName().toString().endsWith(".tmp")) {
                        if (deleted[0] < maxDeletes && !Files.isSymbolicLink(path)) {
                            try {
                                Files.deleteIfExists(path);
                                deleted[0]++;
                            } catch (IOException ignored) {
                                // Cleanup is bounded best-effort.
                            }
                        }
                }
            }
        }
    }

    private static void validateProtectedPath(final Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        FileOwnerAttributeView ownerView = Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null || ownerView == null) {
            throw new NuggetAdminFileSecurityUnavailableException("protected file permissions unavailable");
        }
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        if (!java.util.Collections.disjoint(permissions, FORBIDDEN_SHARED_PERMISSIONS)) {
            throw new IOException("protected file permissions rejected");
        }
        UserPrincipal owner = ownerView.getOwner();
        UserPrincipal current = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name", ""));
        if (!owner.equals(current)) {
            throw new IOException("protected file owner rejected");
        }
    }

    private static void validateProtectedDirectoryInternal(final Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("protected directory rejected");
        }
        PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        FileOwnerAttributeView ownerView = Files.getFileAttributeView(directory, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null || ownerView == null) {
            throw new NuggetAdminFileSecurityUnavailableException("protected file permissions unavailable");
        }
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("protected directory permissions rejected");
        }
        UserPrincipal owner = ownerView.getOwner();
        UserPrincipal current = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name", ""));
        if (!owner.equals(current)) {
            throw new IOException("protected directory owner rejected");
        }
    }

    private static void trySetOwnerOnlyPermissions(final Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static void forceDirectory(final Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is best-effort and not available on every platform.
        }
    }
}
