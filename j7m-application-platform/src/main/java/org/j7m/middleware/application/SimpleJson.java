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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleJson {
    private final String input;
    private final ApplicationPlatformLimits limits;
    private int index;

    private SimpleJson(final String input, final ApplicationPlatformLimits limits) {
        this.input = input == null ? "" : input;
        this.limits = limits == null ? ApplicationPlatformLimits.defaults() : limits;
        if (this.input.length() > this.limits.maxMetadataBytes()) {
            throw new IllegalArgumentException("JSON document exceeds configured limit");
        }
    }

    static Object parse(final String input) {
        return parse(input, ApplicationPlatformLimits.defaults());
    }

    static Object parse(final String input, final ApplicationPlatformLimits limits) {
        SimpleJson parser = new SimpleJson(input, limits);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.index != parser.input.length()) {
            throw new IllegalArgumentException("unexpected trailing JSON content");
        }
        return value;
    }

    static String writeStringMap(final Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append('"').append(':');
            builder.append('"').append(escape(entry.getValue())).append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    static String escape(final String value) {
        String source = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(source.length() + 8);
        for (int offset = 0; offset < source.length(); offset++) {
            char character = source.charAt(offset);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        throw new IllegalArgumentException("JSON string contains unsupported control character");
                    }
                    if (Character.isHighSurrogate(character)) {
                        if (offset + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(offset + 1))) {
                            throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
                        }
                        builder.append(character).append(source.charAt(offset + 1));
                        offset++;
                        continue;
                    }
                    if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
                    }
                    builder.append(character);
                }
            }
        }
        return builder.toString();
    }

    private Object readValue(final int depth) {
        this.requireDepth(depth);
        this.skipWhitespace();
        if (this.index >= this.input.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char character = this.input.charAt(this.index);
        return switch (character) {
            case '{' -> this.readObject(depth + 1);
            case '[' -> this.readArray(depth + 1);
            case '"' -> this.readString();
            case 't' -> this.readLiteral("true", Boolean.TRUE);
            case 'f' -> this.readLiteral("false", Boolean.FALSE);
            case 'n' -> this.readLiteral("null", null);
            default -> this.readNumber();
        };
    }

    private Map<String, Object> readObject(final int depth) {
        this.requireDepth(depth);
        this.index++;
        Map<String, Object> values = new LinkedHashMap<>();
        this.skipWhitespace();
        if (this.peek('}')) {
            this.index++;
            return values;
        }
        while (true) {
            this.skipWhitespace();
            if (!this.peek('"')) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            String key = this.readString();
            if (values.containsKey(key)) {
                throw new IllegalArgumentException("duplicate JSON object key");
            }
            this.skipWhitespace();
            this.expect(':');
            Object value = this.readValue(depth);
            values.put(key, value);
            if (values.size() > this.limits.maxEntryCount()) {
                throw new IllegalArgumentException("JSON object exceeds configured entry limit");
            }
            this.skipWhitespace();
            if (this.peek('}')) {
                this.index++;
                return values;
            }
            this.expect(',');
        }
    }

    private List<Object> readArray(final int depth) {
        this.requireDepth(depth);
        this.index++;
        List<Object> values = new ArrayList<>();
        this.skipWhitespace();
        if (this.peek(']')) {
            this.index++;
            return values;
        }
        while (true) {
            values.add(this.readValue(depth));
            if (values.size() > this.limits.maxEntryCount()) {
                throw new IllegalArgumentException("JSON array exceeds configured element limit");
            }
            this.skipWhitespace();
            if (this.peek(']')) {
                this.index++;
                return values;
            }
            this.expect(',');
        }
    }

    private String readString() {
        this.expect('"');
        StringBuilder builder = new StringBuilder();
        while (this.index < this.input.length()) {
            char character = this.input.charAt(this.index++);
            if (character == '"') {
                return builder.toString();
            }
            if (builder.length() >= this.limits.maxMetadataBytes()) {
                throw new IllegalArgumentException("JSON string exceeds configured limit");
            }
            if (character == '\\') {
                this.readEscaped(builder);
            } else {
                if (character < 0x20 || character == 0x7f) {
                    throw new IllegalArgumentException("JSON string contains unsupported control character");
                }
                if (Character.isHighSurrogate(character)) {
                    if (this.index >= this.input.length()) {
                        throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
                    }
                    char low = this.input.charAt(this.index++);
                    if (!Character.isLowSurrogate(low)) {
                        throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
                    }
                    builder.append(character).append(low);
                    continue;
                }
                if (Character.isLowSurrogate(character)) {
                    throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
                }
                builder.append(character);
            }
        }
        throw new IllegalArgumentException("unterminated JSON string");
    }

    private void readEscaped(final StringBuilder builder) {
        if (this.index >= this.input.length()) {
            throw new IllegalArgumentException("unterminated JSON escape");
        }
        char escaped = this.input.charAt(this.index++);
        switch (escaped) {
            case '"', '\\', '/' -> builder.append(escaped);
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'u' -> this.readUnicodeEscape(builder);
            default -> throw new IllegalArgumentException("unsupported JSON escape");
        }
    }

    private void readUnicodeEscape(final StringBuilder builder) {
        char character = this.readUnicodeEscapeChar();
        if (Character.isHighSurrogate(character)) {
            if (this.index + 6 > this.input.length()
                    || this.input.charAt(this.index) != '\\'
                    || this.input.charAt(this.index + 1) != 'u') {
                throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
            }
            this.index += 2;
            char low = this.readUnicodeEscapeChar();
            if (!Character.isLowSurrogate(low)) {
                throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
            }
            builder.append(character).append(low);
            return;
        }
        if (Character.isLowSurrogate(character)) {
            throw new IllegalArgumentException("JSON string contains malformed surrogate pair");
        }
        builder.append(character);
    }

    private char readUnicodeEscapeChar() {
        if (this.index + 4 > this.input.length()) {
            throw new IllegalArgumentException("truncated JSON Unicode escape");
        }
        int value = 0;
        for (int count = 0; count < 4; count++) {
            char character = this.input.charAt(this.index++);
            int digit = Character.digit(character, 16);
            if (digit < 0) {
                throw new IllegalArgumentException("malformed JSON Unicode escape");
            }
            value = (value << 4) | digit;
        }
        if (value < 0x20 || value == 0x7f) {
            throw new IllegalArgumentException("JSON Unicode escape decodes to a control character");
        }
        return (char) value;
    }

    private String readNumber() {
        int start = this.index;
        if (this.peek('-')) {
            this.index++;
        }
        this.readDigits();
        if (this.peek('.')) {
            this.index++;
            this.readDigits();
        }
        if (this.peek('e') || this.peek('E')) {
            this.index++;
            if (this.peek('+') || this.peek('-')) {
                this.index++;
            }
            this.readDigits();
        }
        int length = this.index - start;
        if (length <= 0 || length > this.limits.maxEntryNameBytes()) {
            throw new IllegalArgumentException("JSON number token exceeds configured limit");
        }
        return this.input.substring(start, this.index);
    }

    private void readDigits() {
        int start = this.index;
        while (this.index < this.input.length()) {
            char character = this.input.charAt(this.index);
            if (character < '0' || character > '9') {
                break;
            }
            this.index++;
        }
        if (this.index == start) {
            throw new IllegalArgumentException("invalid JSON number");
        }
    }

    private Object readLiteral(final String literal, final Object value) {
        if (!this.input.startsWith(literal, this.index)) {
            throw new IllegalArgumentException("invalid JSON literal");
        }
        this.index += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (this.index < this.input.length()) {
            char character = this.input.charAt(this.index);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            this.index++;
        }
    }

    private boolean peek(final char expected) {
        return this.index < this.input.length() && this.input.charAt(this.index) == expected;
    }

    private void expect(final char expected) {
        if (!this.peek(expected)) {
            throw new IllegalArgumentException("expected JSON delimiter");
        }
        this.index++;
    }

    private void requireDepth(final int depth) {
        if (depth > this.limits.maxEntryPathDepth()) {
            throw new IllegalArgumentException("JSON nesting exceeds configured limit");
        }
    }
}
