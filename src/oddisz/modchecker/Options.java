package oddisz.modchecker;

/** Command line options. No org.json here: Main parses these before json.jar is loaded. */
final class Options {
    static final String USAGE = String.join(System.lineSeparator(),
            "Starsector Mod Checker " + Main.VERSION,
            "Scans the enabled mods for data files that crash or break Starsector.",
            "",
            "Usage: check-mods [options] [path to Starsector]",
            "",
            "  --game <folder>   the Starsector folder (found on its own when this tool sits inside it)",
            "  --all             check every mod in the mods folder, not only the enabled ones",
            "  --notes           also list notes (things that work but look like mistakes)",
            "  --out <file>      where to save the report (default: mod-checker-report.txt next to the tool)",
            "  --no-report       print to the terminal only",
            "  --help            show this text",
            "",
            "Exit code: 0 when no errors were found, 1 when there are errors, 2 when the options are wrong,",
            "3 when no Starsector folder was found.");

    String gamePath;
    String outPath;
    boolean allMods;
    boolean notes;
    boolean noReport;
    boolean help;

    static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--game" -> o.gamePath = value(args, ++i, a);
                case "--out" -> o.outPath = value(args, ++i, a);
                case "--all" -> o.allMods = true;
                case "--notes" -> o.notes = true;
                case "--no-report" -> o.noReport = true;
                case "--help", "-h", "/?" -> o.help = true;
                default -> {
                    if (a.startsWith("--")) throw new IllegalArgumentException("Unknown option: " + a);
                    if (o.gamePath != null) throw new IllegalArgumentException("Only one Starsector folder can be given: " + a);
                    o.gamePath = a;
                }
            }
        }
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }
}
