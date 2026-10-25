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
import javax.tools.*;
import org.j7m.middleware.processor.J7mProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the complete distributed developer reference with actual generated hosted metadata.
 */
class ManagedDistributedReferenceTest {
    @TempDir Path directory;
    @Test void templateCompilesWithRealProcessorWithoutAnApplicationFrameworkInterface() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(root.getFileName().toString().equals("j7m-application-distributed")) root=root.getParent();
        Path source=root.resolve("examples/templates/annotation-first-service/src/main/java/org/j7m/examples/template/distributed/ManagedDistributedUsage.java");
        assertTrue(Files.isRegularFile(source));var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null);var inputs=Files.list(source.getParent())) {
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",directory.toString()),null,files.getJavaFileObjectsFromPaths(inputs.filter(path->path.toString().endsWith(".java")).toList()));
            task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.isRegularFile(directory.resolve("META-INF/services/org.j7m.middleware.application.api.ManagedComponentIndex")));
        try(var sources=Files.walk(directory)) {
            String generated=String.join("\n",sources.filter(path->path.toString().endsWith(".java")).map(path->{try{return Files.readString(path);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}}).toList());
            for(String feature:List.of("partition","event-subscription","durable-job","saga")) assertTrue(generated.contains("\""+feature+"\""),feature);
        }
    }
}
