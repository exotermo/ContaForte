package dev.contaforte.imports;

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
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Uma importação de extrato para uma conta. Guarda o hash e o nome do arquivo, nunca o conteúdo; o nome do arquivo
 * pode conter dados pessoais e por isso nunca vai para logs, métricas ou auditoria.
 */
@Entity
@Table(name = "imports")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ImportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportStatus status = ImportStatus.PREVIEW;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "file_size", nullable = false, updatable = false)
    private int fileSize;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "new_rows", nullable = false)
    private int newRows;

    @Column(name = "duplicate_rows", nullable = false)
    private int duplicateRows;

    @Column(name = "possible_rows", nullable = false)
    private int possibleRows;

    @Column(name = "invalid_rows", nullable = false)
    private int invalidRows;

    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> mapping;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportBatch() {
        // exigido pelo JPA
    }

    /** Nova prévia: nasce em PREVIEW, com data de validade. */
    public ImportBatch(UUID userId, UUID accountId, ImportFormat format, String fileName, String fileSha256,
                       int fileSize, Instant expiresAt) {
        this.userId = userId;
        this.accountId = accountId;
        this.format = format;
        this.fileName = fileName;
        this.fileSha256 = fileSha256;
        this.fileSize = fileSize;
        this.expiresAt = expiresAt;
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

    /** Grava os contadores da classificação; o total é sempre a soma das quatro classes. */
    public void setCounts(int newRows, int duplicateRows, int possibleRows, int invalidRows) {
        this.newRows = newRows;
        this.duplicateRows = duplicateRows;
        this.possibleRows = possibleRows;
        this.invalidRows = invalidRows;
        this.totalRows = newRows + duplicateRows + possibleRows + invalidRows;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getAccountId() { return accountId; }
    public ImportFormat getFormat() { return format; }
    public ImportStatus getStatus() { return status; }
    public String getFileName() { return fileName; }
    public String getFileSha256() { return fileSha256; }
    public int getFileSize() { return fileSize; }
    public int getTotalRows() { return totalRows; }
    public int getNewRows() { return newRows; }
    public int getDuplicateRows() { return duplicateRows; }
    public int getPossibleRows() { return possibleRows; }
    public int getInvalidRows() { return invalidRows; }
    public int getImportedRows() { return importedRows; }
    public Map<String, String> getMapping() { return mapping; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getRolledBackAt() { return rolledBackAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMapping(Map<String, String> mapping) { this.mapping = mapping; }
    public void setStatus(ImportStatus status) { this.status = status; }
    public void setImportedRows(int importedRows) { this.importedRows = importedRows; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public void setRolledBackAt(Instant rolledBackAt) { this.rolledBackAt = rolledBackAt; }
}
