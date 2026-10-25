Starsector Mod Checker 1.0.1
============================

Finds the mod files that crash Starsector or break it, before you play. It reads
every enabled mod the way the game does and lists what is wrong, grouped by mod,
folder and file. It changes nothing.


Install
-------
1. Unzip, so you get the folder StarsectorModChecker.
2. Put that folder inside your Starsector folder (next to starsector-core and mods).
   It runs on the Java that comes with the game, so you need nothing else.


Use
---
Windows:        double-click check-mods.bat
Linux, macOS:   run ./check-mods.sh in a terminal

If the StarsectorModChecker folder is somewhere else, drag your Starsector
folder onto check-mods.bat. If it still cannot find the game, it asks for the
folder: drag the folder into its window and press Enter.

The report is printed and saved as mod-checker-report.txt in this folder. The
window then waits for a key before it closes.

Options, when you run it from a terminal:
    --all        check every mod in the mods folder, not only the enabled ones
    --notes      also print the notes (the saved report always has them)
    --out FILE   save the report somewhere else
    --no-report  only print, save nothing


Reading the report
------------------
    ERROR    the game crashes or will not start because of it
    WARNING  can crash the game or break something in some situations
    NOTE     works, but looks like a mistake

Each problem says what the game does with it, and the game's own error text in
brackets, so you can match it with a crash in starsector.log.


What it checks
--------------
- Files the game cannot read: JSON with // comments, a missing comma, the same key
  twice or a UTF-8 BOM, and CSV sheets with an unclosed quote or the same id twice.
- Weapons (.wpn): the barrel lists (more angles than barrels crashes at startup,
  more hardpoint barrels than turret barrels crashes in hidden slots), required
  fields, allowed values, art, animation frames and the projectile they fire.
- Projectiles (.proj), hulls (.ship), skins (.skin), ship systems (.system) and
  variants (.variant): required fields, allowed values, art, and every weapon,
  hull, hull mod, fighter wing and variant they name.
- The CSV sheets the game reads by id: ship_data, weapon_data, wing_data,
  hull_mods, ship_systems, descriptions and the campaign sheets.
- Classes named in data (hull mod scripts, weapon effects, plugins), the jars each
  mod lists, and jars built for a newer Java than your game runs.
- Ids written into the mods' code, such as getWeaponSpec("some_id"), that no file
  defines.
- Files in folders the game never reads, mods unzipped one folder too deep, two
  copies of one mod, and enabled mods that are not installed.
- File names whose upper and lower case only work on Windows.

The rules come from the loading code of Starsector 0.98a. A clean report does not
promise a crash-free game: mod code can still fail in ways no file shows.


Made by Oddisz. Uses ASM (https://asm.ow2.io), see LICENSE-ASM.txt.
