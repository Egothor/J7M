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
 * Real javac/direct hosted adapter and signed application fixture; no production sample.
 */
final class GeneratedEventSubscriptionService {
    static Path compileBundle(Path directory) throws Exception {
        return compileBundle(directory, false);
    }
    static Path compileBundle(Path directory, boolean secondFeature) throws Exception { return compileBundle(directory, secondFeature, "1MiB"); }
    static Path compileBundle(Path directory, boolean secondFeature, String payloadLimit) throws Exception {
        Files.createDirectories(directory); Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        Path application = directory.resolve("App.java");
        Files.writeString(application, """
                package subscription;
                import java.util.concurrent.CompletionStage;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="events",version="1")
                public class App {
                    private final int port;
                    private final String marker;
                    @Inject public App(@ConfigValue(value="sink.port",required=true) int port,
                            @ConfigValue(value="ctor.marker",required=true) String marker) throws Exception {
                        this.port=port; this.marker=marker; java.nio.file.Files.writeString(java.nio.file.Path.of(marker),"CTOR");
                    }
                    @EventSubscription(value="projection",filter="BATCH")
                    public CompletionStage<Void> receive(ManagedEventDelivery delivery) {
                        try { java.nio.file.Files.writeString(java.nio.file.Path.of(marker+"-body"),"BODY",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND); }
                        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
                        if(!J7mContext.currentInvocation().security().principal().id().equals("event-worker")) throw new IllegalStateException("wrong authority");
                        return J7mContext.currentInvocation().control().submit(() -> {
                            if(!J7mContext.currentInvocation().security().principal().id().equals("event-worker")) throw new IllegalStateException("lost worker context");
                            try(var socket=new java.net.Socket("127.0.0.1",port)) {
                                var output=new java.io.DataOutputStream(socket.getOutputStream());
                                output.writeUTF(delivery.kind().name()); output.writeUTF(delivery.cursor().partition());
                                output.writeLong(delivery.cursor().epoch()); output.writeLong(delivery.cursor().sequence());
                                byte[] bytes=delivery.payload(); output.writeInt(bytes.length); output.write(bytes); output.flush();
                                if(!new java.io.DataInputStream(socket.getInputStream()).readBoolean()) throw new IllegalStateException("sink failure");
                            }
                            return null;
                        });
                    }
                    @Shutdown public void stop() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(marker+"-closed"),"CLOSED"); }
                }
                """);
        if (secondFeature) {
            String extra = """
                    @EventSubscription(value="zz-fail",filter="BATCH")
                    public CompletionStage<Void> zzFail(ManagedEventDelivery delivery) { return receive(delivery); }
                    """;
            String source = Files.readString(application); int end = source.lastIndexOf('}');
            Files.writeString(application, source.substring(0, end) + extra + source.substring(end));
        }
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("-classpath", System.getProperty("j7m.application.distributed.test.classpath"),
                    "-d", classes.toString()), null, files.getJavaFileObjects(application));
            task.setProcessors(List.of(new J7mProcessor())); assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        Path jar = directory.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/'))); output.write(Files.readAllBytes(path)); output.closeEntry();
            }
        }
        Path manifest = directory.resolve("application.json");
        Files.writeString(manifest, """
                {"schemaVersion":"j7m.application/v1","application":{"group":"fixture","name":"events","version":"1","displayName":"Events","description":"signed hosted subscription gate"},
                "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"CLASSPATH","bootstrapClass":"subscription.App"},
                "services":[],"security":{"trustDomain":"default","confidential":false},
                "resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"1MiB"}}
                """);
        Files.writeString(manifest, Files.readString(manifest).replace("1MiB",payloadLimit));
        var bundle = new J7mBundleWriter().write(manifest, directory.resolve("events.j7m"), List.of(jar), List.of(), List.of());
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signing = new SigningKeyPair(new SigningKeyId("operator"), "subscription-test", keys.getPrivate(), keys.getPublic());
        Files.write(directory.resolve("operator.der"), keys.getPublic().getEncoded());
        return new DefaultBundleSigner(signing, java.time.Clock.systemUTC()).sign(bundle).path();
    }
}
