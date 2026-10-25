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

package org.j7m.middleware.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedJobDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobHostedGeneratorTest {
    @TempDir Path temporary;
    private Path compile(String text,boolean expected)throws Exception {
        var compiler=new SchemaProcessorTest();compiler.temporary=temporary;
        return compiler.compile(List.of(SchemaProcessorTest.source("Worker",text)),expected);
    }
    @Test @SuppressWarnings("unchecked")
    void generatedJobCallsTypedCheckpointRestoreAndProgressWithoutAnApplicationInterface()throws Exception {
        Path output=compile("public class Worker {public Worker(){} private String state; @DurableJob(value=\"work\",version=2) @Schedule(\"daily\") public java.util.concurrent.CompletionStage<String> run(String input){state=input;return java.util.concurrent.CompletableFuture.completedFuture(input);} @Checkpoint public String checkpoint(){return state;} @Restore public void restore(String saved){state=saved;} @Progress public String progress(){return state;}}",true);
        try(var loader=new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},getClass().getClassLoader())) {
            var index=(ManagedComponentIndex)loader.loadClass("fixture.Worker__J7mComponentIndex").getConstructor().newInstance();
            assertEquals(2,index.formatVersion());var definition=(ManagedJobDefinition<Object>)index.components().getFirst();
            assertEquals(String.class,definition.inputType());assertEquals(String.class,definition.resultType());assertEquals(String.class,definition.checkpointType());assertEquals(String.class,definition.progressType());
            assertEquals(java.util.Map.of("policy","default","schedule","daily"),definition.job().attributes());assertEquals(2,definition.job().version());assertEquals(4,definition.job().methods().size());
            Object value=definition.create(null);definition.restore(value,"saved");assertEquals("saved",definition.checkpoint(value));assertEquals("saved",definition.progress(value));
            definition.restore(value,null);assertNull(definition.checkpoint(value));
            var entry=definition.job().methods().stream().filter(method->method.name().equals("run")).findFirst().orElseThrow();
            var result=(java.util.concurrent.CompletionStage<?>)definition.invokeHosted(value,entry,new Object[]{"next"});assertEquals("next",result.toCompletableFuture().join());
            assertEquals("next",definition.checkpoint(value));assertTrue(definition.remoteMethods().isEmpty());
        }
    }
    @Test void incompleteHooksUnsafeScopeAndUndeclaredSchedulesAreRejectedBeforeGeneratingAnIndex()throws Exception {
        compile("public class Worker {public Worker(){} @DurableJob(\"work\") public String run(String input){return input;} @Checkpoint @Progress public String checkpoint(){return \"state\";} @Restore public void restore(String state){} }",false);
        compile("public class Worker {public Worker(){} @DurableJob(\"work\") public String run(String input){return input;} @Checkpoint public String checkpoint(){return \"state\";}}",false);
        compile("@Scope(Scope.Kind.CALL) public class Worker {public Worker(){} @DurableJob(\"work\") public String run(String input){return input;}}",false);
        compile("public class Worker {public Worker(){} @Schedule(\"daily\") public String run(String input){return input;}}",false);
        compile("public class Worker {public Worker(){} @DurableJob(\"work\") public String run(String input){return input;} @Checkpoint public int checkpoint(){return 1;} @Restore public void restore(int state){}}",false);
        compile("public class Worker {public Worker(){} @DurableJob(\"one\") public String first(String input){return input;} @DurableJob(\"two\") public String second(String input){return input;}}",false);
    }
}
