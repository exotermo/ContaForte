package dev.contaforte.imports.dedup;

import dev.contaforte.common.AppTime;
import dev.contaforte.imports.ImportItem;
import dev.contaforte.imports.ImportItemStatus;
import dev.contaforte.imports.parse.ImportLimits;
import dev.contaforte.imports.parse.ParseResult;
import dev.contaforte.imports.parse.ParsedRow;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classifica as linhas de um extrato contra o que já existe na conta. O chamador garante que a conta é do usuário
 * (as transações de uma conta são, pela chave estrangeira composta, sempre do dono dela). São no máximo duas consultas:
 * as transações ativas no intervalo de datas do arquivo (com um dia de folga) e as que têm os identificadores externos
 * do arquivo, em qualquer data, porque o índice único de identificador vale sempre.
 */
@Service
public class ImportClassifier {

    private static final int IN_CHUNK = 500;

    private final TransactionRepository transactions;

    ImportClassifier(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public Classification classify(UUID accountId, ParseResult parsed) {
        List<ParsedRow> valid = parsed.rows().stream().filter(ParsedRow::valid).toList();
        if (valid.isEmpty()) return DedupEngine.classify(parsed.rows(), List.of());

        LocalDate first = null;
        LocalDate last = null;
        List<String> ids = new ArrayList<>();
        for (ParsedRow r : valid) {
            LocalDate d = r.occurredAt().atZone(AppTime.ZONE).toLocalDate();
            if (first == null || d.isBefore(first)) first = d;
            if (last == null || d.isAfter(last)) last = d;
            if (r.externalId() != null) ids.add(r.externalId());
        }
        Instant from = first.minusDays(1).atStartOfDay(AppTime.ZONE).toInstant();
        Instant to = last.plusDays(2).atStartOfDay(AppTime.ZONE).toInstant();   // fim exclusivo: inclui o dia seguinte inteiro

        Map<UUID, Transaction> found = new LinkedHashMap<>();
        for (Transaction t : transactions.findActiveTouchingAccountBetween(accountId, from, to)) found.put(t.getId(), t);
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            for (Transaction t : transactions.findActiveByAccountAndExternalIds(accountId, ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))) {
                found.putIfAbsent(t.getId(), t);
            }
        }
        List<Existing> existing = new ArrayList<>(found.size());
        for (Transaction t : found.values()) existing.add(Existing.of(t, accountId));
        return DedupEngine.classify(parsed.rows(), existing);
    }

    /** Linhas da prévia prontas para gravar (respeitam as restrições do banco: só nova e possível podem vir marcadas). */
    public static List<ImportItem> toItems(UUID importId, Classification c) {
        List<ImportItem> items = new ArrayList<>(c.rows().size());
        for (RowResult r : c.rows()) {
            ParsedRow row = r.row();
            ImportItem item = new ImportItem(importId, row.lineNo(), r.status());
            if (r.status() == ImportItemStatus.INVALID) {
                item.setReason(trim(r.reason(), 200));
            } else {
                item.setOccurredAt(row.occurredAt());
                item.setType(row.type());
                item.setAmount(row.amount());
                item.setDescription(trim(row.description(), ImportLimits.MAX_DESCRIPTION));
                item.setMerchant(trim(row.merchant(), ImportLimits.MAX_MERCHANT));
                item.setNormalizedText(trim(r.text(), 400));
                item.setExternalId(row.externalId());
                item.setDuplicateOf(r.duplicateOf());
                item.setReason(trim(r.reason(), 200));
                item.setSelected(r.selected());
            }
            items.add(item);
        }
        return items;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
