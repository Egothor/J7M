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

import java.sql.*;

/**
 * Durable current-format destination binding in reserved existing-table rows, never business data.
 */
final class OutboxDefinition {
    private static final String QUOTA_SCOPE = "outbox-definition-quota", QUOTA_ID = "definitions";
    private static final int MAX_DEFINITIONS = 256;
    private OutboxDefinition() { }
    static void ensure(Connection connection, OutboxRelayProfile profile) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Outbox definition needs an owned transaction");
        String scope = "outbox-definition-" + DeliveryIdentities.digest(profile.publication().scope());
        String hash = DeliveryIdentities.digest("outbox-definition-v1", profile.publication().scope(), profile.destination().partitionId(), profile.destination().scopeId(), profile.queue());
        try (var query = connection.prepareStatement("SELECT content_hash,sent,OCTET_LENGTH(payload),aggregate_id,sequence_no,created_at,retain_until FROM j7m_outbox WHERE scope=? AND message_id=?")) {
            query.setString(1, scope); query.setString(2, "definition");
            try (var row = query.executeQuery()) {
                if (row.next()) {
                    row.getLong(3); boolean noPayload = row.wasNull();
                    if (!hash.equals(row.getString(1)) || row.getInt(2) != 1 || !noPayload || !row.getString(4).equals("definition")
                            || row.getLong(5) != 1 || row.getLong(6) != 0 || row.getLong(7) != Long.MAX_VALUE) throw new SQLException("Outbox definition conflict", "23505");
                    return;
                }
            }
        }
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM j7m_outbox WHERE scope=?")) {
            query.setString(1, profile.publication().scope());
            try (var row = query.executeQuery()) { if (!row.next() || row.getLong(1) != 0) throw new SQLException("Outbox definition missing for retained business rows"); }
        }
        Long count = null;
        try (var lock = connection.prepareStatement("SELECT next_sequence FROM j7m_outbox_aggregate WHERE scope=? AND aggregate_id=? FOR UPDATE")) {
            lock.setString(1, QUOTA_SCOPE); lock.setString(2, QUOTA_ID);
            try (var row = lock.executeQuery()) { if (row.next()) count = row.getLong(1); }
        }
        if (count == null) {
            try (var insert = connection.prepareStatement("INSERT INTO j7m_outbox_aggregate(scope,aggregate_id,next_sequence) VALUES(?,?,?)")) {
                insert.setString(1, QUOTA_SCOPE); insert.setString(2, QUOTA_ID); insert.setLong(3, 0); insert.executeUpdate(); count = 0L;
            }
        }
        long actual;
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM j7m_outbox WHERE scope LIKE ?")) {
            query.setString(1, "outbox-definition-%");
            try (var row = query.executeQuery()) { if (!row.next()) throw new SQLException("Missing outbox quota result"); actual = row.getLong(1); }
        }
        if (count != actual || count < 0 || count >= MAX_DEFINITIONS) throw new SQLException("Outbox definition quota/corruption");
        try (var insert = connection.prepareStatement("INSERT INTO j7m_outbox(scope,message_id,aggregate_id,sequence_no,content_hash,payload,created_at,retain_until,sent) VALUES(?,?,?,?,?,NULL,?,?,1)")) {
            insert.setString(1, scope); insert.setString(2, "definition"); insert.setString(3, "definition"); insert.setLong(4, 1);
            insert.setString(5, hash); insert.setLong(6, 0); insert.setLong(7, Long.MAX_VALUE); insert.executeUpdate();
        }
        try (var update = connection.prepareStatement("UPDATE j7m_outbox_aggregate SET next_sequence=? WHERE scope=? AND aggregate_id=?")) {
            update.setLong(1, count + 1); update.setString(2, QUOTA_SCOPE); update.setString(3, QUOTA_ID);
            if (update.executeUpdate() != 1) throw new SQLException("Outbox definition quota disappeared");
        }
    }
}
