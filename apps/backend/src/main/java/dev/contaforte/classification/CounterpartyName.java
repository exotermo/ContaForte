package dev.contaforte.classification;

import dev.contaforte.imports.dedup.PixId;
import dev.contaforte.imports.parse.TextNorm;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extrai o favorecido de uma descrição de PIX ("PIX ENVIADO CAFÉ DA PRAÇA", "Pix recebido - Joana Exemplo 12/03"). O nome
 * de uma pessoa é dado pessoal de terceiro (ADR-018): este código só calcula, nunca registra nada em log.
 *
 * <p>Regras: acha a palavra "pix"; pula as palavras de modelo que vêm a seguir ("enviado", "recebido", "transferência",
 * "para"...); lê o nome até encontrar algo que não é nome (identificador PIX, data, documento mascarado ou com muitos
 * dígitos, ou palavras como "banco", "agência", "cpf"). Descrição que não é PIX, ou sem nome depois do modelo, não tem
 * favorecido. Tudo linear, sem expressões regulares.
 */
public final class CounterpartyName {

    /** O favorecido: chave para comparação (sem acento nem caixa) e nome para mostrar (como veio, só limpo). */
    public record Result(String key, String display) {
    }

    static final int MAX_TOKENS = 6;
    static final int MAX_LENGTH = 120;
    static final int MIN_KEY = 3;

    /** Palavras de modelo do PIX, em forma normalizada: pulam-se antes do nome. */
    private static final Set<String> TEMPLATE = Set.of(
            "enviado", "enviada", "env", "recebido", "recebida", "rec", "recebimento", "transf", "transferencia",
            "transferido", "transferida", "pagamento", "pgto", "pagto", "pag", "qr", "qrs", "qrcode", "code", "cob",
            "copia", "cola", "chave", "de", "para", "devolucao", "devolvido", "devolvida", "estorno", "saque", "troco",
            "instantaneo", "instantanea", "dinamico", "estatico", "via", "a", "favor");

    /** Palavras que encerram o nome. */
    private static final Set<String> STOP = Set.of(
            "banco", "bco", "ag", "agencia", "cc", "conta", "cpf", "cnpj", "ref", "id", "e2e", "endtoend", "chave",
            "doc", "documento", "ispb", "psp");

    private CounterpartyName() {
    }

    /** O favorecido da descrição, ou nulo quando não há. */
    public static Result extract(String description) {
        String text = TextNorm.clean(description == null ? null : description.replace(':', ' ').replace(';', ' ').replace('|', ' ').replace(',', ' '), 200);
        if (text == null) return null;
        String[] tokens = splitPixPrefix(text.split(" "));
        int i = 0;
        while (i < tokens.length && !"pix".equals(TextNorm.key(tokens[i]))) i++;
        if (i == tokens.length) return null;
        i++;

        List<String> name = new ArrayList<>();
        int length = 0;
        for (; i < tokens.length && name.size() < MAX_TOKENS; i++) {
            String token = tokens[i];
            String key = TextNorm.key(token);
            if (name.isEmpty()) {
                if (key.isEmpty() || TEMPLATE.contains(key)) continue;   // pontuação solta e palavras de modelo
            } else if (key.isEmpty()) {
                break;                                                   // "NOME - BANCO": o traço encerra
            }
            if (isNotName(token, key)) break;
            if (length + token.length() + 1 > MAX_LENGTH) break;
            name.add(stripEdges(token));
            length += token.length() + 1;
        }
        if (name.isEmpty()) return null;
        String display = String.join(" ", name);
        String key = TextNorm.key(display);
        return key.length() < MIN_KEY ? null : new Result(key, display);
    }

    /** "PIX-ENVIADO" e "PIX_RECEBIDO" colados viram duas palavras; o traço dentro de um nome ("ANA-MARIA") fica. */
    private static String[] splitPixPrefix(String[] tokens) {
        List<String> out = new ArrayList<>(tokens.length + 1);
        for (String t : tokens) {
            if (t.length() > 4 && t.regionMatches(true, 0, "pix", 0, 3) && (t.charAt(3) == '-' || t.charAt(3) == '_')) {
                out.add(t.substring(0, 3));
                out.add(t.substring(4));
            } else {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean isNotName(String token, String key) {
        if (STOP.contains(key)) return true;
        if (token.indexOf('*') >= 0) return true;                        // documento mascarado
        if (PixId.extract(token) != null) return true;                   // identificador fim a fim
        int digits = 0;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) digits++;
        }
        return digits >= 3 || (digits > 0 && token.indexOf('/') >= 0);   // documento, data, número longo
    }

    private static String stripEdges(String token) {
        int a = 0;
        int b = token.length();
        while (a < b && !Character.isLetterOrDigit(token.charAt(a))) a++;
        while (b > a && !Character.isLetterOrDigit(token.charAt(b - 1))) b--;
        return token.substring(a, b);
    }
}
