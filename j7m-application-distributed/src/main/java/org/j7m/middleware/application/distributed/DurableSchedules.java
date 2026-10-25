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

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.j7m.middleware.distributed.ExecutionResult;
import org.j7m.middleware.distributed.ReplicatedResources;

/**
 * Durable one-occurrence trigger engine with no threads or idle polling. A known next due time
 * drives a host timer. Atomic CAS records pending before submission; only confirmed stable submit
 * advances the cursor. A crash between stores replays the same planned UTC identity.
 */
public final class DurableSchedules implements AutoCloseable {
    /**
     * Trusted host-only submission bridge; must use a captured canonical input frame, stable request
     * identity and original deadline. It must not run application constructors or business handlers.
     */
    @FunctionalInterface public interface Submitter {
        /**
         * Submits or replays the exact pending occurrence.
         * @param occurrenceId stable identity of definition and planned UTC instant
         * @param input host profile input; the managed job host uses its pre-encoded immutable snapshot
         * @param deadline original operation deadline
         * @return confirmed native submission, never inferred from a timeout
         * @throws IOException on unknown or unavailable submission
         */
        ExecutionResult submit(String occurrenceId,Object input,Instant deadline) throws IOException;
    }
    /**
     * Bounded engine observation for a host's next timer.
     * @param nextDue conservative host wall-clock wake instant for the next known planned occurrence
     * @param status WAITING, SUBMITTED or SKIPPED
     */
    public record Tick(Instant nextDue,String status) { }
    private record State(Instant next,Instant pending,Instant advance) { }
    private record TimeWindow(Instant lower,long wallCorrection) { }
    private final ManagedScheduleProfile profile;
    private final Submitter submitter;
    private final String key,fingerprint;
    private volatile boolean closed;
    private State cached;
    /**
     * Creates a trigger without starting I/O or application work.
     * @param profile trusted immutable settings
     * @param definitionKey stable namespace/job/version identity, unchanged across artifact replacements
     * @param semanticFingerprint canonical program and input fingerprint, SHA256 lowercase hex
     * @param serial whether the target job profile enforces native serial claims
     * @param submitter trusted captured-frame bridge to the managed job client
     * @throws NullPointerException for missing collaborators
     * @throws IllegalArgumentException for invalid identity, fingerprint or contradictory overlap
     */
    public DurableSchedules(ManagedScheduleProfile profile,String definitionKey,String semanticFingerprint,boolean serial,Submitter submitter) {
        this.profile=Objects.requireNonNull(profile);this.submitter=Objects.requireNonNull(submitter);
        if(definitionKey==null||definitionKey.isEmpty()||definitionKey.length()>1024||semanticFingerprint==null||!semanticFingerprint.matches("[0-9a-f]{64}")
                ||serial!=(profile.overlap()==ManagedScheduleProfile.Overlap.FORBID)) throw invalid();
        key="schedule."+OwnershipData.hash(definitionKey);
        fingerprint=OwnershipData.hash(semanticFingerprint,profile.pattern().name(),profile.start().toString(),Objects.toString(profile.interval(),""),Objects.toString(profile.localTime(),""),profile.zone().getId(),zoneVersion(),profile.gap().name(),profile.fold().name(),profile.misfire().name(),profile.misfireThreshold().toString(),Integer.toString(profile.catchUpLimit()),profile.overlap().name());
    }
    /**
     * Registers or verifies the immutable definition and initial cursor using quorum CAS.
     * @param deadline original absolute I/O deadline
     * @throws IOException on unavailable or ambiguous metadata operations
     * @throws IllegalArgumentException if a different definition already owns this identity
     * @throws IllegalStateException after close or on unsupported native metadata responses
     */
    public synchronized void prepare(Instant deadline) throws IOException {
        requireOpen();
        for(int attempt=0;attempt<16;attempt++) {
            var stored=profile.metadata().read(key,deadline);
            if(stored!=null) {cached=decode(stored.value());return;}
            var initial=new State(first(),null,null);
            if(write(null,initial,deadline)) {cached=initial;return;}
        }
        throw new IOException("Schedule metadata contention");
    }
    /**
     * Advances at most one confirmed planned occurrence. Pending work is always recovered before
     * applying misfire policy; it is never discarded merely because the scheduler restarted late.
     * Calls before cached next due perform no metadata RPC. Unsafe time fails before any mutation.
     * @param deadline original absolute I/O and submit deadline, never refreshed inside the engine
     * @return known next timer and bounded status
     * @throws IOException for unsafe time, unavailable stores, contention or uncertain submission
     * @throws IllegalArgumentException for incompatible/corrupt persisted metadata
     * @throws IllegalStateException before preparation, after close or for rejected submission
     */
    public synchronized Tick tick(Instant deadline) throws IOException {
        requireOpen();if(cached==null) throw new IllegalStateException("Schedule is not prepared");
        var time=healthyTime();Instant now=time.lower();
        if(cached.pending()==null&&cached.next().isAfter(now)) return tick(cached.next(),"WAITING",time);
        for(int attempt=0;attempt<16;attempt++) {
            requireOpen();var stored=profile.metadata().read(key,deadline);if(stored==null) throw invalid();var state=decode(stored.value());cached=state;
            if(state.pending()==null) {
                if(state.next().isAfter(now)) return tick(state.next(),"WAITING",time);
                Instant planned=state.next();
                if(Duration.between(planned,now).compareTo(profile.misfireThreshold())>0) {
                    Instant latest=latest(now);
                    if(profile.misfire()==ManagedScheduleProfile.Misfire.SKIP) {
                        var skipped=new State(next(latest),null,null);
                        if(!write(stored,skipped,deadline)) continue;cached=skipped;return tick(skipped.next(),"SKIPPED",time);
                    }
                    if(profile.misfire()==ManagedScheduleProfile.Misfire.FIRE_ONCE) planned=latest;
                    else {
                        Instant retained=latest;
                        for(int n=1;n<profile.catchUpLimit();n++) {Instant previous=previous(retained);if(previous==null)break;retained=previous;}
                        if(retained.isAfter(planned)) planned=retained;
                    }
                }
                var pending=new State(state.next(),planned,next(planned));
                if(!write(stored,pending,deadline)) continue;
                state=pending;stored=profile.metadata().read(key,deadline);
                if(stored==null) throw invalid();var current=decode(stored.value());
                if(!current.equals(pending)) continue;
            }
            requireOpen();
            String occurrence="occurrence."+OwnershipData.hash(key,state.pending().toString());
            var accepted=submitter.submit(occurrence,profile.input(),deadline);
            if(!Set.of("READY","RUNNING","COMPLETING","COMPLETED","CANCELLED").contains(accepted.status())) throw new IllegalStateException("Schedule submission was not confirmed");
            var advanced=new State(state.advance(),null,null);
            if(write(stored,advanced,deadline)) {cached=advanced;return tick(advanced.next(),"SUBMITTED",time);}
        }
        throw new IOException("Schedule metadata contention");
    }
    private static Tick tick(Instant planned,String status,TimeWindow time) {return new Tick(planned.plusMillis(time.wallCorrection()),status);}
    private boolean write(ReplicatedResources.Value previous,State next,Instant deadline) throws IOException {
        requireOpen();String value=encode(next);String version=Long.toString(previous==null?0:previous.version());
        var result=profile.metadata().execute("BATCH","schedule."+OwnershipData.hash(key,version,value),List.of(key,version,value),deadline);
        if(result.status().equals("OK")) return true;
        if(result.status().equals("CONFLICT")) return false;
        throw new IOException("Schedule metadata write was not confirmed");
    }
    private String encode(State state) {return fingerprint+"|"+state.next().toEpochMilli()+"|"+(state.pending()==null?"":state.pending().toEpochMilli())+"|"+(state.advance()==null?"":state.advance().toEpochMilli());}
    private State decode(String value) {
        try {
            var parts=value.split("\\|",-1);if(parts.length!=4||!parts[0].equals(fingerprint))throw invalid();
            Instant next=Instant.ofEpochMilli(Long.parseLong(parts[1]));Instant pending=parts[2].isEmpty()?null:Instant.ofEpochMilli(Long.parseLong(parts[2]));Instant advance=parts[3].isEmpty()?null:Instant.ofEpochMilli(Long.parseLong(parts[3]));
            if(!scheduled(next)||(pending==null)!=(advance==null)||pending!=null&&(!scheduled(pending)||pending.isBefore(next)||!advance.equals(next(pending))))throw invalid();
            return new State(next,pending,advance);
        } catch(ArithmeticException|DateTimeException malformed) {throw invalid();}
    }
    private TimeWindow healthyTime() throws IOException {
        var sample=Objects.requireNonNull(profile.clock().sample());
        try {
            if(!sample.monotonicStable()||sample.time()<0||sample.reference()<0||sample.uncertainty()<0||sample.uncertainty()>500||Math.abs(Math.subtractExact(sample.time(),sample.reference()))>500) throw new IOException("CLOCK_UNSAFE: durable schedule suspended");
            long lower=Math.max(0,Math.subtractExact(sample.reference(),sample.uncertainty()));
            return new TimeWindow(Instant.ofEpochMilli(lower),Math.subtractExact(sample.time(),lower));
        } catch(ArithmeticException overflow) {throw new IOException("CLOCK_UNSAFE: durable schedule suspended",overflow);}
    }
    private String zoneVersion() {
        if(profile.zone().getRules().isFixedOffset()) return "fixed:"+profile.zone().getRules().getOffset(profile.start());
        return java.time.zone.ZoneRulesProvider.getVersions(profile.zone().getId()).lastKey();
    }
    private boolean scheduled(Instant instant) {
        return !instant.isBefore(profile.start())&&latest(instant).equals(instant);
    }
    private Instant first() {return profile.pattern()==ManagedScheduleProfile.Pattern.FIXED_RATE?profile.start():dailyNext(profile.start().minusMillis(1));}
    private Instant next(Instant after) {
        if(profile.pattern()==ManagedScheduleProfile.Pattern.DAILY) return dailyNext(after);
        long steps=Math.floorDiv(Math.subtractExact(after.toEpochMilli(),profile.start().toEpochMilli()),profile.interval().toMillis())+1;
        return profile.start().plusMillis(Math.multiplyExact(steps,profile.interval().toMillis()));
    }
    private Instant latest(Instant now) {
        if(profile.pattern()==ManagedScheduleProfile.Pattern.FIXED_RATE) {
            long steps=Math.floorDiv(Math.subtractExact(now.toEpochMilli(),profile.start().toEpochMilli()),profile.interval().toMillis());
            return profile.start().plusMillis(Math.multiplyExact(steps,profile.interval().toMillis()));
        }
        LocalDate date=now.atZone(profile.zone()).toLocalDate();
        for(int day=0;day<370;day++,date=date.minusDays(1)) {var candidates=day(date);for(int i=candidates.size()-1;i>=0;i--)if(!candidates.get(i).isAfter(now)&&!candidates.get(i).isBefore(profile.start()))return candidates.get(i);}
        throw invalid();
    }
    private Instant previous(Instant before) {
        if(profile.pattern()==ManagedScheduleProfile.Pattern.FIXED_RATE) {var prior=before.minus(profile.interval());return prior.isBefore(profile.start())?null:prior;}
        LocalDate date=before.atZone(profile.zone()).toLocalDate();
        for(int day=0;day<370;day++,date=date.minusDays(1)) {
            var candidates=day(date);for(int i=candidates.size()-1;i>=0;i--) {var candidate=candidates.get(i);if(candidate.isBefore(profile.start()))return null;if(candidate.isBefore(before))return candidate;}
        }
        throw invalid();
    }
    private Instant dailyNext(Instant after) {
        LocalDate date=after.atZone(profile.zone()).toLocalDate();
        for(int n=0;n<370;n++,date=date.plusDays(1))for(var candidate:day(date))if(candidate.isAfter(after)&&!candidate.isBefore(profile.start()))return candidate;
        throw invalid();
    }
    private List<Instant> day(LocalDate date) {
        var local=LocalDateTime.of(date,profile.localTime());var offsets=profile.zone().getRules().getValidOffsets(local);
        if(offsets.isEmpty()) {
            if(profile.gap()==ManagedScheduleProfile.Gap.SKIP) return List.of();
            var transition=profile.zone().getRules().getTransition(local);
            return List.of(local.plus(transition.getDuration()).toInstant(transition.getOffsetAfter()));
        }
        var values=offsets.stream().map(local::toInstant).sorted().toList();
        if(values.size()==1||profile.fold()==ManagedScheduleProfile.Fold.BOTH)return values;
        return List.of(profile.fold()==ManagedScheduleProfile.Fold.EARLIER?values.getFirst():values.getLast());
    }
    private void requireOpen() {if(closed)throw new IllegalStateException("Durable schedule is closed");}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid durable schedule definition or state");}
    /**
     * Stops future engine operations without I/O or closing borrowed metadata/backend resources.
     */
    @Override public void close() {closed=true;}
    /**
     * Omits definition identity, input and capabilities.
     * @return fixed safe label
     */
    @Override public String toString() {return "DurableSchedules[host-owned]";}
}
