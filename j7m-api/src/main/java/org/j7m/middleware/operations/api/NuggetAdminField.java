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

/**
 * Allowlisted public query and deterministic ordering fields.
 *
 * <p>These fields are schema-visible names, not provider property paths.
 * Providers must map them to their own typed state explicitly and must not use
 * reflection, raw object field inspection, or caller-provided names to reach
 * implementation state. {@link #STABLE_ID} is the mandatory final tie-breaker
 * inserted by {@link NuggetAdminQuery} when the caller did not request it.</p>
 */
public enum NuggetAdminField {
    /**
     * Stable canonical identity used as the deterministic final tie-breaker.
     */
    STABLE_ID,
    /**
     * Nugget identity.
     */
    NUGGET_ID,
    /**
     * Runtime identity.
     */
    RUNTIME_ID,
    /**
     * Runtime generation.
     */
    GENERATION,
    /**
     * Application identity.
     */
    APPLICATION_ID,
    /**
     * Application coordinate.
     */
    COORDINATE,
    /**
     * Application instance identity.
     */
    INSTANCE_ID,
    /**
     * Object identity.
     */
    OBJECT_ID,
    /**
     * Object kind.
     */
    OBJECT_KIND,
    /**
     * Service name.
     */
    SERVICE_NAME,
    /**
     * Visibility category.
     */
    VISIBILITY,
    /**
     * State category.
     */
    STATE,
    /**
     * Health category.
     */
    HEALTH,
    /**
     * Readiness category.
     */
    READINESS,
    /**
     * Transport identifier.
     */
    TRANSPORT_ID,
    /**
     * Disk or repository role.
     */
    ROLE,
    /**
     * Disk root identity.
     */
    ROOT_ID,
    /**
     * Timestamp field.
     */
    TIMESTAMP
}
