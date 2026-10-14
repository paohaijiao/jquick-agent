package io.github.paohaijiao.jquick.agent.bootstrap;

import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.support.JQuickStringUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parser for the string the JVM passes to {@code premain} / {@code agentmain}.
 *
 * <p>The grammar is deliberately tiny, because the whole string has to survive a shell, a Dockerfile
 * {@code ENTRYPOINT}, a Kubernetes manifest and a Windows shortcut:
 *
 * <pre>
 *   agentArgs := pair (';' pair)*
 *   pair      := key '=' value
 *   value     := item (',' item)*          // lists are comma separated, read back by
 *                                          // JQuickConfig.getList
 * </pre>
 *
 * <p>So {@code -javaagent:jquick-agent.jar=includes=com.acme.*;trace.enabled=true;metrics.port=9100}
 * yields three entries. Commas inside a value are <b>list separators</b>, never pair separators — the
 * alternative (allowing both) makes it impossible to tell a two-element list from a two-key
 * configuration.
 *
 * <h3>{@code @file} form</h3>
 * When the argument starts with {@code @}, the rest is treated as a path to a
 * {@code java.util.Properties} file and loaded instead. Long configurations do not belong on a command
 * line: they are unreadable, they get truncated by process managers, and they cannot be versioned.
 * This is the same convention ByteBuddy's agent uses.
 *
 * <p>Malformed input never aborts the install — a bad pair is logged and skipped, because refusing to
 * start would turn a typo into an outage.
 *
 * @author Martin
 */
public final class JQuickArgumentParser {

    /**
     * Prefix selecting the {@code @file} form.
     */
    public static final String FILE_PREFIX = "@";

    private JQuickArgumentParser() {
    }

    /**
     * Parses {@code agentArgs} into a flat map.
     *
     * @param agentArgs the raw argument string, possibly {@code null} or empty
     * @return a mutable, insertion-ordered map; never {@code null}
     */
    public static Map<String, String> parse(String agentArgs) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (JQuickStringUtil.isBlank(agentArgs)) {
            return values;
        }
        String trimmed = agentArgs.trim();
        if (trimmed.startsWith(FILE_PREFIX)) {
            loadProperties(trimmed.substring(FILE_PREFIX.length()).trim(), values);
            return values;
        }
        for (String pair : trimmed.split(";")) {
            parsePair(pair, values);
        }
        return values;
    }

    private static void parsePair(String rawPair, Map<String, String> target) {
        if (JQuickStringUtil.isBlank(rawPair)) {
            return;
        }
        String pair = rawPair.trim();
        int separator = pair.indexOf('=');
        if (separator <= 0) {
            JQuickLogger.warn("ignoring malformed agent argument (expected key=value): " + pair);
            return;
        }
        String key = pair.substring(0, separator).trim();
        String value = pair.substring(separator + 1).trim();
        if (key.isEmpty()) {
            JQuickLogger.warn("ignoring agent argument with an empty key: " + pair);
            return;
        }
        target.put(key, value);
    }

    /**
     * Reads a {@code .properties} file. Any failure is logged and ignored: a missing configuration
     * file leaves the agent with its safe defaults rather than preventing the JVM from starting.
     */
    private static void loadProperties(String path, Map<String, String> target) {
        File file = new File(path);
        if (!file.isFile()) {
            JQuickLogger.warn("agent argument file not found, using defaults: " + file.getAbsolutePath());
            return;
        }
        InputStream stream = null;
        Reader reader = null;
        try {
            stream = new FileInputStream(file);
            // Properties are ISO-8859-1 by definition; reading them explicitly keeps non-ASCII
            // values from depending on the platform encoding of the day.
            reader = new InputStreamReader(stream, StandardCharsets.ISO_8859_1);
            Properties properties = new Properties();
            properties.load(reader);
            for (String name : properties.stringPropertyNames()) {
                target.put(name, properties.getProperty(name));
            }
            JQuickLogger.debug("loaded " + target.size() + " agent argument(s) from " + file);
        } catch (Throwable ex) {
            JQuickLogger.warn("failed to read agent argument file " + file + ": " + ex);
        } finally {
            close(reader);
            close(stream);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Nothing sensible to do; the file is only read once.
        }
    }

    /**
     * Renders a map back into the {@code key=value;key=value} form, for logging and for the
     * {@code Attach} helper. Keys are sorted so two runs print identically.
     */
    public static String format(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry :
                new java.util.TreeMap<String, String>(values).entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
