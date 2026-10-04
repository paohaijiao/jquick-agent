package io.github.paohaijiao.jquick.agent.interceptor;

import io.github.paohaijiao.jquick.agent.support.InvocationRecorder;

import java.util.Arrays;

public final class HttpProxyInterceptor {

    private HttpProxyInterceptor() {
    }

    public static void before(String className, String methodName, Object[] args) {
        InvocationRecorder.record("HTTP_PROXY_BEFORE|" + className + "|" + methodName + "|" + Arrays.toString(args));
    }

    public static void after(String className, String methodName, Object result, long elapsedMillis) {
        InvocationRecorder.record("HTTP_PROXY_AFTER|" + className + "|" + methodName + "|" + String.valueOf(result) + "|" + elapsedMillis);
    }
}
