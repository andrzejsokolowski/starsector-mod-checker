package oddisz.modchecker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starsector's CSV reader (com.fs.starfarer.loading.G), line for line, plus line numbers. The header is
 * split on every comma, quotes in it are stripped. A row whose first character is '#' is a comment, an
 * empty line is skipped, "" inside quotes is one quote, and an unclosed quote is a fatal error.
 */
final class GameCsv {
    private GameCsv() {
    }

    static final class Row {
        final Map<String, String> cells = new LinkedHashMap<>();
        int line;
        int cellCount;

        String get(String column) {
            return cells.get(column);
        }
    }

    static final class Table {
        final List<String> header = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        String error;
        int errorLine;
        boolean bom;
    }

    static Table parse(byte[] bytes) {
        Table table = new Table();
        table.bom = GameJson.hasBom(bytes);
        String text = GameJson.text(bytes);
        int firstBreak = text.indexOf('\n');
        if (firstBreak == -1 || text.length() <= firstBreak + 1) return table;

        for (String name : text.substring(0, firstBreak).split(",")) {
            name = name.trim();
            if (name.startsWith("\"")) name = name.substring(1);
            if (name.endsWith("\"")) name = name.substring(0, name.length() - 1);
            table.header.add(name.replace("\"\"", "\""));
        }

        String body = text.substring(firstBreak + 1) + "\n";
        int line = 2;
        Row row = null;
        StringBuilder cell = null;
        int column = 0;
        boolean quoted = false;
        boolean comment = false;
        int quoteLine = 0;
        boolean atRowStart = true; // "state o00000" in the game: the next character starts a row
        boolean newCell = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : ' ';
            boolean firstCharOfRow = false;
            if (atRowStart) {
                comment = c == '#';
                row = new Row();
                row.line = line;
                column = 0;
                firstCharOfRow = true;
                atRowStart = false;
                newCell = true;
            }
            if (newCell) {
                cell = new StringBuilder();
                newCell = false;
            }
            if (c == '"') {
                if (next == '"') {
                    cell.append(c);
                    i++;
                } else {
                    quoted = !quoted;
                    if (quoted) quoteLine = line;
                }
            } else if ((c == ',' || c == '\n') && !quoted) {
                if (column < table.header.size()) row.cells.put(table.header.get(column), cell.toString());
                if (c == ',') {
                    column++;
                    newCell = true;
                } else {
                    row.cellCount = column + 1;
                    if (!firstCharOfRow && !comment) table.rows.add(row);
                    atRowStart = true;
                }
            } else {
                cell.append(c);
            }
            if (c == '\n') line++;
        }
        if (quoted) {
            table.error = "A quote (\") opened on line " + quoteLine + " is never closed";
            table.errorLine = quoteLine;
        }
        return table;
    }
}
