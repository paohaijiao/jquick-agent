package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.bootstrap.JQuickMetricsExporter;
import io.github.paohaijiao.jquick.agent.plugin.metrics.JQuickMetricsPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the built-in Prometheus endpoint: that it exists when asked for, that it answers, and that
 * it cannot outlive the application it was installed into.
 *
 * <p>The last one is the reason this class exists. An HTTP server started from an agent is not an
 * ordinary HTTP server: the agent's install path runs on the application's main thread, and
 * {@code com.sun.net.httpserver} hands the daemon status of that thread down to its dispatcher. A
 * non-daemon dispatcher means the JVM never terminates — after {@code main} returns, after
 * {@code System.exit}, always — and no shutdown hook can rescue it, because hooks only run once every
 * non-daemon thread has finished. That is a defect that makes the agent unusable for every batch job
 * and CLI, and it is silent unless someone tests the exit.
 *
 * @author Martin
 */
public class MetricsEndpointTest {

    private static final String RULES = "includes=example.app.*;quiet=true";

    /* ================================================================== lifecycle */

    /**
     * The port is the switch, and the default is off: an agent that opens a socket nobody asked for is
     * an agent whose firewall rules were written by surprise.
     */
    @Test
    public void theEndpointIsOffUnlessAPortIsConfigured() {
        JQuickHarness harness = harness(RULES);
        assertNull("no metrics.port means no listener and no thread",
                JQuickMetricsExporter.start(harness.environment()));
    }

    /**
     * The invariant that keeps the host JVM able to exit, asserted on the actual dispatcher thread.
     *
     * <p>It is checked by walking the live threads for a frame in {@code sun.net.httpserver} rather
     * than by looking for a thread by name, because the name is a JDK implementation detail
     * ({@code Thread-2}) while the frame is the thing that identifies it. A thread that has been
     * started but not yet scheduled reports an empty stack, so the thread is awaited rather than raced
     * — polling a condition that is guaranteed to become true is not the same as sleeping and hoping.
     */
    @Test
    public void theEndpointDoesNotKeepTheJvmAlive() throws Exception {
        int port = freePort();
        JQuickHarness harness = harness(RULES + ";metrics.port=" + port);
        JQuickMetricsExporter exporter = JQuickMetricsExporter.start(harness.environment());
        assertNotNull("the endpoint should have started on port " + port, exporter);
        try {
            List<Thread> serverThreads = awaitServerThreads(5000L);
            assertFalse("the HTTP dispatcher should be running while the endpoint is up",
                    serverThreads.isEmpty());
            for (Thread thread : serverThreads) {
                assertTrue("the metrics endpoint must not leave a non-daemon thread behind: "
                                + thread + " would stop the JVM from ever exiting",
                        thread.isDaemon());
            }
        } finally {
            exporter.stop();
        }
    }

    /**
     * The live threads with a frame inside {@code sun.net.httpserver} — the dispatcher, and any
     * exchange thread the JDK happens to have running.
     */
    private static List<Thread> serverThreads() {
        List<Thread> found = new ArrayList<Thread>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement frame : entry.getValue()) {
                if (frame.getClassName().startsWith("sun.net.httpserver")) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    private static List<Thread> awaitServerThreads(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            List<Thread> found = serverThreads();
            if (!found.isEmpty() || System.currentTimeMillis() >= deadline) {
                return found;
            }
            Thread.sleep(20L);
        }
    }

    /* ================================================================== endpoints */

    /**
     * Every documented path answers, and the configuration endpoint does not hand out secrets.
     */
    @Test
    public void theEndpointServesItsPathsAndRedactsSecretValues() throws Exception {
        int port = freePort();
        JQuickHarness harness = harness(RULES + ";metrics.port=" + port
                + ";crypto.key=super-secret-key");
        JQuickMetricsExporter exporter = JQuickMetricsExporter.start(harness.environment());
        assertNotNull(exporter);
        try {
            assertEquals("OK\n", get(port, "/health"));
            assertTrue("/ must index the other paths", get(port, "/").contains("/metrics"));
            assertNotNull("the call graph must be readable", get(port, "/topology"));

            String config = get(port, "/config");
            assertTrue("the effective configuration must be visible",
                    config.contains("metrics.port=" + port));
            assertTrue("a value whose key looks like a secret must be redacted",
                    config.contains("crypto.key=***"));
            assertFalse("the secret itself must never be sent over an unauthenticated endpoint",
                    config.contains("super-secret-key"));

            String metrics = get(port, "/metrics");
            assertNotNull(metrics);
        } finally {
            exporter.stop();
        }
    }

    /**
     * A second endpoint on the same port is refused rather than thrown: telemetry is optional, the
     * application is not.
     */
    @Test
    public void aBusyPortIsReportedAndIgnored() {
        int port = freePort();
        JQuickHarness first = harness(RULES + ";metrics.port=" + port);
        JQuickMetricsExporter exporter = JQuickMetricsExporter.start(first.environment());
        assertNotNull(exporter);
        try {
            JQuickHarness second = harness(RULES + ";metrics.port=" + port);
            assertNull("the agent must survive a port that is already taken",
                    JQuickMetricsExporter.start(second.environment()));
        } finally {
            exporter.stop();
        }
    }

    /* ================================================================== helpers */

    private static JQuickHarness harness(String agentArgs) {
        return JQuickHarness.ofPlugins(agentArgs, new JQuickTracePlugin(), new JQuickMetricsPlugin());
    }

    /**
     * Asks the OS for a port and hands it straight back. There is a theoretical race with another
     * process taking it in between, which is why {@code aBusyPortIsReportedAndIgnored} asserts on the
     * exporter rather than on the port.
     */
    private static int freePort() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket(0);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("no free port available", ex);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The descriptor is released when the process ends anyway.
                }
            }
        }
    }

    private static String get(int port, String path) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            assertEquals("HTTP status of " + path, 200, connection.getResponseCode());
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                body.write(buffer, 0, read);
            }
            in.close();
            return new String(body.toByteArray(), "UTF-8");
        } finally {
            connection.disconnect();
        }
    }
}
