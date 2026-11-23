# ADR-014 — Categorias em dois níveis e contextos próprios

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
O [ADR-009](009-categorias-separadas-de-contexto.md) separa categoria financeira de contexto pessoal. Falta definir como são armazenadas, quais vêm prontas e o que o usuário pode alterar.

## Decisão
- **Categoria em 2 níveis** (categoria → subcategoria) na tabela `transaction_categories`. Uma transação aponta para uma única categoria, de qualquer nível. A regra dos 2 níveis é imposta por gatilho no banco.
- **Contextos** em `transaction_contexts`, com o mesmo modelo de posse.
- **Padrão do sistema:** linhas com `user_id` nulo e `code` estável (ex.: `FOOD_RESTAURANT`), criadas pela migração, em pt-BR. São somente leitura para o usuário na API. Hoje: 12 categorias, 39 subcategorias e 6 contextos (Individual, Família, Relacionamento, Pet, Trabalho, Outro).
- **Do usuário:** linhas com `user_id` preenchido e sem `code`. O usuário pode criar subcategorias sob categorias padrão ou próprias, mas nunca sob a de outro usuário.
- **Nome único** dentro do mesmo dono e do mesmo pai, sem diferenciar maiúsculas.
- **Acesso:** categoria ou contexto de outro usuário é inacessível (a API responde 404 e o banco recusa a referência).

## Alternativas descartadas
- **Árvore de profundidade livre:** complica telas e relatórios sem ganho no MVP.
- **Contexto como categoria:** explodiria o número de categorias (Alimentação × Família × Pet...). O ADR-009 já havia descartado.
- **Tabela de categorias por usuário copiada no cadastro:** impediria melhorar o conjunto padrão depois.

## Consequências
- Mudar o conjunto padrão é uma nova migração (os `code` são estáveis, os nomes podem evoluir).
- Os `code` ficam disponíveis para os milestones de importação e classificação mapearem regras sem depender do nome exibido.
