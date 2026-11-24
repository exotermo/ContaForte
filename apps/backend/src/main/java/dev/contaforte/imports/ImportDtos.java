package dev.contaforte.imports;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.contaforte.classification.Decision;
import dev.contaforte.classification.Source;
import dev.contaforte.common.DecimalString;
import dev.contaforte.transactions.TransactionSource;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos públicos da API de importações. Nada aqui carrega o conteúdo bruto do arquivo. */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * Uma importação. Os três últimos campos são opcionais e só aparecem quando fazem sentido: {@code fileAlreadyImported}
     * e {@code warnings} na criação da prévia, {@code skippedByConflict} na confirmação (linhas que viraram duplicata
     * entre a prévia e a confirmação, por exemplo por outra importação).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportResponse(
            UUID id, UUID accountId, ImportFormat format, ImportStatus status, String fileName,
            int totalRows, int newRows, int duplicateRows, int possibleRows, int invalidRows, int importedRows,
            Map<String, String> mapping, Instant expiresAt, Instant confirmedAt, Instant rolledBackAt, Instant createdAt,
            Boolean fileAlreadyImported, List<String> warnings, Integer skippedByConflict,
            Integer autoClassified, Integer suggested) {

        static ImportResponse of(ImportBatch b) {
            return new ImportResponse(b.getId(), b.getAccountId(), b.getFormat(), b.getStatus(), b.getFileName(),
                    b.getTotalRows(), b.getNewRows(), b.getDuplicateRows(), b.getPossibleRows(), b.getInvalidRows(),
                    b.getImportedRows(), b.getMapping(), b.getStatus() == ImportStatus.PREVIEW ? b.getExpiresAt() : null,
                    b.getConfirmedAt(), b.getRolledBackAt(), b.getCreatedAt(), null, null, null, null, null);
        }

        ImportResponse withPreviewInfo(boolean alreadyImported, List<String> warnings) {
            return new ImportResponse(id, accountId, format, status, fileName, totalRows, newRows, duplicateRows, possibleRows,
                    invalidRows, importedRows, mapping, expiresAt, confirmedAt, rolledBackAt, createdAt, alreadyImported, warnings, null, autoClassified, suggested);
        }

        /** Quantas linhas novas ou possíveis o motor classificou sozinho e quantas só sugeriu (só na prévia). */
        ImportResponse withClassification(int auto, int suggested) {
            return new ImportResponse(id, accountId, format, status, fileName, totalRows, newRows, duplicateRows, possibleRows,
                    invalidRows, importedRows, mapping, expiresAt, confirmedAt, rolledBackAt, createdAt, fileAlreadyImported, warnings,
                    skippedByConflict, auto, suggested);
        }

        ImportResponse withSkipped(int skipped) {
            return new ImportResponse(id, accountId, format, status, fileName, totalRows, newRows, duplicateRows, possibleRows,
                    invalidRows, importedRows, mapping, expiresAt, confirmedAt, rolledBackAt, createdAt, null, null, skipped, autoClassified, suggested);
        }
    }

    /** Transação existente com a qual uma linha foi comparada (para o usuário decidir uma possível duplicata). */
    public record ExistingSnapshot(UUID id, Instant occurredAt, TransactionType type, @DecimalString BigDecimal amount,
                                   String description, TransactionSource source) {}

    /**
     * O que o motor decidiu para a linha (alvo de categoria e de contexto, decisão de cada um, origem e confiança) e a
     * escolha do usuário na prévia, se houver. Só linhas novas e possíveis duplicatas têm.
     */
    public record ItemClassification(
            Decision decision, Source source, BigDecimal confidence,
            UUID categoryId, Decision categoryDecision, UUID contextId, Decision contextDecision,
            boolean chosen, UUID chosenCategoryId, UUID chosenContextId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportItemResponse(
            UUID id, int lineNo, ImportItemStatus status, Instant occurredAt, TransactionType type,
            @DecimalString BigDecimal amount, String description, String merchant, String reason, boolean selected,
            UUID duplicateOf, ExistingSnapshot existing, ItemClassification classification) {}

    /** Troca a classificação de uma linha da prévia: {@code reset} volta ao que o motor decidiu; senão vale o que vier (nulo = nenhum). */
    public record ItemClassificationRequest(UUID categoryId, UUID contextId, boolean reset) {}

    /**
     * {@code includePossible}: ids de linhas POSSIBLE_DUPLICATE que o usuário decidiu importar mesmo assim.
     * {@code exclude}: ids de linhas NEW que o usuário decidiu não importar.
     */
    public record ConfirmRequest(List<UUID> includePossible, List<UUID> exclude) {}
}
