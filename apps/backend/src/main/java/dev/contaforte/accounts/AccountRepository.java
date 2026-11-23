package dev.contaforte.accounts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserIdOrderByNameAsc(UUID userId);

    /** Busca já escopada ao dono: conta de outro usuário vem vazia (a API responde 404, nunca 403). */
    Optional<Account> findByIdAndUserId(UUID id, UUID userId);
}
