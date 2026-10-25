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

package org.j7m.middleware.distributed;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class RemoteResourceAdmissionTest {
    static Instant deadline(){return Instant.now().plusSeconds(2);}
    static ResourceEndpoint endpoint(){return (ResourceEndpoint)Proxy.newProxyInstance(ResourceEndpoint.class.getClassLoader(),new Class<?>[]{ResourceEndpoint.class},(proxy,method,args)->new ReplicatedResources.Value("present",1));}
    static Map<String,RemoteResourceServer.Binding> bindings(){return Map.of("reader",new RemoteResourceServer.Binding(endpoint(),new byte[32],false));}
    @Test void configuredNonceCapacityRejectsOverflowBeforeDispatchWhileCompatibilityProfileStillAdmits()throws Exception{
        try(var server=new RemoteResourceServer(new InetSocketAddress("127.0.0.1",0),bindings(),null,2)){
            var client=new RemoteResourceClient(List.of(server.address()),"reader",new byte[32],null);
            assertEquals("present",client.read("key",deadline()).value());assertEquals("present",client.read("key",deadline()).value());
            assertThrows(IOException.class,()->client.read("key",deadline()));
            assertEquals(2,server.dispatchedRequests());assertEquals(1,server.nonceCapacityRejections());
        }
        try(var server=new RemoteResourceServer(new InetSocketAddress("127.0.0.1",0),bindings(),null)){
            var client=new RemoteResourceClient(List.of(server.address()),"reader",new byte[32],null);
            for(int i=0;i<3;i++)assertEquals("present",client.read("key",deadline()).value());
            assertEquals(3,server.dispatchedRequests());assertEquals(0,server.nonceCapacityRejections());
        }
    }
    @Test void invalidAdmissionBudgetsAreRejectedBeforeBinding()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new RemoteResourceServer(new InetSocketAddress("127.0.0.1",0),bindings(),null,0));
        assertThrows(IllegalArgumentException.class,()->new RemoteResourceServer(new InetSocketAddress("127.0.0.1",0),bindings(),null,65537));
    }
}
