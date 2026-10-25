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
import java.io.IOException;
import java.time.Instant;
import java.util.List;
/**
 * Versioned, idempotent lifecycle administration. Existing operation retries must retain all original
 * inputs. UNKNOWN never permits blind repetition of an unprotected external effect.
 */
public interface ClusterLifecycle {
    /**
     * Inspects actual authenticated hosts and durable workflow state.
     * @param deadline absolute deadline
     * @return immutable fleet view
     * @throws IOException for unavailable authority
     */
    ClusterView inspect(Instant deadline) throws IOException;
    /**
     * Persists a checked deployment plan without replacing any application.
     * @param operationId stable planning identity
     * @param expectedVersion optimistic fleet generation
     * @param releaseId trusted server catalog release
     * @param nodeIds ordered hosting targets
     * @param deadline absolute deadline
     * @return immutable plan
     * @throws IOException conflict, incompatible release or unavailable authority
     */
    DeploymentPlan plan(String operationId, long expectedVersion, String releaseId, List<String> nodeIds, Instant deadline) throws IOException;
    /**
     * Admits a rolling hosted-application upgrade; progress continues through its durable workflow.
     * @param operationId stable deployment identity
     * @param expectedVersion optimistic generation used by the plan
     * @param planId immutable plan identity
     * @param deadline absolute deadline
     * @return persisted current operation status
     * @throws IOException conflict, incompatible plan or unavailable authority
     */
    OperationStatus apply(String operationId, long expectedVersion, String planId, Instant deadline) throws IOException;
    /**
     * Alias for plan-and-apply using stable derived planning identity.
     * @param operationId stable upgrade identity
     * @param expectedVersion inspected fleet generation
     * @param releaseId trusted release
     * @param nodeIds ordered target hosts
     * @param deadline absolute deadline
     * @return persisted upgrade status
     * @throws IOException conflict, rejected compatibility or unavailable authority
     */
    default OperationStatus upgrade(String operationId, long expectedVersion, String releaseId, List<String> nodeIds, Instant deadline) throws IOException {
        if(operationId==null||!operationId.matches("[A-Za-z0-9_.-]{1,96}"))throw new IllegalArgumentException("Upgrade identity");
        String planningId="upgrade-"+RollingClusterController.hash(operationId);
        DeploymentPlan plan = plan(planningId, expectedVersion, releaseId, nodeIds, deadline);
        return apply(operationId, expectedVersion, plan.planId(), deadline);
    }
    /**
     * Stops new requests and waits for admitted invocations on one safe hosting target.
     * @param operationId stable drain identity
     * @param expectedVersion optimistic fleet generation
     * @param nodeId hosting target
     * @param deadline absolute deadline
     * @return durable drain status
     * @throws IOException conflict, insufficient capacity or unavailable authority
     */
    OperationStatus drain(String operationId, long expectedVersion, String nodeId, Instant deadline) throws IOException;
    /**
     * Plans and applies only a schema-compatible rollback release.
     * @param operationId stable rollback identity
     * @param expectedVersion inspected fleet generation
     * @param releaseId trusted retained release
     * @param nodeIds ordered hosting targets
     * @param deadline absolute deadline
     * @return persisted rollback status
     * @throws IOException incompatible downgrade, conflict or unavailable authority
     */
    OperationStatus rollback(String operationId, long expectedVersion, String releaseId, List<String> nodeIds, Instant deadline) throws IOException;
    /**
     * Reads a durable operation including an earlier lost response.
     * @param operationId admitted identity
     * @param deadline absolute deadline
     * @return persisted status
     * @throws IOException not found or unavailable authority
     */
    OperationStatus operationStatus(String operationId, Instant deadline) throws IOException;
    /**
     * Reads retained hash-linked lifecycle events.
     * @param operationId workflow identity
     * @param deadline absolute deadline
     * @return immutable audit sequence
     * @throws IOException unavailable authority
     * @throws UnsupportedOperationException if this adapter lacks audit access
     */
    default List<AuditEvent> audit(String operationId, Instant deadline) throws IOException { throw new UnsupportedOperationException("Audit capability unavailable"); }
}
