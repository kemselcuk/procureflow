package com.procureflow.app.support;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Test-only JDBC proxy that counts executed statements and keeps their SQL text. It wraps every
 * connection/statement the application obtains from the real pooled DataSource, so query-count
 * tests observe exactly what the production code executes. No production bean references this
 * class.
 */
public final class CountingDataSource implements DataSource {
    private final DataSource delegate;
    private final AtomicLong executions = new AtomicLong();
    private final List<String> executedSql = Collections.synchronizedList(new ArrayList<>());

    public CountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** Clears the counter; call immediately before the operation under test. */
    public void reset() {
        executions.set(0);
        executedSql.clear();
    }

    public long count() {
        return executions.get();
    }

    /** Statements whose normalized SQL contains the fragment, case-insensitively. */
    public long countMatching(String fragment) {
        var needle = fragment.toLowerCase();
        return statements().stream().filter(sql -> sql.toLowerCase().contains(needle)).count();
    }

    public List<String> statements() {
        synchronized (executedSql) {
            return List.copyOf(executedSql);
        }
    }

    private void record(String sql) {
        executions.incrementAndGet();
        executedSql.add(sql == null ? "" : sql.replaceAll("\\s+", " ").strip());
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(CountingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    var name = method.getName();
                    if (name.startsWith("prepareStatement") && args != null && args.length > 0 && args[0] instanceof String sql) {
                        return wrapStatement((Statement) invoke(connection, method, args), sql);
                    }
                    if (name.equals("prepareCall") && args != null && args.length > 0 && args[0] instanceof String sql) {
                        return wrapStatement((Statement) invoke(connection, method, args), sql);
                    }
                    if (name.equals("createStatement")) {
                        return wrapStatement((Statement) invoke(connection, method, args), null);
                    }
                    return invoke(connection, method, args);
                });
    }

    private Statement wrapStatement(Statement statement, String sql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(CountingDataSource.class.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                             "executeBatch", "executeLargeBatch" -> record(sql);
                        default -> { }
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
    @Override public String toString() { return "CountingDataSource[" + delegate + "]"; }
}
