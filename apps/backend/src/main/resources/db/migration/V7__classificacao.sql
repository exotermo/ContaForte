-- V7: classificação de transações (Milestone 4; ADR-017 e ADR-018).
-- Favorecidos PIX aprendidos, regras do usuário e feedback das correções. Nada aqui altera linhas antigas:
-- as transações existentes ficam com classification_status = 'NONE'.

-- permite chaves estrangeiras compostas (transação, dono) a partir do feedback
ALTER TABLE transactions ADD CONSTRAINT uk_transactions_id_user UNIQUE (id, user_id);

-- ---------------------------------------------------------------- estado de classificação da transação
-- NONE       nunca passou pelo motor (inclui todas as transações anteriores ao M4)
-- AUTO       o motor aplicou categoria e/ou contexto sozinho (confiança alta)
-- SUGGESTED  o motor só sugeriu (confiança média): a categoria e o contexto continuam vazios até o usuário confirmar
-- CONFIRMED  o usuário decidiu (confirmou a sugestão ou corrigiu)
ALTER TABLE transactions
    ADD COLUMN classification_status     varchar(10)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN classification_source     varchar(20),
    ADD COLUMN classification_confidence numeric(3,2),
    ADD COLUMN suggested_category_id     uuid REFERENCES transaction_categories (id),
    ADD COLUMN suggested_context_id      uuid REFERENCES transaction_contexts (id);

ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_class_status CHECK (classification_status IN ('NONE', 'AUTO', 'SUGGESTED', 'CONFIRMED')),
    ADD CONSTRAINT ck_transactions_class_source CHECK (
        classification_source IS NULL OR classification_source IN ('USER', 'RULE', 'DEFAULT_RULE', 'RECIPIENT')),
    ADD CONSTRAINT ck_transactions_class_confidence CHECK (
        classification_confidence IS NULL OR (classification_confidence >= 0 AND classification_confidence <= 1)),
    -- NONE não tem origem nem confiança; AUTO e SUGGESTED sempre têm, e não vêm do usuário; CONFIRMED vem do usuário
    ADD CONSTRAINT ck_transactions_class_shape CHECK (
        CASE classification_status
            WHEN 'NONE'      THEN classification_source IS NULL AND classification_confidence IS NULL
            WHEN 'AUTO'      THEN classification_source IS NOT NULL AND classification_source <> 'USER'
                                  AND classification_confidence IS NOT NULL
            WHEN 'SUGGESTED' THEN classification_source IS NOT NULL AND classification_source <> 'USER'
                                  AND classification_confidence IS NOT NULL
            ELSE classification_source = 'USER'
        END),
    -- a sugestão só existe no estado SUGGESTED, tem ao menos um dos dois, e nunca cobre um campo já preenchido
    ADD CONSTRAINT ck_transactions_suggestion CHECK (
        CASE WHEN classification_status = 'SUGGESTED'
            THEN (suggested_category_id IS NOT NULL OR suggested_context_id IS NOT NULL)
                 AND (suggested_category_id IS NULL OR category_id IS NULL)
                 AND (suggested_context_id IS NULL OR context_id IS NULL)
            ELSE suggested_category_id IS NULL AND suggested_context_id IS NULL
        END);

-- fila de revisão: sugeridas e sem categoria
CREATE INDEX ix_transactions_review ON transactions (user_id, occurred_at DESC, id DESC)
    WHERE deleted_at IS NULL AND (classification_status = 'SUGGESTED' OR category_id IS NULL);

-- a sugestão também precisa ser padrão do sistema ou do mesmo dono (reaproveita a função da V5)
CREATE FUNCTION check_transaction_suggestion_refs() RETURNS trigger AS $$
BEGIN
    IF NEW.suggested_category_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_categories c
        WHERE c.id = NEW.suggested_category_id AND c.user_id IS NOT NULL AND c.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'categoria sugerida pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    IF NEW.suggested_context_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_contexts x
        WHERE x.id = NEW.suggested_context_id AND x.user_id IS NOT NULL AND x.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'contexto sugerido pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transaction_suggestion_refs
    BEFORE INSERT OR UPDATE OF suggested_category_id, suggested_context_id, user_id ON transactions
    FOR EACH ROW EXECUTE FUNCTION check_transaction_suggestion_refs();

-- ---------------------------------------------------------------- favorecidos PIX (RF-08)
-- O nome de uma pessoa é dado pessoal de terceiro (ADR-018): fica só por usuário, some com o usuário e pode ser esquecido.
-- A confiança NÃO é guardada: é calculada dos votos (ADR-017), então nunca fica desatualizada.
CREATE TABLE pix_recipients (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    normalized_name     varchar(120)  NOT NULL
                        CONSTRAINT ck_pix_recipients_name CHECK (length(normalized_name) >= 3),
    display_name        varchar(120)  NOT NULL,
    -- escolha explícita do usuário: vale como regra (confiança 1,0) e prevalece sobre os votos
    pinned_category_id  uuid          REFERENCES transaction_categories (id),
    pinned_context_id   uuid          REFERENCES transaction_contexts (id),
    transaction_count   integer       NOT NULL DEFAULT 0 CONSTRAINT ck_pix_recipients_count CHECK (transaction_count >= 0),
    average_amount      numeric(14,2) CONSTRAINT ck_pix_recipients_avg CHECK (average_amount IS NULL OR average_amount >= 0),
    last_transaction_at timestamptz,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uk_pix_recipients_name UNIQUE (user_id, normalized_name),
    CONSTRAINT uk_pix_recipients_id_user UNIQUE (id, user_id)
);

CREATE INDEX ix_pix_recipients_user ON pix_recipients (user_id, last_transaction_at DESC NULLS LAST);

-- votos de categoria e de contexto, separados (as duas dimensões são independentes, ADR-009)
CREATE TABLE pix_recipient_category_votes (
    recipient_id uuid    NOT NULL REFERENCES pix_recipients (id) ON DELETE CASCADE,
    category_id  uuid    NOT NULL REFERENCES transaction_categories (id),
    votes        integer NOT NULL CONSTRAINT ck_pix_cat_votes CHECK (votes > 0),
    PRIMARY KEY (recipient_id, category_id)
);

CREATE TABLE pix_recipient_context_votes (
    recipient_id uuid    NOT NULL REFERENCES pix_recipients (id) ON DELETE CASCADE,
    context_id   uuid    NOT NULL REFERENCES transaction_contexts (id),
    votes        integer NOT NULL CONSTRAINT ck_pix_ctx_votes CHECK (votes > 0),
    PRIMARY KEY (recipient_id, context_id)
);

-- categoria e contexto de favorecido, fixados ou votados, precisam ser padrão ou do dono do favorecido
CREATE FUNCTION check_recipient_refs() RETURNS trigger AS $$
BEGIN
    IF NEW.pinned_category_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_categories c
        WHERE c.id = NEW.pinned_category_id AND c.user_id IS NOT NULL AND c.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'categoria pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    IF NEW.pinned_context_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transaction_contexts x
        WHERE x.id = NEW.pinned_context_id AND x.user_id IS NOT NULL AND x.user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'contexto pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pix_recipient_refs
    BEFORE INSERT OR UPDATE OF pinned_category_id, pinned_context_id, user_id ON pix_recipients
    FOR EACH ROW EXECUTE FUNCTION check_recipient_refs();

CREATE FUNCTION check_recipient_vote_category() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pix_recipients r JOIN transaction_categories c ON c.id = NEW.category_id
        WHERE r.id = NEW.recipient_id AND c.user_id IS NOT NULL AND c.user_id <> r.user_id) THEN
        RAISE EXCEPTION 'categoria pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pix_cat_votes_refs
    BEFORE INSERT OR UPDATE OF category_id, recipient_id ON pix_recipient_category_votes
    FOR EACH ROW EXECUTE FUNCTION check_recipient_vote_category();

CREATE FUNCTION check_recipient_vote_context() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pix_recipients r JOIN transaction_contexts x ON x.id = NEW.context_id
        WHERE r.id = NEW.recipient_id AND x.user_id IS NOT NULL AND x.user_id <> r.user_id) THEN
        RAISE EXCEPTION 'contexto pertence a outro usuário' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pix_ctx_votes_refs
    BEFORE INSERT OR UPDATE OF context_id, recipient_id ON pix_recipient_context_votes
    FOR EACH ROW EXECUTE FUNCTION check_recipient_vote_context();

-- ---------------------------------------------------------------- regras do usuário (RF-09)
-- O padrão é comparado com o texto da descrição normalizado (minúsculas, sem acento, só letras e números).
CREATE TABLE categorization_rules (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    match_type       varchar(12)  NOT NULL
                     CONSTRAINT ck_rules_match CHECK (match_type IN ('CONTAINS', 'STARTS_WITH', 'EQUALS')),
    pattern          varchar(120) NOT NULL CONSTRAINT ck_rules_pattern CHECK (length(pattern) >= 3),
    transaction_type varchar(20)  CONSTRAINT ck_rules_type CHECK (transaction_type IN ('INCOME', 'EXPENSE')),
    category_id      uuid         REFERENCES transaction_categories (id),
    context_id       uuid         REFERENCES transaction_contexts (id),
    priority         integer      NOT NULL DEFAULT 100 CONSTRAINT ck_rules_priority CHECK (priority BETWEEN 0 AND 10000),
    enabled          boolean      NOT NULL DEFAULT true,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    -- uma regra decide ao menos uma das duas dimensões
    CONSTRAINT ck_rules_target CHECK (category_id IS NOT NULL OR context_id IS NOT NULL)
);

-- a mesma condição não se repete para o mesmo usuário
CREATE UNIQUE INDEX uk_rules_condition ON categorization_rules (user_id, match_type, pattern, COALESCE(transaction_type, ''));
CREATE INDEX ix_rules_user_priority ON categorization_rules (user_id, priority, created_at) WHERE enabled;

-- reaproveita a função da V5 (usa NEW.category_id, NEW.context_id e NEW.user_id)
CREATE TRIGGER trg_rule_refs
    BEFORE INSERT OR UPDATE OF category_id, context_id, user_id ON categorization_rules
    FOR EACH ROW EXECUTE FUNCTION check_transaction_refs();

-- ---------------------------------------------------------------- feedback (RF-10)
-- CONFIRMED: o usuário aceitou a sugestão. CORRECTED: trocou categoria e/ou contexto. É o dataset do M6 (ML).
CREATE TABLE classification_feedback (
    id                    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    transaction_id        uuid         NOT NULL,
    kind                  varchar(10)  NOT NULL CONSTRAINT ck_feedback_kind CHECK (kind IN ('CONFIRMED', 'CORRECTED')),
    previous_category_id  uuid         REFERENCES transaction_categories (id),
    corrected_category_id uuid         REFERENCES transaction_categories (id),
    previous_context_id   uuid         REFERENCES transaction_contexts (id),
    corrected_context_id  uuid         REFERENCES transaction_contexts (id),
    prediction_source     varchar(20)  CONSTRAINT ck_feedback_source CHECK (
                          prediction_source IS NULL OR prediction_source IN ('RULE', 'DEFAULT_RULE', 'RECIPIENT')),
    prediction_confidence numeric(3,2) CONSTRAINT ck_feedback_conf CHECK (
                          prediction_confidence IS NULL OR (prediction_confidence >= 0 AND prediction_confidence <= 1)),
    model_version         varchar(40)  NOT NULL,
    reverted_at           timestamptz,                 -- o aprendizado foi desfeito (importação desfeita)
    created_at            timestamptz  NOT NULL DEFAULT now(),
    -- a transação é do mesmo usuário
    CONSTRAINT fk_feedback_transaction FOREIGN KEY (transaction_id, user_id)
        REFERENCES transactions (id, user_id) ON DELETE CASCADE,
    -- uma correção muda alguma coisa; uma confirmação decide alguma coisa
    CONSTRAINT ck_feedback_shape CHECK (
        CASE kind
            WHEN 'CORRECTED' THEN previous_category_id IS DISTINCT FROM corrected_category_id
                                  OR previous_context_id IS DISTINCT FROM corrected_context_id
            ELSE corrected_category_id IS NOT NULL OR corrected_context_id IS NOT NULL
        END)
);

CREATE INDEX ix_feedback_user_created ON classification_feedback (user_id, created_at DESC);
CREATE INDEX ix_feedback_transaction ON classification_feedback (transaction_id);

CREATE FUNCTION check_feedback_refs() RETURNS trigger AS $$
DECLARE
    cat uuid;
    ctx uuid;
BEGIN
    FOREACH cat IN ARRAY ARRAY[NEW.previous_category_id, NEW.corrected_category_id] LOOP
        IF cat IS NOT NULL AND EXISTS (
            SELECT 1 FROM transaction_categories c WHERE c.id = cat AND c.user_id IS NOT NULL AND c.user_id <> NEW.user_id) THEN
            RAISE EXCEPTION 'categoria pertence a outro usuário' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH ctx IN ARRAY ARRAY[NEW.previous_context_id, NEW.corrected_context_id] LOOP
        IF ctx IS NOT NULL AND EXISTS (
            SELECT 1 FROM transaction_contexts x WHERE x.id = ctx AND x.user_id IS NOT NULL AND x.user_id <> NEW.user_id) THEN
            RAISE EXCEPTION 'contexto pertence a outro usuário' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_feedback_refs
    BEFORE INSERT OR UPDATE ON classification_feedback
    FOR EACH ROW EXECUTE FUNCTION check_feedback_refs();
