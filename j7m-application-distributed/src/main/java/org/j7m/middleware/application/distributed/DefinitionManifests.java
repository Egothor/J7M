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

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.application.api.InvocationTraceContext;
import org.j7m.middleware.distributed.DeduplicatedRpc;

/**
 * Immutable program registration in the execution store's protected metadata map.
 * Registration reuses native atomic CAS and does not manufacture a READY business job. No schema
 * constructors, application callbacks or external stores participate in metadata verification.
 */
final class DefinitionManifests {
    private DefinitionManifests() { }
    static void verify(ManagedExecutionBackend.Session session,String kind,String namespace,String definition,int version,String fingerprint,Instant deadline) throws IOException {
        if(!kind.matches("[a-z][a-z-]{0,15}")||!fingerprint.matches("[0-9a-f]{64}"))throw invalid();
        String key=kind+".definition."+OwnershipData.hash(namespace,definition,Integer.toString(version));
        var stored=session.client().query("E_VALUE",key,deadline);
        if(stored.status().equals("NOT_FOUND")) {
            String request="metadata."+OwnershipData.hash(key,fingerprint);
            try(var ignored=InvocationTraceContext.open(Map.of())) {
                new DeduplicatedRpc(session.client()).invoke(request,request,fingerprint,86400000,fingerprint,"",List.of(key,"0",fingerprint),deadline);
            }
            stored=session.client().query("E_VALUE",key,deadline);
        }
        if(!stored.status().equals("OK")||!fingerprint.equals(stored.fields().get("value")))throw invalid();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Managed definition registration conflicts or is unavailable");}
}
