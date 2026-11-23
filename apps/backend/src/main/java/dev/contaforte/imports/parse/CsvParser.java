package dev.contaforte.imports.parse;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de CSV bancário. Aceita aspas (RFC 4180), quebras de linha dentro de campo, CRLF/LF/CR, BOM e UTF-8 ou
 * Windows-1252. Tudo linear e com limites: tamanho do arquivo, número de linhas, tamanho de campo e de colunas.
 * Linha ruim vira uma linha inválida com o motivo; o arquivo só é recusado inteiro quando não dá para confiar nele
 * (aspas não fechadas, mapeamento que não casa com o cabeçalho, limite estourado).
 */
public final class CsvParser {

    private static final char[] DELIMITERS = {';', ',', '\t', '|'};
    private final Clock clock;

    public CsvParser(Clock clock) {
        this.clock = clock;
    }

    private record Rec(int line, List<String> fields) {}

    // ---------------------------------------------------------------- leitura com mapeamento

    public ParseResult parse(byte[] bytes, CsvMapping mapping) {
        mapping.validate();
        ImportLimits.checkSize(bytes);
        String text = TextDecoder.decode(bytes, null);
        char delim = mapping.delimiter() != null ? mapping.delimiter() : detectDelimiter(text, mapping.skipLines());
        List<Rec> recs = read(text, delim, mapping.skipLines(), Integer.MAX_VALUE);
        if (recs.isEmpty()) throw new ImportParseException(Reason.NO_TRANSACTIONS, "Nenhuma linha encontrada no arquivo.");

        List<String> headers = mapping.hasHeader() ? cleanHeaders(recs.get(0).fields()) : List.of();
        int firstData = mapping.hasHeader() ? 1 : 0;
        int width = recs.stream().mapToInt(r -> r.fields().size()).max().orElse(0);

        int dateIdx = resolve(mapping.dateColumn(), headers, width);
        int amountIdx = resolve(mapping.amountColumn(), headers, width);
        int debitIdx = resolve(mapping.debitColumn(), headers, width);
        int creditIdx = resolve(mapping.creditColumn(), headers, width);
        int typeIdx = resolve(mapping.typeColumn(), headers, width);
        int descIdx = resolve(mapping.descriptionColumn(), headers, width);
        int merchantIdx = resolve(mapping.merchantColumn(), headers, width);
        int idIdx = resolve(mapping.idColumn(), headers, width);

        if (recs.size() - firstData > ImportLimits.MAX_ROWS) {
            throw new ImportParseException(Reason.TOO_MANY_ROWS, "O arquivo tem mais de " + ImportLimits.MAX_ROWS + " linhas.");
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = firstData; i < recs.size(); i++) {
            Rec r = recs.get(i);
            rows.add(row(r, mapping, dateIdx, amountIdx, debitIdx, creditIdx, typeIdx, descIdx, merchantIdx, idIdx));
        }
        if (rows.isEmpty()) throw new ImportParseException(Reason.NO_TRANSACTIONS, "O arquivo só tem o cabeçalho.");
        List<String> warnings = new ArrayList<>();
        if (descIdx < 0) warnings.add("Nenhuma coluna de descrição foi escolhida: a deduplicação usará só data e valor.");
        return new ParseResult(rows, warnings);
    }

    private ParsedRow row(Rec r, CsvMapping m, int dateIdx, int amountIdx, int debitIdx, int creditIdx, int typeIdx,
                          int descIdx, int merchantIdx, int idIdx) {
        int line = r.line();
        String dateRaw = field(r, dateIdx);
        if (dateRaw.isBlank()) return ParsedRow.invalid(line, "data ausente");
        Instant when;
        try {
            when = m.dateFormat().parse(dateRaw);
        } catch (DateTimeException e) {
            return ParsedRow.invalid(line, "data inválida");
        }
        String problem = DateRules.problem(when, clock);
        if (problem != null) return ParsedRow.invalid(line, problem);

        BigDecimal signed;
        try {
            if (debitIdx >= 0 || creditIdx >= 0) {
                BigDecimal debit = optionalAmount(field(r, debitIdx));
                BigDecimal credit = optionalAmount(field(r, creditIdx));
                if (debit != null && credit != null) return ParsedRow.invalid(line, "débito e crédito preenchidos na mesma linha");
                if (debit == null && credit == null) return ParsedRow.invalid(line, "valor ausente");
                signed = debit != null ? debit.negate() : credit;
            } else {
                BigDecimal v = AmountParser.parseBr(field(r, amountIdx));
                if (typeIdx >= 0) {
                    String kind = TextNorm.key(field(r, typeIdx));
                    boolean debit = kind.equals("d") || kind.equals("deb") || kind.startsWith("debit") || kind.equals("s")
                            || kind.startsWith("saida");
                    boolean credit = kind.equals("c") || kind.equals("cred") || kind.startsWith("credit") || kind.equals("e")
                            || kind.startsWith("entrada");
                    if (debit == credit) return ParsedRow.invalid(line, "tipo desconhecido (esperado débito ou crédito)");
                    signed = debit ? v.abs().negate() : v.abs();
                } else {
                    signed = v;
                }
            }
        } catch (AmountParser.BadAmount e) {
            return ParsedRow.invalid(line, e.getMessage());
        }
        if (m.invertSign()) signed = signed.negate();
        if (signed.signum() == 0) return ParsedRow.invalid(line, "valor zero");

        return ParsedRow.ok(line, when, signed.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME, signed.abs(),
                TextNorm.clean(field(r, descIdx), ImportLimits.MAX_DESCRIPTION),
                TextNorm.clean(field(r, merchantIdx), ImportLimits.MAX_MERCHANT),
                TextNorm.clean(field(r, idIdx), ImportLimits.MAX_EXTERNAL_ID));
    }

    /** Coluna vazia ou com zero conta como "não preenchida" (débito/crédito em colunas separadas). */
    private static BigDecimal optionalAmount(String raw) {
        if (raw.isBlank()) return null;
        BigDecimal v = AmountParser.parseBr(raw).abs();
        return v.signum() == 0 ? null : v;
    }

    private static String field(Rec r, int idx) {
        return idx < 0 || idx >= r.fields().size() ? "" : r.fields().get(idx);
    }

    // ---------------------------------------------------------------- sugestão de mapeamento

    public CsvSuggestion suggest(byte[] bytes) {
        ImportLimits.checkSize(bytes);
        String text = TextDecoder.decode(bytes, null);
        char delim = detectDelimiter(text, 0);
        List<Rec> recs = read(text, delim, 0, 60);
        List<String> warnings = new ArrayList<>();
        if (recs.isEmpty()) throw new ImportParseException(Reason.NO_TRANSACTIONS, "Nenhuma linha encontrada no arquivo.");

        // a linha do cabeçalho é a primeira com a quantidade de colunas mais comum (pula títulos e avisos do topo)
        int modal = modalWidth(recs);
        int headerAt = 0;
        while (headerAt < recs.size() && recs.get(headerAt).fields().size() != modal) headerAt++;
        int skip = recs.get(headerAt).line() - 1;
        List<String> first = recs.get(headerAt).fields();
        boolean hasHeader = !looksLikeData(first);

        int dataStart = hasHeader ? headerAt + 1 : headerAt;
        List<List<String>> sample = new ArrayList<>();
        for (int i = dataStart; i < recs.size() && sample.size() < 5; i++) {
            if (recs.get(i).fields().size() == modal) sample.add(shorten(recs.get(i).fields()));
        }
        List<String> headers = hasHeader ? cleanHeaders(first) : List.of();
        Map<String, String> mapping = new LinkedHashMap<>();
        if (hasHeader) {
            mapping = guess(headers, recs, dataStart, delim, skip);
            if (mapping.isEmpty()) warnings.add("Não consegui adivinhar as colunas: escolha a data e o valor.");
        } else {
            warnings.add("O arquivo não parece ter cabeçalho: indique as colunas por posição (#1, #2...).");
        }
        return new CsvSuggestion(delim, skip, hasHeader, headers, sample, mapping, warnings);
    }

    private Map<String, String> guess(List<String> headers, List<Rec> recs, int dataStart, char delim, int skip) {
        Map<String, String> m = new LinkedHashMap<>();
        int date = -1;
        int amount = -1;
        int debit = -1;
        int credit = -1;
        int type = -1;
        int desc = -1;
        int merchant = -1;
        int id = -1;
        for (int i = 0; i < headers.size(); i++) {
            String k = TextNorm.key(headers.get(i));
            if (k.contains("saldo")) continue;
            if (date < 0 && (k.contains("data") || k.equals("date") || k.equals("dt"))) date = i;
            else if (amount < 0 && (k.contains("valor") || k.contains("amount") || k.contains("montante"))) amount = i;
            else if (debit < 0 && (k.contains("debito") || k.contains("saida") || k.equals("debit"))) debit = i;
            else if (credit < 0 && (k.contains("credito") || k.contains("entrada") || k.equals("credit"))) credit = i;
            else if (type < 0 && (k.equals("tipo") || k.contains("natureza") || k.equals("dc") || k.equals("d c"))) type = i;
            else if (desc < 0 && (k.contains("historico") || k.contains("descricao") || k.contains("lancamento")
                    || k.contains("memo") || k.contains("detalhe") || k.contains("description") || k.contains("titulo"))) desc = i;
            else if (merchant < 0 && (k.contains("estabelecimento") || k.contains("favorecido") || k.contains("beneficiario")
                    || k.contains("merchant"))) merchant = i;
            else if (id < 0 && (k.equals("id") || k.contains("identificador") || k.contains("documento") || k.contains("fitid")
                    || k.contains("autenticacao") || k.equals("nsu") || k.equals("codigo"))) id = i;
        }
        if (date < 0 || (amount < 0 && debit < 0 && credit < 0)) return m;

        if (delim != ';' ) m.put("delimiter", delim == '\t' ? "TAB" : String.valueOf(delim));
        else m.put("delimiter", ";");
        m.put("skipLines", String.valueOf(skip));
        m.put("hasHeader", "true");
        m.put("dateColumn", ref(headers, date));
        m.put("dateFormat", guessDateFormat(recs, dataStart, date).name());
        if (debit >= 0 || credit >= 0) {
            if (debit >= 0) m.put("debitColumn", ref(headers, debit));
            if (credit >= 0) m.put("creditColumn", ref(headers, credit));
        } else {
            m.put("amountColumn", ref(headers, amount));
            if (type >= 0) m.put("typeColumn", ref(headers, type));
        }
        if (desc >= 0) m.put("descriptionColumn", ref(headers, desc));
        if (merchant >= 0) m.put("merchantColumn", ref(headers, merchant));
        if (id >= 0) m.put("idColumn", ref(headers, id));
        m.put("invertSign", "false");
        return m;
    }

    /** Cabeçalho repetido não identifica a coluna: nesse caso usa a posição. */
    private static String ref(List<String> headers, int idx) {
        String h = headers.get(idx);
        long same = headers.stream().filter(o -> TextNorm.key(o).equals(TextNorm.key(h))).count();
        return h.isBlank() || same > 1 ? "#" + (idx + 1) : h;
    }

    private CsvDateFormat guessDateFormat(List<Rec> recs, int dataStart, int dateIdx) {
        CsvDateFormat[] order = {CsvDateFormat.DMY_SLASH, CsvDateFormat.YMD_DASH, CsvDateFormat.DMY_DASH,
                CsvDateFormat.DMY_DOT, CsvDateFormat.YMD_SLASH, CsvDateFormat.DMY_SLASH_SHORT};
        List<String> values = new ArrayList<>();
        for (int i = dataStart; i < recs.size() && values.size() < 30; i++) {
            String v = field(recs.get(i), dateIdx).strip();
            if (!v.isEmpty()) values.add(v);
        }
        for (CsvDateFormat f : order) {
            boolean all = !values.isEmpty();
            for (String v : values) {
                try {
                    f.parse(v);
                } catch (DateTimeException e) {
                    all = false;
                    break;
                }
            }
            if (all) return f;
        }
        return CsvDateFormat.AUTO;
    }

    /** Primeira célula parecendo data ou todas parecendo número: é dado, não cabeçalho. */
    private boolean looksLikeData(List<String> cells) {
        for (String c : cells) {
            try {
                CsvDateFormat.AUTO.parse(c);
                return true;
            } catch (DateTimeException e) {
                // não é data
            }
        }
        return false;
    }

    private static int modalWidth(List<Rec> recs) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Rec r : recs) counts.merge(r.fields().size(), 1, Integer::sum);
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            // empate: prefere a linha mais larga (títulos de uma célula só perdem)
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() > best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static List<String> shorten(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) out.add(c.length() > 60 ? c.substring(0, 60) + "…" : c);
        return out;
    }

    // ---------------------------------------------------------------- colunas

    private static List<String> cleanHeaders(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String h : raw) {
            String c = TextNorm.clean(h, 80);
            out.add(c == null ? "" : c);
        }
        return out;
    }

    /** Posição da coluna (0-based), ou -1 se não foi pedida. Coluna pedida que não existe recusa o arquivo. */
    private static int resolve(String ref, List<String> headers, int width) {
        if (ref == null) return -1;
        if (ref.startsWith("#")) {
            int n;
            try {
                n = Integer.parseInt(ref.substring(1).strip());
            } catch (NumberFormatException e) {
                throw badColumn(ref, headers);
            }
            if (n < 1 || n > width) throw badColumn(ref, headers);
            return n - 1;
        }
        String want = TextNorm.key(ref);
        for (int i = 0; i < headers.size(); i++) if (TextNorm.key(headers.get(i)).equals(want)) return i;
        throw badColumn(ref, headers);
    }

    private static ImportParseException badColumn(String ref, List<String> headers) {
        String shown = ref.length() > 40 ? ref.substring(0, 40) + "…" : ref;
        String available = headers.isEmpty() ? "" : " Colunas do arquivo: " + String.join(", ", headers.stream().limit(30).toList()) + ".";
        return new ImportParseException(Reason.BAD_MAPPING, "A coluna \"" + shown + "\" não existe no arquivo." + available);
    }

    // ---------------------------------------------------------------- tokenização RFC 4180

    /** Delimitador mais consistente nas primeiras linhas (fora de aspas). Empate: ponto e vírgula (padrão brasileiro). */
    static char detectDelimiter(String text, int skipLines) {
        int pos = 0;
        for (int s = 0; s < skipLines && pos < text.length(); s++) pos = nextLineStart(text, pos);
        int[][] counts = new int[DELIMITERS.length][];
        List<int[]> perLine = new ArrayList<>();
        int lines = 0;
        int[] cur = new int[DELIMITERS.length];
        boolean inQuotes = false;
        boolean any = false;
        for (int i = pos; i < text.length() && lines < 20; i++) {
            char c = text.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            if (!inQuotes && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any) {
                    perLine.add(cur);
                    lines++;
                }
                cur = new int[DELIMITERS.length];
                any = false;
                continue;
            }
            if (!inQuotes) {
                for (int d = 0; d < DELIMITERS.length; d++) if (c == DELIMITERS[d]) cur[d]++;
            }
            any |= !Character.isWhitespace(c);
        }
        if (any && lines < 20) perLine.add(cur);
        char best = ';';
        int bestScore = 0;
        for (int d = 0; d < DELIMITERS.length; d++) {
            Map<Integer, Integer> freq = new HashMap<>();
            for (int[] l : perLine) if (l[d] > 0) freq.merge(l[d], 1, Integer::sum);
            int modeCount = 0;
            int modeWidth = 0;
            for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
                if (e.getValue() > modeCount) {
                    modeCount = e.getValue();
                    modeWidth = e.getKey();
                }
            }
            int score = modeCount * 1000 + modeWidth;
            if (score > bestScore) {
                bestScore = score;
                best = DELIMITERS[d];
            }
        }
        return best;
    }

    private static int nextLineStart(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
        if (i < s.length() && s.charAt(i) == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
        return Math.min(s.length(), i + 1);
    }

    /** Máquina de estados do CSV; devolve os registros não vazios, com o número da linha onde cada um começa. */
    private static List<Rec> read(String text, char delim, int skipLines, int maxRecords) {
        int pos = 0;
        int line = 1;
        for (int s = 0; s < skipLines && pos < text.length(); s++) {
            pos = nextLineStart(text, pos);
            line++;
        }
        List<Rec> recs = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStart = true;
        boolean wasQuoted = false;
        int recStart = line;
        int n = text.length();

        for (int i = pos; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        append(sb, '"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') line++;
                    append(sb, c);
                }
                continue;
            }
            if (c == '"' && fieldStart) {
                inQuotes = true;
                wasQuoted = true;
                fieldStart = false;
            } else if (c == delim) {
                addField(fields, sb, wasQuoted);
                fieldStart = true;
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                addField(fields, sb, wasQuoted);
                finishRecord(recs, fields, recStart);
                fields = new ArrayList<>();
                fieldStart = true;
                wasQuoted = false;
                line++;
                recStart = line;
                if (recs.size() > ImportLimits.MAX_ROWS + 2 && maxRecords == Integer.MAX_VALUE) {
                    throw new ImportParseException(Reason.TOO_MANY_ROWS, "O arquivo tem mais de " + ImportLimits.MAX_ROWS + " linhas.");
                }
                if (recs.size() >= maxRecords) return recs;
            } else {
                fieldStart = false;
                append(sb, c);
            }
        }
        if (inQuotes) {
            throw new ImportParseException(Reason.UNCLOSED_QUOTE, "O arquivo tem aspas que não foram fechadas.");
        }
        if (sb.length() > 0 || !fields.isEmpty() || wasQuoted) {
            addField(fields, sb, wasQuoted);
            finishRecord(recs, fields, recStart);
        }
        return recs;
    }

    private static void append(StringBuilder sb, char c) {
        if (sb.length() < ImportLimits.MAX_FIELD) sb.append(c);
    }

    private static void addField(List<String> fields, StringBuilder sb, boolean quoted) {
        if (fields.size() < ImportLimits.MAX_COLUMNS) fields.add(quoted ? sb.toString() : sb.toString().strip());
        sb.setLength(0);
    }

    /** Linha em branco (todos os campos vazios) é ignorada. */
    private static void finishRecord(List<Rec> recs, List<String> fields, int line) {
        boolean empty = true;
        for (String f : fields) if (!f.isBlank()) { empty = false; break; }
        if (!empty) recs.add(new Rec(line, fields));
    }
}
