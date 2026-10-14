package example.app;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixture: ordinary application code, with no knowledge of the agent.
 *
 * <p>It is written the way a real service would be, because that is the only way to test the promise
 * the framework makes. Every method here exists to exercise one shape the emitter has to handle:
 *
 * <ul>
 *   <li>{@link #add(int, int)} — two int parameters, int return;</li>
 *   <li>{@link #widen(int)} — a long return, i.e. a category-2 value on the stack;</li>
 *   <li>{@link #half(double)} — a double parameter and return, also category 2;</li>
 *   <li>{@link #greet(String)} — a reference parameter and return;</li>
 *   <li>{@link #touch()} — void return;</li>
 *   <li>{@link #staticLabel(int)} — a static method, whose first slot is not the receiver;</li>
 *   <li>{@link #guarded(int)} — the application's <b>own</b> try/catch, the single most important
 *       thing the emitter must not break;</li>
 *   <li>{@link #boom(int)} — a throw that must still propagate;</li>
 *   <li>{@link #selfCall(int)} — recursion, for nested spans and depth;</li>
 *   <li>{@link #runTask(Runnable)} — the submission point that the thread-pool hook wraps;</li>
 *   <li>{@link #tick(String, long, Object)} — a mixed signature, to prove slot arithmetic;</li>
 *   <li>{@link #findBySql(String)} — a method that takes a SQL string without being a database call,
 *       to prove the JDBC capability follows the component label rather than the text.</li>
 * </ul>
 *
 * @author Martin
 */
public class FixtureService {

    /** Observable side effect, so a test can tell "the body ran" from "the body was skipped". */
    public static final AtomicInteger CALLS = new AtomicInteger();

    public int add(int a, int b) {
        CALLS.incrementAndGet();
        return a + b;
    }

    public long widen(int value) {
        return value * 1000L;
    }

    public double half(double value) {
        return value / 2d;
    }

    public float ratio(int numerator, int denominator) {
        return (float) numerator / denominator;
    }

    public boolean isPositive(int value) {
        return value > 0;
    }

    public char initial(String text) {
        return text.charAt(0);
    }

    public String greet(String name) {
        return "hello " + name;
    }

    public void touch() {
        CALLS.incrementAndGet();
    }

    public static String staticLabel(int value) {
        return "n=" + value;
    }

    public int[] range(int size) {
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = i;
        }
        return values;
    }

    /**
     * An array <em>parameter</em>, which is the other half of the array handling: the argument must
     * arrive as the same instance, not as a copy and not as a broken cast.
     */
    public int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    /**
     * The zero-intrusion test in one method: an exception is thrown <em>inside</em> the application's
     * own try block and caught by the application's own handler. If the agent registered a competing
     * handler, this method would either swallow the exception or never reach the catch clause.
     */
    public String guarded(int value) {
        try {
            if (value < 0) {
                throw new IllegalArgumentException("negative:" + value);
            }
            return "ok:" + value;
        } catch (IllegalArgumentException ex) {
            return "caught:" + ex.getMessage();
        }
    }

    /**
     * Throws without a local handler, so the exception must reach the caller untouched.
     */
    public int boom(int value) {
        if (value == 0) {
            throw new IllegalStateException("boom");
        }
        return value * 2;
    }

    /**
     * Recursion: each level opens a span, so a trace of depth {@code n + 2} is expected.
     */
    public String selfCall(int depth) {
        if (depth <= 0) {
            return "done";
        }
        return "x" + selfCall(depth - 1);
    }

    /**
     * Submission point used by the thread-pool propagation test.
     */
    public void runTask(Runnable task) {
        task.run();
    }

    /**
     * Mixed signature: reference + category-2 primitive + reference.
     */
    public String tick(String label, long value, Object extra) {
        return label + ':' + value + ':' + extra;
    }

    /**
     * Returns a string that contains a mobile number, for the masking capability.
     *
     * <p>A result transformer replaces the value the caller receives, so the test asserts on the
     * return value of this method: if masking works, the caller never sees the raw number.
     */
    public String profile(String mobile) {
        return "mobile=" + mobile;
    }

    /**
     * Takes a statement as a string without being a database call: the agent selected no JDBC
     * supertype here, so the JDBC listener must ignore it even though the text parses as SQL.
     */
    public String findBySql(String sql) {
        CALLS.incrementAndGet();
        return "planned:" + sql;
    }

    /**
     * Identity function, for the encryption capability: the caller must receive a ciphertext even
     * though the body produced the plaintext unchanged.
     */
    public String secret(String value) {
        return value;
    }
}
