package oddisz.modchecker;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A tiny Starsector install on disk: a core with just enough data, and mods the test adds. */
final class Fixture {
    static final String SHIP_HEADER = "name,id,designation,tech/manufacturer,system id,fleet pts,hitpoints,armor rating,"
            + "max flux,flux dissipation,ordnance points,fighter bays,max speed,mass,shield type,defense id\n";
    static final String WEAPON_HEADER = "name,id,tier,type,hints\n";
    static final String WING_HEADER = "id,variant,tags,formation,num,role,role desc,refit\n";
    static final String HULLMOD_HEADER = "name,id,cost_frigate,cost_dest,cost_cruiser,cost_capital,script\n";

    final Path root;
    final Path core;
    final Path mods;
    private final List<String> enabled = new ArrayList<>();

    Fixture(Path dir) throws IOException {
        root = dir;
        core = dir.resolve("starsector-core");
        mods = dir.resolve("mods");
        Files.createDirectories(mods);
        Files.copy(realJsonJar(), mk(core.resolve("json.jar")));
        jar(core.resolve("starfarer.api.jar"), "com/fs/starfarer/api/Placeholder.class", new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0, 0, 0, 0, 61});
        coreFile("data/config/settings.json", "{\"graphics\":{\"ui\":{\"logo\":\"graphics/ui/logo.png\"}},\"plugins\":{}}");
        coreFile("graphics/ui/logo.png", "png");
        coreFile("data/config/hull_styles.json", "{\"LOW_TECH\":{\"shieldRingColor\":[1,2,3,4]}}");
        coreFile("data/config/engine_styles.json", "{\"CORE_ENGINE\":{}}");
        coreFile("data/config/sounds.json", "{\"music\":{},\"core_sound\":[]}");
        coreFile("data/hulls/ship_data.csv", SHIP_HEADER);
        coreFile("data/weapons/weapon_data.csv", WEAPON_HEADER);
        coreFile("data/hulls/wing_data.csv", WING_HEADER);
        coreFile("data/hullmods/hull_mods.csv", HULLMOD_HEADER + "Core Mod,core_mod,1,2,3,4,data.hullmods.CoreMod\n");
        coreFile("data/hullmods/CoreMod.java", "class CoreMod {}");
    }

    static Path realJsonJar() {
        String fromBuild = System.getProperty("starsector.json.jar");
        if (fromBuild != null) return Paths.get(fromBuild);
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (entry.endsWith("json.jar")) return Paths.get(entry);
        }
        throw new IllegalStateException("json.jar is not on the test class path");
    }

    private static Path mk(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        return p;
    }

    void coreFile(String rel, String text) throws IOException {
        Files.write(mk(core.resolve(rel)), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds an enabled mod and returns its folder. */
    Path mod(String id, String... jars) throws IOException {
        Path dir = mods.resolve(id + "-1.0");
        StringBuilder jarList = new StringBuilder();
        for (String j : jars) jarList.append(jarList.length() == 0 ? "" : ",").append('"').append(j).append('"');
        Files.write(mk(dir.resolve("mod_info.json")), ("{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"d\","
                + "\"version\":\"1.0\",\"gameVersion\":\"0.98a-RC8\",\"jars\":[" + jarList + "]}").getBytes(StandardCharsets.UTF_8));
        enabled.add(id);
        writeEnabled();
        return dir;
    }

    static void file(Path mod, String rel, String text) throws IOException {
        Files.write(mk(mod.resolve(rel)), text.getBytes(StandardCharsets.UTF_8));
    }

    static void bytes(Path mod, String rel, byte[] data) throws IOException {
        Files.write(mk(mod.resolve(rel)), data);
    }

    static void jar(Path jar, String entry, byte[] data) throws IOException {
        try (OutputStream out = Files.newOutputStream(mk(jar)); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(data);
            zip.closeEntry();
        }
    }

    private void writeEnabled() throws IOException {
        StringBuilder sb = new StringBuilder("{\"enabledMods\":[");
        for (int i = 0; i < enabled.size(); i++) sb.append(i == 0 ? "" : ",").append('"').append(enabled.get(i)).append('"');
        Files.write(mods.resolve("enabled_mods.json"), sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A weapon file with the given barrel lists. */
    static String weapon(String id, String turretOffsets, String turretAngles, String hardpointOffsets, String hardpointAngles) {
        return "{\"id\":\"" + id + "\",\"specClass\":\"projectile\",\"type\":\"BALLISTIC\",\"size\":\"SMALL\","
                + "\"turretSprite\":\"graphics/ui/logo.png\",\"hardpointSprite\":\"graphics/ui/logo.png\","
                + "\"turretOffsets\":[" + turretOffsets + "],\"turretAngleOffsets\":[" + turretAngles + "],"
                + "\"hardpointOffsets\":[" + hardpointOffsets + "],\"hardpointAngleOffsets\":[" + hardpointAngles + "],"
                + "\"barrelMode\":\"LINKED\",\"animationType\":\"NONE\",\"projectileSpecId\":\"test_shot\"}";
    }

    static final String SHOT = "{\"id\":\"test_shot\",\"specClass\":\"projectile\",\"spawnType\":\"BALLISTIC\","
            + "\"collisionClass\":\"PROJECTILE_FF\",\"collisionClassByFighter\":\"PROJECTILE_FIGHTER\",\"width\":2,\"fadeTime\":0.1}";

    /** A hull file with one slot. */
    static String hull(String id, String slotId, String mount, String builtIns) {
        return "{\"hullId\":\"" + id + "\",\"style\":\"LOW_TECH\",\"spriteName\":\"graphics/ui/logo.png\",\"center\":[10,10],"
                + "\"width\":20,\"height\":20,\"collisionRadius\":15,\"hullSize\":\"FRIGATE\",\"shieldRadius\":10,"
                + "\"shieldCenter\":[0,0],\"weaponSlots\":[{\"id\":\"" + slotId + "\",\"type\":\"BALLISTIC\",\"size\":\"SMALL\","
                + "\"mount\":\"" + mount + "\",\"locations\":[0,0],\"angle\":0,\"arc\":10}]" + builtIns + "}";
    }

    static String variant(String id, String hull, String slot, String weapon, String mods) {
        return "{\"variantId\":\"" + id + "\",\"hullId\":\"" + hull + "\",\"displayName\":\"Test\",\"fluxVents\":0,"
                + "\"fluxCapacitors\":0,\"hullMods\":[" + mods + "],\"weaponGroups\":[{\"mode\":\"LINKED\",\"weapons\":{\""
                + slot + "\":\"" + weapon + "\"}}]}";
    }

    /** Runs the checker on this install. */
    Report run() throws IOException {
        Options options = Options.parse(new String[] {"--notes", "--no-report"});
        Checker checker = new Checker(options, GameInstall.at(root));
        checker.check();
        return checker.report;
    }
}
