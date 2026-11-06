// Copyright (c) 2026, Leo Galambos and the Egothor team
// SPDX-License-Identifier: BSD-4-Clause
// Licensed under the BSD 4-Clause License; see LICENSE for the full terms.

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkConsensusFixtureTest {
    @TempDir Path directory;

    @Test void backendReservationsExcludeCompetingListenersAndReleaseTheirPorts() throws Exception {
        Map<String, Integer> ports;
        try (var endpoints = new NetworkConsensusNodeIT.EndpointReservations(List.of("a", "b", "c", "d", "e"))) {
            ports = endpoints.ports();
            assertEquals(5, new HashSet<>(ports.values()).size());
            for (int port : ports.values()) assertThrows(IOException.class, () -> new ServerSocket(port));
            try (ServerSocket proxy = new ServerSocket(0)) { assertFalse(ports.containsValue(proxy.getLocalPort())); }
        }
        List<ServerSocket> rebound = new ArrayList<>();
        try {
            for (int port : ports.values()) rebound.add(new ServerSocket(port));
            assertEquals(5, rebound.size());
        } finally { for (ServerSocket socket : rebound) socket.close(); }
    }

    @Test void childBindFailurePreservesStderrAndCompletesReadinessExceptionally() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            IOException failure = assertThrows(IOException.class, () -> new NetworkConsensusNodeIT.Child("a", directory,
                    "a:" + occupied.getLocalPort() + ",b:1,c:2"));
            assertTrue(failure.getMessage().contains("BindException"), failure.getMessage());
            assertInstanceOf(ExecutionException.class, failure.getCause());
            assertEquals("process stopped before READY", failure.getCause().getCause().getMessage());
        }
    }
}
