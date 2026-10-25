package oddisz.modchecker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Loads the core game and the enabled mods the way Starsector does, then runs every rule on the
 * result. Each rule mirrors a check or a crash in the game's loading code (see Rules, Sheets, Extras).
 */
public final class Checker {
    /** One loaded spec: the file after the game merged every copy of it. */
    static final class Spec {
        String id;
        JSONObject json;
        /** The source whose copy was merged last: problems are reported against it. */
        Source owner;
        String path;
        final List<Source> contributors = new ArrayList<>();
    }

    final Options options;
    final GameInstall game;
    final Report report = new Report();
    final boolean caseSensitiveFiles;
    final int javaVersion;
    Source core;
    List<Source> sources;
    ModList mods;
    FileIndex files;
    ClassIndex classes;

    private final Map<String, GameJson.Parsed> parseCache = new HashMap<>();
    private final Map<String, GameCsv.Table> csvCache = new HashMap<>();
    /** File references already checked by a specific rule, so the general scan does not repeat them. */
    final Set<String> checkedRefs = new LinkedHashSet<>();
    private final Set<String> reportedBroken = new LinkedHashSet<>();

    final Map<String, Spec> projectiles = new LinkedHashMap<>();
    final Map<String, Spec> weapons = new LinkedHashMap<>();
    final Map<String, Spec> hulls = new LinkedHashMap<>();
    final Map<String, Spec> skins = new LinkedHashMap<>();
    final Map<String, Spec> variants = new LinkedHashMap<>();
    final Map<String, Spec> systems = new LinkedHashMap<>();
    /** Specs the game throws away after loading, with the reason. */
    final Map<String, String> droppedWeapons = new LinkedHashMap<>();
    final Map<String, String> droppedHulls = new LinkedHashMap<>();
    final Map<String, Sheets.Entry> wingRows = new LinkedHashMap<>();
    final Map<String, Sheets.Entry> hullmodRows = new LinkedHashMap<>();
    final Map<String, Sheets.Entry> systemRows = new LinkedHashMap<>();
    final Set<String> hullStyles = new LinkedHashSet<>();
    final Set<String> engineStyles = new LinkedHashSet<>();
    /** Weapon id to a description of a hidden slot it sits in. */
    final Map<String, String> hiddenSlotUse = new LinkedHashMap<>();
    /** Weapon id to a description of one place that mounts it (a variant or a built-in slot). */
    final Map<String, String> weaponUse = new LinkedHashMap<>();
    /** Skills and aptitudes listed in skill_data.csv and aptitude_data.csv: only those .skill files load. */
    final Set<String> skillIds = new LinkedHashSet<>();
    /** The .faction files factions.csv lists: only those load. Lower case paths. */
    final Set<String> factionFiles = new LinkedHashSet<>();
    /** The ids each keyed sheet ends up with, by lower case path. */
    final Map<String, Set<String>> sheetIds = new HashMap<>();
    CodeScan codeScan;

    Checker(Options options, GameInstall game) {
        this.options = options;
        this.game = game;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.caseSensitiveFiles = !os.contains("win");
        this.javaVersion = game.javaVersion();
    }

    /** Called by Main through the class loader that holds json.jar. */
    public static int run(String[] args, String gameRoot, String toolDir) throws IOException {
        Options options = Options.parse(args);
        GameInstall game = GameInstall.at(Paths.get(gameRoot));
        Checker checker = new Checker(options, game);
        System.out.println("Starsector Mod Checker " + Main.VERSION + ": checking " + game.mods + " ...");
        checker.check();
        String text = checker.report.render(checker.header(), options.notes);
        System.out.println();
        System.out.print(text);

        if (!options.noReport) {
            Path out = options.outPath != null ? Paths.get(options.outPath)
                    : (toolDir != null ? Paths.get(toolDir) : Paths.get("")).resolve("mod-checker-report.txt");
            try {
                Files.write(out, checker.report.render(checker.header(), true).getBytes(StandardCharsets.UTF_8));
                System.out.println();
                System.out.println("Report saved to " + out.toAbsolutePath() + " (it also lists the notes).");
            } catch (IOException e) {
                System.out.println();
                System.out.println("Could not save the report to " + out.toAbsolutePath() + ": " + e.getMessage());
            }
        }
        return checker.report.count(Report.Severity.ERROR) > 0 ? 1 : 0;
    }

    String header() {
        String nl = System.lineSeparator();
        return "Starsector Mod Checker " + Main.VERSION + nl
                + "Game folder: " + game.root + "  (runs Java " + javaVersion + ")" + nl
                + "Checked " + Report.plural(mods.enabled.size(), options.allMods ? "mod" : "enabled mod")
                + " (" + Report.plural(files.fileCount(), "file") + ", including the base game)." + nl
                + (codeScan == null ? "" : "Read " + Report.plural(codeScan.classesRead, "class") + " in the mods' jars and checked "
                + Report.plural(codeScan.lookupsChecked, "id") + " written into their code." + nl);
    }

    void check() throws IOException {
        mods = ModList.scan(game.mods, options.allMods, report);
        core = new Source("Starsector (base game files)", game.core, null);
        sources = new ArrayList<>(mods.enabled);
        sources.add(core);
        files = new FileIndex(sources);
        classes = new ClassIndex(files, game.core, javaVersion, report);

        Rules rules = new Rules(this);
        rules.run();
        rules.sheets().runLate();
        new Extras(this, rules).run();
        codeScan = new CodeScan(this, rules);
        codeScan.run();
    }

    // ---------------------------------------------------------------- reading files

    byte[] bytes(Source source, String rel) {
        try {
            return Files.readAllBytes(source.root.resolve(rel));
        } catch (IOException e) {
            return null;
        }
    }

    GameCsv.Table table(Source source, String rel) {
        String key = source.name + "|" + rel;
        GameCsv.Table t = csvCache.get(key);
        if (t == null) {
            byte[] data = bytes(source, rel);
            t = data == null ? new GameCsv.Table() : GameCsv.parse(data);
            csvCache.put(key, t);
        }
        return t;
    }

    /** Whether this copy was already parsed (and so already reported if broken). */
    boolean parsedBefore(Source source, String rel) {
        return parseCache.containsKey(source.name + "|" + rel);
    }

    /** A class named in data must exist in a jar or as a .java file, or the game crashes at startup. */
    void checkClass(Fields f, String field, String name) {
        checkClass(f, field, name, true);
    }

    /**
     * With {@code atStartup} false the game only creates the class when it needs it (a bar event, a
     * mission), so a missing class crashes then instead.
     */
    void checkClass(Fields f, String field, String name, boolean atStartup) {
        if (name == null) return;
        String when = atStartup ? "The game crashes at startup" : "The game crashes when it tries to use it";
        if (name.trim().isEmpty() || name.equals("null")) {
            String text = field + " is empty, but the game still tries to load it as a class. " + when + ".";
            if (atStartup) f.error(text);
            else f.warning(text);
            return;
        }
        if (!classes.exists(name)) {
            String text = field + " names the class \"" + name.trim() + "\", which is in no enabled mod's jar and has no"
                    + " .java file. " + when + " (\"Error loading [" + name.trim() + "]\" or ClassNotFoundException).";
            if (atStartup) f.error(text);
            else f.warning(text);
        }
    }

    /** Marks a file reference as handled by a specific rule, so the general scan leaves it alone. */
    void claimRef(Source source, String rel, String value) {
        if (value == null) return;
        checkedRefs.add(source.name + "|" + rel + "|" + FileIndex.normalize(value).toLowerCase(Locale.ROOT));
    }

    /**
     * Parses one copy of a file, once. When the game cannot read it, reports it with the given severity
     * and returns null.
     */
    JSONObject parse(Source source, String rel, Report.Severity ifBroken, String consequence) {
        String key = source.name + "|" + rel;
        GameJson.Parsed p = parseCache.get(key);
        if (p == null) {
            byte[] data = bytes(source, rel);
            if (data == null) {
                p = new GameJson.Parsed();
                p.error = "the file cannot be opened";
            } else {
                p = GameJson.parse(data);
            }
            parseCache.put(key, p);
        }
        if (p.object == null && ifBroken != null && reportedBroken.add(key)) {
            String why = p.bom
                    ? "The file starts with a UTF-8 BOM (an invisible marker some editors add), which the game cannot read."
                    + " Save it as UTF-8 without BOM."
                    : "The game cannot read this file: " + GameJson.cleanError(p.error) + ModList.where(p.errorLine) + ".";
            report.add(ifBroken, source, rel, why + " " + consequence);
        }
        return p.object;
    }

    /**
     * The file as the game sees it: the base game's copy (if any) with every mod's copy merged on top,
     * or only the copy of a mod that lists it under "replace". Returns null if nothing could be read.
     */
    Spec effective(String rel) {
        List<FileIndex.Hit> hits = files.find(rel);
        FileIndex.Hit coreHit = null;
        List<FileIndex.Hit> modHits = new ArrayList<>();
        FileIndex.Hit replacer = null;
        for (FileIndex.Hit hit : hits) {
            if (hit.source().isCore()) {
                coreHit = hit;
            } else {
                modHits.add(hit);
                if (replacer == null && replaces(hit.source(), hit.path())) replacer = hit;
            }
        }
        String crash = "The game crashes while loading.";
        Spec spec = new Spec();
        JSONObject master = null;
        if (replacer != null) {
            master = parse(replacer.source(), replacer.path(), Report.Severity.ERROR, crash);
            spec.owner = replacer.source();
            spec.path = replacer.path();
            spec.contributors.add(replacer.source());
        } else {
            if (coreHit != null) {
                JSONObject c = parse(coreHit.source(), coreHit.path(), Report.Severity.ERROR, crash);
                if (c != null) {
                    master = GameJson.copy(c);
                    spec.owner = core;
                    spec.path = coreHit.path();
                    spec.contributors.add(core);
                }
            }
            if (master == null) master = new JSONObject();
            for (FileIndex.Hit hit : modHits) {
                JSONObject overlay = parse(hit.source(), hit.path(), Report.Severity.ERROR, crash);
                if (overlay == null) continue;
                List<String> conflicts = new ArrayList<>();
                try {
                    GameJson.merge(master, overlay, "", conflicts);
                } catch (JSONException e) {
                    conflicts.add(e.getMessage());
                }
                for (String conflict : conflicts) {
                    report.error(hit.source(), hit.path(), "The game merges this file with the copy in "
                            + otherNames(spec.contributors) + ", and " + conflict + ". The game crashes while loading"
                            + " (\"Value for key ... must be a JSON array/object\").");
                }
                spec.owner = hit.source();
                spec.path = hit.path();
                spec.contributors.add(hit.source());
            }
        }
        if (spec.owner == null || master == null) return null;
        spec.json = master;
        return spec;
    }

    private boolean replaces(Source source, String rel) {
        for (String r : source.mod.replace) if (r.equalsIgnoreCase(rel)) return true;
        return false;
    }

    static String otherNames(List<Source> contributors) {
        if (contributors.isEmpty()) return "no other mod";
        List<String> names = new ArrayList<>();
        for (Source s : contributors) names.add(s.name);
        return String.join(", ", names);
    }

    /**
     * The files of one data folder the game loads, in its order (mods by name, then the base game),
     * each path once. With {@code oneLevel} it also takes files from folders directly inside.
     */
    List<String> listLoaded(String dir, String suffix, boolean oneLevel) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Source source : sources) {
            for (String rel : files.list(source, dir, suffix)) found.putIfAbsent(rel.toLowerCase(Locale.ROOT), rel);
        }
        if (oneLevel) {
            Map<String, String> subdirs = new LinkedHashMap<>();
            for (Source source : sources) {
                for (String sub : files.subdirs(source, dir)) subdirs.putIfAbsent(sub.toLowerCase(Locale.ROOT), sub);
            }
            for (String sub : subdirs.values()) {
                for (Source source : sources) {
                    for (String rel : files.list(source, sub, suffix)) found.putIfAbsent(rel.toLowerCase(Locale.ROOT), rel);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    // ---------------------------------------------------------------- file references

    /**
     * Checks that a file named in data exists somewhere the game looks. Returns false when it is missing
     * (after reporting it with the given severity).
     */
    boolean checkFile(Source source, String rel, String field, String value, Report.Severity ifMissing, String consequence) {
        if (value == null) return true;
        String path = FileIndex.normalize(value);
        if (path.isEmpty() || path.equals("null")) return true;
        checkedRefs.add(source.name + "|" + rel + "|" + path.toLowerCase(Locale.ROOT));
        if (!files.exists(path)) {
            report.add(ifMissing, source, rel, field + " \"" + value + "\" does not exist in this mod, the base game or"
                    + " any enabled mod. " + consequence);
            return false;
        }
        if (!files.exactCaseExists(path)) {
            String actual = files.find(path).get(0).path();
            Report.Severity onThisOs = caseSensitiveFiles || ifMissing == Report.Severity.NOTE ? ifMissing : Report.Severity.WARNING;
            report.add(onThisOs, source, rel, field + " \"" + value
                    + "\" only matches \"" + actual + "\" when upper and lower case are ignored. Windows does that, Linux and"
                    + " macOS do not: there the file is missing. " + consequence);
        } else if (value.contains("\\")) {
            report.warning(source, rel, field + " \"" + value + "\" uses backslashes. That only works on Windows; write"
                    + " forward slashes (/).");
        }
        return true;
    }
}
