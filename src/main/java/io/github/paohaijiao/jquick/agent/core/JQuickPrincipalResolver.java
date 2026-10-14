package io.github.paohaijiao.jquick.agent.core;

/**
 * Resolves the caller of the current operation.
 *
 * <p>Three capabilities need to answer "who is doing this", and none of them can answer it alone:
 * authorisation decides whether the caller may proceed, audit records who did what, and metrics
 * need it as a label. Every application resolves a principal differently — a Spring
 * {@code SecurityContext}, an inbound token, a request-scoped bean, a thread-local set by a filter —
 * so the resolution is pluggable and the rest of the framework only consumes the result.
 *
 * @author Martin
 */
public interface JQuickPrincipalResolver {

    /**
     * The caller of the current request, or {@code null} when anonymous.
     */
    String currentPrincipal();
}
