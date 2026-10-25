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

import java.util.List;
import java.util.Objects;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.api.ValueCodec;

/**
 * Deterministic version-one data-only frames for durable invocation fingerprints and replay.
 * The borrowed codec and schema functions must be thread-safe and free of external effects.
 * References, callbacks, exceptions and control capabilities are rejected. The shared internal
 * engine also serves partition state, but this public facade always enforces the native12KiB cap.
 */
public final class ManagedPayloadCodec {
    /**
     * Largest binary frame whose base64 representation fits the native16KiB result field.
     */
    public static final int MAX_FRAME_BYTES = 12288;
    private final PayloadFrameEngine engine;
    private final SchemaRegistry schemas;
    private final ValueCodec baseCodec;
    private final int maximumBytes;

    /**
     * Creates a data frame codec with the native maximum frame size.
     * @param codec borrowed thread-safe value codec; its schema functions must have no external effects
     * @param schemas explicit allowlist, including any supported previous wire versions
     * @throws NullPointerException if a collaborator is null
     */
    public ManagedPayloadCodec(ValueCodec codec, SchemaRegistry schemas) {
        this(codec, schemas, MAX_FRAME_BYTES);
    }

    /**
     * Creates a codec with a stricter host byte limit; all frames include their format headers.
     * UTF16 code units are length-framed, preserving Java strings without lossy surrogate replacement.
     * @param codec borrowed trusted codec
     * @param schemas explicit schema allowlist
     * @param maximumBytes positive frame cap, at most12288 bytes
     * @throws NullPointerException if a collaborator is null
     * @throws IllegalArgumentException if the frame cap is invalid
     */
    public ManagedPayloadCodec(ValueCodec codec, SchemaRegistry schemas, int maximumBytes) {
        this.baseCodec=Objects.requireNonNull(codec); this.schemas=Objects.requireNonNull(schemas);this.maximumBytes=maximumBytes;
        if (maximumBytes < 1 || maximumBytes > MAX_FRAME_BYTES) throw new IllegalArgumentException("Invalid durable frame limit");
        this.engine = new PayloadFrameEngine(codec, schemas, maximumBytes);
    }

    ManagedPayloadCodec forDefinition(Class<?> actual,List<Class<?>> declaredTypes) {
        var generated=GeneratedDataSchemas.registry(actual);var selected=new java.util.ArrayList<org.j7m.middleware.codec.api.SchemaDescriptor<?>>();
        var pending=new java.util.ArrayDeque<>(declaredTypes);var visited=new java.util.HashSet<Class<?>>();boolean owned=false;
        while(!pending.isEmpty()){
            var type=pending.removeFirst();if(!visited.add(type))continue;if(visited.size()>4096)throw new IllegalArgumentException("Generated data type capacity");
            var descriptor=generated.forType(type);if(descriptor!=null)owned=true;else descriptor=schemas.forType(type);
            if(descriptor!=null){selected.add(descriptor);for(var field:descriptor.fields())pending.add(field.type());}
        }
        if(!owned){validateGeneratedDataTypes(declaredTypes);return this;}
        var combined=new SchemaRegistry(selected);SchemaDataContracts.validate(declaredTypes,combined,true);
        var appCodec=new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(combined);
        ValueCodec scoped=new ValueCodec(){
            @Override public org.j7m.middleware.codec.api.EncodedValue encode(Object value,Class<?> type,org.j7m.middleware.codec.api.CodecContext context){return (generated.forType(type)==null?baseCodec:appCodec).encode(value,type,context);}
            @Override public Object decode(org.j7m.middleware.codec.api.EncodedValue value,Class<?> type,org.j7m.middleware.codec.api.CodecContext context){return (generated.forType(type)==null?baseCodec:appCodec).decode(value,type,context);}
        };
        return new ManagedPayloadCodec(scoped,combined,maximumBytes);
    }

    void validateGeneratedDataTypes(List<Class<?>> types) { SchemaDataContracts.validate(types,schemas,true); }

    /**
     * Encodes an explicitly declared data value; maps and metadata use sorted string keys.
     * @param value nullable data value
     * @param type explicit declared Java data type, never a frame-provided class name
     * @return owned version-one frame, bounded by the configured cap
     * @throws MiddlewareException with CODEC_ERROR for unsupported types, invalid schemas or bounds
     */
    public byte[] encode(Object value, Class<?> type) { return engine.encode(value, type); }

    /**
     * Fully validates a frame and its schema allowlist before invoking the borrowed value decoder.
     * @param frame complete version-one frame; trailing bytes are rejected
     * @param type explicitly trusted expected Java data type
     * @return decoded value, possibly null for a nullable/void result
     * @throws MiddlewareException with CODEC_ERROR for corruption, unsupported data or incompatible types
     */
    public Object decode(byte[] frame, Class<?> type) { return engine.decode(frame, type); }

    /**
     * Fingerprints one complete method's ordered business inputs using the same canonical framing.
     * No target, trace carrier, deadline, physical attempt or principal is added to the input hash;
     * authenticated tenant/principal identity belongs to the separate durable key scope.
     * @param contract stable service ID/version and full erased method identity
     * @param types explicit parameter classes matching the method signature
     * @param arguments ordered values, including a declared business key parameter when present
     * @return lowercase SHA-256 digest of version-one canonical business input bytes
     * @throws MiddlewareException with CODEC_ERROR for a signature mismatch or unsupported/bounded input
     */
    public String inputHash(ManagedServiceContract contract, List<Class<?>> types, Object[] arguments) {
        return engine.inputHash(contract, types, arguments);
    }

    void validateDeclaredType(Class<?> type) { engine.validateDeclaredType(type); }
}
