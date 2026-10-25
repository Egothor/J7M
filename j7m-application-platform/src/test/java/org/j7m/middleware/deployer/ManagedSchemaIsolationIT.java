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

package org.j7m.middleware.deployer;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.processor.J7mProcessor;
import org.j7m.middleware.registry.embedded.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrent application classloaders deliberately reuse a schema ID with incompatible layouts.
 */
class ManagedSchemaIsolationIT {
    @TempDir Path directory;
    private URLClassLoader compile(String app,int field) throws Exception {
        var root=Files.createDirectories(directory.resolve(app));var classes=Files.createDirectories(root.resolve("classes"));
        var data=Files.writeString(root.resolve("Data.java"),"package same; import org.j7m.middleware.annotations.*; @SchemaType(id=\"shared.payload\",version=1) public record Data(@WireField(id="+field+") String value) {}");
        var api=Files.writeString(root.resolve("Api.java"),"package same; public interface Api {Data echo(Data value);}");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.artifact.test.classpath"),"-d",classes.toString()),null,files.getJavaFileObjects(data,api));
            task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    private static Object target(ClassLoader loader,Class<?> api,AtomicInteger calls) {
        return java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{api},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class) return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"Isolated schema target";};
            calls.incrementAndGet();return args[0];
        });
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void verifiedGeneratedIndexesRemainIsolatedPerExportDespiteIdenticalNamesAndSchemaIds() throws Exception {
        try(var first=compile("first",1);var second=compile("second",2)) {
            var apiA=first.loadClass("same.Api");var apiB=second.loadClass("same.Api");var dataA=first.loadClass("same.Data");var dataB=second.loadClass("same.Data");
            assertEquals(dataA.getName(),dataB.getName());assertNotSame(dataA,dataB);
            ValueCodec codecA=ManagedSchemaAllowlist.codec(first,null,type->assertSame(first,type.getClassLoader()));
            ValueCodec codecB=ManagedSchemaAllowlist.codec(second,null,type->assertSame(second,type.getClassLoader()));
            var store=new EmbeddedRegistryStore();var transportA=new GrpcTransportProvider();var transportB=new GrpcTransportProvider();
            try(var server=new MiddlewareRuntimeBuilder().runtimeId("isolated-host").registry(new EmbeddedRegistryClient(store,Clock.systemUTC())).codec(new DefaultValueCodec()).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                    var clientA=new MiddlewareRuntimeBuilder().runtimeId("client-a").registry(new EmbeddedRegistryClient(store,Clock.systemUTC())).codec(codecA).addTransport(transportA).disableNuggetAdmin().build();
                    var clientB=new MiddlewareRuntimeBuilder().runtimeId("client-b").registry(new EmbeddedRegistryClient(store,Clock.systemUTC())).codec(codecB).addTransport(transportB).disableNuggetAdmin().build()) {
                var callsA=new AtomicInteger();var callsB=new AtomicInteger();var options=new ExportOptions(false,"grpc","default",List.of(),Map.of(),Duration.ofSeconds(30),Duration.ofSeconds(10));
                try(var a=server.prepareExport("a",target(first,apiA,callsA),(Class)apiA,options);var b=server.prepareExport("b",target(second,apiB,callsB),(Class)apiB,options)) {
                    a.setValueCodec(codecA);b.setValueCodec(codecB);var refA=a.commit().ref();var refB=b.commit().ref();
                    var valueA=dataA.getConstructor(String.class).newInstance("a");var valueB=dataB.getConstructor(String.class).newInstance("b");
                    var methodA=apiA.getMethod("echo",dataA);var methodB=apiB.getMethod("echo",dataB);
                    var proxyA=clientA.reference(refA,apiA,ProxyOptions.defaults());var proxyB=clientB.reference(refB,apiB,ProxyOptions.defaults());
                    assertEquals(valueA,methodA.invoke(proxyA,valueA));assertEquals(valueB,methodB.invoke(proxyB,valueB));
                    var wrongA=List.of(clientA.encodeValue(valueA,dataA));var wrongB=List.of(clientB.encodeValue(valueB,dataB));
                    assertEquals(MiddlewareErrorCode.CODEC_ERROR,assertThrows(MiddlewareException.class,()->transportA.invoke(refB,MethodDescriptor.fromMethod(methodB),wrongA,InvocationOptions.defaults(),dataB)).code());
                    assertEquals(MiddlewareErrorCode.CODEC_ERROR,assertThrows(MiddlewareException.class,()->transportB.invoke(refA,MethodDescriptor.fromMethod(methodA),wrongB,InvocationOptions.defaults(),dataA)).code());
                    assertEquals(1,callsA.get());assertEquals(1,callsB.get());
                    assertEquals(valueA,methodA.invoke(proxyA,valueA));assertEquals(valueB,methodB.invoke(proxyB,valueB));assertEquals(2,callsA.get());assertEquals(2,callsB.get());
                }
            }
        }
    }
}
