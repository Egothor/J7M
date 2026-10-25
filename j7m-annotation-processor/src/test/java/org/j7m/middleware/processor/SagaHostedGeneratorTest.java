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

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import org.j7m.middleware.application.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual javac output exercises finite saga metadata and direct dispatch, including exact capacity.
 */
class SagaHostedGeneratorTest {
    @TempDir Path temporary;
    private Path compile(String source,boolean success) throws Exception {
        var compiler=new SchemaProcessorTest();compiler.temporary=temporary;
        return compiler.compile(List.of(SchemaProcessorTest.source("Counter",source)),success);
    }
    private static String steps(int count) {
        var source=new StringBuilder("@SagaDefinition(name=\"orders\",version=2,policy=\"sagas\") public class Counter {public Counter(){}");
        for(int i=0;i<count;i++) source.append("@SagaStep(name=\"s").append(i).append("\",order=").append(i).append(",idempotent=true) public String step").append(i).append("(String input){return input+\"").append(i).append("\";} @Compensation(step=\"s").append(i).append("\") public void undo").append(i).append("(String input){}");
        return source.append('}').toString();
    }
    @SuppressWarnings("unchecked")
    @Test void sevenStepsAndTheirInversesGenerateOneFeatureWithFourteenExactCallbacks() throws Exception {
        Path output=compile(steps(7),true);
        try(var loader=new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},getClass().getClassLoader())) {
            var index=(ManagedComponentIndex)loader.loadClass("fixture.Counter__J7mComponentIndex").getConstructor().newInstance();assertEquals(2,index.formatVersion());
            var adapter=(ManagedHostedAdapter<Object>)index.components().getFirst();var feature=adapter.hostedFeatures().getFirst();
            assertEquals(1,adapter.hostedFeatures().size());assertEquals("saga",feature.kind());assertEquals("orders",feature.id());assertEquals(2,feature.version());assertEquals(Map.of("policy","sagas"),feature.attributes());assertEquals(14,feature.methods().size());
            Object instance=adapter.create(null);var first=feature.methods().stream().filter(method->method.name().equals("step0")).findFirst().orElseThrow();
            assertEquals("input0",adapter.invokeHosted(instance,first,new Object[]{"input"}));
            var inverse=feature.methods().stream().filter(method->method.name().equals("undo0")).findFirst().orElseThrow();assertNull(adapter.invokeHosted(instance,inverse,new Object[]{"input"}));
            assertThrows(IllegalArgumentException.class,()->adapter.invokeHosted(instance,first,new Object[]{}));assertTrue(adapter.remoteMethods().isEmpty());
        }
        compile(steps(8),false);
    }
    @Test void invalidOrderChainAndInverseTargetsRejectAtCompilation() throws Exception {
        compile("@SagaDefinition(name=\"orders\") public class Counter {@SagaStep(name=\"one\",order=1) public String one(String input){return input;}}",false);
        compile("@SagaDefinition(name=\"orders\") public class Counter {@SagaStep(name=\"one\",order=0) public String one(String input){return input;} @SagaStep(name=\"two\",order=1) public int two(int input){return input;}}",false);
        compile("@SagaDefinition(name=\"orders\") public class Counter {@SagaStep(name=\"one\",order=0) public String one(String input){return input;} @Compensation(step=\"missing\") public void undo(String input){}}",false);
        compile("@SagaDefinition(name=\"orders\") public class Counter {@SagaStep(name=\"one\",order=0) private String one(String input){return input;}}",false);
        compile("@SagaDefinition(name=\"orders\") public class Counter {@RemoteMethod @SagaStep(name=\"one\",order=0) public String one(String input){return input;}}",false);
    }
}
