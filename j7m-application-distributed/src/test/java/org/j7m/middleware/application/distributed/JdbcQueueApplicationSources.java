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

import java.util.Map;

/**
 * Public-API-only ordinary application sources shared by signed JPMS delivery qualification.
 */
public final class JdbcQueueApplicationSources {
    private JdbcQueueApplicationSources() { }
    /**
     * Supplies the finite application source family, without any backend credential or host callback.
     * @return source path to ordinary POJO/interface source
     */
    public static Map<String,String> sources() {
        return Map.of("inboxfixture/Publisher.java", """
                package inboxfixture;
                import org.j7m.middleware.annotations.OutboxPublisher;
                public interface Publisher {
                    @OutboxPublisher("sales") long enqueue(String id,String aggregate,String payload);
                }
                """, "inboxfixture/Flow.java", """
                package inboxfixture;
                import java.sql.*;
                import java.util.concurrent.*;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="jdbc-delivery",version="1")
                @J7mComponent public class Flow {
                    private final ManagedProvider<Connection> connection;
                    private final ManagedProvider<Publisher> publisher;
                    @Inject public Flow(@J7mResource("jdbc") ManagedProvider<Connection> connection,
                            @J7mResource("producer") ManagedProvider<Publisher> publisher,
                            @ConfigValue(value="constructor.marker",required=true) String marker) throws java.io.IOException {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(marker),"constructed");
                        this.connection=connection;this.publisher=publisher;
                    }
                    @Async @DurableQueueConsumer(value="inbox-handler",queue="orders") @JdbcTransaction @InboxHandler
                    public CompletionStage<Void> consume(String input) {
                        return J7mContext.currentInvocation().control().submit(()->{
                            String parent=InvocationTraceContext.current().getOrDefault("traceparent","");
                            try(var statement=connection.get().prepareStatement("INSERT INTO app_effect(message_id,parent) VALUES(?,?)")) {
                                statement.setString(1,input);statement.setString(2,parent);statement.executeUpdate();
                            }
                            publisher.get().enqueue(input+":out","orders",input);
                            return null;
                        });
                    }
                }
                """);
    }
    /**
     * Supplies the same signed application with a generated application-owned schema payload.
     * @return ordinary record, publisher and consumer sources
     */
    public static Map<String,String> schemaSources() {
        var sources = new java.util.HashMap<>(sources());
        sources.put("inboxfixture/Publisher.java", sources.get("inboxfixture/Publisher.java").replace("String payload", "Payload payload"));
        sources.put("inboxfixture/Flow.java", sources.get("inboxfixture/Flow.java")
                .replace("consume(String input)", "consume(Payload command)")
                .replace("String parent=", "String input=command.value();String parent=")
                .replace("enqueue(input+\":out\",\"orders\",input)", "enqueue(input+\":out\",\"orders\",command)"));
        sources.put("inboxfixture/Payload.java", """
                package inboxfixture;
                import org.j7m.middleware.annotations.*;
                @SchemaType(id="delivery.Order",version=1) public record Payload(@WireField(id=7) String value) { }
                """);
        sources.put("inboxfixture/Incoming.java", """
                package inboxfixture;
                import org.j7m.middleware.annotations.QueuePublisher;
                public interface Incoming { @QueuePublisher(queue="orders") long publish(String id,Payload value); }
                """);
        return Map.copyOf(sources);
    }
}
