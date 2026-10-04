package io.github.paohaijiao.jquick.agent.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public final class AnnotationUtils {

    private AnnotationUtils() {
    }

    public static <A extends Annotation> A findMethodAnnotation(String className,
                                                                 String methodName,
                                                                 Class<?>[] parameterTypes,
                                                                 Class<A> annotationType) {
        try {
            Class<?> targetClass = Class.forName(className);
            Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
            return method.getAnnotation(annotationType);
        } catch (Exception ex) {
            return null;
        }
    }
}
