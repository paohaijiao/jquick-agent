package io.github.paohaijiao.jquick.agent.plugin.mask;

import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickResultTransformer;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;

import java.util.*;

/**
 * Masks sensitive data in the return value of the selected methods.
 *
 * <p>Supported carriers, chosen because they cover the overwhelming majority of real payloads
 * without the agent having to understand an application's domain model:
 *
 * <ul>
 *   <li>{@code String} — the value itself is masked;</li>
 *   <li>{@code Map} — every {@code String} value is masked, keys are left alone;</li>
 *   <li>{@code List} / {@code Object[]} — every {@code String} element is masked;</li>
 *   <li>nested combinations of the three, to a bounded depth.</li>
 * </ul>
 *
 * <p><b>What it does not do.</b> A POJO's fields are not walked. Doing so would require reflective
 * field access on every return value — expensive, fragile with modules, and it would break
 * immutability contracts by mutating shared objects. Applications that return typed DTOs should
 * annotate the fields and mask them in the serializer, or expose a masked view; the agent masks the
 * boundary, not the model.
 *
 * <p><b>Why there is no configuration knob.</b> Masking is pattern based
 * ({@link JQuickSensitiveMasker}) and therefore lossless for text that carries no sensitive value: a
 * message that happens to contain no phone number, identity number, card number or e-mail address
 * comes back byte for byte identical. There is consequently no policy to tune — "mask" and "mask
 * harder" would be the same operation. An operator who needs a specific class to return raw values
 * simply leaves it out of {@code mask.includes}; that is a selection decision, and selection belongs
 * to the class matcher rather than to this transformer.
 *
 * <p><b>Why it filters by class itself.</b> Result transformers are dispatched to every instrumented
 * call, so a transformer that relied on the caller having been selected would act on classes another
 * capability selected. With encryption enabled for one package and masking for another, both would
 * run on both. Each transformer therefore owns its own selection, using the class name carried by the
 * {@link JQuickInvocation}.
 *
 * @author Martin
 */
public final class JQuickSensitiveTransformer implements JQuickResultTransformer {

    /**
     * Bounds the recursive walk so a cyclic or pathologically deep structure cannot hang a request.
     */
    private static final int MAX_DEPTH = 4;

    private final List<String> classPatterns;

    /**
     * @param classPatterns the classes this transformer owns; an empty list masks nothing, so a
     *                      plugin that lost its configuration is inert rather than global
     */
    public JQuickSensitiveTransformer(List<String> classPatterns) {
        this.classPatterns = classPatterns == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(classPatterns));
    }

    public List<String> getClassPatterns() {
        return classPatterns;
    }

    @Override
    public Object transform(JQuickInvocation invocation, Object result) {
        if (result == null) {
            return null;
        }
        if (invocation == null
                || !JQuickClassFilters.matchesAnyPattern(invocation.getClassName(), classPatterns)) {
            return result;
        }
        return maskValue(result, 0);
    }

    private Object maskValue(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof String) {
            return JQuickSensitiveMasker.mask((String) value);
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<Object, Object> masked = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                masked.put(entry.getKey(), maskValue(entry.getValue(), depth + 1));
            }
            return masked;
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> masked = new ArrayList<Object>(source.size());
            for (Object element : source) {
                masked.add(maskValue(element, depth + 1));
            }
            return masked;
        }
        if (value instanceof Object[]) {
            Object[] source = (Object[]) value;
            Object[] masked = new Object[source.length];
            for (int i = 0; i < source.length; i++) {
                masked[i] = maskValue(source[i], depth + 1);
            }
            return masked;
        }
        return value;
    }

    @Override
    public String name() {
        return "sensitive-masker";
    }
}
