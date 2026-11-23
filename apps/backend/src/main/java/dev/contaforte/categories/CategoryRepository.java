package dev.contaforte.categories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<TransactionCategory, UUID> {

    /** Padrão do sistema + as do usuário. */
    @Query("select c from TransactionCategory c where c.userId is null or c.userId = :userId order by c.name")
    List<TransactionCategory> findVisibleTo(UUID userId);

    /** Uma categoria só é acessível se for padrão ou do próprio usuário; do contrário vem vazia (404). */
    @Query("select c from TransactionCategory c where c.id = :id and (c.userId is null or c.userId = :userId)")
    Optional<TransactionCategory> findVisibleById(UUID id, UUID userId);

    Optional<TransactionCategory> findByCodeAndUserIdIsNull(String code);
}
