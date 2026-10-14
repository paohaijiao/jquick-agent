package com.demo;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;

/**
 * Demo target application.
 *
 * <p>Two usages:
 * <ul>
 *   <li>no argument - a short, self-terminating run used by the {@code -javaagent} (premain) demo;</li>
 *   <li>{@code loop [seconds]} - keeps invoking business methods for {@code seconds} (default 60) so
 *       the agent can be attached at runtime (agentmain demo). The PID is written to
 *       {@code demo.pid.file} so the attach script does not need to guess it, and every pass through
 *       {@link #exercise(DemoService)} is identical before and after the attach: only the
 *       {@code [jquick-agent]} lines around it change, which is what makes the attach visible.
 *       <p>The run ends by itself. That is deliberate: the attach script then has nothing to kill,
 *       which matters because "kill the process you just started" is the least portable part of any
 *       demo script — and a target that is still alive holds a lock on the agent jar on Windows.</li>
 * </ul>
 *
 * <p>This class imports nothing from the agent, and neither does any other class in this package. That
 * is the point of the whole exercise: everything below is a plain application.
 *
 * @author Martin
 */
public class DemoApplication {

    public static void main(String[] args) throws Exception {
        DemoService service = new DemoService();
        boolean loop = args.length > 0 && "loop".equalsIgnoreCase(args[0]);
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        System.out.println("[app] started, pid=" + currentPid());
        System.out.println("[app] no logging framework, no agent API and no AOP on the class path: "
                + "the agent sees a bare application");

        exercise(service);

        if (loop) {
            writePidFile();
            System.out.println("[app] loop mode: attach the agent now, then watch the [jquick-agent] "
                    + "lines; calls made before the attach produce none. This JVM exits by itself in "
                    + seconds + "s, so the script has nothing to kill.");
            for (int i = 0; i < seconds; i++) {
                Thread.sleep(1000L);
                exercise(service);
                if ((i + 1) % 10 == 0) {
                    System.out.println("[app] still looping, " + (seconds - i - 1)
                            + "s left; try http://127.0.0.1:9090/metrics and :9090/topology");
                }
            }
            System.out.println("[app] loop finished");
        }

        service.shutdown();
        System.out.println("[app] done");
    }

    /**
     * One pass over every mechanism the agent demonstrates.
     *
     * <p>Called once in premain mode and once a second in loop mode, so the same code produces the
     * "before the attach" and "after the attach" halves of the agentmain demo.
     */
    private static void exercise(final DemoService service) {
        // 1. Primitives: no payload to capture, so this is the cheapest hook plan.
        System.out.println("[app] add(2, 3) = " + service.add(2, 3));

        // 2. Reference payload plus two log lines. The trace id is injected into the message by the
        //    log capability and the mobile number in it is masked, without the application changing a
        //    single logging call.
        System.out.println("[app] checkout returned: " + service.checkout("alice", "13800138000"));

        // 3. The database path, with no database: two statements on a Statement whose only supertype is
        //    java.sql.Statement, so the agent derives the statement mix, the per-type latency, the
        //    slow-query counter and an edge of the call graph from them.
        try {
            System.out.println("[app] record returned affected rows: "
                    + service.record("alice", "13800138000"));
        } catch (java.sql.SQLException ex) {
            System.out.println("[app] the demo's Statement threw: " + ex.getMessage());
        }

        // 4. The failure path: the span is marked as an error and - with trace.error.stack=true - the
        //    frames are printed. The application's own catch below still runs: instrumentation adds
        //    instructions, it never installs a competing exception handler.
        try {
            service.pay("bob", -1L);
        } catch (IllegalArgumentException expected) {
            System.out.println("[app] the application's own catch ran: " + expected.getMessage());
        }

        // 5. Masking on the way out: the caller receives a masked value; the method body is untouched.
        System.out.println("[app] profile returned: " + service.profile("13800138000"));

        // 6. The thread-pool hand-off: the worker logs a line whose trace id was not set on the worker
        //    thread, but captured on this one. Without the agent the line would carry no id at all, and
        //    the asynchronous half of the request would be untraceable.
        service.runAsync(new Runnable() {
            @Override
            public void run() {
                DemoLoggers.APP.info("worker: this line carries the trace id of the submitting thread");
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                service.add(40, 2);
            }
        });

        // 7. The same logger, used from outside any traced business method, to show what the hierarchy
        //    based selection does and does not do: this method is instrumented, so the line is traced.
        DemoLoggers.APP.warn("main: a warning with an attached failure", new IllegalStateException("demo"));
    }

    private static String currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /**
     * Publishes the PID so the attach script does not have to parse {@code jps} output.
     */
    private static void writePidFile() throws Exception {
        String path = System.getProperty("demo.pid.file");
        if (path == null || path.isEmpty()) {
            return;
        }
        File file = new File(path);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Writer writer = new FileWriter(file);
        try {
            writer.write(currentPid());
        } finally {
            writer.close();
        }
    }
}
