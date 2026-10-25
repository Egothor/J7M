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
import java.time.*;
import org.junit.jupiter.api.Test;

/**
 * Host configuration errors fail before durable registration or application construction.
 */
class ManagedScheduleProfileTest {
    @Test void incompatiblePatternPrecisionBoundsAndMissingCapabilitiesFailWithoutMetadataEffects() {
        var fixture=new DurableSchedulesTest.Fixture();var good=fixture.profile(ManagedScheduleProfile.Misfire.CATCH_UP,256);
        assertEquals("ManagedScheduleProfile[host-owned]",good.toString());
        assertThrows(IllegalArgumentException.class,()->fixture.profile(ManagedScheduleProfile.Misfire.CATCH_UP,257));
        assertThrows(IllegalArgumentException.class,()->new ManagedScheduleProfile(good.metadata(),good.clock(),good.pattern(),good.start(),good.interval(),LocalTime.NOON,good.zone(),good.gap(),good.fold(),good.misfire(),good.misfireThreshold(),1,good.overlap(),"sensitive-input"));
        assertThrows(IllegalArgumentException.class,()->new ManagedScheduleProfile(good.metadata(),good.clock(),good.pattern(),good.start().plusNanos(1),good.interval(),null,good.zone(),good.gap(),good.fold(),good.misfire(),good.misfireThreshold(),1,good.overlap(),"sensitive-input"));
        assertThrows(IllegalArgumentException.class,()->new ManagedScheduleProfile(good.metadata(),good.clock(),good.pattern(),good.start(),good.interval(),null,good.zone(),good.gap(),good.fold(),good.misfire(),Duration.ZERO,1,good.overlap(),"sensitive-input"));
        assertThrows(NullPointerException.class,()->new ManagedScheduleProfile(null,good.clock(),good.pattern(),good.start(),good.interval(),null,good.zone(),good.gap(),good.fold(),good.misfire(),good.misfireThreshold(),1,good.overlap(),"sensitive-input"));
        assertEquals(0,fixture.metadata.writes);assertEquals(0,fixture.metadata.reads);
    }
}
