package io.github.paohaijiao.jquick.agent.core;

import com.jquick.asm.core.JQuickAnnotationInfo;
import com.jquick.asm.core.JQuickClassInfo;
import com.jquick.asm.core.JQuickFieldInfo;
import com.jquick.asm.core.JQuickMethodInfo;
import com.jquick.asm.reader.JQuickClassReaderTool;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.support.JQuickStringUtil;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only, framework-neutral view over one class's metadata.
 *
 * <p>Wraps jquick-asm's {@link JQuickClassInfo} so the matchers and the plugins never touch an ASM
 * type directly. All the questions the agent asks about a class — <em>does it extend a servlet?</em>,
 * <em>does it carry {@code @JQuickTrace}?</em>, <em>is it an interface?</em> — are answered here from
 * a single parse.
 *
 * @author Martin
 */
public final class JQuickClassMeta {

    private final JQuickClassInfo info;

    private JQuickClassMeta(JQuickClassInfo info) {
        this.info = info;
    }

    /**
     * Parses class bytes. Returns {@code null} when the bytes cannot be read, so a malformed class
     * can never abort the agent.
     */
    public static JQuickClassMeta of(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return null;
        }
        try {
            return new JQuickClassMeta(JQuickClassReaderTool.read(classBytes));
        } catch (Throwable ex) {
            JQuickLogger.suppressed("class metadata parsing failed", ex);
            return null;
        }
    }

    public String getClassName() {
        return info.getClassName();
    }

    public String getInternalName() {
        return info.getInternalName();
    }

    public int getAccess() {
        return info.getAccess();
    }

    /**
     * Direct superclass as a dotted name, or {@code null} for {@code java.lang.Object}.
     */
    public String getSuperClassName() {
        return JQuickStringUtil.toDottedName(info.getSuperName());
    }

    /**
     * Directly implemented interfaces as dotted names.
     */
    public List<String> getInterfaceNames() {
        List<String> names = new ArrayList<String>();
        for (String internal : info.getInterfaces()) {
            names.add(JQuickStringUtil.toDottedName(internal));
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Class annotations as dotted names.
     */
    public List<String> getAnnotationNames() {
        List<String> names = new ArrayList<String>();
        for (JQuickAnnotationInfo annotation : info.getAnnotations()) {
            names.add(annotation.getClassName());
        }
        return names;
    }

    public List<JQuickMethodInfo> getMethods() {
        return info.getMethods();
    }

    public List<JQuickFieldInfo> getFields() {
        return info.getFields();
    }

    public boolean isInterface() {
        return (info.getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isAnnotation() {
        return (info.getAccess() & Opcodes.ACC_ANNOTATION) != 0;
    }

    public boolean isEnum() {
        return (info.getAccess() & Opcodes.ACC_ENUM) != 0;
    }

    /**
     * Whether the class declares the annotation itself.
     */
    public boolean hasAnnotation(String dottedAnnotationName) {
        return getAnnotationNames().contains(dottedAnnotationName);
    }

    /**
     * Whether the class or any of its methods declares the annotation.
     */
    public boolean hasAnnotationAnywhere(String dottedAnnotationName) {
        if (hasAnnotation(dottedAnnotationName)) {
            return true;
        }
        for (JQuickMethodInfo method : info.getMethods()) {
            if (methodHasAnnotation(method, dottedAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the given method declares the annotation.
     */
    public boolean methodHasAnnotation(JQuickMethodInfo method, String dottedAnnotationName) {
        if (method == null || method.getAnnotations() == null) {
            return false;
        }
        for (JQuickAnnotationInfo annotation : method.getAnnotations()) {
            if (dottedAnnotationName.equals(annotation.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public JQuickMethodInfo findMethod(String name, String descriptor) {
        return info.findMethod(name, descriptor);
    }

    /**
     * Whether a class with no method body (interface, annotation, enum shell) should be skipped by
     * the enhancers.
     */
    public boolean hasModifiableBody() {
        return !isInterface() && !isAnnotation() && !info.getMethods().isEmpty();
    }

    @Override
    public String toString() {
        return "JQuickClassMeta{" + getClassName() + ", super=" + getSuperClassName()
                + ", interfaces=" + getInterfaceNames() + '}';
    }
}
