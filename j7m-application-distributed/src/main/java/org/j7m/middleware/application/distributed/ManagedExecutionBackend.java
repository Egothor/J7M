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
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.j7m.middleware.distributed.DurableJobs;
import org.j7m.middleware.distributed.DurableSagas;
import org.j7m.middleware.distributed.ExecutionClient;
import org.j7m.middleware.distributed.ExecutionEvents;
import org.j7m.middleware.distributed.ExecutionSession;
import org.j7m.middleware.distributed.PartitionAdminClient;
import org.j7m.middleware.distributed.PartitionEndpoint;
import org.j7m.middleware.distributed.PartitionReply;
import org.j7m.middleware.distributed.PartitionStateMachine;
import org.j7m.middleware.distributed.ReplicatedQueue;
import org.j7m.middleware.application.api.InvocationTraceContext;

/**
 * Trusted scope-bound execution source separating dynamic discovery from pinned live claims.
 * Every new claim opens a session at the current authoritative group/epoch. That session never
 * retargets after MOVED/FROZEN, including a later handoff back to the original physical group.
 * Borrowed endpoints and topology are not closed by this adapter.
 */
public final class ManagedExecutionBackend {
    @FunctionalInterface private interface Route { Located resolve(Instant deadline)throws IOException; }
    private record Located(String group,long epoch,PartitionEndpoint endpoint) { }
    private final String partition,scope;
    private final Route route;
    private final Supplier<Map<String,String>> context;
    private final ExecutionClient dynamic;
    private ManagedExecutionBackend(String partition,String scope,Route route,Supplier<Map<String,String>> context) {
        if(partition==null||partition.isBlank()||partition.length()>128||partition.indexOf('\0')>=0||scope==null||scope.isBlank()||scope.length()>128||scope.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid execution source");
        this.partition=partition;this.scope=scope;this.route=route;this.context=Objects.requireNonNull(context);
        dynamic=new ExecutionClient(partition,scope,(id,deadline)->{var located=route.resolve(deadline);return new ReplicatedQueue.Route(located.endpoint(),located.epoch());},context);
    }
    /**
     * Borrows one fixed physical group; new sessions read its current committed ownership epoch.
     * Frozen old sources reject new claims; existing sessions retain their original epoch forever.
     * @param partition stable partition identity
     * @param scope authenticated execution scope
     * @param endpoint fixed physical group's authorized endpoint, never a silently retargeting facade
     * @return source without starting workers or acquiring authority
     */
    public static ManagedExecutionBackend group(String partition,String scope,PartitionEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        return new ManagedExecutionBackend(partition,scope,deadline->{var status=endpoint.status(deadline);checkStatus(status,partition,status.group(),status.epoch());return new Located(status.group(),status.epoch(),endpoint);},InvocationTraceContext::current);
    }
    /**
     * Resolves authoritative topology only for a new claim or dynamic discovery operation.
     * @param partition stable partition identity
     * @param scope authenticated execution scope
     * @param topology borrowed authoritative topology client
     * @param groups finite fixed physical endpoints keyed by their actual group identity
     * @return bounded source with captured immutable endpoint inventory
     */
    public static ManagedExecutionBackend partition(String partition,String scope,PartitionAdminClient topology,Map<String,PartitionEndpoint> groups) {
        Objects.requireNonNull(topology);var inventory=Map.copyOf(groups);
        if(inventory.isEmpty()||inventory.size()>64||inventory.keySet().stream().anyMatch(id->id.isBlank()||id.length()>128||id.indexOf('\0')>=0))throw new IllegalArgumentException("Invalid execution group inventory");
        return new ManagedExecutionBackend(partition,scope,deadline->{var current=topology.route(partition,deadline);var endpoint=inventory.get(current.ownerGroup());if(endpoint==null||!current.partition().equals(partition))throw new IOException("Execution route unavailable");checkStatus(endpoint.status(deadline),partition,current.ownerGroup(),current.epoch());return new Located(current.ownerGroup(),current.epoch(),endpoint);},InvocationTraceContext::current);
    }
    /**
     * Opens fresh ownership before a new claim; does not itself acquire a job or saga lease.
     * @param deadline original route/verification deadline
     * @return session pinned to this exact physical group and ownership epoch
     * @throws IOException if no current authoritative source is available
     */
    public Session open(Instant deadline)throws IOException {
        var located=route.resolve(deadline);
        return new Session(located);
    }
    /**
     * Returns a dynamic scope client for submission, listing and explicit administration only.
     * Live claim checkpoint/renew/complete/effects must use Session.client() instead.
     * @return borrowed discovery/submission client
     */
    public ExecutionClient client(){return dynamic;}
    /**
     * Returns lazy paged discovery over the dynamic authoritative source, never claim authority.
     * @return execution event/listing facade
     */
    public ExecutionEvents discovery(){return new ExecutionEvents(dynamic);}
    /**
     * Pinned host-only live execution capability. It is not installed in an application context.
     */
    public final class Session {
        private final Located located;
        private final ExecutionClient client;
        private Session(Located located) {
            this.located=located;PartitionEndpoint pinned=new PartitionEndpoint(){
                @Override public PartitionReply execute(long epoch,String request,byte[] command,byte[] query,Instant deadline)throws IOException {
                    ensure(epoch);verify(deadline);return checked(located.endpoint().execute(epoch,request,command,query,deadline));
                }
                @Override public PartitionReply query(long epoch,byte[] query,Instant deadline)throws IOException {
                    ensure(epoch);verify(deadline);return checked(located.endpoint().query(epoch,query,deadline));
                }
                @Override public PartitionStateMachine.Status status(Instant deadline)throws IOException {var status=located.endpoint().status(deadline);checkStatus(status,partition,located.group(),located.epoch());return status;}
                private void ensure(long epoch)throws IOException {if(epoch!=located.epoch())throw new IOException("Execution claim epoch revoked");}
                private PartitionReply checked(PartitionReply reply)throws IOException {if(Set.of("MOVED","FROZEN").contains(reply.status())||reply.epoch()!=located.epoch())throw new IOException("Execution claim revoked; outcome UNKNOWN");return reply;}
            };
            client=new ExecutionClient(partition,scope,(id,deadline)->new ReplicatedQueue.Route(pinned,located.epoch()),context);
        }
        /**
         * Returns the actual pinned physical group.
         * @return stable trusted group identity
         */
        public String group(){return located.group();}
        /**
         * Returns the immutable pinned partition epoch, not a current authority assertion.
         * @return captured positive epoch
         */
        public long epoch(){return located.epoch();}
        /**
         * Returns the exact fixed client for every mutation and query of this live claim.
         * @return host-only client which never follows MOVED
         */
        public ExecutionClient client(){return client;}
        /**
         * Returns ownership-fenced job operations on this exact session.
         * @return job facade
         */
        public DurableJobs jobs(){return new DurableJobs(client);}
        /**
         * Returns ownership-fenced saga operations on this exact session.
         * @return saga facade
         */
        public DurableSagas sagas(){return new DurableSagas(client);}
        /**
         * Opens direct native claim push on this exact captured group and epoch.
         * A reconnect creates a new session rather than retargeting an already running claim.
         * @param prefix finite run identity prefix
         * @param kind JOB or SAGA
         * @param owner host-generated unique worker identity
         * @param leaseMillis finite native claim lease
         * @param deadline original handshake budget
         * @return owned transport session, without ownership of borrowed backend endpoints
         * @throws IOException for revoked ownership or unavailable push capability
         */
        public ExecutionSession subscribe(String prefix,String kind,String owner,long leaseMillis,Instant deadline)throws IOException {
            verify(deadline);return located.endpoint().subscribeExecution(located.epoch(),prefix,kind,owner,leaseMillis,deadline);
        }
        /**
         * Checks current quorum ownership without changing the captured route.
         * @param deadline original verification deadline
         * @throws IOException if group, epoch or phase no longer permits effects
         */
        public void verify(Instant deadline)throws IOException {checkStatus(located.endpoint().status(deadline),partition,located.group(),located.epoch());}
        /**
         * Omits scope, endpoints and credentials.
         * @return safe fixed diagnostic
         */
        @Override public String toString(){return "ManagedExecutionSession[pinned]";}
    }
    private static void checkStatus(PartitionStateMachine.Status status,String partition,String group,long epoch)throws IOException {
        if(!status.partition().equals(partition)||!status.group().equals(group)||status.epoch()!=epoch||epoch<1||!Set.of("ACTIVE","COPYING").contains(status.phase()))throw new IOException("Execution ownership unavailable; outcome UNKNOWN");
    }
    /**
     * Omits scope, endpoint inventory and credentials.
     * @return safe fixed diagnostic
     */
    @Override public String toString(){return "ManagedExecutionBackend[host-owned]";}
}
