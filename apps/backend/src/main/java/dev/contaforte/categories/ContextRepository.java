package dev.contaforte.categories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContextRepository extends JpaRepository<TransactionContext, UUID> {

    @Query("select c from TransactionContext c where c.userId is null or c.userId = :userId order by c.name")
    List<TransactionContext> findVisibleTo(UUID userId);

    @Query("select c from TransactionContext c where c.id = :id and (c.userId is null or c.userId = :userId)")
    Optional<TransactionContext> findVisibleById(UUID id, UUID userId);

    Optional<TransactionContext> findByCodeAndUserIdIsNull(String code);
}
