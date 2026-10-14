package io.github.paohaijiao.jquick.agent.plugin.jdbc;

/**
 * Holds the tenant of the request being served by the current thread.
 *
 * <p>Multi-tenant isolation normally either leaks into the business code (every DAO call takes a
 * tenant argument) or relies on connection pooling tricks. Carrying it in a {@link ThreadLocal} and
 * letting the agent inject the predicate into the SQL keeps the DAO signature clean while the
 * database still enforces the isolation.
 *
 * <p>The value is read by {@link JQuickTenantSqlRewriter} when it rewrites a statement, so the
 * application only has to do this once per request:
 *
 * <pre>{@code
 * JQuickTenantContext.set("tenant-42");
 * try {
 *     orderDao.findAll();          // becomes: ... WHERE tenant_id = 'tenant-42'
 * } finally {
 *     JQuickTenantContext.clear();
 * }
 * }</pre>
 *
 * @author Martin
 */
public final class JQuickTenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<String>();

    private JQuickTenantContext() {
    }

    public static String get() {
        return TENANT.get();
    }

    public static void set(String tenantId) {
        if (tenantId == null) {
            TENANT.remove();
        } else {
            TENANT.set(tenantId);
        }
    }

    public static boolean isPresent() {
        return TENANT.get() != null;
    }

    public static void clear() {
        TENANT.remove();
    }
}
