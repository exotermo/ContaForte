package dev.contaforte.categories;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Categoria financeira em 2 níveis. {@code userId} nulo = categoria padrão do sistema (somente leitura). */
@Entity
@Table(name = "transaction_categories")
public class TransactionCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(length = 60, updatable = false)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionCategory() {
        // exigido pelo JPA
    }

    /** Categoria do usuário (as padrão vêm da migração). */
    public TransactionCategory(UUID userId, UUID parentId, String name) {
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isDefault() { return userId == null; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getParentId() { return parentId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
}
