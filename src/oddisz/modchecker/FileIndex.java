package oddisz.modchecker;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every file of every source, by relative path with forward slashes. Lookups ignore case the way the
 * game does on Windows; {@link #exactCaseExists} tells whether Linux and macOS would find it too.
 */
final class FileIndex {
    record Hit(Source source, String path) {
    }

    private final List<Source> sources;
    private final Map<String, List<Hit>> byLowerPath = new HashMap<>();
    private final Map<Source, Map<String, List<String>>> filesByDir = new HashMap<>();
    private final Map<Source, Map<String, Set<String>>> subdirsByDir = new HashMap<>();
    private int fileCount;

    FileIndex(List<Source> sources) throws IOException {
        this.sources = sources;
        for (Source source : sources) index(source);
    }

    List<Source> sources() {
        return sources;
    }

    int fileCount() {
        return fileCount;
    }

    private void index(Source source) throws IOException {
        Map<String, List<String>> files = new HashMap<>();
        Map<String, Set<String>> subdirs = new HashMap<>();
        filesByDir.put(source, files);
        subdirsByDir.put(source, subdirs);
        Path root = source.root;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (name.equals(".git") || name.equals(".gradle") || name.equals(".idea"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(root)) {
                    String rel = relative(root, dir);
                    String parent = parentOf(rel);
                    subdirs.computeIfAbsent(parent.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>()).add(rel);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = relative(root, file);
                byLowerPath.computeIfAbsent(rel.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(new Hit(source, rel));
                files.computeIfAbsent(parentOf(rel).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rel);
                fileCount++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        for (List<String> list : files.values()) Collections.sort(list);
    }

    static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    /** Cleans a path written in a data file: backslashes become slashes, a leading slash goes. */
    static String normalize(String path) {
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/") || p.startsWith("./")) p = p.substring(p.startsWith("/") ? 1 : 2);
        while (p.contains("//")) p = p.replace("//", "/");
        return p;
    }

    /** All copies of this path, in the order the game searches them (mods first, core last). */
    List<Hit> find(String rel) {
        List<Hit> hits = byLowerPath.get(normalize(rel).toLowerCase(Locale.ROOT));
        return hits == null ? List.of() : hits;
    }

    boolean exists(String rel) {
        return !find(rel).isEmpty();
    }

    boolean exactCaseExists(String rel) {
        String n = normalize(rel);
        for (Hit hit : find(n)) if (hit.path.equals(n)) return true;
        return false;
    }

    /** The copy of this path inside one source, or null. */
    String inSource(Source source, String rel) {
        for (Hit hit : find(rel)) if (hit.source == source) return hit.path;
        return null;
    }

    /** Files directly inside dir whose names end with suffix, the way the game lists data folders. */
    List<String> list(Source source, String dir, String suffix) {
        List<String> files = filesByDir.get(source).get(dir.toLowerCase(Locale.ROOT));
        if (files == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String f : files) if (fileName(f).endsWith(suffix)) out.add(f);
        return out;
    }

    /** Folders directly inside dir, skipping hidden ones like the game does. */
    List<String> subdirs(Source source, String dir) {
        Set<String> dirs = subdirsByDir.get(source).get(dir.toLowerCase(Locale.ROOT));
        if (dirs == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String d : dirs) if (!fileName(d).startsWith(".")) out.add(d);
        Collections.sort(out);
        return out;
    }

    /** Every file of one source. */
    List<String> all(Source source) {
        List<String> out = new ArrayList<>();
        for (List<String> files : filesByDir.get(source).values()) out.addAll(files);
        Collections.sort(out);
        return out;
    }
}
