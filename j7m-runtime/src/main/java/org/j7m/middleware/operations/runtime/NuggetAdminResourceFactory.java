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

package org.j7m.middleware.operations.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

interface NuggetAdminResourceFactory {
    static NuggetAdminResourceFactory standard() {
        return DefaultNuggetAdminResourceFactory.INSTANCE;
    }

    ExecutorService newProviderExecutor(NuggetAdminConfiguration configuration, String nuggetId);

    default ExecutorService newLifecycleExecutor(final NuggetAdminConfiguration configuration, final String nuggetId) {
        return newProviderExecutor(configuration, nuggetId);
    }

    default ExecutorService newControlExecutor(final NuggetAdminConfiguration configuration, final String nuggetId) {
        return newProviderExecutor(configuration, nuggetId);
    }

    default ScheduledExecutorService newControlMonitorExecutor(final String nuggetId) {
        return newDiscoveryExecutor(nuggetId);
    }

    ScheduledExecutorService newDiscoveryExecutor(String nuggetId);

    enum DefaultNuggetAdminResourceFactory implements NuggetAdminResourceFactory {
        INSTANCE;

        @Override
        public ExecutorService newProviderExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            return boundedExecutor(configuration, nuggetId, "introspection");
        }

        @Override
        public ExecutorService newLifecycleExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            return boundedExecutor(configuration, nuggetId, "lifecycle");
        }

        @Override
        public ExecutorService newControlExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId) {
            BlockingQueue<Runnable> queue = configuration.controlQueueCapacity() == 0
                    ? new SynchronousQueue<>()
                    : new ArrayBlockingQueue<>(configuration.controlQueueCapacity());
            return new ThreadPoolExecutor(
                    configuration.controlConcurrency(),
                    configuration.controlConcurrency(),
                    0L,
                    TimeUnit.MILLISECONDS,
                    queue,
                    threadFactory("j7m-nugget-control-" + nuggetId + "-"),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        public ScheduledExecutorService newControlMonitorExecutor(final String nuggetId) {
            return Executors.newSingleThreadScheduledExecutor(
                    threadFactory("j7m-nugget-control-monitor-" + nuggetId + "-"));
        }

        private static ExecutorService boundedExecutor(
                final NuggetAdminConfiguration configuration,
                final String nuggetId,
                final String purpose) {
            BlockingQueue<Runnable> queue = configuration.providerQueueCapacity() == 0
                    ? new SynchronousQueue<>()
                    : new ArrayBlockingQueue<>(configuration.providerQueueCapacity());
            return new ThreadPoolExecutor(
                    configuration.providerConcurrency(),
                    configuration.providerConcurrency(),
                    0L,
                    TimeUnit.MILLISECONDS,
                    queue,
                    threadFactory("j7m-nugget-" + purpose + "-" + nuggetId + "-"),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        public ScheduledExecutorService newDiscoveryExecutor(final String nuggetId) {
            return Executors.newSingleThreadScheduledExecutor(
                    threadFactory("j7m-nugget-discovery-" + nuggetId + "-"));
        }

        private static ThreadFactory threadFactory(final String prefix) {
            AtomicInteger counter = new AtomicInteger();
            return task -> {
                Thread thread = new Thread(task, prefix + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
