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

package org.j7m.middleware.application.api;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Exact immutable qualifier identity produced by a generated component index.
 * Member values use value(Object), including annotation defaults. Qualifier values are selection
 * data and must not be printed as diagnostic labels or used to carry credentials.
 * @param annotationType fully qualified binary annotation type name
 * @param members canonical member name to value mapping, at most64 entries
 */
public record ManagedQualifier(String annotationType, Map<String, String> members) {
    /**
     * Validates and copies a bounded canonical qualifier.
     * @param annotationType nonblank type name, at most512 characters
     * @param members at most64 nonnull member names/canonical values
     * @throws IllegalArgumentException for invalid bounds
     */
    public ManagedQualifier {
        if (annotationType == null || annotationType.isBlank() || annotationType.length() > 512
                || members == null || members.size() > 64) throw new IllegalArgumentException("Invalid managed qualifier");
        members = Map.copyOf(members);
        for (var entry : members.entrySet()) if (entry.getKey().isBlank() || entry.getKey().length() > 128
                || entry.getValue().length() > 8192) throw new IllegalArgumentException("Invalid qualifier member");
    }
    /**
     * Encodes one supported annotation member without compiler/runtime formatting differences.
     * Supported values are String, boxed primitives, Character, Enum, Class and arrays thereof.
     * Nested annotation instances are unsupported. Arrays have at most64 elements and depth8;
     * the complete canonical value has at most8192 characters. Length framing avoids collisions.
     * @param member supported nonnull annotation member value
     * @return stable canonical representation, including the member's type
     * @throws IllegalArgumentException for unsupported values or excessive bounds
     */
    public static String value(Object member) {
        String encoded = encode(member, 0);
        if (encoded.length() > 8192) throw new IllegalArgumentException("Qualifier value too large");
        return encoded;
    }
    /**
     * Hashes an exact normalized qualifier set using sorted, length-framed UTF16 code units.
     * Host exports and binding requests share this representation; no qualifier values are exposed
     * in the resulting routing fingerprint. A digest is compatibility data, not authentication.
     * @param qualifiers at most16 unique qualifier types
     * @return lowercase SHA256 fingerprint, including the empty set
     * @throws IllegalArgumentException for invalid qualifier sets
     */
    public static String fingerprint(List<ManagedQualifier> qualifiers) {
        var checked = new ManagedDependency(Object.class, "", qualifiers, false);
        try {
            var bytes = new java.io.ByteArrayOutputStream(); var output = new java.io.DataOutputStream(bytes);
            var sorted = new ArrayList<>(checked.qualifiers()); sorted.sort(Comparator.comparing(ManagedQualifier::annotationType));
            output.writeInt(sorted.size());
            for (var qualifier : sorted) {
                framed(output, qualifier.annotationType()); output.writeInt(qualifier.members().size());
                for (var entry : new TreeMap<>(qualifier.members()).entrySet()) { framed(output, entry.getKey()); framed(output, entry.getValue()); }
            }
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("Qualifier fingerprint unavailable", impossible); }
    }
    private static void framed(java.io.DataOutputStream output, String text) throws java.io.IOException {
        output.writeInt(text.length()); for (int i = 0; i < text.length(); i++) output.writeChar(text.charAt(i));
    }
    private static String encode(Object member, int depth) {
        if (member == null || depth > 8) throw new IllegalArgumentException("Invalid qualifier value");
        if (member instanceof String text) return frame("s", text);
        if (member instanceof Boolean value) return "z:" + value;
        if (member instanceof Byte value) return "b:" + value;
        if (member instanceof Short value) return "h:" + value;
        if (member instanceof Integer value) return "i:" + value;
        if (member instanceof Long value) return "l:" + value;
        if (member instanceof Character value) return "c:" + (int) value;
        if (member instanceof Float value) return "f:" + Float.toHexString(value);
        if (member instanceof Double value) return "d:" + Double.toHexString(value);
        if (member instanceof Enum<?> value) return frame("e", value.getDeclaringClass().getName()) + frame("v", value.name());
        if (member instanceof Class<?> value) return frame("k", value.getName());
        if (member.getClass().isArray()) {
            int count = Array.getLength(member); if (count > 64) throw new IllegalArgumentException("Qualifier array too large");
            var result = new StringBuilder(frame("a", member.getClass().getName())).append(count).append(':');
            for (int i = 0; i < count; i++) {
                result.append(frame("v", encode(Array.get(member, i), depth + 1)));
                if (result.length() > 8192) throw new IllegalArgumentException("Qualifier array too large");
            }
            return result.toString();
        }
        throw new IllegalArgumentException("Unsupported qualifier value type");
    }
    private static String frame(String type, String value) {
        if (value.length() > 8192) throw new IllegalArgumentException("Qualifier value too large");
        return type + ':' + value.length() + ':' + value;
    }
}
