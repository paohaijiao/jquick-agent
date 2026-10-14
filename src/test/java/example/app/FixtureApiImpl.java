package example.app;

/**
 * Fixture: an implementation, whose <em>declared</em> interface is what the class matcher sees.
 *
 * <p>This mirrors the real selection problem the integration plugins solve: a Spring
 * {@code DispatcherServlet} is selected because its direct superclass is
 * {@code FrameworkServlet}, and a Jedis client because it implements a known type — never because of
 * its own name.
 *
 * @author Martin
 */
public class FixtureApiImpl implements FixtureApi {

    @Override
    public String describe() {
        return "impl";
    }

    public String detail(int level) {
        return "detail:" + level;
    }
}
