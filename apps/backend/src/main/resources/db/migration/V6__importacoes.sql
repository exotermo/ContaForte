-- V6: importação de extratos OFX e CSV (Milestone 3; ADR-015 e ADR-016).
-- O arquivo enviado NÃO é guardado: só o hash, o nome e as linhas já normalizadas da prévia.

CREATE TABLE imports (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id       uuid         NOT NULL,
    format           varchar(10)  NOT NULL
                     CONSTRAINT ck_imports_format CHECK (format IN ('OFX', 'CSV')),
    status           varchar(20)  NOT NULL DEFAULT 'PREVIEW'
                     CONSTRAINT ck_imports_status CHECK (status IN ('PREVIEW', 'CONFIRMED', 'ROLLED_BACK')),
    file_name        varchar(255) NOT NULL,
    file_sha256      varchar(64)  NOT NULL,
    file_size        integer      NOT NULL CONSTRAINT ck_imports_size CHECK (file_size >= 0),
    total_rows       integer      NOT NULL DEFAULT 0,
    new_rows         integer      NOT NULL DEFAULT 0,
    duplicate_rows   integer      NOT NULL DEFAULT 0,
    possible_rows    integer      NOT NULL DEFAULT 0,
    invalid_rows     integer      NOT NULL DEFAULT 0,
    imported_rows    integer      NOT NULL DEFAULT 0,
    mapping          jsonb,                       -- mapeamento de colunas do CSV (sem dados do extrato)
    expires_at       timestamptz,                 -- só a prévia vence
    confirmed_at     timestamptz,
    rolled_back_at   timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),

    -- a conta é do mesmo usuário da importação (FK composta, como nas transações)
    CONSTRAINT fk_imports_account FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    -- permitem que a transação referencie (import_id, account_id): garante mesma conta E mesmo dono
    CONSTRAINT uk_imports_id_user UNIQUE (id, user_id),
    CONSTRAINT uk_imports_id_account UNIQUE (id, account_id),

    CONSTRAINT ck_imports_counts CHECK (
        total_rows >= 0 AND new_rows >= 0 AND duplicate_rows >= 0 AND possible_rows >= 0
        AND invalid_rows >= 0 AND imported_rows >= 0
        AND new_rows + duplicate_rows + possible_rows + invalid_rows = total_rows
        AND imported_rows <= new_rows + possible_rows),
    CONSTRAINT ck_imports_lifecycle CHECK (
        (status <> 'PREVIEW' OR expires_at IS NOT NULL)
        AND (status = 'PREVIEW' OR confirmed_at IS NOT NULL)
        AND ((status = 'ROLLED_BACK') = (rolled_back_at IS NOT NULL)))
);

CREATE INDEX ix_imports_user_created ON imports (user_id, created_at DESC);
CREATE INDEX ix_imports_account ON imports (account_id);
-- "este arquivo já foi importado nesta conta?"
CREATE INDEX ix_imports_file_hash ON imports (account_id, file_sha256) WHERE status = 'CONFIRMED';
-- limpeza das prévias vencidas
CREATE INDEX ix_imports_preview_expiry ON imports (expires_at) WHERE status = 'PREVIEW';

CREATE TABLE import_items (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id         uuid         NOT NULL REFERENCES imports (id) ON DELETE CASCADE,
    line_no           integer      NOT NULL CONSTRAINT ck_import_items_line CHECK (line_no > 0),
    status            varchar(20)  NOT NULL
                      CONSTRAINT ck_import_items_status CHECK (status IN ('NEW', 'DUPLICATE', 'POSSIBLE_DUPLICATE', 'INVALID')),
    occurred_at       timestamptz,
    type              varchar(20)  CONSTRAINT ck_import_items_type CHECK (type IN ('INCOME', 'EXPENSE')),
    amount            numeric(14,2),               -- sempre positivo; o tipo dá o sinal (igual às transações)
    description       varchar(200),
    merchant          varchar(120),
    normalized_text   varchar(400),                -- chave da deduplicação (sem acento, caixa e pontuação)
    external_id       varchar(255),                -- FITID do OFX ou coluna de id do CSV
    duplicate_of      uuid         REFERENCES transactions (id) ON DELETE SET NULL,
    reason            varchar(200),                -- por que é inválida (ou o motivo da duplicidade)
    selected          boolean      NOT NULL DEFAULT false,

    CONSTRAINT uk_import_items_line UNIQUE (import_id, line_no),
    -- linha válida tem data, tipo e valor positivo; inválida precisa de motivo
    CONSTRAINT ck_import_items_fields CHECK (
        status = 'INVALID'
        OR (occurred_at IS NOT NULL AND type IS NOT NULL AND amount IS NOT NULL AND amount > 0)),
    CONSTRAINT ck_import_items_invalid_reason CHECK (status <> 'INVALID' OR reason IS NOT NULL),
    -- só NOVA e POSSÍVEL podem ser importadas; duplicada e inválida nunca
    CONSTRAINT ck_import_items_selected CHECK (NOT selected OR status IN ('NEW', 'POSSIBLE_DUPLICATE'))
);

CREATE INDEX ix_import_items_import_status ON import_items (import_id, status);

-- Transações importadas: de qual importação vieram e o identificador no extrato (FITID etc.)
ALTER TABLE transactions
    ADD COLUMN import_id    uuid,
    ADD COLUMN external_id  varchar(255);

-- a importação é da MESMA conta e do mesmo dono da transação (import_id nulo = lançamento fora de importação)
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_import
        FOREIGN KEY (import_id, account_id) REFERENCES imports (id, account_id);

-- só as origens de arquivo têm importação
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_import_source CHECK (import_id IS NULL OR source IN ('OFX', 'CSV'));

-- o mesmo identificador externo não entra duas vezes na mesma conta (entre as ativas: o rollback o libera)
CREATE UNIQUE INDEX uk_transactions_external ON transactions (account_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_transactions_import ON transactions (import_id) WHERE import_id IS NOT NULL;
