package com.demo;

import java.sql.SQLException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Demo target application: a plain business class with no knowledge of the agent.
 *
 * <p>Every method exists to make one injected sequence visible in the demo output, so a reader can map
 * a line of trace back to the mechanism that produced it:
 *
 * <ul>
 *   <li>{@link #add(int, int)} - two primitives, a primitive return;</li>
 *   <li>{@link #greet(String)} - a reference argument and a reference return, i.e. the payload
 *       capture path;</li>
 *   <li>{@link #checkout(String, String)} - logs through the application's logging facade, i.e. the
 *       trace-id injection and log-masking path;</li>
 *   <li>{@link #record(String, String)} - runs two JDBC statements through a Statement whose only
 *       supertype is {@code java.sql.Statement}, i.e. the database capability: statement mix, latency,
 *       slow queries and a call-graph edge;</li>
 *   <li>{@link #pay(String, long)} - throws on a bad argument, i.e. the error and stack path;</li>
 *   <li>{@link #runAsync(Runnable)} - submits to a pool, i.e. the trace context hand-off;</li>
 *   <li>{@link #profile(String)} - returns a value containing a mobile number, i.e. the masking
 *       path.</li>
 * </ul>
 *
 * <p>Nothing here imports the agent. That is the point of the whole exercise.
 *
 * @author Martin
 */
public class DemoService {

    /**
     * A real pool, so the thread-pool hand-off in {@link #runAsync(Runnable)} is a real one.
     */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    public int add(int a, int b) {
        return a + b;
    }

    public String greet(String name) {
        return "hello " + name;
    }

    /**
     * A business method that logs through the application's own logging facade.
     *
     * <p>The message carries a mobile number, and the class carries {@code mask.includes} - so this one
     * method demonstrates two capabilities at once: the trace id is injected into the log line, and the
     * number in it is masked. Neither this method nor {@link ConsoleLogger} knows that either happened.
     */
    public String checkout(String user, String mobile) {
        DemoLoggers.APP.info("checkout started for " + user + " mobile=" + mobile);
        String receipt = "receipt-" + user;
        DemoLoggers.APP.info("checkout finished for " + user);
        return receipt;
    }

    public void pay(String user, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        System.out.println("[app] paid " + amount + " for " + user);
    }

    /**
     * The classic trace-loss case: the caller has a trace id, the worker does not.
     *
     * <p>{@code ThreadPoolExecutor.execute} is the submission point the agent wraps, and a
     * {@code submit} call reaches it through {@code AbstractExecutorService}, so both routes are
     * covered.
     */
    public void runAsync(Runnable task) {
        pool.execute(task);
    }

    /**
     * Returns a value containing a mobile number, to show result masking.
     */
    public String profile(String mobile) {
        return "mobile=" + mobile;
    }

    /**
     * A DAO method, written the way a hand-rolled JDBC layer is written.
     *
     * <p>{@link DemoStatement} is selected by the agent because it declares {@code java.sql.Statement}
     * — one of the names in the default {@code jdbc.includes} list — so the two calls below become
     * {@code EXIT} spans, and from them the agent derives the statement mix, the per-type latency, the
     * slow-query counter and an edge of the call graph. Nothing here knows that.
     *
     * <p>The second statement is deliberately slow ({@link DemoStatement#setLatencyMillis}), which is
     * what makes {@code jdbc.slow.ms} observable in the demo output.
     *
     * @param user   the user name
     * @param mobile the mobile number, part of the statement text so the captured arguments show it
     * @return the affected row count of the insert
     */
    public int record(String user, String mobile) throws SQLException {
        DemoStatement statement = new DemoStatement();
        try {
            boolean hasRows = statement.execute(
                    "insert into orders(user_name, mobile) values ('" + user + "', '" + mobile + "')");
            int affected = statement.getUpdateCount();
            statement.setLatencyMillis(80L);
            statement.execute("select id from orders where user_name = '" + user + "'");
            return hasRows ? -1 : affected;
        } finally {
            statement.close();
        }
    }

    /**
     * Stops the pool and waits for the submitted work, so the demo's output ends in a deterministic
     * order instead of racing the worker thread.
     */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5L, TimeUnit.SECONDS)) {
                System.out.println("[app] the worker pool did not finish within 5s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
