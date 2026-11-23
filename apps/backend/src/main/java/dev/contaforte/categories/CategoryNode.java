package dev.contaforte.categories;

import java.util.List;
import java.util.UUID;

/**
 * Categoria na visão da API. {@code system} indica categoria padrão (somente leitura); {@code code} só existe nas
 * padrão. As subcategorias vêm em {@code children} na listagem em árvore.
 */
public record CategoryNode(UUID id, UUID parentId, String code, String name, boolean system,
                           List<CategoryNode> children) {

    static CategoryNode of(TransactionCategory c, List<CategoryNode> children) {
        return new CategoryNode(c.getId(), c.getParentId(), c.getCode(), c.getName(), c.isDefault(), children);
    }
}
