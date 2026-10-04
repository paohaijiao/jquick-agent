package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.config.AgentConfiguration;
import org.junit.Assert;
import org.junit.Test;

public class AgentConfigurationTest {

    @Test
    public void shouldParseIncludes() {
        AgentConfiguration configuration = AgentConfiguration.parse("includes=com.demo,io.github.paohaijiao");
        Assert.assertTrue(configuration.matches("com.demo.OrderService"));
        Assert.assertTrue(configuration.matches("io.github.paohaijiao.demo.UserController"));
        Assert.assertFalse(configuration.matches("org.example.Other"));
    }

    @Test
    public void shouldUseDefaultIncludesWhenArgsEmpty() {
        AgentConfiguration configuration = AgentConfiguration.parse(null);
        Assert.assertTrue(configuration.matches("io.github.paohaijiao.sample.Demo"));
    }
}
