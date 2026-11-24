package dev.contaforte.transactions;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Fila de revisão: receitas e despesas com sugestão pendente ou sem categoria (ADR-017). */
    @Query("select t from Transaction t where t.userId = :userId and t.deletedAt is null "
            + "and t.type in (dev.contaforte.transactions.TransactionType.INCOME, dev.contaforte.transactions.TransactionType.EXPENSE) "
            + "and (t.classificationStatus = dev.contaforte.transactions.ClassificationStatus.SUGGESTED or t.categoryId is null)")
    Page<Transaction> findReviewQueue(UUID userId, Pageable pageable);

    @Query("select count(t) from Transaction t where t.userId = :userId and t.deletedAt is null "
            + "and t.classificationStatus = dev.contaforte.transactions.ClassificationStatus.SUGGESTED "
            + "and t.type in (dev.contaforte.transactions.TransactionType.INCOME, dev.contaforte.transactions.TransactionType.EXPENSE)")
    long countSuggested(UUID userId);

    @Query("select count(t) from Transaction t where t.userId = :userId and t.deletedAt is null and t.categoryId is null "
            + "and t.classificationStatus <> dev.contaforte.transactions.ClassificationStatus.SUGGESTED "
            + "and t.type in (dev.contaforte.transactions.TransactionType.INCOME, dev.contaforte.transactions.TransactionType.EXPENSE)")
    long countUncategorized(UUID userId);

    /**
     * Candidatas a reclassificação: ativas, receita ou despesa, SEM categoria e que o usuário ainda não decidiu
     * (nunca se sobrescreve uma escolha do usuário, ADR-017). Ordem fixa para o resultado ser repetível.
     */
    @Query("select t from Transaction t where t.userId = :userId and t.deletedAt is null and t.categoryId is null "
            + "and t.classificationStatus in (dev.contaforte.transactions.ClassificationStatus.NONE, dev.contaforte.transactions.ClassificationStatus.SUGGESTED) "
            + "and t.type in (dev.contaforte.transactions.TransactionType.INCOME, dev.contaforte.transactions.TransactionType.EXPENSE)")
    Page<Transaction> findReclassifiable(UUID userId, Pageable pageable);

    /** Transações ativas de uma importação que o usuário classificou (para reverter o aprendizado ao desfazê-la). */
    @Query("select t from Transaction t where t.importId = :importId and t.userId = :userId and t.deletedAt is null "
            + "and t.classificationStatus = dev.contaforte.transactions.ClassificationStatus.CONFIRMED")
    List<Transaction> findConfirmedByImport(UUID importId, UUID userId);
}
