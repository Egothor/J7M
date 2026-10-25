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

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueConsumerProcessTest {
    @TempDir Path directory;
    @Test void heldPathAppearsOnlyAfterCompleteClosedStagingAndPublishesExactMessageFields() throws Exception {
        Path held=directory.resolve("consumer.acks.held");
        String payload=Base64.getEncoder().encodeToString(new byte[32768]);
        String response="MESSAGE\tmessage-id\t"+payload+"\t12\t7\t1";
        var staged=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var writer=Executors.newSingleThreadExecutor()) {
            var publication=writer.submit(()->{
                QueueConsumerProcess.publishHeld(held,response,()->{
                    staged.countDown();
                    try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new AssertionError(stopped);}
                });
                return null;
            });
            try {
                assertTrue(staged.await(5,TimeUnit.SECONDS));
                assertFalse(Files.exists(held),"Final marker existence is the controller's complete-publication barrier");
                assertEquals(response,Files.readString(held.resolveSibling(held.getFileName()+".next")));
            } finally {release.countDown();publication.get(5,TimeUnit.SECONDS);}
        }
        assertEquals(response,Files.readString(held));assertFalse(Files.exists(held.resolveSibling(held.getFileName()+".next")));
        String[] fields=Files.readString(held).split("\\t");
        assertEquals(6,fields.length);assertEquals("MESSAGE",fields[0]);assertEquals("message-id",fields[1]);
        assertEquals(payload,fields[2]);assertEquals("12",fields[3]);assertEquals("7",fields[4]);assertEquals("1",fields[5]);
    }
}
