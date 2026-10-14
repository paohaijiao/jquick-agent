package com.demo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * A JDBC {@link Statement} that does not talk to a database.
 *
 * <p>It exists because a demo needs to prove the database capability, not describe it — and a
 * {@code -javaagent} demo that requires a running Oracle instance proves nothing to the reader. The
 * agent selects this class through its <b>hierarchy</b>: the default {@code jdbc.includes} list names
 * {@code java.sql.Statement}, and this class declares exactly that interface as its direct supertype.
 * A real driver's {@code Statement} is selected by the same rule, which is the point — the agent never
 * learns the driver's name.
 *
 * <p>Everything the demo does not use throws {@link UnsupportedOperationException}. That is honest:
 * a real driver has to implement all of it, and this file shows the shape one has to have in order to
 * be instrumented, without pretending to be one.
 *
 * <p>What {@code execute(String)} does model faithfully: it records the SQL it was handed, decides
 * whether that statement returns rows, and can be told to simulate latency, so the slow-query path can
 * be demonstrated rather than asserted.
 *
 * @author Martin
 */
public final class DemoStatement implements Statement {

    private String lastSql;

    private long latencyMillis;

    private boolean closed;

    /**
     * Latency applied to the next statement, standing in for a slow query. A real slow query is slow
     * because of the database; the demo has no database, so it is told how slow to be.
     */
    public void setLatencyMillis(long millis) {
        this.latencyMillis = millis;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        this.lastSql = sql;
        if (latencyMillis > 0L) {
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // true means "the result is a ResultSet", which is what a query produces. The demo's caller
        // reads getUpdateCount() for the other case, exactly as it would with a real driver.
        return !isWrite(sql);
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return lastSql != null && isWrite(lastSql) ? 1 : -1;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        // The demo never reads rows back: a ResultSet would be 200 more lines of an interface that
        // is not the subject of the exercise.
        return null;
    }

    @Override
    public void close() throws SQLException {
        this.closed = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    private static boolean isWrite(String sql) {
        String normalized = sql == null ? "" : sql.trim().toLowerCase();
        return normalized.startsWith("insert")
                || normalized.startsWith("update")
                || normalized.startsWith("delete")
                || normalized.startsWith("merge");
    }

    /* ================================================================== not modelled */

    private static UnsupportedOperationException notModelled() {
        return new UnsupportedOperationException(
                "the demo's Statement models execute(String) only; a real driver implements this");
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        throw notModelled();
    }

    @Override
    public int getMaxRows() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        throw notModelled();
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        throw notModelled();
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        throw notModelled();
    }

    @Override
    public void cancel() throws SQLException {
        throw notModelled();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        throw notModelled();
    }

    @Override
    public void clearWarnings() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw notModelled();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        throw notModelled();
    }

    @Override
    public int getFetchDirection() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        throw notModelled();
    }

    @Override
    public int getFetchSize() throws SQLException {
        throw notModelled();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        throw notModelled();
    }

    @Override
    public int getResultSetType() throws SQLException {
        throw notModelled();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw notModelled();
    }

    @Override
    public void clearBatch() throws SQLException {
        throw notModelled();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        throw notModelled();
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean isPoolable() throws SQLException {
        throw notModelled();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        throw notModelled();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw notModelled();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw notModelled();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notModelled();
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notModelled();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw notModelled();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        throw notModelled();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw notModelled();
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notModelled();
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notModelled();
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notModelled();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        throw notModelled();
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        throw notModelled();
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        throw notModelled();
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        throw notModelled();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw notModelled();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        throw notModelled();
    }
}
