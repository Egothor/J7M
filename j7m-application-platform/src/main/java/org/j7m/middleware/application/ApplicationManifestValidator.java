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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.j7m.middleware.application.api.ApplicationVerificationResult;
import org.j7m.middleware.application.internal.BoundedBundleIO;

/**
 * Data-only application manifest validation, including background-only applications.
 *
 * <p>Validation is deterministic and side-effect free. It does not load
 * classes or touch bundle entries. Services may be empty; a valid bootstrap is still mandatory.
 * This does not grant a deployment profile, backend capability or authorization.</p>
 */
public final class ApplicationManifestValidator {
    /**
     * Supported manifest schema.
     */
    public static final String SCHEMA_VERSION = "j7m.application/v1";
    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "domain", "classification", "example", "phase", "sbom", "provenance", "tier", "region", "zone", "owner",
            "mode", "template", "version");
    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("grpc", "rmi", "local");
    private final ApplicationPlatformLimits limits;

    /**
     * Creates a stateless manifest validator.
     */
    public ApplicationManifestValidator() {
        this(ApplicationPlatformLimits.defaults());
    }

    /**
     * Creates a manifest validator with explicit bounds.
     *
     * @param limits validation limits
     */
    public ApplicationManifestValidator(final ApplicationPlatformLimits limits) {
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
    }

    /**
     * Validates a manifest without touching bundle contents or loading code.
     *
     * @param manifest manifest to validate
     * @param strictMode whether strict verification mode is active
     * @return structured validation result
     */
    public ApplicationVerificationResult validate(final ApplicationManifest manifest, final boolean strictMode) {
        List<String> findings = new ArrayList<>();
        if (manifest == null) {
            findings.add("manifest is missing");
            return ApplicationVerificationResult.rejected(null, strictMode, findings);
        }
        if (manifest.schemaVersion().isBlank()) {
            findings.add("schemaVersion is required");
        } else if (!SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            findings.add("unsupported schemaVersion: " + manifest.schemaVersion());
        }
        if (manifest.identity() == null || manifest.identity().coordinate() == null) {
            findings.add("application coordinate is required");
        } else {
            validateCoordinate(manifest, findings);
        }
        if (manifest.bootstrapClass().isBlank()) {
            findings.add("runtime.bootstrapClass is required");
        } else if (!isClassName(manifest.bootstrapClass())) {
            findings.add("runtime.bootstrapClass is invalid");
        }
        if (!manifest.minJ7mVersion().isBlank() && !isVersion(manifest.minJ7mVersion())) {
            findings.add("runtime.minJ7mVersion is invalid");
        }
        if (!manifest.javaVersion().isBlank() && !isPositiveInteger(manifest.javaVersion())) {
            findings.add("runtime.javaVersion is invalid");
        }
        if (!"CLASSPATH".equals(manifest.moduleMode()) && !"JPMS".equals(manifest.moduleMode())) {
            findings.add("unsupported runtime.moduleMode: " + manifest.moduleMode());
        }
        if ("JPMS".equals(manifest.moduleMode())) {
            if (manifest.bootstrapModule().isBlank()) {
                findings.add("runtime.bootstrapModule is required for JPMS mode");
            } else if (!isModuleName(manifest.bootstrapModule())) {
                findings.add("runtime.bootstrapModule is invalid");
            }
            if (manifest.modules().isEmpty()) {
                findings.add("module declarations are required for JPMS mode");
            }
            Set<String> moduleNames = new HashSet<>();
            Set<String> modulePaths = new HashSet<>();
            for (ApplicationManifest.ModuleDeclaration module : manifest.modules()) {
                if (module.name().isBlank() || module.path().isBlank()) {
                    findings.add("module declarations require name and path");
                }
                if (!module.name().isBlank() && !isModuleName(module.name())) {
                    findings.add("module declaration name is invalid");
                }
                if (!moduleNames.add(module.name())) {
                    findings.add("duplicate module declaration: " + module.name());
                }
                if (!modulePaths.add(module.path())) {
                    findings.add("duplicate module path: " + module.path());
                }
                if (!isBundlePath(module.path()) || !module.path().startsWith("modules/") || !module.path().endsWith(".jar")) {
                    findings.add("unsafe module path");
                }
            }
        }
        Set<String> serviceKeys = new HashSet<>();
        for (ApplicationManifest.ServiceDeclaration service : manifest.services()) {
            if (service.serviceName().isBlank() || service.interfaceName().isBlank() || service.implementationClass().isBlank()) {
                findings.add("service declarations require serviceName, interfaceName, and implementationClass");
            }
            if (!service.serviceName().isBlank() && !isServiceName(service.serviceName())) {
                findings.add("serviceName is invalid");
            }
            if (!service.interfaceName().isBlank() && !isClassName(service.interfaceName())) {
                findings.add("interfaceName is invalid");
            }
            if (!service.implementationClass().isBlank() && !isClassName(service.implementationClass())) {
                findings.add("implementationClass is invalid");
            }
            if (!ALLOWED_TRANSPORTS.contains(service.transport().toLowerCase(Locale.ROOT))) {
                findings.add("transport is invalid");
            }
            validateMetadata(service.metadata(), findings);
            if (!serviceKeys.add(service.serviceName() + "|" + service.interfaceName())) {
                findings.add("duplicate service declaration: " + service.serviceName());
            }
        }
        if (!manifest.resources().cpu().isBlank() && parseDouble(manifest.resources().cpu()) < 0.0D) {
            findings.add("resources.cpu must be non-negative");
        }
        if (!manifest.resources().memory().isBlank() && parseBytes(manifest.resources().memory()) < 0L) {
            findings.add("resources.memory is invalid");
        }
        if (manifest.resources().maxConcurrentInvocations() < 0) {
            findings.add("resources.maxConcurrentInvocations must be non-negative");
        } else if (manifest.resources().maxConcurrentInvocations() > 100_000) {
            findings.add("resources.maxConcurrentInvocations exceeds configured limit");
        }
        if (!manifest.resources().maxInboundPayload().isBlank() && parseBytes(manifest.resources().maxInboundPayload()) < 0L) {
            findings.add("resources.maxInboundPayload is invalid");
        }
        if (findings.isEmpty()) {
            findings.add("manifest accepted");
            return ApplicationVerificationResult.accepted(manifest.identity().coordinate(), strictMode, findings);
        }
        return ApplicationVerificationResult.rejected(manifest.identity() == null ? null : manifest.identity().coordinate(), strictMode, findings);
    }

    private static double parseDouble(final String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return -1.0D;
        }
    }

    private static long parseBytes(final String value) {
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        long multiplier = 1L;
        String number = normalized;
        if (normalized.endsWith("MIB")) {
            multiplier = 1024L * 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("KIB")) {
            multiplier = 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        } else if (normalized.endsWith("GIB")) {
            multiplier = 1024L * 1024L * 1024L;
            number = normalized.substring(0, normalized.length() - 3);
        }
        try {
            long parsed = Long.parseLong(number.trim());
            if (parsed < 0L || parsed > Long.MAX_VALUE / multiplier) {
                return -1L;
            }
            return parsed * multiplier;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static void validateCoordinate(final ApplicationManifest manifest, final List<String> findings) {
        String group = manifest.identity().coordinate().id().group();
        String name = manifest.identity().coordinate().id().name();
        String version = manifest.identity().coordinate().version().value();
        if (!isDottedIdentifier(group)) {
            findings.add("application.group is invalid");
        }
        if (!isSafeToken(name, true)) {
            findings.add("application.name is invalid");
        }
        if (!isVersion(version)) {
            findings.add("application.version is invalid");
        }
        if (!isSafeToken(manifest.identity().trustDomain(), true)) {
            findings.add("security.trustDomain is invalid");
        }
    }

    private static void validateMetadata(final Map<String, String> metadata, final List<String> findings) {
        if (metadata.size() > 16) {
            findings.add("service metadata exceeds configured entry limit");
            return;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!ALLOWED_METADATA_KEYS.contains(key)) {
                findings.add("service metadata key is not allowed");
                continue;
            }
            if (value.length() > 96 || containsControl(value) || looksSensitive(key) || looksSensitive(value)
                    || looksLikePathOrUrl(value) || looksHighEntropy(value)) {
                findings.add("service metadata value is unsafe");
            }
        }
    }

    private static boolean isBundlePath(final String value) {
        try {
            BoundedBundleIO.normalizeEntryName(value, false, ApplicationPlatformLimits.defaults());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isServiceName(final String value) {
        return value.length() <= 160 && value.startsWith("//") && !containsControl(value)
                && value.indexOf('\\') < 0 && value.indexOf('\0') < 0 && !value.contains("..");
    }

    private static boolean isClassName(final String value) {
        return isDottedIdentifier(value) && Character.isJavaIdentifierStart(value.charAt(value.lastIndexOf('.') + 1));
    }

    private static boolean isModuleName(final String value) {
        return isDottedIdentifier(value);
    }

    private static boolean isDottedIdentifier(final String value) {
        if (value == null || value.isBlank() || value.length() > 240 || value.startsWith(".") || value.endsWith(".")) {
            return false;
        }
        int segmentStart = 0;
        for (int index = 0; index <= value.length(); index++) {
            if (index == value.length() || value.charAt(index) == '.') {
                if (index == segmentStart || !isJavaIdentifierSegment(value, segmentStart, index)) {
                    return false;
                }
                segmentStart = index + 1;
            }
        }
        return true;
    }

    private static boolean isJavaIdentifierSegment(final String value, final int start, final int end) {
        if (!Character.isJavaIdentifierStart(value.charAt(start))) {
            return false;
        }
        for (int index = start + 1; index < end; index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVersion(final String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z') || character == '.' || character == '-' || character == '+')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPositiveInteger(final String value) {
        if (value == null || value.isBlank() || value.length() > 9) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return Integer.parseInt(value) > 0;
    }

    private static boolean isSafeToken(final String value, final boolean allowDash) {
        if (value == null || value.isBlank() || value.length() > 96) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= '0' && character <= '9')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || character == '_' || (allowDash && character == '-');
            if (!allowed) {
                return false;
            }
        }
        return true;
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

    private static boolean looksSensitive(final String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("secret") || normalized.contains("password") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("private") || normalized.contains("apikey")
                || normalized.contains("api-key");
    }

    private static boolean looksLikePathOrUrl(final String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("://") || lower.startsWith("/") || lower.contains("\\") || lower.startsWith("file:")
                || lower.startsWith("http:") || lower.startsWith("https:") || lower.contains("../") || lower.contains("..\\");
    }

    private static boolean looksHighEntropy(final String value) {
        if (value.length() < 32) {
            return false;
        }
        int classes = 0;
        classes += containsRange(value, 'a', 'z') ? 1 : 0;
        classes += containsRange(value, 'A', 'Z') ? 1 : 0;
        classes += containsRange(value, '0', '9') ? 1 : 0;
        classes += containsSymbols(value) ? 1 : 0;
        return classes >= 3;
    }

    private static boolean containsRange(final String value, final char first, final char last) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= first && character <= last) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSymbols(final String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                return true;
            }
        }
        return false;
    }
}
