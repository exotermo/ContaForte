-- V8: favorecido aprende por direção (Milestone 4; ADR-017).
-- Quem recebe de você e quem te paga de volta é o mesmo favorecido, mas o que você paga a ele e o que ele te paga são
-- categorias diferentes: os votos passam a ser por direção (OUT = despesa, IN = receita). A escolha fixa do usuário
-- (pinned_*) sai: vira uma regra com filtro de tipo (categorization_rules), que já distingue receita de despesa.
-- Nenhum código gravava nestas tabelas antes desta versão.

DROP TRIGGER trg_pix_recipient_refs ON pix_recipients;
DROP FUNCTION check_recipient_refs();
ALTER TABLE pix_recipients DROP COLUMN pinned_category_id, DROP COLUMN pinned_context_id;

ALTER TABLE pix_recipient_category_votes
    ADD COLUMN direction varchar(3) NOT NULL DEFAULT 'OUT'
        CONSTRAINT ck_pix_cat_votes_direction CHECK (direction IN ('IN', 'OUT'));
ALTER TABLE pix_recipient_category_votes DROP CONSTRAINT pix_recipient_category_votes_pkey;
ALTER TABLE pix_recipient_category_votes ADD PRIMARY KEY (recipient_id, direction, category_id);
ALTER TABLE pix_recipient_category_votes ALTER COLUMN direction DROP DEFAULT;

ALTER TABLE pix_recipient_context_votes
    ADD COLUMN direction varchar(3) NOT NULL DEFAULT 'OUT'
        CONSTRAINT ck_pix_ctx_votes_direction CHECK (direction IN ('IN', 'OUT'));
ALTER TABLE pix_recipient_context_votes DROP CONSTRAINT pix_recipient_context_votes_pkey;
ALTER TABLE pix_recipient_context_votes ADD PRIMARY KEY (recipient_id, direction, context_id);
ALTER TABLE pix_recipient_context_votes ALTER COLUMN direction DROP DEFAULT;
