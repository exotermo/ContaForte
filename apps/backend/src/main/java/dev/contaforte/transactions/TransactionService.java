package dev.contaforte.transactions;

import dev.contaforte.accounts.Account;
import dev.contaforte.accounts.AccountRepository;
import dev.contaforte.accounts.AccountType;
import dev.contaforte.audit.AuditService;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.categories.TransactionCategory;
import dev.contaforte.common.ApiException;
import dev.contaforte.common.AppTime;
import dev.contaforte.common.Money;
import dev.contaforte.common.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras das transações manuais (ADR-013). Tudo é escopado ao usuário do token: recurso de outro usuário é
 * indistinguível de inexistente (404). Descrição e valor são dados pessoais: ficam fora de auditoria, eventos e
 * métricas, que registram só ids e o tipo.
 */
@Service
class TransactionService {

    /** Lançamentos podem ser de "hoje" em qualquer fuso, mas não do futuro distante. */
    private static final long MAX_FUTURE_DAYS = 1;
    private static final Instant EARLIEST = LocalDate.of(2000, 1, 1).atStartOfDay(AppTime.ZONE).toInstant();
    private static final Sort ORDER = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final ContextRepository contexts;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final MeterRegistry metrics;
    private final Clock clock;

    TransactionService(TransactionRepository transactions, AccountRepository accounts, CategoryRepository categories,
                       ContextRepository contexts, AuditService audit, ApplicationEventPublisher events,
                       MeterRegistry metrics, Clock clock) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.contexts = contexts;
        this.audit = audit;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        // Registra o contador de cada tipo em 0 já na partida. Um contador que nasce na primeira transação já chega ao
        // Prometheus com valor > 0 e sem amostra anterior, então rate()/increase() perderiam essa primeira contagem.
        for (TransactionType type : TransactionType.values()) {
            metrics.counter("contaforte.transactions.recorded", "type", type.name());
        }
    }

    // ---------------------------------------------------------------- criação

    @Transactional
    TransactionResponse create(UUID userId, TransactionController.CreateTransactionRequest in) {
        BigDecimal amount = Money.parsePositive(in.amount(), "amount");
        Instant occurredAt = validOccurredAt(in.occurredAt());
        Account origin = activeAccount(userId, in.accountId(), "Conta");
        UUID counterpartId = validateCounterpart(userId, in.type(), origin, in.counterpartAccountId());
        UUID categoryId = visibleCategory(userId, in.categoryId());
        UUID contextId = visibleContext(userId, in.contextId());

        Transaction t = new Transaction(userId, origin.getId(), in.type(), amount, occurredAt);
        t.setCounterpartAccountId(counterpartId);
        t.setDescription(blankToNull(in.description()));
        t.setMerchant(blankToNull(in.merchant()));
        t.setCategoryId(categoryId);
        t.setContextId(contextId);
        Transaction saved = transactions.saveAndFlush(t);

        audit.record(userId, "TRANSACTION_CREATED", "transaction:" + saved.getId(), Map.of("type", in.type().name()));
        events.publishEvent(new TransactionRecorded(saved.getId(), userId, origin.getId(), in.type()));
        metrics.counter("contaforte.transactions.recorded", "type", in.type().name()).increment();
        return TransactionResponse.of(saved);
    }

    // ---------------------------------------------------------------- leitura

    @Transactional(readOnly = true)
    TransactionResponse get(UUID userId, UUID id) {
        return TransactionResponse.of(find(userId, id));
    }

    @Transactional(readOnly = true)
    PageResponse<TransactionResponse> list(UUID userId, TransactionController.Filter f, int page, int size) {
        Specification<Transaction> spec = TransactionSpecs.ownedAndActive(userId);
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O período inicial não pode ser depois do final.");
        }
        if (f.from() != null) {
            spec = spec.and(TransactionSpecs.occurredFrom(f.from().atStartOfDay(AppTime.ZONE).toInstant()));
        }
        if (f.to() != null) {   // o dia final é inclusivo: vale até o início do dia seguinte
            spec = spec.and(TransactionSpecs.occurredBefore(f.to().plusDays(1).atStartOfDay(AppTime.ZONE).toInstant()));
        }
        if (f.accountId() != null) spec = spec.and(TransactionSpecs.touchesAccount(f.accountId()));
        if (f.type() != null) spec = spec.and(TransactionSpecs.ofType(f.type()));
        if (f.contextId() != null) spec = spec.and(TransactionSpecs.inContext(f.contextId()));
        if (f.categoryId() != null) spec = spec.and(TransactionSpecs.inCategories(categoryAndChildren(userId, f.categoryId())));
        if (f.q() != null && !f.q().isBlank()) spec = spec.and(TransactionSpecs.textContains(f.q().trim()));

        return PageResponse.of(transactions.findAll(spec, PageRequest.of(page, size, ORDER)).map(TransactionResponse::of));
    }

    // ---------------------------------------------------------------- edição e exclusão

    /** Edita valor, data, descrição, estabelecimento, categoria e contexto. Tipo e contas não mudam. */
    @Transactional
    TransactionResponse update(UUID userId, UUID id, TransactionController.UpdateTransactionRequest in) {
        Transaction t = find(userId, id);
        if ((in.type() != null && in.type() != t.getType())
                || (in.accountId() != null && !in.accountId().equals(t.getAccountId()))
                || (in.counterpartAccountId() != null && !in.counterpartAccountId().equals(t.getCounterpartAccountId()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Tipo e contas da transação não podem ser alterados: exclua e lance novamente.");
        }
        List<String> changed = new ArrayList<>();
        if (in.amount() != null) {
            BigDecimal amount = Money.parsePositive(in.amount(), "amount");
            if (amount.compareTo(t.getAmount()) != 0) {
                t.setAmount(amount);
                changed.add("amount");
            }
        }
        if (in.occurredAt() != null) {
            Instant when = validOccurredAt(in.occurredAt());
            if (!when.equals(t.getOccurredAt())) {
                t.setOccurredAt(when);
                changed.add("occurredAt");
            }
        }
        if (in.has("description")) {
            t.setDescription(blankToNull(in.description()));
            changed.add("description");
        }
        if (in.has("merchant")) {
            t.setMerchant(blankToNull(in.merchant()));
            changed.add("merchant");
        }
        if (in.has("categoryId")) {
            t.setCategoryId(visibleCategory(userId, in.categoryId()));   // null limpa a categoria
            changed.add("categoryId");
        }
        if (in.has("contextId")) {
            t.setContextId(visibleContext(userId, in.contextId()));
            changed.add("contextId");
        }
        Transaction saved = changed.isEmpty() ? t : transactions.saveAndFlush(t);
        if (!changed.isEmpty()) {
            audit.record(userId, "TRANSACTION_UPDATED", "transaction:" + id, Map.of("fields", changed));
        }
        return TransactionResponse.of(saved);
    }

    /** Exclusão lógica: a linha fica no banco (rollback de importações no M3) mas some de listas e saldos. */
    @Transactional
    void delete(UUID userId, UUID id) {
        Transaction t = find(userId, id);
        t.setDeletedAt(Instant.now(clock));
        transactions.saveAndFlush(t);
        audit.record(userId, "TRANSACTION_DELETED", "transaction:" + id, Map.of());
    }

    // ---------------------------------------------------------------- validações

    private Transaction find(UUID userId, UUID id) {
        return transactions.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transação não encontrada."));
    }

    private Instant validOccurredAt(Instant occurredAt) {
        Instant now = Instant.now(clock);
        Instant when = occurredAt == null ? now : occurredAt;
        if (when.isAfter(now.plus(MAX_FUTURE_DAYS, ChronoUnit.DAYS))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A data não pode estar no futuro.");
        }
        if (when.isBefore(EARLIEST)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A data é anterior ao limite permitido (2000).");
        }
        return when;
    }

    private Account activeAccount(UUID userId, UUID accountId, String label) {
        Account a = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, label + " não encontrada."));
        if (a.isArchived()) {
            throw new ApiException(HttpStatus.CONFLICT, label + " arquivada não recebe transações.");
        }
        return a;
    }

    /** TRANSFER e INVESTMENT exigem uma conta de destino diferente; os demais tipos não aceitam destino. */
    private UUID validateCounterpart(UUID userId, TransactionType type, Account origin, UUID counterpartId) {
        if (!type.hasCounterpart()) {
            if (counterpartId != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Só transferências e investimentos aceitam conta de destino.");
            }
            return null;
        }
        if (counterpartId == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe a conta de destino.");
        }
        if (counterpartId.equals(origin.getId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A conta de destino deve ser diferente da de origem.");
        }
        Account dest = activeAccount(userId, counterpartId, "Conta de destino");
        if (type == TransactionType.INVESTMENT && dest.getType() != AccountType.INVESTMENT_ACCOUNT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Um investimento precisa ter como destino uma conta de investimentos.");
        }
        return dest.getId();
    }

    private UUID visibleCategory(UUID userId, UUID categoryId) {
        if (categoryId == null) return null;
        return categories.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria não encontrada.")).getId();
    }

    private UUID visibleContext(UUID userId, UUID contextId) {
        if (contextId == null) return null;
        return contexts.findVisibleById(contextId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contexto não encontrado.")).getId();
    }

    /** Filtrar por uma categoria-pai inclui as suas subcategorias. */
    private Set<UUID> categoryAndChildren(UUID userId, UUID categoryId) {
        Set<UUID> ids = new java.util.HashSet<>();
        ids.add(categoryId);
        for (TransactionCategory c : categories.findVisibleTo(userId)) {
            if (categoryId.equals(c.getParentId())) ids.add(c.getId());
        }
        return ids;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
