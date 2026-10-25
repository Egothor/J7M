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

package org.j7m.middleware.registry.bootstrap;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns finite socket admission and absolute monotonic close timers.
 */
final class BootstrapConnections implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BootstrapConnections.class.getName());
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore permits;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            task -> thread(task, "bootstrap-deadline"));
    private volatile boolean closed;

    BootstrapConnections(final int maximum) {
        permits = new Semaphore(maximum);
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(1, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
    }

    static Thread thread(final Runnable task, final String name) {
        Thread thread = Thread.ofPlatform().name(name).daemon(true).inheritInheritableThreadLocals(false).unstarted(task);
        thread.setContextClassLoader(BootstrapConnections.class.getClassLoader());
        return thread;
    }

    static ThreadPoolExecutor workers(final int maximum, final String name) {
        return new ThreadPoolExecutor(0, maximum, 1, TimeUnit.SECONDS, new SynchronousQueue<>(),
                task -> thread(task, name), new ThreadPoolExecutor.AbortPolicy());
    }

    static long budget(final Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0
                || duration.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Bootstrap budget must be 1ms..5s");
        }
        return duration.toNanos();
    }

    static int millis(final long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IOException("Bootstrap deadline elapsed");
        }
        return (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    Lease admit(final Socket socket, final long deadline) throws IOException {
        if (closed || !permits.tryAcquire()) {
            throw new IOException("Bootstrap capacity unavailable");
        }
        sockets.add(socket);
        try {
            if (closed) {
                throw new IOException("Bootstrap endpoint closed");
            }
            var expiry = timer.schedule(() -> closeSocket(socket), millis(deadline), TimeUnit.MILLISECONDS);
            return () -> {
                expiry.cancel(false);
                closeSocket(socket);
                sockets.remove(socket);
                permits.release();
            };
        } catch (IOException | RuntimeException failure) {
            sockets.remove(socket);
            permits.release();
            closeSocket(socket);
            throw failure;
        }
    }

    static void closeSocket(final java.io.Closeable socket) {
        try {
            socket.close();
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.DEBUG, "Bootstrap terminal socket close failed ({0})", failure.getClass().getName());
        }
    }

    @Override public void close() {
        closed = true;
        sockets.forEach(BootstrapConnections::closeSocket);
        timer.shutdownNow();
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override void close();
    }
}
