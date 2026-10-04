package io.github.paohaijiao.jquick.agent.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AgentConfiguration {

    private final List<String> includePackages;

    private AgentConfiguration(List<String> includePackages) {
        this.includePackages = includePackages;
    }

    public static AgentConfiguration parse(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return new AgentConfiguration(Collections.singletonList("io.github.paohaijiao"));
        }
        String[] items = agentArgs.split(";");
        for (String item : items) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && "includes".equalsIgnoreCase(pair[0].trim())) {
                List<String> packages = new ArrayList<String>();
                for (String value : pair[1].split(",")) {
                    String pkg = value.trim();
                    if (!pkg.isEmpty()) {
                        packages.add(pkg);
                    }
                }
                if (!packages.isEmpty()) {
                    return new AgentConfiguration(packages);
                }
            }
        }
        return new AgentConfiguration(Collections.singletonList("io.github.paohaijiao"));
    }

    public boolean matches(String className) {
        if (className == null) {
            return false;
        }
        for (String includePackage : includePackages) {
            if (className.startsWith(includePackage)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getIncludePackages() {
        return Arrays.asList(includePackages.toArray(new String[0]));
    }
}
