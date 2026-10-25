#!/bin/sh
# Starsector Mod Checker for Linux and macOS.
# Run it from the StarsectorModChecker folder inside your Starsector folder, or give the game folder:
#   ./check-mods.sh /path/to/starsector [options]
#   ./check-mods.sh /Applications/Starsector.app [options]
# If it cannot find the game, it asks for the folder: drag it into the terminal and press Enter.

HERE="$(cd "$(dirname "$0")" && pwd)"
GAME=""
JAVA=""

# Use the Java that comes with the game, so nothing has to be installed.
find_java() {
    JAVA=""
    for candidate in "$GAME/jre_linux/bin/java" "$GAME/Contents/Home/bin/java" \
                     "$HERE/../jre_linux/bin/java" "$HERE/../Contents/Home/bin/java" \
                     "$HERE/../../jre_linux/bin/java"; do
        if [ -x "$candidate" ]; then
            JAVA="$candidate"
            return
        fi
    done
    if command -v java >/dev/null 2>&1; then
        JAVA="java"
    fi
}

if [ -n "$1" ] && [ -d "$1" ]; then
    GAME="$1"
fi
find_java
if [ -n "$JAVA" ]; then
    "$JAVA" -jar "$HERE/mod-checker.jar" "$@"
    CODE=$?
else
    CODE=3
fi

# 3 means the game folder was not found: ask for it.
while [ "$CODE" = 3 ]; do
    echo
    echo "Drag your Starsector folder into this window and press Enter."
    echo "It is the folder with starsector-core and mods in it (on macOS: Starsector.app)."
    printf 'Starsector folder: '
    IFS= read -r GAME || exit 3
    # Terminals paste a dragged folder quoted or with escaped spaces, often with a space after it.
    GAME=$(printf '%s' "$GAME" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
        -e "s/^'\(.*\)'\$/\1/" -e 's/^"\(.*\)"$/\1/' -e 's/\\ / /g')
    if [ -z "$GAME" ]; then
        exit 3
    fi
    find_java
    if [ -z "$JAVA" ]; then
        echo "Could not find Java in that folder or on this computer."
        continue
    fi
    "$JAVA" -jar "$HERE/mod-checker.jar" --game "$GAME"
    CODE=$?
done
exit "$CODE"
