package io.github.paohaijiao.jquick.agent.interceptor;

import io.github.paohaijiao.jquick.agent.annotation.AutoExport;
import io.github.paohaijiao.jquick.agent.support.AnnotationUtils;
import io.github.paohaijiao.jquick.agent.support.InvocationRecorder;

public final class AutoExportInterceptor {

    private AutoExportInterceptor() {
    }

    public static void afterReturn(String className, String methodName, Class<?>[] parameterTypes, Object result) {
        if (result == null) {
            return;
        }
        AutoExport autoExport = AnnotationUtils.findMethodAnnotation(className, methodName, parameterTypes, AutoExport.class);
        if (autoExport == null) {
            return;
        }
        InvocationRecorder.record("AUTO_EXPORT|" + autoExport.fileName() + "|" + autoExport.sheetName() + "|" + String.valueOf(result));
    }
}
