package oddisz.modchecker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the game lives. Windows keeps the game files in starsector-core, Linux in the install folder
 * itself, and macOS in Starsector.app/Contents/Resources/Java. The mods folder sits in the install
 * folder (inside Starsector.app on macOS).
 */
final class GameInstall {
    final Path root;
    final Path core;
    final Path mods;
    final Path jsonJar;

    private GameInstall(Path root, Path core) {
        this.root = root;
        this.core = core;
        this.mods = root.resolve("mods");
        this.jsonJar = core.resolve("json.jar");
    }

    /** The install at this folder, or null. Also accepts the mods or starsector-core folder itself. */
    static GameInstall at(Path folder) {
        if (folder == null) return null;
        folder = folder.toAbsolutePath().normalize();
        for (Path root : new Path[] {folder, folder.getParent()}) {
            if (root == null) continue;
            for (Path core : new Path[] {root.resolve("starsector-core"), root.resolve("Contents/Resources/Java"), root}) {
                if (Files.isRegularFile(core.resolve("json.jar")) && Files.isDirectory(core.resolve("data"))
                        && Files.isRegularFile(core.resolve("starfarer.api.jar"))) {
                    return new GameInstall(root, core);
                }
            }
        }
        return null;
    }

    /**
     * Uses the given folder if any; otherwise looks above the tool's own folder, then the places the
     * official installers use. When nothing is found the launchers ask the player for the folder.
     */
    static GameInstall locate(String given, Path toolDir) {
        if (given != null) {
            return at(Paths.get(stripQuotes(given)));
        }
        for (Path p = toolDir; p != null; p = p.getParent()) {
            GameInstall found = at(p);
            if (found != null) return found;
        }
        List<Path> guesses = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        guesses.add(Paths.get("C:/Program Files (x86)/Fractal Softworks/Starsector"));
        guesses.add(Paths.get("C:/Program Files/Fractal Softworks/Starsector"));
        guesses.add(Paths.get("/Applications/Starsector.app"));
        guesses.add(Paths.get(home, "Applications", "Starsector.app"));
        guesses.add(Paths.get(home, "starsector"));
        guesses.add(Paths.get(home, "Starsector"));
        for (Path guess : guesses) {
            GameInstall found = at(guess);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The Java version the game runs on, read from the bundled runtime's release file. Players who swap
     * in a newer runtime get that runtime's version. Falls back to 17, the version 0.98a ships with.
     */
    int javaVersion() {
        for (Path release : new Path[] {root.resolve("jre/release"), root.resolve("jre_linux/release"),
                root.resolve("Contents/Home/release"), root.resolve("zulu/release")}) {
            try {
                if (!Files.isRegularFile(release)) continue;
                String text = new String(Files.readAllBytes(release), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(text);
                if (m.find()) return Integer.parseInt(m.group(1));
            } catch (IOException | NumberFormatException ignored) {
                // try the next place
            }
        }
        return 17;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }
}
