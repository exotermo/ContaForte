package dev.contaforte.imports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /** Escopada ao dono: importação de outro usuário vem vazia (a API responde 404). */
    Optional<ImportBatch> findByIdAndUserId(UUID id, UUID userId);

    List<ImportBatch> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<ImportBatch> findByUserId(UUID userId, Pageable pageable);

    /** "Este arquivo já foi importado nesta conta?" (só importações confirmadas contam; a desfeita libera). */
    boolean existsByAccountIdAndFileSha256AndStatus(UUID accountId, String fileSha256, ImportStatus status);

    /** Quantas prévias o usuário criou desde o instante dado (limite de uso). */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Prévias vencidas, para a limpeza agendada. */
    List<ImportBatch> findByStatusAndExpiresAtBefore(ImportStatus status, Instant now);

    /**
     * Mesma busca, mas com bloqueio de escrita da linha: confirmar ou desfazer duas vezes ao mesmo tempo não pode criar
     * (ou apagar) em dobro. A segunda requisição espera a primeira e então vê o status já alterado.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id and b.userId = :userId")
    Optional<ImportBatch> findForUpdate(UUID id, UUID userId);

    /** Apaga as prévias vencidas (as linhas vão junto, em cascata no banco). Devolve quantas foram. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ImportBatch b where b.status = :status and b.expiresAt < :now")
    int deleteExpired(ImportStatus status, Instant now);
}
