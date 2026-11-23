package dev.contaforte.imports.parse;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Como ler as colunas de um CSV. Uma coluna é referenciada pelo nome do cabeçalho (sem diferenciar acento e caixa) ou
 * por {@code #N} (posição, a partir de 1), único jeito quando o arquivo não tem cabeçalho.
 *
 * <p>O valor vem de UMA destas formas: uma coluna de valor com sinal ({@code amountColumn}, negativo = despesa; com
 * {@code typeColumn}, o tipo D/C decide o sinal) ou colunas separadas de débito e crédito.
 */
public record CsvMapping(
        Character delimiter,
        int skipLines,
        boolean hasHeader,
        String dateColumn,
        CsvDateFormat dateFormat,
        String amountColumn,
        String debitColumn,
        String creditColumn,
        String typeColumn,
        String descriptionColumn,
        String merchantColumn,
        String idColumn,
        boolean invertSign) {

    private static final Set<String> KEYS = Set.of("delimiter", "skipLines", "hasHeader", "dateColumn", "dateFormat",
            "amountColumn", "debitColumn", "creditColumn", "typeColumn", "descriptionColumn", "merchantColumn",
            "idColumn", "invertSign");

    /** Lê o mapeamento enviado pela API. Chave desconhecida é recusada (um erro de digitação não passa em silêncio). */
    public static CsvMapping fromMap(Map<String, String> m) {
        if (m == null) throw bad("Informe o mapeamento de colunas do CSV.");
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!KEYS.contains(e.getKey())) throw bad("Campo de mapeamento desconhecido: " + abbreviate(e.getKey()) + ".");
            if (e.getValue() != null && e.getValue().length() > 100) throw bad("Valor longo demais em " + e.getKey() + ".");
        }
        CsvMapping mapping = new CsvMapping(
                delimiter(m.get("delimiter")),
                integer(m.get("skipLines"), 0),
                !"false".equalsIgnoreCase(blank(m.get("hasHeader")) == null ? "true" : m.get("hasHeader").strip()),
                blank(m.get("dateColumn")),
                dateFormat(m.get("dateFormat")),
                blank(m.get("amountColumn")),
                blank(m.get("debitColumn")),
                blank(m.get("creditColumn")),
                blank(m.get("typeColumn")),
                blank(m.get("descriptionColumn")),
                blank(m.get("merchantColumn")),
                blank(m.get("idColumn")),
                "true".equalsIgnoreCase(blank(m.get("invertSign")) == null ? "false" : m.get("invertSign").strip()));
        mapping.validate();
        return mapping;
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        if (delimiter != null) m.put("delimiter", delimiter == '\t' ? "TAB" : String.valueOf(delimiter));
        m.put("skipLines", String.valueOf(skipLines));
        m.put("hasHeader", String.valueOf(hasHeader));
        put(m, "dateColumn", dateColumn);
        m.put("dateFormat", dateFormat.name());
        put(m, "amountColumn", amountColumn);
        put(m, "debitColumn", debitColumn);
        put(m, "creditColumn", creditColumn);
        put(m, "typeColumn", typeColumn);
        put(m, "descriptionColumn", descriptionColumn);
        put(m, "merchantColumn", merchantColumn);
        put(m, "idColumn", idColumn);
        m.put("invertSign", String.valueOf(invertSign));
        return m;
    }

    /** Confere o que precisa existir: data e de onde vem o valor. */
    public void validate() {
        if (skipLines < 0 || skipLines > 100) throw bad("skipLines deve estar entre 0 e 100.");
        if (dateColumn == null) throw bad("Escolha a coluna da data.");
        boolean split = debitColumn != null || creditColumn != null;
        if (amountColumn == null && !split) throw bad("Escolha a coluna do valor (ou as colunas de débito e crédito).");
        if (amountColumn != null && split) throw bad("Use a coluna de valor OU as colunas de débito e crédito, não as duas formas.");
        if (!hasHeader) {
            for (String ref : new String[] {dateColumn, amountColumn, debitColumn, creditColumn, typeColumn,
                    descriptionColumn, merchantColumn, idColumn}) {
                if (ref != null && !ref.startsWith("#")) throw bad("Sem cabeçalho, as colunas devem ser indicadas por posição (#1, #2...).");
            }
        }
    }

    private static Character delimiter(String v) {
        if ("\t".equals(v)) return '\t';   // a tabulação é "em branco" para blank(): tratada antes
        String s = blank(v);
        if (s == null) return null;
        if (s.equalsIgnoreCase("TAB") || s.equals("\t")) return '\t';
        if (s.length() == 1 && ";,|".indexOf(s.charAt(0)) >= 0) return s.charAt(0);
        throw bad("Delimitador inválido (use ; , | ou TAB).");
    }

    private static CsvDateFormat dateFormat(String v) {
        String s = blank(v);
        if (s == null) return CsvDateFormat.AUTO;
        try {
            return CsvDateFormat.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw bad("Formato de data inválido.");
        }
    }

    private static int integer(String v, int dflt) {
        String s = blank(v);
        if (s == null) return dflt;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw bad("skipLines deve ser um número.");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    private static String abbreviate(String s) {
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private static ImportParseException bad(String message) {
        return new ImportParseException(Reason.BAD_MAPPING, message);
    }
}
