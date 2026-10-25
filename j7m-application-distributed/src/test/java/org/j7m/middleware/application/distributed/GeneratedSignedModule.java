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
import java.io.*;
import java.lang.module.ModuleFinder;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.processor.J7mProcessor;

/**
 * Reusable actual processor, named-module packaging and signed deployment fixture.
 */
final class GeneratedSignedModule {
    record Artifact(Path bundle,Path publicKey,Path jar,String moduleName) { }
    private GeneratedSignedModule() { }
    static String modulePath() {
        var modules=new LinkedHashMap<String,Path>();
        for(String entry:System.getProperty("j7m.application.distributed.test.classpath").split(File.pathSeparator)) {
            Path path=Path.of(entry);if(!Files.isRegularFile(path)&&!Files.exists(path.resolve("module-info.class")))continue;
            for(var module:ModuleFinder.of(path).findAll())modules.putIfAbsent(module.descriptor().name(),path);
        }
        return String.join(File.pathSeparator,modules.values().stream().map(Path::toString).toList());
    }
    private static void compile(List<Path> inputs,Path output,boolean modular,boolean process) throws IOException {
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        var options=new ArrayList<>(List.of(modular?"--module-path":"-classpath",modular?modulePath():System.getProperty("j7m.application.distributed.test.classpath"),"-d",output.toString()));
        if(!process)options.add("-proc:none");
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=compiler.getTask(null,files,diagnostics,options,null,files.getJavaFileObjectsFromPaths(inputs));
            if(process)task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
    }
    static Artifact compile(Path root,Map<String,String> sources,String coordinate,String bootstrap,Set<String> extraRequires,Set<String> exports) throws Exception {
        var identity=ApplicationCoordinate.parse(coordinate);Path sourceRoot=Files.createDirectories(root.resolve("source"));var inputs=new ArrayList<Path>();
        for(var entry:new TreeMap<>(sources).entrySet()) {
            Path target=sourceRoot.resolve(entry.getKey()).normalize();assertTrue(target.startsWith(sourceRoot));Files.createDirectories(target.getParent());Files.writeString(target,entry.getValue());inputs.add(target);
        }
        Path probe=Files.createDirectories(root.resolve("probe"));compile(inputs,probe,false,true);
        var requires=new TreeSet<>(extraRequires);requires.add("org.j7m.api");
        var descriptor=new StringBuilder("module fixture.application {\n");for(var requirement:requires)descriptor.append("requires ").append(requirement).append(";\n");
        for(var exported:new TreeSet<>(exports))descriptor.append("exports ").append(exported).append(";\n");
        for(String service:List.of("org.j7m.middleware.application.api.ManagedComponentIndex","org.j7m.middleware.codec.api.SchemaIndex")) {
            Path provider=probe.resolve("META-INF/services/"+service);
            if(Files.isRegularFile(provider)) {
                var names=Files.readAllLines(provider).stream().map(String::trim).filter(value->!value.isEmpty()&&!value.startsWith("#")).toList();
                if(!names.isEmpty())descriptor.append("provides ").append(service).append(" with ").append(String.join(",",names)).append(";\n");
            }
        }
        descriptor.append("}\n");inputs.add(Files.writeString(sourceRoot.resolve("module-info.java"),descriptor));
        Path classes=Files.createDirectories(root.resolve("classes"));compile(inputs,classes,true,true);
        Path jar=root.resolve("application.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)) {
            for(var path:paths.filter(Files::isRegularFile).sorted().toList()) {out.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));out.write(Files.readAllBytes(path));out.closeEntry();}
        }
        Path manifest=root.resolve("application.json");Files.writeString(manifest,"""
            {"schemaVersion":"j7m.application/v1","application":{"group":"%s","name":"%s","version":"%s","displayName":"Managed combined gate","description":"signed public API integration"},
            "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"JPMS","bootstrapClass":"%s","bootstrapModule":"fixture.application"},
            "modules":[{"name":"fixture.application","path":"modules/application.jar"}],"services":[],
            "security":{"trustDomain":"default","confidential":false},"resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"32KiB"}}
            """.formatted(identity.id().group(),identity.id().name(),identity.version(),bootstrap));
        var bundle=new J7mBundleWriter().write(manifest,root.resolve("signed.j7m"),List.of(),List.of(jar),List.of());
        var keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Path publicKey=Files.write(root.resolve("operator.der"),keys.getPublic().getEncoded());
        var signed=new DefaultBundleSigner(new SigningKeyPair(new SigningKeyId("operator"),"combined-gate",keys.getPrivate(),keys.getPublic()),Clock.systemUTC()).sign(bundle);
        return new Artifact(signed.path(),publicKey,jar,"fixture.application");
    }
    static List<String> hostCommand(Path root,Path originalSource,String originalPackage,String main,Set<String> extraRequires,String... arguments) throws Exception {
        Path source=Files.createDirectories(root.resolve("host-source"));Path classes=Files.createDirectories(root.resolve("host-classes"));
        Path copied=Files.writeString(source.resolve(main+".java"),Files.readString(originalSource).replace("package "+originalPackage+";","package fixture.host; import "+originalPackage+".*;"));
        var requirements=new TreeSet<>(extraRequires);requirements.addAll(Set.of("org.j7m.application.distributed","org.j7m.transport.grpc","org.j7m.kernel","java.sql"));
        var descriptor=new StringBuilder("module fixture.host {\n");for(var requirement:requirements)descriptor.append("requires ").append(requirement).append(";\n");descriptor.append("}\n");
        Path module=Files.writeString(source.resolve("module-info.java"),descriptor);compile(List.of(copied,module),classes,true,false);
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx160m","--module-path",modulePath()+File.pathSeparator+classes,"--add-modules","ALL-MODULE-PATH","-m","fixture.host/fixture.host."+main));command.addAll(List.of(arguments));return command;
    }
}
