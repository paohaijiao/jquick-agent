package io.github.paohaijiao.jquick.agent.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.topology.JQuickTopology;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The built-in Prometheus scrape endpoint.
 *
 * <p>An agent that collects metrics but cannot be scraped is a library, not a monitoring tool, so the
 * endpoint is part of the product rather than an exercise for the reader. It is built on
 * {@code com.sun.net.httpserver}, which ships with the JDK: bundling a web framework into a
 * {@code -javaagent} jar would put a version of Netty or Jetty on the application's classpath, and an
 * agent that breaks the host's dependency resolution is worse than no agent at all.
 *
 * <h3>Off unless asked</h3>
 * The server starts only when {@code metrics.port} is a positive number. {@code metrics.enabled}
 * controls metric <em>collection</em>; the port controls <em>exposure</em>, and conflating the two is
 * how an agent suddenly opens a socket in an environment whose firewall was never told about it.
 *
 * <h3>Endpoints</h3>
 * <table summary="endpoints">
 *   <tr><td>{@code /metrics}</td><td>Prometheus text exposition — method, SQL and governance meters,
 *       plus the service topology derived from outgoing spans;</td></tr>
 *   <tr><td>{@code /topology}</td><td>human-readable call graph, for when a browser is faster than
 *       standing up Grafana;</td></tr>
 *   <tr><td>{@code /config}</td><td>effective configuration with secret-looking values redacted;</td></tr>
 *   <tr><td>{@code /health}</td><td>{@code OK}, for a liveness probe;</td></tr>
 *   <tr><td>{@code /}</td><td>an index of the above.</td></tr>
 * </table>
 *
 * <h3>Bind address</h3>
 * The default is {@code 0.0.0.0}, because in the environments where this matters — Kubernetes, a
 * container, a VM fleet — the scraper is a different process or a different host, and a loopback-only
 * endpoint is simply unreachable. That default is an availability decision, and it is stated here
 * rather than buried: set {@code metrics.bind=127.0.0.1} on a host whose network is not trusted, or
 * disable the endpoint entirely and push the metrics through an existing pipeline.
 *
 * @author Martin
 */
public final class JQuickMetricsExporter {

    /**
     * Interface the HTTP endpoint binds to.
     */
    public static final String BIND = "metrics.bind";

    /**
     * Path of the Prometheus exposition endpoint.
     */
    public static final String PATH = "metrics.path";

    private static final String DEFAULT_BIND = "0.0.0.0";

    private static final String DEFAULT_PATH = "/metrics";

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;

    private final String bindAddress;

    private final int port;

    private final String metricsPath;

    private JQuickMetricsExporter(HttpServer server, String bindAddress, int port, String metricsPath) {
        this.server = server;
        this.bindAddress = bindAddress;
        this.port = port;
        this.metricsPath = metricsPath;
    }

    /**
     * Starts the endpoint described by the environment's configuration.
     *
     * @return the running exporter, or {@code null} when it is disabled or the port is unavailable
     */
    public static JQuickMetricsExporter start(JQuickEnvironment environment) {
        if (environment == null) {
            return null;
        }
        JQuickConfig config = environment.getConfig();
        int port = config.getInt(JQuickConfig.METRICS_PORT, 0);
        if (port <= 0) {
            JQuickLogger.debug("metrics endpoint disabled (metrics.port=" + port + ")");
            return null;
        }
        String bind = config.get(BIND, DEFAULT_BIND);
        String path = normalizePath(config.get(PATH, DEFAULT_PATH));
        try {
            HttpServer server = startServerOnDaemonThread(bind, port, path, environment, config);
            JQuickMetricsExporter exporter =
                    new JQuickMetricsExporter(server, bind, port, path);
            JQuickLogger.info("Prometheus endpoint listening on http://"
                    + ("0.0.0.0".equals(bind) ? "0.0.0.0" : bind) + ":" + port + path);
            return exporter;
        } catch (Throwable ex) {
            // A busy port must not stop the agent: telemetry is optional, the application is not.
            JQuickLogger.warn("could not start the metrics endpoint on " + bind + ":" + port
                    + " (" + ex + "); metrics remain available in-process");
            return null;
        }
    }

    /**
     * Creates and starts the HTTP server <em>on a daemon thread</em>, and returns it.
     *
     * <p>This is not a stylistic choice, and getting it wrong is a defect with a very visible symptom.
     * {@code sun.net.httpserver.ServerImpl} creates its dispatcher thread in its constructor with a
     * plain {@code new Thread(...)} and never calls {@code setDaemon(true)} on it, so the dispatcher
     * inherits the daemon status of whichever thread ran {@code HttpServer.create(...)}. In an agent
     * that thread is {@code premain}'s, i.e. the application's main thread — so a non-daemon dispatcher
     * is created, the JVM never reaches the end of its life, and the process hangs after {@code main}
     * returns. {@code System.exit} does not save it either: shutdown hooks only run once every
     * non-daemon thread has finished, so a hook registered here would never get the chance to call
     * {@link #stop()}.
     *
     * <p>An agent that turns every short-lived JVM — a batch job, a CLI, a forked test run — into a
     * process that never exits is worse than an agent with no endpoint at all. Creating the server from
     * a daemon thread makes the inherited flag the correct one, and the invariant is pinned by
     * {@code MetricsEndpointTest#theEndpointDoesNotKeepTheJvmAlive()}, which asserts the daemon flag of
     * the actual dispatcher thread rather than of a thread it happens to have created itself.
     */
    private static HttpServer startServerOnDaemonThread(final String bind, final int port,
                                                        final String path,
                                                        final JQuickEnvironment environment,
                                                        final JQuickConfig config) throws Exception {
        final AtomicReference<HttpServer> started = new AtomicReference<HttpServer>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch ready = new CountDownLatch(1);
        Thread creator = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
                    server.createContext("/", new IndexHandler());
                    server.createContext(path, new MetricsHandler(environment));
                    server.createContext("/topology", new TopologyHandler(environment.getTopology()));
                    server.createContext("/config", new ConfigHandler(config));
                    server.createContext("/health", new HealthHandler());
                    server.setExecutor(daemonExecutor("jquick-metrics-exporter"));
                    server.start();
                    started.set(server);
                } catch (Throwable ex) {
                    failure.set(ex);
                } finally {
                    ready.countDown();
                }
            }
        }, "jquick-metrics-server");
        creator.setDaemon(true);
        creator.start();
        ready.await();
        Throwable failed = failure.get();
        if (failed != null) {
            if (failed instanceof Exception) {
                throw (Exception) failed;
            }
            throw new IllegalStateException(failed);
        }
        return started.get();
    }

    private static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return DEFAULT_PATH;
        }
        String normalized = path.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static ExecutorService daemonExecutor(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
                // Daemon: the endpoint must never keep the JVM alive after main() returns.
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(payload);
        } finally {
            out.close();
        }
    }

    /**
     * Renders the call graph as Prometheus series.
     *
     * <p>Topology is exposed through the same endpoint as everything else because a separate export
     * format would need its own collector, its own scrape job and its own alerting rules. The labels
     * are deliberately low-cardinality: caller and callee are <em>classes</em> (or logical component
     * names), never full signatures.
     */
    private static String topologyExposition(JQuickTopology topology) {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP jquick_topology_calls_total Calls observed on a caller -> callee edge.\n");
        sb.append("# TYPE jquick_topology_calls_total counter\n");
        for (JQuickTopology.Edge edge : topology.edges()) {
            sb.append(edge.toPrometheusLine());
        }
        return sb.toString();
    }

    /**
     * Renders the effective configuration with anything that looks like a secret masked.
     *
     * <p>The configuration is very useful when diagnosing "why is this class not instrumented", but
     * it is also where a crypto key ends up, and an unauthenticated HTTP endpoint is the last place a
     * key should be readable. Keys are therefore filtered by name; the value is never inspected and
     * never guessed at.
     */
    static String describeRedacted(JQuickConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("JQuick agent configuration (secret-like values redacted)\n");
        sb.append("---------------------------------------------------\n");
        for (Map.Entry<String, String> entry :
                new TreeMap<String, String>(config.asMap()).entrySet()) {
            sb.append(entry.getKey()).append('=')
                    .append(isSecretKey(entry.getKey()) ? "***" : entry.getValue())
                    .append('\n');
        }
        return sb.toString();
    }

    private static boolean isSecretKey(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        return lower.contains("key") || lower.contains("secret")
                || lower.contains("password") || lower.contains("passwd")
                || lower.contains("token") || lower.contains("credential");
    }


    public int getPort() {
        return port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public String getMetricsPath() {
        return metricsPath;
    }

    /**
     * Stops the endpoint; safe to call more than once.
     */
    public void stop() {
        try {
            server.stop(0);
        } catch (Throwable ex) {
            JQuickLogger.suppressed("metrics endpoint shutdown failed", ex);
        }
    }

    /**
     * Wraps a handler so that a failure while rendering a metric produces a 500 instead of a stack
     * trace on the wire, and never escapes into the HTTP server's thread.
     */
    private abstract static class SafeHandler implements HttpHandler {

        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            try {
                render(exchange);
            } catch (Throwable ex) {
                JQuickLogger.suppressed("metrics endpoint handler failed", ex);
                try {
                    respond(exchange, 500, "text/plain; charset=utf-8",
                            "internal error\n");
                } catch (Throwable ignored) {
                    // The connection is already broken; nothing left to report on.
                }
            } finally {
                exchange.close();
            }
        }

        abstract void render(HttpExchange exchange) throws IOException;
    }

    private static final class MetricsHandler extends SafeHandler {

        private final JQuickEnvironment environment;

        MetricsHandler(JQuickEnvironment environment) {
            this.environment = environment;
        }

        @Override
        void render(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder(4096);
            sb.append(environment.getMeters().scrape());
            if (environment.isTopologyEnabled()) {
                sb.append(topologyExposition(environment.getTopology()));
            }
            respond(exchange, 200, CONTENT_TYPE, sb.toString());
        }
    }

    private static final class TopologyHandler extends SafeHandler {

        private final JQuickTopology topology;

        TopologyHandler(JQuickTopology topology) {
            this.topology = topology;
        }

        @Override
        void render(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "text/plain; charset=utf-8", topology.render());
        }
    }

    private static final class HealthHandler extends SafeHandler {

        @Override
        void render(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "text/plain; charset=utf-8", "OK\n");
        }
    }

    private static final class ConfigHandler extends SafeHandler {

        private final JQuickConfig config;

        ConfigHandler(JQuickConfig config) {
            this.config = config;
        }

        @Override
        void render(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "text/plain; charset=utf-8", describeRedacted(config));
        }
    }

    private static final class IndexHandler extends SafeHandler {

        @Override
        void render(HttpExchange exchange) throws IOException {
            String path = exchange.getHttpContext().getPath();
            StringBuilder sb = new StringBuilder();
            sb.append("jquick-agent\n\n");
            sb.append("  GET /metrics     Prometheus exposition (meters + topology)\n");
            sb.append("  GET /topology    call graph, human readable\n");
            sb.append("  GET /config      effective configuration, secrets redacted\n");
            sb.append("  GET /health      liveness probe\n");
            if (!"/".equals(path)) {
                sb.append("\n(requested ").append(path).append(")\n");
            }
            respond(exchange, 200, "text/plain; charset=utf-8", sb.toString());
        }
    }
}
