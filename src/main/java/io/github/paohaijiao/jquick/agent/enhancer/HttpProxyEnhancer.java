package io.github.paohaijiao.jquick.agent.enhancer;

import io.github.paohaijiao.jquick.agent.annotation.HttpProxy;
import javassist.CtClass;
import javassist.CtMethod;

public class HttpProxyEnhancer implements MethodEnhancer {

    @Override
    public boolean supports(CtMethod method) throws Exception {
        return method.hasAnnotation(HttpProxy.class);
    }

    @Override
    public void enhance(CtClass ctClass, CtMethod method) throws Exception {
        method.addLocalVariable("_proxy_start", CtClass.longType);
        method.insertBefore(
                "{ _proxy_start = System.currentTimeMillis();" +
                        " io.github.paohaijiao.jquick.agent.interceptor.HttpProxyInterceptor.before(" +
                        "$class.getName(), \"" + method.getName() + "\", $args); }"
        );
        if (!CtClass.voidType.equals(method.getReturnType())) {
            method.insertAfter(
                    "{ io.github.paohaijiao.jquick.agent.interceptor.HttpProxyInterceptor.after(" +
                            "$class.getName(), \"" + method.getName() + "\", ($w)$_, System.currentTimeMillis() - _proxy_start); }"
            );
        } else {
            method.insertAfter(
                    "{ io.github.paohaijiao.jquick.agent.interceptor.HttpProxyInterceptor.after(" +
                            "$class.getName(), \"" + method.getName() + "\", null, System.currentTimeMillis() - _proxy_start); }"
            );
        }
    }
}
