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

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 * Finite standard-JDBC views bound to one serial USER frame; delegates never escape backlinks.
 */
final class GuardedJdbc {
    private final ManagedJdbcTransactionContext context;
    private final Connection physical, connection;
    private final IdentityHashMap<Object, Handle> handles = new IdentityHashMap<>();
    private final List<Handle> ordered = new ArrayList<>();
    private static final Set<String> CONNECTION_READS = Set.of("getAutoCommit", "getTransactionIsolation", "isReadOnly", "getCatalog", "getSchema", "getHoldability", "getNetworkTimeout");
    private static final Set<String> STATEMENT_SETTINGS = Set.of("setQueryTimeout", "setMaxRows", "setLargeMaxRows", "setMaxFieldSize", "setFetchSize", "setFetchDirection");
    GuardedJdbc(ManagedJdbcTransactionContext context, Connection physical) {
        this.context = context; this.physical = physical;
        connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this::connectionCall);
    }
    Connection connection() { return connection; }
    private Object connectionCall(Object self, Method method, Object[] supplied) throws Throwable {
        Object[] arguments = supplied == null ? new Object[0] : supplied; String name = method.getName();
        if (method.getDeclaringClass() == Object.class) return object(self, name, arguments);
        if (name.equals("isClosed")) return context.closed();
        context.user();
        if (name.equals("isWrapperFor")) return false;
        if (name.equals("unwrap")) throw denied();
        if (CONNECTION_READS.contains(name)) return invoke(physical, method, arguments);
        if (name.equals("getWarnings")) return null;
        if (name.equals("clearWarnings")) return null;
        if (name.equals("getMetaData")) return wrap(invoke(physical, method, arguments), null);
        if (name.equals("createStatement") || name.equals("prepareStatement")) {
            capacity(); String sql = null;
            if (name.equals("prepareStatement")) { sql = (String) arguments[0]; sql(sql); }
            creation(name, arguments);
            return wrap(invoke(physical, method, arguments), sql);
        }
        if (name.equals("isValid")) { if ((Integer) arguments[0] < 0) throw denied(); int seconds = Math.max(1, Math.min((Integer) arguments[0], timeout())); return invoke(physical, method, new Object[]{seconds}); }
        if (name.equals("setClientInfo")) { context.failNested(); throw new SQLClientInfoException(); }
        throw denied();
    }
    private void creation(String name, Object[] arguments) throws SQLException {
        int offset = name.equals("prepareStatement") ? 1 : 0;
        if (arguments.length >= offset + 2 && arguments[offset] instanceof Integer type && arguments[offset + 1] instanceof Integer concurrency) {
            if (type != ResultSet.TYPE_FORWARD_ONLY || concurrency != ResultSet.CONCUR_READ_ONLY) throw denied();
            if (arguments.length > offset + 2 && !Objects.equals(arguments[offset + 2], ResultSet.CLOSE_CURSORS_AT_COMMIT)) throw denied();
        }
        if (arguments.length == 2 && name.equals("prepareStatement")) {
            Object option = arguments[1];
            if (option instanceof Integer value && value != Statement.RETURN_GENERATED_KEYS && value != Statement.NO_GENERATED_KEYS) throw denied();
            if (option instanceof int[] indexes && indexes.length > 64 || option instanceof String[] names && names.length > 64) throw denied();
        }
    }
    private Object wrap(Object value, String sql) throws SQLException {
        if (value == null) return null;
        Handle present = handles.get(value); if (present != null) return present.proxy;
        Class<?> type = value instanceof ResultSet ? ResultSet.class : value instanceof PreparedStatement ? PreparedStatement.class
                : value instanceof Statement ? Statement.class : value instanceof DatabaseMetaData ? DatabaseMetaData.class
                : value instanceof ResultSetMetaData ? ResultSetMetaData.class : value instanceof ParameterMetaData ? ParameterMetaData.class : null;
        if (type == null) return detached(value);
        capacity(); var handle = new Handle(value, type, sql);
        handle.proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handle::call);
        handles.put(value, handle); ordered.add(handle); return handle.proxy;
    }
    private void capacity() throws SQLException { if (handles.size() >= 256) throw denied(); }
    private Object detached(Object value) throws SQLException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof byte[] || value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor
                || value instanceof UUID || value.getClass().isEnum()) return value;
        throw denied();
    }
    private final class Handle {
        final Object delegate; final Class<?> type; final String prepared;
        Object proxy; boolean closed; int rows, batch;
        Handle(Object delegate, Class<?> type, String prepared) { this.delegate = delegate; this.type = type; this.prepared = prepared; }
        Object call(Object self, Method method, Object[] supplied) throws Throwable {
            Object[] arguments = supplied == null ? new Object[0] : supplied; String name = method.getName();
            if (method.getDeclaringClass() == Object.class) return object(self, name, arguments);
            if (name.equals("isClosed")) return closed || context.closed();
            if (name.equals("close") && (closed || context.closed())) return null;
            if (name.equals("close")) { context.userCleanup(); close(); return null; }
            context.user(); if (closed) throw ManagedJdbcTransactionContext.unavailable();
            if (name.equals("isWrapperFor")) return false;
            if (name.equals("unwrap")) throw denied();
            if (name.equals("getConnection")) return connection;
            if (name.equals("getWarnings")) return null;
            if (name.equals("clearWarnings")) return null;
            if (type == DatabaseMetaData.class || type == ResultSetMetaData.class || type == ParameterMetaData.class) {
                if (name.equals("getURL") || name.equals("getUserName")) return "managed";
                return result(invoke(delegate, method, arguments));
            }
            if (type == ResultSet.class) {
                if (name.equals("getStatement")) return wrap(invoke(delegate, method, arguments), null);
                if (name.equals("next")) {
                    boolean next = (Boolean) invoke(delegate, method, arguments);
                    if (next && ++rows > 1024) throw denied(); return next;
                }
                if (name.startsWith("get")) {
                    if (name.equals("getObject")) {
                        var rows = (ResultSet) delegate;
                        int column = arguments[0] instanceof Integer index ? index : rows.findColumn((String) arguments[0]);
                        int sqlType = rows.getMetaData().getColumnType(column);
                        if (Set.of(Types.JAVA_OBJECT, Types.OTHER, Types.ARRAY, Types.REF, Types.STRUCT, Types.BLOB, Types.CLOB, Types.NCLOB, Types.SQLXML).contains(sqlType)) throw denied();
                    }
                    if (name.contains("Stream") || name.contains("Reader") || Set.of("getBlob", "getClob", "getNClob", "getSQLXML", "getArray", "getRef", "getRowId").contains(name)) throw denied();
                    return result(invoke(delegate, method, arguments));
                }
                if (name.equals("wasNull") || name.equals("findColumn")) return invoke(delegate, method, arguments);
                throw denied();
            }
            if (name.startsWith("execute") || name.equals("addBatch")) {
                if (arguments.length > 0 && arguments[0] instanceof String statement) sql(statement);
                else if (!name.equals("executeBatch") && !name.equals("executeLargeBatch") && prepared == null) throw denied();
                if (name.equals("addBatch")) { if (++batch > 128) throw denied(); }
                else {
                    try {
                        int configured = ((Statement) delegate).getQueryTimeout(); int bound = timeout();
                        ((Statement) delegate).setQueryTimeout(configured > 0 ? Math.min(configured, bound) : bound);
                    } catch (SQLException failure) { context.failNested(); throw ManagedJdbcTransactionContext.unavailable(); }
                    context.user(); batch = 0;
                }
                Object result = invoke(delegate, method, arguments);
                if (result instanceof int[] || result instanceof long[]) return result;
                return result(result);
            }
            if (name.equals("clearBatch")) { batch = 0; return invoke(delegate, method, arguments); }
            if (STATEMENT_SETTINGS.contains(name)) {
                if (name.equals("setQueryTimeout")) {
                    int requested = ((Number) arguments[0]).intValue(); if (requested < 0) throw denied();
                    return invoke(delegate, method, new Object[]{requested == 0 ? timeout() : Math.min(requested, timeout())});
                }
                if (name.equals("setFetchDirection") && !Objects.equals(arguments[0], ResultSet.FETCH_FORWARD)) throw denied();
                if (!name.equals("setFetchDirection") && (((Number) arguments[0]).longValue() < 0 || ((Number) arguments[0]).longValue() > 1024)) throw denied();
                return invoke(delegate, method, arguments);
            }
            if (name.equals("clearParameters")) return invoke(delegate, method, arguments);
            if (type == PreparedStatement.class && name.startsWith("set")) {
                if (name.contains("Stream") || name.contains("Reader") || Set.of("setBlob", "setClob", "setNClob", "setSQLXML", "setArray", "setRef", "setRowId").contains(name)) throw denied();
                for (Object argument : arguments) detached(argument);
                return invoke(delegate, method, arguments);
            }
            if (name.startsWith("get") || name.startsWith("is") || name.startsWith("enquote")) return result(invoke(delegate, method, arguments));
            throw denied();
        }
        private Object result(Object value) throws SQLException {
            return wrap(value, null);
        }
        void close() throws SQLException {
            if (closed) return; closed = true;
            if (delegate instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception failure) { throw ManagedJdbcTransactionContext.unavailable(); }
            }
        }
    }
    private int timeout() {
        Duration budget = context.invocation.control().remainingBudget();
        return (int) Math.max(1, Math.min(30, budget.toSeconds() + (budget.getNano() > 0 ? 1 : 0)));
    }
    private void sql(String value) throws SQLException {
        try { JdbcDataSql.validate(value); } catch (SQLException invalid) { context.failNested(); throw invalid; }
    }
    private SQLException denied() { context.failNested(); return JdbcDataSql.rejected(); }
    private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            context.failNested(); Throwable cause = failure.getCause();
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                throw new SQLException("Managed JDBC operation failed", state != null && state.matches("[A-Z0-9]{5}") ? state : "HY000", sql.getErrorCode());
            }
            throw ManagedJdbcTransactionContext.unavailable();
        }
    }
    private static Object object(Object self, String name, Object[] arguments) {
        return switch (name) { case "equals" -> self == arguments[0]; case "hashCode" -> System.identityHashCode(self); case "toString" -> "ManagedJdbcHandle[owned]"; default -> throw new IllegalStateException("Invalid JDBC object method"); };
    }
    void dispose() throws SQLException {
        SQLException failure = null;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            try { ordered.get(i).close(); } catch (SQLException cleanup) { if (failure == null) failure = cleanup; }
        }
        handles.clear(); ordered.clear(); if (failure != null) throw failure;
    }
}
