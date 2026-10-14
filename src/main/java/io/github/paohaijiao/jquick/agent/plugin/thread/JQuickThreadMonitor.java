package io.github.paohaijiao.jquick.agent.plugin.thread;

import java.util.*;

/**
 * On-demand thread inventory, used to find a thread leak without attaching a profiler.
 *
 * <p>A pooled application names its threads by role — {@code http-nio-8080-exec-12},
 * {@code pool-3-thread-1}, {@code kafka-consumer-2} — so counting threads by the name with the
 * trailing discriminator removed gives a usable per-pool census. A leak then shows up as a monotonically
 * growing count for one role, which is exactly what the two-sample API here is for.
 *
 * <p>This is a diagnostic, not a monitor: it walks every thread in the JVM, so it is safe to call by
 * hand or from an admin endpoint and must never be called on a hot path.
 *
 * @author Martin
 */
public final class JQuickThreadMonitor {

    private JQuickThreadMonitor() {
    }

    /**
     * Thread counts grouped by role.
     */
    public static Map<String, Integer> snapshot() {
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            String role = roleOf(entry.getKey().getName());
            Integer current = counts.get(role);
            counts.put(role, current == null ? 1 : current + 1);
        }
        return counts;
    }

    /**
     * Total number of live threads.
     */
    public static int count() {
        return Thread.getAllStackTraces().size();
    }

    /**
     * Roles whose thread count grew beyond {@code maxExpected} between two samples.
     *
     * @param before      an earlier {@link #snapshot()}
     * @param maxExpected maximum tolerated count per role
     * @return one description per suspicious role
     */
    public static List<String> findLeaks(Map<String, Integer> before, int maxExpected) {
        List<String> findings = new ArrayList<String>();
        Map<String, Integer> after = snapshot();
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            Integer previous = before == null ? null : before.get(entry.getKey());
            if (previous == null) {
                if (entry.getValue() > maxExpected) {
                    findings.add(entry.getKey() + ": " + entry.getValue()
                            + " threads appeared (limit " + maxExpected + ")");
                }
                continue;
            }
            int growth = entry.getValue() - previous;
            if (entry.getValue() > maxExpected && growth > 0) {
                findings.add(entry.getKey() + ": " + previous + " -> " + entry.getValue()
                        + " (+" + growth + ", limit " + maxExpected + ")");
            }
        }
        return findings;
    }

    /**
     * Full inventory with the top frames of each thread, for post-mortem analysis.
     */
    public static String report(int maxFramesPerThread) {
        StringBuilder sb = new StringBuilder(2048);
        Map<Thread, StackTraceElement[]> traces =
                new TreeMap<Thread, StackTraceElement[]>(new java.util.Comparator<Thread>() {
                    @Override
                    public int compare(Thread first, Thread second) {
                        return first.getName().compareTo(second.getName());
                    }
                });
        traces.putAll(Thread.getAllStackTraces());
        for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
            Thread thread = entry.getKey();
            sb.append(thread.getName())
                    .append(" [").append(thread.getState()).append("] daemon=")
                    .append(thread.isDaemon()).append('\n');
            Deque<StackTraceElement> frames = new ArrayDeque<StackTraceElement>();
            for (StackTraceElement frame : entry.getValue()) {
                if (frames.size() >= maxFramesPerThread) {
                    break;
                }
                frames.addLast(frame);
            }
            for (StackTraceElement frame : frames) {
                sb.append("    at ").append(frame).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Groups a thread name into a role by removing the trailing discriminator.
     */
    static String roleOf(String threadName) {
        if (threadName == null) {
            return "(unnamed)";
        }
        int end = threadName.length();
        while (end > 0) {
            char c = threadName.charAt(end - 1);
            if (Character.isDigit(c)) {
                end--;
                continue;
            }
            if (c == '-' || c == '_' || c == ' ') {
                end--;
            }
            break;
        }
        return end == 0 ? threadName : threadName.substring(0, end);
    }

    /**
     * Convenience helper for a quick check from a shell or an admin endpoint.
     */
    public static Map<String, Integer> topRoles(int limit) {
        Map<String, Integer> snapshot = snapshot();
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<Map.Entry<String, Integer>>(snapshot.entrySet());
        java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> first, Map.Entry<String, Integer> second) {
                return second.getValue().compareTo(first.getValue());
            }
        });
        Map<String, Integer> top = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            top.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return top;
    }
}
