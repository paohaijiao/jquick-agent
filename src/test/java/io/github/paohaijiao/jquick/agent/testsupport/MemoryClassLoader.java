package io.github.paohaijiao.jquick.agent.testsupport;

public class MemoryClassLoader extends ClassLoader {

    public MemoryClassLoader(ClassLoader parent) {
        super(parent);
    }

    public Class<?> define(String className, byte[] byteCode) {
        return defineClass(className, byteCode, 0, byteCode.length);
    }
}
