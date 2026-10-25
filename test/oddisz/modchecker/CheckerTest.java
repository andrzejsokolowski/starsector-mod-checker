package oddisz.modchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CheckerTest {
    @TempDir
    Path dir;
    Fixture game;
    Path mod;

    @BeforeEach
    void setUp() throws Exception {
        game = new Fixture(dir);
        mod = game.mod("testmod");
        Fixture.file(mod, "data/weapons/proj/test_shot.proj", Fixture.SHOT);
    }

    private void addWeapon(String id, String wpn) throws Exception {
        Fixture.file(mod, "data/weapons/" + id + ".wpn", wpn);
        appendRow("data/weapons/weapon_data.csv", Fixture.WEAPON_HEADER, "Test," + id + ",0,KINETIC,\n");
    }

    private final java.util.Map<String, StringBuilder> sheets = new java.util.HashMap<>();

    private void appendRow(String rel, String header, String row) throws Exception {
        StringBuilder sb = sheets.computeIfAbsent(rel, k -> new StringBuilder(header));
        sb.append(row);
        Fixture.file(mod, rel, sb.toString());
    }

    private void addHull(String id, String text) throws Exception {
        Fixture.file(mod, "data/hulls/" + id + ".ship", text);
        appendRow("data/hulls/ship_data.csv", Fixture.SHIP_HEADER, "Test," + id + ",,,,,100,10,100,10,10,0,100,100,NONE,\n");
    }

    private static List<Report.Problem> of(Report report, Report.Severity severity) {
        return report.problems().stream().filter(p -> p.severity() == severity).collect(Collectors.toList());
    }

    private static Report.Problem find(Report report, Report.Severity severity, String text) {
        for (Report.Problem p : report.problems()) if (p.severity() == severity && p.message().contains(text)) return p;
        return null;
    }

    private static String dump(Report report) {
        return report.problems().stream().map(p -> p.severity() + " " + p.path() + ": " + p.message()).collect(Collectors.joining("\n"));
    }

    // ---------------------------------------------------------------- barrels

    @Test
    void cleanModHasNoErrorsOrWarnings() throws Exception {
        addWeapon("good_gun", Fixture.weapon("good_gun", "10,0,10,2", "0,0", "12,0,12,2", "0,0"));
        Report r = game.run();
        assertTrue(of(r, Report.Severity.ERROR).isEmpty(), dump(r));
        assertTrue(of(r, Report.Severity.WARNING).isEmpty(), dump(r));
    }

    @Test
    void moreAnglesThanBarrelsCrashesWhileLoading() throws Exception {
        addWeapon("bad_gun", Fixture.weapon("bad_gun", "10,0", "0,5", "12,0", "0"));
        Report r = game.run();
        Report.Problem p = find(r, Report.Severity.ERROR, "\"turretAngleOffsets\" has 2 angles but \"turretOffsets\" only defines 1 barrel");
        assertNotNull(p, dump(r));
        assertEquals("data/weapons/bad_gun.wpn", p.path());
    }

    @Test
    void fewerAnglesThanBarrelsIsOnlyANote() throws Exception {
        addWeapon("needler", Fixture.weapon("needler", "10,0,10,2", "0", "12,0,12,2", "0,0"));
        Report r = game.run();
        assertTrue(of(r, Report.Severity.ERROR).isEmpty(), dump(r));
        assertNotNull(find(r, Report.Severity.NOTE, "the game uses 0 for the missing ones"), dump(r));
    }

    @Test
    void moreHardpointThanTurretBarrelsIsAnErrorOnlyInAHiddenSlot() throws Exception {
        addWeapon("odd_gun", Fixture.weapon("odd_gun", "10,0", "0", "12,0,12,2", "0,0"));
        Report safe = game.run();
        assertNull(find(safe, Report.Severity.ERROR, "getHiddenAngleOffset"), dump(safe));
        assertNotNull(find(safe, Report.Severity.NOTE, "getHiddenAngleOffset"), dump(safe));

        addHull("test_hull", Fixture.hull("test_hull", "WS1", "HIDDEN", ",\"builtInWeapons\":{\"WS1\":\"odd_gun\"}"));
        Report used = game.run();
        Report.Problem p = find(used, Report.Severity.ERROR, "getHiddenAngleOffset");
        assertNotNull(p, dump(used));
        assertTrue(p.message().contains("built into hull \"test_hull\""), p.message());
    }

    @Test
    void mergedCopyOfABaseGameWeaponIsCheckedAfterMerging() throws Exception {
        // The game appends the mod's lists to the base game's: 1 barrel, but 1 + 1 angles.
        game.coreFile("data/weapons/core_gun.wpn", Fixture.weapon("core_gun", "10,0", "0", "12,0", "0").replace("test_shot", "core_shot"));
        game.coreFile("data/weapons/proj/core_shot.proj", Fixture.SHOT.replace("test_shot", "core_shot"));
        game.coreFile("data/weapons/weapon_data.csv", Fixture.WEAPON_HEADER + "Core,core_gun,0,KINETIC,\n");
        Fixture.file(mod, "data/weapons/core_gun.wpn", "{\"turretAngleOffsets\":[0]}");
        Report r = game.run();
        Report.Problem p = find(r, Report.Severity.ERROR, "after the game merged this file");
        assertNotNull(p, dump(r));
        assertEquals("data/weapons/core_gun.wpn", p.path());
    }

    @Test
    void weaponWithoutBarrelsIsAnErrorWhenMounted() throws Exception {
        addWeapon("empty_gun", Fixture.weapon("empty_gun", "", "", "", ""));
        assertNotNull(find(game.run(), Report.Severity.NOTE, "Has no barrels"));
        addHull("test_hull", Fixture.hull("test_hull", "WS1", "TURRET", ""));
        Fixture.file(mod, "data/variants/test_hull_std.variant", Fixture.variant("test_hull_std", "test_hull", "WS1", "empty_gun", ""));
        assertNotNull(find(game.run(), Report.Severity.ERROR, "Has no barrels"));
    }

    // ---------------------------------------------------------------- references

    @Test
    void variantNamingAMissingWeaponOrHullCrashes() throws Exception {
        addHull("test_hull", Fixture.hull("test_hull", "WS1", "TURRET", ""));
        Fixture.file(mod, "data/variants/a.variant", Fixture.variant("a", "test_hull", "WS1", "no_such_gun", "\"core_mod\""));
        Fixture.file(mod, "data/variants/sub/b.variant", Fixture.variant("b", "no_such_hull", "WS1", "x", ""));
        Fixture.file(mod, "data/variants/c.variant", Fixture.variant("c", "test_hull", "WS1", "no_such_gun", "\"no_such_mod\""));
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.ERROR, "Weapon spec [no_such_gun] not found!"), dump(r));
        assertNotNull(find(r, Report.Severity.ERROR, "Ship hull spec [no_such_hull] not found!"), dump(r));
        assertNotNull(find(r, Report.Severity.WARNING, "Hullmod no_such_mod not found!"), dump(r));
        assertNull(find(r, Report.Severity.WARNING, "Hullmod core_mod"), dump(r));
    }

    @Test
    void weaponWithoutACsvRowIsDroppedAndItsUsersCrash() throws Exception {
        Fixture.file(mod, "data/weapons/orphan.wpn", Fixture.weapon("orphan", "1,0", "0", "1,0", "0"));
        addHull("test_hull", Fixture.hull("test_hull", "WS1", "TURRET", ""));
        Fixture.file(mod, "data/variants/v.variant", Fixture.variant("v", "test_hull", "WS1", "orphan", ""));
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.NOTE, "game drops this weapon"), dump(r));
        assertNotNull(find(r, Report.Severity.ERROR, "no row in data/weapons/weapon_data.csv"), dump(r));
    }

    @Test
    void missingArtCrashesOnlyForSpecsTheGameKeeps() throws Exception {
        String wpn = Fixture.weapon("art_gun", "1,0", "0", "1,0", "0").replace("\"turretSprite\":\"graphics/ui/logo.png\"",
                "\"turretSprite\":\"graphics/weapons/missing.png\"");
        addWeapon("art_gun", wpn);
        Fixture.file(mod, "data/weapons/unused_gun.wpn", wpn.replace("art_gun", "unused_gun"));
        Report r = game.run();
        Report.Problem p = find(r, Report.Severity.ERROR, "graphics/weapons/missing.png");
        assertNotNull(p, dump(r));
        assertEquals("data/weapons/art_gun.wpn", p.path());
        assertTrue(r.problems().stream().noneMatch(x -> x.path().equals("data/weapons/unused_gun.wpn")
                && x.severity() != Report.Severity.NOTE), dump(r));
    }

    @Test
    void missingScriptClassCrashesAtStartup() throws Exception {
        appendRow("data/hullmods/hull_mods.csv", Fixture.HULLMOD_HEADER, "Mod,test_mod,1,2,3,4,data.hullmods.Missing\n");
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.ERROR, "data.hullmods.Missing"), dump(r));
        assertNull(find(r, Report.Severity.ERROR, "data.hullmods.CoreMod"), dump(r));
    }

    // ---------------------------------------------------------------- reading files

    @Test
    void jsonTheGameCannotReadIsAnError() throws Exception {
        addWeapon("slash", Fixture.weapon("slash", "1,0", "0", "1,0", "0").replace("\"size\"", "// a comment\n\"size\""));
        addWeapon("dup", Fixture.weapon("dup", "1,0", "0", "1,0", "0").replace("\"size\":\"SMALL\"", "\"size\":\"SMALL\",\"size\":\"SMALL\""));
        addWeapon("hash", Fixture.weapon("hash", "1,0", "0", "1,0", "0").replace("\"size\"", "# a comment\n\"size\"")
                .replace("\"projectileSpecId\":\"test_shot\"", "\"projectileSpecId\":\"test_shot\",\"extra\":[1,2,],"));
        Report r = game.run();
        assertTrue(r.problems().stream().anyMatch(p -> p.path().endsWith("slash.wpn") && p.severity() == Report.Severity.ERROR
                && p.message().contains("cannot read")), dump(r));
        assertTrue(r.problems().stream().anyMatch(p -> p.path().endsWith("dup.wpn") && p.message().contains("Duplicate key")), dump(r));
        assertTrue(r.problems().stream().noneMatch(p -> p.path().endsWith("hash.wpn") && p.severity() != Report.Severity.NOTE), dump(r));
    }

    @Test
    void bomIsAnError() throws Exception {
        byte[] text = Fixture.weapon("bom", "1,0", "0", "1,0", "0").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream withBom = new ByteArrayOutputStream();
        withBom.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        withBom.write(text);
        Fixture.bytes(mod, "data/weapons/bom.wpn", withBom.toByteArray());
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.ERROR, "UTF-8 BOM"), dump(r));
    }

    @Test
    void csvDuplicateIdOrOpenQuoteCrashes() throws Exception {
        Fixture.file(mod, "data/hulls/wing_data.csv", Fixture.WING_HEADER + "w,v,,V,1,FIGHTER,x,5\nw,v,,V,1,FIGHTER,x,5\n");
        Fixture.file(mod, "data/hullmods/hull_mods.csv", Fixture.HULLMOD_HEADER + "Mod,m,1,1,1,1,\"data.hullmods.CoreMod\n");
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.ERROR, "appears twice in this file (lines 2 and 3)"), dump(r));
        assertNotNull(find(r, Report.Severity.ERROR, "is never closed"), dump(r));
    }

    @Test
    void filesInFoldersTheGameDoesNotReadAreNoted() throws Exception {
        Fixture.file(mod, "data/weapons/old/leftover.wpn", "{broken");
        Fixture.file(mod, "data/variants_CN/translated.variant", "{broken");
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.NOTE, "The game never reads this file"), dump(r));
        assertTrue(r.problems().stream().noneMatch(p -> p.path().contains("variants_CN")), dump(r));
        assertTrue(r.problems().stream().noneMatch(p -> p.path().contains("leftover") && p.severity() == Report.Severity.ERROR), dump(r));
    }

    @Test
    void enabledModThatIsNotInstalledIsAWarning() throws Exception {
        java.nio.file.Files.write(game.mods.resolve("enabled_mods.json"),
                "{\"enabledMods\":[\"testmod\",\"ghost\"]}".getBytes(StandardCharsets.UTF_8));
        Report r = game.run();
        assertNotNull(find(r, Report.Severity.WARNING, "\"ghost\" is enabled"), dump(r));
    }

    // ---------------------------------------------------------------- the code scan

    /** A class whose method f() looks up "nope" with getWeaponSpec and checks a getVariant result for null. */
    private static byte[] lookupClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Lookups", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", "()V", null, null);
        mv.visitCode();
        String settings = "com/fs/starfarer/api/SettingsAPI";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSettings", "()L" + settings + ";", false);
        mv.visitLdcInsn("nope");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, settings, "getWeaponSpec", "(Ljava/lang/String;)Lcom/fs/starfarer/api/loading/WeaponSpecAPI;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/api/Global", "getSettings", "()L" + settings + ";", false);
        mv.visitLdcInsn("nope_variant");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, settings, "getVariant", "(Ljava/lang/String;)Lcom/fs/starfarer/api/combat/ShipVariantAPI;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, end);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void codeScanFindsIdsWrittenIntoMods() throws Exception {
        Path coded = game.mod("coded", "jars/coded.jar");
        Fixture.jar(coded.resolve("jars/coded.jar"), "test/Lookups.class", lookupClass());
        Report r = game.run();
        Report.Problem p = find(r, Report.Severity.WARNING, "getWeaponSpec for the weapon \"nope\"");
        assertNotNull(p, dump(r));
        assertEquals("jars/coded.jar", p.path());
        assertNotNull(find(r, Report.Severity.NOTE, "checks the result for null"), dump(r));
    }

    @Test
    void findsTheGameFromAToolFolderInsideIt() throws Exception {
        GameInstall fromTool = GameInstall.locate(null, game.root.resolve("StarsectorModChecker"));
        assertNotNull(fromTool);
        assertEquals(game.root.toAbsolutePath().normalize(), fromTool.root);
        GameInstall fromMods = GameInstall.locate(game.mods.toString(), null);
        assertNotNull(fromMods);
        assertEquals(game.mods.toAbsolutePath().normalize(), fromMods.mods);
        assertNull(GameInstall.at(game.mods.resolve("testmod-1.0")));
    }

    // ---------------------------------------------------------------- the game's readers

    @Test
    void csvReaderMatchesTheGame() {
        String text = "id,name,desc\n#comment,row,x\n\na,\"Quoted, name\",\"say \"\"hi\"\"\"\nb,plain,\"two\nlines\"\nc,short\n";
        GameCsv.Table t = GameCsv.parse(text.getBytes(StandardCharsets.UTF_8));
        assertNull(t.error);
        assertEquals(3, t.rows.size());
        assertEquals("Quoted, name", t.rows.get(0).get("name"));
        assertEquals("say \"hi\"", t.rows.get(0).get("desc"));
        assertEquals(4, t.rows.get(0).line);
        assertEquals("two\nlines", t.rows.get(1).get("desc"));
        assertNull(t.rows.get(2).get("desc"));
    }

    @Test
    void commentStrippingMatchesTheGame() {
        assertEquals("{\"a\":\"x#y\", \n\"b\":1}", GameJson.stripComments("{\"a\":\"x#y\", # gone\n\"b\":1}"));
    }

    @Test
    void reportUsesTheModFolderFileLayout() throws Exception {
        addWeapon("bad_gun", Fixture.weapon("bad_gun", "10,0", "0,5", "12,0", "0"));
        String text = game.run().render("", false);
        assertTrue(text.contains("\ntestmod  (id testmod"), text);
        assertTrue(text.contains("--- data/weapons"), text);
        assertTrue(text.contains("------ bad_gun.wpn"), text);
        assertTrue(text.contains("--------- ERROR: \"turretAngleOffsets\""), text);
    }
}
