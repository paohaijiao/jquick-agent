package com.demo;

import com.sun.tools.attach.VirtualMachine;

/**
 * Dynamic attach demo (agentmain).
 *
 * <p>Loads {@code jquick-agent.jar} into an already-running JVM identified by its PID. This requires
 * the Attach API: on JDK 8 it lives in {@code ${JAVA_HOME}/lib/tools.jar}, on JDK 9+ it is part of
 * the {@code jdk.attach} module and needs no extra class path entry.
 *
 * <pre>
 *   java -cp demo/target;$JAVA_HOME/lib/tools.jar com.demo.AgentAttachDemo &lt;pid&gt; &lt;agent-jar&gt; [agent-args]
 * </pre>
 *
 * @author Martin
 */
public class AgentAttachDemo {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AgentAttachDemo <pid> <agent-jar> [agent-args]");
            System.err.println("example: AgentAttachDemo 12345 /path/jquick-agent-1.0.0.jar includes=com.demo");
            System.exit(2);
        }
        String pid = args[0];
        String agentJar = args[1];
        String agentArgs = args.length > 2 ? args[2] : "includes=com.demo";

        System.out.println("[attach] attaching to pid " + pid + " with agent args: " + agentArgs);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agentJar, agentArgs);
        } finally {
            vm.detach();
        }
        System.out.println("[attach] agent loaded, the target JVM now reports [jquick-agent] events");
    }
}
