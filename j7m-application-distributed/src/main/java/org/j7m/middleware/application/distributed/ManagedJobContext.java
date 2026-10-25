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

import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.distributed.ReplicatedResources;

/**
 * Actual current job claim capability, supplied only through an explicit provider host binding.
 * Checkpoint/progress are explicit safe points on the current POJO. Protected effects are staged
 * and commit atomically with native completion; arbitrary external I/O is not made exactly once.
 * Retaining this object never retargets its run, owner, fence or pinned partition epoch.
 */
public final class ManagedJobContext {
    interface Access {
        boolean valid();
        void checkpoint(boolean progressOnly);
        ReplicatedResources.Value read(String key);
        String key(String key);
    }
    private final String logical;
    private final long fence,epoch;
    private final InvocationControl control;
    private final Access access;
    private final List<String> effects=new ArrayList<>();
    private int effectBytes;
    private volatile boolean revoked;
    ManagedJobContext(String logical,long fence,long epoch,InvocationControl control,Access access){this.logical=logical;this.fence=fence;this.epoch=epoch;this.control=control;this.access=access;}
    private void active(){
        boolean current=false;
        if(ManagedInvocationLifetime.currentUserWorkOpen())try{current=ManagedInvocationLifetime.currentContext(ManagedJobContext.class)==this;}catch(IllegalStateException absent){current=false;}
        if(!current||revoked||!access.valid()||control.cancellationRequested()||control.remainingBudget().isZero())throw MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN,"Job ownership is unavailable");
    }
    /**
     * Returns the original stable logical occurrence identity.
     * @return current job identity
     * @throws MiddlewareException outside this live owned USER invocation
     */
    public String jobId(){active();return logical;}
    /**
     * Returns this claim's native fence, never a general database lock token.
     * @return captured live ownership fence
     * @throws MiddlewareException outside this live owned USER invocation
     */
    public long fence(){active();return fence;}
    /**
     * Returns the immutable route epoch captured before this claim.
     * @return pinned partition epoch
     * @throws MiddlewareException outside this live owned USER invocation
     */
    public long partitionEpoch(){active();return epoch;}
    /**
     * Observes cancellation or revocation without acquiring or renewing authority.
     * @return whether work should cooperatively stop
     */
    public boolean cancellationRequested(){return revoked||!access.valid()||control.cancellationRequested()||control.remainingBudget().isZero();}
    /**
     * Calls actual checkpoint/progress hooks and durably saves their bounded data envelope.
     * @throws MiddlewareException after revocation, uncertain native completion or original deadline
     * @throws IllegalArgumentException if no checkpoint pair is declared or its frame exceeds bounds
     */
    public void checkpoint(){active();access.checkpoint(false);}
    /**
     * Calls only the explicit progress hook, retaining the last durable checkpoint unchanged.
     * @throws MiddlewareException after revocation or uncertain native completion
     * @throws IllegalArgumentException if no progress hook is declared or its frame exceeds bounds
     */
    public void progress(){active();access.checkpoint(true);}
    /**
     * Reads the exact same native protected map used by this claim's final staged effects.
     * @param key logical identifier,1..128 characters
     * @return immutable value/version, or null when absent
     * @throws MiddlewareException after revocation, unavailable quorum or original deadline
     */
    public ReplicatedResources.Value read(String key){active();return access.read(key);}
    /**
     * Stages a protected compare-and-set, without performing an immediate write.
     * Up to64 distinct keys and64KiB encoded key/value data fit one native completion command.
     * @param key logical identifier,1..128 characters
     * @param expectedVersion exact current version, zero for absence
     * @param value textual data, at most4096 UTF16 code units without NUL
     * @throws MiddlewareException outside the original live USER invocation
     * @throws IllegalArgumentException for malformed input, duplicates or capacity
     */
    public synchronized void stageEffect(String key,long expectedVersion,String value){
        active();String target=access.key(key);
        if(expectedVersion<0||value==null||value.length()>4096||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid job effect");
        for(int i=0;i<effects.size();i+=3)if(effects.get(i).equals(target))throw new IllegalArgumentException("Duplicate job effect");
        int bytes=target.length()+Long.toString(expectedVersion).length()+6;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);bytes+=c>=1&&c<=127?1:c<=2047?2:3;}
        if(effects.size()>=192||effectBytes+bytes>65536)throw new IllegalArgumentException("Job effect capacity");
        effects.addAll(List.of(target,Long.toString(expectedVersion),value));effectBytes+=bytes;
    }
    synchronized List<String> effects(){return List.copyOf(effects);}
    void revoke(){revoked=true;}
    /**
     * Omits identity, fence and application data from diagnostics.
     * @return fixed revocation diagnostic
     */
    @Override public String toString(){return "ManagedJobContext[revoked="+cancellationRequested()+"]";}
}
