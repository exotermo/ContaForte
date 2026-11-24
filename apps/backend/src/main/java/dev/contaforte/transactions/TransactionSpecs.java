package dev.contaforte.transactions;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Filtros dinâmicos da listagem. Tudo é combinado com E; o escopo do dono e a exclusão lógica são sempre aplicados. */
final class TransactionSpecs {

    private TransactionSpecs() {
    }

    static Specification<Transaction> ownedAndActive(UUID userId) {
        return (root, q, cb) -> cb.and(cb.equal(root.get("userId"), userId), cb.isNull(root.get("deletedAt")));
    }

    static Specification<Transaction> occurredFrom(Instant from) {
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
    }

    static Specification<Transaction> occurredBefore(Instant before) {
        return (root, q, cb) -> cb.lessThan(root.get("occurredAt"), before);
    }

    /** Transações que tocam a conta, como origem ou como destino. */
    static Specification<Transaction> touchesAccount(UUID accountId) {
        return (root, q, cb) -> cb.or(cb.equal(root.get("accountId"), accountId),
                cb.equal(root.get("counterpartAccountId"), accountId));
    }

    static Specification<Transaction> ofType(TransactionType type) {
        return (root, q, cb) -> cb.equal(root.get("type"), type);
    }

    static Specification<Transaction> inCategories(Collection<UUID> categoryIds) {
        return (root, q, cb) -> root.get("categoryId").in(categoryIds);
    }

    static Specification<Transaction> fromImport(UUID importId) {
        return (root, q, cb) -> cb.equal(root.get("importId"), importId);
    }

    static Specification<Transaction> inContext(UUID contextId) {
        return (root, q, cb) -> cb.equal(root.get("contextId"), contextId);
    }

    /** Busca por trecho na descrição ou no estabelecimento, sem diferenciar maiúsculas; %, _ e \ valem como texto. */
    static Specification<Transaction> textContains(String text) {
        String escaped = text.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("description")), pattern, '\\'),
                cb.like(cb.lower(root.get("merchant")), pattern, '\\'));
    }
}
