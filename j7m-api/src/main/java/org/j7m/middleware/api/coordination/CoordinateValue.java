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

package org.j7m.middleware.api.coordination;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Bounded opaque coordination metadata value.
 *
 * <p>The value defensively copies payload bytes and redacts them from
 * {@link #toString()}.</p>
 *
 * @param bytes value bytes
 */
public record CoordinateValue(byte[] bytes) implements Serializable {
    /**
     * Maximum public API value size.
     */
    public static final int MAX_BYTES = 16 * 1024;

    /**
     * Creates a bounded coordinate value.
     */
    public CoordinateValue {
        bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("coordinate value is too large");
        }
    }

    /**
     * Returns a defensive copy of the coordinate bytes.
     *
     * @return coordinate value bytes
     */
    @Override
    public byte[] bytes() {
        return Arrays.copyOf(this.bytes, this.bytes.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object other) {
        return other instanceof CoordinateValue that && Arrays.equals(this.bytes, that.bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "CoordinateValue[bytes=redacted,length=" + this.bytes.length + "]";
    }
}
