-- V9: classificação dentro da prévia da importação (Milestone 4; ADR-017).
-- Cada linha nova ou possível duplicata guarda o que o motor decidiu (alvo e decisão de categoria e de contexto,
-- origem e confiança) e, se o usuário trocou, a escolha dele. Na confirmação isso vira o estado da transação criada.
-- Só ids e rótulos: nenhum texto do favorecido além do que a linha já guarda (description).

ALTER TABLE import_items
    ADD COLUMN engine_category_id       uuid REFERENCES transaction_categories (id),
    ADD COLUMN engine_category_decision varchar(7),
    ADD COLUMN engine_context_id        uuid REFERENCES transaction_contexts (id),
    ADD COLUMN engine_context_decision  varchar(7),
    ADD COLUMN engine_decision          varchar(7),     -- resumo: o elo mais fraco (AUTO ou SUGGEST); nulo = sem sugestão
    ADD COLUMN engine_source            varchar(20),
    ADD COLUMN engine_confidence        numeric(3,2),
    ADD COLUMN chosen                   boolean NOT NULL DEFAULT false,
    ADD COLUMN chosen_category_id       uuid REFERENCES transaction_categories (id),
    ADD COLUMN chosen_context_id        uuid REFERENCES transaction_contexts (id);

ALTER TABLE import_items
    ADD CONSTRAINT ck_import_items_engine_decisions CHECK (
        (engine_category_decision IS NULL OR engine_category_decision IN ('AUTO', 'SUGGEST'))
        AND (engine_context_decision IS NULL OR engine_context_decision IN ('AUTO', 'SUGGEST'))
        AND (engine_decision IS NULL OR engine_decision IN ('AUTO', 'SUGGEST'))),
    -- alvo e decisão andam juntos, por dimensão
    ADD CONSTRAINT ck_import_items_engine_parts CHECK (
        (engine_category_id IS NULL) = (engine_category_decision IS NULL)
        AND (engine_context_id IS NULL) = (engine_context_decision IS NULL)),
    -- sem nenhum alvo, não há resumo, origem nem confiança; com algum alvo, há os três
    ADD CONSTRAINT ck_import_items_engine_shape CHECK (
        (engine_decision IS NULL) = (engine_category_id IS NULL AND engine_context_id IS NULL)
        AND (engine_decision IS NULL) = (engine_source IS NULL)
        AND (engine_decision IS NULL) = (engine_confidence IS NULL)),
    ADD CONSTRAINT ck_import_items_engine_source CHECK (
        engine_source IS NULL OR engine_source IN ('RULE', 'DEFAULT_RULE', 'RECIPIENT')),
    ADD CONSTRAINT ck_import_items_engine_confidence CHECK (
        engine_confidence IS NULL OR (engine_confidence >= 0 AND engine_confidence <= 1)),
    -- o resumo é SUGGEST exatamente quando alguma dimensão só sugeriu
    ADD CONSTRAINT ck_import_items_engine_summary CHECK (
        COALESCE(engine_decision = 'SUGGEST', false)
        = (COALESCE(engine_category_decision = 'SUGGEST', false) OR COALESCE(engine_context_decision = 'SUGGEST', false))),
    -- a escolha do usuário só existe quando há escolha
    ADD CONSTRAINT ck_import_items_chosen CHECK (chosen OR (chosen_category_id IS NULL AND chosen_context_id IS NULL));

-- categorias e contextos da linha precisam ser padrão do sistema ou do dono da importação
CREATE FUNCTION check_import_item_refs() RETURNS trigger AS $$
DECLARE
    dono uuid;
    cat  uuid;
    ctx  uuid;
BEGIN
    SELECT user_id INTO dono FROM imports WHERE id = NEW.import_id;
    FOREACH cat IN ARRAY ARRAY[NEW.engine_category_id, NEW.chosen_category_id] LOOP
        IF cat IS NOT NULL AND EXISTS (
            SELECT 1 FROM transaction_categories c WHERE c.id = cat AND c.user_id IS NOT NULL AND c.user_id <> dono) THEN
            RAISE EXCEPTION 'categoria pertence a outro usuário' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH ctx IN ARRAY ARRAY[NEW.engine_context_id, NEW.chosen_context_id] LOOP
        IF ctx IS NOT NULL AND EXISTS (
            SELECT 1 FROM transaction_contexts x WHERE x.id = ctx AND x.user_id IS NOT NULL AND x.user_id <> dono) THEN
            RAISE EXCEPTION 'contexto pertence a outro usuário' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- WHEN: a função (que consulta imports) só roda quando há algum id a conferir. Sem isso, cada linha inserida ou
-- atualizada pagava uma consulta à toa: a prévia de 20 mil linhas ficava segundos mais lenta.
CREATE TRIGGER trg_import_item_refs
    BEFORE INSERT OR UPDATE OF engine_category_id, engine_context_id, chosen_category_id, chosen_context_id ON import_items
    FOR EACH ROW
    WHEN (NEW.engine_category_id IS NOT NULL OR NEW.engine_context_id IS NOT NULL
          OR NEW.chosen_category_id IS NOT NULL OR NEW.chosen_context_id IS NOT NULL)
    EXECUTE FUNCTION check_import_item_refs();

-- mesma razão para o gatilho da V7 em transactions: só confere quando há sugestão
DROP TRIGGER trg_transaction_suggestion_refs ON transactions;
CREATE TRIGGER trg_transaction_suggestion_refs
    BEFORE INSERT OR UPDATE OF suggested_category_id, suggested_context_id, user_id ON transactions
    FOR EACH ROW
    WHEN (NEW.suggested_category_id IS NOT NULL OR NEW.suggested_context_id IS NOT NULL)
    EXECUTE FUNCTION check_transaction_suggestion_refs();
