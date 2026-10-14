package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.support.JQuickStringUtil;

import java.util.*;

/**
 * Immutable, typed view over the agent argument string.
 *
 * <p>The configuration is deliberately a flat key/value map rather than a strongly typed settings
 * tree: a Java agent is configured on a command line ({@code -javaagent:...=a=b;c=d}) and one flat
 * namespace is the only shape that survives that medium without a parser generator. Typed accessors
 * ({@link #getBoolean}, {@link #getInt}, {@link #getList}) keep the call sites readable and give a
 * single place where the defaults live.
 *
 * <p>Every key has a documented default. {@link #describe()} renders the effective configuration,
 * which the bootstrap prints once so an operator can always tell what the running agent actually
 * does — a small feature that removes an entire class of "why is this not instrumented?" tickets.
 *
 * @author Martin
 */
public final class JQuickConfig {

    /**
     * general
     */

    /**
     * Comma separated class rules to instrument. Empty means "instrument nothing".
     */
    public static final String INCLUDES = "includes";

    /**
     * Comma separated class rules to skip even when included.
     */
    public static final String EXCLUDES = "excludes";

    /**
     * Comma separated JDK classes that may be instrumented despite the infrastructure filter.
     * The canonical use is {@code java.util.concurrent.ThreadPoolExecutor} for context propagation.
     */
    public static final String ALLOW_JDK = "allowJdk";

    /**
     * Re-run instrumentation on already loaded classes. Mandatory for {@code agentmain}.
     */
    public static final String RETRANSFORM = "retransform";

    /**
     * Suppress all agent diagnostics.
     */
    public static final String QUIET = "quiet";

    /**
     * Enable verbose bootstrap diagnostics.
     */
    public static final String DEBUG = "debug";

    //tracing

    /**
     * Whether the generic method tracer is installed.
     */
    public static final String TRACE_ENABLED = "trace.enabled";

    /**
     * Instrument only classes/methods carrying {@code @JQuickTrace}.
     */
    public static final String TRACE_ANNOTATION_ONLY = "trace.annotation.only";

    /**
     * Capture method arguments into the span.
     */
    public static final String TRACE_ARGS = "trace.args";

    /**
     * Capture return values into the span.
     */
    public static final String TRACE_RESULT = "trace.result";

    /**
     * Capture a (trimmed) stack trace when a method throws.
     */
    public static final String TRACE_ERROR_STACK = "trace.error.stack";

    /**
     * Keep every Nth trace; {@code 1} keeps everything.
     */
    public static final String TRACE_SAMPLER_RATE = "trace.sampler.rate";

    /**
     * Minimum duration, in milliseconds, for a span to be reported as slow.
     */
    public static final String TRACE_SLOW_MS = "trace.slow.ms";

    /**
     * Print one line per span to {@code System.out}.
     */
    public static final String TRACE_PRINT = "trace.print";

    //metrics

    public static final String METRICS_ENABLED = "metrics.enabled";

    /**
     * Port of the built-in Prometheus exposition endpoint; {@code 0} disables the HTTP server.
     */
    public static final String METRICS_PORT = "metrics.port";

    //topology

    public static final String TOPOLOGY_ENABLED = "topology.enabled";

    //component plugins

    public static final String JDBC_ENABLED = "jdbc.enabled";

    public static final String JDBC_INCLUDES = "jdbc.includes";

    public static final String JDBC_SLOW_MS = "jdbc.slow.ms";

    public static final String JDBC_TENANT_ENABLED = "jdbc.tenant.enabled";

    public static final String JDBC_TENANT_COLUMN = "jdbc.tenant.column";

    public static final String HTTP_ENABLED = "http.enabled";

    public static final String HTTP_INCLUDES = "http.includes";

    public static final String CACHE_ENABLED = "cache.enabled";

    public static final String CACHE_INCLUDES = "cache.includes";

    public static final String MQ_ENABLED = "mq.enabled";

    public static final String MQ_INCLUDES = "mq.includes";

    //logging / audit

    public static final String LOG_ENABLED = "log.enabled";

    public static final String LOG_INCLUDES = "log.includes";

    public static final String LOG_MASK_ENABLED = "log.mask.enabled";

    public static final String AUDIT_ENABLED = "audit.enabled";

    public static final String AUDIT_INCLUDES = "audit.includes";

    //security

    public static final String SECURITY_ENABLED = "security.enabled";

    public static final String SECURITY_COMMAND = "security.command.check";

    public static final String SECURITY_REFLECTION = "security.reflection.check";

    public static final String SECURITY_SQL_INJECTION = "security.sql.injection.check";

    public static final String SECURITY_DESERIALIZE = "security.deserialize.check";

    public static final String SECURITY_SQL_LENGTH = "security.sql.max.length";

    //governance

    public static final String GOVERNANCE_ENABLED = "governance.enabled";

    public static final String GOVERNANCE_INCLUDES = "governance.includes";

    public static final String RATE_LIMIT_QPS = "rate.limit.qps";

    public static final String RATE_LIMIT_BURST = "rate.limit.burst";

    public static final String CIRCUIT_FAILURE_RATE = "circuit.failure.rate";

    public static final String CIRCUIT_WINDOW = "circuit.window";

    public static final String CIRCUIT_OPEN_MS = "circuit.open.ms";

    public static final String GOVERNANCE_BLACKLIST = "governance.blacklist";

    public static final String GOVERNANCE_AUTH = "governance.auth";

    //data protection

    public static final String CRYPTO_ENABLED = "crypto.enabled";

    public static final String CRYPTO_INCLUDES = "crypto.includes";

    public static final String CRYPTO_KEY = "crypto.key";

    //diagnostics

    public static final String MOCK_ENABLED = "mock.enabled";

    public static final String THREAD_ENABLED = "thread.enabled";

    public static final String THREAD_POOL_ENABLED = "thread.pool.enabled";

    public static final String THREAD_UNCAUGHT_ENABLED = "thread.uncaught.enabled";

    public static final String COVERAGE_ENABLED = "coverage.enabled";

    public static final String COVERAGE_INCLUDES = "coverage.includes";

    public static final String CLASSLOAD_ENABLED = "classload.enabled";

    public static final String CLASSLOAD_BLACKLIST = "classload.blacklist";

    public static final String CLASSLOAD_CONFLICT = "classload.conflict.detect";

    /**
     * Include rule meaning "every application class, but never the JVM or the agent itself".
     */
    public static final String INCLUDE_ALL = "*";

    private final Map<String, String> values;

    private JQuickConfig(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    public static JQuickConfig empty() {
        return new JQuickConfig(new LinkedHashMap<String, String>());
    }

    /**
     * Normalises the parsed argument map: keys are lower-cased and trimmed.
     */
    public static JQuickConfig of(Map<String, String> raw) {
        Map<String, String> normalized = new LinkedHashMap<String, String>();
        if (raw != null) {
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                if (JQuickStringUtil.isBlank(entry.getKey())) {
                    continue;
                }
                normalized.put(entry.getKey().trim().toLowerCase(),
                        entry.getValue() == null ? "" : entry.getValue().trim());
            }
        }
        return new JQuickConfig(normalized);
    }

    public String get(String key) {
        return key == null ? null : values.get(key.toLowerCase());
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return JQuickStringUtil.isBlank(value) ? defaultValue : value;
    }

    public List<String> getList(String key) {
        return JQuickStringUtil.splitComma(get(key));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = get(key);
        if (JQuickStringUtil.isBlank(raw)) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized)
                || "off".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        try {
            String raw = get(key);
            return JQuickStringUtil.isBlank(raw) ? defaultValue : Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        try {
            String raw = get(key);
            return JQuickStringUtil.isBlank(raw) ? defaultValue : Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            String raw = get(key);
            return JQuickStringUtil.isBlank(raw) ? defaultValue : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Convention helper: {@code <prefix>.enabled}, defaulting to {@code false}.
     *
     * <p>Every capability plugin is off unless switched on. This is the single most important
     * safety property of the framework: installing the agent never changes the behaviour of an
     * application until the operator asks for a specific capability.
     */
    public boolean isPluginEnabled(String prefix) {
        return getBoolean(prefix + ".enabled", false);
    }

    public Set<String> keySet() {
        return values.keySet();
    }

    public Map<String, String> asMap() {
        return values;
    }

    /**
     * Renders the effective configuration with keys sorted so two runs can be diffed easily.
     */
    public String describe() {
        Map<String, String> sorted = new TreeMap<String, String>(values);
        StringBuilder sb = new StringBuilder("JQuickConfig{");
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
