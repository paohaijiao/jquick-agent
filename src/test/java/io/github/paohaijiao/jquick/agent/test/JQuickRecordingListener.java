package io.github.paohaijiao.jquick.agent.test;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test listener that records everything the sink dispatches.
 *
 * <p>It also acts as the switch that drives the emitter: whether the argument array is built and
 * whether the return value is boxed is decided by {@link #captureArguments()} and
 * {@link #captureResult()} on the listeners that are registered. A test that asserts on the captured
 * arguments therefore proves two things at once — that the emitter honours the plan flag and that the
 * runtime populates the field.
 *
 * @author Martin
 */
public final class JQuickRecordingListener implements JQuickMethodListener {

    private final List<JQuickMethodEvent> events =
            Collections.synchronizedList(new ArrayList<JQuickMethodEvent>());

    private final boolean captureArguments;

    private final boolean captureResult;

    public JQuickRecordingListener() {
        this(false, false);
    }

    public JQuickRecordingListener(boolean captureArguments, boolean captureResult) {
        this.captureArguments = captureArguments;
        this.captureResult = captureResult;
    }

    @Override
    public void onMethodEvent(JQuickMethodEvent event) {
        events.add(event);
    }

    @Override
    public boolean captureArguments() {
        return captureArguments;
    }

    @Override
    public boolean captureResult() {
        return captureResult;
    }

    @Override
    public String name() {
        return "recording";
    }

    /* ================================================================== queries */

    public List<JQuickMethodEvent> events() {
        synchronized (events) {
            return new ArrayList<JQuickMethodEvent>(events);
        }
    }

    public List<JQuickMethodEvent> phase(JQuickMethodEvent.JQuickPhase phase) {
        List<JQuickMethodEvent> filtered = new ArrayList<JQuickMethodEvent>();
        for (JQuickMethodEvent event : events()) {
            if (event.getPhase() == phase) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    /**
     * The single event of the given phase, failing loudly when the count is not one.
     */
    public JQuickMethodEvent only(JQuickMethodEvent.JQuickPhase phase) {
        List<JQuickMethodEvent> filtered = phase(phase);
        if (filtered.size() != 1) {
            throw new AssertionError("expected exactly one " + phase + " event but found "
                    + filtered.size() + " in " + events());
        }
        return filtered.get(0);
    }

    /**
     * The first event whose method name matches, or {@code null}.
     */
    public JQuickMethodEvent byMethod(JQuickMethodEvent.JQuickPhase phase, String methodName) {
        for (JQuickMethodEvent event : phase(phase)) {
            if (methodName.equals(event.getMethodName())) {
                return event;
            }
        }
        return null;
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
    }
}
