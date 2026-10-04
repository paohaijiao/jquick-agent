package io.github.paohaijiao.jquick.agent.interceptor;

import io.github.paohaijiao.jquick.agent.support.InvocationRecorder;

import java.util.Arrays;

public final class AuditLogInterceptor {

    private AuditLogInterceptor() {
    }

    public static void before(String className, String methodName, Object[] args) {
        InvocationRecorder.record("AUDIT_BEFORE|" + className + "|" + methodName + "|" + Arrays.toString(args));
    }

    public static void after(String className, String methodName, Object result, long elapsedMillis) {
        InvocationRecorder.record("AUDIT_AFTER|" + className + "|" + methodName + "|" + String.valueOf(result) + "|" + elapsedMillis);
    }

    public static void onError(String className, String methodName, Throwable throwable) {
        InvocationRecorder.record("AUDIT_ERROR|" + className + "|" + methodName + "|" + throwable.getClass().getName());
    }
}
