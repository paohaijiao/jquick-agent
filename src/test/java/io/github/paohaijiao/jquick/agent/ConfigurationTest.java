package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.bootstrap.JQuickArgumentParser;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the two things every other capability depends on: the grammar of the argument string, and
 * the typed view over it.
 *
 * <p>The grammar is small on purpose, and its one subtle decision is worth a test of its own: commas
 * separate items <em>inside</em> a value, never key/value pairs. Allowing both would make
 * {@code includes=a,b} indistinguishable from two settings, and every downstream consumer would have
 * to guess.
 *
 * @author Martin
 */
public class ConfigurationTest {

    /* ================================================================== grammar */

    @Test
    public void pairsAreSeparatedBySemicolons() {
        Map<String, String> values = JQuickArgumentParser.parse(
                "includes=com.acme.*;trace.enabled=true;metrics.port=9100");
        assertEquals(3, values.size());
        assertEquals("com.acme.*", values.get("includes"));
        assertEquals("true", values.get("trace.enabled"));
        assertEquals("9100", values.get("metrics.port"));
    }

    /**
     * The list form. A comma inside a value stays inside the value, which is what makes
     * {@code includes=a,b} mean one setting with two patterns.
     */
    @Test
    public void commasSeparateItemsInsideAValueNotPairs() {
        Map<String, String> values = JQuickArgumentParser.parse(
                "includes=com.acme.a,com.acme.b;trace.enabled=true");
        assertEquals("the comma must not create a second pair", 2, values.size());
        assertEquals("com.acme.a,com.acme.b", values.get("includes"));

        JQuickConfig config = JQuickConfig.of(values);
        List<String> includes = config.getList("includes");
        assertEquals(Arrays.asList("com.acme.a", "com.acme.b"), includes);
        assertTrue(config.getBoolean("trace.enabled", false));
    }

    /**
     * A typo must not prevent the JVM from starting. Refusing to install because one pair is
     * malformed turns a configuration mistake into an outage, which is a far worse trade than an
     * agent that runs with one setting missing.
     */
    @Test
    public void malformedPairsAreSkippedRatherThanFatal() {
        Map<String, String> values = JQuickArgumentParser.parse(
                "noSeparator;=emptyKey;good=1;;  ;trailing=");
        assertEquals("only the two usable pairs survive: " + values, 2, values.size());
        assertEquals("1", values.get("good"));
        assertEquals("an empty value is kept, not dropped", "", values.get("trailing"));
    }

    @Test
    public void absentArgumentsYieldAnEmptyMap() {
        assertTrue(JQuickArgumentParser.parse(null).isEmpty());
        assertTrue(JQuickArgumentParser.parse("").isEmpty());
        assertTrue(JQuickArgumentParser.parse("   ").isEmpty());
        assertTrue(JQuickArgumentParser.parse(";;;").isEmpty());
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        Map<String, String> values = JQuickArgumentParser.parse("  includes = com.acme.*  ; b = 2 ");
        assertEquals("com.acme.*", values.get("includes"));
        assertEquals("2", values.get("b"));
    }

    /* ================================================================== @file form */

    /**
     * Long configurations do not belong on a command line: they are unreadable and process managers
     * truncate them. The {@code @file} form reads a {@code .properties} file instead.
     */
    @Test
    public void theAtFileFormLoadsProperties() throws Exception {
        File file = writeProperties("crypto.key=0123456789abcdef\n"
                + "crypto.includes=com.acme.pay.*\n"
                + "trace.enabled=true\n"
                + "trace.slow.ms=250\n");
        Map<String, String> values = JQuickArgumentParser.parse("@" + file.getPath());
        assertEquals(4, values.size());
        assertEquals("0123456789abcdef", values.get("crypto.key"));

        JQuickConfig config = JQuickConfig.of(values);
        assertEquals(250L, config.getLong(JQuickConfig.TRACE_SLOW_MS, 0L));
        assertEquals(1, config.getList(JQuickConfig.CRYPTO_INCLUDES).size());
    }

    /**
     * A missing configuration file leaves the agent with its safe defaults — everything opt-in stays
     * off — rather than preventing the JVM from starting.
     */
    @Test
    public void aMissingAtFileLeavesTheDefaults() {
        assertTrue(JQuickArgumentParser.parse("@target/definitely-not-here-"
                + System.nanoTime() + ".properties").isEmpty());
        assertTrue(JQuickArgumentParser.parse("@").isEmpty());
    }

    /**
     * The file form is not a prefix to a key/value string; it replaces it. Asserting that keeps a
     * future "smart merge" from silently making the two forms ambiguous.
     */
    @Test
    public void theAtFileFormDoesNotAlsoParsePairs() {
        assertTrue(JQuickArgumentParser.parse("@target/missing.properties;a=b").isEmpty());
    }

    @Test
    public void formatRoundTripsAndSortsKeys() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("trace.enabled", "true");
        values.put("includes", "com.acme.*");
        values.put("audit.enabled", "false");

        String rendered = JQuickArgumentParser.format(values);
        assertEquals("keys must be sorted so two runs are diffable",
                "audit.enabled=false;includes=com.acme.*;trace.enabled=true", rendered);
        assertEquals("the rendering must parse back to the same map",
                values, JQuickArgumentParser.parse(rendered));
        assertEquals("", JQuickArgumentParser.format(null));
        assertEquals("", JQuickArgumentParser.format(new LinkedHashMap<String, String>()));
    }

    /* ================================================================== typed view */

    @Test
    public void keysAreNormalisedToLowerCase() {
        JQuickConfig config = JQuickConfig.of(JQuickArgumentParser.parse("INCLUDES=com.acme.*"));
        assertNotNull(config.get("includes"));
        assertEquals("com.acme.*", config.get(JQuickConfig.INCLUDES));
        assertEquals("com.acme.*", config.get("inClUdEs"));
    }

    @Test
    public void booleansAcceptTheDocumentedSynonyms() {
        JQuickConfig config = JQuickConfig.of(JQuickArgumentParser.parse(
                "a=true;b=1;c=on;d=yes;e=false;f=0;g=off;h=no;i=maybe;j="));

        assertTrue(config.getBoolean("a", false));
        assertTrue(config.getBoolean("b", false));
        assertTrue(config.getBoolean("c", false));
        assertTrue(config.getBoolean("d", false));
        assertFalse(config.getBoolean("e", true));
        assertFalse(config.getBoolean("f", true));
        assertFalse(config.getBoolean("g", true));
        assertFalse(config.getBoolean("h", true));
        assertTrue("an unrecognised value must fall back, not guess",
                config.getBoolean("i", true));
        assertTrue("an empty value must fall back", config.getBoolean("j", true));
        assertTrue("an absent key must fall back", config.getBoolean("absent", true));
    }

    @Test
    public void numericAccessorsFallBackOnGarbage() {
        JQuickConfig config = JQuickConfig.of(JQuickArgumentParser.parse(
                "i=42;j=9000000000;k=0.25;bad=notanumber;empty="));

        assertEquals(42, config.getInt("i", -1));
        assertEquals(9_000_000_000L, config.getLong("j", -1L));
        assertEquals(0.25d, config.getDouble("k", -1d), 0.0001d);
        assertEquals("a malformed number must not abort the install",
                -1, config.getInt("bad", -1));
        assertEquals(-1L, config.getLong("empty", -1L));
        assertEquals("an absent key must fall back", -1d, config.getDouble("absent", -1d), 0.0001d);
    }

    /**
     * The convention every capability plugin follows: {@code <name>.enabled}, off unless switched on.
     * Asserting the default here is what makes the "safe by default" claim testable.
     */
    @Test
    public void pluginsAreDisabledUnlessExplicitlySwitchedOn() {
        JQuickConfig off = JQuickConfig.of(JQuickArgumentParser.parse("includes=com.acme.*"));
        assertFalse(off.isPluginEnabled("governance"));
        assertFalse(off.isPluginEnabled("crypto"));

        JQuickConfig on = JQuickConfig.of(JQuickArgumentParser.parse("governance.enabled=true"));
        assertTrue(on.isPluginEnabled("governance"));
        assertFalse("one capability must not switch on another", on.isPluginEnabled("crypto"));
    }

    @Test
    public void anEmptyConfigurationIsUsableAndDescribesItself() {
        JQuickConfig config = JQuickConfig.empty();
        assertTrue(config.keySet().isEmpty());
        assertTrue(config.getList(JQuickConfig.INCLUDES).isEmpty());
        assertFalse(config.isPluginEnabled("anything"));
        assertEquals("JQuickConfig{}", config.describe());
    }

    @Test
    public void describeRendersTheEffectiveConfigurationSorted() {
        JQuickConfig config = JQuickConfig.of(JQuickArgumentParser.parse(
                "trace.enabled=true;audit.enabled=false"));
        assertEquals("JQuickConfig{audit.enabled=false, trace.enabled=true}", config.describe());
        assertEquals("toString must agree with describe", config.describe(), config.toString());
    }

    /**
     * A value with an equals sign in it — a base64 key, for example — must survive: only the first
     * separator counts.
     */
    @Test
    public void valuesMayContainEqualsSigns() {
        Map<String, String> values = JQuickArgumentParser.parse("crypto.key=aGVsbG8=;x=1");
        assertEquals("aGVsbG8=", values.get("crypto.key"));
        assertEquals("1", values.get("x"));
    }

    /* ================================================================== helpers */

    private static File writeProperties(String content) throws Exception {
        File directory = new File("target/tmp-args-" + System.nanoTime());
        assertTrue("could not create " + directory, directory.mkdirs());
        File file = new File(directory, "agent.properties");
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "ISO-8859-1");
        try {
            writer.write(content);
            writer.flush();
        } finally {
            writer.close();
        }
        return file;
    }
}
