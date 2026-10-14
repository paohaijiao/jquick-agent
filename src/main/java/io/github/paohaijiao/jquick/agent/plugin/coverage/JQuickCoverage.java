package io.github.paohaijiao.jquick.agent.plugin.coverage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which methods were executed, in the spirit of a coverage probe.
 *
 * <h3>What is measured, honestly</h3>
 * Coverage is at the <b>method</b> level, not the line level. A line-level probe needs a hook on
 * every {@code visitLineNumber} of the byte-code, which jquick-asm's advice API does not expose — it
 * offers method-entry and pre-return hooks, and this framework builds exclusively on that API rather
 * than reaching for a hand-written visitor. The consequence is stated plainly: the report answers
 * "which methods never ran", not "which branch never ran".
 *
 * <p>That is still the question an integration suite asks most often — an untested entry point is
 * usually an entire untested feature — and it costs nothing but a set insertion per instrumented
 * call.
 *
 * @author Martin
 */
public final class JQuickCoverage {

    private final Set<String> executed =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final Set<String> instrumented =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static int[] countersFor(Map<String, int[]> perClass, String signature) {
        String className = signature;
        int hash = signature.indexOf('#');
        if (hash > 0) {
            className = signature.substring(0, hash);
        }
        int[] counters = perClass.get(className);
        if (counters == null) {
            counters = new int[2];
            perClass.put(className, counters);
        }
        return counters;
    }

    /**
     * Records that a method started executing.
     */
    public void record(String signature) {
        if (signature != null) {
            executed.add(signature);
        }
    }

    /**
     * Declares a method as instrumented, so the report can compute what was never touched.
     */
    public void register(String signature) {
        if (signature != null) {
            instrumented.add(signature);
        }
    }

    public boolean wasExecuted(String signature) {
        return executed.contains(signature);
    }

    public int executedCount() {
        return executed.size();
    }

    public int instrumentedCount() {
        return instrumented.size();
    }

    public Set<String> executedOperations() {
        return Collections.unmodifiableSet(new TreeSet<String>(executed));
    }

    /**
     * Methods that were instrumented but never executed.
     */
    public Set<String> untouchedOperations() {
        Set<String> untouched = new TreeSet<String>(instrumented);
        untouched.removeAll(executed);
        return untouched;
    }

    /**
     * Text report grouped by class, with the coverage ratio per class.
     */
    public String report() {
        Map<String, int[]> perClass = new TreeMap<String, int[]>();
        for (String signature : instrumented) {
            int[] counters = countersFor(perClass, signature);
            counters[0]++;
            if (executed.contains(signature)) {
                counters[1]++;
            }
        }
        for (String signature : executed) {
            if (!instrumented.contains(signature)) {
                int[] counters = countersFor(perClass, signature);
                counters[0]++;
                counters[1]++;
            }
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("jquick-agent method coverage\n");
        sb.append("----------------------------\n");
        int total = 0;
        int covered = 0;
        for (Map.Entry<String, int[]> entry : perClass.entrySet()) {
            int[] counters = entry.getValue();
            total += counters[0];
            covered += counters[1];
            sb.append(String.format("  %6.2f%%  %3d/%-3d  %s%n",
                    counters[0] == 0 ? 0d : (100d * counters[1] / counters[0]),
                    counters[1], counters[0], entry.getKey()));
        }
        sb.append(String.format("  ------  %3d/%-3d  TOTAL (%.2f%%)%n", covered, total,
                total == 0 ? 0d : 100d * covered / total));
        return sb.toString();
    }

    /**
     * Writes {@link #report()} to a file so a build can archive it next to the test results.
     */
    public void writeTo(File target) {
        if (target == null) {
            return;
        }
        Writer writer = null;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
            writer.write(report());
            writer.flush();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write the coverage report to " + target, ex);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    public void reset() {
        executed.clear();
        instrumented.clear();
    }
}
