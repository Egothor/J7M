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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationRepository;
import org.j7m.middleware.application.api.ApplicationRepositoryStatus;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.internal.BoundedBundleIO;

/**
 * Local filesystem application repository.
 *
 * <p>The repository is synchronized per instance. Published artifacts are
 * immutable and are verified before storage. Audit events are appended as JSON
 * lines. It does not replicate artifacts or provide consensus.</p>
 */
public final class LocalApplicationRepository implements ApplicationRepository {
    private static final String COMPLETE_MARKER = ".j7m-complete";
    private static final String AUDIT_CHECKPOINT_SCHEMA = "j7m.repository.audit-checkpoint/v1";
    private final Path root;
    private final Clock clock;
    private final ApplicationBundleVerifier verifier;
    private final ApplicationPlatformLimits limits;
    private AuditCheckpoint auditCheckpoint;

    /**
     * A bounded page of repository audit events.
     *
     * <p>The cursor is a repository-local byte offset in the append-only audit
     * log. Callers must treat it as an opaque continuation token and pass back
     * only values previously returned by this repository instance or by another
     * instance over the same unchanged repository. The page is immutable and
     * contains only raw JSON Lines event text that has passed the repository's
     * configured line-size bound. The end flag is explicit so callers do not
     * need to infer completion from a short page.</p>
     *
     * @param events bounded immutable audit event lines
     * @param nextCursor cursor to use for the next page
     * @param end whether the page reached the end of the current audit log
     */
    public static record AuditPage(List<String> events, long nextCursor, boolean end) {
        /**
         * Creates an immutable audit page.
         *
         * @param events bounded audit event lines
         * @param nextCursor next continuation cursor, never negative
         * @param end whether the page reached the current end of the audit log
         * @throws NullPointerException if {@code events} is {@code null}
         * @throws IllegalArgumentException if {@code nextCursor} is negative
         */
        public AuditPage {
            Objects.requireNonNull(events, "events");
            if (nextCursor < 0L) {
                throw new IllegalArgumentException("nextCursor must not be negative");
            }
            events = List.copyOf(events);
        }
    }

    /**
     * Creates a repository rooted at a local path using the system UTC clock.
     *
     * @param root repository root directory
     */
    public LocalApplicationRepository(final Path root) {
        this(root, Clock.systemUTC());
    }

    /**
     * Creates a repository rooted at a local path.
     *
     * @param root repository root directory
     * @param clock clock used for audit timestamps
     */
    public LocalApplicationRepository(final Path root, final Clock clock) {
        this(root, clock, ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a repository rooted at a local path with explicit platform
     * limits.
     *
     * @param root repository root directory
     * @param clock clock used for audit timestamps
     * @param limits repository and bundle limits
     */
    public LocalApplicationRepository(final Path root, final Clock clock, final ApplicationPlatformLimits limits) {
        this(root, clock, limits, PublicKeyTrustStore.localDevelopment());
    }

    /**
     * Creates a repository with explicit operator-owned bundle-signing trust.
     * Public keys are never loaded from an application's own manifest or signature assertions.
     * @param root repository storage directory
     * @param clock audit clock, or null for UTC
     * @param limits repository bounds, or null for defaults
     * @param signingTrust immutable trusted public keys; an empty store denies signed strict publication
     * @throws NullPointerException for missing root or trust store
     * @throws MiddlewareException if storage initialization fails
     */
    public LocalApplicationRepository(final Path root, final Clock clock, final ApplicationPlatformLimits limits,
            final PublicKeyTrustStore signingTrust) {
        this.root = Objects.requireNonNull(root);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        this.verifier = new ApplicationBundleVerifier(Objects.requireNonNull(signingTrust), this.limits);
        try {
            Files.createDirectories(this.root.resolve("artifacts"));
            Files.createDirectories(this.root.resolve("promotions"));
            Files.createDirectories(this.root.resolve("audit"));
            Files.createDirectories(this.root.resolve("staging"));
            Files.createDirectories(this.root.resolve("quarantine"));
            this.recoverIncompletePublications();
            this.auditCheckpoint = this.recoverAuditCheckpoint();
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to initialize application repository", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationDeploymentResult publish(final ApplicationBundle bundle, final RepositoryPublishOptions options) {
        RepositoryPublishOptions safeOptions = options == null ? new RepositoryPublishOptions("system", false) : options;
        SignaturePolicy policy = ApplicationBundleVerifier.policy(safeOptions.verificationProfile(), safeOptions.strictVerification());
        ApplicationVerificationResult verification = this.verifier.verify(bundle, policy);
        if (!verification.accepted()) {
            this.audit("reject", bundle.coordinate(), String.join("; ", verification.messages()));
            return new ApplicationDeploymentResult(false, ApplicationRunState.REJECTED, "bundle rejected", verification.messages());
        }
        Path target = this.bundlePath(verification.coordinate());
        if (Files.exists(target)) {
            this.audit("reject", verification.coordinate(), "artifact already exists");
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application artifact is immutable and already exists");
        }
        Path targetDirectory = target.getParent();
        Path staging = this.root.resolve("staging").resolve("publish-" + UUID.randomUUID());
        try {
            if (Files.exists(targetDirectory)) {
                this.quarantine(targetDirectory, "incomplete-final-before-publish");
            }
            Files.createDirectories(staging);
            Path stagedArtifact = staging.resolve(target.getFileName().toString());
            String artifactDigest = this.copyArtifact(bundle.path(), stagedArtifact);
            if (!artifactDigest.equals(new J7mBundleReader(this.limits).sha256(stagedArtifact))) {
                throw MiddlewareException.of(MiddlewareErrorCode.INTERNAL, "staged artifact digest verification failed");
            }
            BoundedBundleIO.writeSmallFileAtomically(staging.resolve("metadata.json"),
                    "{\"coordinate\":\"" + SimpleJson.escape(verification.coordinate().toString()) + "\",\"publisher\":\""
                            + SimpleJson.escape(safeOptions.publisher()) + "\"}\n",
                    this.limits.maxMetadataBytes());
            this.writeVerificationRecord(staging.resolve("verification.json"), verification, policy, artifactDigest);
            BoundedBundleIO.writeSmallFileAtomically(staging.resolve(COMPLETE_MARKER), "complete\n",
                    this.limits.maxMetadataBytes());
            this.forceDirectory(staging);
            Files.createDirectories(targetDirectory.getParent());
            this.moveDirectory(staging, targetDirectory);
            staging = null;
            this.forceDirectory(targetDirectory.getParent());
            this.writeSummary();
            this.audit("publish", verification.coordinate(), "accepted");
            return new ApplicationDeploymentResult(true, ApplicationRunState.DEPLOYED, "published", verification.messages());
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to publish application bundle", java.util.Map.of(), null, null, false, true), exception);
        } finally {
            if (staging != null) {
                this.deleteTree(staging);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationBundle fetch(final ApplicationCoordinate coordinate) {
        Path path = this.bundlePath(coordinate);
        if (!Files.exists(path)) {
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "application coordinate not found");
        }
        return new ApplicationBundle(path, coordinate, new J7mBundleReader(this.limits).sha256(path));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized List<ApplicationVersion> listVersions(final ApplicationId applicationId) {
        Path directory = this.root.resolve("artifacts").resolve(applicationId.group().replace('.', '/')).resolve(applicationId.name());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            List<ApplicationVersion> versions = new ArrayList<>();
            BoundedCounter examined = new BoundedCounter(this.limits.maxEntryCount(),
                    "repository version listing exceeds configured scan limit");
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                examined.increment();
                if (Files.isDirectory(path)) {
                    if (versions.size() == this.limits.maxEntryCount()) {
                        throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED,
                                "repository version listing exceeds configured limit");
                    }
                    versions.add(new ApplicationVersion(path.getFileName().toString()));
                }
            }
            versions.sort(Comparator.comparing(ApplicationVersion::value));
            return List.copyOf(versions);
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to list application versions", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationVerificationResult verify(final ApplicationCoordinate coordinate) {
        ApplicationBundle bundle = this.fetch(coordinate);
        SignaturePolicy policy = this.storedPolicy(coordinate);
        ApplicationVerificationResult result = this.verifier.verify(bundle, policy);
        if (result.accepted()) {
            this.writeVerificationRecord(this.bundlePath(coordinate).getParent().resolve("verification.json"),
                    result, policy, bundle.sha256());
        }
        this.audit("verify", coordinate, Boolean.toString(result.accepted()));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationDeploymentResult promote(final ApplicationCoordinate coordinate, final String environment) {
        this.fetch(coordinate);
        String safeEnvironment = safeEnvironment(environment);
        try {
            BoundedBundleIO.writeSmallFileAtomically(this.root.resolve("promotions").resolve(safeEnvironment + ".json"),
                    "{\"schemaVersion\":\"1\",\"coordinate\":\"" + SimpleJson.escape(coordinate.toString()) + "\",\"state\":\"PROMOTED\"}\n",
                    this.limits.maxMetadataBytes());
            this.writeSummary();
            this.audit("promote", coordinate, safeEnvironment);
            return new ApplicationDeploymentResult(true, ApplicationRunState.DEPLOYED, "promoted", List.of(safeEnvironment));
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to promote application", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationDeploymentResult demote(final ApplicationCoordinate coordinate, final String environment) {
        String safeEnvironment = safeEnvironment(environment);
        try {
            BoundedBundleIO.writeSmallFileAtomically(this.root.resolve("promotions").resolve(safeEnvironment + ".json"),
                    "{\"schemaVersion\":\"1\",\"coordinate\":\"" + SimpleJson.escape(coordinate.toString()) + "\",\"state\":\"DEMOTED\"}\n",
                    this.limits.maxMetadataBytes());
            this.writeSummary();
            this.audit("demote", coordinate, safeEnvironment);
            return new ApplicationDeploymentResult(true, ApplicationRunState.DEPLOYED, "demoted", List.of(safeEnvironment));
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to demote application", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized ApplicationRepositoryStatus status() {
        try {
            Map<String, String> summary = this.readSummary();
            int artifacts = parseInt(summary.get("artifactCount"));
            int promotions = parseInt(summary.get("promotionCount"));
            return new ApplicationRepositoryStatus(Path.of("<redacted>"), artifacts, promotions, java.util.Map.of(
                    "type", "local-filesystem",
                    "auditChainValid", summary.getOrDefault("auditChainValid", "true"),
                    "auditChainStatus", "explicit-verification-required",
                    "signatureVerification", "built-in-ed25519",
                    "strictRegulatedProfile", "signature+sbom+provenance"));
        } catch (IOException | RuntimeException exception) {
            return new ApplicationRepositoryStatus(Path.of("<redacted>"), 0, 0, java.util.Map.of(
                    "type", "local-filesystem",
                    "auditChainValid", "false",
                    "auditChainStatus", "degraded",
                    "signatureVerification", "unknown",
                    "strictRegulatedProfile", "signature+sbom+provenance"));
        }
    }

    /**
     * Reads repository audit events as JSON Lines text.
     *
     * @return repository audit lines
     */
    public synchronized List<String> auditEvents() {
        return this.auditEvents(this.limits.maxAuditEventsPerPage());
    }

    /**
     * Reads at most {@code maxEvents} repository audit events.
     *
     * @param maxEvents maximum lines to return
     * @return bounded repository audit lines
     */
    public synchronized List<String> auditEvents(final int maxEvents) {
        return this.auditEventsPage(0L, maxEvents).events();
    }

    /**
     * Reads the first bounded page of repository audit events.
     *
     * <p>This method is safe for operator status and administrative paging. It
     * reads at most the configured page size from the append-only audit log and
     * returns an explicit continuation cursor. It does not verify the audit hash
     * chain; callers that need chain validation must invoke
     * {@link #verifyAuditChain()} explicitly.</p>
     *
     * @param maxEvents maximum number of event lines to return
     * @return immutable audit page with a continuation cursor
     */
    public synchronized AuditPage auditEventsPage(final int maxEvents) {
        return this.auditEventsPage(0L, maxEvents);
    }

    /**
     * Reads a bounded page of repository audit events from a continuation
     * cursor.
     *
     * <p>The cursor must be {@code 0} or a cursor returned by a previous call.
     * The implementation validates that non-zero cursors align with a line
     * boundary and rejects negative or out-of-range values. Corrupt, malformed
     * UTF-8, and overlong lines are rejected without returning unsafe content,
     * so each call performs bounded allocation and deterministic line reads.</p>
     *
     * @param cursor repository-local continuation cursor
     * @param maxEvents maximum number of event lines to return
     * @return immutable audit page with a continuation cursor and end flag
     * @throws MiddlewareException if the cursor is invalid or the audit log
     *         cannot be read
     */
    public synchronized AuditPage auditEventsPage(final long cursor, final int maxEvents) {
        Path audit = this.root.resolve("audit").resolve("repository-events.jsonl");
        if (!Files.exists(audit)) {
            return new AuditPage(List.of(), 0L, true);
        }
        int limit = Math.max(0, Math.min(maxEvents, this.limits.maxAuditEventsPerPage()));
        long fileSize;
        try {
            fileSize = Files.size(audit);
            this.validateAuditCursor(audit, cursor, fileSize);
            if (limit == 0 || cursor == fileSize) {
                return new AuditPage(List.of(), cursor, cursor >= fileSize);
            }
            List<String> lines = new ArrayList<>(limit);
            long nextCursor = cursor;
            int inspected = 0;
            try (FileChannel channel = FileChannel.open(audit, StandardOpenOption.READ)) {
                while (lines.size() < limit && inspected < this.limits.maxAuditEventsPerPage()) {
                    AuditLine line = this.readAuditLine(channel, nextCursor, fileSize);
                    if (line.end()) {
                        nextCursor = line.nextCursor();
                        break;
                    }
                    inspected++;
                    nextCursor = line.nextCursor();
                    if (!line.text().isBlank()) {
                        lines.add(line.text());
                    }
                }
            }
            return new AuditPage(lines, Math.min(nextCursor, fileSize), nextCursor >= fileSize);
        } catch (CharacterCodingException exception) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit line is malformed");
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "failed to read repository audit events", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * Validates the repository audit hash chain.
     *
     * @return whether all available audit lines verify
     */
    public synchronized boolean verifyAuditChain() {
        Path audit = this.root.resolve("audit").resolve("repository-events.jsonl");
        if (!Files.exists(audit)) {
            return true;
        }
        try {
            String previous = "";
            long cursor = 0L;
            long fileSize = Files.size(audit);
            try (FileChannel channel = FileChannel.open(audit, StandardOpenOption.READ)) {
                while (cursor < fileSize) {
                    AuditLine auditLine = this.readAuditLine(channel, cursor, fileSize);
                    if (auditLine.end()) {
                        break;
                    }
                    cursor = auditLine.nextCursor();
                    String line = auditLine.text();
                    if (line.isBlank()) {
                        continue;
                    }
                    Object parsed = SimpleJson.parse(line, this.limits);
                    if (!(parsed instanceof Map<?, ?> map)) {
                        return false;
                    }
                    String linePrevious = string(map, "previousHash");
                    String current = string(map, "currentHash");
                    if (!previous.equals(linePrevious)) {
                        return false;
                    }
                    String canonical = auditCanonical(
                            string(map, "timestamp"),
                            string(map, "action"),
                            string(map, "coordinate"),
                            string(map, "result"),
                            linePrevious);
                    if (!sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(current)) {
                        return false;
                    }
                    previous = current;
                }
            }
            AuditCheckpoint checkpoint = new AuditCheckpoint(previous, Files.size(audit), false);
            this.auditCheckpoint = checkpoint;
            this.writeAuditCheckpoint(checkpoint);
            this.writeSummary();
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Checks whether a stored artifact has an accepted verification record for
     * the requested activation policy and current artifact digest.
     *
     * @param coordinate exact application coordinate
     * @param policy requested activation verification policy
     * @return whether activation may proceed
     */
    public synchronized boolean verifiedForActivation(final ApplicationCoordinate coordinate, final SignaturePolicy policy) {
        Path path = this.bundlePath(coordinate);
        Path verification = path.getParent().resolve("verification.json");
        if (!Files.exists(path) || !Files.exists(verification)) {
            return false;
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(verification, StandardCharsets.UTF_8), this.limits);
            if (!(parsed instanceof Map<?, ?> map)) {
                return false;
            }
            if (!"true".equals(string(map, "accepted"))) {
                return false;
            }
            String digest = new J7mBundleReader(this.limits).sha256(path);
            if (!digest.equals(string(map, "artifactDigest"))) {
                return false;
            }
            SignaturePolicy stored = ApplicationBundleVerifier.policy(string(map, "policy"), false);
            SignaturePolicy requested = policy == null ? SignaturePolicy.DEVELOPMENT : policy;
            if (requested == SignaturePolicy.STRICT_REGULATED && stored != SignaturePolicy.STRICT_REGULATED) {
                return false;
            }
            if (requested == SignaturePolicy.STRICT_PRODUCTION && stored == SignaturePolicy.DEVELOPMENT) {
                return false;
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns the stored verification policy for an accepted artifact.
     *
     * @param coordinate exact application coordinate
     * @return stored verification policy, or development when no record exists
     */
    public synchronized SignaturePolicy verificationPolicy(final ApplicationCoordinate coordinate) {
        return this.storedPolicy(coordinate);
    }

    /**
     * Returns safe verification metadata for an accepted artifact.
     *
     * @param coordinate exact application coordinate
     * @return safe verification metadata, or an empty map when unavailable
     */
    public synchronized Map<String, String> verificationMetadata(final ApplicationCoordinate coordinate) {
        Path verification = this.bundlePath(coordinate).getParent().resolve("verification.json");
        if (!Files.exists(verification)) {
            return Map.of();
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(verification, StandardCharsets.UTF_8), this.limits);
            if (!(parsed instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> output = new LinkedHashMap<>();
            output.put("policy", string(map, "policy"));
            output.put("artifactDigest", string(map, "artifactDigest"));
            output.put("signature", string(map, "signature"));
            output.put("sbom", string(map, "sbom"));
            output.put("provenance", string(map, "provenance"));
            output.put("accepted", string(map, "accepted"));
            return Map.copyOf(output);
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * Returns the local repository root path.
     *
     * <p>The path is exposed for repository-platform components such as local
     * replication and repair engines. Callers must treat artifact files as
     * immutable and use repository verification APIs before promotion or
     * activation.</p>
     *
     * @return repository root path
     */
    public Path repositoryRoot() {
        return this.root;
    }

    private Path bundlePath(final ApplicationCoordinate coordinate) {
        return this.root.resolve("artifacts")
                .resolve(coordinate.id().group().replace('.', '/'))
                .resolve(coordinate.id().name())
                .resolve(coordinate.version().value())
                .resolve(coordinate.id().name() + "-" + coordinate.version().value() + ".j7m");
    }

    private void audit(final String action, final ApplicationCoordinate coordinate, final String result) {
        try {
            Path audit = this.root.resolve("audit").resolve("repository-events.jsonl");
            Files.createDirectories(audit.getParent());
            String safeAction = this.safeAuditText(action);
            String coordinateText = this.safeAuditText(coordinate == null ? "" : coordinate.toString());
            String safeResult = this.safeAuditText(result);
            String timestamp = this.clock.instant().toString();
            AuditCheckpoint checkpoint = this.currentAuditCheckpoint(audit);
            String previousHash = checkpoint.degraded() ? "" : checkpoint.lastHash();
            String currentHash = sha256(auditCanonical(timestamp, safeAction, coordinateText, safeResult, previousHash)
                    .getBytes(StandardCharsets.UTF_8));
            String line = "{\"timestamp\":\"" + timestamp + "\",\"action\":\"" + SimpleJson.escape(safeAction)
                    + "\",\"coordinate\":\"" + SimpleJson.escape(coordinateText) + "\",\"result\":\""
                    + SimpleJson.escape(safeResult) + "\",\"previousHash\":\"" + previousHash
                    + "\",\"currentHash\":\"" + currentHash + "\"}\n";
            this.validateAuditAppendLine(line);
            Files.writeString(audit, line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            BoundedBundleIO.forceFile(audit);
            AuditCheckpoint written = new AuditCheckpoint(currentHash, Files.size(audit), checkpoint.degraded());
            this.auditCheckpoint = written;
            this.writeAuditCheckpoint(written);
            this.writeSummary();
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL, "failed to append repository audit event", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    private void writeVerificationRecord(
            final Path path,
            final ApplicationVerificationResult verification,
            final SignaturePolicy policy,
            final String artifactDigest) {
        try {
            Files.createDirectories(path.getParent());
            Map<String, String> values = new LinkedHashMap<>();
            values.put("accepted", Boolean.toString(verification.accepted()));
            values.put("coordinate", verification.coordinate() == null ? "" : verification.coordinate().toString());
            values.put("policy", policy.name());
            values.put("artifactDigest", artifactDigest);
            values.put("signature", containsMessage(verification, "valid=true") ? "verified" : "not-verified");
            values.put("sbom", containsMessage(verification, "sbom present=true") ? "present" : "missing");
            values.put("provenance", containsMessage(verification, "provenance present=true") ? "present" : "missing");
            BoundedBundleIO.writeSmallFileAtomically(path, SimpleJson.writeStringMap(values) + "\n",
                    this.limits.maxMetadataBytes());
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "failed to write verification record", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    private SignaturePolicy storedPolicy(final ApplicationCoordinate coordinate) {
        Path verification = this.bundlePath(coordinate).getParent().resolve("verification.json");
        if (!Files.exists(verification)) {
            return SignaturePolicy.DEVELOPMENT;
        }
        try {
            Object parsed = SimpleJson.parse(Files.readString(verification, StandardCharsets.UTF_8), this.limits);
            if (parsed instanceof Map<?, ?> map) {
                return ApplicationBundleVerifier.policy(string(map, "policy"), false);
            }
            return SignaturePolicy.DEVELOPMENT;
        } catch (IOException | RuntimeException exception) {
            return SignaturePolicy.DEVELOPMENT;
        }
    }

    private AuditCheckpoint currentAuditCheckpoint(final Path audit) throws IOException {
        long fileSize = Files.exists(audit) ? Files.size(audit) : 0L;
        AuditCheckpoint checkpoint = this.auditCheckpoint;
        if (checkpoint != null && checkpoint.fileSize() == fileSize) {
            return checkpoint;
        }
        AuditCheckpoint recovered = this.recoverAuditCheckpointFromTail(audit, fileSize);
        this.auditCheckpoint = recovered;
        if (!recovered.degraded()) {
            this.writeAuditCheckpoint(recovered);
        }
        return recovered;
    }

    private AuditCheckpoint recoverAuditCheckpoint() throws IOException {
        Path audit = this.root.resolve("audit").resolve("repository-events.jsonl");
        long auditSize = Files.exists(audit) ? Files.size(audit) : 0L;
        Path checkpoint = this.auditCheckpointPath();
        if (Files.isRegularFile(checkpoint) && Files.size(checkpoint) <= this.limits.maxMetadataBytes()) {
            try {
                Object parsed = SimpleJson.parse(Files.readString(checkpoint, StandardCharsets.UTF_8), this.limits);
                if (parsed instanceof Map<?, ?> map) {
                    String schema = string(map, "schemaVersion");
                    long fileSize = parseLong(string(map, "fileSize"), -1L);
                    String lastHash = string(map, "lastHash");
                    boolean degraded = Boolean.parseBoolean(string(map, "degraded"));
                    if (AUDIT_CHECKPOINT_SCHEMA.equals(schema)
                            && fileSize == auditSize
                            && validAuditHash(lastHash)) {
                        return new AuditCheckpoint(lastHash, fileSize, degraded);
                    }
                }
            } catch (RuntimeException exception) {
                ignoreInvalidAuditCheckpoint(exception);
            }
        }
        AuditCheckpoint recovered = this.recoverAuditCheckpointFromTail(audit, auditSize);
        if (!recovered.degraded()) {
            this.writeAuditCheckpoint(recovered);
        }
        return recovered;
    }

    private AuditCheckpoint recoverAuditCheckpointFromTail(final Path audit, final long auditSize) throws IOException {
        if (auditSize == 0L || !Files.exists(audit)) {
            return new AuditCheckpoint("", 0L, false);
        }
        long maxTail = Math.min(auditSize, Math.addExact((long) this.limits.maxAuditLineBytes(), 2L));
        int tailBytes = Math.toIntExact(maxTail);
        byte[] bytes = new byte[tailBytes];
        long start = auditSize - tailBytes;
        try (FileChannel channel = FileChannel.open(audit, StandardOpenOption.READ)) {
            channel.position(start);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Continue until the bounded tail buffer is full or EOF is reached.
            }
            tailBytes = buffer.position();
        }
        int end = tailBytes;
        while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
            end--;
        }
        if (end == 0) {
            return new AuditCheckpoint("", auditSize, true);
        }
        int lineStart = end - 1;
        while (lineStart >= 0 && bytes[lineStart] != '\n') {
            lineStart--;
        }
        lineStart++;
        if (start > 0L && lineStart == 0) {
            return new AuditCheckpoint("", auditSize, true);
        }
        int lineLength = end - lineStart;
        if (lineLength <= 0 || lineLength > this.limits.maxAuditLineBytes()) {
            return new AuditCheckpoint("", auditSize, true);
        }
        try {
            String line = new String(bytes, lineStart, lineLength, StandardCharsets.UTF_8);
            Object parsed = SimpleJson.parse(line, this.limits);
            if (parsed instanceof Map<?, ?> map) {
                String currentHash = string(map, "currentHash");
                if (validAuditHash(currentHash)) {
                    return new AuditCheckpoint(currentHash, auditSize, false);
                }
            }
        } catch (RuntimeException exception) {
            ignoreInvalidAuditCheckpoint(exception);
        }
        return new AuditCheckpoint("", auditSize, true);
    }

    private void writeAuditCheckpoint(final AuditCheckpoint checkpoint) throws IOException {
        Path checkpointPath = this.auditCheckpointPath();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", AUDIT_CHECKPOINT_SCHEMA);
        values.put("fileSize", Long.toString(checkpoint.fileSize()));
        values.put("lastHash", checkpoint.lastHash());
        values.put("degraded", Boolean.toString(checkpoint.degraded()));
        BoundedBundleIO.writeSmallFileAtomically(checkpointPath, SimpleJson.writeStringMap(values) + "\n",
                this.limits.maxMetadataBytes());
    }

    private Path auditCheckpointPath() {
        return this.root.resolve("audit").resolve("repository-events.checkpoint.json");
    }

    private void validateAuditCursor(final Path audit, final long cursor, final long fileSize) throws IOException {
        if (cursor < 0L || cursor > fileSize) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit cursor is invalid");
        }
        if (cursor == 0L || cursor == fileSize) {
            return;
        }
        try (FileChannel channel = FileChannel.open(audit, StandardOpenOption.READ)) {
            channel.position(cursor - 1L);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1);
            if (channel.read(buffer) != 1) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit cursor is invalid");
            }
            buffer.flip();
            if (buffer.get() != '\n') {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit cursor is invalid");
            }
        }
    }

    private AuditLine readAuditLine(
            final FileChannel channel,
            final long cursor,
            final long fileSize) throws IOException {
        if (cursor >= fileSize) {
            return new AuditLine("", fileSize, true);
        }
        int maxLineBytes = this.limits.maxAuditLineBytes();
        byte[] bytes = new byte[maxLineBytes + 1];
        ByteBuffer buffer = ByteBuffer.allocate(this.limits.ioBufferBytes());
        long position = cursor;
        int length = 0;
        while (position < fileSize) {
            this.checkAuditScanInterrupted();
            buffer.clear();
            int requested = (int) Math.min((long) buffer.capacity(), fileSize - position);
            buffer.limit(requested);
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("short audit line read");
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                position++;
                if (value == '\n') {
                    int contentLength = length;
                    if (contentLength > 0 && bytes[contentLength - 1] == '\r') {
                        contentLength--;
                    }
                    if (contentLength > maxLineBytes) {
                        throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit line exceeds configured limit");
                    }
                    return new AuditLine(decodeUtf8(bytes, contentLength), position, false);
                }
                if (length == bytes.length) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit line exceeds configured limit");
                }
                bytes[length] = value;
                length++;
                if (length > maxLineBytes && value != '\r') {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit line exceeds configured limit");
                }
            }
        }
        if (length == 0) {
            return new AuditLine("", fileSize, true);
        }
        if (length > maxLineBytes) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit line exceeds configured limit");
        }
        return new AuditLine(decodeUtf8(bytes, length), fileSize, false);
    }

    private void checkAuditScanInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "repository audit scan was interrupted");
        }
    }

    private static String decodeUtf8(final byte[] bytes, final int length) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    private String safeAuditText(final String value) {
        String source = value == null ? "" : value;
        if (source.length() > this.limits.maxAuditLineBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit field exceeds configured limit");
        }
        StringBuilder builder = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                builder.append(' ');
            } else {
                builder.append(character);
            }
        }
        String normalized = builder.toString().trim();
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("private")) {
            return "<redacted>";
        }
        return normalized;
    }

    private void validateAuditAppendLine(final String line) {
        int contentBytes = line.endsWith("\n")
                ? line.substring(0, line.length() - 1).getBytes(StandardCharsets.UTF_8).length
                : line.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > this.limits.maxAuditLineBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository audit record exceeds configured limit");
        }
    }

    private String copyArtifact(final Path source, final Path target) throws IOException {
        if (Files.size(source) > this.limits.maxBundleBytes()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "application bundle exceeds configured size limit");
        }
        MessageDigest digest = this.sha256Digest();
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            BoundedBundleIO.copy(input, output, this.limits.maxBundleBytes(), this.limits.ioBufferBytes(), digest);
        }
        this.forceFile(target);
        return J7mBundleReader.hex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "SHA-256 unavailable", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    private void writeSummary() throws IOException {
        int artifacts = this.countArtifactsBounded();
        int promotions = this.countPromotionsBounded();
        Path summary = this.root.resolve("repository-summary.json");
        BoundedBundleIO.writeSmallFileAtomically(summary, SimpleJson.writeStringMap(Map.of(
                "artifactCount", Integer.toString(artifacts),
                "promotionCount", Integer.toString(promotions),
                "auditChainValid", Boolean.toString(this.auditCheckpoint == null || !this.auditCheckpoint.degraded()))) + "\n",
                this.limits.maxMetadataBytes());
    }

    private Map<String, String> readSummary() throws IOException {
        Path summary = this.root.resolve("repository-summary.json");
        if (!Files.isRegularFile(summary) || Files.size(summary) > this.limits.maxMetadataBytes()) {
            this.writeSummary();
        }
        Object parsed = SimpleJson.parse(Files.readString(summary, StandardCharsets.UTF_8), this.limits);
        if (!(parsed instanceof Map<?, ?> map)) {
            return Map.of("artifactCount", "0", "promotionCount", "0", "auditChainValid", "false");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("artifactCount", string(map, "artifactCount"));
        values.put("promotionCount", string(map, "promotionCount"));
        values.put("auditChainValid", string(map, "auditChainValid"));
        return values;
    }

    private int countArtifactsBounded() throws IOException {
        Path artifacts = this.root.resolve("artifacts");
        if (!Files.isDirectory(artifacts)) {
            return 0;
        }
        BoundedCounter counter = new BoundedCounter(this.limits.maxEntryCount(),
                "repository artifact summary exceeds configured limit");
        int[] artifactsFound = new int[1];
        Files.walkFileTree(artifacts, java.util.Set.of(), 8, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                counter.increment();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                counter.increment();
                if (file.toString().endsWith(".j7m")) {
                    artifactsFound[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return artifactsFound[0];
    }

    private int countPromotionsBounded() throws IOException {
        Path promotions = this.root.resolve("promotions");
        if (!Files.isDirectory(promotions)) {
            return 0;
        }
        int count = 0;
        BoundedCounter examined = new BoundedCounter(this.limits.maxEntryCount(),
                "repository promotion summary exceeds configured scan limit");
        try (java.util.stream.Stream<Path> stream = Files.list(promotions)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                examined.increment();
                if (path.toString().endsWith(".json")) {
                    count++;
                    if (count > this.limits.maxEntryCount()) {
                        throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED,
                                "repository promotion summary exceeds configured limit");
                    }
                }
            }
        }
        return count;
    }

    private void recoverIncompletePublications() throws IOException {
        this.deleteTree(this.root.resolve("staging"));
        Files.createDirectories(this.root.resolve("staging"));
        Path artifacts = this.root.resolve("artifacts");
        if (!Files.isDirectory(artifacts)) {
            return;
        }
        BoundedCounter examined = new BoundedCounter(this.limits.maxEntryCount(),
                "repository recovery scan exceeds configured limit");
        List<Path> directoriesWithBundles = new ArrayList<>();
        Files.walkFileTree(artifacts, java.util.Set.of(), 8, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                examined.increment();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                examined.increment();
                if (file.toString().endsWith(".j7m")) {
                    directoriesWithBundles.add(file.getParent());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                if (directory.equals(artifacts)) {
                    return FileVisitResult.CONTINUE;
                }
                if (directoriesWithBundles.contains(directory) && !Files.isRegularFile(directory.resolve(COMPLETE_MARKER))) {
                    quarantine(directory, "incomplete-final-recovery");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void quarantine(final Path directory, final String reason) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Path target = this.root.resolve("quarantine").resolve(reason + "-" + UUID.randomUUID());
        Files.createDirectories(target.getParent());
        this.moveDirectory(directory, target);
    }

    private void moveDirectory(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteTree(final Path path) {
        if (!Files.exists(path)) {
            return;
        }
        BoundedCounter counter = new BoundedCounter(this.limits.maxEntryCount(), "cleanup entry limit exceeded");
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    counter.increment();
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    counter.increment();
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            ignoreBestEffortIoFailure(exception);
        }
    }

    private void forceFile(final Path path) throws IOException {
        BoundedBundleIO.forceFile(path);
    }

    private void forceDirectory(final Path directory) {
        BoundedBundleIO.forceDirectory(directory);
    }

    private static void ignoreBestEffortIoFailure(final IOException exception) {
        Objects.requireNonNull(exception, "exception");
    }

    private static int parseInt(final String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static long parseLong(final String value, final long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean validAuditHash(final String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void ignoreInvalidAuditCheckpoint(final RuntimeException exception) {
        Objects.requireNonNull(exception, "exception");
    }

    private static String safeEnvironment(final String environment) {
        String value = environment == null || environment.isBlank() ? "dev" : environment.trim();
        if (value.length() > 64) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository environment name is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_';
            if (!allowed) {
                throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "repository environment name is invalid");
            }
        }
        return value;
    }

    private static boolean containsMessage(final ApplicationVerificationResult verification, final String token) {
        for (String message : verification.messages()) {
            if (message.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String auditCanonical(
            final String timestamp,
            final String action,
            final String coordinate,
            final String result,
            final String previousHash) {
        return timestamp + '\n' + action + '\n' + coordinate + '\n' + result + '\n' + previousHash;
    }

    private static String string(final Map<?, ?> map, final String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static String sha256(final byte[] bytes) {
        try {
            return J7mBundleReader.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "SHA-256 unavailable", java.util.Map.of(), null, null, false, true), exception);
        }
    }

    private record AuditCheckpoint(String lastHash, long fileSize, boolean degraded) {
        private AuditCheckpoint {
            Objects.requireNonNull(lastHash, "lastHash");
            if (!validAuditHash(lastHash)) {
                throw new IllegalArgumentException("lastHash must be empty or lowercase SHA-256 hex");
            }
            if (fileSize < 0L) {
                throw new IllegalArgumentException("fileSize must not be negative");
            }
        }
    }

    private record AuditLine(String text, long nextCursor, boolean end) {
        private AuditLine {
            Objects.requireNonNull(text, "text");
            if (nextCursor < 0L) {
                throw new IllegalArgumentException("nextCursor must not be negative");
            }
        }
    }

    private static final class BoundedCounter {
        private final int limit;
        private final String message;
        private int value;

        private BoundedCounter(final int limit, final String message) {
            this.limit = limit;
            this.message = message;
        }

        private void increment() {
            this.value++;
            if (this.value > this.limit) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, this.message);
            }
        }

    }
}
