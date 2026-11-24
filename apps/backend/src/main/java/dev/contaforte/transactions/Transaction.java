package dev.contaforte.transactions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lançamento financeiro. {@code amount} é sempre positivo (BigDecimal/NUMERIC, ADR-008). Descrição e valor são
 * dados pessoais: nunca vão para logs, métricas ou auditoria.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "counterpart_account_id")
    private UUID counterpartAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "BRL";

    @Column(length = 200)
    private String description;

    @Column(length = 120)
    private String merchant;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "context_id")
    private UUID contextId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionSource source = TransactionSource.MANUAL;

    @Column(name = "import_id", updatable = false)
    private UUID importId;

    /** Identificador no extrato (FITID etc.). Opaco; único por conta entre as transações ativas. */
    @Column(name = "external_id", length = 255, updatable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_status", nullable = false, length = 10)
    private ClassificationStatus classificationStatus = ClassificationStatus.NONE;

    /** Quem decidiu (USER, RULE, DEFAULT_RULE ou RECIPIENT); nulo enquanto o estado é NONE. */
    @Column(name = "classification_source", length = 20)
    private String classificationSource;

    @Column(name = "classification_confidence", precision = 3, scale = 2)
    private BigDecimal classificationConfidence;

    @Column(name = "suggested_category_id")
    private UUID suggestedCategoryId;

    @Column(name = "suggested_context_id")
    private UUID suggestedContextId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {
        // exigido pelo JPA
    }

    public Transaction(UUID userId, UUID accountId, TransactionType type, BigDecimal amount, Instant occurredAt) {
        this.userId = userId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDeleted() { return deletedAt != null; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getAccountId() { return accountId; }
    public UUID getCounterpartAccountId() { return counterpartAccountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public String getMerchant() { return merchant; }
    public UUID getCategoryId() { return categoryId; }
    public UUID getContextId() { return contextId; }
    public Instant getOccurredAt() { return occurredAt; }
    public TransactionSource getSource() { return source; }
    public UUID getImportId() { return importId; }
    public String getExternalId() { return externalId; }
    public ClassificationStatus getClassificationStatus() { return classificationStatus; }
    public String getClassificationSource() { return classificationSource; }
    public BigDecimal getClassificationConfidence() { return classificationConfidence; }
    public UUID getSuggestedCategoryId() { return suggestedCategoryId; }
    public UUID getSuggestedContextId() { return suggestedContextId; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCounterpartAccountId(UUID counterpartAccountId) { this.counterpartAccountId = counterpartAccountId; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setDescription(String description) { this.description = description; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public void setContextId(UUID contextId) { this.contextId = contextId; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    /** Marca a transação como vinda de uma importação (origem OFX/CSV, com o identificador do extrato). */
    public void markImported(UUID importId, String externalId, TransactionSource source) {
        this.importId = importId;
        this.externalId = externalId;
        this.source = source;
    }

    /** Estado de classificação inteiro de uma vez: o banco só aceita certas combinações (ck_transactions_class_*). */
    public void setClassification(ClassificationStatus status, String source, BigDecimal confidence,
                                  UUID suggestedCategoryId, UUID suggestedContextId) {
        this.classificationStatus = status;
        this.classificationSource = source;
        this.classificationConfidence = confidence;
        this.suggestedCategoryId = suggestedCategoryId;
        this.suggestedContextId = suggestedContextId;
    }

    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
