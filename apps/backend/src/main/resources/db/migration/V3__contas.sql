-- V3: contas e carteiras (Milestone 2). Conta e carteira são a mesma tabela; o tipo define a liquidez (ADR-013).
-- O saldo NÃO é armazenado: é opening_balance + efeito das transações.

CREATE TABLE accounts (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            varchar(80)   NOT NULL,
    type            varchar(30)   NOT NULL
                    CONSTRAINT ck_accounts_type CHECK (type IN (
                        'CHECKING_ACCOUNT', 'SAVINGS_ACCOUNT', 'CASH', 'CREDIT_CARD',
                        'VA', 'VR', 'INVESTMENT_ACCOUNT', 'OTHER')),
    currency        varchar(3)    NOT NULL DEFAULT 'BRL'
                    CONSTRAINT ck_accounts_currency CHECK (currency = 'BRL'),   -- MVP só em reais
    opening_balance numeric(14,2) NOT NULL DEFAULT 0,
    archived_at     timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    -- permite que transações referenciem (id, user_id) e o banco garanta que conta e transação têm o mesmo dono
    CONSTRAINT uk_accounts_id_user UNIQUE (id, user_id)
);

-- nome único por usuário entre as contas ativas (sem diferenciar maiúsculas); arquivada libera o nome
CREATE UNIQUE INDEX uk_accounts_user_name_active ON accounts (user_id, lower(name)) WHERE archived_at IS NULL;
CREATE INDEX ix_accounts_user ON accounts (user_id);
