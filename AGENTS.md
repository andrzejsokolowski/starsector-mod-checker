# Starsector Mod Checker

These instructions apply to this repository, including when it is opened without the parent mod workspace.
The workspace `AGENTS.md` one folder up still governs releases, packaging, Git identity and communication.

A command-line tool, not a mod: it is never put in the mods folder and has no mod_info.json or
.version file. Players unzip `StarsectorModChecker/` into their Starsector folder and run
`check-mods.bat` or `check-mods.sh`, which start the game's own Java.

## Notion

Tasks, history and test lists: the **Mod Checker board** in Andrzej's Notion, under
**Starsector mods** (`collection://f5208742-d6f1-4120-8bed-281a1f793e0d`). Working rules and game
API notes: **Claude Information** (`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting
with "Start here: working on Starsector mods through Notion". Ticket ids start with SMC. Read the
board with `notion-query-data-sources` mode `view` on `view://40420ca7-5d20-8169-a32e-000ca0cb2c60`.
Update the board during the work, not only at release time.

## Build

`./gradlew releaseZip` compiles against the game's `json.jar` (`starsectorPath` in
`gradle.properties`), runs the tests, and writes `StarsectorModChecker.zip` in this folder with
the staging folder `StarsectorModChecker/`. ASM is bundled into `mod-checker.jar`; `json.jar` is
not, it is loaded from the player's install at run time.

Run it on the real install: `D:/Games/StarSector/jre/bin/java -jar build/libs/mod-checker.jar
--game D:/Games/StarSector --notes`. Andrzej's game starts, so any ERROR that says "crashes while
loading" on that install is a false positive in a rule: fix the rule.

## Layout

- `Main` - finds the game (`GameInstall`), then runs `Checker` in a class loader that also holds
  the game's `json.jar`. Must not touch org.json itself.
- `Checker` - loads the core and enabled mods in the game's order, merges copies of a file the
  game's way (`GameJson.merge`: lists append, objects merge, values overwrite as text), reports.
- `GameJson`, `GameCsv` - ports of the game's readers (`LoadingUtils`, `loading.G`).
- `Rules` - .proj, .wpn, .ship, .skin, .system, .variant in load order; `Sheets` - the CSVs;
  `Extras` - every other data file, art references, unread folders; `CodeScan` - ids written into
  mod jars (ASM); `ClassIndex` - classes and jar Java versions.
- `Enums` - allowed values, read from the 0.98a classes.

Severity: ERROR only for what the game's code certainly does at load (or in combat for content
that is mounted). Things that crash only when some content is used are WARNING. Harmless or
unknowable things are NOTE. Every rule names the loader behaviour it copies.
