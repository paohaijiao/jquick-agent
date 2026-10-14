package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickClassMatcher;
import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.core.JQuickInstrumentEnhancer;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMatcher;
import io.github.paohaijiao.jquick.agent.plugin.jdbc.JQuickJdbcListener;
import io.github.paohaijiao.jquick.agent.plugin.jdbc.JQuickJdbcPlugin;
import io.github.paohaijiao.jquick.agent.plugin.metrics.JQuickMetricsPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the database capability, and specifically for the boundary it must not cross.
 *
 * <p>The interesting part of the JDBC plugin is not that it counts a statement — it is <em>which</em>
 * statements it counts. A listener is dispatched to every instrumented call, so a plugin that keys on
 * "an argument that parses as SQL" counts a private helper, a DAO facade or a query builder as a
 * database round trip, and the resulting metric silently reads high. Both halves are asserted here,
 * because only together do they say "the metric follows the selection, not the text".
 *
 * <p>There is no {@code java.sql.Statement} fixture in this suite on purpose: the SQL text never
 * reaches the listener through the interface, only through the argument array, so a hand-written
 * enhancer labelled {@code jdbc} exercises exactly the same code path a real driver would.
 *
 * @author Martin
 */
public class JdbcCapabilityTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    /**
     * The failure mode this test exists for: a method on an instrumented class, taking a string that
     * {@code JQuickSqlUtil.looksLikeSql} accepts, that has nothing to do with a database.
     */
    @Test
    public void aSqlLookingArgumentIsNotADatabaseCall() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES,
                new JQuickTracePlugin(), new JQuickMetricsPlugin(), new JQuickJdbcPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "findBySql",
                new Class<?>[]{String.class}, "select * from orders where id = 1");

        String scrape = harness.environment().getMeters().scrape();
        assertFalse("a method the JDBC enhancer did not select must not be counted, whatever its "
                + "arguments look like: " + scrape, scrape.contains("jquick_sql_executions_total"));
        assertFalse("and it must not be counted as a slow query either",
                scrape.contains("jquick_slow_sql_total"));
    }

    /**
     * The other half: the same method with the same SQL-looking argument, but this time selected with
     * the component label the JDBC enhancer writes. Now it must be counted — which is what proves the
     * guard keys on the label rather than on the class name.
     */
    @Test
    public void aCallLabelledJdbcIsCounted() throws Exception {
        JQuickHarness harness = jdbcLabelledHarness();
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "findBySql",
                new Class<?>[]{String.class}, "update orders set mobile = 'x' where id = 1");
        call(service, instance, "findBySql",
                new Class<?>[]{String.class}, "update orders set mobile = 'y' where id = 2");

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("a call labelled jdbc must be counted: " + scrape,
                scrape.contains("jquick_sql_executions_total{sql_type=\"UPDATE\"} 2"));
        assertTrue("its latency must be observed too",
                scrape.contains("jquick_sql_duration_seconds_count{sql_type=\"UPDATE\"} 2"));
    }

    /**
     * The statement type is derived from the text, and it is the label an operator's dashboard groups
     * by — so it has to be the type, never the statement.
     */
    @Test
    public void theStatementTypeIsTheLabelNotTheStatementText() throws Exception {
        JQuickHarness harness = jdbcLabelledHarness();
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "findBySql", new Class<?>[]{String.class}, "insert into t values (1)");
        call(service, instance, "findBySql", new Class<?>[]{String.class}, "select * from t");
        call(service, instance, "findBySql", new Class<?>[]{String.class}, "delete from t");

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("one series per type, not one per statement: " + scrape,
                scrape.contains("jquick_sql_executions_total{sql_type=\"INSERT\"} 1"));
        assertTrue(scrape.contains("jquick_sql_executions_total{sql_type=\"SELECT\"} 1"));
        assertTrue(scrape.contains("jquick_sql_executions_total{sql_type=\"DELETE\"} 1"));
        assertFalse("the statement text itself must never become a label",
                scrape.contains("insert into t"));
    }

    /* ================================================================== helpers */

    /**
     * A harness whose only database-shaped plugin is a hand-written enhancer carrying the {@code jdbc}
     * component label, plus the real listener. This is the smallest thing that can be called a
     * database integration.
     */
    private static JQuickHarness jdbcLabelledHarness() {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES,
                new JQuickTracePlugin(), new JQuickMetricsPlugin());
        JQuickEnvironment environment = harness.environment();
        environment.addListener(new JQuickJdbcListener(environment.getMeters(), 200L, false, 200));
        return harness.withEnhancer(new JQuickInstrumentEnhancer(
                "fixture-jdbc",
                JQuickClassMatcher.byName(SERVICE),
                JQuickMethodMatcher.named("findBySql"),
                JQuickSpanType.EXIT,
                JQuickJdbcPlugin.NAME,
                environment));
    }

    private static Object call(Class<?> owner, Object instance, String name, Class<?>[] types,
                               Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException(cause);
        }
    }
}
