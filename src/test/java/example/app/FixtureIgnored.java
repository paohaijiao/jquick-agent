package example.app;

/**
 * Fixture: a class that must never be instrumented, used as the negative half of the matcher tests.
 *
 * @author Martin
 */
public class FixtureIgnored {

    public String never(String value) {
        return "never:" + value;
    }
}
