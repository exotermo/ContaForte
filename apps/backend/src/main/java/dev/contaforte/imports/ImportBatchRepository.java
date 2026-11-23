package dev.contaforte.imports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /** Escopada ao dono: importação de outro usuário vem vazia (a API responde 404). */
    Optional<ImportBatch> findByIdAndUserId(UUID id, UUID userId);

    List<ImportBatch> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** "Este arquivo já foi importado nesta conta?" (só importações confirmadas contam; a desfeita libera). */
    boolean existsByAccountIdAndFileSha256AndStatus(UUID accountId, String fileSha256, ImportStatus status);

    /** Quantas prévias o usuário criou desde o instante dado (limite de uso). */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Prévias vencidas, para a limpeza agendada. */
    List<ImportBatch> findByStatusAndExpiresAtBefore(ImportStatus status, Instant now);
}
