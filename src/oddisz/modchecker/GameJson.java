package oddisz.modchecker;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reads JSON the way Starsector 0.98a does (com.fs.starfarer.loading.LoadingUtils): drop every '#'
 * comment outside a string, then hand the text to the game's own org.json. Also merges copies of one
 * file from several mods with the game's rules.
 */
final class GameJson {
    private static final Pattern LINE = Pattern.compile("line (\\d+)\\]");
    private static final Pattern DUPLICATE = Pattern.compile("Duplicate key \"(.*)\"");

    private GameJson() {
    }

    /** The outcome of reading one file. Exactly one of {@code object} and {@code error} is set. */
    static final class Parsed {
        JSONObject object;
        String error;
        int errorLine;
        boolean bom;
    }

    static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    /** The game decodes UTF-8 and drops every carriage return. */
    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r", "");
    }

    static Parsed parse(byte[] bytes) {
        Parsed result = new Parsed();
        result.bom = hasBom(bytes);
        String raw = text(bytes);
        String stripped = stripComments(raw);
        try {
            result.object = new JSONObject(stripped);
        } catch (JSONException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            result.error = message;
            Matcher m = LINE.matcher(message);
            if (m.find()) {
                result.errorLine = Integer.parseInt(m.group(1));
            } else {
                Matcher dup = DUPLICATE.matcher(message);
                if (dup.find()) result.errorLine = secondKeyLine(stripped, dup.group(1));
            }
        }
        return result;
    }

    /**
     * Exactly the game's comment stripping: a '#' outside double quotes starts a comment that runs to
     * the end of the line. Quote tracking restarts on every line, and a backslash does not escape a quote.
     */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean comment = false;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c != '\n' && c != '\r') {
                if (c == '#' && !quoted) {
                    comment = true;
                } else if (!comment) {
                    out.append(c);
                }
            } else {
                comment = false;
                quoted = false;
                if (c == '\n') out.append('\n');
            }
        }
        return out.toString();
    }

    /** The line where a duplicated key appears for the second time, or 0. */
    private static int secondKeyLine(String text, String key) {
        Pattern p = Pattern.compile("[\"']?" + Pattern.quote(key) + "[\"']?\\s*(:|=>?)");
        Matcher m = p.matcher(text);
        int found = 0;
        while (m.find()) {
            found++;
            if (found == 2) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) if (text.charAt(i) == '\n') line++;
                return line;
            }
        }
        return 0;
    }

    /** The error text without org.json's position suffix. */
    static String cleanError(String message) {
        String m = message.replaceAll("\\s*at \\d+ \\[character \\d+ line \\d+\\]", "");
        return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }

    /**
     * Folds one more copy of a file into the master copy, as the game does when several mods ship the
     * same path: arrays are appended (or cleared by a leading "core_clearArray"), 4-number colour arrays
     * are replaced, objects merge key by key, and plain values overwrite as text. A value whose kind
     * differs between the copies makes the game crash; those keys go into {@code conflicts}.
     */
    static void merge(JSONObject master, JSONObject overlay, String path, List<String> conflicts) throws JSONException {
        Iterator<?> keys = overlay.sortedKeys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            String where = path.isEmpty() ? key : path + "." + key;
            JSONArray array = overlay.optJSONArray(key);
            JSONObject object = overlay.optJSONObject(key);
            if (array != null) {
                if (master.has(key) && master.optJSONArray(key) == null) {
                    conflicts.add(where + " is a list here but not in the other copy");
                    continue;
                }
                if (!master.has(key)) master.put(key, new JSONArray());
                boolean clear = array.length() >= 1 && "core_clearArray".equals(array.optString(0, null));
                if (clear) master.put(key, new JSONArray());
                JSONArray target = master.getJSONArray(key);
                String lower = key.toLowerCase(Locale.ROOT);
                if (target.length() == 4 && (lower.contains("color") || lower.contains("button")) || lower.startsWith("music_")) {
                    master.put(key, new JSONArray());
                    target = master.getJSONArray(key);
                }
                for (int i = 0; i < array.length(); i++) target.put(array.get(i));
            } else if (object != null) {
                if (master.has(key) && master.optJSONObject(key) == null) {
                    conflicts.add(where + " is a block ({...}) here but not in the other copy");
                    continue;
                }
                if (!master.has(key)) master.put(key, new JSONObject());
                merge(master.getJSONObject(key), object, where, conflicts);
            } else {
                master.put(key, overlay.optString(key));
            }
        }
    }

    /** Deep copy, so merging never changes a cached parse. */
    static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
