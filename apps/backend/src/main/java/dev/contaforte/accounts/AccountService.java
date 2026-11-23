package dev.contaforte.accounts;

import dev.contaforte.audit.AuditService;
import dev.contaforte.common.ApiException;
import dev.contaforte.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras de contas e carteiras. Toda consulta é escopada ao usuário do token: conta de outro usuário é
 * indistinguível de conta inexistente (404). A auditoria registra só o evento e o tipo, nunca nome nem valores.
 */
@Service
class AccountService {

    private static final String DUPLICATE_NAME = "Já existe uma conta ativa com esse nome.";

    private final AccountRepository accounts;
    private final AccountBalances balances;
    private final AuditService audit;

    AccountService(AccountRepository accounts, AccountBalances balances, AuditService audit) {
        this.accounts = accounts;
        this.balances = balances;
        this.audit = audit;
    }

    @Transactional
    AccountResponse create(UUID userId, String name, AccountType type, BigDecimal openingBalance) {
        BigDecimal opening = openingBalance == null ? BigDecimal.ZERO.setScale(2)
                : Money.parse(openingBalance, "openingBalance");
        Account saved = saveOrConflict(new Account(userId, cleanName(name), type, opening));
        audit.record(userId, "ACCOUNT_CREATED", "account:" + saved.getId(), Map.of("type", type.name()));
        return AccountResponse.of(saved, opening);
    }

    @Transactional(readOnly = true)
    List<AccountResponse> list(UUID userId, boolean includeArchived) {
        Map<UUID, BigDecimal> effects = balances.effectsByAccount(userId);
        List<AccountResponse> result = new ArrayList<>();
        for (Account a : accounts.findByUserIdOrderByNameAsc(userId)) {
            if (includeArchived || !a.isArchived()) {
                result.add(AccountResponse.of(a, balanceOf(a, effects)));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    AccountResponse get(UUID userId, UUID id) {
        Account a = find(userId, id);
        return AccountResponse.of(a, balanceOf(a, balances.effectsByAccount(userId)));
    }

    /** Campos nulos não mudam. O tipo nunca muda e o saldo de abertura só enquanto não houver transações. */
    @Transactional
    AccountResponse update(UUID userId, UUID id, String name, AccountType type, BigDecimal openingBalance,
                           Boolean archived) {
        Account a = find(userId, id);
        if (type != null && type != a.getType()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O tipo da conta não pode ser alterado.");
        }
        List<String> changed = new ArrayList<>();
        if (name != null && !cleanName(name).equals(a.getName())) {
            a.setName(cleanName(name));
            changed.add("name");
        }
        if (openingBalance != null) {
            BigDecimal novo = Money.parse(openingBalance, "openingBalance");
            if (novo.compareTo(a.getOpeningBalance()) != 0) {
                if (balances.hasTransactions(id)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "O saldo inicial só pode ser alterado enquanto a conta não tiver transações.");
                }
                a.setOpeningBalance(novo);
                changed.add("openingBalance");
            }
        }
        String archiveEvent = null;
        if (archived != null && archived != a.isArchived()) {
            a.setArchivedAt(archived ? Instant.now() : null);
            archiveEvent = archived ? "ACCOUNT_ARCHIVED" : "ACCOUNT_UNARCHIVED";
        }
        Account saved = changed.isEmpty() && archiveEvent == null ? a : saveOrConflict(a);
        if (!changed.isEmpty()) {
            audit.record(userId, "ACCOUNT_UPDATED", "account:" + id, Map.of("fields", changed));
        }
        if (archiveEvent != null) {
            audit.record(userId, archiveEvent, "account:" + id, Map.of());
        }
        return AccountResponse.of(saved, balanceOf(saved, balances.effectsByAccount(userId)));
    }

    private Account find(UUID userId, UUID id) {
        return accounts.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conta não encontrada."));
    }

    private Account saveOrConflict(Account a) {
        try {
            return accounts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // índice único de nome entre as ativas (cobre também a corrida entre duas requisições)
            throw new ApiException(HttpStatus.CONFLICT, DUPLICATE_NAME);
        }
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O nome deve ter de 1 a 80 caracteres.");
        }
        return n;
    }

    private static BigDecimal balanceOf(Account a, Map<UUID, BigDecimal> effects) {
        return AccountBalances.balanceOf(a, effects);
    }
}
