package io.github.paohaijiao.jquick.agent.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Small string helpers shared by the bootstrap, the matchers and the plugins.
 *
 * <p>Kept dependency free on purpose: the agent is loaded into arbitrary applications, so it must
 * not pull in Guava, commons-lang or any other third party utility library.
 *
 * @author Martin
 */
public final class JQuickStringUtil {

    private JQuickStringUtil() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * Returns the value when it is not blank, otherwise the fallback.
     */
    public static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    /**
     * Splits a comma separated list, trimming every item and dropping the empty ones.
     */
    public static List<String> splitComma(String value) {
        List<String> items = new ArrayList<String>();
        if (isBlank(value)) {
            return items;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * Converts an internal class name to its dotted form; {@code null} safe.
     */
    public static String toDottedName(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    /**
     * Converts a dotted class name to its internal form; {@code null} safe.
     */
    public static String toInternalName(String dottedName) {
        return dottedName == null ? null : dottedName.replace('.', '/');
    }

    /**
     * Shortens a dotted type name for display, keeping at most the last {@code keep} segments.
     */
    public static String abbreviateClassName(String className, int keep) {
        if (className == null) {
            return "?";
        }
        String[] parts = className.split("\\.");
        if (parts.length <= keep) {
            return className;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - keep; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Truncates a value for logging so a huge payload cannot flood the log stream.
     */
    public static String abbreviateValue(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(" + value.length() + " chars)";
    }

    /**
     * Matches a class name against a simple glob pattern supporting {@code *} and {@code ?}.
     */
    public static boolean wildcardMatch(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        return wildcardMatch(pattern, 0, value, 0);
    }

    private static boolean wildcardMatch(String pattern, int pi, String value, int vi) {
        while (pi < pattern.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // Collapse consecutive wildcards, then try every remaining split point.
                while (pi < pattern.length() && pattern.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == pattern.length()) {
                    return true;
                }
                for (int skip = vi; skip <= value.length(); skip++) {
                    if (wildcardMatch(pattern, pi, value, skip)) {
                        return true;
                    }
                }
                return false;
            }
            if (vi >= value.length()) {
                return false;
            }
            if (pc != '?' && pc != value.charAt(vi)) {
                return false;
            }
            pi++;
            vi++;
        }
        return vi == value.length();
    }
}
