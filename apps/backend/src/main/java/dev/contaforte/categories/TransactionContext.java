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

/** Contexto pessoal do gasto (individual, família, relacionamento, pet...). Independe da categoria (ADR-009). */
@Entity
@Table(name = "transaction_contexts")
public class TransactionContext {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(length = 60, updatable = false)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionContext() {
        // exigido pelo JPA
    }

    public TransactionContext(UUID userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isDefault() { return userId == null; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
}
