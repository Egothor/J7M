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
import java.util.ServiceLoader;
import org.j7m.middleware.codec.api.SchemaDescriptor;
import org.j7m.middleware.codec.api.SchemaIndex;
import org.j7m.middleware.codec.api.SchemaIndexes;
import org.j7m.middleware.codec.api.SchemaRegistry;

/**
 * Bounded actual-definition schema loading, excluding parent/global providers before creation.
 */
final class GeneratedDataSchemas {
    private GeneratedDataSchemas(){ }
    private record Snapshot(int formatVersion,List<SchemaDescriptor<?>> schemas) implements SchemaIndex { }
    static SchemaRegistry registry(Class<?> actual){
        var indexes=new ArrayList<SchemaIndex>();var layer=actual.getModule().getLayer();
        var source=layer==null?ServiceLoader.load(SchemaIndex.class,actual.getClassLoader()):ServiceLoader.load(layer,SchemaIndex.class);
        var providers=source.stream().iterator();int inspected=0;
        while(providers.hasNext()){
            if(++inspected>1024)throw new IllegalArgumentException("Generated schema provider capacity");var provider=providers.next();
            if(provider.type().getClassLoader()!=actual.getClassLoader())continue;
            if(indexes.size()>=256)throw new IllegalArgumentException("Generated schema provider capacity");
            var index=provider.get();int version=index.formatVersion();var descriptors=List.copyOf(index.schemas());
            if(descriptors.size()>128)throw new IllegalArgumentException("Generated schema descriptor capacity");indexes.add(new Snapshot(version,descriptors));
        }
        return SchemaIndexes.registry(indexes);
    }
}
