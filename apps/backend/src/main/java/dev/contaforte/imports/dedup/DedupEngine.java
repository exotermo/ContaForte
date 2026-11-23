package dev.contaforte.imports.dedup;

import dev.contaforte.common.AppTime;
import dev.contaforte.imports.ImportItemStatus;
import dev.contaforte.imports.parse.ParsedRow;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.TransactionType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deduplicação em camadas (ADR-016), em memória e sem banco. Recebe as linhas do arquivo e as transações já existentes
 * na conta e classifica cada linha:
 *
 * <ol>
 *   <li><b>Repetida no arquivo</b> (mesmo identificador externo ou PIX duas vezes): a segunda é DUPLICADA.</li>
 *   <li><b>Identificador externo</b> igual ao de uma transação existente: DUPLICADA (certeza).</li>
 *   <li><b>Identificador PIX</b> igual: DUPLICADA.</li>
 *   <li><b>Mesmo tipo, valor, dia e descrição</b> (sem acento, caixa e pontuação): DUPLICADA (provável). Não vale entre
 *       dois identificadores externos diferentes (dois lançamentos distintos do mesmo banco) nem sem texto.</li>
 *   <li><b>Mesmo tipo e valor a até 1 dia</b>, qualquer descrição: POSSÍVEL duplicata (o usuário decide).</li>
 *   <li>Senão, NOVA.</li>
 * </ol>
 *
 * <b>Contagem de ocorrências:</b> cada transação existente só pode "consumir" UMA linha. Dois cafés idênticos no mesmo
 * arquivo são duas transações; se já existe um café no banco, só a primeira linha é duplicada.
 *
 * <b>Três passadas, da mais certa para a menos certa</b>, cada uma sobre TODAS as linhas antes da seguinte:
 * identificadores, depois "mesmo valor, dia e texto", depois "possível". Assim uma correspondência fraca nunca rouba a
 * transação que pertence a outra linha com correspondência forte, qualquer que seja a ordem no arquivo.
 */
public final class DedupEngine {

    static final String R_FILE_REPEAT = "Identificador repetido no próprio arquivo";
    static final String R_EXTERNAL = "Identificador do extrato já importado nesta conta";
    static final String R_PIX = "Mesmo identificador PIX de uma transação existente";
    static final String R_SAME = "Mesmo valor, dia e descrição de uma transação existente";
    static final String R_NEAR = "Mesmo valor em data próxima (até 1 dia), com descrição diferente";

    private record Bucket(TransactionType type, long cents, LocalDate day, String text) {}

    private record AmountKey(TransactionType type, long cents) {}

    private DedupEngine() {
    }

    public static Classification classify(List<ParsedRow> rows, List<Existing> existing) {
        Map<String, Existing> byExternal = new HashMap<>();
        Map<String, List<Existing>> byPix = new HashMap<>();
        Map<Bucket, List<Existing>> byBucket = new HashMap<>();
        Map<AmountKey, List<Existing>> byAmount = new HashMap<>();
        for (Existing e : existing) {
            if (e.externalId() != null) byExternal.putIfAbsent(e.externalId(), e);
            if (e.pixId() != null) byPix.computeIfAbsent(e.pixId(), k -> new ArrayList<>()).add(e);
            if (!e.text().isEmpty()) byBucket.computeIfAbsent(new Bucket(e.effect(), e.cents(), e.day(), e.text()), k -> new ArrayList<>()).add(e);
            byAmount.computeIfAbsent(new AmountKey(e.effect(), e.cents()), k -> new ArrayList<>()).add(e);
        }
        Comparator<Existing> stable = Comparator.comparing(Existing::occurredAt).thenComparing(Existing::id);
        byAmount.values().forEach(l -> l.sort(stable));
        byBucket.values().forEach(l -> l.sort(stable));
        byPix.values().forEach(l -> l.sort(stable));

        RowResult[] out = new RowResult[rows.size()];
        String[] texts = new String[rows.size()];
        Set<UUID> consumed = new HashSet<>();
        Set<String> seenExternal = new HashSet<>();
        Set<String> seenPix = new HashSet<>();
        List<Integer> unmatched = new ArrayList<>();

        // passada 1 (certeza): linha inválida, repetição no arquivo, identificador externo e identificador PIX
        for (int i = 0; i < rows.size(); i++) {
            ParsedRow row = rows.get(i);
            if (!row.valid()) {
                out[i] = new RowResult(row, ImportItemStatus.INVALID, null, row.error(), null, false);
                continue;
            }
            texts[i] = TextNorm.key(row.description() != null ? row.description() : row.merchant());
            String pix = PixId.extract((row.description() == null ? "" : row.description()) + " " + (row.merchant() == null ? "" : row.merchant()));

            if ((row.externalId() != null && !seenExternal.add(row.externalId())) || (pix != null && !seenPix.add(pix))) {
                out[i] = duplicate(row, null, R_FILE_REPEAT, texts[i]);
                continue;
            }
            if (row.externalId() != null) {
                Existing e = byExternal.get(row.externalId());
                if (e != null) {   // sempre duplicata: o índice único do banco recusaria a inserção de qualquer forma
                    consumed.add(e.id());
                    out[i] = duplicate(row, e.id(), R_EXTERNAL, texts[i]);
                    continue;
                }
            }
            Existing byPixHit = null;
            if (pix != null) {
                for (Existing e : byPix.getOrDefault(pix, List.of())) {
                    if (consumed.add(e.id())) {
                        byPixHit = e;
                        break;
                    }
                }
            }
            if (byPixHit != null) {
                out[i] = duplicate(row, byPixHit.id(), R_PIX, texts[i]);
                continue;
            }
            unmatched.add(i);
        }

        // passada 2 (provável): mesmo tipo, valor, dia e descrição, casando uma linha com uma transação por vez
        List<Integer> pending = new ArrayList<>();
        for (int i : unmatched) {
            ParsedRow row = rows.get(i);
            Existing hit = null;
            if (!texts[i].isEmpty()) {
                LocalDate day = row.occurredAt().atZone(AppTime.ZONE).toLocalDate();
                for (Existing e : byBucket.getOrDefault(new Bucket(row.type(), cents(row), day, texts[i]), List.of())) {
                    // dois identificadores externos diferentes = dois lançamentos distintos do banco: não é duplicata provável
                    boolean differentIds = row.externalId() != null && e.externalId() != null;
                    if (!differentIds && consumed.add(e.id())) {
                        hit = e;
                        break;
                    }
                }
            }
            if (hit != null) out[i] = duplicate(row, hit.id(), R_SAME, texts[i]);
            else pending.add(i);
        }

        // passada 3 (possível): mesmo tipo e valor a até 1 dia, só com o que sobrou das passadas anteriores
        for (int i : pending) {
            ParsedRow row = rows.get(i);
            LocalDate day = row.occurredAt().atZone(AppTime.ZONE).toLocalDate();
            Existing best = null;
            long bestDiff = Long.MAX_VALUE;
            for (Existing e : byAmount.getOrDefault(new AmountKey(row.type(), cents(row)), List.of())) {
                if (consumed.contains(e.id())) continue;
                long diff = Math.abs(ChronoUnit.DAYS.between(e.day(), day));
                if (diff <= 1 && diff < bestDiff) {   // a lista já vem em ordem estável: o empate fica com o mais antigo
                    best = e;
                    bestDiff = diff;
                }
            }
            if (best != null) {
                consumed.add(best.id());
                out[i] = new RowResult(row, ImportItemStatus.POSSIBLE_DUPLICATE, best.id(), R_NEAR, texts[i], false);
            } else {
                out[i] = new RowResult(row, ImportItemStatus.NEW, null, null, texts[i], true);
            }
        }
        return new Classification(List.of(out));
    }

    private static RowResult duplicate(ParsedRow row, UUID of, String reason, String text) {
        return new RowResult(row, ImportItemStatus.DUPLICATE, of, reason, text, false);
    }

    private static long cents(ParsedRow row) {
        return row.amount().movePointRight(2).longValueExact();
    }
}
