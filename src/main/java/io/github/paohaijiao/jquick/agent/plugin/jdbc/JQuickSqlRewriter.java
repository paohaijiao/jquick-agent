package io.github.paohaijiao.jquick.agent.plugin.jdbc;

/**
 * Rewrites a SQL statement on its way to the driver.
 *
 * <p>This is the extension point behind every "modify SQL without touching the DAO" requirement:
 * multi-tenant row filtering, sharding hints, mandatory audit columns, a forced {@code LIMIT} on an
 * unbounded query, or dialect adaptation.
 *
 * <p>The implementation receives the statement exactly as the application produced it, and returns
 * what the driver will actually execute. Returning the input unchanged is a valid no-op.
 *
 * @author Martin
 */
public interface JQuickSqlRewriter {

    /**
     * Rewrites a statement.
     *
     * @param sql      the statement as written by the application
     * @param tenantId the tenant of the current request, or {@code null} when none is bound
     * @return the statement to execute; must never be {@code null}
     */
    String rewrite(String sql, String tenantId);

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
