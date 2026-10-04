package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.config.AgentConfiguration;
import io.github.paohaijiao.jquick.agent.fixture.DemoService;
import io.github.paohaijiao.jquick.agent.support.InvocationRecorder;
import io.github.paohaijiao.jquick.agent.testsupport.BytecodeTestUtils;
import io.github.paohaijiao.jquick.agent.testsupport.MemoryClassLoader;
import io.github.paohaijiao.jquick.agent.transformer.JQuickClassFileTransformer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

public class JQuickClassFileTransformerTest {

    @Before
    public void setUp() {
        InvocationRecorder.clear();
    }

    @Test
    public void shouldEnhanceAnnotatedMethods() throws Exception {
        byte[] original = BytecodeTestUtils.readClassBytes(DemoService.class);
        JQuickClassFileTransformer transformer = new JQuickClassFileTransformer(AgentConfiguration.parse("includes=io.github.paohaijiao.jquick.agent.fixture"));
        byte[] transformed = transformer.transform(DemoService.class.getClassLoader(),
                "io/github/paohaijiao/jquick/agent/fixture/DemoService",
                null,
                null,
                original);

        Assert.assertNotNull(transformed);

        MemoryClassLoader classLoader = new MemoryClassLoader(DemoService.class.getClassLoader());
        Class<?> enhancedClass = classLoader.define(DemoService.class.getName(), transformed);
        Object target = enhancedClass.getDeclaredConstructor().newInstance();

        Method exportUsers = enhancedClass.getMethod("exportUsers");
        Method findUser = enhancedClass.getMethod("findUser", String.class);
        Method proxy = enhancedClass.getMethod("proxy", String.class);

        exportUsers.invoke(target);
        findUser.invoke(target, "1001");
        proxy.invoke(target, "payload");

        List<String> events = InvocationRecorder.snapshot();
        Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("AUTO_EXPORT|users|sheetA|[alice, bob]")));
        Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("AUDIT_BEFORE|io.github.paohaijiao.jquick.agent.fixture.DemoService|findUser")));
        Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("AUDIT_AFTER|io.github.paohaijiao.jquick.agent.fixture.DemoService|findUser|user-1001|")));
        Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("HTTP_PROXY_BEFORE|io.github.paohaijiao.jquick.agent.fixture.DemoService|proxy")));
        Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("HTTP_PROXY_AFTER|io.github.paohaijiao.jquick.agent.fixture.DemoService|proxy|proxy-payload|")));
    }

    @Test
    public void shouldRecordErrorForAuditMethod() throws Exception {
        byte[] original = BytecodeTestUtils.readClassBytes(DemoService.class);
        JQuickClassFileTransformer transformer = new JQuickClassFileTransformer(AgentConfiguration.parse("includes=io.github.paohaijiao.jquick.agent.fixture"));
        byte[] transformed = transformer.transform(DemoService.class.getClassLoader(),
                "io/github/paohaijiao/jquick/agent/fixture/DemoService",
                null,
                null,
                original);

        MemoryClassLoader classLoader = new MemoryClassLoader(DemoService.class.getClassLoader());
        Class<?> enhancedClass = classLoader.define(DemoService.class.getName(), transformed);
        Object target = enhancedClass.getDeclaredConstructor().newInstance();
        Method fail = enhancedClass.getMethod("fail");

        try {
            fail.invoke(target);
            Assert.fail("Expected exception");
        } catch (Exception ex) {
            List<String> events = InvocationRecorder.snapshot();
            Assert.assertTrue(events.stream().anyMatch(item -> item.startsWith("AUDIT_ERROR|io.github.paohaijiao.jquick.agent.fixture.DemoService|fail|java.lang.IllegalStateException")));
        }
    }
}
