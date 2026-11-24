package dev.contaforte.imports;

import dev.contaforte.classification.Decision;
import dev.contaforte.classification.Part;
import dev.contaforte.classification.Result;
import dev.contaforte.transactions.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do extrato na prévia, já normalizada. {@code amount} é sempre positivo (o tipo dá o sinal) e o texto
 * original da linha não é guardado.
 */
@Entity
@Table(name = "import_items")
public class ImportItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "import_id", nullable = false, updatable = false)
    private UUID importId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportItemStatus status;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TransactionType type;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 200)
    private String description;

    @Column(length = 120)
    private String merchant;

    @Column(name = "normalized_text", length = 400)
    private String normalizedText;

    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "duplicate_of")
    private UUID duplicateOf;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false)
    private boolean selected;

    // ---- classificação (M4): o que o motor decidiu e o que o usuário escolheu na prévia
    @Column(name = "engine_category_id")
    private UUID engineCategoryId;

    @Column(name = "engine_category_decision", length = 7)
    private String engineCategoryDecision;

    @Column(name = "engine_context_id")
    private UUID engineContextId;

    @Column(name = "engine_context_decision", length = 7)
    private String engineContextDecision;

    @Column(name = "engine_decision", length = 7)
    private String engineDecision;

    @Column(name = "engine_source", length = 20)
    private String engineSource;

    @Column(name = "engine_confidence", precision = 3, scale = 2)
    private BigDecimal engineConfidence;

    @Column(nullable = false)
    private boolean chosen;

    @Column(name = "chosen_category_id")
    private UUID chosenCategoryId;

    @Column(name = "chosen_context_id")
    private UUID chosenContextId;

    protected ImportItem() {
        // exigido pelo JPA
    }

    public ImportItem(UUID importId, int lineNo, ImportItemStatus status) {
        this.importId = importId;
        this.lineNo = lineNo;
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getImportId() { return importId; }
    public int getLineNo() { return lineNo; }
    public ImportItemStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public String getMerchant() { return merchant; }
    public String getNormalizedText() { return normalizedText; }
    public String getExternalId() { return externalId; }
    public UUID getDuplicateOf() { return duplicateOf; }
    public String getReason() { return reason; }
    public boolean isSelected() { return selected; }

    public UUID getEngineCategoryId() { return engineCategoryId; }
    public String getEngineCategoryDecision() { return engineCategoryDecision; }
    public UUID getEngineContextId() { return engineContextId; }
    public String getEngineContextDecision() { return engineContextDecision; }
    public String getEngineDecision() { return engineDecision; }
    public String getEngineSource() { return engineSource; }
    public BigDecimal getEngineConfidence() { return engineConfidence; }
    public boolean isChosen() { return chosen; }
    public UUID getChosenCategoryId() { return chosenCategoryId; }
    public UUID getChosenContextId() { return chosenContextId; }

    /** Guarda o que o motor decidiu para esta linha (só alvos decididos; sem decisão fica tudo nulo). */
    public void setEngine(Result r) {
        Part cat = r.category();
        Part ctx = r.context();
        engineCategoryId = cat.decided() ? cat.id() : null;
        engineCategoryDecision = cat.decided() ? cat.decision().name() : null;
        engineContextId = ctx.decided() ? ctx.id() : null;
        engineContextDecision = ctx.decided() ? ctx.decision().name() : null;
        Result.Summary sum = r.summary();
        boolean any = sum.decision() != Decision.NONE;
        engineDecision = any ? sum.decision().name() : null;
        engineSource = any ? sum.source().name() : null;
        engineConfidence = any ? sum.confidence().toBigDecimal() : null;
    }

    /** O usuário escolheu categoria e contexto (qualquer um pode ser nulo = "nenhum"); {@code reset} volta ao motor. */
    public void choose(UUID categoryId, UUID contextId) {
        chosen = true;
        chosenCategoryId = categoryId;
        chosenContextId = contextId;
    }

    public void resetChoice() {
        chosen = false;
        chosenCategoryId = null;
        chosenContextId = null;
    }

    public void setStatus(ImportItemStatus status) { this.status = status; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public void setType(TransactionType type) { this.type = type; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setDescription(String description) { this.description = description; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public void setNormalizedText(String normalizedText) { this.normalizedText = normalizedText; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public void setDuplicateOf(UUID duplicateOf) { this.duplicateOf = duplicateOf; }
    public void setReason(String reason) { this.reason = reason; }
    public void setSelected(boolean selected) { this.selected = selected; }
}
