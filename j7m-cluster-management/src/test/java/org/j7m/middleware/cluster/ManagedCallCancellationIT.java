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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ManagedCallCancellationIT {
    @TempDir Path directory;
    @Test void actualSignedHandlerObservesCallerDeadlineWhileInflightTracksRealCompletion()throws Exception{
        try(var cluster=new ClusterProcessFixture(directory,0)){
            var endpoint=(ClusterNodeEndpoint)cluster.invocations().get("n1");Path marker=directory.resolve("cancellation-observed");
            try{endpoint.invoke("cooperative","echo",("WAIT:"+marker).getBytes(StandardCharsets.UTF_8),Instant.now().plusMillis(400));}
            catch(IOException deadlineUnknown){System.out.println("CALLER_DEADLINE_UNKNOWN");}
            long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(!Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(Files.exists(marker));assertEquals("CANCELLED",Files.readString(marker));
            assertEquals(1,endpoint.status(Instant.now().plusSeconds(2)).node().inFlight());
            Files.writeString(marker.resolveSibling(marker.getFileName()+".release"),"release");
            while(endpoint.status(Instant.now().plusSeconds(2)).node().inFlight()!=0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(0,endpoint.status(Instant.now().plusSeconds(2)).node().inFlight());
            assertEquals("v1:next:normal",new String(endpoint.invoke("next","echo","normal".getBytes(StandardCharsets.UTF_8),Instant.now().plusSeconds(2)),StandardCharsets.UTF_8));
        }
    }
}
