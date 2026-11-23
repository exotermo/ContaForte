# Modelo de dados e API (Milestones 2 e 3)

Fonte da verdade: as migrações em `apps/backend/src/main/resources/db/migration` (V1–V6). Este documento é o mapa; em caso de divergência, vale a migração. Decisões em [ADR-013](../02_Arquitetura/Arquitetura/ADR/013-saldo-derivado-e-classes-de-liquidez.md) e [ADR-014](../02_Arquitetura/Arquitetura/ADR/014-categorias-em-dois-niveis-e-contextos.md).

## Diagrama

```mermaid
erDiagram
    users ||--o{ accounts : possui
    users ||--o{ transactions : registra
    users |o--o{ transaction_categories : "cria (user_id nulo = padrão)"
    users |o--o{ transaction_contexts : "cria (user_id nulo = padrão)"
    accounts ||--o{ transactions : "origem (account_id)"
    accounts |o--o{ transactions : "destino (counterpart_account_id)"
    transaction_categories |o--o{ transaction_categories : "subcategoria (parent_id)"
    transaction_categories |o--o{ transactions : classifica
    transaction_contexts |o--o{ transactions : contextualiza
    accounts ||--o{ imports : "recebe extratos"
    imports ||--o{ import_items : "linhas da prévia"
    imports |o--o{ transactions : "origem (import_id)"

    accounts {
        uuid id PK
        uuid user_id FK
        varchar name "único entre as ativas"
        varchar type "8 tipos"
        numeric opening_balance "14,2"
        timestamptz archived_at
    }
    transactions {
        uuid id PK
        uuid user_id FK
        uuid account_id FK
        uuid counterpart_account_id FK "só TRANSFER e INVESTMENT"
        varchar type "5 tipos"
        numeric amount "14,2, sempre positivo"
        uuid category_id FK
        uuid context_id FK
        timestamptz occurred_at
        varchar source "MANUAL no M2"
        timestamptz deleted_at "exclusão lógica"
    }
    transaction_categories {
        uuid id PK
        uuid user_id FK "nulo = padrão do sistema"
        uuid parent_id FK "2 níveis"
        varchar code "só nas padrão"
        varchar name
    }
    transaction_contexts {
        uuid id PK
        uuid user_id FK "nulo = padrão do sistema"
        varchar code "só nos padrão"
        varchar name
    }
    imports {
        uuid id PK
        uuid user_id FK
        uuid account_id FK
        varchar format "OFX ou CSV"
        varchar status "PREVIEW, CONFIRMED, ROLLED_BACK"
        varchar file_sha256 "o arquivo NÃO é guardado"
        int total_rows "novas + duplicadas + possíveis + inválidas"
        timestamptz expires_at "só a prévia vence (24 h)"
    }
    import_items {
        uuid id PK
        uuid import_id FK
        int line_no
        varchar status "NEW, DUPLICATE, POSSIBLE_DUPLICATE, INVALID"
        numeric amount "14,2, positivo"
        varchar external_id "FITID"
        uuid duplicate_of FK
        boolean selected "só NEW e POSSIBLE"
    }
```

`transactions` ganha `import_id` e `external_id` (V6, M3). Decisões em [ADR-015](../02_Arquitetura/Arquitetura/ADR/015-importacao-em-duas-fases-sem-guardar-o-arquivo.md) e [ADR-016](../02_Arquitetura/Arquitetura/ADR/016-deduplicacao-em-camadas-com-contagem-de-ocorrencias.md).

## Regras que o banco impõe (além da API)

| Regra | Como |
|---|---|
| Valor sempre positivo, moeda BRL, tipos e origem válidos | `CHECK` em `accounts` e `transactions` |
| Conta e destino são do **mesmo usuário** da transação | chave estrangeira composta `(conta, user_id)` → `accounts (id, user_id)` |
| Só TRANSFER e INVESTMENT têm destino, e ele difere da origem | `CHECK ck_transactions_counterpart` |
| Categoria e contexto são padrão ou do mesmo dono | gatilho `check_transaction_refs` |
| Categorias em no máximo 2 níveis, pai padrão ou do mesmo dono | gatilho `check_category_hierarchy` |
| Nome único entre as contas ativas; único por pai nas categorias | índices únicos parciais/por escopo |
| Apagar o usuário apaga tudo dele (LGPD), menos os padrões | `ON DELETE CASCADE` a partir de `users` |
| A importação é da conta e do dono; a transação importada só aponta para importação **da própria conta** | FK composta `(conta, user_id)` em `imports` e `(import_id, account_id)` em `transactions` |
| Contadores da importação somam o total; datas seguem o status | `CHECK ck_imports_counts` e `ck_imports_lifecycle` |
| Só NOVA e POSSÍVEL duplicata podem ser importadas; linha válida tem data, tipo e valor positivo | `CHECK` em `import_items` |
| O identificador externo não se repete na conta entre as transações ativas (o rollback o libera) | índice único parcial `uk_transactions_external` |
| Importação confirmada não pode ser apagada, só desfeita | FK de `transactions.import_id` (sem `CASCADE`) |

O `NUMERIC(14,2)` do Postgres **arredonda em silêncio** valores com mais de 2 casas; por isso a API recusa (422) antes de gravar.

## Liquidez (o que entra em cada total)

| Tipo de conta | Total |
|---|---|
| CHECKING_ACCOUNT, SAVINGS_ACCOUNT, CASH | `availableCash` |
| VA, VR | `restrictedBalance` (nunca é caixa disponível) |
| INVESTMENT_ACCOUNT | `investedAssets` |
| CREDIT_CARD | `creditCardBalance` (dívida, negativo) |
| OTHER | `otherBalance` |

`totalNetWorth` é a soma de todos. O saldo de uma conta é `opening_balance` + efeito das transações (INCOME e REFUND somam; EXPENSE subtrai; TRANSFER e INVESTMENT subtraem da origem e somam no destino). Só contas ativas entram nos totais.

## Endpoints (`/api/v1`)

Todos exigem `Authorization: Bearer`, exceto login, refresh, logout, `system/info` e `openapi`. A documentação interativa (OpenAPI) está em `/api/v1/openapi`. Dinheiro sai e entra como string decimal (`"1234.50"`). Recurso de outro usuário responde 404, igual a inexistente.

| Área | Rotas | Papéis |
|---|---|---|
| Autenticação | `POST /auth/login`, `/auth/refresh`, `/auth/logout` | público |
| Perfil | `GET`, `PATCH /users/me` | qualquer autenticado |
| Administração | `GET`, `POST /admin/users` (cria só USER) | ADMIN |
| Contas | `POST`, `GET`, `GET /{id}`, `PATCH /{id}` em `/accounts` | USER, ADMIN |
| Categorias | `GET` (árvore), `POST`, `PATCH /{id}` em `/categories` | USER, ADMIN |
| Contextos | `GET`, `POST`, `PATCH /{id}` em `/contexts` | USER, ADMIN |
| Transações | `POST`, `GET` (filtros e paginação), `GET /{id}`, `PATCH /{id}`, `DELETE /{id}` em `/transactions` | USER, ADMIN |
| Resumo | `GET /summary`, `GET /summary/monthly?month=AAAA-MM` | USER, ADMIN |

Erros seguem ProblemDetail (RFC 7807): 400 validação, 401 sem sessão, 403 sem permissão ou padrão do sistema, 404 inexistente ou de outro usuário, 409 conflito (nome duplicado, conta arquivada), 422 regra de negócio (valor com mais de 2 casas, data no futuro, tipo e contas inalteráveis).
