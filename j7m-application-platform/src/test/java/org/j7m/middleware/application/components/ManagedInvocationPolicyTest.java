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

package org.j7m.middleware.application.components;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.junit.jupiter.api.Test;

class ManagedInvocationPolicyTest {
    public interface Wire { String ping(); }
    public static class Worker implements Wire {
        @Override public String ping() { return "pong"; }
        @Deadline(millis = 100) public String execute(String input) { return input; }
        public String execute(Integer input) { return input.toString(); }
    }
    public static class HiddenPolicyWorker {
        public String execute(String input) { return helper(input); }
        @Deadline(millis = 100) private String helper(String input) { return input; }
    }
    private static ManagedServiceContract contract(Class<?> type) {
        return new ManagedServiceContract("hosted/job/example", 1,
                new ManagedMethod(type.getName(), "execute", List.of(String.class.getName()), String.class.getName()));
    }

    @Test
    void concreteHostedCallbackUsesPoliciesWithoutRelaxingOrdinaryWireValidation() throws Exception {
        ManagedInvocationPolicy policy = new ManagedCallingPolicy();
        var contract = contract(Worker.class);
        assertThrows(IllegalArgumentException.class, () -> policy.validate(contract, Worker.class, Worker.class));
        assertDoesNotThrow(() -> policy.validateHostedExposure(Worker.class, Set.of(contract)));
        var wire = new ManagedServiceContract("wire", 1, new ManagedMethod(Wire.class.getName(), "ping", List.of(), String.class.getName()));
        assertDoesNotThrow(() -> policy.validateHostedExposure(Worker.class,
                java.util.Map.of(contract, Worker.class, wire, Wire.class)));
        assertThrows(IllegalArgumentException.class, () -> policy.validateExposure(Worker.class, java.util.Map.of(wire, Wire.class)),
                "wire-only surface must not silently cover the annotated hosted method");
        assertDoesNotThrow(() -> policy.validateHosted(contract, Worker.class, Set.of(contract.method())));
        try (var binding = policy.bindHosted(contract, Worker.class, Set.of(contract.method()))) {
            org.junit.jupiter.api.Assertions.assertNotNull(binding);
        }
        assertThrows(IllegalArgumentException.class, () -> policy.validate(contract, Worker.class, Worker.class));
    }

    @Test
    void undeclaredOverloadAndHiddenPolicyFailClosedAndLeaveNoHostedScope() {
        ManagedInvocationPolicy policy = new ManagedCallingPolicy();
        var contract = contract(Worker.class);
        var other = new ManagedMethod(Worker.class.getName(), "execute", List.of(Integer.class.getName()), String.class.getName());
        assertThrows(IllegalArgumentException.class, () -> policy.validateHosted(contract, Worker.class, Set.of(other)));
        assertThrows(IllegalArgumentException.class, () -> policy.bindHosted(contract, Worker.class, Set.of(other)));
        var hidden = contract(HiddenPolicyWorker.class);
        assertThrows(IllegalArgumentException.class, () -> policy.validateHostedExposure(HiddenPolicyWorker.class, Set.of(hidden)));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(hidden, HiddenPolicyWorker.class, HiddenPolicyWorker.class));
        assertThrows(IllegalArgumentException.class, () -> policy.validateHostedExposure(Worker.class, Set.of()));
    }
}
