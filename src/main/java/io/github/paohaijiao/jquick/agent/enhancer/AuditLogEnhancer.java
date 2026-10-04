package io.github.paohaijiao.jquick.agent.enhancer;

import io.github.paohaijiao.jquick.agent.annotation.AuditLog;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class AuditLogEnhancer implements MethodEnhancer {

    @Override
    public boolean supports(CtMethod method) throws Exception {
        return method.hasAnnotation(AuditLog.class);
    }

    @Override
    public void enhance(CtClass ctClass, CtMethod method) throws Exception {
        method.addLocalVariable("_audit_start", CtClass.longType);
        method.insertBefore(
                "{ _audit_start = System.currentTimeMillis();" +
                        " io.github.paohaijiao.jquick.agent.interceptor.AuditLogInterceptor.before(" +
                        "$class.getName(), \"" + method.getName() + "\", $args); }"
        );
        if (!CtClass.voidType.equals(method.getReturnType())) {
            method.insertAfter(
                    "{ io.github.paohaijiao.jquick.agent.interceptor.AuditLogInterceptor.after(" +
                            "$class.getName(), \"" + method.getName() + "\", ($w)$_, System.currentTimeMillis() - _audit_start); }"
            );
        } else {
            method.insertAfter(
                    "{ io.github.paohaijiao.jquick.agent.interceptor.AuditLogInterceptor.after(" +
                            "$class.getName(), \"" + method.getName() + "\", null, System.currentTimeMillis() - _audit_start); }"
            );
        }
        method.addCatch(
                "{ io.github.paohaijiao.jquick.agent.interceptor.AuditLogInterceptor.onError(" +
                        "$class.getName(), \"" + method.getName() + "\", $e); throw $e; }",
                ClassPool.getDefault().get("java.lang.Throwable")
        );
    }
}
