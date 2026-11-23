package dev.contaforte.imports.parse;

import java.util.List;

/** Resultado da leitura de um arquivo: as linhas (válidas e inválidas, na ordem) e avisos que não impedem a importação. */
public record ParseResult(List<ParsedRow> rows, List<String> warnings) {

    public ParseResult {
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public long validCount() {
        return rows.stream().filter(ParsedRow::valid).count();
    }

    public long invalidCount() {
        return rows.size() - validCount();
    }
}
