package dev.contaforte.imports;

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
