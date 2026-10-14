package io.github.paohaijiao.jquick.agent.plugin.security;

import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime application self-protection checks.
 *
 * <h3>What this honestly can and cannot do</h3>
 * RASP is advertised far beyond what any byte-code agent can deliver, so the boundary is stated up
 * front:
 *
 * <ul>
 *   <li><b>It can</b> inspect the arguments of instrumented call sites and refuse the call. SQL
 *       injection is the showcase: the JDBC plugin already captures the statement text, so detecting
 *       a classic injection payload before the driver sees it is both effective and cheap.</li>
 *   <li><b>It can</b> refuse a command before {@code Runtime.exec} runs it — <em>if</em> the JDK class
 *       is instrumentable on the target JVM. {@code java.lang.Runtime} is a bootstrap class and is
 *       usually not retransformable; on HotSpot it can be reached with
 *       {@code -Xbootclasspath/a:} or by instrumenting the application's own wrapper instead. The
 *       declarative matcher is provided; the deployability caveat is real and documented.</li>
 *   <li><b>It cannot</b> be a security boundary on its own. A determined attacker who can execute
 *       arbitrary code in the JVM can remove the agent. RASP raises the cost of an exploit; it does
 *       not replace input validation, least privilege or a WAF.</li>
 * </ul>
 *
 * <p>Every rule is opt-in and every rule can produce a false positive. A blocking RASP rule deployed
 * without a dry-run period is a self-inflicted outage, which is why the defaults are all off and the
 * violations are counted before they are enforced.
 *
 * @author Martin
 */
public final class JQuickSecurityGuard {

    /**
     * Patterns that indicate an attempt to break out of a SQL string literal or inject a predicate.
     */
    private static final List<String> SQL_INJECTION_PATTERNS = Collections.unmodifiableList(Arrays.asList(
            "' or '", "'or'1'='1", "or 1=1", "or 1 = 1", "';--", "\";--",
            "union select", "union all select", "/*!", "xp_cmdshell",
            "waitfor delay", "sleep(", "benchmark(", "load_file(", "into outfile",
            "information_schema.tables", "@@version"));

    /**
     * Characters that must never appear in a command handed to the OS by this process.
     */
    private static final List<String> COMMAND_INJECTION_PATTERNS = Collections.unmodifiableList(Arrays.asList(
            ";", "|", "&&", "||", "`", "$(", ">", "<", "\n", "\r"));

    /**
     * Class names whose deserialisation is a known remote-code-execution primitive.
     */
    private static final List<String> DANGEROUS_DESERIALIZATION_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl",
            "com.sun.org.apache.xalan.internal.xsltc.trax.TrAXFilter",
            "org.apache.commons.collections.functors.InvokerTransformer",
            "org.apache.commons.collections4.functors.InvokerTransformer",
            "org.apache.commons.collections.functors.InstantiateTransformer",
            "javax.management.BadAttributeValueExpException",
            "org.springframework.beans.factory.ObjectFactory",
            "java.rmi.server.UnicastRemoteObject"));

    private static volatile boolean enabled = false;

    private static volatile boolean commandCheck = false;

    private static volatile boolean sqlInjectionCheck = false;

    private static volatile boolean reflectionCheck = false;

    private static volatile boolean deserializeCheck = false;

    private static volatile int maxSqlLength = 100_000;

    private static volatile boolean enforce = true;

    private JQuickSecurityGuard() {
    }

    /**
     * Applies the configuration; called by {@link JQuickSecurityPlugin} during installation.
     */
    public static void configure(JQuickConfig config) {
        enabled = config.getBoolean(JQuickConfig.SECURITY_ENABLED, false);
        commandCheck = config.getBoolean(JQuickConfig.SECURITY_COMMAND, true);
        sqlInjectionCheck = config.getBoolean(JQuickConfig.SECURITY_SQL_INJECTION, true);
        reflectionCheck = config.getBoolean(JQuickConfig.SECURITY_REFLECTION, false);
        deserializeCheck = config.getBoolean(JQuickConfig.SECURITY_DESERIALIZE, true);
        maxSqlLength = config.getInt(JQuickConfig.SECURITY_SQL_LENGTH, 100_000);
        enforce = config.getBoolean("security.enforce", true);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isSqlInjectionCheckEnabled() {
        return enabled && sqlInjectionCheck;
    }

    /**
     * Rejects a statement that looks like an injection attempt or an abnormal payload size.
     */
    public static void checkSql(String sql) {
        if (!enabled || !sqlInjectionCheck || sql == null) {
            return;
        }
        if (sql.length() > maxSqlLength) {
            reject("rasp.sql.length", abbreviate(sql),
                    "statement exceeds the configured maximum length (" + maxSqlLength + ")");
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        for (String pattern : SQL_INJECTION_PATTERNS) {
            if (normalized.contains(pattern)) {
                reject("rasp.sql.injection", abbreviate(sql),
                        "statement matches the injection pattern '" + pattern + "'");
            }
        }
    }

    /**
     * Rejects a command containing shell metacharacters.
     */
    public static void checkCommand(String command) {
        if (!enabled || !commandCheck || command == null) {
            return;
        }
        for (String pattern : COMMAND_INJECTION_PATTERNS) {
            if (command.contains(pattern)) {
                reject("rasp.command", command,
                        "command contains the shell metacharacter '" + pattern + "'");
            }
        }
    }

    /**
     * Allows a whitelist-driven check for reflective invocation.
     */
    public static void checkReflection(String owner, String method, String argument) {
        if (!enabled || !reflectionCheck) {
            return;
        }
        if (argument != null && argument.startsWith("java.lang.Runtime")) {
            reject("rasp.reflection", owner + "#" + method,
                    "reflective access to a process-control class: " + argument);
        }
    }

    /**
     * Rejects the deserialisation of an object graph rooted in a known gadget class.
     */
    public static void checkDeserialize(String className) {
        if (!enabled || !deserializeCheck || className == null) {
            return;
        }
        for (String dangerous : DANGEROUS_DESERIALIZATION_CLASSES) {
            if (dangerous.equals(className)) {
                reject("rasp.deserialize", className,
                        "deserialisation of a known dangerous class: " + className);
            }
        }
    }

    private static void reject(String policy, String subject, String message) {
        if (!enforce) {
            JQuickLogger.warn("[dry-run] " + policy + ": " + message);
            return;
        }
        JQuickLogger.warn("blocked by " + policy + ": " + message);
        throw new JQuickRejectedException(policy, subject, message);
    }

    private static String abbreviate(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160) + "...";
    }
}
