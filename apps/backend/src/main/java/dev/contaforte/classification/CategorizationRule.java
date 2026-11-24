package dev.contaforte.classification;

import dev.contaforte.transactions.TransactionType;
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
import java.time.Instant;
import java.util.UUID;

/** Regra do usuário (tabela categorization_rules). O padrão é guardado já normalizado. */
@Entity
@Table(name = "categorization_rules")
class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 12)
    private MatchType matchType;

    @Column(nullable = false, length = 120)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 20)
    private TransactionType transactionType;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "context_id")
    private UUID contextId;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CategorizationRule() {
        // exigido pelo JPA
    }

    CategorizationRule(UUID userId, MatchType matchType, String pattern, TransactionType transactionType,
                       UUID categoryId, UUID contextId, int priority) {
        this.userId = userId;
        this.matchType = matchType;
        this.pattern = pattern;
        this.transactionType = transactionType;
        this.categoryId = categoryId;
        this.contextId = contextId;
        this.priority = priority;
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

    Rule toRule() {
        return new Rule(id, matchType, pattern, transactionType, categoryId, contextId, priority, createdAt, Source.RULE);
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    MatchType getMatchType() { return matchType; }
    String getPattern() { return pattern; }
    TransactionType getTransactionType() { return transactionType; }
    UUID getCategoryId() { return categoryId; }
    UUID getContextId() { return contextId; }
    int getPriority() { return priority; }
    boolean isEnabled() { return enabled; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }

    void setMatchType(MatchType v) { matchType = v; }
    void setPattern(String v) { pattern = v; }
    void setTransactionType(TransactionType v) { transactionType = v; }
    void setCategoryId(UUID v) { categoryId = v; }
    void setContextId(UUID v) { contextId = v; }
    void setPriority(int v) { priority = v; }
    void setEnabled(boolean v) { enabled = v; }
}
