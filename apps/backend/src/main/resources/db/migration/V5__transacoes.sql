-- V5: transações (Milestone 2). amount é sempre positivo; o tipo define o sinal (ADR-013).
-- TRANSFER e INVESTMENT são uma linha só, com conta de origem e de destino (counterpart).

CREATE TABLE transactions (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    account_id             uuid          NOT NULL,
    counterpart_account_id uuid,
    type                   varchar(20)   NOT NULL
                           CONSTRAINT ck_transactions_type CHECK (type IN (
                               'INCOME', 'EXPENSE', 'TRANSFER', 'INVESTMENT', 'REFUND')),
    amount                 numeric(14,2) NOT NULL
                           CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    currency               varchar(3)    NOT NULL DEFAULT 'BRL'
                           CONSTRAINT ck_transactions_currency CHECK (currency = 'BRL'),
    description            varchar(200),
    merchant               varchar(120),
    category_id            uuid          REFERENCES transaction_categories (id),
    context_id             uuid          REFERENCES transaction_contexts (id),
    occurred_at            timestamptz   NOT NULL,
    source                 varchar(20)   NOT NULL DEFAULT 'MANUAL'
                           CONSTRAINT ck_transactions_source CHECK (source IN (
                               'MANUAL', 'PIX', 'OPEN_FINANCE', 'OFX', 'CSV', 'SYSTEM')),
    deleted_at             timestamptz,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),

    -- conta e contraparte precisam pertencer ao MESMO usuário da transação (FK composta)
    CONSTRAINT fk_transactions_account
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_transactions_counterpart
        FOREIGN KEY (counterpart_account_id, user_id) REFERENCES accounts (id, user_id),

    -- só TRANSFER e INVESTMENT têm contraparte, e ela difere da conta de origem
    CONSTRAINT ck_transactions_counterpart CHECK (
        (type IN ('TRANSFER', 'INVESTMENT')) = (counterpart_account_id IS NOT NULL)
        AND counterpart_account_id IS DISTINCT FROM account_id)
);

CREATE INDEX ix_transactions_user_occurred ON transactions (user_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_transactions_account ON transactions (account_id);
CREATE INDEX ix_transactions_counterpart ON transactions (counterpart_account_id)
    WHERE counterpart_account_id IS NOT NULL;
CREATE INDEX ix_transactions_category ON transactions (category_id);
CREATE INDEX ix_transactions_context ON transactions (context_id);

-- Categoria e contexto precisam ser padrão do sistema ou do mesmo dono da transação.
CREATE FUNCTION check_transaction_refs() RETURNS trigger AS $$
BEGIN
    IF NEW.category_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_categories c
        WHERE c.id = NEW.category_id AND c.user_id IS NOT NULL AND c.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'categoria pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    IF NEW.context_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_contexts x
        WHERE x.id = NEW.context_id AND x.user_id IS NOT NULL AND x.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'contexto pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transaction_refs
    BEFORE INSERT OR UPDATE OF category_id, context_id, user_id ON transactions
    FOR EACH ROW EXECUTE FUNCTION check_transaction_refs();
