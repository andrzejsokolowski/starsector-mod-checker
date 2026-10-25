package oddisz.modchecker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every class the game can find: the classes in its own jars and in the jars each enabled mod lists
 * in mod_info.json, plus .java sources, which the game compiles at startup from any mod's folder.
 */
final class ClassIndex {
    private final Set<String> classes = new HashSet<>();
    private final FileIndex files;

    ClassIndex(FileIndex files, Path coreDir, int javaVersion, Report report) throws IOException {
        this.files = files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(coreDir, "*.jar")) {
            for (Path jar : stream) readJar(jar, null, null, 0, report);
        }
        for (Source source : files.sources()) {
            if (source.isCore()) continue;
            for (String jarPath : source.mod.jars) {
                if (jarPath.isEmpty()) continue;
                String found = files.inSource(source, jarPath);
                if (found == null) {
                    report.error(source, "mod_info.json", "Lists the jar \"" + jarPath + "\", but that file is not in the mod"
                            + " folder. The mod's code is missing, so the game crashes at startup.");
                    continue;
                }
                readJar(source.root.resolve(found), source, found, javaVersion, report);
            }
        }
    }

    private void readJar(Path jar, Source source, String rel, int javaVersion, Report report) {
        int newest = 0;
        String newestClass = null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
                classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                if (source == null) continue;
                int major = classFileMajor(zip, e);
                if (major > newest) {
                    newest = major;
                    newestClass = name;
                }
            }
        } catch (IOException e) {
            if (source != null) {
                report.error(source, rel, "This jar cannot be opened (" + e.getMessage() + "). The game crashes at startup."
                        + " Download the mod again.");
            }
            return;
        }
        int needs = newest - 44;
        if (source != null && javaVersion > 0 && needs > javaVersion) {
            report.error(source, rel, "Built for Java " + needs + " (" + newestClass + "), but your game runs Java "
                    + javaVersion + ". The game crashes at startup with UnsupportedClassVersionError. Get a build of the mod"
                    + " made for your game version, or the Java version the mod asks for.");
        }
    }

    private static int classFileMajor(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] head = in.readNBytes(8);
            if (head.length < 8 || (head[0] & 0xFF) != 0xCA || (head[1] & 0xFF) != 0xFE) return 0;
            return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Whether the game can load this class from a jar, or compile it from a .java file. */
    boolean exists(String className) {
        String name = className.trim();
        if (classes.contains(name)) return true;
        String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        if (classes.contains(outer)) return true;
        return files.exists(outer.replace('.', '/') + ".java");
    }
}
