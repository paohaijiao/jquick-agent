package io.github.paohaijiao.jquick.agent.plugin.jdbc;

import io.github.paohaijiao.jquick.agent.core.JQuickArgumentTransformer;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;

/**
 * Applies a {@link JQuickSqlRewriter} to the statement argument of a JDBC call.
 *
 * <p>Registered as an argument transformer rather than as a listener, because the rewrite has to
 * happen <em>before</em> the driver executes the statement and has to be visible to the method body —
 * which is exactly the difference between observation and interception.
 *
 * <p>The transformer only touches invocations whose component contains {@code jdbc}. Argument
 * transformers are global by design (a plugin should not have to know the full byte-code plan), so
 * each implementation is responsible for declaring the domain it acts on.
 *
 * @author Martin
 */
public final class JQuickJdbcArgumentTransformer implements JQuickArgumentTransformer {

    private final JQuickSqlRewriter rewriter;

    public JQuickJdbcArgumentTransformer(JQuickSqlRewriter rewriter) {
        this.rewriter = rewriter;
    }

    public JQuickSqlRewriter getRewriter() {
        return rewriter;
    }

    @Override
    public Object[] transform(JQuickInvocation invocation, Object[] arguments) {
        if (arguments == null) {
            return arguments;
        }
        String component = invocation.getComponent();
        if (component == null || component.indexOf("jdbc") < 0) {
            return arguments;
        }
        for (int i = 0; i < arguments.length; i++) {
            Object candidate = arguments[i];
            if (!(candidate instanceof String)) {
                continue;
            }
            String sql = (String) candidate;
            if (!JQuickSqlUtil.looksLikeSql(sql)) {
                continue;
            }
            String rewritten = rewriter.rewrite(sql, JQuickTenantContext.get());
            if (rewritten != null && !rewritten.equals(sql)) {
                Object[] replacement = arguments.clone();
                replacement[i] = rewritten;
                return replacement;
            }
            return arguments;
        }
        return arguments;
    }

    @Override
    public String name() {
        return rewriter.name();
    }
}
