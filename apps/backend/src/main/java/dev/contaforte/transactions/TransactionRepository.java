package dev.contaforte.transactions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /** Escopada ao dono e ignorando as excluídas logicamente (a API responde 404 nos dois casos). */
    Optional<Transaction> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}
