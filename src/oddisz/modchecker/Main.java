package oddisz.modchecker;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point. Finds the Starsector install, then runs the checker in a class loader that also holds
 * the game's own json.jar, so every file is parsed by exactly the code the game uses.
 *
 * This class and the ones it touches (Options, GameInstall) must not reference org.json: they are
 * loaded before json.jar is on the class path.
 */
public final class Main {
    public static final String VERSION = "1.0.1";
    /** Exit code when no Starsector folder was found: the launchers then ask the player for it. */
    static final int GAME_NOT_FOUND = 3;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.out.println();
            System.out.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options.help) {
            System.out.println(Options.USAGE);
            return;
        }

        Path toolDir = toolDirectory();
        GameInstall game = GameInstall.locate(options.gamePath, toolDir);
        if (game == null) {
            System.out.println("Starsector Mod Checker " + VERSION);
            System.out.println();
            if (options.gamePath != null) {
                System.out.println("This is not a Starsector folder: " + options.gamePath);
                System.out.println("The Starsector folder is the one with starsector-core and mods in it.");
            } else {
                System.out.println("Could not find your Starsector folder.");
                System.out.println("Put the StarsectorModChecker folder inside your Starsector folder,");
                System.out.println("or drag your Starsector folder onto check-mods.bat.");
            }
            System.exit(GAME_NOT_FOUND);
            return;
        }

        URL self = Main.class.getProtectionDomain().getCodeSource().getLocation();
        URL json = game.jsonJar.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {self, json}, ClassLoader.getPlatformClassLoader())) {
            Class<?> checker = Class.forName("oddisz.modchecker.Checker", true, loader);
            int code = (Integer) checker.getMethod("run", String[].class, String.class, String.class)
                    .invoke(null, args, game.root.toString(), toolDir == null ? null : toolDir.toString());
            System.exit(code);
        }
    }

    /** The folder holding the checker's jar, or null when it runs from loose class files. */
    static Path toolDirectory() {
        try {
            Path location = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.toString().endsWith(".jar") ? location.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
