package dev.contaforte.categories;

import java.util.UUID;

/** Contexto na visão da API. {@code system} indica contexto padrão (somente leitura). */
public record ContextResponse(UUID id, String code, String name, boolean system) {

    static ContextResponse of(TransactionContext c) {
        return new ContextResponse(c.getId(), c.getCode(), c.getName(), c.isDefault());
    }
}
