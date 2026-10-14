package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.core.JQuickCategory;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMatcher;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.Arrays;
import java.util.List;

/**
 * Capability: message queue instrumentation (JMS / Kafka / RabbitMQ / RocketMQ).
 *
 * <p>Two kinds of span matter in messaging and they have opposite directions: producing is an
 * {@link JQuickSpanType#EXIT} edge into the broker, consuming is an {@link JQuickSpanType#ENTRY}
 * edge that may legitimately start a new trace when the message was published by another system.
 *
 * <p>This plugin declares the <em>producer</em> side, which is the unambiguous one: a produce call is
 * always an outbound operation of the current trace. Consumer-side tracing additionally needs the
 * broker's message headers — a trace id must be injected on publish and extracted on consume — which
 * the plugin exposes through {@link JQuickMessageHeaders} rather than pretending it can infer it.
 *
 * @author Martin
 */
public final class JQuickMqPlugin extends JQuickComponentPlugin {

    public static final String NAME = "mq";

    private static final List<String> DEFAULT_TARGETS = Arrays.asList(
            "javax.jms.MessageProducer",
            "jakarta.jms.MessageProducer",
            "org.apache.kafka.clients.producer.Producer",
            "org.apache.kafka.clients.producer.KafkaProducer",
            "com.rabbitmq.client.Channel",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.apache.rocketmq.client.producer.DefaultMQProducer",
            "org.springframework.kafka.core.KafkaTemplate");

    public JQuickMqPlugin() {
        super(NAME,
                "mq",
                JQuickSpanType.EXIT,
                JQuickCategory.INTEGRATION,
                JQuickComponentMatchers.superTypes(JQuickConfig.MQ_INCLUDES, DEFAULT_TARGETS),
                JQuickMethodMatcher.all(),
                null,
                "message queue produce spans (JMS / Kafka / RabbitMQ / RocketMQ)");
    }
}
