# Starsector Mod Checker

Finds the mod files that crash Starsector 0.98a or break it, before you play. It reads every
enabled mod the way the game does and lists what is wrong, grouped by mod, folder and file:

```
United Aurora Federation  (id uaf, version 0.8.4c, folder mods/United Aurora Federation-0.8.4c)
--- data/weapons
------ uaf_example.wpn
--------- ERROR: "turretAngleOffsets" has 3 angles but "turretOffsets" only defines 2 barrels (2 numbers each). The game crashes while loading (IndexOutOfBoundsException in addTurretAngleOffset). Give each barrel exactly one angle.
```

The report is printed and saved as `mod-checker-report.txt`. It changes no files.

Forum topic: https://fractalsoftworks.com/forum/index.php?topic=36202.0

## Install and run

1. Download `StarsectorModChecker.zip` from the latest release and unzip it.
2. Put the `StarsectorModChecker` folder inside your Starsector folder.
3. Windows: double-click `check-mods.bat`. Linux and macOS: run `./check-mods.sh`.

It uses the Java that comes with the game. If the folder is somewhere else, drag your Starsector
folder onto `check-mods.bat`. If it still can't find the game, it asks for the folder in its
window: drag the folder in and press Enter. Options, from a terminal: `--all` (every mod, not
only enabled ones), `--notes`, `--out FILE`, `--no-report`.

## Levels

- **ERROR**: the game crashes or will not start because of it.
- **WARNING**: can crash the game or break something in some situations.
- **NOTE**: works, but looks like a mistake.

## What it checks

- Files the game cannot read (JSON with `//` comments, missing commas, duplicate keys, a UTF-8
  BOM; CSV with an unclosed quote or a repeated id).
- Weapons: barrel lists, required fields, allowed values, art, animation frames, projectile ids.
- Projectiles, hulls, skins, ship systems and variants: required fields, allowed values, art, and
  every weapon, hull, hull mod, wing and variant they name.
- The CSV sheets the game reads by id.
- Classes named in data, the jars each mod lists, and jars built for a newer Java than the game.
- Ids written into mod code (`getWeaponSpec("id")` and similar) that no file defines.
- Files in folders the game never reads, mods unzipped one folder too deep, duplicate mods,
  enabled mods that are not installed, and file names whose case only works on Windows.

The rules copy the loading code of Starsector 0.98a. A clean report does not promise a crash-free
game: mod code can still fail in ways no file shows.

## Building

`./gradlew releaseZip` (set `starsectorPath` in `gradle.properties` first). Needs JDK 17 or newer.

Made by Oddisz. Uses [ASM](https://asm.ow2.io) (BSD licence).
