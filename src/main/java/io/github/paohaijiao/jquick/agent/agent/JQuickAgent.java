package io.github.paohaijiao.jquick.agent.agent;

import io.github.paohaijiao.jquick.agent.config.AgentConfiguration;
import io.github.paohaijiao.jquick.agent.transformer.JQuickClassFileTransformer;

import java.lang.instrument.Instrumentation;

public final class JQuickAgent {

    private JQuickAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        bootstrap(agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        bootstrap(agentArgs, instrumentation);
    }

    private static void bootstrap(String agentArgs, Instrumentation instrumentation) {
        AgentConfiguration configuration = AgentConfiguration.parse(agentArgs);
        JQuickClassFileTransformer transformer = new JQuickClassFileTransformer(configuration);
        instrumentation.addTransformer(transformer, true);
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!instrumentation.isModifiableClass(loadedClass)) {
                continue;
            }
            if (!configuration.matches(loadedClass.getName())) {
                continue;
            }
            try {
                instrumentation.retransformClasses(loadedClass);
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
    }
}
