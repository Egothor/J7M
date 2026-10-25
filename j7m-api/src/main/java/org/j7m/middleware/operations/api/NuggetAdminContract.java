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

package org.j7m.middleware.operations.api;

import java.time.Duration;

/**
 * Stable public constants for the Phase 7.8 nugget administration contract.
 *
 * <p>The constants define the transport-neutral public contract used by CLI
 * clients, deterministic JSON artifacts, tests, demos, and future adapters.
 * They do not start listeners, select an HTTP shape, or grant admission or
 * authorization. Implementations must reject unsupported request protocol
 * versions before performing admission, authorization, provider calls,
 * lifecycle mutation, control callbacks, or audit mutation.</p>
 *
 * <p>This type is immutable and thread-safe.</p>
 */
public final class NuggetAdminContract {
    /**
     * Stable request/response protocol version for the public admin contract.
     */
    public static final int PROTOCOL_VERSION = 1;
    /**
     * Stable JSON format name for normative fixtures and schema documents.
     */
    public static final String JSON_FORMAT_NAME = "j7m-nugget-admin-json";
    /**
     * Stable JSON schema/format version for Phase 7.8 artifacts.
     */
    public static final int JSON_FORMAT_VERSION = 1;
    /**
     * Maximum safe request metadata entries retained by public DTOs.
     */
    public static final int MAX_METADATA_ENTRIES = 16;
    /**
     * Maximum metadata key length in UTF-8 bytes.
     */
    public static final int MAX_METADATA_KEY_UTF8_BYTES = 64;
    /**
     * Maximum metadata value length in UTF-8 bytes.
     */
    public static final int MAX_METADATA_VALUE_UTF8_BYTES = 256;
    /**
     * Maximum aggregate metadata key and value content retained in UTF-8 bytes.
     */
    public static final int MAX_METADATA_AGGREGATE_UTF8_BYTES = 2048;
    /**
     * Maximum generic token length in UTF-8 bytes.
     */
    public static final int MAX_TOKEN_UTF8_BYTES = 128;
    /**
     * Maximum generic safe text length in UTF-8 bytes.
     */
    public static final int MAX_TEXT_UTF8_BYTES = 512;
    /**
     * Maximum warning entries retained by public result headers.
     */
    public static final int MAX_WARNING_ENTRIES = 16;
    /**
     * Maximum safe warning length in UTF-8 bytes.
     */
    public static final int MAX_WARNING_UTF8_BYTES = 160;
    /**
     * Maximum collection page size accepted by public query DTOs.
     */
    public static final int MAX_PAGE_LIMIT = 500;
    /**
     * Default collection page size when callers omit a limit.
     */
    public static final int DEFAULT_PAGE_LIMIT = 50;
    /**
     * Maximum filter clauses accepted by public query DTOs.
     */
    public static final int MAX_FILTERS = 16;
    /**
     * Maximum sort clauses accepted by public query DTOs before tie-breaker insertion.
     */
    public static final int MAX_SORTS = 4;
    /**
     * Maximum offset accepted by public query JSON artifacts before semantic offset-plus-limit validation.
     */
    public static final long MAX_OFFSET = Long.MAX_VALUE;
    /**
     * Maximum count value for public {@code long} count fields.
     */
    public static final long MAX_COUNT = Long.MAX_VALUE;
    /**
     * Maximum scalar millisecond duration representable by public JSON artifacts without overflow.
     */
    public static final long MAX_DURATION_MILLIS = Long.MAX_VALUE;
    /**
     * Maximum duration retained by public DTOs that render scalar milliseconds in JSON.
     */
    public static final Duration MAX_DURATION = Duration.ofMillis(MAX_DURATION_MILLIS);
    /**
     * Maximum client-supplied budget metadata retained in a request context.
     */
    public static final Duration MAX_CLIENT_BUDGET = Duration.ofMinutes(5L);
    /**
     * Default client budget metadata when no budget is supplied.
     */
    public static final Duration DEFAULT_CLIENT_BUDGET = Duration.ofSeconds(30L);
    /**
     * Stable generic payload type id for typed public DTOs without a narrower schema id.
     */
    public static final String TYPED_PUBLIC_DTO_PAYLOAD_TYPE = "typed-public-dto";

    private NuggetAdminContract() {
    }

    /**
     * Verifies that a caller requested the current public admin protocol.
     *
     * @param protocolVersion requested protocol version
     * @throws IllegalArgumentException if the version is unsupported
     */
    public static void requireSupportedProtocol(final int protocolVersion) {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported nugget admin request protocol version");
        }
    }
}
