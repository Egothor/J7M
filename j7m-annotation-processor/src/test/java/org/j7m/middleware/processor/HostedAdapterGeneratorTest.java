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
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.annotations.PartitionHandler;
import org.j7m.middleware.application.api.ManagedComponentIndex;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostedAdapterGeneratorTest {
    @TempDir Path temporary;

    private Path compile(String body, boolean successful) throws Exception {
        var compiler = new SchemaProcessorTest(); compiler.temporary = temporary;
        return compiler.compile(List.of(SchemaProcessorTest.source("Counter", body)), successful);
    }

    @SuppressWarnings("unchecked")
    @Test void partitionAnnotationAloneGeneratesFiniteDirectReducerAndVersionTwoFeatureIndex() throws Exception {
        Path output = compile("@PartitionHandler(resource=\"counter\",version=2) public class Counter {public Counter(){} public int initial(){return 0;} public int apply(int state,int command){return state+command;} public int query(int state,String query){return state;}}", true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Counter__J7mComponentIndex").getConstructor().newInstance();
            assertEquals(2, index.formatVersion());
            var definition = (ManagedPartitionDefinition<Object>) index.components().getFirst();
            var annotation = definition.type().getAnnotation(PartitionHandler.class);
            assertEquals(annotation.resource(), definition.partition().id());
            assertEquals(annotation.version(), definition.partition().version());
            assertEquals(annotation.policy(), definition.partition().attributes().get("policy"));
            assertEquals(List.of(definition.partition()), definition.hostedFeatures());
            assertEquals(int.class, definition.stateType()); assertEquals(int.class, definition.commandType());
            assertEquals(String.class, definition.queryType()); assertEquals(int.class, definition.viewType());
            Object reducer = definition.create(null);
            assertEquals(0, definition.initial(reducer));
            assertEquals(7, definition.apply(reducer, 3, 4));
            assertEquals(7, definition.query(reducer, 7, "current"));
            var apply = definition.partition().methods().stream().filter(method -> method.name().equals("apply")).findFirst().orElseThrow();
            assertEquals(9, definition.invokeHosted(reducer, apply, new Object[]{4, 5}));
            assertThrows(IllegalArgumentException.class, () -> definition.invokeHosted(reducer, apply, new Object[]{1}));
            var undeclared = new ManagedMethod(definition.type().getName(), "apply", List.of("long", "long"), "long");
            assertThrows(IllegalArgumentException.class, () -> definition.invokeHosted(reducer, undeclared, new Object[]{1L, 2L}));
            assertTrue(definition.remoteMethods().isEmpty());
        }
    }

    @Test void mutableAmbiguousAndInaccessibleReducersAreRejectedAtCompilation() throws Exception {
        for (String extra : List.of("public int mutable;", "public long apply(long state,long command){return state;}", "")) {
            String initial = extra.isEmpty() ? "private int initial(){return 0;}" : "public int initial(){return 0;}";
            compile("@PartitionHandler(resource=\"counter\") public class Counter {public Counter(){} " + extra + initial
                    + " public int apply(int state,int command){return state+command;} public int query(int state,String query){return state;}}", false);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void queueCallbacksGenerateExactDataDispatchAndRejectAmbiguousOrFrameworkPayloads() throws Exception {
        Path output = compile("public class Counter {public Counter(){} @DurableQueueConsumer(queue=\"work\",policy=\"fast\",version=2) public void accept(String payload){} @DeadLetterHandler(queue=\"work\",value=\"poison\") public java.util.concurrent.CompletionStage<Void> poison(byte[] payload){return java.util.concurrent.CompletableFuture.completedFuture(null);}}", true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Counter__J7mComponentIndex").getConstructor().newInstance();
            var adapter = (org.j7m.middleware.application.api.ManagedHostedAdapter<Object>) index.components().getFirst();
            var consumer = adapter.hostedFeatures().stream().filter(feature -> feature.kind().equals("queue-consumer")).findFirst().orElseThrow();
            assertEquals("fixture.Counter#accept", consumer.id());
            assertEquals(2, consumer.version()); assertEquals(java.util.Map.of("queue","work","policy","fast"), consumer.attributes());
            assertNull(adapter.invokeHosted(adapter.create(null), consumer.methods().getFirst(), new Object[]{"payload"}));
            assertTrue(adapter.remoteMethods().isEmpty());
        }
        compile("public class Counter {public Counter(){} @DurableQueueConsumer(queue=\"work\") public void accept(String payload){} @DurableQueueConsumer(queue=\"work\") public void accept(byte[] payload){}}", false);
        compile("public class Counter {public Counter(){} @DeadLetterHandler(queue=\"work\") public void accept(java.util.Map<String,String> payload){}}", false);
        compile("public class Counter {public Counter(){} @DurableQueueConsumer(queue=\"work\") public String accept(String payload){return payload;}}", false);
    }

    @Test void outboxLifecycleUsesOnlyActuallySelectedResourceDependenciesWithoutCreatingDummyCallbacks() throws Exception {
        var compiler = new SchemaProcessorTest(); compiler.temporary = temporary;
        Path output = compiler.compile(List.of(
                SchemaProcessorTest.source("Producer", "public interface Producer {@OutboxPublisher(value=\"events\",policy=\"sql\",version=2) long send(String id,String payload);}"),
                SchemaProcessorTest.source("Counter", "@J7mComponent @Scope(Scope.Kind.CALL) public class Counter { @J7mResource(\"publisher\") public org.j7m.middleware.application.api.ManagedProvider<Producer> producer; @Inject public Counter(){} public Counter(@J7mResource(\"unused\") Producer unused){} }")), true);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var index = (ManagedComponentIndex) loader.loadClass("fixture.Counter__J7mComponentIndex").getConstructor().newInstance();
            var adapter = (org.j7m.middleware.application.api.ManagedHostedAdapter<?>) index.components().getFirst();
            assertEquals(org.j7m.middleware.annotations.Scope.Kind.CALL, adapter.scope());
            assertEquals(1, adapter.hostedFeatures().size());
            var relay = adapter.hostedFeatures().getFirst();
            assertEquals("outbox-relay", relay.kind()); assertEquals("fixture.Counter#publisher#events#2",relay.id());
            assertEquals(java.util.Map.of("resource","publisher","outbox","events","policy","sql","version","2"),relay.attributes());
            assertTrue(relay.methods().isEmpty()); assertEquals(1,adapter.dependencies().size());
            assertTrue(adapter.remoteMethods().isEmpty());
        }
    }
}
