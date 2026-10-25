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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.internal.BoundedBundleIO;

/**
 * Writer for development {@code .j7m} bundles.
 *
 * <p>The writer creates deterministic ZIP entries and checksum metadata. It is
 * suitable for tests and development packaging; production signing remains an
 * extension point.</p>
 */
public final class J7mBundleWriter {
    private final ApplicationManifestParser parser;
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a writer.
     */
    public J7mBundleWriter() {
        this(ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a writer with explicit bounded resource limits.
     *
     * @param limits bundle and metadata limits
     */
    public J7mBundleWriter(final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.parser = new ApplicationManifestParser(this.limits);
    }

    /**
     * Writes a classpath-mode bundle.
     *
     * @param manifestPath manifest JSON path
     * @param outputPath output bundle path
     * @param libraries library JARs to place under {@code lib/}
     * @param docs documentation files to place under {@code docs/}
     * @return written bundle descriptor
     */
    public ApplicationBundle write(final Path manifestPath, final Path outputPath, final List<Path> libraries, final List<Path> docs) {
        return this.write(manifestPath, outputPath, libraries, List.of(), docs);
    }

    /**
     * Writes a bundle with optional classpath libraries and JPMS module JARs.
     *
     * @param manifestPath manifest JSON path
     * @param outputPath output bundle path
     * @param libraries library JARs to place under {@code lib/}
     * @param modules module JARs to place under {@code modules/}
     * @param docs documentation files to place under {@code docs/}
     * @return written bundle descriptor
     */
    public ApplicationBundle write(
            final Path manifestPath,
            final Path outputPath,
            final List<Path> libraries,
            final List<Path> modules,
            final List<Path> docs) {
        return this.write(manifestPath, outputPath, libraries, modules, docs, List.of());
    }

    /**
     * Writes a bundle with optional libraries, JPMS module JARs, documentation,
     * and configuration files.
     *
     * @param manifestPath manifest JSON path
     * @param outputPath output bundle path
     * @param libraries library JARs to place under {@code lib/}
     * @param modules module JARs to place under {@code modules/}
     * @param docs documentation files to place under {@code docs/}
     * @param configurationFiles configuration files to place under {@code config/}
     * @return written bundle descriptor
     */
    public ApplicationBundle write(
            final Path manifestPath,
            final Path outputPath,
            final List<Path> libraries,
            final List<Path> modules,
            final List<Path> docs,
            final List<Path> configurationFiles) {
        Path temporary = null;
        try {
            Path absoluteOutput = outputPath.toAbsolutePath().normalize();
            Path outputDirectory = absoluteOutput.getParent();
            if (outputDirectory == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle output directory is required");
            }
            Files.createDirectories(outputDirectory);
            if (Files.size(manifestPath) > this.limits.maxManifestBytes()) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "manifest exceeds configured limit");
            }
            String manifestText = Files.readString(manifestPath, StandardCharsets.UTF_8);
            ApplicationCoordinate coordinate = this.parser.parse(manifestText).identity().coordinate();
            List<SourceEntry> entries = new ArrayList<>();
            Set<String> normalizedNames = new HashSet<>();
            byte[] manifestBytes = manifestText.getBytes(StandardCharsets.UTF_8);
            this.addMemoryEntry(entries, normalizedNames, J7mBundleReader.MANIFEST_ENTRY, manifestBytes);
            for (Path library : libraries == null ? List.<Path>of() : libraries) {
                this.addFile(entries, normalizedNames, "lib/" + library.getFileName(), library, this.limits.maxEntryExpandedBytes());
            }
            for (Path module : modules == null ? List.<Path>of() : modules) {
                this.addFile(entries, normalizedNames, "modules/" + module.getFileName(), module, this.limits.maxEntryExpandedBytes());
            }
            for (Path doc : docs == null ? List.<Path>of() : docs) {
                this.addFile(entries, normalizedNames, "docs/" + doc.getFileName(), doc, this.limits.maxMetadataBytes());
            }
            for (Path configurationFile : configurationFiles == null ? List.<Path>of() : configurationFiles) {
                this.addFile(entries, normalizedNames, "config/" + configurationFile.getFileName(),
                        configurationFile, this.limits.maxMetadataBytes());
            }
            this.addOptionalMetadata(entries, normalizedNames, manifestPath);
            if (!normalizedNames.contains(J7mBundleReader.PROVENANCE_ENTRY)) {
                this.addMemoryEntry(entries, normalizedNames, J7mBundleReader.PROVENANCE_ENTRY,
                        "{\"mode\":\"development\"}\n".getBytes(StandardCharsets.UTF_8));
            }
            entries.sort(Comparator.comparing(SourceEntry::entryName));
            temporary = Files.createTempFile(outputDirectory, ".j7m-write-", ".tmp");
            Map<String, String> checksums = new LinkedHashMap<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING))) {
                long aggregate = 0L;
                for (SourceEntry entry : entries) {
                    aggregate = BoundedBundleIO.checkedAdd(aggregate, entry.size(), "bundle expanded size limit exceeded");
                    if (aggregate > this.limits.maxAggregateExpandedBytes()) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle expanded size limit exceeded");
                    }
                    checksums.put(entry.entryName(), this.writeEntry(zip, entry));
                    entry.verifyUnchanged();
                }
                byte[] checksumBytes = this.checksumText(checksums).getBytes(StandardCharsets.UTF_8);
                if (checksumBytes.length > this.limits.maxChecksumBytes()) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "checksum metadata exceeds configured limit");
                }
                this.writeMemoryEntry(zip, J7mBundleReader.CHECKSUM_ENTRY, checksumBytes, null);
            }
            this.forceFile(temporary);
            this.moveCompleted(temporary, absoluteOutput);
            temporary = null;
            this.forceDirectory(outputDirectory);
            String bundleHash = new J7mBundleReader(this.limits).sha256(absoluteOutput);
            return new ApplicationBundle(absoluteOutput, coordinate, bundleHash);
        } catch (IOException | IllegalArgumentException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "failed to write application bundle", Map.of(), null, null, false, false), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup after a failed publish; the original output was not replaced.
                }
            }
        }
    }

    private void addFile(
            final List<SourceEntry> entries,
            final Set<String> normalizedNames,
            final String entryName,
            final Path path,
            final long maxBytes) throws IOException {
        String normalized = BoundedBundleIO.normalizeEntryName(entryName, false, this.limits);
        if (!normalizedNames.add(normalized)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate bundle entry");
        }
        if (!Files.isRegularFile(path)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle source is not a regular file");
        }
        long size = Files.size(path);
        if (size > maxBytes || size > this.limits.maxEntryExpandedBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle source exceeds configured limit");
        }
        entries.add(SourceEntry.file(normalized, path, size, Files.getLastModifiedTime(path)));
    }

    private void addOptionalMetadata(
            final List<SourceEntry> entries,
            final Set<String> normalizedNames,
            final Path manifestPath) throws IOException {
        Path metadataDirectory = manifestPath.toAbsolutePath().getParent();
        if (metadataDirectory == null) {
            return;
        }
        this.addOptionalMetadata(entries, normalizedNames, metadataDirectory, J7mBundleReader.PROVENANCE_ENTRY, this.limits.maxMetadataBytes());
        this.addOptionalMetadata(entries, normalizedNames, metadataDirectory, J7mBundleReader.SBOM_ENTRY, this.limits.maxMetadataBytes());
        this.addOptionalMetadata(entries, normalizedNames, metadataDirectory, J7mBundleReader.SIGNATURE_ENTRY, this.limits.maxSignatureBytes());
    }

    private void addOptionalMetadata(
            final List<SourceEntry> entries,
            final Set<String> normalizedNames,
            final Path metadataDirectory,
            final String entryName,
            final long maxBytes) throws IOException {
        Path source = metadataDirectory.resolve(Path.of(entryName).getFileName());
        if (Files.isRegularFile(source)) {
            this.addFile(entries, normalizedNames, entryName, source, maxBytes);
        }
    }

    private void addMemoryEntry(
            final List<SourceEntry> entries,
            final Set<String> normalizedNames,
            final String entryName,
            final byte[] bytes) {
        String normalized = BoundedBundleIO.normalizeEntryName(entryName, false, this.limits);
        if (!normalizedNames.add(normalized)) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "duplicate bundle entry");
        }
        if (bytes.length > this.limits.maxEntryExpandedBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle metadata exceeds configured limit");
        }
        entries.add(SourceEntry.memory(normalized, bytes));
    }

    private String checksumText(final Map<String, String> checksums) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            if (J7mBundleReader.SIGNATURE_ENTRY.equals(entry.getKey())) {
                continue;
            }
            builder.append(entry.getValue()).append(' ').append(entry.getKey()).append('\n');
        }
        return builder.toString();
    }

    private String writeEntry(final ZipOutputStream zip, final SourceEntry entry) throws IOException {
        if (entry.bytes() != null) {
            return this.writeMemoryEntry(zip, entry.entryName(), entry.bytes(), null);
        }
        MessageDigest digest = this.sha256Digest();
        ZipEntry zipEntry = new ZipEntry(entry.entryName());
        zipEntry.setTime(0L);
        zip.putNextEntry(zipEntry);
        try (InputStream input = Files.newInputStream(entry.path())) {
            BoundedBundleIO.copy(input, zip, this.limits.maxEntryExpandedBytes(), this.limits.ioBufferBytes(), digest);
        }
        zip.closeEntry();
        return J7mBundleReader.hex(digest.digest());
    }

    private String writeMemoryEntry(
            final ZipOutputStream zip,
            final String entryName,
            final byte[] bytes,
            final MessageDigest providedDigest) throws IOException {
        MessageDigest digest = providedDigest == null ? this.sha256Digest() : providedDigest;
        ZipEntry zipEntry = new ZipEntry(entryName);
        zipEntry.setTime(0L);
        zip.putNextEntry(zipEntry);
        zip.write(bytes);
        digest.update(bytes);
        zip.closeEntry();
        return J7mBundleReader.hex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "SHA-256 unavailable", Map.of(), null, null, false, true), exception);
        }
    }

    private void moveCompleted(final Path temporary, final Path output) throws IOException {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(final Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException exception) {
            ignoreBestEffortIoFailure(exception);
        }
    }

    private static void ignoreBestEffortIoFailure(final IOException exception) {
        Objects.requireNonNull(exception, "exception");
    }

    private record SourceEntry(String entryName, Path path, byte[] bytes, long size, FileTime lastModifiedTime) {
        private static SourceEntry file(
                final String entryName,
                final Path path,
                final long size,
                final FileTime lastModifiedTime) {
            return new SourceEntry(entryName, path, null, size, lastModifiedTime);
        }

        private static SourceEntry memory(final String entryName, final byte[] bytes) {
            return new SourceEntry(entryName, null, bytes.clone(), bytes.length, null);
        }

        private void verifyUnchanged() throws IOException {
            if (this.path == null) {
                return;
            }
            if (Files.size(this.path) != this.size || !Files.getLastModifiedTime(this.path).equals(this.lastModifiedTime)) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "bundle source changed during packaging");
            }
        }
    }
}
