package io.github.paohaijiao.jquick.agent.transformer;

import io.github.paohaijiao.jquick.agent.config.AgentConfiguration;
import io.github.paohaijiao.jquick.agent.enhancer.AuditLogEnhancer;
import io.github.paohaijiao.jquick.agent.enhancer.AutoExportEnhancer;
import io.github.paohaijiao.jquick.agent.enhancer.HttpProxyEnhancer;
import io.github.paohaijiao.jquick.agent.enhancer.MethodEnhancer;
import io.github.paohaijiao.jquick.agent.support.ClassPoolHolder;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class JQuickClassFileTransformer implements ClassFileTransformer {

    private final AgentConfiguration configuration;
    private final List<MethodEnhancer> enhancers;

    public JQuickClassFileTransformer(AgentConfiguration configuration) {
        this(configuration, Arrays.<MethodEnhancer>asList(
                new AutoExportEnhancer(),
                new HttpProxyEnhancer(),
                new AuditLogEnhancer()
        ));
    }

    public JQuickClassFileTransformer(AgentConfiguration configuration, List<MethodEnhancer> enhancers) {
        this.configuration = configuration;
        this.enhancers = enhancers;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        String dottedClassName = className.replace('/', '.');
        if (!configuration.matches(dottedClassName)) {
            return null;
        }
        try {
            CtClass ctClass = ClassPoolHolder.create(loader).makeClass(new ByteArrayInputStream(classfileBuffer));
            if (ctClass.isInterface() || ctClass.isAnnotation() || ctClass.isEnum()) {
                ctClass.detach();
                return null;
            }
            boolean modified = false;
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                for (MethodEnhancer enhancer : enhancers) {
                    if (enhancer.supports(method)) {
                        enhancer.enhance(ctClass, method);
                        modified = true;
                    }
                }
            }
            if (!modified) {
                ctClass.detach();
                return null;
            }
            byte[] byteCode = ctClass.toBytecode();
            ctClass.detach();
            return byteCode;
        } catch (Throwable ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
