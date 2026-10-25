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

import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Common bounded query model for public nugget administration collections.
 *
 * <p>The query uses enum fields rather than caller-provided object-property
 * names. Filters are exact-match public predicates. Sorts are public ordering
 * fields and always end with {@link NuggetAdminField#STABLE_ID} as a
 * deterministic final tie-breaker. Offset and limit are validated with
 * overflow-safe arithmetic. Instances are immutable and thread-safe.</p>
 *
 * @param offset zero-based result offset
 * @param limit maximum number of returned records
 * @param filters allowlisted filter clauses in schema-visible order
 * @param sorts allowlisted sort clauses in schema-visible order with a final
 *        stable identity tie-breaker
 */
public record NuggetAdminQuery(
        long offset,
        int limit,
        List<Filter> filters,
        List<Sort> sorts) {
    /**
     * Creates an immutable query.
     */
    public NuggetAdminQuery {
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 0 || limit > NuggetAdminContract.MAX_PAGE_LIMIT) {
            throw new IllegalArgumentException("limit outside nugget admin query bounds");
        }
        limit = limit == 0 ? NuggetAdminContract.DEFAULT_PAGE_LIMIT : limit;
        try {
            Math.addExact(offset, (long) limit);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("offset plus limit overflows", exception);
        }
        filters = copyFilters(filters);
        sorts = copySorts(sorts);
    }

    /**
     * Returns the default query.
     *
     * @return default query
     */
    public static NuggetAdminQuery all() {
        return new NuggetAdminQuery(0L, NuggetAdminContract.DEFAULT_PAGE_LIMIT, List.of(), List.of());
    }

    private static List<Filter> copyFilters(final List<Filter> source) {
        if (source == null) {
            return List.of();
        }
        final ArrayList<Filter> copy = new ArrayList<>(NuggetAdminContract.MAX_FILTERS);
        try {
            final Iterator<Filter> iterator = source.iterator();
            while (iterator.hasNext()) {
                final Filter filter = iterator.next();
                if (copy.size() >= NuggetAdminContract.MAX_FILTERS) {
                    throw new IllegalArgumentException("too many nugget admin filters");
                }
                if (filter == null) {
                    throw new IllegalArgumentException("nugget admin filters must not contain null values");
                }
                copy.add(filter);
            }
        } catch (UnsupportedOperationException | ConcurrentModificationException | IllegalStateException exception) {
            throw new IllegalArgumentException("invalid nugget admin filters", exception);
        }
        return copy.isEmpty() ? List.of() : List.copyOf(copy);
    }

    private static List<Sort> copySorts(final List<Sort> source) {
        final ArrayList<Sort> copy = new ArrayList<>(NuggetAdminContract.MAX_SORTS + 1);
        if (source != null) {
            try {
                final Iterator<Sort> iterator = source.iterator();
                while (iterator.hasNext()) {
                    final Sort sort = iterator.next();
                    if (copy.size() >= NuggetAdminContract.MAX_SORTS) {
                        throw new IllegalArgumentException("too many nugget admin sort fields");
                    }
                    if (sort == null) {
                        throw new IllegalArgumentException("nugget admin sort fields must not contain null values");
                    }
                    copy.add(sort);
                }
            } catch (UnsupportedOperationException | ConcurrentModificationException | IllegalStateException exception) {
                throw new IllegalArgumentException("invalid nugget admin sort fields", exception);
            }
        }
        boolean hasStableTieBreaker = false;
        for (Sort sort : copy) {
            if (sort.field() == NuggetAdminField.STABLE_ID) {
                hasStableTieBreaker = true;
                break;
            }
        }
        if (!hasStableTieBreaker) {
            copy.add(new Sort(NuggetAdminField.STABLE_ID, NuggetAdminSortDirection.ASC));
        }
        return List.copyOf(copy);
    }

    /**
     * Public exact-match filter.
     *
     * @param field allowlisted public field
     * @param value safe exact-match value
     */
    public record Filter(NuggetAdminField field, String value) {
        /**
         * Creates a validated filter.
         */
        public Filter {
            if (field == null) {
                throw new IllegalArgumentException("filter field must not be null");
            }
            value = AdminContractSupport.optionalText(value);
            if (value.isBlank()) {
                throw new IllegalArgumentException("filter value must be a non-empty safe value");
            }
        }
    }

    /**
     * Public sort clause.
     *
     * @param field allowlisted public field
     * @param direction sort direction
     */
    public record Sort(NuggetAdminField field, NuggetAdminSortDirection direction) {
        /**
         * Creates a validated sort clause.
         */
        public Sort {
            if (field == null) {
                throw new IllegalArgumentException("sort field must not be null");
            }
            direction = direction == null ? NuggetAdminSortDirection.ASC : direction;
        }
    }

    /**
     * Public collection page metadata.
     *
     * @param offset zero-based offset used by the response
     * @param limit requested page limit
     * @param returned number of records returned in this response
     * @param totalAvailable total matching records when authorized and known
     * @param totalAvailableAuthorized whether {@code totalAvailable} may be
     *        disclosed to this caller
     * @param partial whether optional provider data is partial
     * @param truncated whether the response was truncated by public hard bounds
     * @param collectedAt collection timestamp
     * @param metadata safe page metadata in schema-visible order
     */
    public record Page(
            long offset,
            int limit,
            int returned,
            long totalAvailable,
            boolean totalAvailableAuthorized,
            boolean partial,
            boolean truncated,
            Instant collectedAt,
            Map<String, String> metadata) {
        /**
         * Creates validated page metadata.
         */
        public Page {
            if (offset < 0L || limit < 0 || returned < 0 || totalAvailable < 0L) {
                throw new IllegalArgumentException("page counts must not be negative");
            }
            if (limit == 0) {
                throw new IllegalArgumentException("page limit must be positive");
            }
            if (limit > NuggetAdminContract.MAX_PAGE_LIMIT || returned > NuggetAdminContract.MAX_PAGE_LIMIT) {
                throw new IllegalArgumentException("page counts outside nugget admin bounds");
            }
            if (returned > limit) {
                throw new IllegalArgumentException("returned count must not exceed page limit");
            }
            if (totalAvailable < (long) returned) {
                throw new IllegalArgumentException("total available count must not be less than returned count");
            }
            try {
                Math.addExact(offset, (long) limit);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("page offset plus limit overflows", exception);
            }
            collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
            metadata = AdminContractSupport.orderedStringMap(metadata, NuggetAdminContract.MAX_METADATA_ENTRIES);
        }
    }
}
