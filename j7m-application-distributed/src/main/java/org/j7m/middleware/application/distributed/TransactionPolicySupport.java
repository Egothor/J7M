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

package org.j7m.middleware.application.distributed;

import java.time.*;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * Shared finite admission rules; no ambient registry or application-selected capability.
 */
final class TransactionPolicySupport {
    private TransactionPolicySupport() { }
    static void names(Map<String, ?> profiles) {
        if (profiles == null || profiles.size() > 256) throw new IllegalArgumentException("Transaction profile capacity");
        for (String name : profiles.keySet()) if (!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid transaction profile name");
    }
    static void cleanup(Duration budget) {
        if (budget == null || budget.compareTo(Duration.ofMillis(1)) < 0 || budget.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Invalid transaction cleanup budget");
    }
    static void validateMix(ManagedServiceContract contract, Class<?> implementation, Class<?> business) {
        if (ManagedPolicyMetadata.scalar(contract, implementation, business, ResourceTransaction.class) != null
                && ManagedPolicyMetadata.scalar(contract, implementation, business, JdbcTransaction.class) != null) {
            throw new IllegalArgumentException("Native resource and local JDBC transactions cannot mix");
        }
    }
    static void boundary(InvocationContext context, ManagedInvocationPolicy.Proceed next) {
        if (!next.supportsResultFinalizers() || !ManagedInvocationLifetime.currentUserWorkOpen()) throw new IllegalStateException("TRANSACTION_SCOPE_UNAVAILABLE");
        deadline(context);
    }
    static Instant deadline(InvocationContext context) {
        Duration budget = context.control().remainingBudget();
        if (context.control().cancellationRequested() || budget.isNegative() || budget.isZero()) throw failure(MiddlewareErrorCode.DEADLINE_EXCEEDED);
        return Instant.now().plus(budget.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : budget);
    }
    static MiddlewareException failure(MiddlewareErrorCode code) { return MiddlewareException.of(code, "Managed transaction outcome unavailable"); }
    static InvocationResult unknown(String status) { return new InvocationResult(null, failure(MiddlewareErrorCode.UNAVAILABLE), Map.of("status", status)); }
    static InvocationResult outcome(InvocationResult result, String status) { return new InvocationResult(result.value(), result.failure(), Map.of("status", status)); }
    static int bytes(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); count += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3; }
        return count;
    }
}
