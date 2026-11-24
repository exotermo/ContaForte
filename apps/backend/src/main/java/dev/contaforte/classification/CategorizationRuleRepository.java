package dev.contaforte.classification;

import dev.contaforte.transactions.TransactionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, UUID> {

    Optional<CategorizationRule> findByIdAndUserId(UUID id, UUID userId);

    @Query("select r from CategorizationRule r where r.userId = :userId order by r.priority, r.createdAt, r.id")
    List<CategorizationRule> findAllOfUser(UUID userId);

    @Query("select r from CategorizationRule r where r.userId = :userId and r.enabled = true")
    List<CategorizationRule> findEnabledOfUser(UUID userId);

    long countByUserId(UUID userId);

    /** A mesma condição (tipo de comparação, padrão e tipo da transação) não se repete para o usuário. */
    @Query("select count(r) > 0 from CategorizationRule r where r.userId = :userId and r.matchType = :match "
            + "and r.pattern = :pattern and ((:type is null and r.transactionType is null) or r.transactionType = :type) "
            + "and (:ignoreId is null or r.id <> :ignoreId)")
    boolean conditionExists(UUID userId, MatchType match, String pattern, TransactionType type, UUID ignoreId);
}
