package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.core.JQuickCategory;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMatcher;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.Arrays;
import java.util.List;

/**
 * Capability: cache access instrumentation (Redis / Memcached / Spring Cache).
 *
 * <p>Cache calls are the single most common source of "the request is slow but the database is
 * fine" incidents, so they are traced as {@link JQuickSpanType#EXIT} spans and become edges of the
 * topology graph. The plugin also demonstrates how little a new integration costs: it is a target
 * list, nothing else — the emitter and the runtime already do the work.
 *
 * @author Martin
 */
public final class JQuickCachePlugin extends JQuickComponentPlugin {

    public static final String NAME = "cache";

    private static final List<String> DEFAULT_TARGETS = Arrays.asList(
            "redis.clients.jedis.Jedis",
            "redis.clients.jedis.JedisCluster",
            "redis.clients.jedis.JedisPool",
            "io.lettuce.core.AbstractRedisClient",
            "io.lettuce.core.api.sync.RedisCommands",
            "org.springframework.data.redis.core.RedisTemplate",
            "org.springframework.data.redis.core.StringRedisTemplate",
            "org.springframework.cache.Cache");

    public JQuickCachePlugin() {
        super(NAME,
                "cache",
                JQuickSpanType.EXIT,
                JQuickCategory.INTEGRATION,
                JQuickComponentMatchers.superTypes(JQuickConfig.CACHE_INCLUDES, DEFAULT_TARGETS),
                JQuickMethodMatcher.all(),
                null,
                "cache access spans (Redis / Spring Cache)");
    }
}
