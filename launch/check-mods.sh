#!/bin/sh
# Starsector Mod Checker for Linux and macOS.
# Run it from the StarsectorModChecker folder inside your Starsector folder, or give the game folder:
#   ./check-mods.sh /path/to/starsector [options]
#   ./check-mods.sh /Applications/Starsector.app [options]

HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA=""

# Use the Java that comes with the game, so nothing has to be installed.
for candidate in "$1/jre_linux/bin/java" "$1/Contents/Home/bin/java" \
                 "$HERE/../jre_linux/bin/java" "$HERE/../Contents/Home/bin/java" \
                 "$HERE/../../jre_linux/bin/java"; do
    if [ -x "$candidate" ]; then
        JAVA="$candidate"
        break
    fi
done
if [ -z "$JAVA" ]; then
    if command -v java >/dev/null 2>&1; then
        JAVA="java"
    else
        echo "Could not find Java. Run this from the StarsectorModChecker folder inside your Starsector folder,"
        echo "or give the Starsector folder: ./check-mods.sh /path/to/starsector"
        exit 2
    fi
fi

exec "$JAVA" -jar "$HERE/mod-checker.jar" "$@"
