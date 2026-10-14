package io.github.paohaijiao.jquick.agent.plugin.security;

import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocationGate;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.plugin.jdbc.JQuickSqlUtil;

/**
 * Turns the {@link JQuickSecurityGuard} rules into an admission decision.
 *
 * <p>The gate is where RASP becomes enforcement: it runs inside the instrumented call, before the
 * body, with the arguments already captured, and throws to refuse the call. Reusing the arguments the
 * tracing plugin already materialised means RASP costs no additional instrumentation.
 *
 * @author Martin
 */
public final class JQuickSecurityGate implements JQuickInvocationGate {

    @Override
    public boolean requiresArguments() {
        return true;
    }

    @Override
    public void inspect(JQuickInvocation invocation) throws JQuickRejectedException {
        if (!JQuickSecurityGuard.isEnabled()) {
            return;
        }
        Object[] arguments = invocation.getArguments();
        if (arguments == null || arguments.length == 0) {
            return;
        }
        String component = invocation.getComponent();
        if (component != null && component.indexOf("jdbc") >= 0) {
            inspectJdbc(arguments);
            return;
        }
        inspectCommandExecution(invocation, arguments);
        inspectDeserialization(invocation, arguments);
    }

    private void inspectJdbc(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof String && JQuickSqlUtil.looksLikeSql((String) argument)) {
                JQuickSecurityGuard.checkSql((String) argument);
            }
        }
    }

    private void inspectCommandExecution(JQuickInvocation invocation, Object[] arguments) {
        String method = invocation.getMethodName();
        if (method == null) {
            return;
        }
        boolean commandLike = "exec".equals(method) || "start".equals(method)
                || "command".equals(method) || "system".equals(method);
        if (!commandLike) {
            return;
        }
        for (Object argument : arguments) {
            if (argument instanceof String) {
                JQuickSecurityGuard.checkCommand((String) argument);
            }
        }
    }

    private void inspectDeserialization(JQuickInvocation invocation, Object[] arguments) {
        if (!"readObject".equals(invocation.getMethodName())) {
            return;
        }
        for (Object argument : arguments) {
            if (argument instanceof String) {
                JQuickSecurityGuard.checkDeserialize((String) argument);
            }
        }
    }

    @Override
    public String name() {
        return "rasp";
    }
}
