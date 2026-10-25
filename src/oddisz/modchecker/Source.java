package oddisz.modchecker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One place the game reads files from: the core game folder or one enabled mod. */
final class Source {
    final String name;
    final Path root;
    final ModInfo mod;

    Source(String name, Path root, ModInfo mod) {
        this.name = name;
        this.root = root;
        this.mod = mod;
    }

    boolean isCore() {
        return mod == null;
    }

    @Override
    public String toString() {
        return name;
    }

    /** What mod_info.json says. */
    static final class ModInfo {
        String id;
        String name;
        String version;
        String gameVersion;
        String modPlugin;
        boolean totalConversion;
        final List<String> jars = new ArrayList<>();
        final List<String> dependencies = new ArrayList<>();
        /** Files this mod replaces outright instead of merging ("replace" in mod_info.json). */
        final Set<String> replace = new LinkedHashSet<>();
    }
}
