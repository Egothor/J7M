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

import java.sql.SQLFeatureNotSupportedException;
import java.util.*;

/**
 * Conservative H2 data-SQL subset, not a parser or sandbox for arbitrary vendor SQL.
 */
final class JdbcDataSql {
    private record Token(String text, boolean quoted) { }
    private static final Set<String> START = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");
    private static final Set<String> FORBIDDEN = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "COMMIT", "ROLLBACK", "BEGIN", "END",
            "SAVEPOINT", "RELEASE", "CALL", "EXEC", "EXECUTE", "PREPARE", "DEALLOCATE", "RUNSCRIPT", "SCRIPT", "SHUTDOWN",
            "GRANT", "REVOKE", "BACKUP", "VACUUM", "ANALYZE", "NEXT", "NEXTVAL", "CURRVAL");
    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG", "COALESCE", "UPPER", "LOWER", "LENGTH",
            "OCTET_LENGTH", "ABS", "ROUND", "CAST");
    private static final Set<String> GROUPS = Set.of("IN", "EXISTS", "VALUES", "KEY", "AS", "SELECT", "WHERE", "AND", "OR", "NOT", "ON", "WHEN", "THEN", "ELSE", "FROM", "JOIN");
    private static final Set<String> TYPES = Set.of("VARCHAR", "CHAR", "DECIMAL", "NUMERIC", "TIMESTAMP", "TIME", "BINARY", "VARBINARY");
    private JdbcDataSql() { }
    static void validate(String sql) throws SQLFeatureNotSupportedException {
        if (sql == null || sql.isBlank() || sql.length() > 16384) throw rejected();
        List<Token> tokens = tokenize(sql);
        if (tokens.isEmpty() || tokens.getFirst().quoted || !START.contains(tokens.getFirst().text)) throw rejected();
        String start = tokens.getFirst().text;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.quoted && (FORBIDDEN.contains(token.text) || start.equals("SELECT") && token.text.equals("INTO"))) throw rejected();
            if (!token.text.equals("(") || i == 0) continue;
            Token before = tokens.get(i - 1);
            if (!identifier(before)) continue;
            if (tableColumns(tokens, i - 1, start)) continue;
            if (before.quoted || i >= 2 && tokens.get(i - 2).text.equals(".")
                    || !(FUNCTIONS.contains(before.text) || GROUPS.contains(before.text) || TYPES.contains(before.text))) throw rejected();
        }
    }
    private static boolean tableColumns(List<Token> tokens, int table, String start) {
        if (!(start.equals("INSERT") || start.equals("MERGE"))) return false;
        int first = table;
        while (first >= 2 && tokens.get(first - 1).text.equals(".") && identifier(tokens.get(first - 2))) first -= 2;
        return first > 0 && !tokens.get(first - 1).quoted && tokens.get(first - 1).text.equals("INTO");
    }
    private static boolean identifier(Token token) { return token.quoted || !token.text.isEmpty() && (Character.isLetter(token.text.charAt(0)) || token.text.charAt(0) == '_'); }
    private static List<Token> tokenize(String sql) throws SQLFeatureNotSupportedException {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == 0 || c == ';' || c == '{' || c == '}' || c == '`'
                    || i + 1 < sql.length() && (sql.startsWith("--", i) || sql.startsWith("/*", i) || sql.startsWith("*/", i))) throw rejected();
            if (c == '\'' || c == '"') {
                char quote = c; int begin = ++i; boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw rejected();
                tokens.add(new Token(quote == '"' ? sql.substring(begin, i - 1) : "$LITERAL", quote == '"'));
                if (tokens.size() > 4096) throw rejected();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int begin = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                tokens.add(new Token(sql.substring(begin, i).toUpperCase(Locale.ROOT), false));
            } else { tokens.add(new Token(Character.toString(c), false)); i++; }
            if (tokens.size() > 4096) throw rejected();
        }
        return tokens;
    }
    static SQLFeatureNotSupportedException rejected() { return new SQLFeatureNotSupportedException("SQL operation outside managed H2 data subset", "0A000"); }
}
