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

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcDataSqlTest {
    @ParameterizedTest @ValueSource(strings={"SELECT COUNT(*) FROM t", "UPDATE t SET value=? WHERE id=?", "INSERT INTO t(id,value) VALUES(?,?)", "MERGE INTO t(id,value) KEY(id) VALUES(?,?)", "DELETE FROM t WHERE id IN(?,?)", "SELECT 'CALL;COMMIT--', COALESCE(?, 'a') FROM t", "INSERT INTO \"schema\".\"table\"(\"CALL\") VALUES(?)"})
    void supportedDataSqlDoesNotConfuseQuotedBusinessTextWithControl(String sql) { assertDoesNotThrow(() -> JdbcDataSql.validate(sql)); }
    @ParameterizedTest @ValueSource(strings={"COMMIT", "ROLLBACK", "SET AUTOCOMMIT TRUE", "CREATE TABLE t(id INT)", "SELECT /*comment*/ 1", "SELECT 1--comment", "SELECT 1; COMMIT", "CALL dangerous()", "{call dangerous()}", "SELECT dangerous()", "SELECT \"ABS\"(1)", "SELECT schema.ABS(1)", "SELECT NEXT VALUE FOR sequence", "SELECT * INTO other FROM t", "SELECT 'unterminated", "WITH c AS(SELECT 1) SELECT * FROM c"})
    void unsupportedSqlAndFunctionEscapesRejectBeforeDriver(String sql) { assertThrows(SQLFeatureNotSupportedException.class, () -> JdbcDataSql.validate(sql)); }
    @Test void exactCharacterAndTokenLimitsRejectOverflowInsteadOfTruncatingSql() {
        assertDoesNotThrow(() -> JdbcDataSql.validate("SELECT '" + "x".repeat(16375) + "'"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> JdbcDataSql.validate("SELECT '" + "x".repeat(16376) + "'"));
        assertDoesNotThrow(() -> JdbcDataSql.validate("SELECT " + "1,".repeat(2047) + "1"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> JdbcDataSql.validate("SELECT " + "1,".repeat(2048) + "1"));
    }
}
