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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.processor.J7mProcessor;

/**
 * Signed ordinary application fixture exercising real generated saga callbacks across JVMs.
 */
final class GeneratedSagaService {
    static Path compileBundle(Path root) throws Exception {
        Files.createDirectories(root);var classes=Files.createDirectories(root.resolve("classes"));var source=root.resolve("Flow.java");
        Files.writeString(source,"""
                package sagaprocess;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                import java.util.concurrent.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="sagas",version="1")
                @SagaDefinition(name="orders") public class Flow {
                    private final ManagedProvider<ManagedSagaContext> context;private final int port;
                    @Inject public Flow(@J7mResource("saga") ManagedProvider<ManagedSagaContext> context,
                            @ConfigValue(value="sink.port",required=true) int port,@ConfigValue(value="ctor.marker",required=true) String marker)throws Exception{
                        this.context=context;this.port=port;java.nio.file.Files.writeString(java.nio.file.Path.of(marker),"CTOR");
                    }
                    @SagaStep(name="first",order=0,idempotent=true) public String first(String input){context.get().stageEffect("initial",0,input);return input+"-first";}
                    @SagaStep(name="last",order=1) public CompletionStage<String> last(String input){return J7mContext.currentInvocation().control().submit(()->{
                        if(!J7mContext.currentInvocation().security().principal().id().equals("alice")) throw new IllegalStateException("Wrong actual worker");
                        if(!InvocationTraceContext.current().equals(java.util.Map.of("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))) throw new IllegalStateException("Lost durable trace parent");
                        return exchange("forward",context.get().idempotencyKey(),input);
                    });}
                    @Compensation(step="first",idempotent=true) public void undoFirst(String input)throws Exception{exchange("undo-first",context.get().idempotencyKey(),input);}
                    @Compensation(step="last") public void undoLast(String input)throws Exception{exchange("undo-last",context.get().idempotencyKey(),input);}
                    @ReconciliationHandler public ManagedSagaDecision establish(ManagedSagaUncertainty uncertainty)throws Exception{
                        if(!InvocationTraceContext.current().equals(java.util.Map.of("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))) throw new IllegalStateException("Lost reconciliation trace parent");
                        String result=exchange("query",uncertainty.idempotencyKey(),(String)uncertainty.input());
                        return new ManagedSagaDecision(result==null?ManagedSagaDecision.Outcome.UNRESOLVED:ManagedSagaDecision.Outcome.SUCCEEDED,result);
                    }
                    private String exchange(String kind,String key,String input)throws Exception{
                        try(var socket=new java.net.Socket("127.0.0.1",port)){
                            socket.setSoTimeout(10000);var out=new java.io.DataOutputStream(socket.getOutputStream());out.writeUTF(kind);out.writeUTF(key);out.writeUTF(input);out.flush();
                            var in=new java.io.DataInputStream(socket.getInputStream());return in.readBoolean()?in.readUTF():null;
                        }
                    }
                }
                """);
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",classes.toString()),null,files.getJavaFileObjects(source));
            task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        var jar=root.resolve("application.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)) {
            for(var path:paths.filter(Files::isRegularFile).sorted().toList()) {out.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));out.write(Files.readAllBytes(path));out.closeEntry();}
        }
        var manifest=root.resolve("application.json");Files.writeString(manifest,"""
                {"schemaVersion":"j7m.application/v1","application":{"group":"fixture","name":"sagas","version":"1","displayName":"Sagas","description":"signed saga process qualification"},
                "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"CLASSPATH","bootstrapClass":"sagaprocess.Flow"},
                "services":[],"security":{"trustDomain":"default","confidential":false},
                "resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"32KiB"}}
                """);
        var bundle=new J7mBundleWriter().write(manifest,root.resolve("sagas.j7m"),List.of(jar),List.of(),List.of());
        var keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Files.write(root.resolve("operator.der"),keys.getPublic().getEncoded());
        return new DefaultBundleSigner(new SigningKeyPair(new SigningKeyId("operator"),"saga-test",keys.getPrivate(),keys.getPublic()),java.time.Clock.systemUTC()).sign(bundle).path();
    }
}
