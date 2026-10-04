package io.github.paohaijiao.jquick.agent.support;

import javassist.ClassPool;
import javassist.LoaderClassPath;

public final class ClassPoolHolder {

    private ClassPoolHolder() {
    }

    public static ClassPool create(ClassLoader classLoader) {
        ClassPool classPool = new ClassPool(true);
        if (classLoader != null) {
            classPool.insertClassPath(new LoaderClassPath(classLoader));
        }
        return classPool;
    }
}
