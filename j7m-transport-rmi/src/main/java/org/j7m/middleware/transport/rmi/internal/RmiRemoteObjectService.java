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

package org.j7m.middleware.transport.rmi.internal;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Native RMI remote service for the generic middleware object protocol.
 *
 * <p>This interface is transport-internal and is intentionally not exported
 * from the RMI transport module. Every remote method accepts and returns only
 * a {@code byte[]} envelope. Middleware objects, encoded values, references,
 * callbacks, collections, proxies, exceptions, and application values are
 * encoded by the J7M RMI wire codec before the native RMI boundary.</p>
 *
 * <p>Implementations are thread-safe according to the owning
 * {@code RmiTransportProvider} lifecycle. Callers must treat all byte arrays as
 * untrusted transport input.</p>
 */
public interface RmiRemoteObjectService extends Remote {
    /**
     * Invokes a method on a concrete exported object.
     *
     * @param request encoded invocation request
     * @return encoded invocation response
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] invoke(byte[] request) throws RemoteException;

    /**
     * Opens and validates a target reference.
     *
     * @param request encoded open request
     * @return encoded open response
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] open(byte[] request) throws RemoteException;

    /**
     * Describes a target reference.
     *
     * @param request encoded describe request
     * @return encoded describe response
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] describe(byte[] request) throws RemoteException;

    /**
     * Returns server-stream chunks for a target reference.
     *
     * @param request encoded stream request
     * @return encoded stream chunks
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] serverStream(byte[] request) throws RemoteException;

    /**
     * Consumes client-stream chunks and returns an aggregate response.
     *
     * @param requests encoded stream requests
     * @return encoded aggregate response
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] clientStream(byte[] requests) throws RemoteException;

    /**
     * Processes bidirectional stream chunks through the legacy request/response
     * fallback.
     *
     * @param requests encoded stream requests
     * @return encoded response chunks
     * @throws RemoteException when the RMI transport fails before the J7M
     *         envelope is decoded
     */
    byte[] bidirectionalStream(byte[] requests) throws RemoteException;
}
