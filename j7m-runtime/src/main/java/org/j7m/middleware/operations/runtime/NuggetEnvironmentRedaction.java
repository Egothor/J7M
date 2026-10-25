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

import java.util.Set;

final class NuggetEnvironmentRedaction {
    private static final Set<String> SENSITIVE_ENVIRONMENT_NAME_WORDS = Set.of(
            "secret", "password", "passwd", "pwd", "pin", "token", "key", "credential", "auth", "bearer", "session",
            "cookie", "certificate", "cert", "private", "database", "cloud", "repository");

    private NuggetEnvironmentRedaction() {
    }

    static String argumentName(final String argument) {
        if (argument == null || argument.isBlank()) {
            return "arg";
        }
        String value = argument.strip();
        if (value.startsWith("-D")) {
            return "system-property";
        }
        if (value.startsWith("-javaagent") || value.startsWith("-agentpath") || value.startsWith("-agentlib")) {
            return "agent-option";
        }
        if (value.startsWith("-Xrunjdwp") || value.startsWith("-Xdebug") || value.contains("jdwp")) {
            return "debug-option";
        }
        if (value.equals("-cp") || value.equals("-classpath") || value.startsWith("--class-path")
                || value.startsWith("--module-path") || value.startsWith("-p")) {
            return "path-option";
        }
        int equals = value.indexOf('=');
        String name = equals > 0 ? value.substring(0, equals) : value;
        if (name.startsWith("-X") || name.startsWith("-XX:")) {
            return "jvm-tuning-option";
        }
        if (sensitive(name) || name.contains("/") || name.contains("\\") || name.contains(":")) {
            return "redacted-option";
        }
        if (name.startsWith("--")) {
            return safeIdentifier(name.substring(2), "jvm-option");
        }
        return "jvm-option";
    }

    static String safeEnvironmentName(final String name) {
        if (name == null || name.isBlank() || name.length() > 128 || !validEnvironmentVariableName(name)
                || containsAny(name, SENSITIVE_ENVIRONMENT_NAME_WORDS)) {
            return "";
        }
        return name;
    }

    static boolean validEnvironmentVariableName(final String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            return false;
        }
        char first = name.charAt(0);
        if (!(first == '_' || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!(current == '_' || (current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String safeIdentifier(final String value, final String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 64));
        for (int index = 0; index < value.length() && builder.length() < 64; index++) {
            char current = value.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.'
                    || current == '_'
                    || current == '-') {
                builder.append(current);
            } else if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '-') {
                builder.append('-');
            }
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '-') {
            builder.setLength(length - 1);
        }
        return builder.isEmpty() ? fallback : builder.toString();
    }

    private static boolean sensitive(final String value) {
        String normalized = normalizedSensitivityText(value);
        return normalized.contains("pin")
                || normalized.contains("admission")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bearer")
                || normalized.contains("url")
                || normalized.contains("uri")
                || normalized.contains("privatekey")
                || normalized.contains("userinfo")
                || value.contains("://")
                || value.contains("..")
                || value.contains("\\\\")
                || windowsDrive(value);
    }

    private static boolean containsAny(final String value, final Set<String> words) {
        String normalized = normalizedSensitivityText(value);
        for (String word : words) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedSensitivityText(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                builder.append((char) (current + ('a' - 'A')));
            } else if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    private static boolean windowsDrive(final String value) {
        return value.length() >= 2
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'))
                && value.charAt(1) == ':';
    }
}
