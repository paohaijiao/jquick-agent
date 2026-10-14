package io.github.paohaijiao.jquick.agent.core;

/**
 * Functional family a plugin belongs to.
 *
 * <p>Categories exist for two practical reasons: an operator can enable or silence a whole family at
 * once, and the bootstrap can report "3 tracing plugins, 1 security plugin" instead of a bare list,
 * which is what makes an inventory of a running agent actually readable.
 *
 * @author Martin
 */
public enum JQuickCategory {

    /**
     * Distributed tracing: spans, trace ids, topology.
     */
    TRACING,

    /**
     * Metrics: counters, timers, gauges, Prometheus exposition.
     */
    METRICS,

    /**
     * Logging: trace id injection, structured output, sensitive-data masking.
     */
    LOGGING,

    /**
     * Runtime application self-protection and audit.
     */
    SECURITY,

    /**
     * Admission control: rate limiting, circuit breaking, blacklists, authorisation.
     */
    GOVERNANCE,

    /**
     * Data protection: encryption, decryption, desensitisation.
     */
    DATA,

    /**
     * Developer diagnostics: mocking, coverage, hot fix, class-loading insight.
     */
    DIAGNOSTIC,

    /**
     * Framework and middleware integration (JDBC, HTTP, cache, MQ).
     */
    INTEGRATION,

    /**
     * Components the framework needs to function; cannot be switched off.
     */
    INFRASTRUCTURE
}
