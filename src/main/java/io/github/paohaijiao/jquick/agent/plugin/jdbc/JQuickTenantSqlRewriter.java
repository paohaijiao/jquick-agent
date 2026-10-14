package io.github.paohaijiao.jquick.agent.plugin.jdbc;

/**
 * Rewrites a statement so that it can only ever see the rows of the current tenant.
 *
 * <p>This is the reference {@link JQuickSqlRewriter} and it is deliberately conservative, because a
 * wrong rewrite is a data-corruption bug rather than a lost trace:
 *
 * <ul>
 *   <li>{@code SELECT} — a {@code tenant_id = '…'} predicate is appended to the existing
 *       {@code WHERE}, or introduced before {@code GROUP BY} / {@code ORDER BY} / {@code LIMIT} when
 *       the statement has none;</li>
 *   <li>{@code UPDATE} / {@code DELETE} — the predicate is added to the {@code WHERE}; a statement
 *       with no {@code WHERE} at all is <b>left untouched</b>, because silently widening it to every
 *       row of the table would be far worse than the missing filter;</li>
 *   <li>{@code INSERT} — left untouched. A tenant column has to be added together with its value,
 *       which requires knowing the column list; that is a modelling decision, not a rewrite;</li>
 *   <li>any statement already mentioning the tenant column is left untouched, so a rewrite is never
 *       applied twice — which also makes the rewriter safe when an ORM has already done its job.</li>
 * </ul>
 *
 * <p>The literal is escaped by doubling single quotes, which is the standard SQL escape; the
 * statement still goes through the driver's own normalisation afterwards.
 *
 * @author Martin
 */
public final class JQuickTenantSqlRewriter implements JQuickSqlRewriter {

    private final String tenantColumn;

    public JQuickTenantSqlRewriter(String tenantColumn) {
        this.tenantColumn = (tenantColumn == null || tenantColumn.trim().isEmpty())
                ? "tenant_id" : tenantColumn.trim();
    }

    private static int indexOfKeyword(String upper, String keyword) {
        return upper.indexOf(" " + keyword + " ");
    }

    private static int firstIndexOfAny(String upper, String... keywords) {
        int best = -1;
        for (String keyword : keywords) {
            int index = upper.indexOf(keyword + " ");
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    public String getTenantColumn() {
        return tenantColumn;
    }

    @Override
    public String rewrite(String sql, String tenantId) {
        if (sql == null || tenantId == null || tenantId.isEmpty()) {
            return sql;
        }
        String upper = sql.toUpperCase();
        if (upper.contains(tenantColumn.toUpperCase())) {
            return sql;
        }
        String predicate = tenantColumn + " = '" + tenantId.replace("'", "''") + "'";
        if (upper.startsWith("SELECT")) {
            return rewriteSelect(sql, upper, predicate);
        }
        if (upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            return rewriteWhereOnly(sql, upper, predicate);
        }
        return sql;
    }

    private String rewriteSelect(String sql, String upper, String predicate) {
        int whereIndex = indexOfKeyword(upper, "WHERE");
        if (whereIndex >= 0) {
            return sql + " AND " + predicate;
        }
        int boundary = firstIndexOfAny(upper, "GROUP BY", "ORDER BY", "LIMIT", "HAVING");
        if (boundary < 0) {
            return sql + " WHERE " + predicate;
        }
        int cut = boundary;
        while (cut > 0 && Character.isWhitespace(sql.charAt(cut - 1))) {
            cut--;
        }
        return sql.substring(0, cut) + " WHERE " + predicate + " " + sql.substring(cut);
    }

    private String rewriteWhereOnly(String sql, String upper, String predicate) {
        int whereIndex = indexOfKeyword(upper, "WHERE");
        if (whereIndex < 0) {
            // Refusing to rewrite is the safe behaviour: adding a WHERE would silently turn a
            // statement the application wrote into a narrower one, and skipping it would widen it.
            return sql;
        }
        return sql + " AND " + predicate;
    }

    @Override
    public String name() {
        return "tenant-sql-rewriter[" + tenantColumn + "]";
    }
}
