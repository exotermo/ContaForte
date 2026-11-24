package dev.contaforte.transactions;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    /** Escopada ao dono e ignorando as excluídas logicamente (a API responde 404 nos dois casos). */
    Optional<Transaction> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    /**
     * Transações ativas que mexem no saldo da conta (como origem ou como destino) num intervalo de datas
     * [from, to): são os candidatos a duplicata de uma importação (ADR-016).
     */
    @Query("select t from Transaction t where t.deletedAt is null and t.occurredAt >= :from and t.occurredAt < :to "
            + "and (t.accountId = :accountId or t.counterpartAccountId = :accountId)")
    List<Transaction> findActiveTouchingAccountBetween(UUID accountId, Instant from, Instant to);

    /** Transações ativas da conta com um destes identificadores externos, em qualquer data (o índice único vale sempre). */
    @Query("select t from Transaction t where t.deletedAt is null and t.accountId = :accountId and t.externalId in :ids")
    List<Transaction> findActiveByAccountAndExternalIds(UUID accountId, Collection<String> ids);

    /** Rollback lógico de uma importação: marca como excluídas as transações dela que ainda estão ativas. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Transaction t set t.deletedAt = :now, t.updatedAt = :now where t.importId = :importId and t.deletedAt is null")
    int softDeleteByImport(UUID importId, Instant now);
}
