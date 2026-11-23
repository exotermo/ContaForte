package dev.contaforte.imports.parse;

import dev.contaforte.imports.parse.ImportParseException.Reason;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Leitor de OFX 1.x (SGML, com ou sem tags de fechamento) e 2.x (XML) feito à mão, de propósito: um tokenizador linear
 * que procura só o que interessa (STMTTRN e seus campos). NÃO usa parser XML, então não há DTD, entidade externa nem
 * expansão de entidades (XXE e "billion laughs" não existem aqui), e cada limite é nosso.
 *
 * <p>Cobre extratos de conta corrente (BANKMSGSRSV1) e de cartão (CREDITCARDMSGSRSV1): em ambos, valor negativo é
 * despesa. Um arquivo com mais de uma conta ou com moeda diferente de BRL é recusado inteiro.
 */
public final class OfxParser {

    private static final int MAX_TAG = 64;
    private static final int MAX_VALUE = 2000;
    private static final Set<String> LEAVES = Set.of("TRNTYPE", "DTPOSTED", "DTUSER", "TRNAMT", "FITID", "NAME", "MEMO");
    private static final Set<String> END_OF_LIST = Set.of("STMTTRN", "BANKTRANLIST", "STMTRS", "CCSTMTRS", "STMTTRNRS", "CCSTMTTRNRS");

    private final Clock clock;

    public OfxParser(Clock clock) {
        this.clock = clock;
    }

    public ParseResult parse(byte[] bytes) {
        ImportLimits.checkSize(bytes);
        String text = TextDecoder.decode(bytes, headerCharset(bytes));
        int start = indexOfIgnoreCase(text, "<OFX");
        if (start < 0) {
            throw new ImportParseException(Reason.UNKNOWN_FORMAT, "O arquivo não parece um OFX (não tem a marca <OFX>).");
        }

        List<ParsedRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> currencies = new TreeSet<>();
        Set<String> accounts = new TreeSet<>();
        Map<String, String> tx = null;
        int n = text.length();
        int i = start;

        while (i < n) {
            int lt = text.indexOf('<', i);
            if (lt < 0) break;
            int gt = findGt(text, lt);
            if (gt < 0) {                       // "<" solto no texto (ex.: "a < b"): segue em frente
                i = lt + 1;
                continue;
            }
            String raw = text.substring(lt + 1, gt);
            i = gt + 1;
            if (raw.isEmpty() || raw.charAt(0) == '?' || raw.charAt(0) == '!') continue;   // <?xml ...?>, <!-- -->, <!DOCTYPE
            boolean closing = raw.charAt(0) == '/';
            String name = tagName(closing ? raw.substring(1) : raw);
            if (name == null) continue;

            if (closing) {
                if (tx != null && END_OF_LIST.contains(name)) {
                    rows.add(finish(tx, rows.size() + 1));
                    tx = null;
                }
                continue;
            }
            if (name.equals("STMTTRN")) {
                if (tx != null) rows.add(finish(tx, rows.size() + 1));   // SGML sem </STMTTRN>
                if (rows.size() >= ImportLimits.MAX_ROWS) {
                    throw new ImportParseException(Reason.TOO_MANY_ROWS,
                            "O arquivo tem mais de " + ImportLimits.MAX_ROWS + " transações.");
                }
                tx = new HashMap<>();
                continue;
            }
            if (raw.endsWith("/")) continue;    // <TAG/>: elemento vazio

            boolean wanted = (tx != null && LEAVES.contains(name)) || name.equals("CURDEF") || name.equals("ACCTID");
            if (!wanted) continue;
            String value = decodeEntities(leafValue(text, i));
            if (name.equals("CURDEF")) {
                if (!value.isEmpty()) currencies.add(value.toUpperCase(Locale.ROOT));
            } else if (name.equals("ACCTID")) {
                if (!value.isEmpty()) accounts.add(value);
            } else {
                tx.putIfAbsent(name, value);
            }
        }
        if (tx != null) rows.add(finish(tx, rows.size() + 1));

        if (rows.isEmpty()) {
            throw new ImportParseException(Reason.NO_TRANSACTIONS, "Nenhuma transação encontrada no arquivo.");
        }
        if (rows.size() > ImportLimits.MAX_ROWS) {
            throw new ImportParseException(Reason.TOO_MANY_ROWS, "O arquivo tem mais de " + ImportLimits.MAX_ROWS + " transações.");
        }
        if (accounts.size() > 1) {
            throw new ImportParseException(Reason.MULTIPLE_ACCOUNTS,
                    "O arquivo tem mais de uma conta. Exporte um extrato por conta.");
        }
        if (currencies.size() > 1 || (!currencies.isEmpty() && !currencies.contains("BRL"))) {
            throw new ImportParseException(Reason.UNSUPPORTED_CURRENCY, "Só extratos em reais (BRL) são aceitos.");
        }
        if (currencies.isEmpty()) warnings.add("O arquivo não informa a moeda (CURDEF): assumindo reais.");
        return new ParseResult(rows, warnings);
    }

    // ---------------------------------------------------------------- linha

    private ParsedRow finish(Map<String, String> tx, int lineNo) {
        String dateText = firstNonBlank(tx.get("DTPOSTED"), tx.get("DTUSER"));
        if (dateText == null) return ParsedRow.invalid(lineNo, "data ausente");
        Instant when;
        try {
            when = parseDate(dateText);
        } catch (DateTimeException | NumberFormatException e) {
            return ParsedRow.invalid(lineNo, "data inválida");
        }
        String problem = DateRules.problem(when, clock);
        if (problem != null) return ParsedRow.invalid(lineNo, problem);

        BigDecimal signed;
        try {
            signed = AmountParser.parseOfx(tx.get("TRNAMT"));
        } catch (AmountParser.BadAmount e) {
            return ParsedRow.invalid(lineNo, e.getMessage());
        }
        if (signed.signum() == 0) return ParsedRow.invalid(lineNo, "valor zero");

        String name = TextNorm.clean(tx.get("NAME"), ImportLimits.MAX_DESCRIPTION);
        String memo = TextNorm.clean(tx.get("MEMO"), ImportLimits.MAX_DESCRIPTION);
        String description = memo != null ? memo : name;
        String merchant = (memo != null && name != null && !name.equalsIgnoreCase(memo))
                ? TextNorm.clean(name, ImportLimits.MAX_MERCHANT) : null;
        String fitid = TextNorm.clean(tx.get("FITID"), ImportLimits.MAX_EXTERNAL_ID);

        return ParsedRow.ok(lineNo, when, signed.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME,
                signed.abs(), description, merchant, fitid);
    }

    /**
     * {@code AAAAMMDD[HHMM[SS]][.mmm][[deslocamento:FUSO]]}. Com hora e deslocamento, é esse instante; com hora sem
     * deslocamento, vale o horário de São Paulo; só com a data, vale o meio-dia de São Paulo.
     */
    static Instant parseDate(String s) {
        String t = s.strip();
        int d = 0;
        while (d < t.length() && t.charAt(d) >= '0' && t.charAt(d) <= '9') d++;
        if (d != 8 && d != 12 && d != 14) throw new DateTimeException("formato de data");
        int year = Integer.parseInt(t.substring(0, 4));
        int month = Integer.parseInt(t.substring(4, 6));
        int day = Integer.parseInt(t.substring(6, 8));
        LocalDate date = LocalDate.of(year, month, day);
        if (d == 8) return DateRules.noonSp(date);

        int hour = Integer.parseInt(t.substring(8, 10));
        int minute = Integer.parseInt(t.substring(10, 12));
        int second = d == 14 ? Integer.parseInt(t.substring(12, 14)) : 0;
        LocalDateTime local = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute, second));

        int br = t.indexOf('[', d);
        if (br < 0) return local.atZone(DateRules.SP).toInstant();
        int end = t.indexOf(']', br);
        String off = t.substring(br + 1, end < 0 ? t.length() : end);
        int colon = off.indexOf(':');
        if (colon >= 0) off = off.substring(0, colon);
        // deslocamento em horas, com fração opcional: -3, -3.00, +5.5
        BigDecimal hours = new BigDecimal(off.strip().isEmpty() ? "0" : off.strip().replace(',', '.'));
        if (hours.abs().compareTo(new BigDecimal("18")) > 0) throw new DateTimeException("deslocamento");
        int seconds = hours.multiply(new BigDecimal("3600")).intValue();
        return local.toInstant(ZoneOffset.ofTotalSeconds(seconds));
    }

    // ---------------------------------------------------------------- tokenização (tudo linear)

    /** Procura o ">" numa janela curta: um "<" sem fechamento nunca faz a busca varrer o arquivo inteiro. */
    private static int findGt(String s, int lt) {
        int limit = Math.min(s.length(), lt + 1 + MAX_TAG);
        for (int j = lt + 1; j < limit; j++) {
            char c = s.charAt(j);
            if (c == '>') return j;
            if (c == '<') return -1;
        }
        return -1;
    }

    /** Nome do elemento (sem atributos), em maiúsculas; nulo se não parece um nome de tag. */
    private static String tagName(String raw) {
        int e = 0;
        while (e < raw.length() && raw.charAt(e) != ' ' && raw.charAt(e) != '\t' && raw.charAt(e) != '\r'
                && raw.charAt(e) != '\n' && raw.charAt(e) != '/') e++;
        if (e == 0 || e > 40) return null;
        for (int j = 0; j < e; j++) {
            char c = raw.charAt(j);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') return null;
        }
        return raw.substring(0, e).toUpperCase(Locale.ROOT);
    }

    /** Texto entre a tag e o próximo "<", aparado e limitado. */
    private static String leafValue(String s, int from) {
        int nxt = s.indexOf('<', from);
        int end = nxt < 0 ? s.length() : nxt;
        if (end - from > MAX_VALUE) end = from + MAX_VALUE;
        return s.substring(from, end).strip();
    }

    /** Só as 5 entidades do XML e as numéricas. Nada de DTD: um "&foo;" desconhecido fica como está. */
    static String decodeEntities(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi = c == '&' ? findSemicolon(s, i) : -1;
            if (semi > i) {
                String ent = s.substring(i + 1, semi);
                String rep = switch (ent) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> numericEntity(ent);
                };
                if (rep != null) {
                    out.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** ";" numa janela de 10 caracteres: um "&" sem ";" nunca faz a busca varrer o texto todo (evita custo quadrático). */
    private static int findSemicolon(String s, int amp) {
        int limit = Math.min(s.length(), amp + 11);
        for (int j = amp + 1; j < limit; j++) if (s.charAt(j) == ';') return j;
        return -1;
    }

    private static String numericEntity(String ent) {
        if (ent.length() < 2 || ent.charAt(0) != '#') return null;
        try {
            int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X' ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
            return Character.isValidCodePoint(cp) && cp > 0 ? new String(Character.toChars(cp)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Charset declarado no cabeçalho SGML (CHARSET:1252, ENCODING:UTF-8...), lendo só os primeiros bytes como ASCII. */
    private static Charset headerCharset(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 1500), StandardCharsets.ISO_8859_1);
        int stop = indexOfIgnoreCase(head, "<OFX");
        if (stop >= 0) head = head.substring(0, stop);
        String v = headerValue(head, "CHARSET:");
        if (v != null) {
            switch (v.toUpperCase(Locale.ROOT)) {
                case "1252", "WINDOWS-1252", "CP1252" -> { return TextDecoder.WINDOWS_1252; }
                case "8859-1", "ISO-8859-1", "LATIN1", "LATIN-1" -> { return StandardCharsets.ISO_8859_1; }
                case "UTF-8", "UTF8" -> { return StandardCharsets.UTF_8; }
                default -> { /* desconhecido: detecta pelo conteúdo */ }
            }
        }
        String enc = headerValue(head, "ENCODING:");
        if (enc != null && enc.equalsIgnoreCase("UTF-8")) return StandardCharsets.UTF_8;
        return null;
    }

    private static String headerValue(String head, String key) {
        int p = indexOfIgnoreCase(head, key);
        if (p < 0) return null;
        int s = p + key.length();
        int e = s;
        while (e < head.length() && head.charAt(e) != '\r' && head.charAt(e) != '\n' && head.charAt(e) != ' '
                && head.charAt(e) != '<') e++;
        String v = head.substring(s, e).strip();
        return v.isEmpty() || v.equalsIgnoreCase("NONE") ? null : v;
    }

    private static int indexOfIgnoreCase(String s, String what) {
        int max = s.length() - what.length();
        for (int i = 0; i <= max; i++) if (s.regionMatches(true, i, what, 0, what.length())) return i;
        return -1;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
