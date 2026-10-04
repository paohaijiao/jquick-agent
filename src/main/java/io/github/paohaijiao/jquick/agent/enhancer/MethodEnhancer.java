package io.github.paohaijiao.jquick.agent.enhancer;

import javassist.CtClass;
import javassist.CtMethod;

public interface MethodEnhancer {

    boolean supports(CtMethod method) throws Exception;

    void enhance(CtClass ctClass, CtMethod method) throws Exception;
}
