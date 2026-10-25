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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionDeliveryTest {
    private static Map<String,String> claim(){return new HashMap<>(Map.of("input","漢".repeat(16384),"checkpoint","界".repeat(16384),"result","","owner","worker","fence","1","expires","1000","version","2"));}
    @Test void completeUnicodeClaimRoundTripsAboveQueueFrameButInsideExplicitExecutionBound()throws Exception {
        var delivery=new ExecutionDelivery("job","JOB",1,new ExecutionResult("RUNNING",claim()));
        assertTrue(delivery.encodedBytes()>QueueSessionWire.MAX_FRAME);assertTrue(delivery.encodedBytes()<ExecutionDelivery.MAX_FRAME);
        var output=new ByteArrayOutputStream();ExecutionDelivery.write(new DataOutputStream(output),delivery);
        assertEquals(delivery,ExecutionDelivery.read(new DataInputStream(new ByteArrayInputStream(output.toByteArray()))));
        var fields=claim();fields.put("stepResult","文".repeat(16384));fields.put("result","字".repeat(16384));
        assertTrue(new ExecutionDelivery("saga","SAGA",1,new ExecutionResult("COMPENSATING",fields)).encodedBytes()<ExecutionDelivery.MAX_FRAME);
        fields.put("extra1","文".repeat(16384));fields.put("extra2","字".repeat(16384));
        assertThrows(IllegalArgumentException.class,()->new ExecutionDelivery("large","SAGA",1,new ExecutionResult("RUNNING",fields)));
        var invalid=claim();invalid.put("fence","SYNTHETIC_SECRET");
        assertEquals("Execution claim metadata",assertThrows(IllegalArgumentException.class,()->new ExecutionDelivery("job","JOB",1,new ExecutionResult("RUNNING",invalid))).getMessage());
    }
    @Test void sharedFramingPreservesExactQueueBoundaryAndUsesOnlyExplicitExecutionLimit()throws Exception {
        for(int maximum:new int[]{QueueSessionWire.MAX_FRAME,ExecutionDelivery.MAX_FRAME}){
            var output=new ByteArrayOutputStream();byte[] key=new byte[32];byte[] payload=new byte[maximum-11];
            QueueSessionWire.send(new DataOutputStream(output),key,"n",1,maximum,out->out.write(payload));
            assertEquals(payload.length,QueueSessionWire.receive(new DataInputStream(new ByteArrayInputStream(output.toByteArray())),key,"n",1,maximum).length);
            output.reset();assertThrows(IOException.class,()->QueueSessionWire.send(new DataOutputStream(output),key,"n",1,maximum,out->out.write(new byte[maximum-10])));assertEquals(0,output.size());
        }
        assertThrows(IOException.class,()->QueueSessionWire.receive(new DataInputStream(new ByteArrayInputStream(new byte[0])),new byte[32],"n",1,65537));
    }
}
