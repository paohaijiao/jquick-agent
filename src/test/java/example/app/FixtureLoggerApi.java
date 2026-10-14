package example.app;

/**
 * Fixture: a logging facade, so the log capability can be exercised without a logging framework on the
 * test classpath.
 *
 * <p>It exists to reproduce the real selection problem in miniature: an application's logger has an
 * arbitrary class name, so the log plugin selects by <em>hierarchy</em> — the same way it matches
 * SLF4J's, Log4j's and {@code java.util.logging}'s implementations in production.
 *
 * @author Martin
 */
public interface FixtureLoggerApi {

    void info(String message);

    void error(String message, Throwable cause);
}
