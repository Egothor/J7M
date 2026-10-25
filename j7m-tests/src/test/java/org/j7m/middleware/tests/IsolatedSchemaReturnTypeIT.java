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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.registry.embedded.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real proxies retain a local application return class across both local and remote transport.
 */
class IsolatedSchemaReturnTypeIT {
    @TempDir Path directory;
    @SuppressWarnings({"rawtypes","unchecked"})
    @ParameterizedTest @ValueSource(strings={"grpc","rmi"})
    void explicitApplicationReturnClassNeverLoadsThroughTheTransportLoader(String transport) throws Exception {
        var source=directory.resolve("Data.java");var apiSource=directory.resolve("Api.java");var classes=directory.resolve("classes");Files.createDirectories(classes);
        Files.writeString(source,"package isolated; public record Data(String value) {}");Files.writeString(apiSource,"package isolated; public interface Api {Data echo(Data value);}");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),source.toString(),apiSource.toString()));
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var data=loader.loadClass("isolated.Data");var api=loader.loadClass("isolated.Api");var constructor=data.getConstructor(String.class);var accessor=data.getMethod("value");
            assertThrows(ClassNotFoundException.class,()->Class.forName(data.getName()));
            var schema=new SchemaDescriptor("isolated",1,data,List.of(new SchemaField("value",String.class,true,null)),value->{
                try {return Map.of("value",accessor.invoke(value));}catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Test encoder",failure);}
            },fields->{try{return constructor.newInstance(((Map<?,?>)fields).get("value"));}catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Test decoder",failure);}});
            var codec=new DefaultValueCodec(new SchemaRegistry(List.of(schema)));var store=new EmbeddedRegistryStore();var calls=new AtomicInteger();
            TransportProvider serverTransport=transport.equals("grpc")?new GrpcTransportProvider():new RmiTransportProvider();
            TransportProvider clientTransport=transport.equals("grpc")?new GrpcTransportProvider():new RmiTransportProvider();
            try(var server=new MiddlewareRuntimeBuilder().runtimeId("isolated-server").registry(new EmbeddedRegistryClient(store,Clock.systemUTC())).codec(codec).addTransport(serverTransport).disableNuggetAdmin().build();
                    var client=new MiddlewareRuntimeBuilder().runtimeId("isolated-client").registry(new EmbeddedRegistryClient(store,Clock.systemUTC())).codec(codec).addTransport(clientTransport).disableNuggetAdmin().build()) {
                var target=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{api},(proxy,method,args)->{if(method.getDeclaringClass()==Object.class) return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"Isolated test service";};calls.incrementAndGet();return args[0];});
                try(var prepared=server.prepareExport("isolated",target,(Class)api,new ExportOptions(false,transport,"default",List.of(),Map.of(),Duration.ofSeconds(30),Duration.ofSeconds(10)))) {
                    prepared.setValueCodec(codec);var registration=prepared.commit();var value=constructor.newInstance("payload");var method=api.getMethod("echo",data);
                    var descriptor=MethodDescriptor.fromMethod(method);var arguments=List.of(client.encodeValue(value,data));
                    assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,assertThrows(MiddlewareException.class,()->clientTransport.invoke(registration.ref(),descriptor,arguments,InvocationOptions.defaults(),String.class)).code());
                    assertEquals(0,calls.get());
                    assertEquals(value,method.invoke(client.reference(registration.ref(),api,ProxyOptions.defaults()),value));
                    assertEquals(value,method.invoke(server.lookup("isolated",api,LookupOptions.defaults()),value));
                    assertEquals(2,calls.get());
                }
            }
        }
    }
}
