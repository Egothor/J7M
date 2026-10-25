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

package org.j7m.middleware.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.junit.jupiter.api.Test;

/**
 * Direct ObjectRef calls are outside the host alias counter and can keep the actual manager busy.
 */
class ApplicationManagerHostStopTest {
    @Test
    void replacementNeverStartsUntilTheActualManagerConfirmsStopAndCanRetryAfterQuiescence() throws Exception {
        List<String> effects = new ArrayList<>();
        ApplicationDeploymentResult busy = new ApplicationDeploymentResult(false, ApplicationRunState.DRAINING, "still running", List.of());
        assertThrows(IOException.class, () -> ApplicationManagerHost.afterStopped(() -> { effects.add("stop-busy"); return busy; },
                () -> { effects.add("unsafe-activate"); return "replacement"; }));
        assertEquals(List.of("stop-busy"), effects);
        var stopped = new ApplicationDeploymentResult(true, ApplicationRunState.STOPPED, "stopped", List.of());
        assertEquals("replacement", ApplicationManagerHost.afterStopped(() -> { effects.add("stop-complete"); return stopped; },
                () -> { effects.add("activate"); return "replacement"; }));
        assertEquals(List.of("stop-busy", "stop-complete", "activate"), effects);
        assertEquals("first-activation", ApplicationManagerHost.afterStopped(() -> null, () -> "first-activation"));
    }
}
