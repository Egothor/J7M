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

package org.j7m.middleware.cluster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.j7m.middleware.distributed.*;

/**
 * Quorum-persisted, restartable single-flight rolling application controller. Durable progress and
 * hash-linked audit precede/describe every actual node effect. Lease loss stops progress; a new
 * controller resumes the persisted phase using node-side reconciliation. No local timer grants
 * authority. Resource retention capacity must be provisioned; completed operation identities are
 * retained rather than evicted and accidentally reexecuted.
 */
public final class RollingClusterController implements ClusterLifecycle,AutoCloseable {
    private static final String ACTIVITY="cluster-lifecycle",CURRENT="cluster/current";
    private final ResourceEndpoint resources;
    private final org.j7m.middleware.integrations.Observability telemetry;
    private final ReplicatedCoordination coordination;
    private final Map<String,ClusterNodeEndpoint> nodes;
    private final Map<String,ReleaseDescriptor> catalog;
    private final String owner="controller-"+UUID.randomUUID();
    private final ScheduledExecutorService worker;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong reconciliationFailures=new java.util.concurrent.atomic.AtomicLong();
    private ReplicatedResources.Lease lease;
    private static final class State {
        long version;String operation="",fingerprint="",plan="",release="",state="IDLE",error="";
        List<String> targets=List.of();int position;String phase="DRAIN";List<AuditEvent> audit=new ArrayList<>();
    }
    /**
     * Creates a controller and starts bounded background reconciliation.
     * @param resources authorized shared resource scope with healthy time maintenance
     * @param nodes explicit authenticated node endpoints
     * @param catalog immutable operator-trusted compatibility catalog
     */
    public RollingClusterController(ResourceEndpoint resources,Map<String,ClusterNodeEndpoint> nodes,
            Map<String,ReleaseDescriptor> catalog) {
        this(resources,nodes,catalog,null);
    }
    /**
     * Creates a controller with concrete bounded D15 tracing of actual lifecycle reconciliation.
     * @param resources authorized shared scope
     * @param nodes authenticated physical node endpoints
     * @param catalog operator-trusted releases
     * @param telemetry optional bounded exporter; collector failure cannot authorize or block effects
     */
    public RollingClusterController(ResourceEndpoint resources,Map<String,ClusterNodeEndpoint> nodes,
            Map<String,ReleaseDescriptor> catalog,org.j7m.middleware.integrations.Observability telemetry) {
        this.telemetry=telemetry;
        if(nodes.isEmpty()||nodes.size()>16)throw new IllegalArgumentException("Fleet size");
        this.resources=Objects.requireNonNull(resources);coordination=new ReplicatedCoordination(resources);
        this.nodes=Map.copyOf(nodes);this.catalog=Map.copyOf(catalog);
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"j7m-rolling-controller");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(this::reconcileQuietly,50,100,TimeUnit.MILLISECONDS);
    }
    /**
     * {@inheritDoc}
     */
    @Override public ClusterView inspect(Instant deadline) throws IOException {
        State state=load(CURRENT,deadline);Map<String,NodeView> result=new TreeMap<>();
        for(var entry:nodes.entrySet())try{result.put(entry.getKey(),entry.getValue().status(deadline).node());}
        catch(IOException unavailable){result.put(entry.getKey(),new NodeView(entry.getKey(),"",false,true,0,0));}
        return new ClusterView(state.version,result,state.state.equals("RUNNING")||state.state.equals("HOLD")?state.operation:"");
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized DeploymentPlan plan(String operationId,long expectedVersion,String releaseId,
            List<String> nodeIds,Instant deadline) throws IOException {
        identifier(operationId);validateTargets(nodeIds);if(!catalog.containsKey(releaseId))throw new IOException("Unknown release");
        String fingerprint=hash("PLAN|"+expectedVersion+"|"+releaseId+"|"+String.join(",",nodeIds));
        String key="cluster/plans/"+operationId;var old=resources.read(key,deadline);
        if(old!=null){DeploymentPlan prior=decodePlan(old.value());if(!prior.fingerprint().equals(fingerprint))throw new IOException("CONFLICT: operation identity");return prior;}
        State state=load(CURRENT,deadline);if(state.version!=expectedVersion)throw new IOException("CONFLICT: cluster version");
        validateCompatibility(releaseId,nodeIds,deadline);capacity(nodeIds.getFirst(),deadline);
        DeploymentPlan plan=new DeploymentPlan(operationId,expectedVersion,releaseId,nodeIds,fingerprint);
        var result=resources.execute("BATCH","plan-"+operationId,List.of(key,"0",encodePlan(plan)),deadline);
        if(!result.status().equals("OK"))throw new IOException("CONFLICT: plan admission "+result.status());return plan;
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized OperationStatus apply(String operationId,long expectedVersion,String planId,
            Instant deadline) throws IOException {
        var value=resources.read("cluster/plans/"+planId,deadline);if(value==null)throw new IOException("Unknown plan");
        DeploymentPlan plan=decodePlan(value.value());
        if(plan.expectedVersion()!=expectedVersion)throw new IOException("CONFLICT: plan version");
        return start(operationId,expectedVersion,planId,plan.releaseId(),plan.nodeIds(),false,deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized OperationStatus rollback(String operationId,long expectedVersion,String releaseId,
            List<String> nodeIds,Instant deadline) throws IOException {
        return start(operationId,expectedVersion,"rollback-"+operationId,releaseId,nodeIds,false,deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized OperationStatus drain(String operationId,long expectedVersion,String nodeId,
            Instant deadline) throws IOException {
        String release=nodes.get(nodeId).status(deadline).node().releaseId();
        return start(operationId,expectedVersion,"drain-"+operationId,release,List.of(nodeId),true,deadline);
    }
    private OperationStatus start(String operation,long expected,String plan,String release,List<String> targets,
            boolean drainOnly,Instant deadline) throws IOException {
        identifier(operation);validateTargets(targets);
        String fingerprint=hash(expected+"|"+plan+"|"+release+"|"+String.join(",",targets)+"|"+drainOnly);
        State archived=load("cluster/operations/"+operation,deadline);
        if(!archived.operation.isEmpty())return replay(archived,fingerprint);
        authorize(deadline);State state=load(CURRENT,deadline);
        if(state.operation.equals(operation))return replay(state,fingerprint);
        if(state.version!=expected)throw new IOException("CONFLICT: cluster version");
        if(state.state.equals("RUNNING")||state.state.equals("HOLD"))throw new IOException("CONFLICT: active operation");
        validateCompatibility(release,targets,deadline);capacity(targets.getFirst(),deadline);
        if(!state.operation.isEmpty())save("cluster/operations/"+state.operation,state,deadline);
        State next=new State();next.version=state.version;next.operation=operation;next.fingerprint=fingerprint;
        next.plan=plan;next.release=release;next.targets=List.copyOf(targets);next.state="RUNNING";
        next.phase=drainOnly?"DRAIN_ONLY":"DRAIN";audit(next,"ADMITTED","");save(CURRENT,next,deadline);
        return status(next);
    }
    private OperationStatus replay(State state,String fingerprint)throws IOException {
        if(!state.fingerprint.equals(fingerprint))throw new IOException("CONFLICT: operation identity");return status(state);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized OperationStatus operationStatus(String operation,Instant deadline)throws IOException {
        State current=load(CURRENT,deadline);if(current.operation.equals(operation))return status(current);
        State archived=load("cluster/operations/"+operation,deadline);
        if(archived.operation.isEmpty())throw new IOException("Unknown operation");return status(archived);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized List<AuditEvent> audit(String operation,Instant deadline)throws IOException {
        State current=load(CURRENT,deadline);if(current.operation.equals(operation))return List.copyOf(current.audit);
        State archived=load("cluster/operations/"+operation,deadline);return List.copyOf(archived.audit);
    }
    private void reconcileQuietly(){try{reconcile(Instant.now().plusSeconds(4));}catch(IOException|RuntimeException unavailable){reconciliationFailures.incrementAndGet();}}
    /**
     * Advances at most one durable phase. Useful for supervised control loops as well as the built-in worker.
     * @param deadline absolute deadline
     * @throws IOException unavailable authority or physical effect
     */
    public synchronized void reconcile(Instant deadline)throws IOException {
        if(closed)return;State state=load(CURRENT,deadline);
        if(!state.state.equals("RUNNING")&&!state.state.equals("HOLD"))return;
        authorize(deadline);String target=state.targets.get(state.position);
        var span=telemetry==null?null:telemetry.start(org.j7m.middleware.integrations.Observability.Operation.CLUSTER_STEP);
        try {
            if(state.phase.equals("DRAIN")||state.phase.equals("DRAIN_ONLY")) {
                capacity(target,deadline);validateCompatibility(state.release,List.of(target),deadline);
                nodes.get(target).step(state.operation,"DRAIN",state.release,owner,lease.fence(),lease.leadershipEpoch(),deadline);
                if(state.phase.equals("DRAIN_ONLY")){state.position++;state.phase="DRAIN_ONLY";}
                else state.phase="REPLACE";
            }else if(state.phase.equals("REPLACE")) {
                nodes.get(target).step(state.operation,"REPLACE",state.release,owner,lease.fence(),lease.leadershipEpoch(),deadline);
                state.phase="VERIFY";
            }else {
                NodeView actual=nodes.get(target).status(deadline).node();
                if(!actual.ready()||!actual.releaseId().equals(state.release))throw new IOException("Host verification unavailable");
                state.position++;state.phase="DRAIN";
            }
            state.state=state.position==state.targets.size()?"COMPLETED":"RUNNING";state.error="";
            audit(state,state.state.equals("COMPLETED")?"COMPLETED":state.phase,target);save(CURRENT,state,deadline);
        }catch(IOException effectFailure) {
            if(span!=null)span.failed();
            if(!state.state.equals("HOLD")){state.state="HOLD";state.error=effectFailure.getMessage()!=null&&effectFailure.getMessage().startsWith("UNKNOWN")?"UNKNOWN_EFFECT":"UNAVAILABLE";
                audit(state,"HOLD",target);save(CURRENT,state,deadline);}
            throw effectFailure;
        } finally {if(span!=null)span.close();}
    }
    private void authorize(Instant deadline)throws IOException {
        var live=coordination.leader(ACTIVITY,deadline);
        if(live==null){coordination.lead("lead-"+UUID.randomUUID(),ACTIVITY,owner,15000,deadline);live=coordination.leader(ACTIVITY,deadline);}
        if(live==null||!live.owner().equals(owner))throw new IOException("Lifecycle controller not leader");
        // Renewal generates a new fence; every effect gets the fresh lease, never a cached grant.
        if(live.expiresAt()-System.currentTimeMillis()<5000)resources.execute("RENEW","renew-"+UUID.randomUUID(),List.of("leadership/"+ACTIVITY,
                Long.toString(live.version()),owner,"15000","0"),deadline);
        lease=coordination.leader(ACTIVITY,deadline);
        if(lease==null||!lease.owner().equals(owner))throw new IOException("Lifecycle lease lost");
    }
    private void capacity(String target,Instant deadline)throws IOException {
        int available=0;int voters=0;long epoch=-1;
        for(var entry:nodes.entrySet()) {
            ClusterNodeEndpoint.Status state;
            try { state=entry.getValue().status(deadline); }
            catch(IOException unavailable){if(entry.getKey().equals(target))throw unavailable;continue;}
            if(state.joint()||!state.quorumAvailable())throw new IOException("Quorum reconfiguration in progress");
            if(epoch!=-1&&epoch!=state.configurationEpoch())throw new IOException("Configuration epochs differ");
            epoch=state.configurationEpoch();voters=Math.max(voters,state.votingMembers());
            if(!entry.getKey().equals(target)&&state.node().ready())available++;
        }
        if(voters<1||available<voters/2+1)throw new IOException("Insufficient quorum-safe hosting capacity");
    }
    private void validateCompatibility(String release,List<String> targets,Instant deadline)throws IOException {
        ReleaseDescriptor desired=catalog.get(release);if(desired==null)throw new IOException("Unknown trusted release");
        for(String target:targets){var actual=nodes.get(target).status(deadline);var current=catalog.get(actual.node().releaseId());
            if(current==null||!desired.compatibleWith(current,actual.dataSchema()))throw new IOException("Incompatible protocol/schema release");}
    }
    private void validateTargets(List<String> targets){if(targets.isEmpty()||targets.size()>16||new HashSet<>(targets).size()!=targets.size()||!nodes.keySet().containsAll(targets))throw new IllegalArgumentException("Target nodes");}
    private static void identifier(String identity){if(identity==null||!identity.matches("[A-Za-z0-9_.-]{1,96}"))throw new IllegalArgumentException("Operation identity");}
    private State load(String key,Instant deadline)throws IOException {var value=resources.read(key,deadline);return value==null?new State():decode(value.value());}
    private void save(String key,State state,Instant deadline)throws IOException {
        var result=coordination.mutate("write-"+UUID.randomUUID(),ACTIVITY,lease.fence(),lease.leadershipEpoch(),key,encode(state),deadline);
        if(!result.status().equals("OK"))throw new IOException("Lifecycle fenced persistence "+result.status());
    }
    private static OperationStatus status(State state){return new OperationStatus(state.operation,state.state,state.version,state.plan,
            state.position<state.targets.size()?state.targets.get(state.position):"",state.phase,state.error);}
    private static void audit(State state,String phase,String node){state.version++;String previous=state.audit.isEmpty()?"":state.audit.getLast().hash();
        String digest=hash(previous+"|"+state.version+"|"+state.operation+"|"+node+"|"+phase);
        state.audit.add(new AuditEvent(state.version,state.operation,node,phase,previous,digest));}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String encodePlan(DeploymentPlan plan)throws IOException {return encoded(out->{out.writeUTF(plan.planId());out.writeLong(plan.expectedVersion());out.writeUTF(plan.releaseId());writeList(out,plan.nodeIds());out.writeUTF(plan.fingerprint());});}
    private static DeploymentPlan decodePlan(String value)throws IOException {try(var in=input(value)){var plan=new DeploymentPlan(in.readUTF(),in.readLong(),in.readUTF(),readList(in),in.readUTF());end(in);return plan;}}
    private static String encode(State state)throws IOException {return encoded(out->{out.writeInt(1);out.writeLong(state.version);out.writeUTF(state.operation);out.writeUTF(state.fingerprint);out.writeUTF(state.plan);out.writeUTF(state.release);out.writeUTF(state.state);out.writeUTF(state.error);writeList(out,state.targets);out.writeInt(state.position);out.writeUTF(state.phase);out.writeInt(state.audit.size());for(var a:state.audit){out.writeLong(a.version());out.writeUTF(a.nodeId());out.writeUTF(a.phase());out.writeUTF(a.previousHash());out.writeUTF(a.hash());}});}
    private static State decode(String value)throws IOException {try(var in=input(value)){if(in.readInt()!=1)throw new IOException("Controller schema");State s=new State();s.version=in.readLong();s.operation=in.readUTF();s.fingerprint=in.readUTF();s.plan=in.readUTF();s.release=in.readUTF();s.state=in.readUTF();s.error=in.readUTF();s.targets=readList(in);s.position=in.readInt();s.phase=in.readUTF();int count=in.readInt();if(count<0||count>100)throw new IOException("Audit capacity");for(int i=0;i<count;i++)s.audit.add(new AuditEvent(in.readLong(),s.operation,in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF()));end(in);return s;}}
    @FunctionalInterface private interface Writer{void write(DataOutputStream out)throws IOException;}
    private static String encoded(Writer writer)throws IOException {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){writer.write(out);}String value=Base64.getEncoder().encodeToString(bytes.toByteArray());if(value.length()>32768)throw new IOException("Controller record capacity");return value;}
    private static DataInputStream input(String value)throws IOException {try{return new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)));}catch(IllegalArgumentException bad){throw new IOException("Controller encoding",bad);}}
    private static void end(DataInputStream in)throws IOException {if(in.read()!=-1)throw new IOException("Trailing controller data");}
    private static void writeList(DataOutputStream out,List<String> values)throws IOException {out.writeInt(values.size());for(String value:values)out.writeUTF(value);}
    private static List<String> readList(DataInputStream in)throws IOException {int count=in.readInt();if(count<0||count>16)throw new IOException("Node bounds");List<String> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(in.readUTF());return List.copyOf(values);}
    /**
     * Failed reconciliation attempts; durable progress remains available through operationStatus.
     * @return monotonic local failure count
     */
    public long reconciliationFailures(){return reconciliationFailures.get();}
    /**
     * {@inheritDoc}
     */
    @Override public void close(){closed=true;worker.shutdownNow();}
}
