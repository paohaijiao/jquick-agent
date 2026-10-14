package io.github.paohaijiao.jquick.agent.core;

/**
 * Thrown by a {@link JQuickInvocationGate} to refuse a call.
 *
 * <p>This is the enforcement mechanism of every "无侵入网关" capability the agent offers: method
 * level rate limiting, circuit breaking, blacklists, authorisation and the runtime application
 * self-protection rules all reject by throwing this exception from the entry hook.
 *
 * <p><b>Semantics.</b> The exception is raised inside the instrumented method, <em>before</em> its
 * first instruction. The application therefore observes a thrown exception from a method it called,
 * exactly as if the method had thrown it itself. The body never runs and — importantly — the
 * application's own {@code try/catch} blocks still behave normally, because the exception travels
 * through the ordinary JVM exception path.
 *
 * <p>Operators should be aware of the consequence: rejecting a call is a <b>behaviour change</b>.
 * Governance capabilities are off by default for exactly this reason.
 *
 * @author Martin
 */
public class JQuickRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String policy;

    private final String subject;

    public JQuickRejectedException(String policy, String subject, String message) {
        super(message);
        this.policy = policy;
        this.subject = subject;
    }

    public JQuickRejectedException(String policy, String subject, String message, Throwable cause) {
        super(message, cause);
        this.policy = policy;
        this.subject = subject;
    }

    /**
     * Name of the rule that rejected the call, e.g. {@code rate-limit} or {@code rasp.command}.
     */
    public String getPolicy() {
        return policy;
    }

    /**
     * What was rejected: a method signature, a command line, a SQL statement, ...
     */
    public String getSubject() {
        return subject;
    }

    @Override
    public String toString() {
        return "JQuickRejectedException[" + policy + "] " + getMessage();
    }
}
