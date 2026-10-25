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
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ClusterModuleConsumerTest {
    @TempDir Path directory;
    @Test void moduleConsumerNeedsOnlyClusterModuleForItsPublicTelemetryConstructor()throws Exception{
        List<String> modules=new ArrayList<>();
        for(String entry:System.getProperty("j7m.cluster.test.classpath").split(java.util.regex.Pattern.quote(File.pathSeparator))){
            Path path=Path.of(entry);String filename=path.getFileName().toString();
            if(Files.isDirectory(path)&&Files.exists(path.resolve("module-info.class"))||filename.startsWith("j7m-")&&filename.endsWith(".jar")||filename.startsWith("opentelemetry-")&&filename.endsWith(".jar"))modules.add(entry);
        }
        Path source=directory.resolve("src/consumer");Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),"module consumer { requires org.j7m.cluster.management; }");
        Files.writeString(source.resolve("Consumer.java"),"""
                package consumer;
                import java.util.Map;
                import org.j7m.middleware.cluster.*;
                import org.j7m.middleware.distributed.ResourceEndpoint;
                import org.j7m.middleware.integrations.Observability;
                public class Consumer {
                    public RollingClusterController create(ResourceEndpoint resources, Map<String,ClusterNodeEndpoint> nodes,
                            Map<String,ReleaseDescriptor> catalog, Observability telemetry) {
                        return new RollingClusterController(resources,nodes,catalog,telemetry);
                    }
                }
                """);
        var diagnostic=new ByteArrayOutputStream();int result=ToolProvider.getSystemJavaCompiler().run(null,diagnostic,diagnostic,"--module-path",String.join(File.pathSeparator,modules),"-d",directory.resolve("out").toString(),source.resolve("module-info.java").toString(),source.resolve("Consumer.java").toString());
        assertEquals(0,result,diagnostic.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
