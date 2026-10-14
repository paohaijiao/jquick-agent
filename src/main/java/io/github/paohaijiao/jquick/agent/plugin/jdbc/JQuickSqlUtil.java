package io.github.paohaijiao.jquick.agent.plugin.jdbc;

/**
 * Statement classification helpers.
 *
 * <p>A SQL string arriving through an instrumented call is just a {@code String}. Deciding whether it
 * is a statement at all — and which kind — is what lets the JDBC plugin avoid counting arbitrary
 * strings, and what lets the RASP plugin inspect only the statements worth inspecting.
 *
 * <p>The analysis is textual and intentionally simple: the agent sees the statement before the driver
 * parses it, and a full SQL parser would be both far too heavy for an instrumentation path and a
 * maintenance liability.
 *
 * @author Martin
 */
public final class JQuickSqlUtil {

    private JQuickSqlUtil() {
    }

    /**
     * Whether the text plausibly is a SQL statement.
     */
    public static boolean looksLikeSql(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim();
        if (normalized.length() < 6) {
            return false;
        }
        String upper = normalized.toUpperCase();
        return upper.startsWith("SELECT")
                || upper.startsWith("INSERT")
                || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE")
                || upper.startsWith("MERGE")
                || upper.startsWith("REPLACE")
                || upper.startsWith("CALL")
                || upper.startsWith("{CALL")
                || upper.startsWith("WITH");
    }

    /**
     * Leading keyword of the statement, upper-cased ({@code SELECT}, {@code UPDATE}, ...), or
     * {@code UNKNOWN}.
     */
    public static String statementType(String sql) {
        if (sql == null) {
            return "UNKNOWN";
        }
        String trimmed = sql.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))
                && trimmed.charAt(end) != '(') {
            end++;
        }
        if (end == 0) {
            return "UNKNOWN";
        }
        return trimmed.substring(0, end).toUpperCase();
    }

    /**
     * Collapses whitespace runs into a single space and trims. Used for logging and for compact
     * metric labels, never for execution.
     */
    public static String normalize(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.trim().replaceAll("\\s+", " ");
    }

    /**
     * Extracts the first table name of a statement, or {@code null} when it cannot be determined
     * cheaply. Used only for metric labels.
     */
    public static String firstTable(String sql) {
        if (sql == null) {
            return null;
        }
        String upper = sql.toUpperCase();
        String[] anchors = {"FROM ", "INTO ", "UPDATE ", "JOIN "};
        for (String anchor : anchors) {
            int index = upper.indexOf(anchor);
            if (index < 0) {
                continue;
            }
            int start = index + anchor.length();
            while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < sql.length()) {
                char c = sql.charAt(end);
                if (Character.isWhitespace(c) || c == '(' || c == ',' || c == ';') {
                    break;
                }
                end++;
            }
            if (end > start) {
                return sql.substring(start, end);
            }
        }
        return null;
    }

    /**
     * Truncates a statement for a log line or a metric label.
     */
    public static String abbreviate(String sql, int maxLength) {
        if (sql == null) {
            return null;
        }
        String normalized = normalize(sql);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
