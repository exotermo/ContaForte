package dev.contaforte.imports.dedup;

import dev.contaforte.imports.ImportItemStatus;
import java.util.List;

/** Linhas classificadas, na ordem do arquivo, com as contagens que vão para a importação. */
public record Classification(List<RowResult> rows) {

    public Classification {
        rows = List.copyOf(rows);
    }

    public int count(ImportItemStatus status) {
        int n = 0;
        for (RowResult r : rows) if (r.status() == status) n++;
        return n;
    }
}
