package example.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixture: the concrete "logger". Received messages are recorded instead of printed, so a test can
 * assert on the exact text the agent handed to the logging call.
 *
 * <p>That is the only way to observe the log capability: the agent rewrites the message
 * <em>argument</em>, so the interesting value is the one that arrives at the logger, not the one the
 * caller wrote.
 *
 * @author Martin
 */
public class FixtureLogger implements FixtureLoggerApi {

    /**
     * Messages the application passed to the logger, after the agent's rewrite.
     */
    public static final List<String> MESSAGES =
            Collections.synchronizedList(new ArrayList<String>());

    @Override
    public void info(String message) {
        MESSAGES.add(message);
    }

    @Override
    public void error(String message, Throwable cause) {
        MESSAGES.add(message + " | " + (cause == null ? "null" : cause.getClass().getSimpleName()));
    }
}
