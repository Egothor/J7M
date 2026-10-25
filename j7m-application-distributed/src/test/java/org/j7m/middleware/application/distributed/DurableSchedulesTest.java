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
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

/**
 * Real resource/execution state machines, controlled reference time and crash-window recovery.
 */
class DurableSchedulesTest {
    static final String FINGERPRINT="a".repeat(64);
    static final Instant START=Instant.parse("2026-01-01T00:00:00Z");
    static final class Metadata implements ResourceEndpoint {
        ReplicatedResources machine=new ReplicatedResources();long index;int writes,reads;int loseWrite;
        public synchronized ReplicatedResources.Result execute(String operation,String request,List<String> arguments,Instant deadline) throws IOException {
            var command=new ResourceCommand(operation,"metadata",request,arguments);machine.apply(++index,command.encode());writes++;
            if(writes==loseWrite)throw new IOException("Controlled committed metadata reply loss");return machine.result("metadata",request,index);
        }
        public synchronized ReplicatedResources.Value read(String key,Instant deadline) {reads++;return machine.queryAtReadIndex("metadata",key,index);}
        public ReplicatedResources.Result transaction(String tx,Instant deadline) {throw new AssertionError("No XA");}
        public List<String> prepared(Instant deadline) {throw new AssertionError("No XA");}
        public List<String> participants(String tx,Instant deadline) {throw new AssertionError("No XA");}
        void restore() {byte[] bytes=machine.snapshot();machine=new ReplicatedResources();machine.restore(bytes);}
    }
    static final class Fixture {
        final Metadata metadata=new Metadata();final SagaEndpoint execution=new SagaEndpoint();
        final AtomicLong now=new AtomicLong(START.toEpochMilli());final AtomicBoolean safe=new AtomicBoolean(true);
        final List<String> submissions=new CopyOnWriteArrayList<>();final AtomicBoolean loseSubmit=new AtomicBoolean();
        ManagedScheduleProfile profile(ManagedScheduleProfile.Misfire misfire,int limit) {
            return new ManagedScheduleProfile(metadata,()->new ReplicatedResourceClient.ClockMeasurement(now.get(),now.get(),0,safe.get()),ManagedScheduleProfile.Pattern.FIXED_RATE,START,
                    Duration.ofSeconds(10),null,ZoneOffset.UTC,ManagedScheduleProfile.Gap.SKIP,ManagedScheduleProfile.Fold.EARLIER,misfire,Duration.ofSeconds(1),limit,ManagedScheduleProfile.Overlap.FORBID,"input");
        }
        ExecutionResult submit(String id,Object input,Instant deadline) throws IOException {
            submissions.add(id);var result=new DurableJobs(execution.client()).submit("submit."+OwnershipData.hash(id),id,(String)input,deadline);
            if(loseSubmit.compareAndSet(true,false))throw new IOException("Controlled committed submission reply loss");return result;
        }
        DurableSchedules engine(ManagedScheduleProfile profile) {return new DurableSchedules(profile,"tenant/orders/1",FINGERPRINT,true,this::submit);}
    }
    private static Instant deadline() {return Instant.now().plusSeconds(3);}
    @Test void idleFutureAndBackwardClockDoNotPollOrAppendAndUnsafeClockCannotSubmit() throws Exception {
        var fixture=new Fixture();try(var engine=fixture.engine(fixture.profile(ManagedScheduleProfile.Misfire.SKIP,1))) {
            engine.prepare(deadline());assertEquals("SUBMITTED",engine.tick(deadline()).status());int reads=fixture.metadata.reads,writes=fixture.metadata.writes;
            fixture.now.set(START.minusSeconds(30).toEpochMilli());
            for(int n=0;n<100;n++)assertEquals(START.plusSeconds(10),engine.tick(deadline()).nextDue());
            assertEquals(reads,fixture.metadata.reads);assertEquals(writes,fixture.metadata.writes);assertEquals(1,fixture.submissions.size());
            fixture.now.set(START.plusSeconds(20).toEpochMilli());fixture.safe.set(false);
            assertThrows(IOException.class,()->engine.tick(deadline()));assertEquals(writes,fixture.metadata.writes);assertEquals(1,fixture.submissions.size());
        }
    }
    @Test void pendingSurvivesBothUnknownStoreWindowsAndNeverChangesPlannedIdentity() throws Exception {
        for(int lostMetadataWrite:List.of(2,3)) {
            var fixture=new Fixture();var profile=fixture.profile(ManagedScheduleProfile.Misfire.SKIP,1);fixture.metadata.loseWrite=lostMetadataWrite;
            try(var engine=fixture.engine(profile)) {engine.prepare(deadline());assertThrows(IOException.class,()->engine.tick(deadline()));}
            fixture.metadata.restore();fixture.now.set(START.plusSeconds(100).toEpochMilli());
            try(var recovered=fixture.engine(profile)) {recovered.prepare(deadline());recovered.tick(deadline());}
            if(lostMetadataWrite==2)assertEquals(1,fixture.submissions.size());
            assertEquals(1,new ExecutionEvents(fixture.execution.client()).snapshotAndTail(deadline()).runs().size());
        }
        var fixture=new Fixture();var profile=fixture.profile(ManagedScheduleProfile.Misfire.SKIP,1);fixture.loseSubmit.set(true);
        try(var engine=fixture.engine(profile)) {engine.prepare(deadline());assertThrows(IOException.class,()->engine.tick(deadline()));}
        fixture.metadata.restore();fixture.execution.machine.restore(fixture.execution.machine.snapshot());
        fixture.now.set(START.plusSeconds(100).toEpochMilli());
        try(var recovered=fixture.engine(profile)) {recovered.prepare(deadline());assertEquals("SUBMITTED",recovered.tick(deadline()).status());}
        assertEquals(2,fixture.submissions.size());assertEquals(fixture.submissions.get(0),fixture.submissions.get(1));
        assertEquals(1,new ExecutionEvents(fixture.execution.client()).snapshotAndTail(deadline()).runs().size());
    }
    @Test void competingSchedulersReplayTheSamePendingSubmissionAndDefinitionChangesFailClosed() throws Exception {
        var fixture=new Fixture();var profile=fixture.profile(ManagedScheduleProfile.Misfire.CATCH_UP,2);var barrier=new CyclicBarrier(2);
        DurableSchedules.Submitter bridge=(id,input,deadline)->{
            try{barrier.await(2,TimeUnit.SECONDS);}catch(Exception failure){throw new IOException(failure);}
            return fixture.submit(id,input,deadline);
        };
        try(var first=new DurableSchedules(profile,"tenant/orders/1",FINGERPRINT,true,bridge);var second=new DurableSchedules(profile,"tenant/orders/1",FINGERPRINT,true,bridge);var pool=Executors.newFixedThreadPool(2)) {
            first.prepare(deadline());second.prepare(deadline());
            var a=pool.submit(()->first.tick(deadline()));var b=pool.submit(()->second.tick(deadline()));a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);
            assertEquals(1,new HashSet<>(fixture.submissions).size());assertEquals(1,new ExecutionEvents(fixture.execution.client()).snapshotAndTail(deadline()).runs().size());
        }
        try(var changed=new DurableSchedules(profile,"tenant/orders/1","b".repeat(64),true,fixture::submit)) {assertThrows(IllegalArgumentException.class,()->changed.prepare(deadline()));}
        assertThrows(IllegalArgumentException.class,()->new DurableSchedules(profile,"tenant/orders/1",FINGERPRINT,false,fixture::submit));
    }
    @Test void misfireModesRetainOnlyTheExplicitLatestBoundAndNeverUseWorkerClockAsIdentity() throws Exception {
        for(var mode:ManagedScheduleProfile.Misfire.values()) {
            var fixture=new Fixture();var profile=fixture.profile(mode,3);fixture.now.set(START.plusSeconds(95).toEpochMilli());
            try(var engine=fixture.engine(profile)) {
                engine.prepare(deadline());var result=engine.tick(deadline());
                if(mode==ManagedScheduleProfile.Misfire.CATCH_UP) {engine.tick(deadline());result=engine.tick(deadline());}
                assertEquals(START.plusSeconds(100),result.nextDue());
                assertEquals(mode==ManagedScheduleProfile.Misfire.SKIP?0:mode==ManagedScheduleProfile.Misfire.FIRE_ONCE?1:3,fixture.submissions.size());
                assertEquals(fixture.submissions.size(),new HashSet<>(fixture.submissions).size());
            }
        }
    }
    private ManagedScheduleProfile daily(Fixture fixture,Instant start,ManagedScheduleProfile.Gap gap,ManagedScheduleProfile.Fold fold) {
        return new ManagedScheduleProfile(fixture.metadata,()->new ReplicatedResourceClient.ClockMeasurement(fixture.now.get(),fixture.now.get(),0,true),ManagedScheduleProfile.Pattern.DAILY,start,null,LocalTime.of(2,30),ZoneId.of("Europe/Prague"),gap,fold,
                ManagedScheduleProfile.Misfire.CATCH_UP,Duration.ofSeconds(1),8,ManagedScheduleProfile.Overlap.FORBID,"input");
    }
    @Test void dailyDstGapSkipOrShiftAndFoldEarlierLaterOrBothUseDistinctPlannedUtcInstants() throws Exception {
        for(var gap:ManagedScheduleProfile.Gap.values()) {
            var fixture=new Fixture();Instant start=Instant.parse("2026-03-28T00:00:00Z");var profile=daily(fixture,start,gap,ManagedScheduleProfile.Fold.BOTH);
            fixture.now.set(Instant.parse("2026-03-28T01:30:00Z").toEpochMilli());
            try(var engine=fixture.engine(profile)) {
                engine.prepare(deadline());var result=engine.tick(deadline());
                assertEquals(Instant.parse(gap==ManagedScheduleProfile.Gap.SKIP?"2026-03-30T00:30:00Z":"2026-03-29T01:30:00Z"),result.nextDue());
            }
        }
        for(var fold:ManagedScheduleProfile.Fold.values()) {
            var fixture=new Fixture();Instant start=Instant.parse("2026-10-25T00:00:00Z");var profile=daily(fixture,start,ManagedScheduleProfile.Gap.SKIP,fold);
            fixture.now.set(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli());
            try(var engine=fixture.engine(profile)) {
                engine.prepare(deadline());var first=engine.tick(deadline());
                if(fold==ManagedScheduleProfile.Fold.LATER) {assertEquals("WAITING",first.status());assertEquals(Instant.parse("2026-10-25T01:30:00Z"),first.nextDue());}
                if(fold==ManagedScheduleProfile.Fold.BOTH)assertEquals(Instant.parse("2026-10-25T01:30:00Z"),first.nextDue());
                fixture.now.set(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli());engine.tick(deadline());
                assertEquals(fold==ManagedScheduleProfile.Fold.BOTH?2:1,fixture.submissions.size());assertEquals(fixture.submissions.size(),new HashSet<>(fixture.submissions).size());
            }
        }
    }
    @Test void measuredUncertaintyDefersTheKnownWakeWithoutBusyPollingBeforeEligibility() throws Exception {
        var fixture=new Fixture();var base=fixture.profile(ManagedScheduleProfile.Misfire.SKIP,1);
        var profile=new ManagedScheduleProfile(base.metadata(),()->new ReplicatedResourceClient.ClockMeasurement(fixture.now.get(),fixture.now.get(),500,true),base.pattern(),base.start(),base.interval(),null,base.zone(),base.gap(),base.fold(),base.misfire(),base.misfireThreshold(),1,base.overlap(),base.input());
        try(var engine=fixture.engine(profile)) {
            engine.prepare(deadline());int reads=fixture.metadata.reads;
            var waiting=engine.tick(deadline());assertEquals("WAITING",waiting.status());assertEquals(START.plusMillis(500),waiting.nextDue());assertEquals(reads,fixture.metadata.reads);assertTrue(fixture.submissions.isEmpty());
            fixture.now.addAndGet(500);assertEquals("SUBMITTED",engine.tick(deadline()).status());assertEquals(1,fixture.submissions.size());
        }
    }
}
