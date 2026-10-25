package oddisz.modchecker;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Typed reads that report what the game would choke on. Each method states the org.json call the
 * game makes: get... throws when the key is missing or the value has the wrong type (a crash), opt...
 * quietly falls back to a default.
 */
final class Fields {
    static final String LOAD_CRASH = "The game crashes while loading.";

    private final Report report;
    private final Source source;
    private final String path;

    Fields(Report report, Source source, String path) {
        this.report = report;
        this.source = source;
        this.path = path;
    }

    Fields(Report report, Checker.Spec spec) {
        this(report, spec.owner, spec.path);
    }

    void error(String message) {
        report.error(source, path, message);
    }

    void warning(String message) {
        report.warning(source, path, message);
    }

    void note(String message) {
        report.note(source, path, message);
    }

    static boolean isNumber(Object v) {
        if (v instanceof Number) return true;
        if (v instanceof String s) {
            try {
                Double.parseDouble(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** What optString returns: "" when missing, "null" for a JSON null. */
    static String text(JSONObject o, String key) {
        return o.optString(key);
    }

    /** What optString(key, null) returns: null only when the key is missing. */
    static String textOrNull(JSONObject o, String key) {
        return o.has(key) ? o.optString(key) : null;
    }

    /** getString: the key must exist. */
    String requireText(JSONObject o, String key, String label) {
        if (!o.has(key)) {
            error(label + "\"" + key + "\" is missing. " + LOAD_CRASH + " (JSONObject[\"" + key + "\"] not found)");
            return null;
        }
        return o.optString(key);
    }

    /** getDouble or getInt: the key must exist and hold a number. */
    boolean requireNumber(JSONObject o, String key, String label) {
        if (!o.has(key)) {
            error(label + "\"" + key + "\" is missing. " + LOAD_CRASH + " (JSONObject[\"" + key + "\"] not found)");
            return false;
        }
        if (!isNumber(o.opt(key))) {
            error(label + "\"" + key + "\" is \"" + o.optString(key) + "\", not a number. " + LOAD_CRASH);
            return false;
        }
        return true;
    }

    /** optDouble: a value that is not a number is ignored and the default is used. */
    void optionalNumber(JSONObject o, String key, String label) {
        if (o.has(key) && !isNumber(o.opt(key)) && !o.isNull(key)) {
            warning(label + "\"" + key + "\" is \"" + o.optString(key) + "\", not a number, so the game ignores it and uses"
                    + " its default.");
        }
    }

    /**
     * The game's enum reader: optString, then Enum.valueOf. Empty or missing is fatal unless the field has
     * a default. Spelling and upper case must match exactly.
     */
    String enumValue(JSONObject o, String key, Set<String> allowed, boolean required, String label) {
        String v = o.optString(key);
        if (v == null || v.isEmpty()) {
            if (required) {
                error(label + "\"" + key + "\" is missing. " + LOAD_CRASH + " (Key [" + key + "] is required)");
            }
            return null;
        }
        if (!allowed.contains(v)) {
            error(label + "\"" + key + "\" is \"" + v + "\", which the game does not know. Allowed: "
                    + String.join(", ", allowed) + ". " + LOAD_CRASH + " (Key [" + key + "] has invalid value [" + v + "])");
            return null;
        }
        return v;
    }

    /**
     * A list of enum names in a JSON array, read with Enum.valueOf one by one. Read with getJSONArray
     * (mustBeList) a value that is not a list crashes; read with optJSONArray it is ignored.
     */
    void enumList(JSONObject o, String key, Set<String> allowed, String label, boolean mustBeList) {
        if (!o.has(key)) return;
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            if (mustBeList) error(label + "\"" + key + "\" must be a list in [ ]. " + LOAD_CRASH);
            else if (!o.optString(key).isEmpty()) note(label + "\"" + key + "\" is not a list in [ ], so the game ignores it.");
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "");
            if (!allowed.contains(v)) {
                error(label + "\"" + key + "\" contains \"" + v + "\", which the game does not know. Allowed: "
                        + String.join(", ", allowed) + ". " + LOAD_CRASH + " (No enum constant ..." + v + ")");
            }
        }
    }

    /** getJSONArray: the key must exist and hold a list. Returns null otherwise. */
    JSONArray requireArray(JSONObject o, String key, String label) {
        if (!o.has(key)) {
            error(label + "\"" + key + "\" is missing. " + LOAD_CRASH + " (JSONObject[\"" + key + "\"] not found)");
            return null;
        }
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            error(label + "\"" + key + "\" must be a list in [ ], but is \"" + o.optString(key) + "\". " + LOAD_CRASH
                    + " (JSONObject[\"" + key + "\"] is not a JSONArray)");
        }
        return a;
    }

    /** getJSONObject: the key must exist and hold a block in { }. */
    JSONObject requireObject(JSONObject o, String key, String label) {
        if (!o.has(key)) {
            error(label + "\"" + key + "\" is missing. " + LOAD_CRASH + " (JSONObject[\"" + key + "\"] not found)");
            return null;
        }
        JSONObject v = o.optJSONObject(key);
        if (v == null) {
            error(label + "\"" + key + "\" must be a block in { }. " + LOAD_CRASH + " (JSONObject[\"" + key
                    + "\"] is not a JSONObject)");
        }
        return v;
    }

    /** A point [x, y], read with getJSONArray then getDouble(0) and getDouble(1). */
    void requirePoint(JSONObject o, String key, String label) {
        JSONArray a = requireArray(o, key, label);
        if (a == null) return;
        if (a.length() < 2 || !isNumber(a.opt(0)) || !isNumber(a.opt(1))) {
            error(label + "\"" + key + "\" must be two numbers [x, y], but is " + a + ". " + LOAD_CRASH);
        }
    }

    /** Every entry of the list must be a number (read with getDouble). */
    boolean allNumbers(JSONArray a, String key, String label) {
        for (int i = 0; i < a.length(); i++) {
            if (!isNumber(a.opt(i))) {
                error(label + "\"" + key + "\" has \"" + a.optString(i) + "\" at position " + (i + 1)
                        + ", which is not a number (often a missing comma). " + LOAD_CRASH);
                return false;
            }
        }
        return true;
    }

    /** The game's colour reader: absent is fine (white), present needs at least 4 whole numbers. */
    void color(JSONObject o, String key, String label) {
        if (!o.has(key)) return;
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            error(label + "\"" + key + "\" must be a colour [r, g, b, a]. " + LOAD_CRASH);
            return;
        }
        if (a.length() < 4) {
            error(label + "\"" + key + "\" has " + a.length() + " numbers; a colour needs 4 [r, g, b, a]. " + LOAD_CRASH
                    + " (Color array with id [" + key + "] has less than 4 elements)");
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (!isNumber(a.opt(i))) {
                error(label + "\"" + key + "\" has \"" + a.optString(i) + "\", which is not a number. " + LOAD_CRASH);
                return;
            }
        }
    }
}
