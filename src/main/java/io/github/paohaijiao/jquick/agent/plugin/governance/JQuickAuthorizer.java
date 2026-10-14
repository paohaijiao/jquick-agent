package io.github.paohaijiao.jquick.agent.plugin.governance;

import io.github.paohaijiao.jquick.agent.core.JQuickPrincipalResolver;

/**
 * Decides whether the caller of an operation is allowed to perform it.
 *
 * <p>The agent cannot know an application's permission model, so authorisation is a pluggable
 * decision rather than a built-in rule. What the agent supplies is the machinery around it: the
 * check runs on the <em>real</em> method, before its body, with no annotation to add and no
 * interceptor to register and no proxy to configure.
 *
 * <pre>{@code
 * JQuickAuthorizer authorizer = new JQuickAuthorizer() {
 *     public boolean isAllowed(String principal, String operation) {
 *         return permissions.get(principal).contains(operation);
 *     }
 *     public String currentPrincipal() {
 *         return SecurityContext.getCurrentUser();
 *     }
 * };
 * }</pre>
 *
 * @author Martin
 */
public interface JQuickAuthorizer extends JQuickPrincipalResolver {

    /**
     * Whether the principal may execute the operation.
     *
     * @param principal the caller, as reported by {@link #currentPrincipal()}
     * @param operation readable method signature
     */
    boolean isAllowed(String principal, String operation);
}
