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
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class ClusterServerTest {
    static Instant deadline(){return Instant.now().plusSeconds(2);}
    static ClusterLifecycle lifecycle(){return (ClusterLifecycle)Proxy.newProxyInstance(ClusterLifecycle.class.getClassLoader(),new Class<?>[]{ClusterLifecycle.class},(proxy,method,args)->new ClusterView(0,Map.of(),""));}
    @Test void nonceAdmissionIsBoundedAndExplicitlyRejectsOverflowBeforeDispatch()throws Exception{
        try(var server=new ClusterServer(new InetSocketAddress("127.0.0.1",0),"cluster",Map.of("viewer",new ClusterServer.Principal(new byte[32],Set.of("INSPECT"))),lifecycle(),null,null,2)){
            var client=new ClusterAdminClient(server.address(),"cluster","viewer",new byte[32],null);
            assertEquals(0,client.inspect(deadline()).version());assertEquals(0,client.inspect(deadline()).version());
            assertThrows(IOException.class,()->client.inspect(deadline()));assertEquals(1,server.nonceCapacityRejections());
        }
    }
    @Test void credentialsCannotBeReusedAcrossDifferentPermissionSets()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new ClusterServer(new InetSocketAddress("127.0.0.1",0),"cluster",Map.of("reader",new ClusterServer.Principal(new byte[32],Set.of("INSPECT")),"operator",new ClusterServer.Principal(new byte[32],Set.of("APPLY"))),lifecycle(),null,null));
    }
    @Test void wrongClusterAndForgedPrincipalCannotReadOrMutate()throws Exception{
        try(var server=new ClusterServer(new InetSocketAddress("127.0.0.1",0),"cluster",Map.of("viewer",new ClusterServer.Principal(new byte[32],Set.of("INSPECT"))),lifecycle(),null,null)){
            assertThrows(IOException.class,()->new ClusterAdminClient(server.address(),"other","viewer",new byte[32],null).inspect(deadline()));
            byte[] forged=new byte[32];Arrays.fill(forged,(byte)1);
            assertThrows(IOException.class,()->new ClusterAdminClient(server.address(),"cluster","viewer",forged,null).inspect(deadline()));
            assertEquals(0,new ClusterAdminClient(server.address(),"cluster","viewer",new byte[32],null).inspect(deadline()).version());
        }
    }
}
