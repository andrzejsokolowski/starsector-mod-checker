package oddisz.modchecker;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Finds the mods the game will load: folders in mods/ with a mod_info.json, whose id is listed in
 * enabled_mods.json. Mod managers disable spare copies by renaming mod_info.json, so those are skipped.
 */
final class ModList {
    final List<Source> enabled = new ArrayList<>();
    final Map<String, Source> byId = new LinkedHashMap<>();
    int folderCount;

    private ModList() {
    }

    static ModList scan(Path modsDir, boolean allMods, Report report) throws IOException {
        ModList list = new ModList();
        Set<String> enabledIds = readEnabledIds(modsDir, allMods, report);

        List<Path> folders = new ArrayList<>();
        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
                for (Path p : stream) if (Files.isDirectory(p)) folders.add(p);
            }
        }
        folders.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
        list.folderCount = folders.size();

        Map<String, Path> firstFolderForId = new LinkedHashMap<>();
        for (Path folder : folders) {
            Path info = folder.resolve("mod_info.json");
            if (!Files.isRegularFile(info)) {
                checkNested(folder, report);
                continue;
            }
            Source.ModInfo mod = readModInfo(folder, info, report);
            if (mod == null) continue;
            boolean wanted = enabledIds == null || enabledIds.contains(mod.id);
            if (!wanted) continue;
            Path first = firstFolderForId.putIfAbsent(mod.id, folder);
            if (first != null) {
                Source earlier = list.byId.get(mod.id);
                report.warning(earlier, "mod_info.json", "Two folders hold the mod id \"" + mod.id + "\": mods/"
                        + first.getFileName() + " and mods/" + folder.getFileName()
                        + ". The game loads only one of them. Delete or disable the copy you do not want.");
                continue;
            }
            Source source = new Source(mod.name, folder, mod);
            list.enabled.add(source);
            list.byId.put(mod.id, source);
        }

        if (enabledIds != null) {
            for (String id : enabledIds) {
                if (!list.byId.containsKey(id)) {
                    report.warning(null, "enabled_mods.json", "The mod \"" + id + "\" is enabled but no folder in mods/ has a"
                            + " mod_info.json with that id, so the game cannot load it.");
                }
            }
        }

        // The game sorts mods by name; that order decides which copy of a shared file is read first.
        list.enabled.sort(Comparator.comparing(s -> s.name));

        for (Source source : list.enabled) {
            for (String dep : source.mod.dependencies) {
                if (!list.byId.containsKey(dep)) {
                    report.error(source, "mod_info.json", "Needs the mod \"" + dep + "\", which is not enabled or not installed."
                            + " Mods usually crash at startup without their requirements.");
                }
            }
        }
        return list;
    }

    private static Set<String> readEnabledIds(Path modsDir, boolean allMods, Report report) throws IOException {
        if (allMods) return null;
        Path file = modsDir.resolve("enabled_mods.json");
        if (!Files.isRegularFile(file)) {
            report.warning(null, "enabled_mods.json", "mods/enabled_mods.json was not found, so every mod in the folder was checked.");
            return null;
        }
        GameJson.Parsed parsed = GameJson.parse(Files.readAllBytes(file));
        if (parsed.object == null) {
            report.error(null, "enabled_mods.json", "The game cannot read mods/enabled_mods.json: "
                    + GameJson.cleanError(parsed.error) + ". Every mod in the folder was checked instead.");
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        JSONArray array = parsed.object.optJSONArray("enabledMods");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "").trim();
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    /** A mod folder without mod_info.json that holds one a level down was unzipped one folder too deep. */
    private static void checkNested(Path folder, Report report) throws IOException {
        if (hasDisabledInfo(folder)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path inner : stream) {
                if (Files.isDirectory(inner) && Files.isRegularFile(inner.resolve("mod_info.json"))) {
                    Source holder = new Source("mods/" + folder.getFileName(), folder, null);
                    report.warning(holder, "", "This folder has no mod_info.json, but mods/" + folder.getFileName() + "/"
                            + inner.getFileName() + " does. The mod was unzipped one folder too deep, so the game"
                            + " does not see it. Move the inner folder straight into mods/.");
                    return;
                }
            }
        }
    }

    private static boolean hasDisabledInfo(Path folder) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "mod_info.json*")) {
            return stream.iterator().hasNext();
        }
    }

    private static Source.ModInfo readModInfo(Path folder, Path info, Report report) throws IOException {
        byte[] bytes = Files.readAllBytes(info);
        GameJson.Parsed parsed = GameJson.parse(bytes);
        Source pseudo = new Source(folder.getFileName().toString(), folder, null);
        if (parsed.object == null) {
            report.error(pseudo, "mod_info.json", "The game cannot read this file, so the mod does not appear in the launcher: "
                    + GameJson.cleanError(parsed.error) + where(parsed.errorLine));
            return null;
        }
        JSONObject o = parsed.object;
        Source.ModInfo mod = new Source.ModInfo();
        mod.id = o.optString("id", null);
        mod.name = o.optString("name", null);
        List<String> missing = new ArrayList<>();
        if (mod.id == null) missing.add("id");
        if (mod.name == null) missing.add("name");
        if (!o.has("description")) missing.add("description");
        if (!o.has("version")) missing.add("version");
        if (!o.has("gameVersion")) missing.add("gameVersion");
        if (!missing.isEmpty()) {
            report.error(pseudo, "mod_info.json", "Missing " + String.join(", ", missing)
                    + ". The game skips the mod without it, so it never loads.");
            return null;
        }
        mod.version = versionText(o, "version");
        mod.gameVersion = versionText(o, "gameVersion");
        mod.modPlugin = o.optString("modPlugin", null);
        mod.totalConversion = o.optBoolean("totalConversion", false);
        JSONArray jars = o.optJSONArray("jars");
        if (jars != null) for (int i = 0; i < jars.length(); i++) mod.jars.add(FileIndex.normalize(jars.optString(i, "")));
        JSONArray deps = o.optJSONArray("dependencies");
        if (deps != null) {
            for (int i = 0; i < deps.length(); i++) {
                JSONObject dep = deps.optJSONObject(i);
                if (dep != null && dep.has("id")) mod.dependencies.add(dep.optString("id"));
            }
        }
        JSONArray replace = o.optJSONArray("replace");
        if (replace != null) {
            for (int i = 0; i < replace.length(); i++) {
                String path = FileIndex.normalize(replace.optString(i, ""));
                if (!path.endsWith("settings.json")) mod.replace.add(path);
            }
        }
        return mod;
    }

    private static String versionText(JSONObject o, String key) {
        JSONObject parts = o.optJSONObject(key);
        if (parts == null) return o.optString(key, null);
        StringBuilder sb = new StringBuilder(parts.optString("major", ""));
        if (!parts.optString("minor", "").isEmpty()) sb.append('.').append(parts.optString("minor"));
        if (!parts.optString("patch", "").isEmpty()) sb.append('.').append(parts.optString("patch"));
        return sb.toString();
    }

    static String where(int line) {
        return line > 0 ? " (line " + line + ")" : "";
    }
}
