package io.github.paohaijiao.jquick.agent.support;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Identifier generator for traces and spans.
 *
 * <p>The format follows the widely deployed distributed-tracing convention (Dapper / Zipkin /
 * Spring Cloud Sleuth): a 128-bit trace id rendered as 32 lower-case hex characters and a 64-bit
 * span id rendered as 16 characters. Keeping the same shape means the ids produced here can be fed
 * to Zipkin, Jaeger, SkyWalking or an ELK stack without any transformation.
 *
 * <p>Generation is allocation light (no {@code String.format}, no {@code UUID} parsing) because it
 * sits on the hot path of every instrumented call.
 *
 * @author Martin
 */
public final class JQuickIdGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JQuickIdGenerator() {
    }

    /**
     * Creates a 128-bit trace id (32 hex characters).
     */
    public static String newTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return toHex(random.nextLong()).concat(toHex(random.nextLong()));
    }

    /**
     * Creates a 64-bit span id (16 hex characters).
     */
    public static String newSpanId() {
        return toHex(ThreadLocalRandom.current().nextLong());
    }

    private static String toHex(long value) {
        char[] buffer = new char[16];
        for (int i = 15; i >= 0; i--) {
            buffer[i] = HEX[(int) (value & 0xF)];
            value >>>= 4;
        }
        return new String(buffer);
    }
}
