package oddisz.modchecker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The spec files: .proj, .wpn, .ship, .skin, .system and .variant, plus the CSV sheets they depend on,
 * in the order the game loads them. Every rule names the crash it prevents.
 */
final class Rules {
    private static final String CRASH = Fields.LOAD_CRASH;
    private static final String MISSING_ART = "The game crashes while loading (\"Error loading [...] resource, not found\").";

    private final Checker c;
    private final Sheets sheets;
    /** Weapon slot id to mount (TURRET, HARDPOINT, HIDDEN), per hull and skin id. */
    private final Map<String, Map<String, String>> slotsByHull = new LinkedHashMap<>();
    private final Map<String, Integer> engineCountByHull = new LinkedHashMap<>();
    /** Weapons with more hardpoint barrels than turret barrels: id to "hardpoint/turret" counts. */
    private final Map<String, int[]> hiddenRisk = new LinkedHashMap<>();
    private final List<Runnable> afterVariants = new ArrayList<>();
    private final Map<String, List<Runnable>> weaponArt = new LinkedHashMap<>();
    /** Checks that only matter for hulls the game keeps after ship_data.csv. */
    private final Map<String, List<Runnable>> hullLate = new LinkedHashMap<>();
    /** Weapons without barrels: id to the problem text, graded once we know whether anything mounts them. */
    private final Map<String, String> noBarrels = new LinkedHashMap<>();

    Rules(Checker checker) {
        this.c = checker;
        this.sheets = new Sheets(checker);
    }

    Sheets sheets() {
        return sheets;
    }

    void run() {
        loadStyles();
        for (String rel : c.listLoaded("data/weapons/proj", "proj", false)) loadProjectile(rel);
        for (String rel : c.listLoaded("data/shipsystems/proj", "proj", false)) loadProjectile(rel);
        for (String rel : c.listLoaded("data/weapons", "wpn", false)) loadWeapon(rel);
        for (String rel : c.listLoaded("data/shipsystems/wpn", "wpn", false)) loadWeapon(rel);
        sheets.weaponData();
        weaponArt.forEach((id, art) -> {
            if (weaponExists(id)) art.forEach(Runnable::run);
        });
        for (String rel : c.listLoaded("data/hulls", "ship", false)) loadHull(rel);
        sheets.shipData();
        hullLate.forEach((id, checks) -> {
            if (hullExists(id)) checks.forEach(Runnable::run);
        });
        for (String rel : c.listLoaded("data/hulls/skins", "skin", true)) loadSkin(rel);
        for (String rel : c.listLoaded("data/shipsystems", "system", false)) loadSystem(rel);
        sheets.shipSystems();
        sheets.hullMods();
        sheets.wings();
        for (String rel : c.listLoaded("data/variants", "variant", true)) loadVariant(rel);
        for (Runnable r : afterVariants) r.run();
        sheets.wingVariants(this);
        hiddenSlotBarrels();
        weaponsWithoutBarrels();
    }

    // ---------------------------------------------------------------- lookups

    boolean weaponExists(String id) {
        return c.weapons.containsKey(id) && !c.droppedWeapons.containsKey(id);
    }

    boolean hullExists(String id) {
        if (id == null) return false;
        if (c.skins.containsKey(id)) return true;
        if (c.hulls.containsKey(id) && !c.droppedHulls.containsKey(id)) return true;
        return id.endsWith("_default_D") && hullExists(id.substring(0, id.length() - "_default_D".length()));
    }

    /** Variants from .variant files plus the empty "<hull>_Hull" variant the game makes for every hull. */
    boolean variantExists(String id) {
        if (id == null) return false;
        if (c.variants.containsKey(id)) return true;
        return id.endsWith("_Hull") && hullExists(id.substring(0, id.length() - "_Hull".length()));
    }

    private String whyNoWeapon(String id) {
        String dropped = c.droppedWeapons.get(id);
        if (dropped != null) return "weapon \"" + id + "\" " + dropped;
        return "weapon \"" + id + "\" does not exist in the base game or any enabled mod";
    }

    private String whyNoHull(String id) {
        String dropped = c.droppedHulls.get(id);
        if (dropped != null) return "hull \"" + id + "\" " + dropped;
        return "hull \"" + id + "\" does not exist in the base game or any enabled mod";
    }

    private void duplicate(String kind, String id, Checker.Spec kept, Checker.Spec ignored) {
        if (kept.owner == ignored.owner && kept.path.equalsIgnoreCase(ignored.path)) return;
        new Fields(c.report, ignored).note("Defines " + kind + " \"" + id + "\", which " + kept.owner.name + " (" + kept.path
                + ") also defines. The game keeps the first one it loads and ignores this one.");
    }

    // ---------------------------------------------------------------- styles

    private void loadStyles() {
        Checker.Spec hull = c.effective("data/config/hull_styles.json");
        if (hull != null) c.hullStyles.addAll(keys(hull.json));
        Checker.Spec engine = c.effective("data/config/engine_styles.json");
        if (engine != null) c.engineStyles.addAll(keys(engine.json));
    }

    static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        Iterator<?> it = o.keys();
        while (it.hasNext()) out.add((String) it.next());
        return out;
    }

    // ---------------------------------------------------------------- scripts

    /** A class named in data must exist in a jar or as a .java file, or the game crashes at startup. */
    void script(Fields f, JSONObject o, String key, String label) {
        if (!o.has(key)) return;
        c.checkClass(f, label + "\"" + key + "\"", o.optString(key));
    }

    // ---------------------------------------------------------------- projectiles (.proj)

    private void loadProjectile(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        String id = f.requireText(s.json, "id", "");
        if (id == null) return;
        s.id = id;
        Checker.Spec first = c.projectiles.get(id);
        if (first != null) {
            duplicate("projectile", id, first, s);
            return;
        }
        c.projectiles.put(id, s);
        JSONObject o = s.json;
        String specClass = f.requireText(o, "specClass", "");
        if (specClass == null) return;
        if (specClass.equals("projectile")) {
            script(f, o, "onHitEffect", "");
            script(f, o, "onFireEffect", "");
            f.enumValue(o, "spawnType", Enums.SPAWN_TYPE, true, "");
            f.enumValue(o, "collisionClass", Enums.COLLISION_CLASS, true, "");
            f.enumValue(o, "collisionClassByFighter", Enums.COLLISION_CLASS, true, "");
            f.optionalNumber(o, "length", "");
            f.requireNumber(o, "width", "");
            f.requireNumber(o, "fadeTime", "");
            f.color(o, "fringeColor", "");
            f.color(o, "coreColor", "");
            f.color(o, "glowColor", "");
            List<Runnable> art = new ArrayList<>();
            textureType(f, s, o, false, art);
            art.forEach(Runnable::run);
            c.checkFile(s.owner, s.path, "bulletSprite", Fields.textOrNull(o, "bulletSprite"), Report.Severity.ERROR, MISSING_ART);
            behaviorSpec(f, o);
        } else if (specClass.equals("missile")) {
            f.enumValue(o, "missileType", Enums.MISSILE_TYPE, true, "");
            String sprite = f.requireText(o, "sprite", "");
            c.checkFile(s.owner, s.path, "sprite", sprite, Report.Severity.ERROR, MISSING_ART);
            f.requirePoint(o, "center", "");
            f.requirePoint(o, "size", "");
            f.requireNumber(o, "collisionRadius", "");
            JSONObject engine = f.requireObject(o, "engineSpec", "");
            if (engine != null) {
                for (String k : new String[] {"turnAcc", "turnRate", "acc", "dec"}) f.requireNumber(engine, k, "engineSpec ");
                f.optionalNumber(engine, "maxSpeed", "engineSpec ");
            }
            f.enumValue(o, "collisionClass", Enums.COLLISION_CLASS, true, "");
            engineSlots(f, o, "engineSlots", "loc", "", null);
            c.checkFile(s.owner, s.path, "glowSprite", Fields.textOrNull(o, "glowSprite"), Report.Severity.ERROR, MISSING_ART);
            f.color(o, "glowColor", "");
            f.color(o, "explosionColor", "");
            f.color(o, "destroyedExplosionColor", "");
            f.enumValue(o, "collisionClassAfterFlameout", Enums.COLLISION_CLASS, false, "");
            script(f, o, "onHitEffect", "");
            script(f, o, "onFireEffect", "");
            behaviorSpec(f, o);
        } else {
            f.error("\"specClass\" is \"" + specClass + "\"; a .proj file needs \"projectile\" or \"missile\". " + CRASH
                    + " (Invalid specClass)");
        }
    }

    private void behaviorSpec(Fields f, JSONObject o) {
        if (!o.has("behaviorSpec")) return;
        JSONObject b = f.requireObject(o, "behaviorSpec", "");
        if (b == null) return;
        f.enumValue(b, "behavior", Enums.PROJ_BEHAVIOR, true, "behaviorSpec ");
        script(f, b, "onHitEffect", "behaviorSpec ");
        script(f, b, "onExplosionEffect", "behaviorSpec ");
    }

    /** Beams need textureType; projectiles may leave it out. A list gives the fringe and core art. */
    private void textureType(Fields f, Checker.Spec s, JSONObject o, boolean required, List<Runnable> art) {
        String v = o.optString("textureType");
        if (v.isEmpty()) {
            if (required) f.error("\"textureType\" is missing; a beam needs one of " + String.join(", ", Enums.BEAM_TEXTURE)
                    + " or a list of two textures [fringe, core]. " + CRASH);
            return;
        }
        if (Enums.BEAM_TEXTURE.contains(v)) return;
        JSONArray a = o.optJSONArray("textureType");
        if (a == null || a.length() < 2) {
            f.error("\"textureType\" is \"" + v + "\". Use one of " + String.join(", ", Enums.BEAM_TEXTURE)
                    + ", or a list of two textures [fringe, core]. " + CRASH);
            return;
        }
        String fringe = a.optString(0, ""), coreTex = a.optString(1, "");
        c.claimRef(s.owner, s.path, fringe);
        c.claimRef(s.owner, s.path, coreTex);
        art.add(() -> c.checkFile(s.owner, s.path, "textureType fringe", fringe, Report.Severity.ERROR, MISSING_ART));
        art.add(() -> c.checkFile(s.owner, s.path, "textureType core", coreTex, Report.Severity.ERROR, MISSING_ART));
    }

    /**
     * Engine slots on missiles and ships. The style is a built-in name or an id from engine_styles.json.
     * Style checks go to {@code late} when given (hulls: only kept hulls matter).
     */
    private void engineSlots(Fields f, JSONObject o, String key, String locationKey, String label, List<Runnable> late) {
        if (!o.has(key)) return;
        JSONArray slots = o.optJSONArray(key);
        if (slots == null) {
            f.error(label + "\"" + key + "\" must be a list in [ ]. " + CRASH);
            return;
        }
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            String at = label + "engine slot " + (i + 1) + ": ";
            if (slot == null) {
                f.error(at + "must be a block in { }. " + CRASH);
                continue;
            }
            f.requirePoint(slot, locationKey, at);
            for (String k : new String[] {"width", "length", "angle"}) f.requireNumber(slot, k, at);
            String where = at;
            if (late == null) engineStyle(f, slot, where);
            else late.add(() -> engineStyle(f, slot, where));
        }
    }

    private void engineStyle(Fields f, JSONObject slot, String at) {
        if (!slot.has("style")) {
            f.error(at + "\"style\" is missing. " + CRASH);
            return;
        }
        String style = slot.optString("style");
        if (style.equals("CUSTOM")) {
            if (!slot.has("styleId") && slot.optJSONObject("styleSpec") == null) {
                f.error(at + "\"style\" is CUSTOM but there is no \"styleSpec\" block. " + CRASH);
            } else if (slot.has("styleId") && !c.engineStyles.contains(slot.optString("styleId"))) {
                f.warning(at + "\"styleId\" \"" + slot.optString("styleId") + "\" is not in data/config/engine_styles.json of"
                        + " the base game or any enabled mod. The engine gets no style, which can crash the game when the"
                        + " engines are drawn.");
            }
            return;
        }
        if (Enums.ENGINE_STYLE.contains(style) || c.engineStyles.contains(style)) return;
        f.warning(at + "\"style\" \"" + style + "\" is neither a built-in engine style nor an id in"
                + " data/config/engine_styles.json of the base game or any enabled mod. The engine gets no style, which can crash"
                + " the game when the engines are drawn.");
    }

    // ---------------------------------------------------------------- weapons (.wpn)

    private void loadWeapon(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        String id = f.requireText(s.json, "id", "");
        if (id == null) return;
        s.id = id;
        Checker.Spec first = c.weapons.get(id);
        if (first != null) {
            duplicate("weapon", id, first, s);
            return;
        }
        c.weapons.put(id, s);
        JSONObject o = s.json;
        String specClass = f.requireText(o, "specClass", "");
        if (specClass == null) return;
        boolean beam = specClass.equals("beam");
        if (!beam && !specClass.equals("projectile") && !specClass.equals("pulse")) {
            f.error("\"specClass\" is \"" + specClass + "\"; a weapon needs \"projectile\", \"pulse\" or \"beam\". " + CRASH
                    + " (Invalid specClass)");
            return;
        }
        script(f, o, "everyFrameEffect", "");
        if (beam) script(f, o, "beamEffect", "");
        String type = f.enumValue(o, "type", Enums.WEAPON_TYPE, true, "");
        f.enumValue(o, "size", Enums.WEAPON_SIZE, true, "");
        f.enumValue(o, "mountTypeOverride", Enums.WEAPON_TYPE, false, "");
        String turret = f.requireText(o, "turretSprite", "");
        String hardpoint = f.requireText(o, "hardpointSprite", "");
        // The game loads weapon art only for weapons that survive weapon_data.csv, so these wait for it.
        List<Runnable> art = new ArrayList<>();
        weaponArt.put(id, art);
        for (String key : new String[] {"turretSprite", "hardpointSprite", "turretUnderSprite", "hardpointUnderSprite",
                "turretGlowSprite", "hardpointGlowSprite"}) {
            String value = Fields.textOrNull(o, key);
            c.claimRef(s.owner, s.path, value);
            art.add(() -> c.checkFile(s.owner, s.path, key, value, Report.Severity.ERROR, MISSING_ART));
        }
        f.enumList(o, "renderHints", Enums.RENDER_HINT, "", false);
        barrels(f, s, o, id, "DECORATIVE".equals(type));
        art.add(() -> animationFrames(f, o, turret, hardpoint));

        if (beam) {
            textureType(f, s, o, true, art);
            f.color(o, "fringeColor", "");
            f.color(o, "coreColor", "");
            f.color(o, "glowColor", "");
            f.enumList(o, "pierceSet", Enums.COLLISION_CLASS, "", false);
            f.enumValue(o, "collisionClass", Enums.COLLISION_CLASS, false, "");
            f.enumValue(o, "collisionClassByFighter", Enums.COLLISION_CLASS, false, "");
        } else {
            f.enumValue(o, "barrelMode", Enums.BARREL_MODE, true, "");
            f.enumValue(o, "animationType", Enums.ANIMATION_TYPE, true, "");
            f.color(o, "glowColor", "");
            for (String key : new String[] {"turretGunSprite", "hardpointGunSprite"}) {
                String value = Fields.textOrNull(o, key);
                c.claimRef(s.owner, s.path, value);
                art.add(() -> c.checkFile(s.owner, s.path, key, value, Report.Severity.ERROR, MISSING_ART));
            }
            f.requireText(o, "projectileSpecId", "");
            if (o.has("muzzleFlashSpec")) {
                JSONObject flash = f.requireObject(o, "muzzleFlashSpec", "");
                if (flash != null && f.requireNumber(flash, "particleCount", "muzzleFlashSpec ")
                        && flash.optDouble("particleCount", 0) > 0) {
                    for (String k : new String[] {"length", "spread", "particleSizeMin", "particleSizeRange", "particleDuration"}) {
                        f.requireNumber(flash, k, "muzzleFlashSpec ");
                    }
                    f.color(flash, "particleColor", "muzzleFlashSpec ");
                }
            }
        }
    }

    /**
     * Barrels: turretOffsets and hardpointOffsets hold an x,y pair per barrel; the angle lists hold one
     * angle per barrel. When loading, the game gives every barrel a 0 angle and then overwrites entry i
     * with angle i. An angle list longer than the barrel list therefore crashes at startup
     * (IndexOutOfBoundsException in addTurretAngleOffset); a shorter one is harmless, the rest stay 0.
     * In a hidden slot the game counts barrels from hardpointOffsets but reads the angles and positions
     * that come from turretOffsets, so more hardpoint barrels than turret barrels crashes in combat there.
     */
    private void barrels(Fields f, Checker.Spec s, JSONObject o, String id, boolean decorative) {
        JSONArray tOff = f.requireArray(o, "turretOffsets", "");
        JSONArray hOff = f.requireArray(o, "hardpointOffsets", "");
        JSONArray tAng = f.requireArray(o, "turretAngleOffsets", "");
        JSONArray hAng = f.requireArray(o, "hardpointAngleOffsets", "");
        if (tOff == null || hOff == null || tAng == null || hAng == null) return;
        if (!f.allNumbers(tOff, "turretOffsets", "") || !f.allNumbers(hOff, "hardpointOffsets", "")) return;
        int tBarrels = tOff.length() / 2;
        int hBarrels = hOff.length() / 2;
        String merged = s.contributors.size() > 1 ? " (after the game merged this file with the copy in "
                + Checker.otherNames(s.contributors.subList(0, s.contributors.size() - 1)) + ", which adds the lists together)" : "";
        if (tOff.length() % 2 == 1) {
            f.warning("\"turretOffsets\" has " + tOff.length() + " numbers" + merged + "; each barrel needs an x,y pair, so the"
                    + " last number is ignored. Usually a number is missing.");
        }
        if (hOff.length() % 2 == 1) {
            f.warning("\"hardpointOffsets\" has " + hOff.length() + " numbers" + merged + "; each barrel needs an x,y pair, so"
                    + " the last number is ignored. Usually a number is missing.");
        }
        angleList(f, "turret", tAng.length(), tBarrels, merged);
        angleList(f, "hardpoint", hAng.length(), hBarrels, merged);
        if (!decorative) {
            if (tBarrels == 0 && hBarrels == 0) {
                noBarrels.put(id, "Has no barrels: \"turretOffsets\" and \"hardpointOffsets\" are empty. The game crashes in"
                        + " combat the moment this weapon fires from a slot (IndexOutOfBoundsException).");
            } else if (tBarrels == 0) {
                noBarrels.put(id, "\"turretOffsets\" is empty: no turret barrels. The game crashes in combat if this weapon"
                        + " fires from a turret slot (IndexOutOfBoundsException).");
            } else if (hBarrels == 0) {
                noBarrels.put(id, "\"hardpointOffsets\" is empty: no hardpoint barrels. The game crashes in combat if this"
                        + " weapon fires from a hardpoint or hidden slot (IndexOutOfBoundsException).");
            }
        }
        if (hBarrels > tBarrels && tBarrels > 0) hiddenRisk.put(id, new int[] {hBarrels, tBarrels});
        for (int i = 0; i < tAng.length(); i++) {
            if (!Fields.isNumber(tAng.opt(i))) {
                f.warning("\"turretAngleOffsets\" has \"" + tAng.optString(i) + "\", which is not a number. That barrel's"
                        + " angle becomes invalid (NaN) and its shots fly wrong.");
                break;
            }
        }
    }

    private static void angleList(Fields f, String mount, int angles, int barrels, String merged) {
        String key = mount + "AngleOffsets";
        if (angles > barrels) {
            f.error("\"" + key + "\" has " + plural(angles, "angle") + " but \"" + mount + "Offsets\" only defines "
                    + plural(barrels, "barrel") + " (2 numbers each)" + merged + ". The game crashes while loading"
                    + " (IndexOutOfBoundsException in add" + capital(mount) + "AngleOffset). Give each barrel exactly one angle.");
        } else if (angles < barrels) {
            f.note("\"" + key + "\" has " + plural(angles, "angle") + " for " + plural(barrels, "barrel") + merged
                    + ". Harmless: the game uses 0 for the missing ones (the base game's heavy needler does this too).");
        }
    }

    private static String plural(int n, String word) {
        return Report.plural(n, word);
    }

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Animated weapons load frames 01, 02 ... by replacing "00.png" in the sprite names. */
    private void animationFrames(Fields f, JSONObject o, String turret, String hardpoint) {
        int frames = (int) o.optDouble("numFrames", 1.0);
        if (frames <= 1) return;
        List<String> missing = new ArrayList<>();
        for (String sprite : new String[] {turret, hardpoint}) {
            if (sprite == null || sprite.isEmpty()) continue;
            String base = sprite.replaceAll("00\\.png", "");
            for (int i = 1; i < frames; i++) {
                String frame = String.format("%s%02d.png", base, i);
                if (!c.files.exists(frame)) missing.add(frame);
            }
        }
        if (missing.isEmpty()) return;
        String hint = turret != null && !turret.contains("00.png")
                ? " Animated weapons need sprite names ending in 00.png; the game adds 01.png, 02.png and so on to the name"
                + " without it." : "";
        f.error("\"numFrames\" is " + frames + ", but " + plural(missing.size(), "animation frame") + " are missing: "
                + shortList(missing) + "." + hint + " " + MISSING_ART);
    }

    static String shortList(List<String> items) {
        if (items.size() <= 4) return String.join(", ", items);
        return String.join(", ", items.subList(0, 4)) + " and " + (items.size() - 4) + " more";
    }

    /**
     * A weapon without barrels is fine when scripts only use it to spawn shots. It is an error when a
     * hull or variant mounts it, a note otherwise.
     */
    private void weaponsWithoutBarrels() {
        for (Map.Entry<String, String> e : noBarrels.entrySet()) {
            if (!weaponExists(e.getKey())) continue;
            Fields f = new Fields(c.report, c.weapons.get(e.getKey()));
            String use = c.weaponUse.get(e.getKey());
            if (use != null) f.error(e.getValue() + " It is mounted: " + use + ".");
            else f.note(e.getValue() + " No hull or variant mounts it, so it is safe if scripts only use it to fire shots.");
        }
    }

    /** The barrel problem that only bites in hidden slots: an error when some hull or variant puts it there. */
    private void hiddenSlotBarrels() {
        for (Map.Entry<String, int[]> e : hiddenRisk.entrySet()) {
            Checker.Spec s = c.weapons.get(e.getKey());
            Fields f = new Fields(c.report, s);
            int h = e.getValue()[0], t = e.getValue()[1];
            String use = c.hiddenSlotUse.get(e.getKey());
            String text = "Has " + plural(h, "hardpoint barrel") + " but only " + plural(t, "turret barrel") + ". In a hidden"
                    + " slot the game counts the hardpoint barrels but reads the turret barrel angles, so firing barrel "
                    + (t + 1) + " there crashes the game in combat (IndexOutOfBoundsException in getHiddenAngleOffset).";
            if (use != null) {
                f.error(text + " It is in a hidden slot: " + use + ". Give turretOffsets and hardpointOffsets the same number"
                        + " of barrels.");
            } else {
                f.note(text + " No hull or variant puts it in a hidden slot, so it is safe for now.");
            }
        }
    }

    // ---------------------------------------------------------------- hulls (.ship)

    private void loadHull(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        String id = f.requireText(s.json, "hullId", "");
        if (id == null) return;
        s.id = id;
        Checker.Spec first = c.hulls.get(id);
        if (first != null) {
            duplicate("hull", id, first, s);
            return;
        }
        c.hulls.put(id, s);
        JSONObject o = s.json;

        String covers = o.optString("coversColor", "");
        if (!covers.isEmpty()) {
            String[] parts = covers.split(",");
            boolean ok = parts.length >= 4;
            for (int i = 0; ok && i < 4; i++) ok = parts[i].trim().matches("-?\\d+") && parts[i].equals(parts[i].trim());
            if (!ok) f.error("\"coversColor\" is \"" + covers + "\"; it must be four whole numbers \"r,g,b,a\" without spaces. "
                    + CRASH + " (NumberFormatException)");
        }
        String style = f.requireText(o, "style", "");
        if (style != null && !c.hullStyles.contains(style)) {
            f.error("\"style\" \"" + style + "\" is not in data/config/hull_styles.json of the base game or any enabled mod. "
                    + CRASH + " (NullPointerException while reading the shield colours)");
        }
        if (o.has("builtInWeapons")) f.requireObject(o, "builtInWeapons", "");
        if (o.has("builtInMods")) f.requireArray(o, "builtInMods", "");
        if (o.has("builtInWings")) f.requireArray(o, "builtInWings", "");
        String sprite = f.requireText(o, "spriteName", "");
        // Loaded only for hulls that survive ship_data.csv.
        List<Runnable> late = new ArrayList<>();
        hullLate.put(id, late);
        c.claimRef(s.owner, s.path, sprite);
        late.add(() -> c.checkFile(s.owner, s.path, "spriteName", sprite, Report.Severity.ERROR, MISSING_ART));
        f.requirePoint(o, "center", "");
        f.requireNumber(o, "width", "");
        f.requireNumber(o, "height", "");
        f.requireNumber(o, "collisionRadius", "");
        JSONArray bounds = o.optJSONArray("bounds");
        if (bounds != null && bounds.length() > 2) f.allNumbers(bounds, "bounds", "");
        f.enumValue(o, "hullSize", Enums.HULL_SIZE, true, "");
        engineSlots(f, o, "engineSlots", "location", "", late);
        JSONArray engines = o.optJSONArray("engineSlots");
        engineCountByHull.put(id, engines == null ? 0 : engines.length());

        Map<String, String> slots = new LinkedHashMap<>();
        JSONArray weaponSlots = o.optJSONArray("weaponSlots");
        if (o.has("weaponSlots") && weaponSlots == null) f.error("\"weaponSlots\" must be a list in [ ]. " + CRASH);
        for (int i = 0; weaponSlots != null && i < weaponSlots.length(); i++) {
            JSONObject slot = weaponSlots.optJSONObject(i);
            String at = "weapon slot " + (i + 1) + ": ";
            if (slot == null) {
                f.error(at + "must be a block in { }. " + CRASH);
                continue;
            }
            String slotId = f.requireText(slot, "id", at);
            if (slotId != null) at = "weapon slot \"" + slotId + "\": ";
            f.enumValue(slot, "type", Enums.WEAPON_TYPE, true, at);
            f.enumValue(slot, "size", Enums.WEAPON_SIZE, true, at);
            String mount = f.enumValue(slot, "mount", Enums.SLOT_MOUNT, true, at);
            if (slot.has("position")) f.requirePoint(slot, "position", at);
            f.requirePoint(slot, "locations", at);
            JSONArray locations = slot.optJSONArray("locations");
            if (locations != null) f.allNumbers(locations, "locations", at);
            f.requireNumber(slot, "angle", at);
            f.requireNumber(slot, "arc", at);
            if (slotId != null) {
                if (slots.containsKey(slotId)) {
                    f.note("Two weapon slots are both called \"" + slotId + "\". Variants and built-in weapons can only reach"
                            + " the first one.");
                }
                slots.put(slotId, mount == null ? "" : mount);
            }
        }
        slotsByHull.put(id, slots);
        f.requireNumber(o, "shieldRadius", "");
        f.requirePoint(o, "shieldCenter", "");
        if (o.has("moduleAnchor")) f.requirePoint(o, "moduleAnchor", "");

        afterVariants.add(() -> {
            if (hullExists(id)) builtIns(s, slots);
        });
    }

    /** Built-in weapons, hull mods and wings of a hull or skin must exist. */
    private void builtIns(Checker.Spec s, Map<String, String> slots) {
        Fields f = new Fields(c.report, s);
        JSONObject weapons = s.json.optJSONObject("builtInWeapons");
        if (weapons != null) {
            for (String slot : keys(weapons)) {
                String weapon = weapons.optString(slot);
                if (!slots.containsKey(slot)) {
                    f.warning("builtInWeapons puts \"" + weapon + "\" in slot \"" + slot + "\", which this hull does not have."
                            + " The weapon never appears.");
                } else if ("HIDDEN".equals(slots.get(slot))) {
                    c.hiddenSlotUse.putIfAbsent(weapon, "built into hull \"" + s.id + "\" (" + s.owner.name.trim() + ") slot \""
                            + slot + "\"");
                }
                if (slots.containsKey(slot)) {
                    c.weaponUse.putIfAbsent(weapon, "built into hull \"" + s.id + "\" (" + s.owner.name.trim() + ") slot \"" + slot + "\"");
                }
                if (!weaponExists(weapon)) {
                    f.error("builtInWeapons: " + whyNoWeapon(weapon) + ". " + CRASH + " (Weapon spec [" + weapon + "] not found!)");
                }
            }
        }
        JSONArray mods = s.json.optJSONArray("builtInMods");
        for (int i = 0; mods != null && i < mods.length(); i++) {
            String mod = mods.optString(i, "");
            if (!mod.isEmpty() && !c.hullmodRows.containsKey(mod)) {
                f.warning("builtInMods: the hull mod \"" + mod + "\" does not exist in the base game or any enabled mod. The"
                        + " game crashes when a ship of this hull appears (\"Hullmod " + mod + " not found!\").");
            }
        }
        JSONArray wings = s.json.optJSONArray("builtInWings");
        for (int i = 0; wings != null && i < wings.length(); i++) {
            String wing = wings.optString(i, "");
            if (!wing.isEmpty() && !c.wingRows.containsKey(wing)) {
                f.warning("builtInWings: the fighter wing \"" + wing + "\" does not exist in the base game or any enabled mod."
                        + " The game crashes when this ship launches or shows its fighters.");
            }
        }
    }

    // ---------------------------------------------------------------- skins (.skin)

    private void loadSkin(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        JSONObject o = s.json;
        String base = f.requireText(o, "baseHullId", "");
        String id = f.requireText(o, "skinHullId", "");
        if (base == null || id == null) return;
        s.id = id;
        if (c.skins.containsKey(id) || c.hulls.containsKey(id)) {
            Checker.Spec first = c.skins.containsKey(id) ? c.skins.get(id) : c.hulls.get(id);
            duplicate("hull", id, first, s);
            return;
        }
        if (!hullExists(base)) {
            f.error("\"baseHullId\": " + whyNoHull(base) + ". " + CRASH + " (Ship hull spec [" + base + "] not found!)");
            return;
        }
        c.skins.put(id, s);
        Map<String, String> slots = new LinkedHashMap<>(slotsByHull.getOrDefault(base, Map.of()));
        int engines = engineCountByHull.getOrDefault(base, 0);

        f.color(o, "coversColor", "");
        c.checkFile(s.owner, s.path, "spriteName", Fields.textOrNull(o, "spriteName"), Report.Severity.ERROR, MISSING_ART);
        for (String k : new String[] {"fleetPoints", "fpMod", "suppliesToRecover", "suppliesPerMonth", "fighterBays",
                "ordnancePoints", "baseValue", "maxSpeed", "shieldEfficiency", "baseValueMult"}) {
            if (o.has(k)) f.requireNumber(o, k, "");
        }
        JSONArray removeSlots = o.optJSONArray("removeWeaponSlots");
        for (int i = 0; removeSlots != null && i < removeSlots.length(); i++) {
            String slot = removeSlots.optString(i, "");
            if (slots.remove(slot) == null) {
                f.note("removeWeaponSlots names \"" + slot + "\", which the base hull \"" + base + "\" does not have.");
            }
        }
        JSONArray removeEngines = o.optJSONArray("removeEngineSlots");
        if (removeEngines != null) f.allNumbers(removeEngines, "removeEngineSlots", "");
        f.enumList(o, "removeHints", Enums.SHIP_HINT, "", true);
        f.enumList(o, "addHints", Enums.SHIP_HINT, "", true);
        JSONObject slotChanges = o.optJSONObject("weaponSlotChanges");
        if (slotChanges != null) {
            for (String slot : keys(slotChanges)) {
                if (!slots.containsKey(slot)) {
                    f.error("weaponSlotChanges changes slot \"" + slot + "\", which the base hull \"" + base + "\" does not have"
                            + " (or the skin removed). " + CRASH + " (NullPointerException)");
                    continue;
                }
                JSONObject change = slotChanges.optJSONObject(slot);
                if (change == null) continue;
                String at = "weaponSlotChanges \"" + slot + "\": ";
                if (change.has("angle")) f.requireNumber(change, "angle", at);
                if (change.has("arc")) f.requireNumber(change, "arc", at);
                String mount = change.has("mount") ? f.enumValue(change, "mount", Enums.SLOT_MOUNT, true, at) : null;
                if (change.has("size")) f.enumValue(change, "size", Enums.WEAPON_SIZE, true, at);
                if (change.has("type")) f.enumValue(change, "type", Enums.WEAPON_TYPE, true, at);
                if (mount != null) slots.put(slot, mount);
            }
        }
        JSONObject engineChanges = o.optJSONObject("engineSlotChanges");
        if (engineChanges != null) {
            for (String key : keys(engineChanges)) {
                int index;
                try {
                    index = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    f.error("engineSlotChanges uses \"" + key + "\" as an engine number; it must be 0, 1, 2 ... " + CRASH);
                    continue;
                }
                if (index < 0 || index >= engines) {
                    f.error("engineSlotChanges changes engine " + index + ", but the base hull \"" + base + "\" has "
                            + plural(engines, "engine") + " (numbered from 0). " + CRASH + " (IndexOutOfBoundsException)");
                    continue;
                }
                JSONObject change = engineChanges.optJSONObject(key);
                if (change == null) continue;
                String at = "engineSlotChanges \"" + key + "\": ";
                if (change.has("style") && !c.engineStyles.contains(change.optString("style"))) {
                    f.warning(at + "\"style\" \"" + change.optString("style") + "\" is not an id in"
                            + " data/config/engine_styles.json of the base game or any enabled mod. The engine gets no style, which"
                            + " can crash the game when the engines are drawn.");
                }
                for (String k : new String[] {"width", "length", "angle"}) if (change.has(k)) f.requireNumber(change, k, at);
            }
        }
        slotsByHull.put(id, slots);
        engineCountByHull.put(id, engines);
        afterVariants.add(() -> builtIns(s, slots));
    }

    // ---------------------------------------------------------------- ship systems (.system)

    private void loadSystem(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        String id = f.requireText(s.json, "id", "");
        if (id == null) return;
        s.id = id;
        Checker.Spec first = c.systems.get(id);
        if (first != null) {
            duplicate("ship system", id, first, s);
            return;
        }
        c.systems.put(id, s);
        script(f, s.json, "statsScript", "");
        script(f, s.json, "aiScript", "");
    }

    // ---------------------------------------------------------------- variants (.variant)

    private void loadVariant(String rel) {
        Checker.Spec s = c.effective(rel);
        if (s == null) return;
        Fields f = new Fields(c.report, s);
        JSONObject o = s.json;
        String id = f.requireText(o, "variantId", "");
        if (id == null) return;
        s.id = id;
        Checker.Spec first = c.variants.get(id);
        if (first != null) {
            duplicate("variant", id, first, s);
            return;
        }
        c.variants.put(id, s);
        f.requireText(o, "displayName", "");
        String hull = f.requireText(o, "hullId", "");
        boolean hullOk = hull != null && hullExists(hull);
        if (hull != null && !hullOk) {
            f.error("\"hullId\": " + whyNoHull(hull) + ". " + CRASH + " (Ship hull spec [" + hull + "] not found!)");
        }
        f.requireNumber(o, "fluxVents", "");
        f.requireNumber(o, "fluxCapacitors", "");
        Map<String, String> slots = hullOk ? slotsFor(hull) : null;

        for (String key : new String[] {"hullMods", "permaMods", "sMods"}) {
            JSONArray list = o.optJSONArray(key);
            for (int i = 0; list != null && i < list.length(); i++) {
                String mod = list.optString(i, "");
                if (!c.hullmodRows.containsKey(mod)) {
                    f.warning("\"" + key + "\": the hull mod \"" + mod + "\" does not exist in the base game or any enabled mod."
                            + " The game crashes when a ship with this loadout appears (\"Hullmod " + mod + " not found!\").");
                }
            }
        }
        if (o.has("wings")) {
            JSONArray wings = f.requireArray(o, "wings", "");
            for (int i = 0; wings != null && i < wings.length(); i++) {
                String wing = wings.optString(i, "");
                if (!wing.isEmpty() && !c.wingRows.containsKey(wing)) {
                    f.warning("\"wings\": the fighter wing \"" + wing + "\" does not exist in the base game or any enabled mod."
                            + " The game crashes when a ship with this loadout launches or shows its fighters.");
                }
            }
        }
        JSONArray groups = f.requireArray(o, "weaponGroups", "");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            String at = "weapon group " + (i + 1) + ": ";
            if (group == null) {
                f.error(at + "must be a block in { }. " + CRASH);
                continue;
            }
            f.enumValue(group, "mode", Enums.WEAPON_GROUP, true, at);
            JSONObject weapons = f.requireObject(group, "weapons", at);
            if (weapons == null) continue;
            for (String slot : keys(weapons)) {
                String weapon = weapons.optString(slot);
                if (!weaponExists(weapon)) {
                    f.error(at + "slot \"" + slot + "\" holds " + whyNoWeapon(weapon) + ". " + CRASH
                            + " (Weapon spec [" + weapon + "] not found!)");
                }
                if (slots != null && slots.containsKey(slot)) {
                    c.weaponUse.putIfAbsent(weapon, "variant \"" + id + "\" (" + s.owner.name.trim() + ") slot \"" + slot + "\"");
                }
                if (slots != null) {
                    String mount = slots.get(slot);
                    if (mount == null) {
                        f.warning(at + "slot \"" + slot + "\" does not exist on hull \"" + hull + "\", so \"" + weapon
                                + "\" has nowhere to go. This can crash the refit screen or combat.");
                    } else if (mount.equals("HIDDEN")) {
                        c.hiddenSlotUse.putIfAbsent(weapon, "variant \"" + id + "\" (" + s.owner.name.trim() + ") slot \"" + slot + "\"");
                    }
                }
            }
        }
        if (o.has("modules")) {
            Map<String, String> modules = new LinkedHashMap<>();
            JSONObject map = o.optJSONObject("modules");
            JSONArray list = o.optJSONArray("modules");
            if (map != null) {
                for (String k : keys(map)) modules.put(k, map.optString(k));
            } else if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.optJSONObject(i);
                    if (entry == null || entry.length() == 0) {
                        f.error("\"modules\" entry " + (i + 1) + " must be a block like {\"SLOT\":\"variant_id\"}. " + CRASH);
                        continue;
                    }
                    String k = keys(entry).get(0);
                    modules.put(k, entry.optString(k));
                }
            } else {
                f.error("\"modules\" must be a block or a list. " + CRASH);
            }
            afterVariants.add(() -> {
                for (Map.Entry<String, String> m : modules.entrySet()) {
                    if (!variantExists(m.getValue())) {
                        f.warning("\"modules\": slot \"" + m.getKey() + "\" uses the variant \"" + m.getValue() + "\", which does"
                                + " not exist in the base game or any enabled mod. The game can crash while loading or when the"
                                + " station appears (NullPointerException, or Ship hull variant [" + m.getValue() + "] not found!).");
                    }
                }
            });
        }
    }

    private Map<String, String> slotsFor(String hull) {
        Map<String, String> slots = slotsByHull.get(hull);
        if (slots == null && hull.endsWith("_default_D")) slots = slotsByHull.get(hull.substring(0, hull.length() - 10));
        return slots;
    }
}
