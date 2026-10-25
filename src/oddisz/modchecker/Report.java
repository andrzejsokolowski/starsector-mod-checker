package oddisz.modchecker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Collects problems and prints them grouped as mod, folder, file. */
final class Report {
    enum Severity {
        ERROR, WARNING, NOTE
    }

    record Problem(Severity severity, Source source, String path, String message) {
    }

    private final List<Problem> problems = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    void error(Source source, String path, String message) {
        add(Severity.ERROR, source, path, message);
    }

    void warning(Source source, String path, String message) {
        add(Severity.WARNING, source, path, message);
    }

    void note(Source source, String path, String message) {
        add(Severity.NOTE, source, path, message);
    }

    void add(Severity severity, Source source, String path, String message) {
        String key = severity + "|" + (source == null ? "" : source.name) + "|" + path + "|" + message;
        if (seen.add(key)) problems.add(new Problem(severity, source, path == null ? "" : path, message));
    }

    List<Problem> problems() {
        return problems;
    }

    int count(Severity severity) {
        int n = 0;
        for (Problem p : problems) if (p.severity == severity) n++;
        return n;
    }

    /** The report text. Core game files only appear when something in them is actually wrong. */
    String render(String header, boolean showNotes) {
        StringBuilder out = new StringBuilder(header);
        int errors = count(Severity.ERROR), warnings = count(Severity.WARNING), notes = count(Severity.NOTE);

        Map<String, List<Problem>> byGroup = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Problem p : problems) {
            if (p.severity == Severity.NOTE && !showNotes) continue;
            byGroup.computeIfAbsent(groupName(p.source), k -> new ArrayList<>()).add(p);
        }

        nl(out);
        if (errors == 0 && warnings == 0) {
            out.append("No errors or warnings found.");
        } else {
            out.append("Found ").append(plural(errors, "error")).append(" and ").append(plural(warnings, "warning"))
                    .append(" in ").append(plural(countGroups(byGroup), "mod")).append('.');
        }
        nl(out);
        out.append("  ERROR    the game crashes or will not start because of it");
        nl(out);
        out.append("  WARNING  can crash the game or break something in some situations");
        nl(out);
        if (showNotes) {
            out.append("  NOTE     works, but looks like a mistake");
        } else if (notes > 0) {
            out.append("  (").append(plural(notes, "note")).append(" hidden: run with --notes to see them)");
        }
        nl(out);

        if (byGroup.isEmpty()) return out.toString();

        nl(out);
        out.append("Mods with problems:");
        nl(out);
        for (Map.Entry<String, List<Problem>> group : byGroup.entrySet()) {
            int e = 0, w = 0, n = 0;
            for (Problem p : group.getValue()) {
                if (p.severity == Severity.ERROR) e++;
                else if (p.severity == Severity.WARNING) w++;
                else n++;
            }
            List<String> parts = new ArrayList<>();
            if (e > 0) parts.add(plural(e, "error"));
            if (w > 0) parts.add(plural(w, "warning"));
            if (n > 0) parts.add(plural(n, "note"));
            out.append("  ").append(group.getKey()).append(": ").append(String.join(", ", parts));
            nl(out);
        }

        for (Map.Entry<String, List<Problem>> group : byGroup.entrySet()) {
            List<Problem> list = group.getValue();
            nl(out);
            Source source = list.get(0).source;
            out.append(group.getKey());
            if (source != null && source.mod != null) {
                out.append("  (id ").append(source.mod.id);
                if (source.mod.version != null) out.append(", version ").append(source.mod.version);
                out.append(", folder mods/").append(source.root.getFileName()).append(')');
            }
            nl(out);

            Map<String, Map<String, List<Problem>>> byFolder = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Problem p : list) {
                String path = p.path.isEmpty() ? "(whole mod)" : p.path;
                int slash = path.lastIndexOf('/');
                String folder = slash < 0 ? "(mod folder)" : path.substring(0, slash);
                String file = slash < 0 ? path : path.substring(slash + 1);
                byFolder.computeIfAbsent(folder, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                        .computeIfAbsent(file, k -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<String, Map<String, List<Problem>>> folder : byFolder.entrySet()) {
                out.append("--- ").append(folder.getKey());
                nl(out);
                for (Map.Entry<String, List<Problem>> file : folder.getValue().entrySet()) {
                    out.append("------ ").append(file.getKey());
                    nl(out);
                    List<Problem> fileProblems = new ArrayList<>(file.getValue());
                    fileProblems.sort(Comparator.comparing((Problem p) -> p.severity).thenComparing(p -> p.message));
                    for (Problem p : fileProblems) {
                        out.append("--------- ").append(p.severity).append(": ").append(p.message);
                        nl(out);
                    }
                }
            }
        }
        return out.toString();
    }

    private static int countGroups(Map<String, List<Problem>> byGroup) {
        int n = 0;
        for (List<Problem> list : byGroup.values()) {
            for (Problem p : list) {
                if (p.severity != Severity.NOTE) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private static String groupName(Source source) {
        if (source == null) return "Mods folder";
        return source.name.trim();
    }

    static String plural(int n, String word) {
        if (n == 1) return n + " " + word;
        return n + " " + word + (word.endsWith("s") ? "es" : "s");
    }

    private static void nl(StringBuilder out) {
        out.append(System.lineSeparator());
    }

    /** Problems per source, for tests. */
    Map<String, List<Problem>> bySource() {
        Map<String, List<Problem>> map = new LinkedHashMap<>();
        for (Problem p : problems) map.computeIfAbsent(groupName(p.source), k -> new ArrayList<>()).add(p);
        return map;
    }
}
