package io.github.paohaijiao.jquick.agent.plugin.jdbc;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickComponentMatchers;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickComponentPlugin;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.Arrays;
import java.util.List;

/**
 * Capability: database access instrumentation.
 *
 * <p>Delivers the three database questions that matter in production — <em>which statements run, how
 * slow are they, which ones are slow</em> — plus the two interception features that require writing
 * SQL rather than reading it: automatic tenant filtering and, through the pluggable
 * {@link JQuickSqlRewriter}, sharding hints or dialect adaptation.
 *
 * <p>Targets are expressed as the JDBC interfaces ({@code Statement}, {@code Connection},
 * {@code DataSource}) rather than driver class names, so a driver swap, a shaded jar or a change of
 * connection pool does not break instrumentation. Common MyBatis and pool wrappers are listed as
 * well because they declare their own hierarchy.
 *
 * @author Martin
 */
public final class JQuickJdbcPlugin extends JQuickComponentPlugin {

    public static final String NAME = "jdbc";

    private static final List<String> DEFAULT_TARGETS = Arrays.asList(
            "java.sql.Statement",
            "java.sql.PreparedStatement",
            "java.sql.CallableStatement",
            "java.sql.Connection",
            "javax.sql.DataSource",
            "javax.sql.PooledConnection",
            "org.apache.ibatis.executor.Executor",
            "org.apache.ibatis.session.SqlSession",
            "org.mybatis.spring.SqlSessionTemplate",
            "com.zaxxer.hikari.HikariDataSource",
            "com.alibaba.druid.pool.DruidDataSource",
            "org.apache.commons.dbcp2.BasicDataSource");

    public JQuickJdbcPlugin() {
        super(NAME,
                "jdbc",
                JQuickSpanType.EXIT,
                JQuickCategory.INTEGRATION,
                JQuickComponentMatchers.superTypes(JQuickConfig.JDBC_INCLUDES, DEFAULT_TARGETS),
                buildMethodMatcher(),
                null,
                "JDBC spans, SQL metrics, slow queries, tenant rewrite");
    }

    /**
     * Every statement entry point of {@code java.sql}, plus the MyBatis executor methods.
     */
    private static JQuickMethodMatcher buildMethodMatcher() {
        return JQuickMethodMatcher.builder()
                .matchPrefix("execute")
                .matchName("prepareStatement")
                .matchName("prepareCall")
                .matchName("createStatement")
                .matchName("addBatch")
                .matchName("query")
                .matchName("update")
                .build();
    }

    /**
     * Argument write-back is only worth its cost when a SQL rewriter is active, so the plan is
     * resolved from the configuration actually in force rather than at construction time.
     */
    @Override
    protected JQuickHookPlan planRequirement(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.JDBC_TENANT_ENABLED, false)
                ? JQuickHookPlan.builder().transformArguments().build()
                : null;
    }

    /**
     * The listener is registered here rather than passed to the constructor because it needs the
     * runtime meter registry, which only exists once the environment has been created.
     */
    @Override
    protected void configure(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        environment.addListener(new JQuickJdbcListener(
                environment.getMeters(),
                config.getLong(JQuickConfig.JDBC_SLOW_MS,
                        config.getLong(JQuickConfig.TRACE_SLOW_MS, 200L)),
                config.getBoolean("jdbc.print.slow", true),
                config.getInt("jdbc.log.length", 200)));
        if (config.getBoolean(JQuickConfig.JDBC_TENANT_ENABLED, false)) {
            JQuickSqlRewriter rewriter = new JQuickTenantSqlRewriter(
                    config.get(JQuickConfig.JDBC_TENANT_COLUMN, "tenant_id"));
            environment.addArgumentTransformer(new JQuickJdbcArgumentTransformer(rewriter));
        }
    }
}
