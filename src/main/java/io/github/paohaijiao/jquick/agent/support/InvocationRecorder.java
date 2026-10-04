package io.github.paohaijiao.jquick.agent.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InvocationRecorder {

    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<String>());

    private InvocationRecorder() {
    }

    public static void record(String value) {
        EVENTS.add(value);
    }

    public static List<String> snapshot() {
        synchronized (EVENTS) {
            return new ArrayList<String>(EVENTS);
        }
    }

    public static void clear() {
        EVENTS.clear();
    }
}
