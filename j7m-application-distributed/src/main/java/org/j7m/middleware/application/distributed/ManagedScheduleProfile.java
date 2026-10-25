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

import java.time.*;
import java.util.Objects;
import org.j7m.middleware.distributed.ResourceEndpoint;
import org.j7m.middleware.distributed.ReplicatedResourceClient;

/**
 * Immutable trusted durable trigger settings. Metadata and execution are separate durable stores;
 * pending identity bridges their recovery without XA. The host snapshots input through its declared
 * codec before scheduling; neither the engine nor a timer may decode application DTOs.
 * @param metadata borrowed authenticated CAS metadata resource
 * @param clock measured authenticated-reference clock health, never an application wall-clock argument
 * @param pattern fixed UTC interval or daily local wall time
 * @param start earliest planned UTC occurrence, millisecond precision
 * @param interval fixed-rate interval, null for DAILY
 * @param localTime daily local time, null for FIXED_RATE
 * @param zone explicit time zone, UTC for FIXED_RATE
 * @param gap missing local time behavior
 * @param fold repeated local time behavior
 * @param misfire behavior after the configured lateness threshold
 * @param misfireThreshold tolerated ordinary scheduler lateness, 1millisecond..1day
 * @param catchUpLimit latest retained overdue occurrences, 1..256
 * @param overlap authoritative job claim overlap contract, not forcible Java thread interruption
 * @param input trusted application input; the job host must capture its canonical encoded snapshot
 */
public record ManagedScheduleProfile(ResourceEndpoint metadata,ReplicatedResourceClient.ClockHealth clock,
        Pattern pattern,Instant start,Duration interval,LocalTime localTime,ZoneId zone,Gap gap,Fold fold,
        Misfire misfire,Duration misfireThreshold,int catchUpLimit,Overlap overlap,Object input) {
    /**
     * Supported finite schedule patterns.
     */
    public enum Pattern {
        /**
         * UTC instants separated by the fixed interval.
         */ FIXED_RATE,
        /**
         * One configured local wall time per civil date.
         */ DAILY
    }
    /**
     * Resolves a local time omitted by a forward clock transition.
     */
    public enum Gap {
        /**
         * Omit a nonexistent local occurrence.
         */ SKIP,
        /**
         * Shift the local time forward by the actual transition duration.
         */ SHIFT_BY_GAP
    }
    /**
     * Resolves a repeated local time during a backward transition.
     */
    public enum Fold {
        /**
         * Select the earlier UTC occurrence.
         */ EARLIER,
        /**
         * Select the later UTC occurrence.
         */ LATER,
        /**
         * Preserve both distinct UTC occurrences.
         */ BOTH
    }
    /**
     * Selects explicit bounded handling of overdue planned occurrences.
     */
    public enum Misfire {
        /**
         * Advance beyond overdue occurrences without submitting them.
         */ SKIP,
        /**
         * Submit only the latest overdue occurrence.
         */ FIRE_ONCE,
        /**
         * Submit at most the latest configured number of overdue occurrences.
         */ CATCH_UP
    }
    /**
     * Selects native durable claim serialization; pending occurrences may remain queued.
     */
    public enum Overlap {
        /**
         * Independent runs may own concurrent native leases.
         */ ALLOW,
        /**
         * At most one run in the definition may own a current native lease.
         */ FORBID
    }
    /**
     * Validates finite settings without I/O, application construction or clock sampling.
     * @throws NullPointerException for required metadata, time or policy collaborators
     * @throws IllegalArgumentException for invalid timing, precision or pattern-specific settings
     */
    public ManagedScheduleProfile {
        Objects.requireNonNull(metadata);Objects.requireNonNull(clock);Objects.requireNonNull(pattern);Objects.requireNonNull(start);
        Objects.requireNonNull(zone);Objects.requireNonNull(gap);Objects.requireNonNull(fold);Objects.requireNonNull(misfire);Objects.requireNonNull(misfireThreshold);Objects.requireNonNull(overlap);
        try {
            if(start.toEpochMilli()<0||!start.equals(Instant.ofEpochMilli(start.toEpochMilli()))||start.toEpochMilli()>Long.MAX_VALUE-86400000L
                    ||misfireThreshold.compareTo(Duration.ofMillis(1))<0||misfireThreshold.compareTo(Duration.ofDays(1))>0||catchUpLimit<1||catchUpLimit>256) throw invalid();
            if(pattern==Pattern.FIXED_RATE) {
                if(interval==null||localTime!=null||!zone.equals(ZoneOffset.UTC)||interval.compareTo(Duration.ofMillis(1))<0||interval.compareTo(Duration.ofDays(365))>0||interval.toNanosPart()%1000000!=0) throw invalid();
            } else if(interval!=null||localTime==null||localTime.getNano()%1000000!=0) throw invalid();
        } catch(ArithmeticException overflow) {throw invalid();}
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid durable schedule profile");}
    /**
     * Omits input, resource credentials and clock internals.
     * @return fixed safe diagnostic
     */
    @Override public String toString() {return "ManagedScheduleProfile[host-owned]";}
}
