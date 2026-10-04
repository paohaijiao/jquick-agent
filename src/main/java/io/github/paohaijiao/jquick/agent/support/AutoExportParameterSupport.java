package io.github.paohaijiao.jquick.agent.support;

import javassist.CtClass;
import javassist.CtMethod;

public final class AutoExportParameterSupport {

    private AutoExportParameterSupport() {
    }

    public static Class<?>[] resolveParameterTypes(CtMethod method) throws Exception {
        CtClass[] parameterTypes = method.getParameterTypes();
        Class<?>[] resolved = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            resolved[i] = toJavaClass(parameterTypes[i]);
        }
        return resolved;
    }

    private static Class<?> toJavaClass(CtClass ctClass) throws Exception {
        if (CtClass.booleanType.equals(ctClass)) {
            return boolean.class;
        }
        if (CtClass.byteType.equals(ctClass)) {
            return byte.class;
        }
        if (CtClass.charType.equals(ctClass)) {
            return char.class;
        }
        if (CtClass.doubleType.equals(ctClass)) {
            return double.class;
        }
        if (CtClass.floatType.equals(ctClass)) {
            return float.class;
        }
        if (CtClass.intType.equals(ctClass)) {
            return int.class;
        }
        if (CtClass.longType.equals(ctClass)) {
            return long.class;
        }
        if (CtClass.shortType.equals(ctClass)) {
            return short.class;
        }
        if (CtClass.voidType.equals(ctClass)) {
            return void.class;
        }
        return Class.forName(ctClass.getName());
    }
}
