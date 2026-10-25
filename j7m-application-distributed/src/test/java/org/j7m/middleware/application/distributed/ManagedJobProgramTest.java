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
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

/**
 * Proc:none descriptors, immutable program identity and data boundaries before component creation.
 */
class ManagedJobProgramTest {
    public static class Valid {
        static int constructed;
        public Valid(){constructed++;}
        @DurableJob("work") public String run(String input){return input;}
    }
    public static class Mutable extends Valid {String state;}
    public static class ParameterMarker extends Valid {@Override @DurableJob("work") public String run(@Progress String input){return input;}}
    public static class HookPolicy extends Valid {
        String state;
        @Checkpoint @Observed public String snapshot(){return state;}
        @Restore public void restore(String state){this.state=state;}
    }
    private static final class Definition implements ManagedJobDefinition<Object> {
        final Class<?> type;final ManagedHostedFeature feature;
        Definition(Class<?> type){this.type=type;var callbacks=Arrays.stream(type.getMethods()).filter(method->method.isAnnotationPresent(DurableJob.class)||method.isAnnotationPresent(Checkpoint.class)||method.isAnnotationPresent(Restore.class)||method.isAnnotationPresent(Progress.class)).map(Definition::identity).toList();feature=new ManagedHostedFeature("durable-job","work",1,Map.of("policy","default","schedule",""),callbacks);}
        private static ManagedMethod identity(Method method){return new ManagedMethod(method.getDeclaringClass().getName(),method.getName(),Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),method.getReturnType().getName());}
        @SuppressWarnings("unchecked") public Class<Object> type(){return (Class<Object>)type;}
        public String name(){return "";}public List<ManagedQualifier> qualifiers(){return List.of();}public Scope.Kind scope(){return Scope.Kind.ACTIVATION;}public List<ManagedDependency> dependencies(){return List.of();}
        public Object create(ManagedResolver resolver){throw new AssertionError("Preflight cannot construct a component");}
        public List<ManagedHostedFeature> hostedFeatures(){return List.of(feature);}public ManagedHostedFeature job(){return feature;}
        public Class<?> inputType(){return String.class;}public Class<?> resultType(){return String.class;}
        public Class<?> checkpointType(){return Arrays.stream(type.getMethods()).anyMatch(method->method.isAnnotationPresent(Checkpoint.class))?String.class:Void.class;}public Class<?> progressType(){return Void.class;}
        public Object invokeHosted(Object component,ManagedMethod method,Object[] arguments){throw new AssertionError("Preflight cannot invoke a callback");}
    }
    private static ManagedJobProfile profile(){
        PartitionEndpoint unused=new PartitionEndpoint(){public PartitionStateMachine.Status status(Instant end){throw new AssertionError("Metadata validation must not perform native I/O");}public PartitionReply execute(long epoch,String request,byte[] command,byte[] query,Instant end){throw new AssertionError("No native effect");}public PartitionReply query(long epoch,byte[] query,Instant end){throw new AssertionError("No native query");}};
        return new ManagedJobProfile(ManagedExecutionBackend.group("execution","tenant",unused),new ManagedPayloadCodec(new DefaultValueCodec(),new SchemaRegistry(List.of())),"jobs.tenant",Duration.ofSeconds(5),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofSeconds(4),Duration.ofMillis(20),true);
    }
    private static ManagedJobProgram program(Definition definition){return new ManagedJobProgram(definition.feature,definition,new ManagedHostedResources.Bundle("a".repeat(64),Map.of()),profile());}
    @Test void handwrittenDescriptorsCannotIgnoreMutableStateParameterProgressOrHookPolicy(){
        Valid.constructed=0;assertNotNull(program(new Definition(Valid.class)));
        for(Class<?> type:List.of(Mutable.class,ParameterMarker.class,HookPolicy.class))assertThrows(IllegalArgumentException.class,()->program(new Definition(type)));
        assertEquals(0,Valid.constructed);
    }
    @Test void completeFramesRejectTrailingDataOverCapacityChangedProgramAndSecretFailureDetails(){
        var program=program(new Definition(Valid.class));String input=program.input("logical","data");assertEquals("logical",program.input(input).logical());
        assertTrue(program.nativeId("logical").startsWith(program.prefix()));assertTrue(program.serialGroup().startsWith("job."));
        byte[] data=Base64.getDecoder().decode(input),trailing=Arrays.copyOf(data,data.length+1);assertThrows(IllegalArgumentException.class,()->program.input(Base64.getEncoder().encodeToString(trailing)));
        assertThrows(RuntimeException.class,()->program.input("large","x".repeat(12288)));
        var saved=program.saved(program.saved(new byte[]{1,2},new byte[]{3}));assertArrayEquals(new byte[]{1,2},saved.state());assertArrayEquals(new byte[]{3},saved.progress());
        String outcome=program.outcome("answer",null);assertFalse(program.failed(outcome));assertEquals("answer",program.result(outcome));
        String failure=program.outcome(null,new IllegalStateException("SYNTHETIC_SECRET"));assertTrue(program.failed(failure));assertFalse(new String(Base64.getDecoder().decode(failure),java.nio.charset.StandardCharsets.ISO_8859_1).contains("SYNTHETIC_SECRET"));assertEquals("Durable job failed",assertThrows(IllegalStateException.class,()->program.result(failure)).getMessage());
        var changed=new ManagedJobProgram(new Definition(Valid.class).feature,new Definition(Valid.class),new ManagedHostedResources.Bundle("b".repeat(64),Map.of()),profile());assertThrows(IllegalArgumentException.class,()->changed.input(input));
    }
    @Test void exactHostSelectorIsDeferredAndProfileBoundsFailBeforeAnyNativeOrApplicationEffect()throws Exception{
        var profile=profile();try(var factory=new ManagedJobFactory(Map.of("default",profile),1,1);var client=factory.client("default","work",1)){
            assertThrows(org.j7m.middleware.api.MiddlewareException.class,()->client.submit("id","value",Instant.now().plusSeconds(1)));assertThrows(IllegalArgumentException.class,()->factory.client("unknown","work",1));
            client.close();assertThrows(IllegalStateException.class,()->client.status("id",Instant.now().plusSeconds(1)));
        }
        assertThrows(IllegalArgumentException.class,()->new ManagedJobProfile(profile.backend(),profile.codec(),"jobs",Duration.ofSeconds(1),profile.renewInterval(),profile.operationBudget(),profile.handlerBudget(),profile.reconnectDelay(),true));
        assertThrows(IllegalArgumentException.class,()->new ManagedJobProfile(profile.backend(),profile.codec(),"jobs",Duration.ofSeconds(2),Duration.ofMillis(600),Duration.ofMillis(600),profile.handlerBudget(),profile.reconnectDelay(),true));
    }
}
