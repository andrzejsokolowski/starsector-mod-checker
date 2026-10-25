package oddisz.modchecker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Looks inside the enabled mods' jars for ids written into the code: a string constant (or a static
 * field or local variable that only ever holds one) passed straight to a lookup such as
 * getWeaponSpec("x"), getVariant("x") or playSound("x", ...). Ids no loaded file defines are reported.
 * Ids built at run time are skipped: only values the bytecode fixes are checked.
 *
 * What a missing id does depends on the call (read from the 0.98a implementations): getWeaponSpec and
 * getHullSpec throw; the other spec getters return null, which only crashes if the code then uses it
 * without checking; sprites throw for a missing key; an unknown sound just stays silent.
 */
final class CodeScan {
    enum Kind {
        WEAPON("weapon"), HULL("hull"), VARIANT("variant"), WING("fighter wing"), SHIP_OR_WING("variant, wing or hull"),
        HULLMOD("hull mod"), SYSTEM("ship system"), COMMODITY("commodity"), SPECIAL_ITEM("special item"),
        INDUSTRY("industry"), SKILL("skill"), ABILITY("ability"), FACTION("faction"), SPRITE_FILE("image file"),
        SPRITE_KEY("settings.json graphics entry"), SOUND("sound");

        final String noun;

        Kind(String noun) {
            this.noun = noun;
        }
    }

    /** What the call does with an id that does not exist. */
    enum Effect {
        /** Throws at once. */
        THROWS,
        /** Returns null; crashes only if the result is used unchecked. */
        NULL,
        /** Stores the id; the crash comes later, wherever the game uses it. */
        LATER,
        /** spawnProjectile: falls back to the weapon passed in, crashes when that is null. */
        FALLBACK,
        /** Nothing happens. */
        SILENT
    }

    /** One lookup method: owner class, name, which String parameter is the id, and its behaviour. */
    record Target(String owner, String name, int param, Kind kind, Effect effect, String gameMessage) {
    }

    private static final String SETTINGS = "com/fs/starfarer/api/SettingsAPI";
    private static final String VARIANT_API = "com/fs/starfarer/api/combat/ShipVariantAPI";
    private static final String CARGO = "com/fs/starfarer/api/campaign/CargoAPI";
    static final List<Target> TARGETS = List.of(
            new Target(SETTINGS, "getWeaponSpec", 0, Kind.WEAPON, Effect.THROWS, "Weapon spec [%s] not found!"),
            new Target(SETTINGS, "getHullSpec", 0, Kind.HULL, Effect.THROWS, "Ship hull spec [%s] not found!"),
            new Target(SETTINGS, "getVariant", 0, Kind.VARIANT, Effect.NULL, null),
            new Target(SETTINGS, "getFighterWingSpec", 0, Kind.WING, Effect.NULL, null),
            new Target(SETTINGS, "getHullModSpec", 0, Kind.HULLMOD, Effect.NULL, null),
            new Target(SETTINGS, "getShipSystemSpec", 0, Kind.SYSTEM, Effect.NULL, null),
            new Target(SETTINGS, "getCommoditySpec", 0, Kind.COMMODITY, Effect.NULL, null),
            new Target(SETTINGS, "getSpecialItemSpec", 0, Kind.SPECIAL_ITEM, Effect.NULL, null),
            new Target(SETTINGS, "getIndustrySpec", 0, Kind.INDUSTRY, Effect.NULL, null),
            new Target(SETTINGS, "getSkillSpec", 0, Kind.SKILL, Effect.NULL, null),
            new Target(SETTINGS, "getAbilitySpec", 0, Kind.ABILITY, Effect.NULL, null),
            new Target(SETTINGS, "getFactionSpec", 0, Kind.FACTION, Effect.NULL, null),
            new Target(SETTINGS, "getSprite", 0, Kind.SPRITE_KEY, Effect.THROWS, "Texture [%s] from category [...] not found"),
            new Target(SETTINGS, "getSpriteName", 0, Kind.SPRITE_KEY, Effect.THROWS, "Texture [%s] from category [...] not found"),
            new Target(SETTINGS, "loadTexture", 0, Kind.SPRITE_FILE, Effect.THROWS, "Error loading [%s] resource, not found"),
            new Target(VARIANT_API, "addWeapon", 1, Kind.WEAPON, Effect.LATER, "Weapon spec [%s] not found!"),
            new Target(VARIANT_API, "addMod", 0, Kind.HULLMOD, Effect.LATER, "Hullmod %s not found!"),
            new Target(VARIANT_API, "addPermaMod", 0, Kind.HULLMOD, Effect.LATER, "Hullmod %s not found!"),
            new Target(VARIANT_API, "setWingId", 1, Kind.WING, Effect.LATER, "Fighter wing spec [%s] not found"),
            new Target(CARGO, "addWeapons", 0, Kind.WEAPON, Effect.LATER, "Weapon spec [%s] not found!"),
            new Target(CARGO, "addFighters", 0, Kind.WING, Effect.LATER, "Fighter wing spec [%s] not found"),
            new Target(CARGO, "addHullmods", 0, Kind.HULLMOD, Effect.LATER, "Hullmod %s not found!"),
            new Target(CARGO, "addCommodity", 0, Kind.COMMODITY, Effect.LATER, "Spec of class [...] with id [%s] not found"),
            new Target("com/fs/starfarer/api/combat/CombatEngineAPI", "spawnProjectile", 2, Kind.WEAPON, Effect.FALLBACK, null),
            new Target("com/fs/starfarer/api/FactoryAPI", "createFleetMember", 1, Kind.SHIP_OR_WING, Effect.LATER,
                    "Ship hull variant [%s] not found!"),
            new Target("com/fs/starfarer/api/combat/CombatFleetManagerAPI", "spawnShipOrWing", 0, Kind.SHIP_OR_WING,
                    Effect.LATER, "Ship hull variant [%s] not found!"),
            new Target("com/fs/starfarer/api/SoundPlayerAPI", "playSound", 0, Kind.SOUND, Effect.SILENT, null),
            new Target("com/fs/starfarer/api/SoundPlayerAPI", "playUISound", 0, Kind.SOUND, Effect.SILENT, null),
            new Target("com/fs/starfarer/api/SoundPlayerAPI", "playLoop", 0, Kind.SOUND, Effect.SILENT, null));

    private static final Set<String> BROAD_CATCHES = Set.of("java/lang/Throwable", "java/lang/Exception",
            "java/lang/RuntimeException", "java/lang/NullPointerException");

    private final Checker c;
    private final Rules rules;
    private final Set<String> sounds = new HashSet<>();
    private final Map<String, Set<String>> spriteKeys = new HashMap<>();
    private final Set<String> factionIds = new HashSet<>();
    /** "owner.field" to the string it always holds, from ConstantValue or a single PUTSTATIC in <clinit>. */
    private final Map<String, String> constants = new HashMap<>();
    private final Map<String, Integer> putCount = new HashMap<>();
    int classesRead;
    int lookupsChecked;

    CodeScan(Checker checker, Rules rules) {
        this.c = checker;
        this.rules = rules;
    }

    private record Jar(Source source, String path) {
    }

    void run() {
        loadSoundsSpritesFactions();
        List<Jar> jars = new ArrayList<>();
        for (Source source : c.mods.enabled) {
            for (String jarPath : source.mod.jars) {
                String rel = jarPath.isEmpty() ? null : c.files.inSource(source, jarPath);
                if (rel != null) jars.add(new Jar(source, rel));
            }
        }
        // Two passes, one class in memory at a time: first the string constants of every jar (a mod may
        // keep its ids in a constants class in another jar), then the lookups.
        for (Jar jar : jars) readClasses(jar, this::collectConstants);
        constants.keySet().removeIf(k -> putCount.getOrDefault(k, 0) > 1);
        for (Jar jar : jars) {
            readClasses(jar, cls -> {
                classesRead++;
                if (mentionsTarget(cls)) scanClass(jar, cls);
            });
        }
    }

    private void readClasses(Jar jar, Consumer<ClassNode> action) {
        try (ZipFile zip = new ZipFile(jar.source.root.resolve(jar.path).toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
                ClassNode node = new ClassNode();
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                } catch (RuntimeException | IOException ignored) {
                    continue; // a class ASM cannot read (newer than it knows); ClassIndex reports the Java version
                }
                action.accept(node);
            }
        } catch (IOException ignored) {
            // Reported by ClassIndex.
        }
    }

    private static boolean mentionsTarget(ClassNode node) {
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && target(call) != null) return true;
            }
        }
        return false;
    }

    static Target target(MethodInsnNode call) {
        for (Target t : TARGETS) {
            if (!t.owner.equals(call.owner) || !t.name.equals(call.name)) continue;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (t.param >= args.length || !args[t.param].getDescriptor().equals("Ljava/lang/String;")) continue;
            if (t.kind == Kind.SPRITE_KEY) {
                boolean twoStrings = args.length >= 2 && args[1].getDescriptor().equals("Ljava/lang/String;");
                if (call.name.equals("getSprite") && args.length == 1) {
                    return new Target(t.owner, t.name, 0, Kind.SPRITE_FILE, Effect.THROWS, "Error loading [%s] resource, not found");
                }
                if (!twoStrings) continue;
            }
            return t;
        }
        return null;
    }

    private void collectConstants(ClassNode cls) {
        for (FieldNode f : cls.fields) {
            if ((f.access & Opcodes.ACC_STATIC) != 0 && f.value instanceof String s) constants.put(cls.name + "." + f.name, s);
        }
        for (MethodNode m : cls.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn.getOpcode() != Opcodes.PUTSTATIC) continue;
                FieldInsnNode put = (FieldInsnNode) insn;
                String key = put.owner + "." + put.name;
                putCount.merge(key, 1, Integer::sum);
                if (m.name.equals("<clinit>") && real(insn.getPrevious(), false) instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    constants.putIfAbsent(key, s);
                } else {
                    putCount.merge(key, 2, Integer::sum); // assigned from something else: never trust it
                }
            }
        }
    }

    /** The nearest real instruction (skipping labels and line numbers) before or after this one. */
    private static AbstractInsnNode real(AbstractInsnNode insn, boolean forward) {
        while (insn != null && insn.getOpcode() < 0) insn = forward ? insn.getNext() : insn.getPrevious();
        return insn;
    }

    private void scanClass(Jar jar, ClassNode cls) {
        for (MethodNode m : cls.methods) {
            List<MethodInsnNode> calls = new ArrayList<>();
            boolean checksMods = false;
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (target(call) != null) calls.add(call);
                if (call.name.equals("isModEnabled")) checksMods = true;
            }
            if (calls.isEmpty()) continue;
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(cls.name, m);
            } catch (AnalyzerException | RuntimeException e) {
                continue;
            }
            for (MethodInsnNode call : calls) {
                int index = m.instructions.indexOf(call);
                Frame<SourceValue> frame = frames[index];
                if (frame == null) continue; // unreachable code
                Target t = target(call);
                int base = frame.getStackSize() - Type.getArgumentTypes(call.desc).length;
                if (base < 0) continue;
                Set<String> ids = resolve(m, frames, frame.getStack(base + t.param), 0);
                if (ids == null) continue;
                Set<String> keys = null;
                if (t.kind == Kind.SPRITE_KEY) {
                    keys = resolve(m, frames, frame.getStack(base + 1), 0);
                    if (keys == null) continue;
                }
                boolean guarded = checksMods || insideBroadCatch(m, index);
                boolean nullChecked = t.effect == Effect.NULL && nullChecked(m, call);
                for (String id : ids) {
                    if (id.isEmpty() && t.name.equals("setWingId")) continue; // "" clears a wing slot
                    lookupsChecked++;
                    if (keys == null) check(jar, cls, m, t, id, null, guarded, nullChecked);
                    else for (String key : keys) check(jar, cls, m, t, id, key, guarded, nullChecked);
                }
            }
        }
    }

    /** The strings a stack value can hold, or null when any of its sources is not a fixed string. */
    private Set<String> resolve(MethodNode m, Frame<SourceValue>[] frames, SourceValue value, int depth) {
        if (value == null || value.insns.isEmpty() || depth > 4) return null;
        Set<String> out = new LinkedHashSet<>();
        for (AbstractInsnNode insn : value.insns) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                out.add(s);
            } else if (insn.getOpcode() == Opcodes.GETSTATIC) {
                FieldInsnNode get = (FieldInsnNode) insn;
                String s = constants.get(get.owner + "." + get.name);
                if (s == null) return null;
                out.add(s);
            } else if (insn.getOpcode() == Opcodes.ALOAD) {
                // A local variable: follow it back to the stores that fill it.
                Frame<SourceValue> at = frames[m.instructions.indexOf(insn)];
                if (at == null) return null;
                SourceValue local = at.getLocal(((VarInsnNode) insn).var);
                if (local == null || local.insns.isEmpty()) return null; // a method parameter
                for (AbstractInsnNode store : local.insns) {
                    if (store.getOpcode() != Opcodes.ASTORE) return null;
                    Frame<SourceValue> before = frames[m.instructions.indexOf(store)];
                    if (before == null || before.getStackSize() == 0) return null;
                    Set<String> stored = resolve(m, frames, before.getStack(before.getStackSize() - 1), depth + 1);
                    if (stored == null) return null;
                    out.addAll(stored);
                }
            } else {
                return null;
            }
        }
        return out;
    }

    /** Whether the code tests the result for null right after the call (directly, or after storing it). */
    private static boolean nullChecked(MethodNode m, MethodInsnNode call) {
        AbstractInsnNode next = real(call.getNext(), true);
        if (next != null && next.getOpcode() == Opcodes.CHECKCAST) next = real(next.getNext(), true);
        if (next == null) return false;
        if (isNullTest(next)) return true;
        if (next.getOpcode() == Opcodes.DUP) {
            AbstractInsnNode after = real(next.getNext(), true);
            return after != null && isNullTest(after);
        }
        if (next.getOpcode() == Opcodes.ASTORE) {
            int var = ((VarInsnNode) next).var;
            AbstractInsnNode scan = next.getNext();
            for (int i = 0; scan != null && i < 40; scan = scan.getNext(), i++) {
                if (scan.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) scan).var == var) {
                    AbstractInsnNode after = real(scan.getNext(), true);
                    return after != null && isNullTest(after);
                }
            }
        }
        return false;
    }

    private static boolean isNullTest(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) return true;
        if (op == Opcodes.ACONST_NULL) {
            AbstractInsnNode after = real(insn.getNext(), true);
            return after != null && (after.getOpcode() == Opcodes.IF_ACMPEQ || after.getOpcode() == Opcodes.IF_ACMPNE);
        }
        return insn instanceof MethodInsnNode call && (call.name.equals("isNull") || call.name.equals("nonNull"));
    }

    private static boolean insideBroadCatch(MethodNode m, int index) {
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            int start = m.instructions.indexOf(block.start), end = m.instructions.indexOf(block.end);
            if (index >= start && index < end && (block.type == null || BROAD_CATCHES.contains(block.type))) return true;
        }
        return false;
    }

    private void check(Jar jar, ClassNode cls, MethodNode m, Target t, String id, String key, boolean guarded,
            boolean nullChecked) {
        if (exists(t.kind, id, key)) return;
        String where = cls.name.replace('/', '.') + "." + m.name + "()";
        Fields f = new Fields(c.report, jar.source, jar.path);
        if (t.effect == Effect.SILENT) {
            f.note(where + " plays the sound \"" + id + "\", which is not in data/config/sounds.json of the base game or any"
                    + " enabled mod. The game stays silent there.");
            return;
        }
        boolean noCategory = t.kind == Kind.SPRITE_KEY && !spriteKeys.containsKey(id);
        String what = t.kind == Kind.SPRITE_KEY ? "the graphics entry \"" + key + "\" in category \"" + id + "\""
                : "the " + t.kind.noun + " \"" + id + "\"";
        String text = where + " calls " + t.name + " for " + what + ", which does not exist in the base game or any enabled mod.";
        String consequence = switch (t.effect) {
            case THROWS -> noCategory
                    ? " Without that category the game hands back an empty sprite, and drawing it crashes (NullPointerException)."
                    : " If that code runs, the game crashes (" + String.format(t.gameMessage, t.kind == Kind.SPRITE_KEY ? key : id) + ").";
            case NULL -> nullChecked ? null : " The call returns nothing and the code uses the result without checking, so"
                    + " the game crashes with a NullPointerException if that code runs.";
            case LATER -> " The game accepts the id there and crashes later, wherever it uses it (" + String.format(t.gameMessage, id)
                    + ").";
            case FALLBACK -> " The game then fires the weapon passed in instead, and crashes if none was passed.";
            case SILENT -> null;
        };
        String namespace = t.kind == Kind.SPRITE_KEY && noCategory ? id : t.kind == Kind.SPRITE_KEY ? null : id;
        if (consequence == null) {
            f.note(text + " The code checks the result for null, so this is probably handled.");
        } else if (namespace != null && foreign(t.kind, namespace)) {
            f.note(text + consequence + " Nothing loaded starts with \"" + prefix(namespace) + "_\", so this is probably"
                    + " for a mod you do not have, and the mod may check for it before this code runs.");
        } else if (guarded) {
            f.note(text + consequence + " The method also checks for other mods or catches errors, so this is probably handled.");
        } else {
            f.warning(text + consequence);
        }
    }

    private boolean exists(Kind kind, String id, String key) {
        return switch (kind) {
            case WEAPON -> rules.weaponExists(id);
            case HULL -> rules.hullExists(id);
            case VARIANT -> rules.variantExists(id);
            case WING -> c.wingRows.containsKey(id);
            case SHIP_OR_WING -> rules.variantExists(id) || c.wingRows.containsKey(id) || rules.hullExists(id);
            case HULLMOD -> c.hullmodRows.containsKey(id);
            case SYSTEM -> c.systems.containsKey(id);
            case COMMODITY -> sheet("data/campaign/commodities.csv").contains(id);
            case SPECIAL_ITEM -> sheet("data/campaign/special_items.csv").contains(id);
            case INDUSTRY -> sheet("data/campaign/industries.csv").contains(id);
            case SKILL -> c.skillIds.contains(id);
            case ABILITY -> sheet("data/campaign/abilities.csv").contains(id);
            case FACTION -> factionIds.contains(id);
            case SPRITE_FILE -> c.files.exists(id);
            case SPRITE_KEY -> spriteKeys.getOrDefault(id, Set.of()).contains(key);
            case SOUND -> sounds.contains(id);
        };
    }

    private final Map<Kind, Set<String>> prefixes = new HashMap<>();

    static String prefix(String id) {
        int cut = id.indexOf('_');
        return cut <= 0 ? null : id.substring(0, cut).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * An id whose prefix (the part before the first "_") no loaded id of that kind shares most likely
     * belongs to a mod that is not installed: mods name other mods' content for optional support.
     */
    private boolean foreign(Kind kind, String id) {
        String p = prefix(id);
        if (p == null) return false;
        Set<String> known = prefixes.computeIfAbsent(kind, k -> {
            Set<String> out = new HashSet<>();
            for (String loaded : idsOf(k)) {
                String lp = prefix(loaded);
                if (lp != null) out.add(lp);
            }
            return out;
        });
        return !known.contains(p);
    }

    private java.util.Collection<String> idsOf(Kind kind) {
        return switch (kind) {
            case WEAPON -> c.weapons.keySet();
            case HULL -> union(c.hulls.keySet(), c.skins.keySet());
            case VARIANT -> c.variants.keySet();
            case WING -> c.wingRows.keySet();
            case SHIP_OR_WING -> union(union(c.variants.keySet(), c.wingRows.keySet()), union(c.hulls.keySet(), c.skins.keySet()));
            case HULLMOD -> c.hullmodRows.keySet();
            case SYSTEM -> c.systems.keySet();
            case COMMODITY -> sheet("data/campaign/commodities.csv");
            case SPECIAL_ITEM -> sheet("data/campaign/special_items.csv");
            case INDUSTRY -> sheet("data/campaign/industries.csv");
            case SKILL -> c.skillIds;
            case ABILITY -> sheet("data/campaign/abilities.csv");
            case FACTION -> factionIds;
            case SPRITE_KEY -> spriteKeys.keySet();
            case SPRITE_FILE, SOUND -> Set.of();
        };
    }

    private static Set<String> union(java.util.Collection<String> a, java.util.Collection<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private Set<String> sheet(String path) {
        return c.sheetIds.getOrDefault(path, Set.of());
    }

    private void loadSoundsSpritesFactions() {
        Checker.Spec soundSpec = c.effective("data/config/sounds.json");
        if (soundSpec != null) {
            for (String k : Rules.keys(soundSpec.json)) if (!k.equals("music")) sounds.add(k);
        }
        Checker.Spec settings = c.effective("data/config/settings.json");
        JSONObject graphics = settings == null ? null : settings.json.optJSONObject("graphics");
        if (graphics != null) {
            for (String category : Rules.keys(graphics)) {
                JSONObject entries = graphics.optJSONObject(category);
                if (entries != null) spriteKeys.put(category, new HashSet<>(Rules.keys(entries)));
            }
        }
        for (String path : c.factionFiles) {
            Checker.Spec faction = c.effective(path);
            if (faction != null) factionIds.add(faction.json.optString("id"));
        }
    }
}
