package io.github.paohaijiao.jquick.agent.core;

/**
 * A stub whose effect is an action rather than a value.
 *
 * <p>Returning a value covers most mocking needs, but not the one that matters most for testing
 * resilience: making a dependency <em>fail</em>. This interface covers it, and it is a separate type
 * rather than a convention so that a method which legitimately returns a {@code Throwable} cannot be
 * confused with a stub that is supposed to throw one.
 *
 * <p>{@link #execute()} is expected to throw; a stub that returns normally from it will leave the
 * instrumented method on the value path, where the stub object will fail to unbox into the declared
 * return type.
 *
 * @author Martin
 */
public interface JQuickStubAction {

    /**
     * Performs the action. Implementations are expected to throw.
     */
    void execute();
}
