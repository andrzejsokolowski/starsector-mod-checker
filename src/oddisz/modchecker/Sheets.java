package oddisz.modchecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CSV sheets. The game reads every copy of a sheet (mods first, base game last) and keeps the
 * first row it sees for each id, so a mod can change a base game row. Within one file a repeated id
 * stops the game ("Duplicate key"), as does an unclosed quote or a missing id column.
 */
final class Sheets {
    /** One row the game keeps, with the file it came from. */
    record Entry(Source source, String path, GameCsv.Row row) {
    }

    private static final String CRASH = Fields.LOAD_CRASH;

    /** Sheets the game reads by id; the value lists the id columns. */
    static final Map<String, List<String>> KEYED = new LinkedHashMap<>();

    static {
        for (String p : new String[] {"data/hulls/ship_data.csv", "data/weapons/weapon_data.csv", "data/hulls/wing_data.csv",
                "data/hullmods/hull_mods.csv", "data/shipsystems/ship_systems.csv", "data/campaign/submarkets.csv",
                "data/campaign/market_conditions.csv", "data/campaign/abilities.csv", "data/campaign/industries.csv",
                "data/campaign/commodities.csv", "data/campaign/special_items.csv", "data/characters/skills/skill_data.csv",
                "data/characters/skills/aptitude_data.csv", "data/characters/personalities.csv",
                "data/campaign/procgen/star_gen_data.csv", "data/campaign/procgen/age_gen_data.csv",
                "data/campaign/procgen/objective_gen_data.csv", "data/campaign/procgen/location_gen_data.csv",
                "data/campaign/procgen/condition_gen_data.csv", "data/campaign/procgen/salvage_entity_gen_data.csv"}) {
            KEYED.put(p, List.of("id"));
        }
        KEYED.put("data/world/factions/factions.csv", List.of("faction"));
        KEYED.put("data/missions/mission_list.csv", List.of("mission"));
        KEYED.put("data/world/generators.csv", List.of("className"));
        KEYED.put("data/campaign/person_missions.csv", List.of("mission id"));
        KEYED.put("data/campaign/bar_events.csv", List.of("bar event id"));
        KEYED.put("data/strings/descriptions.csv", List.of("id", "type"));
    }

    /** Sheets whose classes the game creates only when it needs them, not at startup. */
    static final Set<String> LAZY_SCRIPTS = Set.of("data/campaign/bar_events.csv", "data/campaign/person_missions.csv");

    /** Columns that name a class the game loads (at startup, except the sheets in LAZY_SCRIPTS). */
    static final Map<String, List<String>> SCRIPT_COLUMNS = Map.of(
            "data/campaign/industries.csv", List.of("plugin"),
            "data/campaign/market_conditions.csv", List.of("script"),
            "data/campaign/submarkets.csv", List.of("script"),
            "data/campaign/abilities.csv", List.of("plugin", "ai"),
            "data/campaign/special_items.csv", List.of("plugin"),
            "data/world/generators.csv", List.of("className"),
            "data/campaign/bar_events.csv", List.of("plugin"),
            "data/campaign/person_missions.csv", List.of("plugin"));

    private final Checker c;
    private final Set<String> loadedSheets = new HashSet<>();
    private Map<String, Entry> hullRows = Map.of();

    Sheets(Checker checker) {
        this.c = checker;
    }

    /** All copies of a sheet merged the game's way, with the per-file errors reported. */
    List<Entry> load(String rel, List<String> keys) {
        loadedSheets.add(rel.toLowerCase());
        List<FileIndex.Hit> hits = new ArrayList<>(c.files.find(rel));
        for (FileIndex.Hit hit : hits) {
            if (!hit.source().isCore() && hit.source().mod.replace.stream().anyMatch(r -> r.equalsIgnoreCase(rel))) {
                hits = List.of(hit);
                break;
            }
        }
        List<Entry> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FileIndex.Hit hit : hits) {
            Fields f = new Fields(c.report, hit.source(), hit.path());
            GameCsv.Table t = c.table(hit.source(), hit.path());
            if (t.error != null) {
                f.error(t.error + ". " + CRASH + " (Mismatched quotes in the string)");
                continue;
            }
            if (t.rows.isEmpty()) continue;
            if (t.bom) {
                f.error("The file starts with a UTF-8 BOM (an invisible marker some editors add), so the game reads the first"
                        + " column name as \"\\uFEFF" + t.header.get(0) + "\". " + CRASH + " Save it as UTF-8 without BOM.");
                continue;
            }
            boolean columnsOk = true;
            for (String key : keys) {
                if (!t.header.contains(key)) {
                    f.error("Has no \"" + key + "\" column (first line: " + String.join(",", t.header) + "). " + CRASH
                            + " (JSONObject[\"" + key + "\"] not found)");
                    columnsOk = false;
                }
            }
            if (!columnsOk) continue;
            Map<String, Integer> lineOfKey = new HashMap<>();
            for (GameCsv.Row row : t.rows) {
                StringBuilder key = new StringBuilder();
                boolean allEmpty = true;
                boolean shortRow = false;
                for (String k : keys) {
                    String v = row.get(k);
                    if (v == null) {
                        shortRow = true;
                        break;
                    }
                    key.append(v).append(" | ");
                    allEmpty &= v.isEmpty();
                }
                if (shortRow) {
                    f.error("Line " + row.line + " has fewer columns than the header, so it has no \"" + keys.get(0) + "\". "
                            + CRASH);
                    continue;
                }
                if (allEmpty) continue;
                Integer firstLine = lineOfKey.putIfAbsent(key.toString(), row.line);
                if (firstLine != null) {
                    f.error("\"" + key.substring(0, key.length() - 3) + "\" appears twice in this file (lines " + firstLine
                            + " and " + row.line + "). " + CRASH + " (Duplicate key [" + key + "])");
                    continue;
                }
                if (seen.add(key.toString())) kept.add(new Entry(hit.source(), hit.path(), row));
            }
        }
        Set<String> ids = c.sheetIds.computeIfAbsent(rel.toLowerCase(java.util.Locale.ROOT), k -> new HashSet<>());
        for (Entry e : kept) ids.add(e.row.get(keys.get(0)));
        return kept;
    }

    private static Map<String, Entry> byId(List<Entry> entries, String column) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) map.putIfAbsent(e.row.get(column), e);
        return map;
    }

    private static Fields fields(Checker c, Entry e) {
        return new Fields(c.report, e.source, e.path);
    }

    private static String at(Entry e) {
        return "line " + e.row.line + " (\"" + e.row.cells.values().stream().filter(v -> !v.isEmpty()).findFirst().orElse("")
                + "\"): ";
    }

    private static boolean number(String v) {
        return v != null && Fields.isNumber(v);
    }

    /** A comma separated list of enum names inside one cell, read with Enum.valueOf. */
    private void enumCell(Entry e, String column, Set<String> allowed) {
        String v = e.row.get(column);
        if (v == null || v.isEmpty()) return;
        for (String part : v.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !allowed.contains(name)) {
                fields(c, e).error(at(e) + "\"" + column + "\" contains \"" + name + "\", which the game does not know. Allowed: "
                        + String.join(", ", allowed) + ". " + CRASH);
            }
        }
    }

    private void requiredEnum(Entry e, String column, Set<String> allowed) {
        String v = e.row.get(column);
        if (v == null || v.isEmpty()) {
            fields(c, e).error(at(e) + "\"" + column + "\" is empty. " + CRASH + " (Key [" + column + "] is required)");
        } else if (!allowed.contains(v)) {
            fields(c, e).error(at(e) + "\"" + column + "\" is \"" + v + "\", which the game does not know. Allowed: "
                    + String.join(", ", allowed) + ". " + CRASH);
        }
    }

    private void optionalEnum(Entry e, String column, Set<String> allowed) {
        String v = e.row.get(column);
        if (v != null && !v.isEmpty() && !allowed.contains(v)) {
            fields(c, e).error(at(e) + "\"" + column + "\" is \"" + v + "\", which the game does not know. Allowed: "
                    + String.join(", ", allowed) + ". " + CRASH);
        }
    }

    private void requiredNumber(Entry e, String column) {
        String v = e.row.get(column);
        if (v == null) {
            fields(c, e).error(at(e) + "has no \"" + column + "\" column. " + CRASH);
        } else if (!number(v)) {
            fields(c, e).error(at(e) + "\"" + column + "\" is \"" + v + "\", not a number. " + CRASH
                    + " (JSONObject[\"" + column + "\"] is not a number)");
        }
    }

    private void requiredColumn(Entry e, String column) {
        if (e.row.get(column) == null) fields(c, e).error(at(e) + "has no \"" + column + "\" column. " + CRASH);
    }

    // ---------------------------------------------------------------- weapon_data.csv

    void weaponData() {
        Map<String, Entry> rows = byId(load("data/weapons/weapon_data.csv", List.of("id")), "id");
        for (Map.Entry<String, Entry> r : rows.entrySet()) {
            Entry e = r.getValue();
            Checker.Spec weapon = c.weapons.get(r.getKey());
            if (weapon == null) {
                fields(c, e).note(at(e) + "a row for the weapon \"" + r.getKey() + "\", but no .wpn file defines it. The game"
                        + " ignores the row.");
                continue;
            }
            requiredColumn(e, "name");
            enumCell(e, "hints", Enums.AI_HINT);
            optionalEnum(e, "type", Enums.DAMAGE_TYPE);
            String specClass = weapon.json.optString("specClass");
            if (!specClass.equals("beam")) {
                String proj = weapon.json.optString("projectileSpecId");
                if (!proj.isEmpty() && !c.projectiles.containsKey(proj)) {
                    new Fields(c.report, weapon).error("\"projectileSpecId\" \"" + proj + "\" is not the id of any .proj file in"
                            + " data/weapons/proj or data/shipsystems/proj. " + CRASH + " (Projectile spec for weapon ["
                            + r.getKey() + "] is null - most likely not found)");
                }
            }
        }
        for (Checker.Spec weapon : c.weapons.values()) {
            if (rows.containsKey(weapon.id)) continue;
            String reason = "has a .wpn file (" + weapon.owner.name + ") but no row in data/weapons/weapon_data.csv, so the"
                    + " game drops it";
            c.droppedWeapons.put(weapon.id, reason);
            new Fields(c.report, weapon).note("Has no row in data/weapons/weapon_data.csv, so the game drops this weapon."
                    + " Fine for a leftover file; anything that uses it is reported separately.");
        }
    }

    // ---------------------------------------------------------------- ship_data.csv

    void shipData() {
        Map<String, Entry> rows = byId(load("data/hulls/ship_data.csv", List.of("id")), "id");
        for (Map.Entry<String, Entry> r : rows.entrySet()) {
            Entry e = r.getValue();
            if (!c.hulls.containsKey(r.getKey())) {
                fields(c, e).note(at(e) + "a row for the hull \"" + r.getKey() + "\", but no .ship file in data/hulls defines it."
                        + " The game ignores the row.");
                continue;
            }
            enumCell(e, "hints", Enums.SHIP_HINT);
            requiredEnum(e, "shield type", Enums.SHIELD_TYPE);
            requiredNumber(e, "mass");
        }
        for (Checker.Spec hull : c.hulls.values()) {
            if (rows.containsKey(hull.id)) continue;
            c.droppedHulls.put(hull.id, "has a .ship file (" + hull.owner.name + ") but no row in data/hulls/ship_data.csv,"
                    + " so the game drops it");
            new Fields(c.report, hull).note("Has no row in data/hulls/ship_data.csv, so the game drops this hull. Fine for a"
                    + " leftover file; variants and skins that use it are reported separately.");
        }
        // Ship systems are loaded after this sheet; their references are checked in shipSystems().
        hullRows = rows;
    }

    // ---------------------------------------------------------------- ship_systems.csv

    void shipSystems() {
        Map<String, Entry> rows = byId(load("data/shipsystems/ship_systems.csv", List.of("id")), "id");
        for (Map.Entry<String, Entry> r : rows.entrySet()) {
            Entry e = r.getValue();
            if (!c.systems.containsKey(r.getKey())) {
                fields(c, e).note(at(e) + "a row for the ship system \"" + r.getKey() + "\", but no .system file in"
                        + " data/shipsystems defines it. The game ignores the row.");
                continue;
            }
            requiredColumn(e, "name");
            c.systemRows.put(r.getKey(), e);
        }
        for (Checker.Spec system : c.systems.values()) {
            if (!rows.containsKey(system.id)) {
                new Fields(c.report, system).warning("Has no row in data/shipsystems/ship_systems.csv. Ships with this system"
                        + " get no name or flux cost for it, and the game logs an error.");
            }
        }
        // Hull rows name their systems: the game logs "Ship system [x] not found" and the ship has no working system.
        for (Map.Entry<String, Entry> r : hullRows.entrySet()) {
            if (!c.hulls.containsKey(r.getKey())) continue;
            Entry e = r.getValue();
            for (String column : new String[] {"system id", "defense id"}) {
                String system = e.row.get(column);
                if (system == null || system.isEmpty() || c.systems.containsKey(system)) continue;
                fields(c, e).warning(at(e) + "\"" + column + "\" is \"" + system + "\", which is not a ship system in the base"
                        + " game or any enabled mod. The ship has no working system and can crash when it tries to use it.");
            }
        }
    }

    // ---------------------------------------------------------------- hull_mods.csv

    void hullMods() {
        List<Entry> entries = load("data/hullmods/hull_mods.csv", List.of("id"));
        for (Entry e : entries) {
            String id = e.row.get("id");
            c.hullmodRows.putIfAbsent(id, e);
            requiredColumn(e, "name");
            for (String column : new String[] {"cost_frigate", "cost_dest", "cost_cruiser", "cost_capital"}) {
                requiredNumber(e, column);
            }
            String script = e.row.get("script");
            if (script == null) {
                requiredColumn(e, "script");
            } else {
                c.checkClass(fields(c, e), at(e) + "\"script\"", script);
            }
        }
    }

    // ---------------------------------------------------------------- wing_data.csv

    void wings() {
        for (Entry e : load("data/hulls/wing_data.csv", List.of("id"))) {
            c.wingRows.putIfAbsent(e.row.get("id"), e);
            requiredNumber(e, "refit");
            requiredEnum(e, "formation", Enums.FORMATION);
            requiredEnum(e, "role", Enums.WING_ROLE);
            requiredColumn(e, "variant");
            requiredColumn(e, "role desc");
            requiredNumber(e, "num");
        }
    }

    /** Wing rows name the fighter variant; that variant has to exist once all variants are loaded. */
    void wingVariants(Rules rules) {
        for (Entry e : c.wingRows.values()) {
            String variant = e.row.get("variant");
            if (variant != null && !variant.isEmpty() && !rules.variantExists(variant)) {
                fields(c, e).error(at(e) + "the fighter variant \"" + variant + "\" does not exist in the base game or any enabled"
                        + " mod. The game crashes when this wing is used (Ship hull variant [" + variant + "] not found!).");
            }
        }
    }

    // ---------------------------------------------------------------- the other sheets

    void runLate() {
        for (Map.Entry<String, List<String>> sheet : KEYED.entrySet()) {
            if (loadedSheets.contains(sheet.getKey())) continue;
            List<Entry> entries = load(sheet.getKey(), sheet.getValue());
            if (sheet.getKey().equals("data/strings/descriptions.csv")) {
                for (Entry e : entries) optionalEnum(e, "type", Enums.DESCRIPTION_TYPE);
            }
            if (sheet.getKey().startsWith("data/characters/skills/")) {
                for (Entry e : entries) c.skillIds.add(e.row.get("id"));
            }
            if (sheet.getKey().equals("data/world/factions/factions.csv")) {
                for (Entry e : entries) c.factionFiles.add(FileIndex.normalize(e.row.get("faction")).toLowerCase(java.util.Locale.ROOT));
            }
            List<String> scripts = SCRIPT_COLUMNS.get(sheet.getKey());
            if (scripts == null) continue;
            for (Entry e : entries) {
                for (String column : scripts) {
                    String name = e.row.get(column);
                    if (name != null && !name.trim().isEmpty()) {
                        c.checkClass(fields(c, e), at(e) + "\"" + column + "\"", name, !LAZY_SCRIPTS.contains(sheet.getKey()));
                    }
                }
            }
        }
    }

    /** Whether the game reads this sheet by id (so a broken copy is a crash rather than a warning). */
    static boolean isKnown(String rel) {
        for (String k : KEYED.keySet()) if (k.equalsIgnoreCase(rel)) return true;
        return false;
    }
}
