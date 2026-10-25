package oddisz.modchecker;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks on every data file of every enabled mod: can the game read it, do the art and sound files it
 * names exist, is it in a folder the game actually reads, and do the classes it names exist.
 */
final class Extras {
    /** A whole string that is one art or sound path (not a "from -> to" map or a list). */
    private static final Pattern FILE_REF = Pattern.compile("(?i)(graphics|sounds)/[^,;|<>*?\"]+\\.(png|jpg|jpeg|webp|gif|ogg|wav)");
    private static final Set<String> JSON_SUFFIXES = Enums.of("json", "faction", "skill", "wpn", "proj", "ship", "skin",
            "variant", "system");
    /**
     * JSON files the base game itself reads at startup, merged across mods. Taken from the "Loading JSON
     * from" lines of a 0.98a starsector.log, not from the loader code: some loaders there (channels.json)
     * never run.
     */
    private static final Set<String> CORE_JSON = Enums.of("data/config/settings.json", "data/config/sounds.json",
            "data/config/hull_styles.json", "data/config/engine_styles.json", "data/config/planets.json",
            "data/config/custom_entities.json", "data/config/battle_objectives.json", "data/config/tag_data.json",
            "data/config/contact_tag_data.json", "data/campaign/events.json", "data/campaign/terrain.json",
            "data/campaign/pings.json", "data/campaign/starmap.json", "data/campaign/econ/economy.json",
            "data/world/factions/default_ship_roles.json", "data/world/factions/default_fleet_type_names.json",
            "data/world/factions/default_ranks.json", "data/strings/strings.json", "data/strings/tips.json",
            "data/strings/ship_names.json");
    /** Sheets read at startup with the game's CSV reader but not by id. */
    private static final Set<String> OTHER_SHEETS = Enums.of("data/campaign/rules.csv", "data/config/lunasettings.csv",
            "data/config/version/version_files.csv");

    private final Checker c;

    Extras(Checker checker, Rules rules) {
        this.c = checker;
    }

    void run() {
        for (String rel : CORE_JSON) c.effective(rel);
        for (String rel : c.factionFiles) c.effective(rel);

        for (Source source : c.mods.enabled) {
            List<String> all = c.files.all(source);
            for (String rel : all) {
                String lower = rel.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("data/")) continue;
                String suffix = lower.substring(lower.lastIndexOf('.') + 1);
                if (JSON_SUFFIXES.contains(suffix)) json(source, rel, lower, suffix);
                else if (suffix.equals("csv")) csv(source, rel, lower);
                unloaded(source, rel, lower, suffix);
            }
            Fields info = new Fields(c.report, source, "mod_info.json");
            if (source.mod.modPlugin != null && !source.mod.modPlugin.isEmpty()) {
                c.checkClass(info, "\"modPlugin\"", source.mod.modPlugin);
            }
            nestedMods(source);
        }
    }

    // ---------------------------------------------------------------- JSON files

    /** Whether the base game itself reads this file (as opposed to a mod's own code, or nobody). */
    private boolean gameReads(String lower, String suffix) {
        if (suffix.equals("faction")) return c.factionFiles.contains(lower);
        if (suffix.equals("json")) return CORE_JSON.contains(lower);
        if (suffix.equals("csv")) return Sheets.isKnown(lower) || OTHER_SHEETS.contains(lower);
        return loadedSpec(lower, suffix);
    }

    private void json(Source source, String rel, String lower, String suffix) {
        boolean spec = !suffix.equals("json");
        boolean loaded = gameReads(lower, suffix);
        // Spec files outside the folders the game reads (translations, backups) are never loaded.
        if (spec && !loaded) return;
        byte[] bytes = c.bytes(source, rel);
        if (bytes == null) return;
        JSONObject o = c.parse(source, rel, null, null);
        if (o == null) {
            if (isJsonArray(bytes)) return;
            c.parse(source, rel, loaded ? Report.Severity.ERROR : Report.Severity.WARNING,
                    loaded ? Fields.LOAD_CRASH : "If the game or a mod reads this file, it crashes or ignores the file.");
            return;
        }
        Fields f = new Fields(c.report, source, rel);
        if (suffix.equals("skill")) {
            // The game skips a .skill file whose id is not in skill_data.csv or aptitude_data.csv.
            if (!c.skillIds.contains(o.optString("id"))) {
                f.note("The skill \"" + o.optString("id") + "\" is not listed in skill_data.csv or aptitude_data.csv, so the"
                        + " game skips this file.");
                return;
            }
            skillScripts(f, o);
        }
        scanRefs(source, rel, o, "", loaded);
        if (lower.equals("data/config/settings.json")) {
            JSONObject plugins = o.optJSONObject("plugins");
            if (plugins != null) {
                for (String key : Rules.keys(plugins)) c.checkClass(f, "\"plugins\" entry \"" + key + "\"", plugins.optString(key));
            }
        }
    }

    private static boolean isJsonArray(byte[] bytes) {
        String text = GameJson.stripComments(GameJson.text(bytes)).trim();
        if (!text.startsWith("[")) return false;
        try {
            new JSONArray(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void skillScripts(Fields f, JSONObject o) {
        JSONArray groups = o.optJSONArray("effectGroups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            JSONArray effects = group == null ? null : group.optJSONArray("effects");
            for (int j = 0; effects != null && j < effects.length(); j++) {
                JSONObject effect = effects.optJSONObject(j);
                if (effect != null && effect.has("script")) {
                    c.checkClass(f, "effect script in group " + (i + 1), effect.optString("script"));
                }
            }
        }
    }

    /**
     * Every string that looks like an art or sound path must point at a file that exists. A warning in
     * files the game reads; a note in files only a mod reads (or nobody: copies, placeholders, optional
     * GraphicsLib maps), since those mods often check first.
     */
    private void scanRefs(Source source, String rel, Object value, String key, boolean gameReads) {
        if (value instanceof JSONObject o) {
            Iterator<?> keys = o.keys();
            while (keys.hasNext()) {
                String k = (String) keys.next();
                scanRefs(source, rel, o.opt(k), k, gameReads);
            }
        } else if (value instanceof JSONArray a) {
            for (int i = 0; i < a.length(); i++) scanRefs(source, rel, a.opt(i), key, gameReads);
        } else if (value instanceof String s) {
            reference(source, rel, key.isEmpty() ? "A path" : "\"" + key + "\"", s, gameReads);
        }
    }

    private void reference(Source source, String rel, String field, String value, boolean gameReads) {
        String path = FileIndex.normalize(value);
        if (path.contains("->") || !FILE_REF.matcher(path).matches()) return;
        if (c.checkedRefs.contains(source.name + "|" + rel + "|" + path.toLowerCase(Locale.ROOT))) return;
        if (gameReads) {
            c.checkFile(source, rel, field, value, Report.Severity.WARNING,
                    "The game crashes or shows nothing when it needs this file.");
        } else {
            c.checkFile(source, rel, field, value, Report.Severity.NOTE,
                    "Only matters if the mod that reads this file uses that entry.");
        }
    }

    // ---------------------------------------------------------------- CSV files

    private void csv(Source source, String rel, String lower) {
        if (Sheets.isKnown(lower)) {
            scanCells(source, rel, true);
            return;
        }
        GameCsv.Table t = c.table(source, rel);
        Fields f = new Fields(c.report, source, rel);
        boolean read = OTHER_SHEETS.contains(lower);
        if (t.error != null) {
            if (read) f.error(t.error + ". The game crashes while loading (Mismatched quotes in the string).");
            else f.warning(t.error + ". If the game or a mod reads this sheet, it crashes (Mismatched quotes in the string).");
            return;
        }
        if (t.bom && !t.rows.isEmpty()) {
            f.warning("The file starts with a UTF-8 BOM (an invisible marker some editors add), so the first column is read"
                    + " as \"\\uFEFF" + t.header.get(0) + "\" instead of \"" + t.header.get(0) + "\". Save it as UTF-8 without BOM.");
        }
        if (lower.equals("data/config/lunasettings.csv")) lunaPercent(f, t);
        scanCells(source, rel, read);
    }

    private void scanCells(Source source, String rel, boolean gameReads) {
        GameCsv.Table t = c.table(source, rel);
        if (t.error != null) return;
        for (GameCsv.Row row : t.rows) {
            for (java.util.Map.Entry<String, String> cell : row.cells.entrySet()) {
                reference(source, rel, "line " + row.line + " \"" + cell.getKey() + "\"", cell.getValue(), gameReads);
            }
        }
    }

    /** LunaLib passes setting names and descriptions through String.format, so a lone % crashes the tab. */
    private static void lunaPercent(Fields f, GameCsv.Table t) {
        for (GameCsv.Row row : t.rows) {
            for (String column : new String[] {"fieldName", "fieldDescription"}) {
                String v = row.get(column);
                if (v == null) continue;
                String rest = v.replace("%%", "").replace("%n", "");
                if (rest.contains("%")) {
                    f.error("line " + row.line + " \"" + column + "\" contains a % sign (\"" + shorten(v) + "\"). LunaLib runs this"
                            + " text through String.format, so the game crashes when that settings tab opens. Write %% or"
                            + " \"percent\" instead.");
                }
            }
        }
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    // ---------------------------------------------------------------- files in the wrong folder

    /** Whether the game loads a spec file from this place (it never looks deeper than shown here). */
    static boolean loadedSpec(String lower, String suffix) {
        String parent = FileIndex.parentOf(lower);
        String grand = FileIndex.parentOf(parent);
        return switch (suffix) {
            case "wpn" -> parent.equals("data/weapons") || parent.equals("data/shipsystems/wpn");
            case "proj" -> parent.equals("data/weapons/proj") || parent.equals("data/shipsystems/proj");
            case "ship" -> parent.equals("data/hulls");
            case "skin" -> parent.equals("data/hulls/skins") || grand.equals("data/hulls/skins");
            case "variant" -> parent.equals("data/variants") || grand.equals("data/variants");
            case "system" -> parent.equals("data/shipsystems");
            case "skill" -> parent.equals("data/characters/skills");
            case "faction" -> parent.equals("data/world/factions");
            default -> true;
        };
    }

    /**
     * A spec file inside the right family of folders but too deep, or in a sibling folder, is a mistake.
     * Files in other places (data/variants_CN and the like) are left alone: those are kept on purpose.
     */
    private void unloaded(Source source, String rel, String lower, String suffix) {
        if (loadedSpec(lower, suffix)) return;
        String where = switch (suffix) {
            case "wpn" -> family(lower, "data/weapons/", "data/shipsystems/") ? "data/weapons and data/shipsystems/wpn" : null;
            case "proj" -> family(lower, "data/weapons/", "data/shipsystems/") ? "data/weapons/proj and data/shipsystems/proj" : null;
            case "ship" -> family(lower, "data/hulls/") ? "data/hulls" : null;
            case "skin" -> family(lower, "data/hulls/") ? "data/hulls/skins and the folders directly inside it" : null;
            case "variant" -> family(lower, "data/variants/") ? "data/variants and the folders directly inside it" : null;
            case "system" -> family(lower, "data/shipsystems/") ? "data/shipsystems" : null;
            default -> null;
        };
        if (where != null) {
            // A note: an unread file cannot break anything by itself, and whatever expected it to load is reported
            // as a missing id where it is used.
            c.report.note(source, rel, "The game never reads this file: it only loads ." + suffix + " files from " + where
                    + ". Move it there, or delete it if it is a leftover.");
        }
    }

    private static boolean family(String lower, String... roots) {
        for (String root : roots) if (lower.startsWith(root)) return true;
        return false;
    }

    /** A whole mod copied inside another mod's folder is never loaded. */
    private void nestedMods(Source source) {
        for (String dir : c.files.subdirs(source, "")) {
            if (c.files.inSource(source, dir + "/mod_info.json") != null) {
                c.report.note(source, dir + "/mod_info.json", "This folder inside the mod is a complete mod of its own. The game"
                        + " ignores it here; it only loads mods placed straight in mods/.");
            }
        }
    }
}
