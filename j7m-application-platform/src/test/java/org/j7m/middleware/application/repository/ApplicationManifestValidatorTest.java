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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationManifest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationManifestValidatorTest {
    private static ApplicationManifest manifest(String bootstrap, List<ApplicationManifest.ServiceDeclaration> services) {
        return new ApplicationManifest("j7m.application/v1",
                new ApplicationIdentity(ApplicationCoordinate.parse("example.worker:background:1.0.0"), "background", "workers"),
                "", "25", "CLASSPATH", bootstrap, services, null, null);
    }
    private static ApplicationManifest.ServiceDeclaration service() {
        return new ApplicationManifest.ServiceDeclaration("//worker", "not.loaded.Service", "not.loaded.Implementation", "grpc", false, Map.of());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroOrMoreServicesAreDataOnlyAndDoNotRequireApplicationClassesToExist(boolean strict) {
        var validator = new ApplicationManifestValidator();
        var background = validator.validate(manifest("not.loaded.Background", List.of()), strict);
        assertTrue(background.accepted(), background.messages().toString());
        var exported = validator.validate(manifest("not.loaded.Background", List.of(service())), strict);
        assertTrue(exported.accepted(), exported.messages().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundManifestsStillRequireValidBootstrapAndEveryPresentServiceRemainsValidated(boolean strict) {
        var validator = new ApplicationManifestValidator();
        for (String bootstrap : List.of("", "unsafe/path", "invalid();")) {
            assertFalse(validator.validate(manifest(bootstrap, List.of()), strict).accepted());
        }
        var missing = new ApplicationManifest.ServiceDeclaration("", "", "", "grpc", false, Map.of());
        var unsafe = new ApplicationManifest.ServiceDeclaration("worker", "bad/type", "not.loaded.Implementation", "http", false, Map.of());
        assertFalse(validator.validate(manifest("not.loaded.Background", List.of(missing)), strict).accepted());
        assertFalse(validator.validate(manifest("not.loaded.Background", List.of(unsafe)), strict).accepted());
        assertFalse(validator.validate(manifest("not.loaded.Background", List.of(service(), service())), strict).accepted());
        assertFalse(validator.validate(null, strict).accepted());
    }
}
