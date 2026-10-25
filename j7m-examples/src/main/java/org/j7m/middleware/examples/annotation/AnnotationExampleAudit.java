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

package org.j7m.middleware.examples.annotation;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory audit collector used only by annotation-first examples.
 *
 * <p>The collector stores safe strings after replacing sentinel secret values
 * and obvious amount values. It is not a production audit sink; it provides a
 * deterministic demonstration target for {@code @Sensitive}, security
 * metadata, lifecycle events, saga compensation, and queue consumer status.</p>
 */
public final class AnnotationExampleAudit {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final List<String> entries;

    /**
     * Creates an empty collector.
     */
    public AnnotationExampleAudit() {
        this.entries = new ArrayList<>();
    }

    /**
     * Records one safe audit entry.
     *
     * @param entry entry to record
     */
    public void record(final String entry) {
        this.entries.add(redact(entry));
    }

    /**
     * Returns immutable audit entries.
     *
     * @return safe audit entries
     */
    public List<String> entries() {
        return List.copyOf(this.entries);
    }

    /**
     * Returns the audit entries as a safe summary string.
     *
     * @return safe summary
     */
    public String summary() {
        return this.entries().toString();
    }

    /**
     * Redacts sentinel values used by tests and demos.
     *
     * @param value input value
     * @return redacted output
     */
    public static String redact(final String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("J7M_SENTINEL_SECRET_TOKEN", "<redacted>")
                .replace("J7M_SENTINEL_PASSWORD", "<redacted>")
                .replace("J7M_SENTINEL_API_KEY", "<redacted>")
                .replace("amount=4200", "amount=<redacted>")
                .replace("amount=9900", "amount=<redacted>");
    }
}
