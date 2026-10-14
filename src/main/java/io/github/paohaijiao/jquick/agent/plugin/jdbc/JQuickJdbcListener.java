package io.github.paohaijiao.jquick.agent.plugin.jdbc;

import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;
import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Turns JDBC spans into database metrics and a slow-query log.
 *
 * <p>The listener declares {@link #captureArguments()}, which is what makes the emitter materialise
 * the argument array for JDBC methods — and only for them. The SQL text is recovered from that array
 * by looking for the first argument that parses as a statement, so the plugin works with
 * {@code Statement}, {@code PreparedStatement} and the MyBatis/Hikari wrappers without having to know
 * their exact signatures.
 *
 * <p>Labels are the statement <em>type</em> ({@code SELECT}, {@code UPDATE}, …) rather than the
 * statement text: labelling by text would create one time series per distinct query, which is the
 * fastest way to take a Prometheus server down.
 *
 * <p>The listener only accepts calls the JDBC enhancer selected, identified by the component label
 * (see {@link #isJdbcCall(JQuickInvocation)}). A listener sees every instrumented call, so without
 * that check a private helper taking a SQL string would be counted as a database round trip.
 *
 * @author Martin
 */
public final class JQuickJdbcListener implements JQuickMethodListener {

    private final JQuickMeterRegistry meters;

    private final long slowThresholdMillis;

    private final boolean printSlowQueries;

    private final int logLength;

    public JQuickJdbcListener(JQuickMeterRegistry meters, long slowThresholdMillis,
                              boolean printSlowQueries, int logLength) {
        this.meters = meters;
        this.slowThresholdMillis = slowThresholdMillis;
        this.printSlowQueries = printSlowQueries;
        this.logLength = logLength;
    }

    /**
     * Whether the call came from a method this plugin's enhancer selected.
     *
     * <p>A listener is dispatched to <em>every</em> instrumented call, so without this guard the
     * listener would attribute any method to the database just because one of its arguments happens to
     * parse as SQL — a DAO facade taking a statement, a SQL builder, or a private helper like
     * {@code isWrite(String sql)}. The result is a metric that silently over-counts, which is worse
     * than a missing one: it looks plausible.
     *
     * <p>The test is the component label the enhancer writes, not the class name, for the same reason
     * the log transformer uses it: this plugin selects by <em>hierarchy</em>
     * ({@code java.sql.Statement} and friends), so the selected classes have arbitrary names, while the
     * label is a constant. The label is matched with {@code contains} rather than equals because
     * {@code JQuickMethodMeta} merges the labels of every capability that selected the method, so a
     * JDBC statement that the tracer also covers carries {@code method,jdbc}.
     */
    private static boolean isJdbcCall(JQuickInvocation invocation) {
        if (invocation == null) {
            return false;
        }
        String component = invocation.getComponent();
        return component != null && component.indexOf(JQuickJdbcPlugin.NAME) >= 0;
    }

    /**
     * Recovers the statement from the captured arguments, or {@code null} when the call carried none.
     */
    static String findSql(JQuickInvocation invocation) {
        Object[] arguments = invocation.getArguments();
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument instanceof String && JQuickSqlUtil.looksLikeSql((String) argument)) {
                return (String) argument;
            }
        }
        return null;
    }

    @Override
    public boolean captureArguments() {
        return true;
    }

    @Override
    public void onMethodEvent(JQuickMethodEvent event) {
        if (event.getPhase() == JQuickMethodEvent.JQuickPhase.ENTER) {
            return;
        }
        JQuickSpan span = event.getSpan();
        if (span == null) {
            return;
        }
        if (!isJdbcCall(event.getInvocation())) {
            return;
        }
        String sql = findSql(event.getInvocation());
        if (sql == null) {
            return;
        }
        try {
            String type = JQuickSqlUtil.statementType(sql);
            meters.counter("jquick_sql_executions_total", "sql_type", type).increment();
            meters.timer("jquick_sql_duration_seconds", "sql_type", type)
                    .record(span.getDurationNanos());
            if (!span.isSuccess()) {
                meters.counter("jquick_sql_errors_total", "sql_type", type).increment();
            }
            if (span.getDurationMillis() >= slowThresholdMillis) {
                meters.counter("jquick_slow_sql_total", "sql_type", type).increment();
                if (printSlowQueries) {
                    System.out.println("[jquick-agent] SLOW SQL (" + span.getDurationMillis()
                            + "ms) " + JQuickSqlUtil.abbreviate(sql, logLength));
                }
            }
        } catch (Throwable ignored) {
            // Aggregation must never affect the application.
        }
    }

    @Override
    public String name() {
        return "jdbc-metrics";
    }
}
