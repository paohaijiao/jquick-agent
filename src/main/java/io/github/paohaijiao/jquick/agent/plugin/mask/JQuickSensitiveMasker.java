package io.github.paohaijiao.jquick.agent.plugin.mask;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the personal data an API is not allowed to return in the clear.
 *
 * <p>This is the "mask it on the way out" half of data protection: the value exists in plain form
 * inside the process (where the business logic needs it) and is masked at the boundary the agent
 * selects, so no DAO, DTO or serializer has to know the rule.
 *
 * <p>The patterns cover the four identifiers a Chinese-facing service is normally audited on —
 * mobile number, resident identity card, e-mail address and bank card — and are shape based rather
 * than checksum based: a value that merely <em>looks</em> like an identity card is masked, because
 * the cost of masking a non-sensitive 18-digit number is a slightly less readable log, while the cost
 * of leaking one is a compliance incident.
 *
 * <p>Ordering matters and is handled explicitly: identity cards and bank cards are matched before the
 * mobile pattern, and every pattern is anchored with digit look-around so it cannot match a
 * <em>substring</em> of a longer number.
 *
 * @author Martin
 */
public final class JQuickSensitiveMasker {

    private static final Pattern ID_CARD =
            Pattern.compile("(?<![0-9])([0-9]{17}[0-9Xx])(?![0-9])");

    private static final Pattern BANK_CARD =
            Pattern.compile("(?<![0-9])([0-9]{16,19})(?![0-9])");

    private static final Pattern MOBILE =
            Pattern.compile("(?<![0-9])(1[3-9][0-9]{9})(?![0-9])");

    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-]{1,64})@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private JQuickSensitiveMasker() {
    }

    /**
     * Masks every recognised identifier inside a free-form string.
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        result = replace(result, ID_CARD, 6, 4);
        result = replace(result, BANK_CARD, 4, 4);
        result = replace(result, MOBILE, 3, 4);
        result = maskEmails(result);
        return result;
    }

    /**
     * Masks a mobile number, keeping the first three and the last four digits.
     */
    public static String maskMobile(String value) {
        return value == null ? null : replace(value, MOBILE, 3, 4);
    }

    /**
     * Masks a resident identity card, keeping the first six and the last four characters.
     */
    public static String maskIdCard(String value) {
        return value == null ? null : replace(value, ID_CARD, 6, 4);
    }

    /**
     * Masks an e-mail address, keeping the first character of the local part and the domain.
     */
    public static String maskEmail(String value) {
        return value == null ? null : maskEmails(value);
    }

    private static String replace(String value, Pattern pattern, int keepPrefix, int keepSuffix) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.length() <= keepPrefix + keepSuffix) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(candidate));
                continue;
            }
            StringBuilder replacement = new StringBuilder();
            replacement.append(candidate, 0, keepPrefix);
            for (int i = keepPrefix; i < candidate.length() - keepSuffix; i++) {
                replacement.append('*');
            }
            replacement.append(candidate, candidate.length() - keepSuffix, candidate.length());
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String maskEmails(String value) {
        Matcher matcher = EMAIL.matcher(value);
        StringBuffer buffer = new StringBuffer(value.length());
        while (matcher.find()) {
            String local = matcher.group(1);
            String replacement = local.charAt(0) + "***@" + matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
