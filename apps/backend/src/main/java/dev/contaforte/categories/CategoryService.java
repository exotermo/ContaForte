package dev.contaforte.categories;

import dev.contaforte.audit.AuditService;
import dev.contaforte.common.ApiException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorias (2 níveis) e contextos. As padrão do sistema são somente leitura (403); as de outro usuário são
 * invisíveis (404). O nome é único entre tudo que o usuário enxerga (padrão + próprias) no mesmo nível, para a
 * tela nunca mostrar duas "Alimentação". A auditoria registra o evento e o id, nunca o nome.
 */
@Service
class CategoryService {

    // Collator ordena com acentos no lugar certo (Água, Alimentação...); é seguro entre threads
    private static final Collator COLLATOR = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
    private static final Comparator<String> PT_BR = COLLATOR::compare;

    private final CategoryRepository categories;
    private final ContextRepository contexts;
    private final AuditService audit;

    CategoryService(CategoryRepository categories, ContextRepository contexts, AuditService audit) {
        this.categories = categories;
        this.contexts = contexts;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- categorias

    @Transactional(readOnly = true)
    List<CategoryNode> tree(UUID userId) {
        List<TransactionCategory> visible = categories.findVisibleTo(userId);
        Map<UUID, List<TransactionCategory>> childrenByParent = visible.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(TransactionCategory::getParentId));
        return visible.stream()
                .filter(c -> c.getParentId() == null)
                .sorted(byName())
                .map(p -> CategoryNode.of(p, childrenByParent.getOrDefault(p.getId(), List.of()).stream()
                        .sorted(byName())
                        .map(ch -> CategoryNode.of(ch, List.of()))
                        .toList()))
                .toList();
    }

    @Transactional
    CategoryNode createCategory(UUID userId, String name, UUID parentId) {
        String clean = cleanName(name);
        if (parentId != null) {
            TransactionCategory parent = categories.findVisibleById(parentId, userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria-pai não encontrada."));
            if (parent.getParentId() != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Categorias admitem no máximo 2 níveis: a categoria-pai já é uma subcategoria.");
            }
        }
        rejectDuplicate(categories.findVisibleTo(userId), parentId, clean, null);
        TransactionCategory saved = saveCategory(new TransactionCategory(userId, parentId, clean));
        audit.record(userId, "CATEGORY_CREATED", "category:" + saved.getId(), Map.of());
        return CategoryNode.of(saved, List.of());
    }

    @Transactional
    CategoryNode renameCategory(UUID userId, UUID id, String name) {
        TransactionCategory c = categories.findVisibleById(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria não encontrada."));
        if (c.isDefault()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Categorias padrão do sistema não podem ser alteradas.");
        }
        String clean = cleanName(name);
        if (!clean.equals(c.getName())) {
            rejectDuplicate(categories.findVisibleTo(userId), c.getParentId(), clean, c.getId());
            c.setName(clean);
            c = saveCategory(c);
            audit.record(userId, "CATEGORY_RENAMED", "category:" + id, Map.of());
        }
        return CategoryNode.of(c, List.of());
    }

    // ---------------------------------------------------------------- contextos

    @Transactional(readOnly = true)
    List<ContextResponse> listContexts(UUID userId) {
        return contexts.findVisibleTo(userId).stream()
                .sorted(Comparator.comparing(TransactionContext::getName, PT_BR))
                .map(ContextResponse::of)
                .toList();
    }

    @Transactional
    ContextResponse createContext(UUID userId, String name) {
        String clean = cleanName(name);
        rejectDuplicateContext(contexts.findVisibleTo(userId), clean, null);
        TransactionContext saved = saveContext(new TransactionContext(userId, clean));
        audit.record(userId, "CONTEXT_CREATED", "context:" + saved.getId(), Map.of());
        return ContextResponse.of(saved);
    }

    @Transactional
    ContextResponse renameContext(UUID userId, UUID id, String name) {
        TransactionContext c = contexts.findVisibleById(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contexto não encontrado."));
        if (c.isDefault()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Contextos padrão do sistema não podem ser alterados.");
        }
        String clean = cleanName(name);
        if (!clean.equals(c.getName())) {
            rejectDuplicateContext(contexts.findVisibleTo(userId), clean, c.getId());
            c.setName(clean);
            c = saveContext(c);
            audit.record(userId, "CONTEXT_RENAMED", "context:" + id, Map.of());
        }
        return ContextResponse.of(c);
    }

    // ---------------------------------------------------------------- apoio

    private static void rejectDuplicate(List<TransactionCategory> visible, UUID parentId, String name, UUID selfId) {
        boolean exists = visible.stream().anyMatch(c ->
                !c.getId().equals(selfId)
                        && java.util.Objects.equals(c.getParentId(), parentId)
                        && c.getName().equalsIgnoreCase(name));
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe uma categoria com esse nome neste nível.");
        }
    }

    private static void rejectDuplicateContext(List<TransactionContext> visible, String name, UUID selfId) {
        boolean exists = visible.stream()
                .anyMatch(c -> !c.getId().equals(selfId) && c.getName().equalsIgnoreCase(name));
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um contexto com esse nome.");
        }
    }

    private TransactionCategory saveCategory(TransactionCategory c) {
        try {
            return categories.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            // índice único (cobre a corrida entre duas requisições com o mesmo nome)
            throw new ApiException(HttpStatus.CONFLICT, "Já existe uma categoria com esse nome neste nível.");
        }
    }

    private TransactionContext saveContext(TransactionContext c) {
        try {
            return contexts.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um contexto com esse nome.");
        }
    }

    private static Comparator<TransactionCategory> byName() {
        return Comparator.comparing(TransactionCategory::getName, PT_BR);
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O nome deve ter de 1 a 80 caracteres.");
        }
        return n;
    }
}
