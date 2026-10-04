package io.github.paohaijiao.jquick.agent.enhancer;

import io.github.paohaijiao.jquick.agent.annotation.AutoExport;
import io.github.paohaijiao.jquick.agent.support.AutoExportParameterSupport;
import javassist.CtClass;
import javassist.CtMethod;

public class AutoExportEnhancer implements MethodEnhancer {

    @Override
    public boolean supports(CtMethod method) throws Exception {
        return method.hasAnnotation(AutoExport.class);
    }

    @Override
    public void enhance(CtClass ctClass, CtMethod method) throws Exception {
        if (CtClass.voidType.equals(method.getReturnType())) {
            return;
        }
        Class<?>[] parameterTypes = AutoExportParameterSupport.resolveParameterTypes(method);
        String parameterLiteral = buildParameterLiteral(parameterTypes);
        method.insertAfter(
                "{ io.github.paohaijiao.jquick.agent.interceptor.AutoExportInterceptor.afterReturn(" +
                        "$class.getName(), \"" + method.getName() + "\", " + parameterLiteral + ", ($w)$_); }"
        );
    }

    private String buildParameterLiteral(Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("new Class[]{");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.isPrimitive()) {
                builder.append(parameterType.getName()).append(".class");
            } else {
                builder.append(parameterType.getName()).append(".class");
            }
        }
        builder.append('}');
        return builder.toString();
    }
}
