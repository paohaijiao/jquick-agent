package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.core.JQuickCategory;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMatcher;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.Arrays;
import java.util.List;

/**
 * Capability: inbound HTTP request instrumentation.
 *
 * <p>Produces an {@link JQuickSpanType#ENTRY} span per request, which is what anchors a trace: it is
 * the only span that legitimately starts a new trace, and its duration is the number an SLO is
 * written against.
 *
 * <p>The target list covers the servlet API, Spring MVC and a common Netty-based stack. Nothing is
 * bundled: the plugin only matches the hierarchy of whatever the application already has on its
 * classpath, so a framework that is not present costs nothing.
 *
 * @author Martin
 */
public final class JQuickHttpPlugin extends JQuickComponentPlugin {

    public static final String NAME = "http";

    /**
     * Direct superclass / interface names of the supported entry points.
     *
     * <p>Intermediate types are listed explicitly because matching is performed on the direct
     * hierarchy (see {@link JQuickComponentMatchers}).
     */
    private static final List<String> DEFAULT_TARGETS = Arrays.asList(
            "javax.servlet.http.HttpServlet",
            "jakarta.servlet.http.HttpServlet",
            "javax.servlet.Filter",
            "jakarta.servlet.Filter",
            "org.springframework.web.servlet.FrameworkServlet",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.servlet.mvc.Controller",
            "io.undertow.servlet.handlers.ServletInitialHandler",
            "io.netty.channel.ChannelInboundHandlerAdapter");

    public JQuickHttpPlugin() {
        super(NAME,
                "http",
                JQuickSpanType.ENTRY,
                JQuickCategory.INTEGRATION,
                JQuickComponentMatchers.superTypes(JQuickConfig.HTTP_INCLUDES, DEFAULT_TARGETS),
                JQuickMethodMatcher.all(),
                null,
                "inbound HTTP entry spans (servlet / Spring MVC / Netty)");
    }
}
