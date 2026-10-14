package io.github.paohaijiao.jquick.agent.plugin.classload;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds classes that exist in more than one classpath entry.
 *
 * <p>A duplicate class is the classic cause of "it works on my machine": two versions of a library on
 * the classpath, the wrong one wins, and the symptom appears as an inexplicable
 * {@code NoSuchMethodError} three layers away from the cause. Detecting it takes a single pass over
 * the classpath, which makes it a very cheap pre-flight check to run once at startup.
 *
 * <p>Only <code>.class</code> entries are considered, and the scan is deliberately shallow — it reads
 * the archive directory, never the file contents — so a classpath of a few hundred megabytes is
 * inspected in a few seconds.
 *
 * @author Martin
 */
public final class JQuickJarConflictDetector {

    private JQuickJarConflictDetector() {
    }

    /**
     * Duplicated class names mapped to the classpath entries that provide them.
     *
     * @param ignorePrefixes entries whose class names start with one of these prefixes are ignored,
     *                       e.g. {@code META-INF.versions}
     */
    public static Map<String, List<String>> conflicts(List<String> ignorePrefixes) {
        List<String> ignores = ignorePrefixes == null
                ? Collections.emptyList() : ignorePrefixes;
        Map<String, String> firstSeen = new HashMap<String, String>();
        Map<String, List<String>> duplicates = new TreeMap<String, List<String>>();

        for (File entry : classpathEntries()) {
            if (entry.isFile() && entry.getName().endsWith(".jar")) {
                scanJar(entry, ignores, firstSeen, duplicates);
            } else if (entry.isDirectory()) {
                scanDirectory(entry, entry, ignores, firstSeen, duplicates);
            }
        }
        return duplicates;
    }

    /**
     * Conflicts with the default ignore list.
     */
    public static Map<String, List<String>> conflicts() {
        return conflicts(Collections.singletonList("META-INF"));
    }

    /**
     * Human readable report; empty when no conflict was found.
     */
    public static String report() {
        Map<String, List<String>> duplicates = conflicts();
        if (duplicates.isEmpty()) {
            return "no duplicate class found on the classpath";
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("duplicate classes found on the classpath: ").append(duplicates.size()).append('\n');
        for (Map.Entry<String, List<String>> entry : duplicates.entrySet()) {
            sb.append("  ").append(entry.getKey()).append('\n');
            for (String location : entry.getValue()) {
                sb.append("      <- ").append(location).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<File> classpathEntries() {
        String classpath = System.getProperty("java.class.path");
        List<File> entries = new ArrayList<File>();
        if (classpath == null || classpath.isEmpty()) {
            return entries;
        }
        for (String part : classpath.split(File.pathSeparator)) {
            if (part != null && !part.trim().isEmpty()) {
                entries.add(new File(part.trim()));
            }
        }
        return entries;
    }

    private static void scanJar(File jar, List<String> ignores, Map<String, String> firstSeen,
                                Map<String, List<String>> duplicates) {
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jar);
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || isIgnored(name, ignores)) {
                    continue;
                }
                record(toClassName(name), jar.getPath(), firstSeen, duplicates);
            }
        } catch (IOException ex) {
            // An unreadable archive is not a conflict; ignore it.
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    /**
     * Walks a directory classpath entry.
     *
     * <p>Two file arguments, and the distinction matters. {@code entry} is the classpath root, used
     * both to build the class name (the path relative to it) and to report the location;
     * {@code current} is the recursion cursor. Collapsing the two — passing the cursor as the root —
     * is the bug this signature exists to prevent: the class name would then be only the file name,
     * the package would be lost, and {@code com.acme.User} would be reported as a duplicate of
     * {@code org.other.User} because both are called {@code User}. The filter would also stop seeing
     * {@code META-INF/...} prefixes, since the relative path would no longer start with the directory
     * it is inside.
     */
    private static void scanDirectory(File entry, File current, List<String> ignores,
                                      Map<String, String> firstSeen,
                                      Map<String, List<String>> duplicates) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanDirectory(entry, child, ignores, firstSeen, duplicates);
            } else if (child.getName().endsWith(".class")) {
                String relative = child.getPath().substring(entry.getPath().length() + 1);
                if (isIgnored(relative, ignores)) {
                    continue;
                }
                record(toClassName(relative), entry.getPath(), firstSeen, duplicates);
            }
        }
    }

    private static boolean isIgnored(String entryName, List<String> ignores) {
        for (String ignore : ignores) {
            if (ignore != null && !ignore.isEmpty() && entryName.startsWith(ignore)) {
                return true;
            }
        }
        return false;
    }

    private static void record(String className, String location, Map<String, String> firstSeen,
                               Map<String, List<String>> duplicates) {
        String previous = firstSeen.get(className);
        if (previous == null) {
            firstSeen.put(className, location);
            return;
        }
        if (previous.equals(location)) {
            return;
        }
        List<String> locations = duplicates.get(className);
        if (locations == null) {
            locations = new ArrayList<String>(2);
            locations.add(previous);
            duplicates.put(className, locations);
        }
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static String toClassName(String entryName) {
        String name = entryName;
        if (name.endsWith(".class")) {
            name = name.substring(0, name.length() - ".class".length());
        }
        return name.replace('/', '.').replace('\\', '.');
    }

    /**
     * Convenience for callers that want the conflicts indexed by providing archive.
     */
    public static Map<String, Integer> conflictCountByArchive() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, List<String>> entry : conflicts().entrySet()) {
            for (String location : entry.getValue()) {
                Integer current = counts.get(location);
                counts.put(location, current == null ? 1 : current + 1);
            }
        }
        return counts;
    }
}
